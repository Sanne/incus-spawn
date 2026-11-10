package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How release.yml builds with PGO profiles (docs/dev/PGO.md, #1637). The release workflow runs on
 * {@code v*} tags, so a profile its builds cannot take, or a dispatch that could publish, would be
 * found by the next release at the earliest; these are its rules, checked on every PR.
 */
class ReleasePgoWorkflowTest {

    private static final Path RELEASE = Path.of("../.github/workflows/release.yml");

    private static JqValue jobs() throws IOException {
        return JqYaml.parse(Files.readString(RELEASE)).path("jobs");
    }

    private static String graalVmOf(JqValue job) {
        for (var step : job.path("steps").asList()) {
            if (step.pathText("uses", "").startsWith("graalvm/setup-graalvm@")) {
                return step.path("with").pathText("version", "");
            }
        }
        return fail("no graalvm/setup-graalvm step in the profiles job");
    }

    @Test
    void everyProfiledBuildRunsTheGraalVmThatCollectedItsProfile() throws IOException {
        var jobs = jobs();
        var collectedWith = graalVmOf(jobs.path("profiles"));
        var profiled = new ArrayList<String>();
        for (var entry : jobs.path("build").path("strategy").path("matrix").path("include").asList()) {
            if (entry.pathBoolean("pgo", false)) {
                profiled.add(entry.pathText("runner", ""));
                assertEquals(collectedWith, entry.pathText("graalvm-version", ""), entry.pathText("runner", "")
                        + " applies profiles collected with GraalVM " + collectedWith
                        + ": a profile's version must match the build's, or set pgo: false there");
            }
        }
        assertFalse(profiled.isEmpty(), "no release build applies a PGO profile");
        assertTrue(jobs.path("build").path("needs").asList().stream().anyMatch(n -> "profiles".equals(n.asText())),
                "the builds must wait for the profiles job: a release collects its own");
    }

    @Test
    void aProfiledBuildChecksThatBothBinariesTookTheirProfile() throws IOException {
        var steps = jobs().path("build").path("steps").asList();
        assertTrue(steps.stream().anyMatch(s -> s.pathText("run", "").contains(".github/scripts/check-pgo-quality.sh")),
                "the build job no longer checks the PGO profile quality of what it built");
        var builds = steps.stream().filter(s -> s.pathText("name", "").startsWith("Build native image")).toList();
        assertFalse(builds.isEmpty(), "no native build step");
        for (var build : builds) {
            var run = build.pathText("run", "");
            assertTrue(run.contains("$PGO_ARGS") && run.contains("tee native-build.log"),
                    build.pathText("name", "") + " must build with $PGO_ARGS into native-build.log");
            assertTrue(run.contains("set -o pipefail"),
                    build.pathText("name", "") + " pipes the build into tee: without pipefail a failed build passes");
        }
    }

    @Test
    void aDispatchBuildsButPublishesNothing() throws IOException {
        var jobs = jobs();
        var names = new ArrayList<String>();
        names.addAll(jobs.asMap().keySet());
        assertTrue(names.containsAll(List.of("profiles", "build", "release")), "jobs: " + names);
        for (var name : names) {
            var job = jobs.path(name);
            if (name.equals("profiles") || name.equals("build")) {
                // What a dispatch runs: it may read the repository and nothing else.
                assertEquals("read", job.path("permissions").pathText("contents", ""), name + " must read only");
                assertEquals(1, job.path("permissions").asMap().size(),
                        name + " runs on a dispatch, so it may hold no permission beyond contents: read");
                assertFalse(job.has("if"), name + " is what a dispatch verifies; it must run for one");
            } else if (job.pathText("if", "").equals("github.event_name == 'workflow_dispatch'")) {
                // Shows on a dispatch what the release job would publish (the SHA256SUMS, #2080)
                assertEquals("read", job.path("permissions").pathText("contents", ""), name + " must read only");
                assertEquals(1, job.path("permissions").asMap().size(),
                        name + " runs on a dispatch, so it may hold no permission beyond contents: read");
            } else {
                // Everything else publishes or feeds what does: a pushed tag only.
                assertTrue(job.pathText("if", "").contains("github.event_name == 'push'"),
                        name + " must run for a pushed tag only, never for a workflow_dispatch");
            }
        }
        var release = jobs.path("release").pathText("if", "");
        assertTrue(release.contains("startsWith(github.ref, 'refs/tags/v')"),
                "the release job publishes: it must run for a pushed v* tag only. Found: " + release);
    }
}
