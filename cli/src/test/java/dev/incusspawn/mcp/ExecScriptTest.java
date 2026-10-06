package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the scripts {@link ExecScript} generates in a real local bash, as the instance's login
 * shell would (minus {@code su}), with {@code HOME} pointed at a temporary directory. Linux only:
 * the script is written for the guest, which has {@code setsid}, GNU {@code timeout} and the
 * {@code /proc} this test counts a session's processes in, and a macOS host has none of them (#1016).
 */
@EnabledOnOs(OS.LINUX)
class ExecScriptTest {

    @TempDir
    Path home;

    private record Run(int exit, String stdout, String stderr) {}

    private Run run(String script) throws Exception {
        var pb = new ProcessBuilder("bash", "-c", script);
        pb.environment().put("HOME", home.toString());
        pb.directory(home.toFile());
        var p = pb.start();
        p.getOutputStream().close();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "script did not finish");
        return new Run(p.exitValue(),
                new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private String build(String cwd, Map<String, String> env, String command, Integer timeout) {
        return ExecScript.build("exec-1-1", cwd, env, command, timeout);
    }

    @Test
    void theCommandsExitCodeAndOutputComeBack() throws Exception {
        var r = run(build(home.toString(), Map.of(), "echo out; echo err >&2; exit 7", null));
        assertEquals(7, r.exit());
        assertEquals("out\n", r.stdout());
        assertEquals("err\n", r.stderr());
    }

    @Test
    void itRunsInTheRequestedDirectoryRelativeToHome() throws Exception {
        Files.createDirectories(home.resolve("repo/sub"));
        assertEquals(home.resolve("repo/sub").toRealPath() + "\n",
                run(build("repo/sub", Map.of(), "pwd -P", null)).stdout());
    }

    @Test
    void aMissingDirectoryFailsBeforeTheCommandRuns() throws Exception {
        var r = run(build("nope", Map.of(), "touch ran", null));
        assertEquals(125, r.exit());
        assertFalse(Files.exists(home.resolve("ran")));
    }

    @Test
    void hostileValuesReachTheCommandVerbatim() throws Exception {
        var env = new LinkedHashMap<String, String>();
        env.put("TRICKY", "a'b\"c $(touch pwned) `touch pwned2` ; exit 3");
        var dir = "it's a \"dir\" $(touch pwned3)";
        Files.createDirectories(home.resolve(dir));
        var r = run(build(dir, env, "printf '%s|%s' \"$TRICKY\" \"$(basename \"$PWD\")\"", null));
        assertEquals(0, r.exit(), r.stderr());
        assertEquals(env.get("TRICKY") + "|" + dir, r.stdout());
        try (var files = Files.list(home)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith("pwned")));
        }
    }

    @Test
    void theCommandsStdinIsForwarded() throws Exception {
        var pb = new ProcessBuilder("bash", "-c", build(home.toString(), Map.of(), "tr a-z A-Z", null));
        pb.environment().put("HOME", home.toString());
        var p = pb.start();
        p.getOutputStream().write("hello\n".getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals("HELLO\n", new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aTimeoutKillsTheCommand() throws Exception {
        var start = System.nanoTime();
        var r = run(build(home.toString(), Map.of(), "sleep 30", 1));
        assertEquals(124, r.exit());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(15));
    }

    @Test
    void killingARunStopsItsWholeProcessTree() throws Exception {
        // A command that forks: a background child and a foreground one.
        var pb = new ProcessBuilder("bash", "-c",
                build(home.toString(), Map.of(), "sleep 300 & sleep 300; echo finished", null));
        pb.environment().put("HOME", home.toString());
        pb.redirectErrorStream(true);
        var p = pb.start();
        var pidFile = home.resolve(".isx-mcp/run/exec-1-1.pid");
        waitFor(() -> Files.exists(pidFile) && !readQuietly(pidFile).isBlank());
        var session = Long.parseLong(readQuietly(pidFile).strip());
        waitFor(() -> countInSession(session) >= 2);

        run(ExecScript.kill("exec-1-1"));

        assertTrue(p.waitFor(15, TimeUnit.SECONDS), "the run did not end");
        waitFor(() -> countInSession(session) == 0);
        assertFalse(Files.exists(pidFile), "the pid file is removed");
    }

    @Test
    void aFifoInPlaceOfThePidFileHoldsNoRun() throws Exception {
        // Run ids are predictable: one could be waiting there before the run writes its pid.
        var pidFile = Files.createDirectories(home.resolve(".isx-mcp/run")).resolve("exec-1-1.pid");
        new ProcessBuilder("mkfifo", pidFile.toString()).start().waitFor();
        var pb = new ProcessBuilder("bash", "-c", build(home.toString(), Map.of(), "echo ran", null));
        pb.environment().put("HOME", home.toString());
        var p = pb.start();
        try {
            assertTrue(p.waitFor(10, TimeUnit.SECONDS), "the run waited on a FIFO");
            assertEquals("ran\n", new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(Files.isRegularFile(pidFile), "the FIFO is replaced, never written through");
        } finally {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    @Test
    void aFifoInPlaceOfThePidFileHoldsNoKill() throws Exception {
        // Anyone in the instance can put one there; reading it would wait for a writer.
        var pidFile = Files.createDirectories(home.resolve(".isx-mcp/run")).resolve("exec-1-1.pid");
        new ProcessBuilder("mkfifo", pidFile.toString()).start().waitFor();
        var pb = new ProcessBuilder("bash", "-c", ExecScript.kill("exec-1-1"));
        pb.environment().put("HOME", home.toString());
        var p = pb.start();
        try {
            assertTrue(p.waitFor(10, TimeUnit.SECONDS), "the kill waited on a FIFO");
        } finally {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    @Test
    void killingARunThatAlreadyEndedIsHarmless() throws Exception {
        assertEquals(0, run(ExecScript.kill("exec-9-9")).exit());
    }

    private static long countInSession(long sid) {
        try (var procs = Files.list(Path.of("/proc"))) {
            return procs.filter(d -> d.getFileName().toString().matches("\\d+"))
                    .filter(d -> sessionOf(d) == sid).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sessionOf(Path procDir) {
        try {
            var stat = Files.readString(procDir.resolve("stat"));
            // Fields after the parenthesised command: state ppid pgrp session ...
            var fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(fields[3]);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return "";
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in time");
            Thread.sleep(50);
        }
    }
}
