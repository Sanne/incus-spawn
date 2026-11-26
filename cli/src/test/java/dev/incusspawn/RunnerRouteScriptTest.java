package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Where test-integration.yml's routed jobs run: the project's Mac Mini for the macOS jobs (#2298)
 * and a fresh isx instance on its Linux server for unit-tests (#2519), each only for trusted code
 * while its heartbeat says it is up and free, and GitHub's runners for everything else, so a
 * busy or missing runner never leaves a pull request's checks queued. Each runner's own hook is
 * what keeps untrusted code off it; this holds the routing that keeps such jobs from being sent there.
 */
class RunnerRouteScriptTest {

    private static final Path SCRIPT = Path.of("../.github/scripts/runner-route.sh").toAbsolutePath();
    private static final Path TRUSTED = Path.of("../.github/self-hosted/trusted-heads");

    private static final String MINI = "[\"self-hosted\",\"macOS\",\"ARM64\",\"isx-mac\"]";
    private static final String HOSTED = "\"macos-15\"";
    private static final String LINUX = "[\"self-hosted\",\"Linux\",\"X64\",\"isx-linux\"]";
    private static final String UBUNTU = "\"ubuntu-26.04\"";
    private static final long NOW = 2_000_000_000L;
    private static final String FRESH = Long.toString(NOW - 600);

    @Test
    void theBotsAndTheRepositorysOwnPullRequestsRunOnTheMini() throws Exception {
        assertEquals(MINI, decide("pull_request", "Sanne/incus-spawn", "Sanne", FRESH, ""));
        assertEquals(MINI, decide("pull_request", "Sanne-ai-bot/incus-spawn", "Sanne-ai-bot", FRESH, ""));
    }

    @Test
    void anyOtherForkRunsOnGitHubsRunner() throws Exception {
        assertEquals(HOSTED, decide("pull_request", "someone/incus-spawn", "someone", FRESH, ""));
        assertEquals(HOSTED, decide("pull_request", "Sanne-ai-bot/other", "Sanne-ai-bot", FRESH, ""));
        assertEquals(HOSTED, decide("pull_request", "sanne/incus-spawn", "Sanne", FRESH, ""),
                "a name is compared exactly");
        assertEquals(HOSTED, decide("pull_request", "", "Sanne", FRESH, ""), "a deleted head repository");
    }

    @Test
    void dependabotsPullRequestsRunOnGitHubsRunner() throws Exception {
        assertEquals(HOSTED, decide("pull_request", "Sanne/incus-spawn", "dependabot[bot]", FRESH, ""));
    }

    @Test
    void theRepositorysOwnEventsRunOnTheMini() throws Exception {
        for (var event : List.of("push", "schedule", "workflow_dispatch")) {
            assertEquals(MINI, decide(event, "", "Sanne", FRESH, ""), event);
        }
    }

    @Test
    void anyOtherEventRunsOnGitHubsRunner() throws Exception {
        for (var event : List.of("pull_request_target", "workflow_run", "merge_group", "")) {
            assertEquals(HOSTED, decide(event, "Sanne/incus-spawn", "Sanne", FRESH, ""), event);
        }
    }

    @Test
    void aMiniWithoutARecentHeartbeatIsNotUsed() throws Exception {
        assertEquals(HOSTED, decide("push", "", "Sanne", "", ""), "no heartbeat ever");
        assertEquals(HOSTED, decide("push", "", "Sanne", Long.toString(NOW - 1501), ""), "a stale heartbeat");
        assertEquals(MINI, decide("push", "", "Sanne", Long.toString(NOW - 1500), ""));
        assertEquals(HOSTED, decide("push", "", "Sanne", "yesterday", ""), "not a time");
    }

    @Test
    void aHeartbeatWaitingForTheRunnerSendsTheJobsToGitHub() throws Exception {
        assertEquals(HOSTED, decide("push", "", "Sanne", FRESH, Long.toString(NOW - 300)), "busy or gone");
        assertEquals(MINI, decide("push", "", "Sanne", FRESH, Long.toString(NOW - 299)), "just queued");
        assertEquals(HOSTED, decide("push", "", "Sanne", FRESH, "soon"), "not a time");
    }

    @Test
    void anotherRunsJobOnTheMiniSendsTheJobsToGitHub() throws Exception {
        assertEquals(HOSTED, decide("push", "", "Sanne", FRESH, "", "1"), "the Mini has one runner");
        assertEquals(HOSTED, decide("push", "", "Sanne", FRESH, "", ""), "unknown");
        assertEquals(MINI, decide("push", "", "Sanne", FRESH, "", "0"));
    }

    @Test
    void theTrustedHeadsAreTheRepositoryAndTheBotsForkAlone() throws IOException {
        var heads = Files.readAllLines(TRUSTED).stream()
                .map(l -> (l.indexOf('#') < 0 ? l : l.substring(0, l.indexOf('#'))).strip()).filter(l -> !l.isEmpty()).toList();
        assertEquals(List.of("Sanne/incus-spawn", "Sanne-ai-bot/incus-spawn"), heads,
                "a new trusted head lets its code run on the project's runners: change the Mini's hook's list too");
    }

    @Test
    void theRouteWithoutAHeartbeatWritesGitHubsRunner(@TempDir Path dir) throws Exception {
        // the API answers nothing: no heartbeat workflow yet, or GitHub is down
        assertEquals("runner=" + HOSTED + "\n", route(dir, "exit 1"));
    }

    @Test
    void theRouteReadsTheHeartbeatsTimesFromTheApi(@TempDir Path dir) throws Exception {
        // gh prints what the route's --jq makes of the runs: the last completed and the oldest waiting
        assertEquals("runner=" + MINI + "\n", route(dir, "echo \"$(( $(date +%s) - 60 ))\""));
        assertEquals("runner=" + HOSTED + "\n",
                route(dir, "echo \"$(( $(date +%s) - 60 )),$(( $(date +%s) - 900 ))\""), "a heartbeat waits");
        assertEquals("runner=" + HOSTED + "\n",
                route(dir, "echo \",$(( $(date +%s) - 60 ))\""), "none completed, one waiting");
    }

    @Test
    void theRouteCountsTheOtherRunsJobsOnTheMini(@TempDir Path dir) throws Exception {
        var up = "echo \"$(( $(date +%s) - 60 ))\"";
        assertEquals("runner=" + HOSTED + "\n", route(dir, up, "111", "1"), "another run's job is on the Mini");
        assertEquals("runner=" + MINI + "\n", route(dir, up, "222", "2"), "the route's own run does not count");
        assertEquals("runner=" + MINI + "\n", route(dir, up, "", "1"), "no other run");
        assertEquals("runner=" + HOSTED + "\n", route(dir, up, "111", "x"), "an answer that is not a count");
    }

    @Test
    void theRouteListsRunsOnlyOfTheWorkflowsThatCanUseTheMini(@TempDir Path dir) throws Exception {
        route(dir, "echo \"$(( $(date +%s) - 60 ))\"", "111", "0");
        var listed = Files.readAllLines(dir.resolve("calls")).stream()
                .filter(c -> c.contains("status=")).toList();
        assertEquals(List.of(
                "repos/Sanne/incus-spawn/actions/workflows/test-integration.yml/runs?status=in_progress&per_page=50",
                "repos/Sanne/incus-spawn/actions/workflows/test-integration.yml/runs?status=queued&per_page=50",
                "repos/Sanne/incus-spawn/actions/workflows/mac-heartbeat.yml/runs?status=in_progress&per_page=50",
                "repos/Sanne/incus-spawn/actions/workflows/mac-heartbeat.yml/runs?status=queued&per_page=50"), listed);
    }

    @Test
    void theRouteCountsOnlyTitledSuccessfulAndWaitingHeartbeats(@TempDir Path dir) throws Exception {
        assumeTrue(onPath("jq"), "jq applies the route's --jq filter as gh would");
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS); // GitHub writes whole seconds, all fromdate reads
        var recent = now.minusSeconds(60).toString();
        var old = now.minusSeconds(3600).toString();
        var longWait = now.minusSeconds(900).toString();
        // A recent success, an older one and a run with the switch off: the Mini is up and free
        assertEquals("runner=" + MINI + "\n", route(dir, gh(dir, runs(
                run("Mac runner heartbeat", "completed", "success", recent, recent),
                run("Mac runner heartbeat", "completed", "success", old, old),
                run("Mac runner heartbeat (off)", "queued", null, longWait, longWait)))));
        // Only the run with the switch off is recent: the Mini is not known to be up
        assertEquals("runner=" + HOSTED + "\n", route(dir, gh(dir, runs(
                run("Mac runner heartbeat (off)", "completed", "success", recent, recent),
                run("Mac runner heartbeat", "completed", "success", old, old)))));
        // A recent failure does not count as up
        assertEquals("runner=" + HOSTED + "\n", route(dir, gh(dir, runs(
                run("Mac runner heartbeat", "completed", "failure", recent, recent)))));
        // A heartbeat waiting for the runner for 15 minutes: busy or gone
        assertEquals("runner=" + HOSTED + "\n", route(dir, gh(dir, runs(
                run("Mac runner heartbeat", "completed", "success", recent, recent),
                run("Mac runner heartbeat", "queued", null, longWait, longWait)))));
    }

    @Test
    void theLinuxRunnerTakesTheSameTrustedCodeAndOtherwiseUbuntu() throws Exception {
        assertEquals(LINUX, decide("linux", "pull_request", "Sanne-ai-bot/incus-spawn", "Sanne-ai-bot", FRESH, "", "0"));
        assertEquals(LINUX, decide("linux", "push", "", "Sanne", FRESH, "", "0"));
        assertEquals(UBUNTU, decide("linux", "pull_request", "someone/incus-spawn", "someone", FRESH, "", "0"));
        assertEquals(UBUNTU, decide("linux", "pull_request", "Sanne/incus-spawn", "dependabot[bot]", FRESH, "", "0"));
        assertEquals(UBUNTU, decide("linux", "push", "", "Sanne", "", "", "0"), "no heartbeat ever");
        assertEquals(UBUNTU, decide("linux", "push", "", "Sanne", FRESH, "", "1"), "the runner has a job");
    }

    @Test
    void anUnknownRunnerIsRefused() throws Exception {
        var process = new ProcessBuilder("bash", "--norc", "--noprofile", SCRIPT.toString(), "windows", "decide",
                "push", "", "Sanne", Long.toString(NOW), FRESH, "", "0").redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(2, process.exitValue());
    }

    @Test
    void theLinuxRouteReadsItsOwnHeartbeatAndCountsItsOwnLabel(@TempDir Path dir) throws Exception {
        assertEquals("runner=" + LINUX + "\n",
                route("linux", "Sanne/incus-spawn", dir, "echo \"$(( $(date +%s) - 60 ))\"", "111", "0"));
        var calls = Files.readAllLines(dir.resolve("calls"));
        assertTrue(calls.contains("repos/Sanne/incus-spawn/actions/workflows/linux-heartbeat.yml/runs?per_page=20"), calls.toString());
        assertEquals(List.of(
                "repos/Sanne/incus-spawn/actions/workflows/test-integration.yml/runs?status=in_progress&per_page=50",
                "repos/Sanne/incus-spawn/actions/workflows/test-integration.yml/runs?status=queued&per_page=50",
                "repos/Sanne/incus-spawn/actions/workflows/linux-heartbeat.yml/runs?status=in_progress&per_page=50",
                "repos/Sanne/incus-spawn/actions/workflows/linux-heartbeat.yml/runs?status=queued&per_page=50"),
                calls.stream().filter(c -> c.contains("status=")).toList());
        assertTrue(Files.readString(SCRIPT).contains("select((.labels | index(\"'\"$LABEL\"'\"))"),
                "a job is the runner's by its own label");
    }

    @Test
    void aForksPullRequestIsRoutedWithoutARequest(@TempDir Path dir) throws Exception {
        for (var target : List.of("mac", "linux")) {
            var answer = route(target, "someone/incus-spawn", dir, "echo \"$(( $(date +%s) - 60 ))\"", "", "0");
            assertEquals("runner=" + (target.equals("mac") ? HOSTED : UBUNTU) + "\n", answer, target);
            assertFalse(Files.exists(dir.resolve("calls")), target + ": a fork's run asks GitHub nothing");
        }
    }

    private static String run(String title, String status, String conclusion, String created, String updated) {
        return "{\"display_title\":\"" + title + "\",\"status\":\"" + status + "\",\"conclusion\":"
                + (conclusion == null ? "null" : "\"" + conclusion + "\"")
                + ",\"created_at\":\"" + created + "\",\"updated_at\":\"" + updated + "\"}";
    }

    private static String runs(String... runs) {
        return "{\"workflow_runs\":[" + String.join(",", runs) + "]}";
    }

    /** A fake {@code gh api} that answers with {@code json} through the route's own --jq filter. */
    private static String gh(Path dir, String json) throws IOException {
        var answer = Files.writeString(dir.resolve("runs.json"), json);
        return "while [ $# -gt 0 ]; do [ \"$1\" = --jq ] && { jq -r \"$2\" '" + answer + "'; exit; }; shift; done; exit 1";
    }

    private static boolean onPath(String command) {
        for (var dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }

    /** The route as the workflow runs it, for a pull request of this repository, with {@code gh} faked. */
    private static String route(Path dir, String gh) throws Exception {
        return route(dir, gh, "111", "0");
    }

    private static String route(Path dir, String gh, String runIds, String miniJobs) throws Exception {
        return route("mac", "Sanne/incus-spawn", dir, gh, runIds, miniJobs);
    }

    /**
     * {@code gh} answers the heartbeat's runs with {@code gh}, the runs in progress and queued with
     * {@code runIds} (the route's own run is 222), and each run's jobs on the Mini with {@code miniJobs}.
     */
    private static String route(String target, String head, Path dir, String gh, String runIds, String miniJobs)
            throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(bin.resolve("gh"), "#!/bin/bash\necho \"$2\" >> '" + dir.resolve("calls") + "'\ncase \"$2\" in\n*/jobs*) echo " + miniJobs
                + " ;;\n*status=*) echo " + runIds + " ;;\n*-heartbeat.yml/runs?per*) " + gh + " ;;\nesac\n");
        bin.resolve("gh").toFile().setExecutable(true);
        var output = dir.resolve("output");
        Files.deleteIfExists(output);
        Files.deleteIfExists(dir.resolve("calls"));
        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", SCRIPT.toString(), target).redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("GITHUB_OUTPUT", output.toString());
        builder.environment().put("GITHUB_REPOSITORY", "Sanne/incus-spawn");
        builder.environment().put("EVENT_NAME", "pull_request");
        builder.environment().put("HEAD_REPO", head);
        builder.environment().put("ACTOR", "Sanne");
        builder.environment().put("GITHUB_RUN_ID", "222");
        var process = builder.start();
        var text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), text);
        assertEquals(0, process.exitValue(), text);
        return Files.readString(output);
    }

    private static String decide(String event, String head, String actor, String completed, String queued)
            throws Exception {
        return decide(event, head, actor, completed, queued, "0");
    }

    private static String decide(String event, String head, String actor, String completed, String queued,
                                 String miniJobs) throws Exception {
        return decide("mac", event, head, actor, completed, queued, miniJobs);
    }

    private static String decide(String target, String event, String head, String actor, String completed,
                                 String queued, String miniJobs) throws Exception {
        var process = new ProcessBuilder("bash", "--norc", "--noprofile", SCRIPT.toString(), target, "decide",
                event, head, actor, Long.toString(NOW), completed, queued, miniJobs).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), output);
        assertEquals(0, process.exitValue(), output);
        return output.strip();
    }
}
