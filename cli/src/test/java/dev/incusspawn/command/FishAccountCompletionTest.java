package dev.incusspawn.command;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the generated fish completion in a real fish and checks the candidates
 * {@code complete -C} returns for a line, descriptions dropped unless asked for.
 * <p>
 * The script asks {@code isx --aesh-complete -- <words>} ({@link FakeIsx}): {@link #complete}
 * answers with {@link Completers#answer} for the words the script sent, and {@link #completeWith}
 * with a given answer.
 */
@ExtendWith(IsolatedHome.class)
class FishAccountCompletionTest {

    @TempDir
    Path dir;

    private FakeIsx isx;

    @BeforeEach
    void writeFakeIsx() throws Exception {
        isx = new FakeIsx(dir);
    }

    private List<String> complete(String line) throws Exception {
        var lines = type(line);
        return isx.answerFromCompleters() ? type(line) : lines;
    }

    private List<String> completeWith(String line, String answer) throws Exception {
        isx.answer(answer);
        return type(line);
    }

    /** The candidates fish offers for {@code line}, each with its description after a TAB. */
    private List<String> described(String line) throws Exception {
        var script = dir.resolve("isx.fish");
        Files.writeString(script, CompletionCommand.script(CompletionCommand.Shell.fish));
        var pb = new ProcessBuilder("fish", "--no-config", "-c",
                "source $argv[1]; complete -C $argv[2]", script.toString(), line);
        pb.environment().put("PATH", isx.bin() + ":" + System.getenv("PATH"));
        // fish keeps variables and history under these; none of it belongs in the real home.
        for (var variable : List.of("HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME")) {
            pb.environment().put(variable, dir.toString());
        }
        var output = dir.resolve("out.txt");
        pb.redirectErrorStream(true).redirectOutput(output.toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            Assumptions.abort("fish is not installed");
            throw e;
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("fish completion did not finish in 30s");
        }
        assertEquals(0, p.exitValue(), () -> "fish failed: " + readQuietly(output));
        return Files.readString(output).lines().toList();
    }

    private List<String> type(String line) throws Exception {
        return described(line).stream().map(l -> l.split("\t", 2)[0]).toList();
    }

    /** The commands the hand-written scripts missed or listed by hand follow the tree (#1842). */
    @Test
    void everyCommandCompletesFromTheTree() throws Exception {
        assertEquals(List.of("--caller-instance"), complete("isx mcp --ca"));
        assertEquals(List.of("--prime"), complete("isx update-all --pr"));
        assertEquals(List.of("--format=json"), complete("isx list --format=j"));
        assertFalse(complete("isx ").contains("vm"), "vm is macOS only");
    }

    /** add takes a tool as well as a namespace, so gh is offered beside github (#1773). */
    @Test
    void addOffersToolNamesBesideNamespaces() throws Exception {
        assertTrue(complete("isx account add g").containsAll(List.of("gh", "github")));
    }

    /** The default account is described as such, as isx answers it. */
    @Test
    void theDefaultAccountIsDescribed() throws Exception {
        IsolatedHome.seed("""
                github:
                  default: personal
                  accounts:
                    personal:
                      token: "xxxxxxxxxxxx"
                    acme:
                      token: "yyyyyyyyyyyy"
                """);
        described("isx account set box1 github=");
        isx.answerFromCompleters();
        assertEquals(List.of("github=acme", "github=personal\tdefault"), described("isx account set box1 github="));
    }

    @Test
    void sendsTheWordsUpToTheCursorUnquoted() throws Exception {
        completeWith("isx branch 'a b' --inbox my\\ d", "");
        assertEquals(List.of("branch", "a b", "--inbox", "my d"), isx.request());
        completeWith("isx shell \"bo", "");
        assertEquals(List.of("shell", "bo"), isx.request());
    }

    /** isx answers a typed ~/ with the home directory; fish is given it back as typed, so it matches. */
    @Test
    void aTypedHomeDirectoryStaysAsTyped() throws Exception {
        assertEquals(List.of("~/my dir/"), completeWith("isx branch x --inbox ~/m", dir + "/my dir/\n"));
        assertEquals(List.of("--config=~/proj.yaml"), completeWith("isx project update --config=~/pro", "--config=" + dir + "/proj.yaml\n"));
    }

    /** A name isx answers is offered as text and never run: each candidate here is a command substitution (#1778). */
    @Test
    void noCandidateIsExpanded() throws Exception {
        var marker = dir.resolve("pwned");
        var hostile = "$(touch %s)\n(touch %s)\n`touch %s`\n".formatted(marker, marker, marker);
        assertTrue(completeWith("isx tools show ", hostile).contains("$(touch " + marker + ")"), "offered as typed");
        for (var line : List.of("isx tools show ", "isx account add ", "isx account unset box1 ",
                "isx account set box1 ", "isx branch x --account ", "isx build ", "isx destroy ", "isx shell ")) {
            completeWith(line, hostile);
            assertFalse(Files.exists(marker), "completion of '" + line + "' ran a candidate as a command");
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "(no output)";
        }
    }
}
