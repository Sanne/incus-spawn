package dev.incusspawn;

import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitHub answered CI's github.com downloads with 503s and 504s, and let them time out, for most
 * of an hour (#2042): the gitleaks install, isx's base-image download (after isx's own retries
 * ran out) and host-distro's HTTPS reachability check all failed with no change to blame.
 * {@code .github/scripts/net-retry.sh} rides that out without weakening any of them: a build is
 * run again only when isx's download retries ran out, and the reachability check still needs a
 * reply, from github.com or a host on another network.
 */
class NetRetryScriptTest {

    private static final Path SCRIPT = Path.of("../.github/scripts/net-retry.sh").toAbsolutePath();
    private static final Path WORKFLOWS = Path.of("../.github/workflows");
    /** What DownloadCache says once its own retries of a transient failure ran out. */
    private static final String EXHAUSTED = "Error: Build failed for tpl-minimal: Failed to download base image from "
            + "https://github.com/x/y.tar.xz: Download failed: HTTP 504 from github.com for "
            + "https://github.com/x/y.tar.xz (after 5 attempts)";
    private static final Pattern RAW_BUILD = Pattern.compile("(?<!retry_download_outage )isx build tpl-");

    @TempDir
    Path dir;

    @Test
    void aBuildWhoseDownloadRetriesRanOutRunsAgain() throws Exception {
        var command = fakeCommand(2, EXHAUSTED);
        var result = shell("retry_download_outage " + command);
        assertEquals(0, result.exitCode, result.output);
        assertEquals("3", Files.readString(dir.resolve("runs")).strip(), result.output);
        assertTrue(result.output.contains("running '" + command + "' again in 0s"), result.output);
    }

    @Test
    void anOutageOutlastingEveryRerunFailsWithTheBuildsStatus() throws Exception {
        var result = shell("retry_download_outage " + fakeCommand(99, EXHAUSTED));
        assertEquals(7, result.exitCode, result.output);
        // The first run and one per pause: NET_RETRY_PAUSES holds two
        assertEquals("3", Files.readString(dir.resolve("runs")).strip(), result.output);
        assertTrue(result.output.contains("the download failed on every run"), result.output);
    }

    @Test
    void anyOtherFailureIsNotRetried() throws Exception {
        // isx's message for a checksum mismatch: retrying could not change it, and must not hide it
        var result = shell("retry_download_outage " + fakeCommand(99,
                "Download failed: SHA-256 mismatch for https://github.com/x/y.tar.xz"));
        assertEquals(7, result.exitCode, result.output);
        assertEquals("1", Files.readString(dir.resolve("runs")).strip(), result.output);
    }

    @Test
    void theRetriedMessageIsTheOneDownloadCacheWrites() throws IOException {
        var cache = Files.readString(Path.of("../common/src/main/java/dev/incusspawn/tool/DownloadCache.java"));
        assertTrue(cache.contains("var message = \"Download failed: \""), "DownloadCache's exhausted() message moved");
        assertTrue(cache.contains("message += \" (after \" + failures.size() + \" attempts)\""),
                "DownloadCache's exhausted() message moved");
    }

    @Test
    void githubDownMeansAnotherHostIsAsked() throws Exception {
        var result = probe(Map.of("https://github.com/", "000", "https://other.example/", "200"));
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("https://github.com/ did not reply but https://other.example/ did"), result.output);
    }

    @Test
    void anyStatusIsAReply() throws Exception {
        var result = probe(Map.of("https://github.com/", "504", "https://other.example/", "000"));
        assertEquals(0, result.exitCode, result.output);
        assertFalse(result.output.contains("did not reply"), result.output);
    }

    @Test
    void noReplyFromAnyHostInAnyRoundFails() throws Exception {
        var result = probe(Map.of("https://github.com/", "000", "https://other.example/", "000"));
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("the host does not pass HTTPS replies"), result.output);
        // One ask per host per round: NET_RETRY_PROBE_PAUSES holds two pauses, so three rounds
        assertEquals(6, Files.readAllLines(dir.resolve("asked")).size(), result.output);
    }

    @Test
    void ciDownloadsGoThroughTheRetries() throws IOException {
        var gitleaks = JqYaml.parse(Files.readString(WORKFLOWS.resolve("gitleaks.yml")));
        var install = gitleaks.path("jobs").path("allowlist-test").path("steps").asList().stream()
                .filter(s -> s.pathText("name", "").startsWith("Install gitleaks")).findFirst().orElseThrow()
                .pathText("run", "");
        assertTrue(install.contains("--retry 8 --retry-all-errors"), install);
        assertTrue(install.contains("sha256sum -c"), "the checksum must still decide:\n" + install);

        var integration = Files.readString(WORKFLOWS.resolve("test-integration.yml"));
        assertFalse(RAW_BUILD.matcher(integration).find(), "a template build in test-integration.yml bypasses the retry");
        assertTrue(integration.contains(".github/scripts/test-host-distro.sh .github/scripts/net-retry.sh"),
                "host-distro must push net-retry.sh beside test-host-distro.sh");

        var hostDistro = Files.readString(Path.of("../.github/scripts/test-host-distro.sh"));
        assertTrue(hostDistro.contains("source \"$HERE/net-retry.sh\""), "test-host-distro.sh must source net-retry.sh");
        assertTrue(hostDistro.contains("retry_download_outage isx build tpl-minimal"), hostDistro);
        assertTrue(hostDistro.contains("https_reply_from_any https://github.com/"), hostDistro);

        var macosRole = Files.readString(Path.of("../appliance/test-macos-role.sh"));
        assertFalse(RAW_BUILD.matcher(macosRole.replace("isx build tpl-macos-role", "")).find(),
                "test-macos-role.sh builds tpl-minimal without the retry");
    }

    /** A command that fails {@code failures} times with {@code message}, then succeeds; it counts its runs. */
    private String fakeCommand(int failures, String message) throws IOException {
        var command = dir.resolve("fake-build");
        Files.writeString(command, """
                #!/bin/bash
                runs=$(( $(cat "%1$s/runs" 2>/dev/null || echo 0) + 1 ))
                echo "$runs" > "%1$s/runs"
                [ "$runs" -gt %2$d ] && { echo built; exit 0; }
                echo '%3$s' >&2
                exit 7
                """.formatted(dir, failures, message));
        command.toFile().setExecutable(true);
        return command.toString();
    }

    /** {@code https_reply_from_any} against a curl that answers each URL with the given status. */
    private Result probe(Map<String, String> statuses) throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        var cases = new StringBuilder();
        statuses.forEach((url, code) -> cases.append("    '").append(url).append("') code=").append(code).append(" ;;\n"));
        Files.writeString(bin.resolve("curl"), """
                #!/bin/bash
                url=${@: -1}
                echo "$url" >> "%s/asked"
                case "$url" in
                %s    *) code=000 ;;
                esac
                printf '%%s' "$code"
                [ "$code" != 000 ] || { echo "curl: (28) Connection timed out" >&2; exit 28; }
                """.formatted(dir, cases));
        bin.resolve("curl").toFile().setExecutable(true);
        return shell("PATH=\"" + bin + ":$PATH\" https_reply_from_any https://github.com/ https://other.example/");
    }

    private Result shell(String command) throws Exception {
        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", "-c",
                "set -euo pipefail; source \"$1\"; " + command, "test", SCRIPT.toString())
                .redirectErrorStream(true);
        builder.environment().put("NET_RETRY_PAUSES", "0 0");
        builder.environment().put("NET_RETRY_PROBE_PAUSES", "0 0");
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script hung:\n" + output);
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {}
}
