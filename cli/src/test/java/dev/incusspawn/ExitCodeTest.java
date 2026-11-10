package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The exit codes docs/CLI.md "Scripting isx" documents (#1036), through the real entry point: a command
 * line aesh cannot parse exits 2 with the usage on stderr, a value the command rejects exits 1 with
 * the reason on stderr, and neither writes anything to stdout. Neither needs Incus.
 */
class ExitCodeTest {

    private record Run(int exit, String out, String err) {}

    private static Run isx(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var oldOut = System.out;
        var oldErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            var exit = new IncusSpawn().run(args);
            return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    void aCommandLineThatCannotBeParsedExits2() {
        for (var args : new String[][] {{"list", "--bogus"}, {"list", "--format"}, {"list", "stray"}}) {
            var run = isx(args);
            assertEquals(2, run.exit(), () -> String.join(" ", args) + "\n" + run);
            assertEquals("", run.out(), () -> String.join(" ", args) + ": usage belongs on stderr");
            assertFalse(run.err().isBlank(), () -> String.join(" ", args) + ": the usage must say what is wrong");
        }
    }

    @Test
    void aValueTheCommandRejectsExits1() {
        // Each with its own reason, so a run that failed for another one (Incus is not reachable
        // here) does not pass.
        var cases = new String[][] {
                {"unknown format 'yaml'", "list", "--format=yaml"},
                {"unknown status 'frozen'", "list", "--status=frozen"},
                {"--quiet prints names only", "list", "-q", "--format=json"},
                {"--plain is --format=plain", "list", "--plain", "--format=json"},
                // Every command that takes --format rejects a value it does not know alike.
                {"unknown format 'yaml'", "templates", "--format=yaml"},
                {"unknown format 'yaml'", "templates", "list", "--format=yaml"},
                {"unknown format 'yaml'", "tools", "--format=yaml"},
                {"unknown format 'yaml'", "tools", "list", "--format=yaml"},
                {"unknown format 'yaml'", "tools", "show", "git", "--format=yaml"},
                {"unknown format 'yaml'", "account", "--format=yaml"},
                {"unknown format 'yaml'", "account", "list", "--format=yaml"},
                {"unknown format 'yaml'", "account", "show", "dev-1", "--format=yaml"},
                {"unknown format 'yaml'", "proxy", "status", "--format=yaml"},
                {"unknown format 'yaml'", "doctor", "--format=yaml"},
                {"unknown format 'yaml'", "update-base", "--list", "--format=yaml"},
                {"unknown format 'yaml'", "branch", "dev-2", "--format=yaml"},
                // Not the server reading stdin: the approvals step, refused without a terminal (#2077).
                {"a person must run it at a terminal", "mcp", "setup"},
                {"--bundle writes an archive", "doctor", "--bundle", "--format=json"},
                {"--explain prints a diagnosis for people", "doctor", "--explain", "--format=json"},
                {"--format applies to --list only", "update-base", "--format=json"},
                {"unknown format 'yaml'", "init", "--plan", "--format=yaml"},
                {"--format goes with --plan", "init", "--format=json"}};
        for (var c : cases) {
            var args = Arrays.copyOfRange(c, 1, c.length);
            var run = isx(args);
            assertEquals(1, run.exit(), () -> String.join(" ", args) + "\n" + run);
            assertEquals("", run.out(), () -> String.join(" ", args));
            // An error sits at the step indent, as every command's does
            assertTrue(run.err().startsWith(dev.incusspawn.util.BuildOutput.STEP_INDENT + "Error: ") && run.err().contains(c[0]),
                    () -> String.join(" ", args) + "\n" + run.err());
        }
    }
}
