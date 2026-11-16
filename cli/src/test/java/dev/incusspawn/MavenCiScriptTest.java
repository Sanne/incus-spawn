package dev.incusspawn;

import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Three of the four Linux Maven jobs of test-integration.yml downloaded from Maven Central on every
 * run, because they shared one cache key that the job resolving least saved (#2307), and one 429
 * failed a build (#2272). Each job now saves a cache of its own, and builds offline on an exact hit
 * through {@code .github/scripts/mvn-ci.sh}.
 */
class MavenCiScriptTest {

    private static final Path SCRIPT = Path.of("../.github/scripts/mvn-ci.sh").toAbsolutePath();
    private static final Path WORKFLOWS = Path.of("../.github/workflows");

    @TempDir
    Path dir;

    @Test
    void anExactHitBuildsOfflineAndSendsNoRequest() throws Exception {
        var result = run("true", 0, 0);
        assertEquals(0, result.exitCode, result.output);
        assertEquals(1, Files.readAllLines(dir.resolve("calls")).size(), result.output);
        assertTrue(Files.readString(dir.resolve("calls")).startsWith("-o "), result.output);
    }

    @Test
    void withoutAnExactHitTheBuildRunsOnline() throws Exception {
        for (var hit : new String[]{"false", ""}) {
            Files.deleteIfExists(dir.resolve("calls"));
            var result = run(hit, 0, 0);
            assertEquals(0, result.exitCode, result.output);
            assertFalse(Files.readString(dir.resolve("calls")).contains("-o "), result.output);
        }
    }

    @Test
    void anIncompleteCacheIsSaidAndTheBuildRunsAgainOnline() throws Exception {
        var result = run("true", 1, 0);
        assertEquals(0, result.exitCode, result.output);
        var calls = Files.readAllLines(dir.resolve("calls"));
        assertEquals(2, calls.size(), result.output);
        assertTrue(calls.get(0).startsWith("-o "), calls.toString());
        assertFalse(calls.get(1).startsWith("-o "), calls.toString());
        assertTrue(result.output.contains("::warning::"), result.output);
    }

    @Test
    void aFailureOfAnotherKindOfflineIsNotRunAgain() throws Exception {
        var result = run("true", 2, 0);
        assertEquals(1, result.exitCode, result.output);
        assertEquals(1, Files.readAllLines(dir.resolve("calls")).size(), result.output);
    }

    @Test
    void aFailureOnlineIsTheBuildsStatus() throws Exception {
        var result = run("false", 99, 3);
        assertEquals(3, result.exitCode, result.output);
        assertEquals(1, Files.readAllLines(dir.resolve("calls")).size(), result.output);
    }

    @Test
    void theResolverAsksAgainOnA429() throws Exception {
        var result = run("false", 0, 0);
        var call = Files.readString(dir.resolve("calls"));
        assertTrue(call.contains("-Daether.connector.http.retryHandler.serviceUnavailable=429,503"), result.output);
        assertTrue(call.contains("-Daether.connector.http.retryHandler.count=8"), result.output);
        assertTrue(call.contains("package -DskipTests"), "the build's own arguments follow: " + call);
    }

    @Test
    void everyMavenCacheIsTheJobsOwnAndItsBuildUsesIt() throws IOException {
        var seen = 0;
        try (var files = Files.list(WORKFLOWS)) {
            for (var file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                var jobs = JqYaml.parse(Files.readString(file)).path("jobs");
                for (var name : jobs.asMap().keySet()) {
                    var steps = jobs.path(name).path("steps").asList();
                    var cache = steps.stream().filter(s -> s.pathText("uses", "").startsWith("actions/cache@")
                            && s.path("with").pathText("path", "").equals("~/.m2/repository")).findFirst();
                    if (cache.isEmpty()) {
                        continue;
                    }
                    seen++;
                    var where = file.getFileName() + " " + name;
                    var with = cache.get().path("with");
                    assertTrue(with.pathText("key", "").matches("(macos-)?maven-" + name + "-\\$\\{\\{ hashFiles.*"),
                            where + ": its key is not its own: " + with.pathText("key", ""));
                    assertTrue(with.pathText("restore-keys", "").lines().anyMatch(k -> k.matches("(macos-)?maven-" + name + "-")),
                            where + ": no restore key of its own");
                    var id = cache.get().pathText("id", "");
                    assertFalse(id.isEmpty(), where + ": the cache step has no id");
                    var maven = steps.stream().map(s -> s.pathText("run", "")).filter(r -> r.contains("mvn")).toList();
                    assertFalse(maven.isEmpty(), where);
                    for (var run : maven) {
                        assertTrue(run.startsWith(".github/scripts/mvn-ci.sh '${{ steps." + id + ".outputs.cache-hit }}' "),
                                where + ": builds without the offline path: " + run);
                    }
                }
            }
        }
        assertTrue(seen >= 7, "found " + seen + " Maven cache steps; the workflows moved");
    }

    /** The script against a fake mvn that fails {@code offlineStatus} when offline, and {@code status} online. */
    private Result run(String hit, int onlineFailsWith, int exit) throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        // onlineFailsWith 1: only the offline run fails; 99: the online run fails with exit
        Files.writeString(bin.resolve("mvn"), """
                #!/bin/bash
                echo "$*" >> "%1$s/calls"
                if [ "$1" = -o ]; then
                    [ %2$d -eq 1 ] && { echo "Cannot access central in offline mode and the artifact x has not been downloaded"; exit 1; }
                    [ %2$d -eq 2 ] && { echo "There are test failures"; exit 1; }
                    exit 0
                fi
                [ %2$d -eq 99 ] && exit %3$d
                exit 0
                """.formatted(dir, onlineFailsWith, exit));
        bin.resolve("mvn").toFile().setExecutable(true);
        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", SCRIPT.toString(), hit, "package", "-DskipTests")
                .redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script hung:\n" + output);
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {}
}
