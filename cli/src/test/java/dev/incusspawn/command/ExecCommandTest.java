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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx exec}: a script runs one command in an instance and gets its output and exit code.
 * The instance is prepared as {@code isx start} does it and left running; the command runs as
 * agentuser in a login shell, in the template's workdir, and its words reach the guest unchanged.
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

    private static ExecCommand command(List<String> prepared, String... args) {
        var command = new ExecCommand();
        command.args = List.of(args);
        command.preparer = (incus, name, out) -> prepared.add(name);
        return command;
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
    void preparesTheInstanceThenRunsTheCommandInItsWorkdirAndPassesOnItsOutputAndExit() {
        var daemon = new FakeIncusDaemon()
                .container("dev", Map.of(Metadata.WORKDIR, "/home/agentuser/project"))
                .execOutput("make", "built\n")
                .execExit("make", 3);
        var prepared = new ArrayList<String>();
        var out = new ByteArrayOutputStream();
        var command = command(prepared, "dev", "make", "test");

        var exit = new int[1];
        RecordingOutput.during(() -> exit[0] = assertDoesNotThrow(() ->
                command.exec(daemon.client(), new StartStopCommandTest.Locks(), null, out, new ByteArrayOutputStream())
                        .getResultValue()));

        assertEquals(List.of("dev"), prepared, "prepared as isx start does");
        assertEquals("built\n", out.toString(StandardCharsets.UTF_8));
        var script = script(daemon);
        assertTrue(script.contains("cd -- '/home/agentuser/project'"), script);
        assertTrue(script.endsWith("bash -c " + Container.shellQuote("'make' 'test'")), script);
        assertTrue(daemon.stateActions().isEmpty(), "never stopped: that is isx stop's job");
        assertEquals(3, exit[0], "the command's exit code is isx's");
    }

    @Test
    void eachWordStaysOneWordAndNothingInItIsExpandedByIsx() throws Exception {
        var daemon = new FakeIncusDaemon().container("dev", Map.of()).servesExecs();
        var args = new ArrayList<>(List.of("dev", "printf", "[%s]", "a b", "$(id)", "it's", "*", ""));
        var command = command(new ArrayList<>(), args.toArray(String[]::new));

        RecordingOutput.during(() -> assertEquals(0, assertDoesNotThrow(() ->
                command.exec(daemon.client(), new StartStopCommandTest.Locks(), null,
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()).getResultValue())));

        // What the guest's session runs once it has written its pid file, run here by a real bash
        var script = script(daemon);
        var run = script.substring(script.indexOf(" isx-exec ") + " isx-exec ".length());
        var bash = new ProcessBuilder("bash", "-c", run).redirectErrorStream(true).start();
        assertEquals("[a b][$(id)][it's][*][]", new String(bash.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(0, bash.waitFor());
    }

    @Test
    void withoutAWorkdirItRunsInTheHomeAndCwdAndEnvAreTheCommands() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of()).servesExecs();
        var command = command(new ArrayList<>(), "dev", "env");
        RecordingOutput.during(() -> assertDoesNotThrow(() -> command.exec(daemon.client(),
                new StartStopCommandTest.Locks(), null, new ByteArrayOutputStream(), new ByteArrayOutputStream())));
        assertTrue(script(daemon).contains("cd -- '.'"), "agentuser's login shell starts in its home");

        var daemon2 = new FakeIncusDaemon().container("dev", Map.of(Metadata.WORKDIR, "/w")).servesExecs();
        var given = command(new ArrayList<>(), "dev", "env");
        given.cwd = "/tmp";
        given.env = List.of("A=1=2", "EMPTY=");
        RecordingOutput.during(() -> assertDoesNotThrow(() -> given.exec(daemon2.client(),
                new StartStopCommandTest.Locks(), null, new ByteArrayOutputStream(), new ByteArrayOutputStream())));
        var script = script(daemon2);
        assertTrue(script.contains("cd -- '/tmp'"), script);
        assertTrue(script.contains("export A='1=2'; export EMPTY=''; "), script);
    }

    @Test
    void stdinIsForwardedOnlyWhenAsked() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of()).servesExecs();
        var command = command(new ArrayList<>(), "dev", "cat");
        RecordingOutput.during(() -> assertDoesNotThrow(() -> command.exec(daemon.client(),
                new StartStopCommandTest.Locks(), new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), new ByteArrayOutputStream())));
        assertEquals(List.of(5), daemon.execStdinFrames());
    }

    @Test
    void isxsOwnFailuresExit125AndRunNothing() {
        var missing = new FakeIncusDaemon().servesExecs();
        var gone = new ExecCommand();
        gone.args = List.of("gone", "true");
        var recorded = RecordingOutput.during(() -> assertEquals(ExecCommand.ISX_FAILED, assertDoesNotThrow(() ->
                gone.exec(missing.client(), new StartStopCommandTest.Locks(), null,
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()).getResultValue())));
        assertEquals("no instance named 'gone' found.", recorded.events(RecordingOutput.Kind.ERROR).getFirst().msg());
        assertTrue(missing.execs().isEmpty());

        var daemon = new FakeIncusDaemon().container("dev", Map.of()).servesExecs();
        var noCommand = command(new ArrayList<>(), "dev");
        var badEnv = command(new ArrayList<>(), "dev", "true");
        badEnv.env = List.of("NOEQUALS");
        var badName = command(new ArrayList<>(), "dev", "true");
        badName.env = List.of("A-B=1");
        for (var refused : List.of(noCommand, badEnv, badName)) {
            RecordingOutput.during(() -> assertEquals(ExecCommand.ISX_FAILED, assertDoesNotThrow(() ->
                    refused.exec(daemon.client(), new StartStopCommandTest.Locks(), null,
                            new ByteArrayOutputStream(), new ByteArrayOutputStream()).getResultValue())));
        }
        assertTrue(daemon.execs().isEmpty(), "nothing ran");
    }
}
