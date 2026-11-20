package dev.incusspawn.command;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.RecordingOutput;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx exec}: a script runs one command in a running instance and gets its output and exit
 * code. A stopped or missing instance is refused, never started; the command runs as agentuser in
 * a login shell, in the template's workdir, and its words reach the guest unchanged.
 */
class ExecCommandTest {

    /** The command line as aesh parses it, without running anything. */
    @CommandDefinition(name = "exec", description = "")
    public static class Parsed extends ExecCommand {
        static List<String> args;
        static String cwd;
        static List<String> env;
        static boolean stdin;

        @Override
        protected CommandResult doExecute() {
            Parsed.args = this.args;
            Parsed.cwd = this.cwd;
            Parsed.env = this.env;
            Parsed.stdin = this.stdin;
            return CommandResult.SUCCESS;
        }
    }

    private static void parse(String... args) {
        Parsed.args = null;
        Parsed.cwd = null;
        Parsed.env = null;
        Parsed.stdin = false;
        AeshRuntimeRunner.builder().command(Parsed.class).args(args).execute();
    }

    @Test
    void everyWordAfterTheSeparatorIsTheCommandsOptionsIncluded() {
        parse("box", "--cwd", "src", "--env", "A=1", "-i", "--", "ls", "-la", "--color", "a b");
        assertEquals(List.of("box", "ls", "-la", "--color", "a b"), Parsed.args);
        assertEquals("src", Parsed.cwd);
        assertEquals(List.of("A=1"), Parsed.env);
        assertTrue(Parsed.stdin);
    }

    @Test
    void theCommandsOwnCwdAndEnvAreNotIsxs() {
        parse("box", "--", "git", "--cwd", "x", "--env", "B=2");
        assertEquals(List.of("box", "git", "--cwd", "x", "--env", "B=2"), Parsed.args);
        assertNull(Parsed.cwd);
        assertNull(Parsed.env);
        assertFalse(Parsed.stdin);
    }

    private static ExecCommand command(String... args) {
        var command = new ExecCommand();
        command.args = List.of(args);
        return command;
    }

    /** A running container, which isx exec runs in as it is. */
    private static FakeIncusDaemon running(Map<String, String> config) {
        return new FakeIncusDaemon().instance("dev", "container", "Running", config);
    }

    private static int run(ExecCommand command, FakeIncusDaemon daemon, java.io.InputStream in,
                           ByteArrayOutputStream out) {
        var exit = new int[1];
        RecordingOutput.during(() -> exit[0] = assertDoesNotThrow(() ->
                command.exec(daemon.client(), in, out, new ByteArrayOutputStream()).getResultValue()));
        return exit[0];
    }

    /** The script the one exec ran: {@code su - agentuser -c <script>}. */
    private static String script(FakeIncusDaemon daemon) {
        var execs = daemon.execs();
        assertEquals(1, execs.size(), "one exec: the command");
        var argv = execs.getFirst().command();
        assertEquals(List.of("su", "-", "agentuser", "-c"), argv.subList(0, 4));
        return argv.get(4);
    }

    @Test
    void runsTheCommandInTheWorkdirOfARunningInstanceAndPassesOnItsOutputAndExit() {
        var daemon = running(Map.of(Metadata.WORKDIR, "/home/agentuser/project"))
                .execOutput("make", "built\n")
                .execExit("make", 3);
        var out = new ByteArrayOutputStream();

        assertEquals(3, run(command("dev", "make", "test"), daemon, null, out), "the command's exit code is isx's");

        assertEquals("built\n", out.toString(StandardCharsets.UTF_8));
        var script = script(daemon);
        assertTrue(script.contains("cd -- '/home/agentuser/project'"), script);
        assertTrue(script.endsWith("bash -c " + Container.shellQuote("'make' 'test'")), script);
        assertTrue(daemon.stateActions().isEmpty(), "neither started nor stopped");
    }

    @Test
    void aStoppedOrMissingInstanceIsRefusedNeverStarted() {
        var stopped = new FakeIncusDaemon().container("dev", Map.of()).servesExecs();
        var recorded = RecordingOutput.during(() -> assertEquals(ExecCommand.ISX_FAILED, assertDoesNotThrow(() ->
                command("dev", "true").exec(stopped.client(), null, new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()).getResultValue())));
        assertEquals(new RecordingOutput.Event(RecordingOutput.Kind.ERROR, "dev is not running.", "Start it with 'isx start dev'."),
                recorded.events(RecordingOutput.Kind.ERROR).getFirst());
        assertTrue(stopped.stateActions().isEmpty(), "not started");
        assertTrue(stopped.execs().isEmpty(), "nothing ran");

        var missing = new FakeIncusDaemon().servesExecs();
        var gone = RecordingOutput.during(() -> assertEquals(ExecCommand.ISX_FAILED, assertDoesNotThrow(() ->
                command("gone", "true").exec(missing.client(), null, new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()).getResultValue())));
        assertEquals("no instance named 'gone' found.", gone.events(RecordingOutput.Kind.ERROR).getFirst().msg());
        assertTrue(missing.execs().isEmpty());
    }

    @Test
    void eachWordStaysOneWordAndNothingInItIsExpandedByIsx() throws Exception {
        var daemon = running(Map.of()).servesExecs();

        assertEquals(0, run(command("dev", "printf", "[%s]", "a b", "$(id)", "it's", "*", ""), daemon, null,
                new ByteArrayOutputStream()));

        // What the guest's session runs once it has written its pid file, run here by a real bash
        var script = script(daemon);
        var tail = script.substring(script.indexOf(" isx-exec ") + " isx-exec ".length());
        var bash = new ProcessBuilder("bash", "-c", tail).redirectErrorStream(true).start();
        assertEquals("[a b][$(id)][it's][*][]", new String(bash.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(0, bash.waitFor());
    }

    @Test
    void withoutAWorkdirItRunsInTheHomeAndCwdAndEnvAreTheCommands() {
        var home = running(Map.of()).servesExecs();
        run(command("dev", "env"), home, null, new ByteArrayOutputStream());
        assertTrue(script(home).contains("cd -- '.'"), "agentuser's login shell starts in its home");

        var daemon = running(Map.of(Metadata.WORKDIR, "/w")).servesExecs();
        var given = command("dev", "env");
        given.cwd = "/tmp";
        given.env = List.of("A=1=2", "EMPTY=");
        run(given, daemon, null, new ByteArrayOutputStream());
        var script = script(daemon);
        assertTrue(script.contains("cd -- '/tmp'"), script);
        assertTrue(script.contains("export A='1=2'; export EMPTY=''; "), script);
    }

    @Test
    void stdinIsForwardedWhenGiven() {
        var daemon = running(Map.of()).servesExecs();
        run(command("dev", "cat"), daemon, new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
        assertEquals(List.of(5), daemon.execStdinFrames());
    }

    @Test
    void aMalformedCommandLineExits125AndRunsNothing() {
        var daemon = running(Map.of()).servesExecs();
        var badEnv = command("dev", "true");
        badEnv.env = List.of("NOEQUALS");
        var badName = command("dev", "true");
        badName.env = List.of("A-B=1");
        for (var refused : List.of(command("dev"), badEnv, badName)) {
            assertEquals(ExecCommand.ISX_FAILED, run(refused, daemon, null, new ByteArrayOutputStream()));
        }
        assertTrue(daemon.execs().isEmpty(), "nothing ran");
    }
}
