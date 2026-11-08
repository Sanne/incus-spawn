package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code prepare} of {@code .github/scripts/test-stable-proofs.sh}, sourced with fake {@code isx},
 * {@code incus} and guest commands.
 * After a restart the guest answers before boot has started agentuser's lingering manager, and
 * Podman run then finds no writable {@code /run/user/1000} (#2048): {@code prepare} must wait for
 * systemd, then for that manager, through {@code wait-for-user-manager.sh}, and fail when either
 * does not come up. The CI workflow's rootless Podman steps run the same script.
 */
class StableProofsScriptTest {

    private static final Path SCRIPT = Path.of("../.github/scripts/test-stable-proofs.sh").toAbsolutePath();

    private static final String USER_MANAGER = "is-active --quiet user@1000.service";

    @TempDir
    Path bin;

    @Test
    void prepareWaitsForTheUserManagerPodmanNeeds() throws Exception {
        var result = prepare(0, 2);
        assertEquals(0, result.exitCode, result.output);
        assertEquals(List.of("show-environment", USER_MANAGER, USER_MANAGER, USER_MANAGER,
                "start systemd-networkd"), systemctlCalls());
    }

    /** Right after {@code incus start}, PID 1 may not answer on the system bus yet. */
    @Test
    void prepareWaitsForSystemdBeforeStartingTheUserManager() throws Exception {
        var result = prepare(3, 0);
        assertEquals(0, result.exitCode, result.output);
        assertEquals(List.of("show-environment", "show-environment", "show-environment", "show-environment",
                USER_MANAGER, "start systemd-networkd"), systemctlCalls());
    }

    @Test
    void prepareFailsWhenSystemdNeverAnswers() throws Exception {
        var result = prepare(1000, 0);
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("systemd did not answer on the system bus"), result.output);
        assertEquals(List.of("show-environment"), systemctlCalls().stream().distinct().toList());
    }

    /** Never started by hand: a build that stops enabling lingering must still fail here. */
    @Test
    void prepareFailsWhenTheUserManagerNeverRuns() throws Exception {
        var result = prepare(0, 1000);
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("user@1000.service did not become active"), result.output);
        assertFalse(systemctlCalls().stream().anyMatch(call -> call.startsWith("start")), result.output);
    }

    private List<String> systemctlCalls() throws Exception {
        return Files.readAllLines(bin.resolve("systemctl.log"));
    }

    /**
     * Runs {@code prepare} with a guest whose system bus fails {@code busDownCalls} times before it
     * answers, and whose user manager is reported inactive {@code managerDownCalls} times.
     */
    private Result prepare(int busDownCalls, int managerDownCalls) throws Exception {
        fake("isx", "exit 0");
        // incus exec <instance> -- <command>: the guest's command runs here, on the fakes.
        fake("incus", "[ \"$1\" = exec ] && shift 3 && exec \"$@\"");
        fake("id", "echo 1000");
        fake("ip", "echo '2: eth0 inet 10.0.0.2/24'");
        fake("sleep", "exit 0");
        var log = bin.resolve("systemctl.log");
        fake("systemctl", "echo \"$*\" >> '" + log + "'\n"
                + "case \"$*\" in\n"
                + "  show-environment) [ \"$(grep -c show-environment '" + log + "')\" -gt " + busDownCalls + " ];;\n"
                + "  *user@*) [ \"$(grep -c user@ '" + log + "')\" -gt " + managerDownCalls + " ];;\n"
                + "esac");
        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", "-c",
                "source \"$1\" && prepare", "test", SCRIPT.toString()).redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var outputFile = bin.resolve("output.log");
        builder.redirectOutput(outputFile.toFile());
        var process = builder.start();
        var finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly().waitFor();
        var output = Files.readString(outputFile, StandardCharsets.UTF_8);
        assertTrue(finished, "prepare hung:\n" + output);
        return new Result(process.exitValue(), output);
    }

    private void fake(String name, String body) throws Exception {
        var file = bin.resolve(name);
        Files.writeString(file, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private record Result(int exitCode, String output) {}
}
