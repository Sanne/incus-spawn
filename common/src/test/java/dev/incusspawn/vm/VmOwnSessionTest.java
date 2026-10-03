package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The VM is started as the leader of a session of its own, so that it outlives the command, the
 * terminal and the launchd job that started it (#971). A session leader is also the leader of
 * its process group, which is what {@code ps} can show on both Linux and macOS.
 */
class VmOwnSessionTest {

    private record Seen(String processGroup, String command) {}

    private static Seen look(long pid) throws Exception {
        var ps = new ProcessBuilder("ps", "-o", "pgid=,comm=", "-p", String.valueOf(pid))
                .redirectErrorStream(true).start();
        var fields = new String(ps.getInputStream().readAllBytes()).strip().split("\\s+", 2);
        ps.waitFor();
        return new Seen(fields[0], fields.length > 1 ? fields[1] : "");
    }

    /** Without perl the command is left as it is; some Linux images also package POSIX apart. */
    private static void assumePerl() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/perl")), "no /usr/bin/perl");
        assumeTrue(new ProcessBuilder("/usr/bin/perl", "-MPOSIX=setsid", "-e", "1")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0,
                "perl has no POSIX module");
    }

    @Test
    void theVmProcessLeadsItsOwnSessionUnderThePidTheCallerGot() throws Exception {
        assumePerl();

        var process = new ProcessBuilder(VmManager.inOwnSession(List.of("sleep", "30"))).start();
        try {
            // The wrapper execs the command in its own place; wait for that to have happened.
            var seen = look(process.pid());
            for (int i = 0; i < 100 && !seen.command().endsWith("sleep"); i++) {
                Thread.sleep(50);
                seen = look(process.pid());
            }
            assertTrue(seen.command().endsWith("sleep"), "the pid must be the command's own: " + seen);
            assertEquals(String.valueOf(process.pid()), seen.processGroup(),
                    "still in the process group of whoever started it, which takes it down with them");
        } finally {
            process.destroyForcibly();
        }
    }

    /** Before the exec the pid is perl's, which {@code isRunning()} would take for a stale pid file. */
    @Test
    void thePidIsOnlyUsedOnceItIsTheCommandItself() throws Exception {
        assumePerl();

        var process = new ProcessBuilder(VmManager.inOwnSession(List.of("sleep", "30"))).start();
        try {
            VmManager.awaitExec(process, "sleep");
            assertTrue(process.info().command().orElse("").endsWith("sleep"), process.info().toString());
        } finally {
            process.destroyForcibly();
        }
    }

    /** {@code ProcessBuilder} no longer sees a command that cannot be executed; perl exits instead. */
    @Test
    void aCommandThatCannotBeExecutedIsReported() throws Exception {
        assumePerl();

        var process = new ProcessBuilder(VmManager.inOwnSession(List.of("/nonexistent/vfkit")))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();

        var failure = assertThrows(java.io.IOException.class, () -> VmManager.awaitExec(process, "vfkit"));
        assertTrue(failure.getMessage().startsWith("vfkit could not be started"), failure.getMessage());
    }

    /** vfkit's arguments carry spaces, commas, equals signs and paths: none may be reinterpreted. */
    @Test
    void theWrappedCommandGetsEveryArgumentAsItWas() throws Exception {
        assumePerl();
        var args = List.of("--kernel-cmdline", "console=hvc0 root=/dev/vda", "--device", "x,y=$z", "-e", "a'b\"c", "");
        var cmd = new java.util.ArrayList<>(List.of("printf", "%s\\n"));
        cmd.addAll(args);

        var process = new ProcessBuilder(VmManager.inOwnSession(cmd)).redirectErrorStream(true).start();
        var printed = new String(process.getInputStream().readAllBytes());

        assertEquals(0, process.waitFor(), printed);
        assertEquals(String.join("\n", args) + "\n", printed);
    }
}
