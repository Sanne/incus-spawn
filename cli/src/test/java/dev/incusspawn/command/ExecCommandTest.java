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

    /** The scripts of every exec, in order, each {@code su - agentuser -c <script>}. */
    private static List<String> scripts(FakeIncusDaemon daemon) {
        return daemon.execs().stream().map(e -> e.command().get(4)).toList();
    }

    @Test
    void anExecThatFailsExits125NotTheCommandsOne() {
        // Incus serves no exec: the POST is refused, as a broken daemon or a just-stopped box would
        var daemon = new FakeIncusDaemon().instance("dev", "container", "Running", Map.of());
        var exit = new int[1];
        var recorded = RecordingOutput.during(() -> exit[0] = assertDoesNotThrow(() ->
                command("dev", "true").exec(daemon.client(), null, new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()).getResultValue()));
        assertEquals(ExecCommand.ISX_FAILED, exit[0]);
        assertTrue(recorded.events(RecordingOutput.Kind.ERROR).getFirst().msg().startsWith("could not run the command in dev: "),
                recorded.events().toString());
        var scripts = scripts(daemon);
        assertEquals(2, scripts.size(), "the command, then its kill in case it started: " + scripts);
        assertTrue(scripts.get(1).contains("kill -TERM"), scripts.get(1));
    }

    @Test
    void anExecWithoutAnExitCodeExits125AndEndsTheCommand() {
        // The wait's ceiling or a lost operation: Incus gives no exit code, and the command may run on
        var daemon = running(Map.of()).servesExecs().operationsAnswer("Failure");
        assertEquals(ExecCommand.ISX_FAILED, run(command("dev", "sleep", "infinity"), daemon, null, new ByteArrayOutputStream()));
        var scripts = scripts(daemon);
        assertEquals(2, scripts.size(), scripts.toString());
        var first = scripts.getFirst();
        var from = first.indexOf("/run/cli-") + "/run/".length();
        var runId = first.substring(from, first.indexOf(".pid", from));
        assertTrue(scripts.get(1).contains("kill -TERM") && scripts.get(1).contains("/" + runId + ".pid"),
                "the kill names the same run: " + scripts.get(1));
    }

    @Test
    void whenNothingReadsTheOutputAnyMoreTheCommandIsEnded() {
        // `isx exec box -- yes | head -1`: the pipe closed, and a PrintStream only records it
        var gone = new java.io.PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                throw new java.io.IOException("Broken pipe");
            }
        });
        var daemon = running(Map.of()).execOutput("yes", "y\ny\n");
        var exit = new int[1];
        RecordingOutput.during(() -> exit[0] = assertDoesNotThrow(() ->
                command("dev", "yes").exec(daemon.client(), null, gone, new ByteArrayOutputStream()).getResultValue()));
        assertEquals(ExecCommand.OUTPUT_CLOSED, exit[0], "a shell's code for a command SIGPIPE ended");
        var scripts = scripts(daemon);
        assertEquals(2, scripts.size(), scripts.toString());
        assertTrue(scripts.get(1).contains("kill -TERM"), scripts.get(1));
    }

    @Test
    void aClosedStderrDropsOnlyStderrAndTheCommandRunsOn() {
        // `isx exec box -- make 2>&- > build.log`: a warning on the closed stderr must not end make
        var gone = new java.io.PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                throw new java.io.IOException("Bad file descriptor");
            }
        });
        var daemon = running(Map.of()).execOutput("make", "built\n").execStderr("make", "warning\n").execExit("make", 3);
        var out = new ByteArrayOutputStream();
        var exit = new int[1];
        RecordingOutput.during(() -> exit[0] = assertDoesNotThrow(() ->
                command("dev", "make").exec(daemon.client(), null, out, gone).getResultValue()));
        assertEquals(3, exit[0], "the command's own exit code");
        assertEquals("built\n", out.toString(StandardCharsets.UTF_8));
        var scripts = scripts(daemon);
        assertEquals(1, scripts.size(), "nothing ended the command: " + scripts);
    }

    @Test
    void eachRunHasItsOwnId() {
        var first = ExecCommand.runId();
        var second = ExecCommand.runId();
        assertNotEquals(first, second, "a pid the host reuses must not make two runs one");
        assertTrue(first.startsWith("cli-" + ProcessHandle.current().pid() + "-"), first);
        assertDoesNotThrow(() -> dev.incusspawn.incus.ExecScript.requireId(first));
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
