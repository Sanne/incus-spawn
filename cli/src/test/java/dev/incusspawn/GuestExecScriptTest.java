package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Moments after a container started, incusd answered an exec with "Failed to retrieve PID of
 * executing child process" (exit 255): it could not attach to the guest, so the command never ran,
 * and host-distro's proxy-only phase failed with no change to blame (#2084).
 * {@code .github/scripts/guest-exec.sh} runs such an exec again, and only such an exec: a
 * command's own failure, which the no-egress checks rely on, is returned at once.
 */
class GuestExecScriptTest {

    private static final Path SCRIPTS = Path.of("../.github/scripts").toAbsolutePath();
    private static final String NO_ATTACH = "Error: Failed to retrieve PID of executing child process";
    private static final Pattern RAW_EXEC = Pattern.compile("\\bincus exec\\b");
    private static final Pattern CURL_FAILS_IN_GUEST =
            Pattern.compile("(?ms)^curl_fails_in_guest\\(\\) \\{$.*?^}$");

    @TempDir
    Path dir;

    @Test
    void anExecIncusCouldNotAttachRunsAgain() throws Exception {
        var result = guestExec(2, NO_ATTACH, 255);
        assertEquals(0, result.exitCode, result.output);
        assertEquals("3", runs(), result.output);
        assertTrue(result.output.contains("ran in the guest"), result.output);
        assertTrue(result.output.contains("Incus could not attach to box; trying again"), result.output);
    }

    @Test
    void anExecNeverAttachingFailsAsIncusDid() throws Exception {
        var result = guestExec(99, NO_ATTACH, 255);
        assertEquals(255, result.exitCode, result.output);
        // The first try and one per pause: GUEST_EXEC_PAUSES holds three
        assertEquals("4", runs(), result.output);
        assertTrue(result.output.contains(NO_ATTACH), "Incus's error must still be shown:\n" + result.output);
    }

    @Test
    void theCommandsOwnFailureIsNotRetried() throws Exception {
        // curl finding no route: the answer a no-egress check is asking for
        var result = guestExec(99, "curl: (7) Failed to connect to 1.1.1.1 port 80", 7);
        assertEquals(7, result.exitCode, result.output);
        assertEquals("1", runs(), result.output);
        assertTrue(result.output.contains("curl: (7)"), result.output);
    }

    @Test
    void anotherIncusErrorIsNotRetried() throws Exception {
        var result = guestExec(99, "Error: Instance is not running", 255);
        assertEquals(255, result.exitCode, result.output);
        assertEquals("1", runs(), result.output);
    }

    @Test
    void theHostDistroScriptsExecOnlyThroughIt() throws IOException {
        var proxyOnly = Files.readString(SCRIPTS.resolve("test-proxy-only.sh"));
        assertTrue(proxyOnly.contains("source \"$HERE/guest-exec.sh\""), "test-proxy-only.sh must source guest-exec.sh");
        assertFalse(RAW_EXEC.matcher(proxyOnly).find(), "an exec in test-proxy-only.sh bypasses guest_exec");
        // Negated, an exec Incus could not run at all (exit 255) would pass as blocked egress
        assertFalse(proxyOnly.contains("! guest_exec"), "a no-egress check must go through curl_fails_in_guest");

        // host-distro's instance phase execs into a branch moments after it started, too
        var hostDistro = Files.readString(SCRIPTS.resolve("test-host-distro.sh"));
        assertTrue(hostDistro.contains("source \"$HERE/guest-exec.sh\""), "test-host-distro.sh must source guest-exec.sh");
        assertFalse(RAW_EXEC.matcher(hostDistro).find(), "an exec in test-host-distro.sh bypasses guest_exec");

        var integration = Files.readString(Path.of("../.github/workflows/test-integration.yml"));
        assertTrue(integration.contains(".github/scripts/test-proxy-only.sh .github/scripts/guest-exec.sh"),
                "host-distro must push guest-exec.sh beside test-proxy-only.sh");
    }

    @Test
    void aNoEgressCheckPassesOnlyOnCurlNotConnecting() throws Exception {
        // Could not resolve, connect or finish: the answers the check is asking for
        for (int status : new int[] {6, 7, 28, 35, 45, 56}) {
            var result = curlFailsInGuest(status);
            assertEquals(0, result.exitCode, "exit " + status + " failed as egress:\n" + result.output);
        }
        // Incus could not reach the instance, Incus could not run the command, no curl in the guest
        for (int status : new int[] {1, 255, 127}) {
            var result = curlFailsInGuest(status);
            assertEquals(1, result.exitCode, "exit " + status + " passed as blocked egress:\n" + result.output);
            assertTrue(result.output.contains("curl in box exited " + status), result.output);
        }
    }

    /** test-proxy-only.sh's curl_fails_in_guest, against an incus whose exec exits with the status. */
    private Result curlFailsInGuest(int status) throws Exception {
        var script = Files.readString(SCRIPTS.resolve("test-proxy-only.sh"));
        var function = CURL_FAILS_IN_GUEST.matcher(script);
        assertTrue(function.find(), "test-proxy-only.sh must define curl_fails_in_guest");
        return run("""
                #!/bin/bash
                [ "$1 $2 $3" = "exec box --" ] || { echo "unexpected: $*" >&2; exit 2; }
                exit %d
                """.formatted(status), function.group() + "\ncurl_fails_in_guest box -- curl http://1.1.1.1/");
    }

    @Test
    void theCommandGetsNoStdin() throws Exception {
        // A retry would run the command again on input the first try already read
        var result = run("""
                #!/bin/bash
                if IFS= read -r line; then echo "read: $line"; else echo "no stdin"; fi
                """, "echo piped | guest_exec box -- true");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("no stdin"), result.output);
    }

    /** guest_exec against an incus whose exec fails {@code failures} times with the message and status. */
    private Result guestExec(int failures, String message, int status) throws Exception {
        return run("""
                #!/bin/bash
                [ "$1 $2 $3" = "exec box --" ] || { echo "unexpected: $*" >&2; exit 2; }
                runs=$(( $(cat "%1$s/runs" 2>/dev/null || echo 0) + 1 ))
                echo "$runs" > "%1$s/runs"
                [ "$runs" -gt %2$d ] && { echo "ran in the guest"; exit 0; }
                echo '%3$s' >&2
                exit %4$d
                """.formatted(dir, failures, message, status), "guest_exec box -- true");
    }

    /** The commands, with guest-exec.sh sourced, against the given stand-in for incus. */
    private Result run(String incus, String commands) throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(bin.resolve("incus"), incus);
        bin.resolve("incus").toFile().setExecutable(true);
        // No sudo: the forkexec log is best effort and must not hold up a try
        Files.writeString(bin.resolve("sudo"), "#!/bin/bash\nexit 1\n");
        bin.resolve("sudo").toFile().setExecutable(true);

        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", "-c",
                "set -euo pipefail; source \"$1\"; PATH=\"$2:$PATH\"; " + commands,
                "test", SCRIPTS.resolve("guest-exec.sh").toString(), bin.toString())
                .redirectErrorStream(true);
        builder.environment().put("GUEST_EXEC_PAUSES", "0 0 0");
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script hung:\n" + output);
        return new Result(process.exitValue(), output);
    }

    private String runs() throws IOException {
        return Files.readString(dir.resolve("runs")).strip();
    }

    private record Result(int exitCode, String output) {}
}
