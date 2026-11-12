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
 * Site previews (#2180) are built from a PR, a fork's included, and deployed with the Cloudflare
 * token, so the two halves run apart. The build ({@code preview.yml}) runs the PR's own code and
 * holds no secret. The deploy ({@code preview-deploy.yml}) and the cleanup ({@code
 * preview-cleanup.yml}) hold the token and run nothing of the PR: no checkout, no local action, no
 * {@code ${{ }}} spliced into a shell or a script. The deploy takes the PR from the API by the run's
 * head repository and SHA, and only a collaborator's. Their header comments state why; this holds it.
 */
class PreviewWorkflowTest {

    private static final Path WORKFLOWS = Path.of("..").resolve(".github/workflows");
    private static final Path BUILD = WORKFLOWS.resolve("preview.yml");
    private static final Path DEPLOY = WORKFLOWS.resolve("preview-deploy.yml");
    private static final Path CLEANUP = WORKFLOWS.resolve("preview-cleanup.yml");

    private static final Pattern SECRET_REFERENCE = Pattern.compile("secrets\\.[A-Za-z0-9_]+");
    private static final Set<String> CLOUDFLARE = Set.of("secrets.CLOUDFLARE_API_TOKEN", "secrets.CLOUDFLARE_ACCOUNT_ID");

    private static JqValue workflow(Path file) throws IOException {
        assertTrue(Files.exists(file), "no workflow " + file);
        return JqYaml.parse(Files.readString(file));
    }

    private static List<JqValue> steps(Path file) throws IOException {
        var jobs = workflow(file).path("jobs").asMap();
        assertEquals(1, jobs.size(), file.getFileName() + " has one job");
        var steps = jobs.values().iterator().next().path("steps").asList();
        assertFalse(steps.isEmpty(), file.getFileName() + " has no steps");
        return steps;
    }

    private static JqValue job(Path file) throws IOException {
        return workflow(file).path("jobs").asMap().values().iterator().next();
    }

    private static JqValue step(Path file, String name) throws IOException {
        return steps(file).stream().filter(s -> s.pathText("name", "").equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(file.getFileName() + " has no step " + name));
    }

    private static Set<String> secretReferences(Path file) throws IOException {
        var found = new TreeSet<String>();
        var m = SECRET_REFERENCE.matcher(Files.readString(file));
        while (m.find()) found.add(m.group());
        return found;
    }

    private static Map<String, String> permissions(Path file) throws IOException {
        var result = new java.util.TreeMap<String, String>();
        workflow(file).path("permissions").asMap().forEach((k, v) -> result.put(k, v.asText()));
        return result;
    }

    // --- the build: the PR's code, no secret ---

    @Test
    void theBuildRunsOnPullRequestWithNoSecretAndReadOnlyContents() throws IOException {
        assertEquals(Set.of("pull_request"), workflow(BUILD).path("on").asMap().keySet(),
                "preview.yml runs on pull_request alone: it runs the PR's site/build.sh, never beside a secret");
        assertEquals(Set.of(), secretReferences(BUILD), "secrets preview.yml references");
        assertEquals(Map.of("contents", "read"), permissions(BUILD), "preview.yml's permissions");
        assertTrue(job(BUILD).pathText("if", "").isEmpty(),
                "preview.yml builds every PR, a fork's included: its job has no if");
    }

    @Test
    void theBuildsCheckoutKeepsNoCredentialAndItUploadsTheSite() throws IOException {
        var checkouts = steps(BUILD).stream()
                .filter(s -> s.pathText("uses", "").startsWith("actions/checkout@")).toList();
        assertEquals(1, checkouts.size(), "preview.yml checks out once");
        var with = checkouts.getFirst().path("with");
        assertTrue(with.has("persist-credentials") && !with.path("persist-credentials").asBoolean(true),
                "preview.yml's checkout keeps the token in .git/config: persist-credentials is not false");
        var upload = step(BUILD, "Upload site").path("with");
        assertEquals("site-preview", upload.pathText("name", ""), "the artifact preview-deploy.yml downloads");
        assertEquals("_site", upload.pathText("path", ""), "what preview.yml uploads");
        var download = step(DEPLOY, "Download site").path("with");
        assertEquals(upload.pathText("name", ""), download.pathText("name", ""),
                "preview-deploy.yml downloads the artifact preview.yml uploads");
    }

    // --- the deploy and the cleanup: the token, nothing of the PR ---

    @Test
    void theDeployRunsOnTheBuildsWorkflowRunAlone() throws IOException {
        var on = workflow(DEPLOY).path("on");
        assertEquals(Set.of("workflow_run"), on.asMap().keySet(),
                "preview-deploy.yml runs on workflow_run alone: main's copy, never the PR's");
        assertEquals(List.of(workflow(BUILD).pathText("name", "")), texts(on.path("workflow_run").path("workflows")),
                "preview-deploy.yml follows preview.yml by its name");
        assertEquals(List.of("completed"), texts(on.path("workflow_run").path("types")), "workflow_run types");
        var condition = job(DEPLOY).pathText("if", "");
        assertTrue(condition.contains("github.event.workflow_run.event == 'pull_request'"),
                "the deploy follows only a pull_request run: " + condition);
        assertTrue(condition.contains("github.event.workflow_run.conclusion == 'success'"),
                "the deploy follows only a successful build: " + condition);
        assertEquals(Map.of("actions", "read", "pull-requests", "write"), permissions(DEPLOY),
                "preview-deploy.yml's permissions");
    }

    @Test
    void theCleanupRunsOnPullRequestTargetClosed() throws IOException {
        var on = workflow(CLEANUP).path("on");
        assertEquals(Set.of("pull_request_target"), on.asMap().keySet(),
                "preview-cleanup.yml runs on pull_request_target alone, so a fork's PR has the token to delete");
        assertEquals(List.of("closed"), texts(on.path("pull_request_target").path("types")), "cleanup types");
        assertEquals(Map.of("contents", "read"), permissions(CLEANUP), "preview-cleanup.yml's permissions");
    }

    @Test
    void onlyTheDeployAndTheCleanupHoldTheCloudflareToken() throws IOException {
        assertEquals(CLOUDFLARE, secretReferences(DEPLOY), "secrets preview-deploy.yml references");
        assertEquals(CLOUDFLARE, secretReferences(CLEANUP), "secrets preview-cleanup.yml references");
    }

    /**
     * A checkout would put the PR's tree beside the token, and a local {@code uses: ./} would run the
     * default branch's action now but is one edit from running the PR's. Neither has a place here.
     */
    @Test
    void theCredentialedWorkflowsCheckOutNothingAndUseNoLocalAction() throws IOException {
        var offenders = new ArrayList<String>();
        for (var file : List.of(DEPLOY, CLEANUP)) {
            for (var step : steps(file)) {
                var uses = step.pathText("uses", "");
                if (uses.startsWith("actions/checkout@") || uses.startsWith("./")) {
                    offenders.add(file.getFileName() + ": " + step.pathText("name", "<unnamed>") + " uses " + uses);
                }
            }
        }
        assertEquals(List.of(), offenders, "steps that check out or run a local action beside the Cloudflare token");
    }

    /**
     * A {@code ${{ }}} in {@code run:} is spliced into the shell, and in a github-script
     * {@code script:} into the JavaScript, before either runs: the PR's branch name there would be
     * code. Through {@code env:} it is a value.
     */
    @Test
    void noCredentialedStepExpandsAnExpressionInsideItsCode() throws IOException {
        var offenders = new ArrayList<String>();
        for (var file : List.of(DEPLOY, CLEANUP)) {
            for (var step : steps(file)) {
                if (step.pathText("run", "").contains("${{") || step.path("with").pathText("script", "").contains("${{")) {
                    offenders.add(file.getFileName() + ": " + step.pathText("name", "<unnamed>"));
                }
            }
        }
        assertEquals(List.of(), offenders, "steps whose run: or script: expands ${{ }} rather than reading env:");
    }

    @Test
    void theDeployTakesThePrFromTheApiByHeadRepositoryAndShaAndOnlyACollaborators() throws IOException {
        var find = step(DEPLOY, "Find the PR");
        assertEquals("pr", find.pathText("id", ""), "the step later steps gate on");
        var script = find.path("with").pathText("script", "");
        assertTrue(script.contains("p.head.repo.full_name === HEAD_REPO"), "the PR's head repository is the run's");
        assertTrue(script.contains("p.head.sha === HEAD_SHA"), "the PR's head is still the run's SHA");
        assertTrue(script.contains("['OWNER', 'MEMBER', 'COLLABORATOR']"), "the authors whose PRs are deployed");
        assertTrue(script.contains("pr.author_association"), "the author gate reads the PR's author_association");
        assertTrue(script.contains("pr.head.repo.owner.login === pr.user.login"),
                "the author vouches only for a head repository they own, or this repository");
        var env = find.path("env");
        assertEquals("${{ github.event.workflow_run.head_sha }}", env.pathText("HEAD_SHA", ""), "HEAD_SHA");
        assertEquals("${{ github.event.workflow_run.head_repository.full_name }}", env.pathText("HEAD_REPO", ""), "HEAD_REPO");

        var again = step(DEPLOY, "Check the PR again").path("with").pathText("script", "");
        assertTrue(again.contains("pr.state !== 'open'") && again.contains("pr.head.sha !== process.env.HEAD_SHA"),
                "the PR is checked open at the run's SHA just before the deploy");
    }

    @Test
    void theDeployChecksTheSiteFirstAndDeploysOnlyTheResolvedPr() throws IOException {
        var names = steps(DEPLOY).stream().map(s -> s.pathText("name", "")).toList();
        assertTrue(names.indexOf("Check the site is static files") >= 0
                        && names.indexOf("Check the PR again") >= 0
                        && names.indexOf("Check the site is static files") < names.indexOf("Deploy preview")
                        && names.indexOf("Check the PR again") < names.indexOf("Deploy preview"),
                "the site and the PR are checked before the deploy: " + names);
        var check = step(DEPLOY, "Check the site is static files").pathText("run", "");
        for (var refused : List.of("-type l", "_site/_worker.js", "_site/_routes.json", "_site/functions")) {
            assertTrue(check.contains(refused), "the static check refuses " + refused);
        }
        var deploy = step(DEPLOY, "Deploy preview");
        assertEquals("steps.still.outputs.open", deploy.pathText("if", ""), "the deploy runs only for a PR still open");
        assertTrue(deploy.path("with").pathText("command", "").endsWith("--branch=pr-${{ steps.pr.outputs.number }}"),
                "the preview branch is the number the API returned, never a string from the PR");
        for (var step : steps(DEPLOY)) {
            if (step.pathText("name", "").equals("Find the PR")) continue;
            assertFalse(step.pathText("if", "").isEmpty(), step.pathText("name", "") + " runs only for a resolved PR");
        }
    }

    /** The token belongs to these two workflows and the production deploy; no other names it. */
    @Test
    void noOtherWorkflowNamesTheCloudflareToken() throws IOException {
        var offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            for (var file : files.sorted().toList()) {
                var name = file.getFileName().toString();
                if (Set.of("preview-deploy.yml", "preview-cleanup.yml", "pages.yml").contains(name)) continue;
                if (Files.readString(file).contains("CLOUDFLARE_")) offenders.add(name);
            }
        }
        assertEquals(List.of(), offenders, "workflows naming CLOUDFLARE_* beside the deploys");
    }

    private static List<String> texts(JqValue value) {
        return value.asList().stream().map(JqValue::asText).toList();
    }
}
