package dev.incusspawn;

import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The project's Linux server runs unit-tests for trusted code, in a fresh isx instance per job
 * (#2519). These hold the three parts: the workflow sends only unit-tests and the heartbeat there,
 * and never waits on a failed route; the instance's job-started hook refuses untrusted code from
 * its own list; and the host's supervisor gives each job a new instance and a just-in-time runner,
 * whose configuration never reaches the host's command line, and destroys both after the job.
 */
class LinuxRunnerTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");
    private static final Path RUNNER = Path.of("../.github/linux-runner").toAbsolutePath();
    private static final Path HOOK = RUNNER.resolve("job-started.sh");
    private static final Path SUPERVISOR = RUNNER.resolve("isx-ci-runner.sh");
    private static final Path DROP = RUNNER.resolve("drop-credentials.sh");
    private static final Path ROUTE = Path.of("../.github/scripts/runner-route.sh");
    private static final Path TRUSTED = Path.of("../.github/self-hosted/trusted-heads");

    private static final String ROUTED =
            "    runs-on: ${{ needs.linux-route.outputs.runner && fromJSON(needs.linux-route.outputs.runner) || 'ubuntu-26.04' }}";
    private static final String HOSTED_ONLY = "runner.environment == 'github-hosted'";

    static boolean reachesLinuxRunner(Path file, String runsOn) {
        return runsOn.contains("isx-linux") || runsOn.contains("linux-route")
                || (file.getFileName().toString().equals("linux-heartbeat.yml") && runsOn.contains("self-hosted"));
    }

    @Test
    void onlyUnitTestsAndTheHeartbeatReachTheLinuxRunner() throws IOException {
        var reaching = new ArrayList<String>();
        for (var file : MacRunnerWorkflowTest.workflows()) {
            for (var job : MacRunnerWorkflowTest.jobs(file).entrySet()) {
                var runsOn = MacRunnerWorkflowTest.runsOn(job.getValue());
                if (reachesLinuxRunner(file, runsOn)) {
                    reaching.add(file.getFileName() + " " + job.getKey() + ": " + runsOn.strip());
                }
            }
        }
        assertEquals(List.of(
                "linux-heartbeat.yml heartbeat: runs-on: [self-hosted, Linux, X64, isx-linux]",
                "test-integration.yml unit-tests: " + ROUTED.strip()), reaching);
    }

    @Test
    void theRouteListsExactlyTheWorkflowsWithAJobForTheLinuxRunner() throws IOException {
        var users = new TreeSet<String>();
        for (var file : MacRunnerWorkflowTest.workflows()) {
            for (var job : MacRunnerWorkflowTest.jobs(file).values()) {
                if (reachesLinuxRunner(file, MacRunnerWorkflowTest.runsOn(job))) {
                    users.add(file.getFileName().toString());
                }
            }
        }
        var m = Pattern.compile("(?m)^\\s*LINUX_WORKFLOWS=\"([^\"]*)\"").matcher(Files.readString(ROUTE));
        assertTrue(m.find(), "runner-route.sh declares LINUX_WORKFLOWS");
        assertEquals(users, new TreeSet<>(List.of(m.group(1).split(" "))));
    }

    /** The labels the supervisor registers are the ones the route names and the heartbeat asks for. */
    @Test
    void theRunnersLabelsAreOneList() throws IOException {
        var labels = Pattern.compile("(?m)^LABELS=\\(([^)]*)\\)$").matcher(Files.readString(SUPERVISOR));
        assertTrue(labels.find());
        var registered = List.of(labels.group(1).split(" "));
        assertEquals(List.of("self-hosted", "Linux", "X64", "isx-linux"), registered);
        assertTrue(Files.readString(ROUTE).contains("OWN='[\"" + String.join("\",\"", registered) + "\"]'"));
        assertTrue(Files.readString(WORKFLOWS.resolve("linux-heartbeat.yml"))
                .contains("runs-on: [" + String.join(", ", registered) + "]"));
    }

    /**
     * A route that fails must not hold unit-tests up, and must not make cancel-on-unit-failure,
     * which follows unit-tests' ancestors, cancel the run.
     */
    @Test
    void unitTestsRunsOnGitHubsRunnerWhenTheRouteFails() throws IOException {
        var jobs = MacRunnerWorkflowTest.jobs(WORKFLOWS.resolve("test-integration.yml"));
        var route = jobs.get("linux-route");
        assertTrue(route.contains("    continue-on-error: true"));
        assertTrue(route.contains("    runs-on: ubuntu-26.04"));
        var unit = jobs.get("unit-tests");
        assertTrue(unit.contains("    needs: [linux-route]"));
        assertTrue(unit.contains(ROUTED));
        assertTrue(unit.stream().anyMatch(l -> l.startsWith("    if: ${{ !cancelled() && ")), "unit-tests runs whatever the route did");
    }

    /** What the template bakes in, unit-tests downloads only on GitHub's runner. */
    @Test
    void theLinuxRunnerSkipsTheDownloadsItsTemplateBakesIn() throws IOException {
        var parsed = JqYaml.parse(Files.readString(WORKFLOWS.resolve("test-integration.yml")));
        var hostedOnly = new ArrayList<String>();
        for (var step : parsed.path("jobs").path("unit-tests").path("steps").asList()) {
            var uses = step.pathText("uses", "");
            var run = step.pathText("run", "");
            if (uses.startsWith("graalvm/setup-graalvm@") || uses.startsWith("actions/cache@") || run.contains("apt-install.sh")) {
                assertEquals(HOSTED_ONLY, step.pathText("if", ""), step.pathText("name", ""));
                hostedOnly.add(step.pathText("name", ""));
            }
        }
        assertEquals(3, hostedOnly.size(), hostedOnly.toString());
    }

    @Test
    void theHeartbeatRunsNoCodeOfAPullRequest() throws IOException {
        var file = WORKFLOWS.resolve("linux-heartbeat.yml");
        var text = Files.readString(file);
        var on = text.substring(text.indexOf("\non:\n") + 5, text.indexOf("\npermissions:"));
        var triggers = on.lines().filter(l -> l.matches("  [a-z_]+:.*")).map(String::strip).toList();
        assertEquals(List.of("schedule:", "workflow_dispatch:"), triggers);
        var jobs = MacRunnerWorkflowTest.jobs(file);
        assertEquals(1, jobs.size());
        var job = String.join("\n", jobs.values().iterator().next());
        assertFalse(job.contains("uses:"), "the heartbeat checks nothing out and runs no action");
        assertTrue(job.contains("vars.LINUX_RUNNER_ENABLED == 'true'"), "the repository variable is the off switch");
        assertTrue(Files.readString(file).contains("'Linux runner heartbeat'"));
        assertTrue(Files.readString(ROUTE).contains("HEARTBEAT_TITLE=\"Linux runner heartbeat\""));
    }

    /** The template installs the hook and the list the runner's .env names, from its clone of main. */
    @Test
    void theTemplateInstallsTheHookTheRunnerCalls() throws IOException {
        var template = JqYaml.parse(Files.readString(RUNNER.resolve("images/tpl-isx-ci-linux.yaml")));
        var tool = Files.readString(RUNNER.resolve("tools/github-actions-runner.yaml"));
        assertTrue(tool.contains("ACTIONS_RUNNER_HOOK_JOB_STARTED=/opt/isx-ci/job-started.sh"));
        var repo = template.path("repos").asList().getFirst();
        assertEquals("https://github.com/Sanne/incus-spawn", repo.pathText("url", ""),
                "actions/checkout reuses a clone only of its own URL");
        assertEquals("~/actions-runner/_work/incus-spawn/incus-spawn", repo.pathText("path", ""));
        var prime = String.join("\n", repo.path("prime").asList().stream().map(p -> p.asText()).toList());
        assertTrue(prime.contains("install -D -o root -g root -m 0755 .github/linux-runner/job-started.sh /opt/isx-ci/job-started.sh"), prime);
        assertTrue(prime.contains("install -o root -g root -m 0644 .github/self-hosted/trusted-heads /opt/isx-ci/trusted-heads"), prime);
        assertTrue(prime.contains("install -o root -g root -m 0755 .github/linux-runner/drop-credentials.sh /opt/isx-ci/drop-credentials.sh"), prime);
        var tools = template.path("tools").asList().stream().map(t -> t.asText()).toList();
        assertEquals(List.of("maven-3", "graalvm", "github-actions-runner"), tools,
                "a tool with a credential would give CI jobs an account through the proxy");
        assertTrue(template.path("accounts").isNull(), "the template pins no account");
        assertEquals("container", template.pathText("type", ""),
                "isx exec gives a VM whose guest lacks its secret the proofs again, a container not");
        assertEquals("tpl-minimal", template.pathText("parent", ""),
                "tpl-java's parent tpl-dev installs gh, which would give CI jobs the host's GitHub account");
    }

    // The hook

    @Test
    void theHookTakesTheRepositorysOwnEventsAndTrustedPullRequests(@TempDir Path dir) throws Exception {
        for (var event : List.of("push", "schedule", "workflow_dispatch")) {
            assertEquals(0, hook(dir, event, "Sanne", null).exit(), event);
        }
        assertEquals(0, hook(dir, "pull_request", "Sanne-ai-bot", pr("Sanne-ai-bot/incus-spawn", "Sanne-ai-bot")).exit());
        assertEquals(0, hook(dir, "pull_request", "Sanne", pr("Sanne/incus-spawn", "Sanne")).exit());
    }

    @Test
    void theHookRefusesEverythingElse(@TempDir Path dir) throws Exception {
        assertRefused(hook(dir, "pull_request", "someone", pr("someone/incus-spawn", "someone")), "which is not trusted here");
        assertRefused(hook(dir, "pull_request", "Sanne", pr("sanne/incus-spawn", "Sanne")), "which is not trusted here");
        assertRefused(hook(dir, "pull_request", "Sanne", pr("", "Sanne")), "a deleted repository");
        assertRefused(hook(dir, "pull_request", "Sanne", pr("Sanne/incus-spawn", "dependabot[bot]")), "Dependabot");
        assertRefused(hook(dir, "push", "dependabot[bot]", null), "Dependabot");
        assertRefused(hook(dir, "pull_request_target", "Sanne", pr("Sanne/incus-spawn", "Sanne")), "never runs here");
        assertRefused(hook(dir, "workflow_run", "Sanne", null), "never runs here");
        assertRefused(hook(dir, "pull_request", "Sanne", "{\"pull_request\":{\"head\":{\"repo\":{\"full_name\":\"Sanne/incus-spawn\"}},"
                + "\"base\":{\"repo\":{\"full_name\":\"someone/incus-spawn\"}},\"user\":{\"login\":\"Sanne\"}}}"), "a pull request into");
        assertRefused(hook(dir, "pull_request", "Sanne", "not json"), "not JSON");
    }

    @Test
    void theHookRefusesARunOfAnotherRepositoryOrWithoutItsList(@TempDir Path dir) throws Exception {
        assertRefused(hook(dir, "push", "Sanne", null, Map.of("GITHUB_REPOSITORY", "someone/incus-spawn")), "not Sanne/incus-spawn");
        Files.delete(dir.resolve("hook/trusted-heads"));
        assertRefused(hook(dir, "push", "Sanne", null, Map.of(), false), "no trusted-heads list");
    }

    /** The list a variable names is never read: only the one installed beside the hook. */
    @Test
    void theHookReadsNoListTheEnvironmentNames(@TempDir Path dir) throws Exception {
        var list = Files.writeString(dir.resolve("other-heads"), "someone/incus-spawn\n");
        var result = hook(dir, "pull_request", "someone", pr("someone/incus-spawn", "someone"),
                Map.of("TRUSTED_HEADS", list.toString(), "CONF", dir.toString(), "here", dir.toString()));
        assertRefused(result, "which is not trusted here");
    }

    // The credentials isx delivers to every instance

    /** isx gives every instance a proof for each namespace the host serves: none may reach the job. */
    @Test
    void theProofsIsxDeliveredAreRemovedBeforeTheRunnerStarts(@TempDir Path dir) throws Exception {
        var run = Files.createDirectories(dir.resolve("root/run/isx"));
        for (var f : List.of("proof-tokens", "credential-secret", "instance-secret", "chatgpt-auth",
                "proof-generation", "proof-version")) {
            Files.writeString(run.resolve(f), "x\n");
        }
        var chatGptProfile = Files.createDirectories(dir.resolve("root/etc/profile.d")).resolve("isx-zz-codex-chatgpt.sh");
        Files.writeString(chatGptProfile, "cp /run/isx/chatgpt-auth ~/.codex/auth.json\n");
        var codexAuth = Files.createDirectories(dir.resolve("root/home/agentuser/.codex")).resolve("auth.json");
        Files.writeString(codexAuth, "{\"tokens\":\"look-alike\"}\n");
        var gitconfig = dir.resolve("root/etc/gitconfig");
        Files.writeString(gitconfig, "[credential \"https://github.com\"]\n\thelper = !isx_github_credential\n"
                + "[credential]\n\tuseHttpPath = true\n[core]\n\tpager = less\n");
        var result = run(new ProcessBuilder("bash", "--norc", "--noprofile", DROP.toString(), dir.resolve("root").toString())
                .redirectErrorStream(true));
        assertEquals(0, result.exit(), result.output());
        try (var left = Files.list(run)) {
            assertEquals(List.of("proof-generation", "proof-version"),
                    left.map(p -> p.getFileName().toString()).sorted().toList(),
                    "the proofs, both secrets and the ChatGPT look-alikes are gone; the public generation and version prove nothing");
        }
        assertFalse(Files.exists(chatGptProfile), "the profile that copies the ChatGPT session to Codex");
        assertFalse(Files.exists(codexAuth), "Codex's copy of the ChatGPT session");
        var config = Files.readString(gitconfig);
        assertFalse(config.contains("helper"), config);
        assertTrue(config.contains("useHttpPath = true"), "a credential setting that is no helper stays, and does not stop the runner");
        assertTrue(config.contains("pager = less"), "the rest of the system config stays");
        assertEquals(0, run(new ProcessBuilder("bash", "--norc", "--noprofile", DROP.toString(), dir.resolve("root").toString())
                .redirectErrorStream(true)).exit(), "nothing to remove is no failure");
    }

    /** A file a later isx delivers to /run/isx stops the runner, whatever its name. */
    @Test
    void anyOtherFileInRunIsxStopsTheRunner(@TempDir Path dir) throws Exception {
        var run = Files.createDirectories(dir.resolve("root/run/isx"));
        Files.writeString(run.resolve("proof-version"), "4");
        Files.writeString(run.resolve("some-new-session"), "a look-alike\n");
        var result = run(new ProcessBuilder("bash", "--norc", "--noprofile", DROP.toString(), dir.resolve("root").toString())
                .redirectErrorStream(true));
        assertEquals(1, result.exit(), result.output());
        assertTrue(result.output().contains("some-new-session"), result.output());
    }

    /** What isx delivers to the guest, by its own constants: each is removed, and none is on the allowlist. */
    @Test
    void theDropRemovesEveryPathIsxDelivers() throws IOException {
        var drop = Files.readString(DROP);
        for (var path : List.of(dev.incusspawn.proxy.InstanceSecret.GUEST_PATH, dev.incusspawn.proxy.InstanceSecret.PROOFS_PATH,
                dev.incusspawn.proxy.InstanceSecret.CHATGPT_GUEST_PATH, dev.incusspawn.proxy.CredentialProof.GUEST_PATH)) {
            assertTrue(path.startsWith("/run/isx/"), path);
            assertTrue(drop.contains("\"$run/" + path.substring("/run/isx/".length()) + "\""), "drop-credentials.sh removes " + path);
        }
        assertTrue(drop.contains("\"$root" + dev.incusspawn.proxy.InstanceSecret.CHATGPT_PROFILE_PATH + "\""),
                "drop-credentials.sh removes " + dev.incusspawn.proxy.InstanceSecret.CHATGPT_PROFILE_PATH);
        assertTrue(drop.contains("\"$root/home/agentuser/.codex/auth.json\""), "and Codex's copy of the ChatGPT session");
        assertTrue(drop.contains("case \"${f##*/}\" in proof-generation|proof-version) ;;"), "the allowlist is the public two alone");
    }

    @Test
    void aProofThatCannotBeRemovedStopsTheRunner(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("root/run/isx/proof-tokens/held"));
        var result = run(new ProcessBuilder("bash", "--norc", "--noprofile", DROP.toString(), dir.resolve("root").toString())
                .redirectErrorStream(true));
        assertEquals(1, result.exit(), result.output());
        assertTrue(result.output().contains("credentials are still in the instance"), result.output());
    }

    /** What the exec runs in the instance: the runner only after the drop, and with none of the exec's variables. */
    @Test
    void theRunnerStartsOnlyAfterTheDropAndInACleanEnvironment(@TempDir Path dir) throws Exception {
        supervisor(dir, "once", "0");
        var script = Files.readString(dir.resolve("in-instance"));
        for (var dropWorks : List.of(true, false)) {
            var home = Files.createDirectories(dir.resolve("home-" + dropWorks + "/actions-runner"));
            Files.writeString(home.resolve("run.sh"), "#!/bin/bash\nprintf '%s\\n' \"$@\" > ran; env > env\n");
            home.resolve("run.sh").toFile().setExecutable(true);
            var bin = Files.createDirectories(dir.resolve("sudo-" + dropWorks));
            Files.writeString(bin.resolve("sudo"), "#!/bin/bash\n[ \"$1\" = /opt/isx-ci/drop-credentials.sh ] && exit "
                    + (dropWorks ? 0 : 1) + "\nexit 9\n");
            bin.resolve("sudo").toFile().setExecutable(true);
            var builder = new ProcessBuilder("bash", "--norc", "--noprofile", "-c", script).redirectErrorStream(true);
            builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
            builder.environment().put("HOME", home.getParent().toString());
            builder.environment().put("ISX_TEST_OUTER_VARIABLE", "gho_isx_from-the-exec");
            builder.redirectInput(ProcessBuilder.Redirect.from(Files.writeString(dir.resolve("stdin-" + dropWorks), "THE-CONFIG\n").toFile()));
            var result = run(builder);
            if (dropWorks) {
                assertEquals(0, result.exit(), result.output());
                assertEquals("--jitconfig\nTHE-CONFIG\n", Files.readString(home.resolve("ran")));
                assertFalse(Files.readString(home.resolve("env")).contains("ISX_TEST_OUTER_VARIABLE"),
                        "the runner sees none of the exec's own variables");
            } else {
                assertEquals(1, result.exit(), result.output());
                assertFalse(Files.exists(home.resolve("ran")), "no runner while a proof may be left");
            }
        }
    }

    // The supervisor

    @Test
    void oneJobGetsAFreshInstanceAndLeavesNothing(@TempDir Path dir) throws Exception {
        var result = supervisor(dir, "once", "0");
        assertEquals(0, result.exit(), result.output());
        var calls = Files.readAllLines(dir.resolve("calls"));
        assertEquals(List.of(
                "isx destroy isx-ci-linux-1 --skip-confirmation",
                "gh api -X POST repos/Sanne/incus-spawn/actions/runners/generate-jitconfig -f name=isx-ci-linux-1-<time>"
                        + " -F runner_group_id=1 -f work_folder=_work -f labels[]=self-hosted -f labels[]=Linux"
                        + " -f labels[]=X64 -f labels[]=isx-linux --jq \"\\(.runner.id) \\(.encoded_jit_config)\"",
                "isx branch isx-ci-linux-1 --from tpl-isx-ci-linux --cpu 8 --memory 16GiB"
                        + " --purpose CI runner 4242 for Sanne/incus-spawn, destroyed after one job --format plain",
                "isx exec -i isx-ci-linux-1 --cwd /home/agentuser/actions-runner -- bash -c <in-instance>",
                "isx destroy isx-ci-linux-1 --skip-confirmation",
                "gh api -X DELETE repos/Sanne/incus-spawn/actions/runners/4242"), calls);
        assertEquals("SECRET-JIT-CONFIG\n", Files.readString(dir.resolve("exec-stdin")),
                "the configuration reaches the instance on the exec's stdin");
        assertFalse(String.join("\n", calls).contains("SECRET-JIT-CONFIG"), "and never the host's command line");
        assertEquals("runner-token-for-tests", Files.readString(dir.resolve("gh-token")).strip(), "gh is given the runner's own token");
        assertFalse(Files.exists(dir.resolve("state/isx-ci-runner/runner-id-1")));
    }

    @Test
    void aRunnerThatEndsBadlyIsStillCleanedUp(@TempDir Path dir) throws Exception {
        var result = supervisor(dir, "once", "3");
        assertEquals(3, result.exit(), result.output());
        var calls = Files.readAllLines(dir.resolve("calls"));
        assertEquals("gh api -X DELETE repos/Sanne/incus-spawn/actions/runners/4242", calls.getLast());
        assertEquals("isx destroy isx-ci-linux-1 --skip-confirmation", calls.get(calls.size() - 2));
    }

    @Test
    void aPauseWhileTheInstanceIsMadeLetsItTakeNoJob(@TempDir Path dir) throws Exception {
        // isx branch runs as the pause comes: the stand-in pauses during the branch
        Files.createDirectories(dir.resolve("state/isx-ci-runner"));
        var result = supervisor(dir, "once", "0", "600",
                "if [ \"$1\" = branch ]; then touch '" + dir.resolve("state/isx-ci-runner/paused") + "'; fi");
        assertEquals(0, result.exit(), result.output());
        var calls = Files.readAllLines(dir.resolve("calls"));
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("isx exec")), "no runner starts: " + calls);
        assertEquals("gh api -X DELETE repos/Sanne/incus-spawn/actions/runners/4242", calls.getLast());
    }

    @Test
    void aTokenOthersCanReadIsNotUsed(@TempDir Path dir) throws Exception {
        var result = supervisor(dir, "once", "0", "644");
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("chmod 600"), result.output());
        assertFalse(Files.readString(dir.resolve("calls")).contains("isx branch"), "no instance without a runner");
    }

    @Test
    void pausingDropsAnIdleRunnerAndQueuesAHeartbeat(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("state/isx-ci-runner"));
        Files.writeString(dir.resolve("state/isx-ci-runner/runner-id-1"), "4242\n");
        var result = supervisor(dir, "pause", "0");
        assertEquals(0, result.exit(), result.output());
        assertTrue(Files.exists(dir.resolve("state/isx-ci-runner/paused")));
        var calls = Files.readAllLines(dir.resolve("calls"));
        assertEquals(List.of(
                "gh api repos/Sanne/incus-spawn/actions/runners/4242 --jq .busy",
                "isx destroy isx-ci-linux-1 --skip-confirmation",
                "gh workflow run linux-heartbeat.yml -R Sanne/incus-spawn --ref main"), calls);
        assertEquals(0, supervisor(dir, "resume", "0").exit());
        assertFalse(Files.exists(dir.resolve("state/isx-ci-runner/paused")));
    }

    // Helpers

    private record Result(int exit, String output) {
    }

    private static void assertRefused(Result result, String why) {
        assertEquals(1, result.exit(), result.output());
        assertTrue(result.output().contains("::error::The project's Linux runner refused this job: "), result.output());
        assertTrue(result.output().contains(why), result.output());
    }

    private static String pr(String head, String author) {
        return "{\"pull_request\":{\"head\":{\"repo\":" + (head.isEmpty() ? "null" : "{\"full_name\":\"" + head + "\"}")
                + "},\"base\":{\"repo\":{\"full_name\":\"Sanne/incus-spawn\"}},\"user\":{\"login\":\"" + author + "\"}}}";
    }

    private static Result hook(Path dir, String event, String actor, String payload) throws Exception {
        return hook(dir, event, actor, payload, Map.of());
    }

    private static Result hook(Path dir, String event, String actor, String payload, Map<String, String> env)
            throws Exception {
        return hook(dir, event, actor, payload, env, true);
    }

    /** The hook installed as the template installs it, the list beside it, and run as the runner runs it. */
    private static Result hook(Path dir, String event, String actor, String payload, Map<String, String> env,
                               boolean install) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/jq")), "the hook reads the event with /usr/bin/jq");
        var installed = Files.createDirectories(dir.resolve("hook"));
        if (install) {
            Files.copy(HOOK, installed.resolve("job-started.sh"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(TRUSTED, installed.resolve("trusted-heads"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", installed.resolve("job-started.sh").toString())
                .redirectErrorStream(true);
        var environment = builder.environment();
        environment.put("GITHUB_REPOSITORY", "Sanne/incus-spawn");
        environment.put("GITHUB_EVENT_NAME", event);
        environment.put("GITHUB_ACTOR", actor);
        environment.put("GITHUB_TRIGGERING_ACTOR", actor);
        if (payload != null) {
            environment.put("GITHUB_EVENT_PATH", Files.writeString(dir.resolve("event.json"), payload).toString());
        }
        environment.putAll(env);
        return run(builder);
    }

    /**
     * The supervisor with {@code isx} and {@code gh} faked: GitHub registers runner 4242, and the
     * runner in the instance ends with {@code runnerExit}.
     */
    private static Result supervisor(Path dir, String command, String runnerExit) throws Exception {
        return supervisor(dir, command, runnerExit, "600");
    }

    private static Result supervisor(Path dir, String command, String runnerExit, String tokenMode) throws Exception {
        return supervisor(dir, command, runnerExit, tokenMode, "");
    }

    private static Result supervisor(Path dir, String command, String runnerExit, String tokenMode, String isxHook)
            throws Exception {
        // The supervisor runs on the Linux server alone, and reads its token's mode with GNU stat
        assumeTrue(System.getProperty("os.name", "").toLowerCase().startsWith("linux"),
                "the supervisor runs on Linux, with GNU stat");
        var bin = Files.createDirectories(dir.resolve("bin"));
        var calls = dir.resolve("calls");
        Files.deleteIfExists(calls);
        Files.writeString(bin.resolve("isx"), """
                #!/bin/bash
                if [ "$1" = exec ]; then
                  printf '%%s' "${@: -1}" > '%s'
                  echo "isx ${*:1:$#-1} <in-instance>" >> '%s'
                else
                  echo "isx $*" >> '%s'
                fi
                %s
                if [ "$1" = exec ]; then cat > '%s'; exit %s; fi
                exit 0
                """.formatted(dir.resolve("in-instance"), calls, calls, isxHook, dir.resolve("exec-stdin"), runnerExit));
        Files.writeString(bin.resolve("gh"), """
                #!/bin/bash
                echo "gh $*" | sed -E 's/name=isx-ci-linux-1-[0-9]+/name=isx-ci-linux-1-<time>/' >> '%s'
                echo "$GH_TOKEN" > '%s'
                case "$*" in
                  *generate-jitconfig*) echo "4242 SECRET-JIT-CONFIG" ;;
                  *--jq\\ .busy*) echo false ;;
                esac
                """.formatted(calls, dir.resolve("gh-token")));
        bin.resolve("isx").toFile().setExecutable(true);
        bin.resolve("gh").toFile().setExecutable(true);
        var token = dir.resolve("token");
        Files.writeString(token, "runner-token-for-tests\n");
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString(
                tokenMode.equals("600") ? "rw-------" : "rw-r--r--"));
        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", SUPERVISOR.toString(), command)
                .redirectErrorStream(true);
        var environment = builder.environment();
        environment.put("PATH", bin + ":" + System.getenv("PATH"));
        environment.put("HOME", dir.toString());
        environment.put("XDG_STATE_HOME", dir.resolve("state").toString());
        environment.put("ISX_CI_TOKEN_FILE", token.toString());
        environment.remove("GH_TOKEN");
        return run(builder);
    }

    private static Result run(ProcessBuilder builder) throws Exception {
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), output);
        return new Result(process.exitValue(), output);
    }
}
