package dev.incusspawn.incus;

import dev.incusspawn.incus.IncusClient.ShellPrep;
import dev.incusspawn.tool.ClaudeSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ctrl+Z in an action suspends it to a shell that {@code fg} resumes it from (#2333). The script the
 * login runs, run in a real bash on a real terminal (util-linux {@code script}), with Claude Code's
 * own action command and a stand-in {@code claude} that suspends itself the way Claude Code does:
 * it hands the terminal back, then sends {@code SIGTSTP} to its process group. Without a
 * job-control shell above it the kernel discards that stop, and the session is lost.
 */
class InteractiveShellJobControlTest {

    private static final long WAIT_MS = 15_000;

    @TempDir
    Path dir;

    @Test
    void ctrlZInAnActionGivesAShellThatFgResumesItFrom() throws Exception {
        assertResumable(claudeAction(false));
    }

    /** The same in the zmx session the action runs in: zmx runs the command with no shell above it either. */
    @Test
    void ctrlZInAnActionInZmxGivesAShellThatFgResumesItFrom() throws Exception {
        assertResumable(claudeAction(true));
    }

    /** A command that goes on after the suspended one and succeeds still leaves the shell to resume it from. */
    @Test
    void ctrlZInACommandThatGoesOnGivesAShellThatFgResumesItFrom() throws Exception {
        assertResumable(new ShellPrep(null, "claude; true", false, false, null, true, "tpl"));
    }

    /** The session still ends when the action succeeds, as auto-return wants. */
    @Test
    void theSessionEndsWhenTheActionSucceeds() throws Exception {
        var process = start(claudeAction(false), "echo DONE\n");
        try {
            assertTrue(process.waitFor(WAIT_MS, TimeUnit.MILLISECONDS), "the session did not end");
        } finally {
            process.destroyForcibly();
        }
    }

    /** A failed action leaves a shell, as before. */
    @Test
    void aFailedActionLeavesAShell() throws Exception {
        var process = start(claudeAction(false), "echo RUNNING; exit 3\n");
        var out = new Transcript(process.getInputStream());
        var in = process.getOutputStream();
        try {
            out.await("RUNNING", process);
            type(in, "echo SHELL-$((40+2))\n");
            out.await("SHELL-42", process);
            type(in, "exit\n");
            assertTrue(process.waitFor(WAIT_MS, TimeUnit.MILLISECONDS), "the shell did not exit:\n" + out);
        } finally {
            process.destroyForcibly();
        }
    }

    private static ShellPrep claudeAction(boolean zmx) {
        return new ShellPrep(null, ClaudeSetup.actionCommand("claude --continue", "claude"), false, zmx, null, true, "tpl");
    }

    private void assertResumable(ShellPrep prep) throws Exception {
        var process = start(prep, "echo RUNNING; kill -TSTP 0; echo RESUMED\n");
        var out = new Transcript(process.getInputStream());
        var in = process.getOutputStream();
        try {
            out.await("RUNNING", process);
            type(in, "echo SHELL-$((40+2))\n");
            out.await("SHELL-42", process);
            type(in, "fg\n");
            out.await("RESUMED", process);
            type(in, "exit\n");
            assertTrue(process.waitFor(WAIT_MS, TimeUnit.MILLISECONDS), "the shell did not exit:\n" + out);
        } finally {
            process.destroyForcibly();
        }
    }

    /** The login's script on a terminal of its own, with {@code claude} doing {@code claudeBody}. */
    private Process start(ShellPrep prep, String claudeBody) throws IOException {
        assumeTrue(utilLinuxScript(), "needs util-linux script for a real terminal");
        var home = Files.createDirectories(dir.resolve("home"));
        var bin = Files.createDirectories(dir.resolve("bin"));
        stub(bin, "claude", claudeBody);
        // zmx as it runs a session's command, given as `zmx attach <session> bash -c <script>`:
        // on a terminal of its own, as that terminal's session leader
        stub(bin, "zmx", "exec script -qefc \"$5\" /dev/null\n");

        var builder = new ProcessBuilder("script", "-qefc", InteractiveShell.innerScript(prep, false, "k"), "/dev/null")
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", bin + ":/usr/bin:/bin");
        builder.environment().put("HOME", home.toString());
        builder.environment().put("SHELL", "/bin/bash");
        builder.environment().put("TERM", "dumb");
        return builder.start();
    }

    /** Typed on the terminal; a session that already ended is reported by the next wait for its output. */
    private static void type(OutputStream in, String text) {
        try {
            in.write(text.getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (IOException ended) {
            // the terminal is gone
        }
    }

    /** What the terminal showed, read as it arrives. */
    private static final class Transcript {
        private final StringBuffer text = new StringBuffer();

        Transcript(InputStream stream) {
            Thread.ofPlatform().daemon().start(() -> {
                var buf = new byte[4096];
                try {
                    for (int n; (n = stream.read(buf)) > 0; ) text.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the terminal closed
                }
            });
        }

        void await(String marker, Process process) throws Exception {
            dev.incusspawn.util.Eventually.await(marker + " on the terminal", java.time.Duration.ofMillis(WAIT_MS), () -> {
                if (text.indexOf(marker) >= 0) return true;
                if (!process.isAlive() && process.waitFor(200, TimeUnit.MILLISECONDS) && text.indexOf(marker) < 0) {
                    fail("the session ended before " + marker + ":\n" + text);
                }
                return false;
            }, () -> text.toString());
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static void stub(Path bin, String name, String body) throws IOException {
        var file = bin.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body);
        assertTrue(file.toFile().setExecutable(true));
    }

    private static boolean utilLinuxScript() {
        try {
            var p = new ProcessBuilder("script", "--version").redirectErrorStream(true).start();
            var version = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor(5, TimeUnit.SECONDS) && version.contains("util-linux")
                    && Files.isExecutable(Path.of("/bin/bash"));
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
