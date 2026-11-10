package dev.incusspawn.incus;

import dev.incusspawn.incus.IncusClient.ShellPrep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which session a shell enters (#2059): the script the login runs, run in a real bash against
 * stand-ins for zmx and tmux that print what they were asked, so the test sees the session a
 * terminal lands in rather than the script's wording.
 */
class InteractiveShellSessionTest {

    @TempDir
    Path dir;

    /** zmx and tmux as the guest has them: {@code sessions} lists the ones already there. */
    private String run(ShellPrep prep, boolean inTmux, boolean zmx, boolean tmux, String... sessions)
            throws IOException, InterruptedException {
        return run(prep, inTmux, InteractiveShell.newSessionKey(), zmx, tmux, sessions);
    }

    /** As one connection of the shell whose new session {@code key} names. */
    private String run(ShellPrep prep, boolean inTmux, String key, boolean zmx, boolean tmux, String... sessions)
            throws IOException, InterruptedException {
        var run = Files.createTempDirectory(dir, "run");
        var bin = Files.createDirectories(run.resolve("bin"));
        Files.writeString(run.resolve("sessions"), sessions.length == 0 ? "" : String.join("\n", sessions) + "\n");
        if (zmx) {
            stub(bin, "zmx", """
                    if [ "$1" = list ]; then while read -r s; do echo "$s"; done < "$SESSIONS"; exit 0; fi
                    echo "zmx $*"
                    """);
        }
        if (tmux) {
            stub(bin, "tmux", """
                    if [ "$1" = has-session ]; then grep -qxF "${3#=}" "$SESSIONS"; exit $?; fi
                    echo "tmux $*"
                    """);
        }
        stub(bin, "infocmp", "exit 0\n");
        stub(bin, "bash", "echo \"plain $*\"\n");
        // Only the stand-ins and grep: the host's own zmx or tmux must not answer
        Files.createSymbolicLink(bin.resolve("grep"), Path.of(which("grep")));
        var builder = new ProcessBuilder("/bin/sh", "-c", InteractiveShell.innerScript(prep, inTmux, key))
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", bin.toString());
        builder.environment().put("SESSIONS", run.resolve("sessions").toString());
        builder.environment().put("XDG_RUNTIME_DIR", Files.createDirectories(dir.resolve("runtime")).toString());
        var process = builder.start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "the script did not exit");
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    }

    private static void stub(Path bin, String name, String body) throws IOException {
        var file = bin.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body);
        assertTrue(file.toFile().setExecutable(true));
    }

    private static String which(String tool) {
        for (var d : new String[] {"/usr/bin", "/bin"}) {
            if (Files.isExecutable(Path.of(d, tool))) return d + "/" + tool;
        }
        throw new AssertionError("no " + tool + " on this host");
    }

    private static ShellPrep prep(boolean tmux, boolean zmx, ShellSession session) {
        return new ShellPrep(null, null, tmux, zmx, null, true, "tpl").withSession(session);
    }

    @Test
    void theDefaultShellEntersTheInstancesOwnSession() throws Exception {
        assertEquals("zmx attach isx bash --login", run(prep(false, true, ShellSession.DEFAULT), false, true, false));
        assertEquals("tmux new-session -A -s isx", run(prep(true, false, ShellSession.DEFAULT), false, false, true));
        // In the host's tmux the default session is skipped, as before
        assertEquals("plain --login", run(prep(false, true, ShellSession.DEFAULT), true, true, false));
    }

    @Test
    void aNamedSessionIsEnteredInsteadOfTheDefault() throws Exception {
        assertEquals("zmx attach work bash --login",
                run(prep(false, true, ShellSession.named("work")), false, true, false, "isx"));
        assertEquals("tmux new-session -A -s work",
                run(prep(true, false, ShellSession.named("work")), false, false, true, "isx"));
    }

    /** Asked for by name, the session is entered even in the host's tmux, which skips the default one. */
    @Test
    void aNamedSessionIsEnteredInTheHostsTmuxToo() throws Exception {
        assertEquals("zmx attach work bash --login",
                run(prep(false, true, ShellSession.named("work")), true, true, false));
    }

    /** Neither multiplexer auto-attaches: a named session still goes to the one the guest has, zmx first. */
    @Test
    void aNamedSessionUsesWhicheverMultiplexerTheGuestHas() throws Exception {
        assertEquals("zmx attach work bash --login",
                run(prep(false, false, ShellSession.named("work")), false, true, true));
        assertEquals("tmux new-session -A -s work",
                run(prep(false, false, ShellSession.named("work")), false, false, true));
        // tmux auto-attaches: it is the one asked first
        assertEquals("tmux new-session -A -s work",
                run(prep(true, false, ShellSession.named("work")), false, true, true));
        assertEquals("plain --login", run(prep(false, false, ShellSession.named("work")), false, false, false));
    }

    @Test
    void aNewSessionTakesTheFirstFreeNumber() throws Exception {
        assertEquals("zmx attach 2 bash --login", run(prep(false, true, ShellSession.NEW), false, true, false, "isx"));
        assertEquals("zmx attach 4 bash --login",
                run(prep(false, true, ShellSession.NEW), false, true, false, "isx", "2", "3", "5"));
        assertEquals("tmux new-session -A -s 3",
                run(prep(true, false, ShellSession.NEW), false, false, true, "isx", "2"));
    }

    /**
     * A reconnect runs the shell's script again, and must land in the session it made rather than
     * take the next free number: the session it left would be orphaned, the opposite of persistence.
     */
    @Test
    void aReconnectReentersTheNewSessionItMade() throws Exception {
        var key = InteractiveShell.newSessionKey();
        assertEquals("zmx attach 2 bash --login", run(prep(false, true, ShellSession.NEW), false, key, true, false, "isx"));
        assertEquals("zmx attach 2 bash --login", run(prep(false, true, ShellSession.NEW), false, key, true, false, "isx", "2"));
        // Another shell's n still gets a session of its own
        assertEquals("zmx attach 3 bash --login", run(prep(false, true, ShellSession.NEW), false, true, false, "isx", "2"));
        assertEquals("tmux new-session -A -s 2", run(prep(true, false, ShellSession.NEW), false, key, false, true, "isx", "2"));
    }

    @Test
    void noSessionIsAPlainLoginShell() throws Exception {
        assertEquals("plain --login", run(prep(false, true, ShellSession.NONE), false, true, false));
        assertEquals("plain --login", run(prep(true, false, ShellSession.NONE), false, false, true));
    }

    /** A template's shell command runs in the session asked for, and in none with --no-attach. */
    @Test
    void aShellCommandRunsInTheSessionAskedFor() throws Exception {
        var prep = new ShellPrep(null, "claude", false, true, null, true, "tpl");
        assertEquals("zmx attach isx claude", run(prep, false, true, false));
        assertEquals("zmx attach work claude", run(prep.withSession(ShellSession.named("work")), false, true, false));
        assertEquals("zmx attach 2 claude", run(prep.withSession(ShellSession.NEW), false, true, false, "isx"));
        // No stand-in for claude: outside zmx it is not found, and the plain shell follows
        var none = run(prep.withSession(ShellSession.NONE), false, true, false);
        assertFalse(none.contains("zmx"), none);
        assertTrue(none.endsWith("plain --login"), none);
    }

    @Test
    void aSessionNameNeedsNoQuotingAndHasNoDot() {
        assertTrue(ShellSession.isValidName("work_2-a"));
        for (var bad : new String[] {"", "-x", "a.b", "../x", "a b", "a;b", "$(x)", "x".repeat(33)}) {
            assertFalse(ShellSession.isValidName(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> ShellSession.named(bad), bad);
        }
    }
}
