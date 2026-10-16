package dev.incusspawn.vm;

import dev.incusspawn.util.OwnSession;
import dev.incusspawn.util.SessionProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The VM is started as the leader of a session of its own, so that it outlives the command, the
 * terminal and the launchd job that started it (#971). A session leader is also the leader of
 * its process group, which is what {@code ps} can show on both Linux and macOS.
 */
class VmOwnSessionTest {

    @Test
    void theVmProcessLeadsItsOwnSessionUnderThePidTheCallerGot() throws Exception {
        SessionProbe.assumeSession();

        var process = new ProcessBuilder(OwnSession.command(List.of("sleep", "30"))).start();
        try {
            // The wrapper execs the command in its own place; wait for that to have happened.
            var seen = SessionProbe.look(process.pid());
            for (int i = 0; i < 100 && !seen.command().endsWith("sleep"); i++) {
                Thread.sleep(50);
                seen = SessionProbe.look(process.pid());
            }
            assertTrue(seen.command().endsWith("sleep"), "the pid must be the command's own: " + seen);
            assertEquals(String.valueOf(process.pid()), seen.processGroup(),
                    "still in the process group of whoever started it, which takes it down with them");
        } finally {
            process.destroyForcibly();
        }
    }

    /** Before the exec the pid is the wrapper's, which {@code isRunning()} would take for a stale pid file. */
    @Test
    void thePidIsOnlyUsedOnceItIsTheCommandItself() throws Exception {
        SessionProbe.assumeSession();

        var process = new ProcessBuilder(OwnSession.command(List.of("sleep", "30"))).start();
        try {
            VmManager.awaitExec(process, "sleep");
            assertTrue(process.info().command().orElse("").endsWith("sleep"), process.info().toString());
        } finally {
            process.destroyForcibly();
        }
    }

    /** {@code ProcessBuilder} no longer sees a command that cannot be executed; the wrapper exits instead. */
    @Test
    void aCommandThatCannotBeExecutedIsReported() throws Exception {
        SessionProbe.assumeSession();

        var process = new ProcessBuilder(OwnSession.command(List.of("/nonexistent/vfkit")))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();

        var failure = assertThrows(java.io.IOException.class, () -> VmManager.awaitExec(process, "vfkit"));
        assertTrue(failure.getMessage().startsWith("vfkit could not be started"), failure.getMessage());
    }

    /** qemu is exposed to the same hangup, Ctrl+C and job-exit as vfkit was, and starts the same way (#993). */
    @Test
    void qemuIsStartedInASessionOfItsOwn(@TempDir Path dir) throws Exception {
        SessionProbe.assumeSession();
        var pidFile = dir.resolve("vm.pid");
        // By its real path, which is what the process reports: sleep may be a multicall binary's link.
        // /bin, not /usr/bin: macOS has it only there.
        var sleep = Path.of("/bin/sleep").toRealPath();
        var program = sleep.getFileName().toString();

        var process = VmManager.launchInOwnSession(List.of(sleep.toString(), "30"), program, dir.resolve("vm.log"), pidFile);
        try {
            var seen = SessionProbe.look(process.pid());
            assertTrue(seen.command().endsWith(program), "the recorded pid must be qemu's own: " + seen);
            assertEquals(String.valueOf(process.pid()), seen.processGroup(),
                    "still in the process group of whoever started it, which takes it down with them");
            assertEquals(String.valueOf(process.pid()), Files.readString(pidFile));
        } finally {
            process.destroyForcibly();
        }
    }
}
