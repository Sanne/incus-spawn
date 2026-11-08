package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The upstream watch (#2036) runs with a GitHub App's key that can push to this repository and open
 * PRs the bot then merges, and its verify check decides what the bot may merge. Both workflows state
 * their invariants in their header comments; this holds them. The watch: never on a PR, issue or
 * comment event, on main of {@code Sanne/incus-spawn} alone, the app's secrets read by exactly one
 * step that downloads nothing, no {@code ${{ }}} in a {@code run:} line, a checkout that keeps no
 * credential. The verify check: {@code pull_request_target} running main's engine with a read-only
 * token and no secret, nothing of the PR executed. And the secret reaches no other workflow.
 */
class UpstreamWorkflowTest {

    private static final Path ROOT = Path.of("..");
    private static final Path WORKFLOWS = ROOT.resolve(".github/workflows");
    private static final Path WATCH = WORKFLOWS.resolve("upstream-watch.yml");
    private static final Path VERIFY = WORKFLOWS.resolve("upstream-verify.yml");
    private static final Path MAIN = WORKFLOWS.resolve("test-integration.yml");

    private static final Pattern SECRET_REFERENCE = Pattern.compile("secrets\\.[A-Za-z0-9_]+");
    /** What the credentialed step must not do: fetch from an upstream or run the planner. */
    private static final Pattern FETCHES = Pattern.compile("\\bcurl\\b|\\bgh api\\b|\\bgh release\\b|upstream\\.sh plan\\b");
    private static final String APP_ID = "secrets.UPSTREAM_APP_ID";
    private static final String APP_KEY = "secrets.UPSTREAM_APP_PRIVATE_KEY";

    private static JqValue workflow(Path file) throws IOException {
        assertTrue(Files.exists(file), "no workflow " + file);
        return JqYaml.parse(Files.readString(file));
    }

    private static JqValue job(Path file, String name) throws IOException {
        var job = workflow(file).path("jobs").path(name);
        assertFalse(job.isNull(), file + " has no job " + name);
        return job;
    }

    private static List<JqValue> steps(Path file, String job) throws IOException {
        var steps = job(file, job).path("steps").asList();
        assertFalse(steps.isEmpty(), job + " has no steps");
        return steps;
    }

    private static JqValue checkout(Path file, String job) throws IOException {
        var checkouts = steps(file, job).stream()
                .filter(step -> step.pathText("uses", "").startsWith("actions/checkout@")).toList();
        assertEquals(1, checkouts.size(), job + " checks out once");
        return checkouts.getFirst();
    }

    private static void assertKeepsNoCredential(JqValue checkout, String where) {
        var with = checkout.path("with");
        assertTrue(with.has("persist-credentials") && !with.path("persist-credentials").asBoolean(true),
                where + "'s checkout keeps the token in .git/config: persist-credentials is not false");
    }

    private static Set<String> secretReferences(Path file) throws IOException {
        var found = new TreeSet<String>();
        var m = SECRET_REFERENCE.matcher(Files.readString(file));
        while (m.find()) found.add(m.group());
        return found;
    }

    // --- upstream-watch.yml ---

    @Test
    void theWatchRunsOnlyOnItsScheduleOrByHand() throws IOException {
        assertEquals(Set.of("schedule", "workflow_dispatch"), workflow(WATCH).path("on").asMap().keySet(),
                "upstream-watch.yml triggers: a PR, issue or comment event would hand its environment to a stranger");
    }

    /**
     * Two jobs, so two runners: the one that downloads and untars publisher data never has the
     * environment's secrets in reach (steps of one job share a runner, and their order is not
     * isolation). Only the publish job runs in the environment, and it needs the planning job.
     */
    @Test
    void thePlanAndPublishJobsRunOnMainOfThisRepositoryAndOnlyPublishInTheEnvironment() throws IOException {
        assertEquals(Set.of("plan", "publish", "track"), workflow(WATCH).path("jobs").asMap().keySet(), "upstream-watch.yml's jobs");
        for (var name : List.of("plan", "publish")) {
            var condition = job(WATCH, name).pathText("if", "");
            assertTrue(condition.contains("github.repository == 'Sanne/incus-spawn'"), name + "'s if names the repository: " + condition);
            assertTrue(condition.contains("github.ref == 'refs/heads/main'"), name + "'s if names main: " + condition);
        }
        assertEquals("", job(WATCH, "plan").pathText("environment", ""), "the planning job, which downloads, runs in no environment");
        assertEquals("upstream-watch", job(WATCH, "publish").pathText("environment", ""),
                "the publish job runs in the upstream-watch environment, whose branch policy admits main alone");
        assertEquals("plan", job(WATCH, "publish").pathText("needs", ""), "publish takes the plan job's artifact");
    }

    @Test
    void theWatchHasReadContentsAndWriteIssuesAndNothingMore() throws IOException {
        var permissions = workflow(WATCH).path("permissions");
        assertTrue(permissions.isObject(), "upstream-watch.yml sets top-level permissions");
        var actual = new java.util.TreeMap<String, String>();
        permissions.asMap().forEach((scope, level) -> actual.put(scope, level.asText()));
        assertEquals(Map.of("contents", "read", "issues", "write"), actual,
                "the run's own token pushes nothing: the app's token does, from one step");
    }

    @Test
    void theWatchsCheckoutsKeepNoCredential() throws IOException {
        assertKeepsNoCredential(checkout(WATCH, "plan"), "upstream-watch.yml plan");
        assertKeepsNoCredential(checkout(WATCH, "publish"), "upstream-watch.yml publish");
    }

    /**
     * An upstream's release name, a PR title or a dispatch input in a {@code ${{ }}} inside a
     * {@code run:} is spliced into the shell before it runs; through {@code env:} it is a quoted
     * variable. Every value a step uses comes in that way.
     */
    @Test
    void noWatchStepExpandsAnExpressionInsideItsShell() throws IOException {
        var offenders = new ArrayList<String>();
        for (var step : watchSteps()) {
            if (step.pathText("run", "").contains("${{")) offenders.add(step.pathText("name", "<unnamed>"));
        }
        assertEquals(List.of(), offenders, "steps whose run: expands ${{ }} rather than reading env:");
    }

    /**
     * The app's id and key are the only secrets, read by one step through its {@code env:}, and that
     * step fetches nothing from any upstream and never plans: a download that has the key in its
     * environment is the one thing the design forbids. It may mint a token and publish.
     */
    @Test
    void theAppsSecretsReachOneStepThatDownloadsNothing() throws IOException {
        assertEquals(Set.of(APP_ID, APP_KEY), secretReferences(WATCH), "secrets upstream-watch.yml references");
        JqValue holder = null;
        for (var step : steps(WATCH, "plan")) {
            for (var value : step.path("env").asMap().values()) {
                assertFalse(value.asText().contains("UPSTREAM_APP"), "the planning job's " + step.pathText("name", "<unnamed>") + " step reads an app secret");
            }
        }
        for (var step : steps(WATCH, "publish")) {
            var env = step.path("env");
            var reads = new ArrayList<String>();
            for (var value : env.asMap().values()) {
                if (value.asText().contains(APP_ID)) reads.add(APP_ID);
                if (value.asText().contains(APP_KEY)) reads.add(APP_KEY);
            }
            if (reads.isEmpty()) continue;
            assertEquals(List.of(APP_ID, APP_KEY), reads.stream().sorted().toList(),
                    step.pathText("name", "<unnamed>") + " reads one secret without the other");
            assertNull(holder, "a second step reads the app's secrets: " + step.pathText("name", "<unnamed>"));
            holder = step;
        }
        assertNotNull(holder, "no step's env: reads the app's secrets");
        var text = Files.readString(WATCH);
        for (var secret : List.of(APP_ID, APP_KEY)) {
            assertEquals(1, text.split(Pattern.quote(secret), -1).length - 1, secret + " is named once, in that step's env:");
        }
        var run = holder.pathText("run", "");
        assertFalse(FETCHES.matcher(run).find(), "the step holding the app's secrets fetches or plans:\n" + run);
        assertTrue(run.contains("app-token.sh") && run.contains("upstream.sh publish"),
                "the step holding the app's secrets mints its token and publishes:\n" + run);
    }

    private static List<JqValue> watchSteps() throws IOException {
        var all = new ArrayList<JqValue>();
        for (var name : List.of("plan", "publish", "track")) all.addAll(steps(WATCH, name));
        return all;
    }

    /**
     * The one "[upstream] watch failing" issue follows the run, not a job: a plan that passed must
     * not close it while the publish that failed is about to open it again, every six hours (the
     * review of #2047). So one job tracks, after both, from both results, and neither of them does.
     */
    @Test
    void oneJobTracksTheRunAfterBothOthers() throws IOException {
        var track = job(WATCH, "track");
        assertEquals(List.of("plan", "publish"), track.path("needs").asList().stream().map(JqValue::asText).sorted().toList(),
                "track runs after plan and publish");
        assertTrue(track.pathText("if", "").startsWith("always()"), "track runs whatever the two jobs did: " + track.pathText("if", ""));
        var status = "";
        for (var step : steps(WATCH, "track")) {
            if (step.pathText("run", "").contains("upstream.sh track")) status = step.path("env").pathText("STATUS", "");
        }
        assertTrue(status.contains("needs.plan.result == 'success'") && status.contains("needs.publish.result == 'success'"),
                "track's STATUS is success only when both jobs succeeded: " + status);
        for (var name : List.of("plan", "publish")) {
            for (var step : steps(WATCH, name)) {
                assertFalse(step.pathText("run", "").contains("upstream.sh track"), name + " tracks the run itself: " + step.pathText("name", ""));
            }
        }
    }

    @Test
    void theWatchRunsOneAtATime() throws IOException {
        var concurrency = workflow(WATCH).path("concurrency");
        assertFalse(concurrency.pathText("group", "").isEmpty(),
                "upstream-watch.yml sets a concurrency group: two runs would push the same branch");
    }

    // --- upstream-verify.yml ---

    @Test
    void theVerifyCheckRunsOnEveryEditOfAPullRequestFromMain() throws IOException {
        var on = workflow(VERIFY).path("on");
        assertEquals(Set.of("pull_request_target"), on.asMap().keySet(),
                "upstream-verify.yml runs on pull_request_target alone: the PR's own copy of the check must never run");
        var types = on.path("pull_request_target").path("types").asList().stream().map(JqValue::asText).toList();
        assertTrue(types.containsAll(List.of("opened", "synchronize", "reopened", "edited")),
                "every edit re-runs the check, a title or body change included: " + types);
    }

    @Test
    void theVerifyCheckHasOnlyReadPermissionsAndNoSecret() throws IOException {
        var permissions = workflow(VERIFY).path("permissions");
        assertTrue(permissions.isObject() && permissions.size() > 0, "upstream-verify.yml sets top-level permissions");
        permissions.asMap().forEach((scope, level) ->
                assertEquals("read", level.asText(), "upstream-verify.yml's " + scope + " permission"));
        assertEquals(Set.of(), secretReferences(VERIFY), "upstream-verify.yml references secrets");
    }

    @Test
    void theVerifyCheckRunsMainsEngineAndNothingOfThePullRequest() throws IOException {
        assertEquals(Set.of("upstream-verify"), workflow(VERIFY).path("jobs").asMap().keySet(), "upstream-verify.yml's jobs");
        var job = job(VERIFY, "upstream-verify");
        assertEquals("upstream-verify", job.pathText("name", ""), "the check's name, as branch protection requires it");
        var checkout = checkout(VERIFY, "upstream-verify");
        assertEquals("${{ github.event.repository.default_branch }}", checkout.path("with").pathText("ref", ""),
                "the checkout is the default branch, whatever the PR's base: main's engine runs, never the PR's");
        assertKeepsNoCredential(checkout, "upstream-verify.yml");
        var actions = new ArrayList<String>();
        for (var step : steps(VERIFY, "upstream-verify")) {
            var uses = step.pathText("uses", "");
            if (!uses.isEmpty() && !uses.startsWith("actions/checkout@")) actions.add(uses);
        }
        assertEquals(List.of(), actions, "upstream-verify.yml uses an action other than actions/checkout");
    }

    // --- the rest of CI ---

    /** The app's key is an environment secret of one job; a second workflow naming it would widen that. */
    @Test
    void noOtherWorkflowNamesTheAppsSecrets() throws IOException {
        var offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            for (var file : files.sorted().toList()) {
                if (file.getFileName().toString().equals("upstream-watch.yml")) continue;
                if (Files.readString(file).contains("UPSTREAM_APP_")) offenders.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of(), offenders, "workflows other than upstream-watch.yml naming UPSTREAM_APP_*");
    }

    /** The engine's fixture tests ({@code upstream/test/run.sh}) run on every PR, like the other scripts'. */
    @Test
    void theEnginesFixtureTestsRunInCi() throws IOException {
        var runs = new ArrayList<String>();
        for (var job : workflow(MAIN).path("jobs").asMap().values()) {
            for (var step : job.path("steps").asList()) runs.add(step.pathText("run", ""));
        }
        assertTrue(runs.stream().anyMatch(run -> run.contains("upstream/test/run.sh")),
                "test-integration.yml has no job running upstream/test/run.sh");
    }
}
