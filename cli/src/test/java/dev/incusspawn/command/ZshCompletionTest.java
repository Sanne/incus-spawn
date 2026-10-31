package dev.incusspawn.command;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the generated zsh completion in a real interactive zsh and checks the buffer TAB leaves
 * behind. zsh only completes inside zle, so the driver (a test resource) uses zpty to type a
 * line, press TAB and capture the buffer.
 *
 * <p>The script asks {@code isx --aesh-complete -- <words>} ({@link FakeIsx}): {@link #complete}
 * answers with {@link Completers#answer} for the words the script sent, and {@link #completeWith}
 * with a given answer. Each case has one candidate, which TAB inserts unambiguously: the captured
 * buffer is then exactly what the completion offered.
 */
@ExtendWith(IsolatedHome.class)
class ZshCompletionTest {

    @TempDir
    Path dir;

    private FakeIsx isx;
    private Path script, driver;

    @BeforeEach
    void writeFixtures() throws Exception {
        isx = new FakeIsx(dir);
        script = dir.resolve("_isx");
        Files.writeString(script, CompletionCommand.script(CompletionCommand.Shell.zsh));
        driver = dir.resolve("zsh-completion-driver.zsh");
        try (InputStream in = getClass().getResourceAsStream("/zsh-completion-driver.zsh")) {
            assertNotNull(in, "driver resource is on the test classpath");
            Files.write(driver, in.readAllBytes());
        }
    }

    /**
     * Saved as {@code _isx} on fpath, as {@code isx completion --install} suggests, the file is
     * the body compinit autoloads on the first TAB: that TAB must complete too, not only define
     * {@code _isx} for the next one.
     */
    @Test
    void theFirstTabCompletesWhenAutoloadedFromFpath() throws Exception {
        var fpath = Files.createDirectories(dir.resolve("fpath"));
        Files.copy(script, fpath.resolve("_isx"));
        script = fpath;
        assertEquals("isx shell box1 ", completeWith("isx shell bo", "box1\n"));
    }

    /** Completes {@code line} against what isx answers for it; returns the buffer, a space zsh added included. */
    private String complete(String line) throws Exception {
        var buffer = type(line);
        return isx.answerFromCompleters() ? type(line) : buffer;
    }

    /** Completes {@code line} with {@code answer} as what isx answers. */
    private String completeWith(String line, String answer) throws Exception {
        isx.answer(answer);
        return type(line);
    }

    /** Types {@code line} with the cursor at its end, presses TAB and returns the resulting buffer. */
    private String type(String line) throws Exception {
        var out = dir.resolve("buffer.txt");
        var log = dir.resolve("driver.log");
        var pb = new ProcessBuilder("zsh", "-f", driver.toString(),
                isx.bin().toString(), script.toString(), line, out.toString());
        pb.redirectErrorStream(true).redirectOutput(log.toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            Assumptions.abort("zsh is not installed");
            throw e;
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("zsh completion did not finish in 30s: " + readQuietly(log));
        }
        // The driver exits 77 when zsh lacks zpty or zselect, or cannot run zpty here (no pty).
        Assumptions.assumeFalse(p.exitValue() == 77, () -> "zpty cannot run here: " + readQuietly(log));
        assertEquals(0, p.exitValue(), () -> "driver failed: " + readQuietly(log));
        return Files.readString(out).replaceAll("\\R$", "");
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return "(no driver log)";
        }
    }

    /**
     * The hand-written script listed {@code init}, {@code update-all} and {@code mcp} but had no
     * case for them, so their options completed nothing (#1842). Asking isx follows the tree.
     */
    @Test
    void everyCommandCompletesItsOptions() throws Exception {
        assertEquals("isx mcp --caller-instance ", complete("isx mcp --ca"));
        assertEquals("isx update-all --prime ", complete("isx update-all --pr"));
        assertEquals("isx init --help ", complete("isx init --he"));
        assertEquals("isx destroy --all-instances ", complete("isx destroy --all-i"));
        assertEquals("isx list --format=json ", complete("isx list --format=j"));
    }

    @Test
    void sendsTheWordsUpToTheCursorUnquoted() throws Exception {
        assertEquals("isx destroy box1 ", completeWith("isx destroy ", "box1\n"));
        assertEquals(List.of("destroy", ""), isx.request());
        completeWith("isx branch 'a b' --inbox my\\ d", "");
        assertEquals(List.of("branch", "a b", "--inbox", "my d"), isx.request());
        completeWith("isx branch x --inbox \"my d", "");
        assertEquals(List.of("branch", "x", "--inbox", "my d"), isx.request());
    }

    @Test
    void positionalValuesComeFromTheAnswer() throws Exception {
        assertEquals("isx shell box1 ", completeWith("isx shell bo", "box1\n"));
        assertEquals("isx templates edit base ", completeWith("isx templates edit ", "base\n"));
        assertEquals("isx tools show claude ", completeWith("isx tools show ", "claude\tAnthropic's agent\n"));
        assertEquals("isx account add gh ", complete("isx account add gh"));
    }

    @Test
    void accountSelectionsAndTheirDefault() throws Exception {
        IsolatedHome.seed("""
                github:
                  accounts:
                    personal:
                      token: "xxxxxxxxxxxx"
                    acme:
                      token: "yyyyyyyyyyyy"
                """);
        assertEquals("isx account set box1 github=acme ", complete("isx account set box1 github=a"));
        assertEquals("isx branch x --account github=personal ", complete("isx branch x --account github=p"));
        assertEquals("isx account unset box1 github ", complete("isx account unset box1 gi"));
    }

    /** A directory or a bare {@code <namespace>=} is completed further, so no space follows it. */
    @Test
    void noSpaceAfterADirectoryOrANamespace() throws Exception {
        assertEquals("isx branch x --account github=", completeWith("isx branch x --account gi", "github=\n"));
        assertEquals("isx branch x --inbox /tmp/my\\ dir/", completeWith("isx branch x --inbox /tmp/m", "/tmp/my dir/\n"));
    }

    /** A name isx answers is inserted quoted and never run: each candidate here is a command substitution (#1778). */
    @Test
    void noCandidateIsExpanded() throws Exception {
        var marker = dir.resolve("pwned");
        for (var hostile : List.of("$(touch " + marker + ")", "`touch " + marker + "`")) {
            for (var line : List.of("isx tools show ", "isx account add ", "isx account set box1 ", "isx build ", "isx destroy ")) {
                var buffer = completeWith(line, hostile + "\n");
                assertFalse(Files.exists(marker), "completion of '" + line + "' ran a candidate as a command");
                assertNotEquals(line + hostile + " ", buffer, "inserted unquoted, so it would run on Enter");
            }
        }
    }
}
