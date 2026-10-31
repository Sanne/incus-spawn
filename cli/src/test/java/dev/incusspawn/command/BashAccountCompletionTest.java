package dev.incusspawn.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the generated bash completion in a real bash and checks the line readline would end up
 * with. Readline splits words on {@code =} and {@code :} (COMP_WORDBREAKS) and replaces only the
 * last piece, so a candidate that repeats the typed {@code namespace=} doubles it.
 * <p>
 * The script asks {@code isx --aesh-complete -- <words>} ({@link FakeIsx}): {@link #complete}
 * answers with {@link Completers#answer} for the words the script sent, and {@link #completeWith}
 * with a given answer.
 */
@ExtendWith(IsolatedHome.class)
class BashAccountCompletionTest {

    @TempDir
    Path dir;

    private FakeIsx isx;

    @BeforeEach
    void writeFakeIsx() throws Exception {
        isx = new FakeIsx(dir);
    }

    /** Completes {@code line} against what isx answers for it. */
    private List<String> complete(String line) throws Exception {
        var lines = type(line);
        return isx.answerFromCompleters() ? type(line) : lines;
    }

    /** Completes {@code line} with {@code answer} as what isx answers. */
    private List<String> completeWith(String line, String answer) throws Exception {
        isx.answer(answer);
        return type(line);
    }

    /** Completes {@code line}; the cursor is at its end. Returns the line after readline applies each candidate. */
    private List<String> type(String line) throws Exception {
        return drive(line, false);
    }

    /**
     * Completes {@code line} with {@code answer}, its one candidate, then presses Enter: runs the
     * completed line in a bash where {@code isx} is a function. Returns the words it received.
     */
    private List<String> enter(String line, String answer) throws Exception {
        isx.answer(answer);
        var args = dir.resolve("args");
        Files.deleteIfExists(args);
        var completed = drive(line, true);
        assertEquals(1, completed.size(), "one candidate: " + completed);
        if (!Files.exists(args)) return null;
        var words = Files.readString(args);
        return java.util.Arrays.asList(words.substring(0, words.length() - 1).split("\0", -1));
    }

    private List<String> drive(String line, boolean enter) throws Exception {
        var script = dir.resolve("isx.bash");
        Files.writeString(script, CompletionCommand.script(CompletionCommand.Shell.bash));
        var driver = dir.resolve("drive.sh");
        // Readline's side, written apart from the script's: it replaces the text after the last
        // unquoted word break or the quote still open, and closes that quote after a lone
        // candidate. A closing quote is no break: bash's char_is_quoted covers it.
        Files.writeString(driver, """
                source %s
                line="$1"
                COMP_WORDBREAKS=$' \\t\\n"\\'@><=;|&(:'
                COMP_LINE="$line"; COMP_POINT=${#line}; COMP_TYPE=9; COMP_KEY=9
                start=0; before=0; quote=""
                for (( i = 0; i < ${#line}; i++ )); do
                  c=${line:i:1}
                  if [[ $quote == "'" ]]; then
                    [[ $c == "'" ]] && quote="" start=$before
                  elif [[ $quote == '"' ]]; then
                    if [[ $c == "\\\\" ]]; then (( i++ )); elif [[ $c == '"' ]]; then quote="" start=$before; fi
                  elif [[ $c == "\\\\" ]]; then
                    (( i++ ))
                  elif [[ $c == "'" || $c == '"' ]]; then
                    quote=$c before=$start start=$(( i + 1 ))
                  elif [[ $COMP_WORDBREAKS == *"$c"* ]]; then
                    start=$(( i + 1 ))
                  fi
                done
                # Outside a completion compopt fails; this one records what the script asked for.
                compopt() { printf '%%s\\n' "$*" >> %s; }
                _isx
                # Readline's append_to_match: no closing quote when the line already ends in it.
                close=""
                (( ${#COMPREPLY[@]} == 1 )) && [[ ${COMPREPLY[0]} != *"$quote" ]] && close=$quote
                for r in "${COMPREPLY[@]}"; do echo "${line:0:start}$r$close"; done
                if [[ $2 == enter && ${#COMPREPLY[@]} == 1 ]]; then
                  # Read as an interactive shell reads the line: history expansion on, after an earlier command.
                  printf '%%s\\n' 'set -o history -H' "isx() { printf '%%s\\\\0' \\"\\$@\\" > %s; }" true \\
                    "${line:0:start}${COMPREPLY[0]}$close" | bash --norc --noprofile -s 2>/dev/null
                fi
                """.formatted(script, dir.resolve("compopt"), dir.resolve("args")));
        var pb = new ProcessBuilder("bash", "--norc", "--noprofile", driver.toString(), line, enter ? "enter" : "");
        pb.environment().put("PATH", isx.bin() + ":" + System.getenv("PATH"));
        // %q's $'...' form depends on the locale: octal bytes in C, which the tests expect
        pb.environment().put("LC_ALL", "C");
        var output = dir.resolve("out.txt");
        pb.redirectErrorStream(true).redirectOutput(output.toFile());
        var p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("bash completion did not finish in 30s");
        }
        return Files.readString(output).lines().filter(l -> !l.isBlank()).toList();
    }

    /**
     * A path whose name the locale cannot print, so %q writes it as $'...', where a $(...) stays
     * live: inside an open quote it is quoted for that quote, so Enter runs nothing and isx gets
     * the path as it is.
     */
    @Test
    void aCandidateIsQuotedForTheQuoteLeftOpen() throws Exception {
        var marker = dir.resolve("pwned");
        // The reproduction, then every character a quote treats specially, history expansion's ! included.
        for (var path : List.of("./x\u2028$(touch " + marker + ")/", "./x\u2028 $(touch " + marker + ")'\"`touch " + marker + "`\\!!/")) {
            for (var open : List.of("", "\"", "'", "$'")) {
                var words = enter("isx branch x --inbox " + open + "./", path + "\n");
                assertFalse(Files.exists(marker), "Enter after completing in " + open + " ran the candidate");
                assertEquals(List.of("branch", "x", "--inbox", path), words, "inside " + open);
                words = enter("isx branch x --inbox=" + open + "./", "--inbox=" + path + "\n");
                assertFalse(Files.exists(marker), "Enter after completing --inbox=" + open + " ran the candidate");
                assertEquals(List.of("branch", "x", "--inbox=" + path), words, "--inbox=" + open);
            }
        }
    }

    /**
     * A second TAB into a directory the first one completed inside a quote, which readline has
     * closed: the closing quote is no word break, so the whole word is replaced and the path kept.
     */
    @Test
    void aSecondTabIntoAQuotedDirectoryKeepsThePath() throws Exception {
        for (var open : List.of("", "\"", "'", "$'")) {
            var first = completeWith("isx branch x --inbox " + open + "./a", "./a b/\n");
            assertEquals(1, first.size(), open + ": " + first);
            var words = enter(first.getFirst(), "./a b/sub/\n");
            assertEquals(List.of("branch", "x", "--inbox", "./a b/sub/"), words, "after " + open);
            words = enter(first.getFirst().replace("--inbox ", "--inbox="), "--inbox=./a b/sub/\n");
            assertEquals(List.of("branch", "x", "--inbox=./a b/sub/"), words, "--inbox= after " + open);
        }
    }

    /**
     * A candidate ending in the quote's own character, or in a {@code !} written outside the
     * quote, is closed by the script: readline adds no closing quote to a line already ending in
     * that character, so the quote would stay open.
     */
    @Test
    void aCandidateEndingInItsQuoteLeavesItClosed() throws Exception {
        for (var open : List.of("", "\"", "'", "$'")) {
            for (var name : List.of("abc'", "abc\"", "abc!", "abc\\")) {
                assertEquals(List.of("shell", name), enter("isx shell " + open + "ab", name + "\n"), name + " inside " + open);
            }
        }
    }

    /**
     * Readline closes the quote of a lone match only, so several candidates ending in the quote's
     * character are offered quoted but unclosed: the common prefix it inserts leaves the quote open.
     */
    @Test
    void onlyALoneMatchHasItsQuoteClosed() throws Exception {
        assertEquals(List.of("isx shell \"abc\\\"", "isx shell \"abd\\\""), completeWith("isx shell \"ab", "abc\"\nabd\"\n"));
        assertEquals(List.of("isx shell 'a'\\''", "isx shell 'a'\\'''\\''"), completeWith("isx shell 'a", "a'\na''\n"));
        assertEquals(List.of("shell", "abc\""), enter("isx shell \"ab", "abc\"\n"));
    }

    /** A redirection's target is a file: isx is not asked, and readline completes file names. */
    @Test
    void aRedirectionTargetFallsBackToFileNames() throws Exception {
        for (var line : List.of("isx list > ou", "isx list >ou", "isx list 2>ou", "isx list >> ou", "isx list &>ou")) {
            Files.deleteIfExists(dir.resolve("compopt"));
            assertEquals(List.of(), completeWith(line, "out\n"), line);
            assertNull(isx.request(), line + " asked isx");
            assertEquals(List.of("-o default"), Files.readAllLines(dir.resolve("compopt")), line);
        }
    }

    /** Every word break but a space is kept in the word isx reads, as it is in the word bash runs. */
    @Test
    void aWordBreakOtherThanSpaceStaysInTheWord() throws Exception {
        assertEquals(List.of("isx account set box claude=me@example"),
                completeWith("isx account set box claude=me@ex", "claude=me@example\n"));
        assertEquals(List.of("account", "set", "box", "claude=me@ex"), isx.request());
    }

    /** isx answers a typed ~/ with the home directory; where readline keeps it, it is matched as typed. */
    @Test
    void aTypedHomeDirectoryMatchesBeforeAWordBreak() throws Exception {
        var home = System.getenv("HOME");
        assertEquals(List.of("isx branch x --inbox ~/a=bc/"), completeWith("isx branch x --inbox ~/a=b", home + "/a=bc/\n"));
        assertEquals(List.of("isx branch x --inbox=~/a=bc/"), completeWith("isx branch x --inbox=~/a=b", "--inbox=" + home + "/a=bc/\n"));
        // With nothing kept, the whole word is replaced by the path isx answered.
        assertEquals(List.of("isx branch x --inbox " + home + "/ab/"), completeWith("isx branch x --inbox ~/a", home + "/ab/\n"));
    }

    private static void seedTwoGithubAccounts() throws Exception {
        IsolatedHome.seed("""
                github:
                  default: personal
                  accounts:
                    personal:
                      token: "xxxxxxxxxxxx"
                    acme:
                      token: "yyyyyyyyyyyy"
                """);
    }

    /** The commands the hand-written scripts missed or listed by hand follow the tree (#1842). */
    @Test
    void everyCommandCompletesFromTheTree() throws Exception {
        assertEquals(List.of("isx mcp --caller-instance"), complete("isx mcp --ca"));
        assertEquals(List.of("isx update-all --prime"), complete("isx update-all --pr"));
        assertEquals(List.of("isx list --format=json"), complete("isx list --format=j"));
        assertEquals(List.of("isx list --format json", "isx list --format plain", "isx list --format table"),
                complete("isx list --format "));
        assertFalse(complete("isx ").contains("isx vm"), "vm is macOS only");
    }

    @Test
    void accountOptionCompletesTheAccountWithoutRepeatingTheNamespace() throws Exception {
        seedTwoGithubAccounts();
        assertEquals(List.of("isx branch x --account github=acme"), complete("isx branch x --account github=a"));
        assertEquals(List.of("isx account set box1 github=personal"), complete("isx account set box1 github=p"));
        assertEquals(List.of("isx branch x --account=github=acme"), complete("isx branch x --account=github=a"));
    }

    @Test
    void aBareNamespacePrefixOffersNamespaceAndAccounts() throws Exception {
        seedTwoGithubAccounts();
        assertEquals(List.of("isx branch x --account github=", "isx branch x --account github=acme",
                "isx branch x --account github=personal"), complete("isx branch x --account gith"));
    }

    @Test
    void aTypedNamespaceListsOnlyItsAccounts() throws Exception {
        seedTwoGithubAccounts();
        assertEquals(List.of("isx branch x --account github=acme", "isx branch x --account github=personal"),
                complete("isx branch x --account github="));
        assertEquals(List.of("isx account set box1 github=acme", "isx account set box1 github=personal"),
                complete("isx account set box1 github="));
    }

    /** add takes a tool as well as a namespace, so gh is offered beside github (#1773). */
    @Test
    void addOffersToolNamesBesideNamespaces() throws Exception {
        assertTrue(complete("isx account add g").containsAll(List.of("isx account add gh", "isx account add github")));
    }

    /** Each word reaches isx as the shell reads it: quotes and backslashes removed, nothing run. */
    @Test
    void sendsTheWordsUpToTheCursorUnquoted() throws Exception {
        var marker = dir.resolve("pwned");
        completeWith("isx branch 'a b' --inbox my\\ d", "");
        assertEquals(List.of("branch", "a b", "--inbox", "my d"), isx.request());
        completeWith("isx shell \"x\\$y\" $'a\\342\\200\\250b'", "");
        assertEquals(List.of("shell", "x$y", "a\u2028b"), isx.request());
        // $'...' takes \", \? and \' as the character, which printf %b would not.
        completeWith("isx shell $'a\\\"b\\?c\\'d\\x27e'", "");
        assertEquals(List.of("shell", "a\"b?c'd'e"), isx.request());
        completeWith("isx branch x --account=github=a", "");
        assertEquals(List.of("branch", "x", "--account=github=a"), isx.request());
        completeWith("isx shell \\$\\(touch\\ " + marker + "\\)", "");
        assertEquals(List.of("shell", "$(touch " + marker + ")"), isx.request());
        completeWith("isx shell \"$(touch " + marker + ")", "");
        completeWith("isx shell `touch " + marker + "`", "");
        assertFalse(Files.exists(marker), "completion ran a typed word as a command");
    }

    /**
     * No candidate is expanded by the script, whatever isx lets through: each answer here is a
     * command substitution, and TAB at every place runs none.
     */
    @Test
    void noCandidateIsExpandedOnTab() throws Exception {
        var marker = dir.resolve("pwned");
        var hostile = "$(touch " + marker + ")\n`touch " + marker + "`\nx=$(touch " + marker + ")\n";
        for (var line : List.of("isx tools show ", "isx account add ", "isx account unset box1 ", "isx account set box1 x=",
                "isx branch x --account ", "isx branch x --from ", "isx build ", "isx destroy ", "isx shell ")) {
            var lines = completeWith(line, hostile);
            assertFalse(Files.exists(marker), "completion of '" + line + "' ran a candidate as a command");
            var unquoted = java.util.regex.Pattern.compile("(?<!\\\\)(\\$\\(|`)");
            assertFalse(lines.stream().anyMatch(l -> unquoted.matcher(l).find()), "inserted unquoted: " + lines);
        }
    }

    /** A tool named like a command substitution, as a cloned repository may ship, never runs on TAB (#1778). */
    @Test
    void toolsShowNeverRunsAHostileToolName() throws Exception {
        var marker = dir.resolve("pwned");
        var tools = Files.createDirectories(dev.incusspawn.Environment.configDir().resolve("tools"));
        Files.writeString(tools.resolve("evil.yaml"), """
                name: "$(touch %s)"
                description: x
                """.formatted(marker));
        complete("isx tools show ");
        assertFalse(Files.exists(marker), "completion ran the tool's name as a command");
    }

    /** An account is quoted after the typed namespace is dropped from it, so the namespace is not repeated. */
    @Test
    void anAccountIsInsertedQuotedAfterItsNamespace() throws Exception {
        assertEquals(List.of("isx account set box1 ns=a\\,b"), completeWith("isx account set box1 ns=", "ns=a,b\n"));
    }

    /**
     * An account %q writes as $'...' (a U+2028 in it) still loses the typed namespace: dropped
     * from the name before quoting, not from the quoted word, where it would be missed.
     */
    @Test
    void anAccountQuotedAsAnsiCLosesItsNamespaceToo() throws Exception {
        // The driver runs bash under LC_ALL=C, where %q writes the bytes in octal
        var inserted = "isx account set box1 ns=$'a\\342\\200\\250b'";
        assertEquals(List.of(inserted), completeWith("isx account set box1 ns=", "ns=a\u2028b\tdefault\n"));
    }

    /** A candidate the typed word does not start where readline replaces is not offered. */
    @Test
    void aCandidateInAnotherNamespaceIsNotOffered() throws Exception {
        assertEquals(List.of("isx account set box1 github=personal"),
                completeWith("isx account set box1 github=", "claude=work\ngithub=personal\n"));
    }

    /** A candidate that is a glob is not expanded to the files in the current directory, and is inserted quoted. */
    @Test
    void aGlobCandidateIsNotExpanded() throws Exception {
        assertEquals(List.of("isx tools show \\*", "isx tools show --help"), completeWith("isx tools show ", "*\n--help\tDisplay this help\n"));
    }

    /**
     * Readline inserts a candidate as is, so one holding a command substitution is quoted: it
     * would otherwise land on the command line and run on Enter.
     */
    @Test
    void aCandidateIsInsertedQuoted() throws Exception {
        // A path %q leaves as it is: the temporary directory's name is letters, digits and '/'
        var marker = dir.resolve("pwned");
        var answer = "$(touch " + marker + ") y\n";
        var inserted = "isx tools show \\$\\(touch\\ " + marker + "\\)\\ y";
        assertEquals(List.of(inserted), completeWith("isx tools show ", answer));
        // What a first TAB inserted still matches when TAB is pressed again
        assertEquals(List.of(inserted), completeWith("isx tools show \\$\\(to", answer));
        assertEquals(List.of("tools", "show", "$(to"), isx.request());
        assertFalse(Files.exists(marker));
    }

    @Test
    void aPathIsInsertedQuoted() throws Exception {
        assertEquals(List.of("isx branch x --inbox /tmp/my\\ dir/"), completeWith("isx branch x --inbox /tmp/m", "/tmp/my dir/\n"));
        assertEquals(List.of("isx branch x --inbox=/tmp/my\\ dir/"),
                completeWith("isx branch x --inbox=/tmp/m", "--inbox=/tmp/my dir/\n"));
    }
}
