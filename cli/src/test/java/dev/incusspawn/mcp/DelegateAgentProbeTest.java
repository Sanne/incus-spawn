package dev.incusspawn.mcp;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each agent's {@link DelegateAgent#probe} as the worker runs it: under bash, against stand-ins on
 * the PATH that print the agent's help on stderr or on stdout. opencode's yargs prints its help on
 * stderr, and a probe that read stdout alone refused every delegate to a real opencode (found on a
 * live VM).
 */
class DelegateAgentProbeTest {

    @TempDir Path bin;

    /** Every option any probe looks for, so one help text serves every subcommand it asks about. */
    private static final String HELP = "Options:\\n  --output-last-message\\n  --json\\n  --session-id\\n  --auto\\n";

    private void stub(String name, String body) throws Exception {
        var f = bin.resolve(name);
        Files.writeString(f, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** The agent's CLI and Node, and a {@code timeout} that drops its duration (macOS has none). */
    private void agent(DelegateAgent agent, String printHelp) throws Exception {
        stub(agent.id, printHelp);
        stub("node", "exit 0");
        stub("timeout", "shift; exec \"$@\"");
    }

    /**
     * Runs the probe with {@link #bin} as the whole PATH, the host's {@code grep} beside the
     * stand-ins, so a {@code node} or agent this host has is never found in its place.
     */
    private int probe(DelegateAgent agent) throws Exception {
        var grep = java.util.stream.Stream.of("/usr/bin/grep", "/bin/grep").map(Path::of)
                .filter(Files::isExecutable).findFirst().orElseThrow();
        stub("grep", "exec " + grep + " \"$@\"");
        var pb = new ProcessBuilder("bash", "-c", agent.probe()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("PATH", bin.toString());
        var p = pb.start();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the probe answers");
        return p.exitValue();
    }

    @ParameterizedTest
    @EnumSource(value = DelegateAgent.class, names = "UNKNOWN", mode = EnumSource.Mode.EXCLUDE)
    void aHelpPrintedOnStderrPassesTheProbe(DelegateAgent agent) throws Exception {
        agent(agent, "printf '" + HELP + "' >&2");
        assertEquals(0, probe(agent));
    }

    @ParameterizedTest
    @EnumSource(value = DelegateAgent.class, names = "UNKNOWN", mode = EnumSource.Mode.EXCLUDE)
    void aHelpPrintedOnStdoutPassesTheProbe(DelegateAgent agent) throws Exception {
        agent(agent, "printf '" + HELP + "'");
        assertEquals(0, probe(agent));
    }

    @ParameterizedTest
    @EnumSource(value = DelegateAgent.class, names = {"CODEX", "PI", "OPENCODE"})
    void aCliWithoutTheOptionsFailsTheProbe(DelegateAgent agent) throws Exception {
        agent(agent, "echo 'Options:' >&2; echo '  --help'");
        assertNotEquals(0, probe(agent));
    }

    @ParameterizedTest
    @EnumSource(value = DelegateAgent.class, names = {"CODEX", "PI", "OPENCODE"})
    void anAgentWithoutNodeFailsTheProbe(DelegateAgent agent) throws Exception {
        agent(agent, "printf '" + HELP + "'");
        Files.delete(bin.resolve("node"));
        assertNotEquals(0, probe(agent));
    }
}
