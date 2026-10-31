package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural guards on the completion scripts, for machines without the shells the real-shell
 * tests ({@code BashAccountCompletionTest}, {@code FishAccountCompletionTest},
 * {@code ZshCompletionTest}) run.
 */
class CompletionCommandTest {

    /**
     * Every script asks the running binary, so none lists a command, an option or a value of its
     * own, or reads the per-source helpers the hand-written scripts read before #1842.
     */
    @Test
    void everyScriptAsksIsxAndNothingElse() {
        for (var shell : CompletionCommand.Shell.values()) {
            var script = CompletionCommand.script(shell);
            assertEquals(2, script.split("--aesh-complete --", -1).length, shell + " asks isx once per TAB");
            for (var gone : List.of("isx list", "--completion", "isx templates", "isx tools", "branch", "doctor")) {
                assertFalse(script.contains(gone), shell + " still names " + gone);
            }
        }
    }

    /**
     * compgen -W and eval expand their words, so a $(...) in a name isx printed would run on TAB
     * (#1778): the bash script uses neither, and puts a candidate in COMPREPLY only as {@code _isx_quote_for}
     * quoted it for the quote the word left open.
     */
    @Test
    void bashNeverExpandsWhatIsxPrints() {
        var script = CompletionCommand.script(CompletionCommand.Shell.bash);
        for (var expands : List.of("compgen", "eval", "mapfile", "readarray", "COMPREPLY=( $(")) {
            assertFalse(script.contains(expands), "the bash script uses " + expands);
        }
        var filled = script.lines().map(String::strip).filter(l -> l.contains("COMPREPLY")).toList();
        assertEquals(List.of("COMPREPLY=()", "COMPREPLY=()", "COMPREPLY+=(\"$REPLY\")",
                "if [[ ${#COMPREPLY[@]} == 1 && -n $close && ${COMPREPLY[0]} == *\"$close\" ]]; then COMPREPLY[0]+=$close; fi"),
                filled, "emptied, filled with what _isx_quote_for quoted, or a lone match's quote closed");
        var lines = script.lines().map(String::strip).toList();
        int added = lines.indexOf("COMPREPLY+=(\"$REPLY\")");
        assertEquals("_isx_quote_for \"${candidate#\"$kept\"}\"", lines.get(added - 1), "quoted just before it is offered");
        assertTrue(script.contains("done < <(isx --aesh-complete -- \"${_isx_request[@]}\" 2>/dev/null)"), script);
        // %q only outside quotes: inside one its $'...' form leaves a $(...) live.
        var quoting = lines.stream().filter(l -> !l.startsWith("#") && l.contains("%q")).toList();
        assertEquals(1, quoting.size(), "%q in one place: " + quoting);
        assertTrue(quoting.getFirst().startsWith("\"\") printf -v REPLY %q \"$1\""), "%q outside a quote only: " + quoting);
    }

    /** What zsh and fish insert is quoted by the shell: neither script asks it not to (compadd -Q, eval). */
    @Test
    void zshAndFishLetTheShellQuote() {
        var zsh = CompletionCommand.script(CompletionCommand.Shell.zsh);
        assertFalse(zsh.contains("-Q") || zsh.contains("eval"), zsh);
        assertTrue(zsh.contains("\"${(@Q)words[2,CURRENT-1]}\" \"${(Q)PREFIX}\""), "words reach isx unquoted, unexpanded");
        var fish = CompletionCommand.script(CompletionCommand.Shell.fish);
        assertFalse(fish.contains("eval"), fish);
        assertTrue(fish.contains("complete -c isx -f -a '(__isx_complete)'"), fish);
    }
}
