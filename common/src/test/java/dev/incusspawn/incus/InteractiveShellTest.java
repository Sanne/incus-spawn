package dev.incusspawn.incus;

import dev.incusspawn.lifecycle.TempHome;
import dev.incusspawn.tool.GeminiSetup;
import dev.incusspawn.tool.ShellMenu;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.RecordingOutput.Event;
import dev.incusspawn.util.RecordingOutput.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveShellTest {

    @TempDir
    Path bin;

    @Test
    void geminiActionPreservesTheLegacyLoginGuardWhenZmxExecutesIt() throws Exception {
        var binary = bin.resolve("gemini");
        Files.writeString(binary, "#!/bin/sh\necho 'Gemini binary launched'\nexit 42\n");
        assertTrue(binary.toFile().setExecutable(true));

        var action = new GeminiSetup().actions().getFirst().getCommand();
        // An old guest's login profile exports this guard. Like zmx, exec launches
        // the supplied executable directly instead of resolving shell functions.
        var script = """
                export PATH="$1:$PATH"
                gemini() {
                    echo 'Gemini login through isx is not supported yet' >&2
                    return 1
                }
                export -f gemini
                exec %s
                """.formatted(InteractiveShell.zmxCommand(action));
        var builder = new ProcessBuilder("bash", "--noprofile", "--norc", "-c", script,
                "bash", bin.toString()).redirectErrorStream(true);
        var path = builder.environment().get("PATH");
        builder.environment().clear();
        builder.environment().put("PATH", path);
        var process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Gemini action did not exit");
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, process.exitValue(), output);
            assertTrue(output.contains("Gemini login through isx is not supported yet"), output);
            assertFalse(output.contains("Gemini binary launched"), output);
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * The session sets the terminal's title through the bound output, and resets it however the
     * session ends (#1703): here a daemon that serves no exec, so the session fails at once.
     */
    @Test
    // An empty home: the status bar's feature is off, as on a fresh host
    @ExtendWith(TempHome.class)
    void theTitleIsSetAndResetThroughTheBoundOutput() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of());
        var prep = IncusClient.ShellPrep.fromPrefetched(null, null, null, null, true, null);
        var said = RecordingOutput.during(() -> assertThrows(RuntimeException.class,
                () -> new InteractiveShell(daemon.client()).session("dev", "agentuser", prep, ShellMenu.NONE, false, null)));
        assertEquals(List.of(new Event(Kind.WINDOW_TITLE, "isx:dev"), new Event(Kind.WINDOW_TITLE, "")),
                said.events());
    }

    /** A terminfo propagation that fails still leaves the terminal's title as it found it. */
    @Test
    @ExtendWith(TempHome.class)
    void aFailedTerminfoPropagationStillResetsTheTitle() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of());
        var prep = IncusClient.ShellPrep.fromPrefetched(null, null, null, null, false, null);
        var said = RecordingOutput.during(() -> assertThrows(RuntimeException.class,
                () -> new InteractiveShell(daemon.client()).session("dev", "agentuser", prep, ShellMenu.NONE, false, "xterm-kitty")));
        assertEquals(List.of(new Event(Kind.WINDOW_TITLE, "isx:dev"), new Event(Kind.WINDOW_TITLE, "")),
                said.events());
    }

    /**
     * A session that ran and then dropped is a loss of its own: each is a warning and waits the
     * first delay again, never a later retry of the one before (a second drop read "attempt 2",
     * and enough of them would overflow the delay).
     */
    @Test
    void eachDropOfASessionThatRanIsANewLoss() {
        var lost = new ArrayDeque<>(List.of(true, true, true, false));
        var now = new long[1];
        var delays = new ArrayList<Long>();
        var said = RecordingOutput.during(() -> InteractiveShell.reconnecting(() -> {
            now[0] += 60_000; // a minute of work in the shell
            return lost.pop();
        }, delays::add, () -> now[0]));
        assertEquals(List.of(1000L, 1000L, 1000L), delays);
        assertEquals(List.of(Kind.WARN, Kind.WARN, Kind.WARN), said.events().stream().map(Event::kind).toList());
    }

    /**
     * A connection that comes back and drops at once is a reconnect that did not hold: a note,
     * a growing wait, and given up after the last attempt rather than retried every second forever.
     */
    @Test
    void aConnectionThatDropsAsSoonAsItComesBackIsGivenUp() {
        var delays = new ArrayList<Long>();
        var said = RecordingOutput.during(() -> assertThrows(IncusException.class,
                () -> InteractiveShell.reconnecting(() -> true, delays::add, () -> 0L)));
        assertEquals(List.of(1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L, 10_000L, 10_000L, 10_000L, 10_000L), delays);
        assertEquals(new Event(Kind.WARN, "Connection lost — reconnecting..."), said.events().getFirst());
        assertEquals(new Event(Kind.NOTE, "Still reconnecting (attempt 10 of 10)"), said.events().getLast());
    }

    /** A connection that fails to come back is retried with a growing wait, as notes, then given up. */
    @Test
    void failedReconnectsAreNotesUntilTheLastAttempt() {
        var delays = new ArrayList<Long>();
        var failure = new IncusException("reconnect failed", new IOException("connection refused"));
        var said = RecordingOutput.during(() -> assertSame(failure, assertThrows(IncusException.class,
                () -> InteractiveShell.reconnecting(() -> { throw failure; }, delays::add, () -> 0L))));
        assertEquals(List.of(1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L, 10_000L, 10_000L, 10_000L, 10_000L), delays);
        var events = said.events();
        assertEquals(new Event(Kind.WARN, "Connection lost — reconnecting..."), events.getFirst());
        assertEquals(new Event(Kind.NOTE, "Still reconnecting (attempt 10 of 10)"), events.getLast());
        assertEquals(10, events.size());
    }

    /** #2569: actions and shell commands clear the visible display; plain shells leave it alone. */
    @Test
    void onlyActionAndCommandSessionsClearTheScreen() {
        var plain = IncusClient.ShellPrep.fromPrefetched(null, null, null, null, true, "tpl-agent");
        assertFalse(InteractiveShell.clearsScreen(plain));
        assertTrue(InteractiveShell.clearsScreen(plain.withActionCommand("claude")));
        assertTrue(InteractiveShell.clearsScreen(plain.withCommandOnly("claude --resume id")));
        var shellCmd = IncusClient.ShellPrep.fromPrefetched(null, "claude", null, null, true, "tpl-agent");
        assertTrue(InteractiveShell.clearsScreen(shellCmd));
    }
}
