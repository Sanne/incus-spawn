package dev.incusspawn;

import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The project's Mac Mini runs test-integration.yml's macOS jobs for trusted code, shared with the
 * agent runner (#2298). The Mini is not thrown away after a job, so only jobs that leave nothing
 * behind may run there, and only through {@code mac-route}, which sends everything else to GitHub's
 * runners. When the agent runner holds the Mini, a routed job gives itself up and its twin runs the
 * same steps on GitHub's runner. The Mini's own job-started hook is what refuses untrusted code;
 * these hold the workflow side of the contract.
 */
class MacRunnerWorkflowTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");
    private static final Pattern JOB = Pattern.compile("^  ([a-z][a-z0-9_-]*):$");
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^[a-z][a-z_-]*:.*$");

    private static final String ROUTED = "    runs-on: ${{ fromJSON(needs.mac-route.outputs.runner) }}";
    private static final String GATE_PASSED = "steps.gate.outputs.run == 'true'";
    private static final String KEYCHAIN = "ISX_TEST_MUTATE_SYSTEM_KEYCHAIN";
    private static final String KEYCHAIN_ON_GITHUB_ONLY =
            "ISX_TEST_MUTATE_SYSTEM_KEYCHAIN: ${{ runner.environment == 'github-hosted' && '1' || '' }}";

    @Test
    void onlyRoutedJobsAndTheHeartbeatReachTheMacRunner() throws IOException {
        var offenders = new ArrayList<String>();
        for (var file : workflows()) {
            for (var job : jobs(file).entrySet()) {
                var runsOn = runsOn(job.getValue());
                if (!(runsOn.contains("self-hosted") || runsOn.contains("isx-mac") || runsOn.contains("mac-route"))) {
                    continue;
                }
                var where = file.getFileName() + " " + job.getKey();
                // The Linux runner's own jobs are LinuxRunnerTest's
                if (file.getFileName().toString().equals("mac-heartbeat.yml") || LinuxRunnerTest.reachesLinuxRunner(file, runsOn)) {
                    continue;
                }
                if (!file.getFileName().toString().equals("test-integration.yml") || !runsOn.equals(ROUTED)) {
                    offenders.add(where + ": " + runsOn.strip());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "a job reaches the Mac runner only through mac-route:\n"
                + String.join("\n", offenders));
    }

    /** A workflow with a job that can hold the Mini must be one runner-route.sh lists runs of when it asks if the Mini is free. */
    @Test
    void macRouteListsExactlyTheWorkflowsWithAJobForTheMini() throws IOException {
        var users = new java.util.TreeSet<String>();
        for (var file : workflows()) {
            for (var job : jobs(file).values()) {
                var runsOn = runsOn(job);
                if (runsOn.contains("isx-mac") || runsOn.contains("mac-route")) {
                    users.add(file.getFileName().toString());
                }
            }
        }
        var m = Pattern.compile("(?m)^\\s*MAC_WORKFLOWS=\"([^\"]*)\"").matcher(Files.readString(Path.of("../.github/scripts/runner-route.sh")));
        assertTrue(m.find(), "runner-route.sh declares MAC_WORKFLOWS");
        assertEquals(users, new java.util.TreeSet<>(List.of(m.group(1).split(" "))),
                "runner-route.sh lists runs of exactly the workflows with a job that can run on the Mini");
    }

    @Test
    void theHeartbeatRunsNoCodeOfAPullRequest() throws IOException {
        var file = WORKFLOWS.resolve("mac-heartbeat.yml");
        var triggers = new ArrayList<String>();
        boolean inOn = false;
        for (var line : Files.readAllLines(file)) {
            if (line.equals("on:")) {
                inOn = true;
            } else if (inOn && TOP_LEVEL_KEY.matcher(line).matches()) {
                break;
            } else if (inOn && line.startsWith("  ") && !line.startsWith("   ") && line.strip().endsWith(":")) {
                triggers.add(line.strip());
            }
        }
        assertEquals(List.of("schedule:", "workflow_dispatch:"), triggers);
        var jobs = jobs(file);
        assertEquals(1, jobs.size());
        var job = jobs.values().iterator().next();
        assertFalse(String.join("\n", job).contains("uses:"), "the heartbeat checks nothing out and runs no action");
        assertTrue(String.join("\n", job).contains("vars.MAC_RUNNER_ENABLED == 'true'"),
                "the repository variable is the Mac runner's off switch");
        assertTrue(Files.readString(file).contains("'Mac runner heartbeat'"),
                "runner-route.sh counts runs by this title");
        assertTrue(Files.readString(Path.of("../.github/scripts/runner-route.sh"))
                .contains("HEARTBEAT_TITLE=\"Mac runner heartbeat\""));
    }

    @Test
    void everyRoutedJobGivesUpToATwinWithItsOwnSteps() throws IOException {
        var file = WORKFLOWS.resolve("test-integration.yml");
        var jobs = jobs(file);
        var parsed = JqYaml.parse(Files.readString(file)).path("jobs");
        var routed = jobs.entrySet().stream().filter(j -> j.getValue().contains(ROUTED)).map(Map.Entry::getKey).toList();
        assertEquals(List.of("unit-tests-macos", "launcher-macos"), routed);
        for (var name : routed) {
            var job = jobs.get(name);
            assertTrue(job.contains("    needs: [mac-route]"), name);
            assertTrue(job.contains("      fell-back: ${{ steps.gate.outputs.fell-back }}"), name);

            var twin = jobs.get(name + "-fallback");
            assertNotNull(twin, name + " has no twin on GitHub's runner");
            assertTrue(twin.contains("    runs-on: macos-15"), name + "-fallback");
            assertTrue(twin.contains("    needs: [" + name + "]"), name + "-fallback");
            assertTrue(twin.contains("    if: ${{ !cancelled() && needs." + name + ".outputs.fell-back == 'true' }}"),
                    name + "-fallback runs exactly when the Mac runner gave the job up");
            assertEquals(steps(job), steps(twin), name + "-fallback must run " + name + "'s steps word for word");
            var limit = parsed.path(name).path("timeout-minutes").asText();
            assertFalse(limit.isEmpty(), name + " has a time limit");
            assertEquals(limit, parsed.path(name + "-fallback").path("timeout-minutes").asText(),
                    name + "-fallback runs on GitHub's runner, so it needs " + name + "'s time limit (#1839)");

            var steps = parsed.path(name).path("steps").asList();
            assertEquals("gate", steps.get(0).pathText("id", ""), name + ": the gate is the first step");
            for (var step : steps.subList(1, steps.size())) {
                assertTrue(step.pathText("if", "").contains(GATE_PASSED),
                        name + ": step '" + step.pathText("name", "") + "' runs on a job the Mac runner gave up");
            }
        }
    }

    /** The System keychain is the machine's: only a runner thrown away after the job may change it (#1467). */
    @Test
    void onlyGitHubsRunnersChangeTheSystemKeychain() throws IOException {
        var offenders = new ArrayList<String>();
        for (var file : workflows()) {
            for (var job : jobs(file).entrySet()) {
                for (var line : job.getValue()) {
                    if (!line.contains(KEYCHAIN + ":")) {
                        continue;
                    }
                    var runsOn = runsOn(job.getValue()).strip();
                    var onGitHub = runsOn.matches("runs-on: macos-\\d+") && line.strip().equals(KEYCHAIN + ": '1'");
                    if (!onGitHub && !line.strip().equals(KEYCHAIN_ON_GITHUB_ONLY)) {
                        offenders.add(file.getFileName() + " " + job.getKey() + ": " + runsOn + " / " + line.strip());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    static List<Path> workflows() throws IOException {
        try (var files = Files.list(WORKFLOWS)) {
            return files.filter(f -> f.toString().endsWith(".yml")).sorted().toList();
        }
    }

    /** Each job's lines, without the comments and blank lines that introduce the next one. */
    static Map<String, List<String>> jobs(Path file) throws IOException {
        var lines = Files.readAllLines(file);
        var jobs = new LinkedHashMap<String, List<String>>();
        int start = lines.indexOf("jobs:");
        String name = null;
        var body = new ArrayList<String>();
        for (int i = start + 1; i <= lines.size(); i++) {
            var line = i < lines.size() ? lines.get(i) : "";
            var m = JOB.matcher(line);
            boolean end = i == lines.size() || m.matches() || TOP_LEVEL_KEY.matcher(line).matches();
            if (end && name != null) {
                while (!body.isEmpty() && (body.getLast().isBlank() || body.getLast().startsWith("  #"))) {
                    body.removeLast();
                }
                jobs.put(name, List.copyOf(body));
                body.clear();
                name = null;
            }
            if (m.matches()) {
                name = m.group(1);
            } else if (name != null) {
                body.add(line);
            }
            if (i < lines.size() && TOP_LEVEL_KEY.matcher(line).matches()) {
                break;
            }
        }
        return jobs;
    }

    /** The job's runs-on, with the items of a block list joined to it, so a list cannot hide a label. */
    static String runsOn(List<String> job) {
        int at = -1;
        for (int i = 0; i < job.size(); i++) {
            if (job.get(i).startsWith("    runs-on:")) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return "";
        }
        var runsOn = new StringBuilder(job.get(at));
        for (int i = at + 1; i < job.size() && job.get(i).startsWith("     "); i++) {
            runsOn.append(' ').append(job.get(i).strip());
        }
        return runsOn.toString();
    }

    static List<String> steps(List<String> job) {
        return job.subList(job.indexOf("    steps:"), job.size());
    }
}
