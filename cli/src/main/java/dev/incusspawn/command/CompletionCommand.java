package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

/**
 * Prints a shell's completion script. Every script answers a TAB by asking the running binary,
 * {@code isx --aesh-complete -- <words>} ({@link Completers#answer}), so it follows this
 * platform's command tree and lists no command or option of its own (#1744, #1842). Each is
 * isx's own rather than aesh's generated one: that one hands what isx prints to a shell that
 * expands it, so a {@code $(...)} in a name ran on TAB or on Enter (#1778). These send each
 * word's value and insert each candidate quoted by the shell's own rules.
 */
@CommandDefinition(
        name = "completion",
        description = "Print shell completion script",
        generateHelp = true
)
public class CompletionCommand extends BaseCommand {

    enum Shell { bash, zsh, fish, pwsh }

    @Argument(description = "Shell type: bash, zsh, fish, pwsh", required = false, defaultValue = {"bash"},
            completer = Completers.Shell.class)
    Shell shell;

    @Option(name = "install", description = "Print installation instructions instead of the script", hasValue = false)
    boolean install;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (install) {
            printInstallInstructions();
            return CommandResult.SUCCESS;
        }
        BuildOutput.results().println(script(shell));
        return CommandResult.SUCCESS;
    }

    /** The completion script for a shell, the same on every platform. */
    static String script(Shell shell) {
        return switch (shell) {
            case zsh  -> ZSH_COMPLETION;
            case bash -> BASH_COMPLETION;
            case fish -> FISH_COMPLETION;
            case pwsh -> PWSH_COMPLETION;
        };
    }

    private void printInstallInstructions() {
        BuildOutput.results().println("""
                # ── Zsh ────────────────────────────────────────────────────────────────────
                # Option A: source directly from your ~/.zshrc
                #   eval "$(isx completion zsh)"
                #
                # Option B: save to a completion file (faster shell startup)
                #   mkdir -p ~/.zsh/completions
                #   isx completion zsh > ~/.zsh/completions/_isx
                #   echo 'fpath=(~/.zsh/completions $fpath)' >> ~/.zshrc
                #   echo 'autoload -Uz compinit && compinit' >> ~/.zshrc
                #
                # ── Bash ────────────────────────────────────────────────────────────────────
                # Option A: source directly from your ~/.bashrc
                #   eval "$(isx completion bash)"
                #
                # Option B: save to a completion file
                #   mkdir -p ~/.local/share/bash-completion/completions
                #   isx completion bash > ~/.local/share/bash-completion/completions/isx
                #
                # ── Fish ────────────────────────────────────────────────────────────────────
                # Save to the fish completions directory:
                #   mkdir -p ~/.config/fish/completions
                #   isx completion fish > ~/.config/fish/completions/isx.fish
                #
                # ── PowerShell ──────────────────────────────────────────────────────────────
                # Add to your $PROFILE:
                #   isx completion pwsh | Out-String | Invoke-Expression
                """);
    }

    // ── PowerShell completion ───────────────────────────────────────────────────

    /**
     * Not aesh's generated script: that one hands isx a quoted word with its quotes and inserts
     * each candidate as is, so a path with a space became two arguments. This one sends each
     * word's value and single-quotes a candidate PowerShell would otherwise split or expand.
     */
    private static final String PWSH_COMPLETION = """
            # PowerShell completion for isx: every TAB asks isx itself.
            Register-ArgumentCompleter -Native -CommandName 'isx' -ScriptBlock {
                param($wordToComplete, $commandAst, $cursorPosition)

                # The words before the cursor as isx receives them: a quoted word's value, without its quotes.
                $cmdArgs = @()
                $last = $null
                foreach ($elem in $commandAst.CommandElements | Select-Object -Skip 1) {
                    if ($elem.Extent.StartOffset -ge $cursorPosition) { break }
                    $last = $elem
                    if ($elem -is [System.Management.Automation.Language.StringConstantExpressionAst]) {
                        $cmdArgs += $elem.Value
                    } else {
                        $cmdArgs += $elem.Extent.Text
                    }
                }
                # Past the last word, the word being completed is a new, empty one.
                $newWord = $null -eq $last -or $cursorPosition -gt $last.Extent.EndOffset
                if ($newWord) { $cmdArgs += '' }
                # PowerShell reads a bare a,b as a list and replaces only its last item.
                $listItem = -not $newWord -and $last -is [System.Management.Automation.Language.ArrayLiteralAst]

                & 'isx' --aesh-complete -- @cmdArgs 2>$null | ForEach-Object {
                    $text, $desc = $_ -split "`t", 2
                    if ($listItem) { $text = $text.Substring($text.LastIndexOf(',') + 1) }
                    if (-not $text) { return }
                    $insert = $text
                    # Quoted: what PowerShell splits on, expands, or globs for a native command.
                    if ($text -match '[\\s''"`$;,(){}@#|&<>*?\\[\\]\\u2018-\\u201E]') {
                        $insert = "'" + ($text -replace '([''\\u2018-\\u201B])', '$1$1') + "'"
                    }
                    if (-not $desc) { $desc = $text }
                    [System.Management.Automation.CompletionResult]::new($insert, $text, 'ParameterValue', $desc)
                }
            }
            """;

    // ── Zsh completion ──────────────────────────────────────────────────────────

    /**
     * zsh removes a word's quoting with {@code (Q)}, which expands nothing, and {@code compadd}
     * quotes what it inserts. Each candidate is already the whole word isx matched, so
     * {@code -U} replaces the word with it: isx answers a typed {@code ~/} with the home directory.
     */
    private static final String ZSH_COMPLETION = """
            #compdef isx
            # zsh completion for isx (incus-spawn): every TAB asks isx itself.

            _isx() {
              local -a request spaced unspaced
              local line word
              # The words up to the cursor as isx receives them: their quoting removed, nothing expanded.
              request=("${(@Q)words[2,CURRENT-1]}" "${(Q)PREFIX}")
              for line in "${(@f)$(isx --aesh-complete -- "${request[@]}" 2>/dev/null)}"; do
                word=${line%%$'\\t'*}
                [[ -n $word ]] || continue
                # _describe reads "word:description", so a colon in the word is escaped.
                local entry=${word//:/\\\\:}
                [[ $line == *$'\\t'* ]] && entry+=":${line#*$'\\t'}"
                # A directory or a bare <namespace>= is completed further, so no space follows it.
                if [[ $word == */ || $word == *= ]]; then unspaced+=("$entry"); else spaced+=("$entry"); fi
              done
              local ret=1
              _describe -t values 'isx' spaced -U && ret=0
              _describe -t values 'isx' unspaced -U -S '' && ret=0
              return $ret
            }

            compdef _isx isx 2>/dev/null
            """;

    // ── Bash completion ─────────────────────────────────────────────────────────

    /**
     * Never gives what isx prints to anything that expands it ({@code compgen -W}, {@code eval}):
     * readline inserts a candidate as is, so each is quoted for the quote the word left open
     * (#1778). {@code printf %q} is used only outside quotes: inside {@code "} its {@code $'...'}
     * form, written for a character the locale cannot print, leaves a {@code $(...)} live. The
     * words come from {@code COMP_LINE}, read by the script itself rather than bash-completion,
     * whose dequoting calls {@code eval}. Readline replaces only what follows the word's last
     * {@code COMP_WORDBREAKS} character or its open quote, so the part before is dropped from each
     * whole-word candidate first, before quoting.
     */
    private static final String BASH_COMPLETION = """
            # bash completion for isx (incus-spawn): every TAB asks isx itself.

            # Reads the command line up to the cursor as isx will, expanding and running nothing:
            # _isx_request holds each word after "isx", its quotes and backslashes removed, the last the
            # one being completed. Readline replaces only the end of that word, after its last word break
            # (COMP_WORDBREAKS) or the quote still open; _isx_kept is the part before, as isx reads it,
            # and _isx_quote the open quote: ', " or $', or nothing.
            _isx_words() {
              local line=${COMP_LINE:0:COMP_POINT} breaks=${COMP_WORDBREAKS//[[:space:]]/}
              # One escape of $'...': up to three octal digits, a hex or Unicode code, or one character.
              local escape='^\\\\([0-7]{1,3}|x[[:xdigit:]]{1,2}|u[[:xdigit:]]{1,4}|U[[:xdigit:]]{1,8}|.)'
              # A closing quote is no word break for readline: what it kept before the quote opened holds again.
              local word="" started="" before="" c i
              _isx_request=() _isx_kept="" _isx_quote=""
              for (( i = 0; i < ${#line}; i++ )); do
                c=${line:i:1}
                if [[ $_isx_quote == "'" ]]; then
                  if [[ $c == "'" ]]; then _isx_quote="" _isx_kept=$before; else word+=$c; fi
                elif [[ $_isx_quote == "\\$'" ]]; then
                  if [[ $c == "'" ]]; then
                    _isx_quote="" _isx_kept=$before
                  elif [[ ${line:i:10} =~ $escape ]]; then
                    # %b reads the escapes %q writes in $'...', and runs nothing; it keeps the backslash of \\', \\" and \\?.
                    if [[ ${BASH_REMATCH[0]} == \\\\[\\'\\"?] ]]; then word+=${BASH_REMATCH[0]:1}; else printf -v c %b "${BASH_REMATCH[0]}"; word+=$c; fi
                    (( i += ${#BASH_REMATCH[0]} - 1 ))
                  else
                    word+=$c
                  fi
                elif [[ $_isx_quote == '"' ]]; then
                  if [[ $c == '"' ]]; then
                    _isx_quote="" _isx_kept=$before
                  elif [[ $c == "\\\\" && ${line:i+1:1} == [\\$\\`\\"\\\\] ]]; then
                    word+=${line:i+1:1}
                    (( i++ ))
                  else
                    word+=$c
                  fi
                elif [[ $c == [[:space:]] ]]; then
                  [[ -n $started ]] && _isx_request+=("$word")
                  word="" started="" _isx_kept=""
                else
                  started=1
                  if [[ $c == "\\\\" ]]; then
                    word+=${line:i+1:1}
                    (( i++ ))
                  elif [[ $c == "'" || $c == '"' ]]; then
                    _isx_quote=$c before=$_isx_kept _isx_kept=$word
                  elif [[ $c == '$' && ${line:i+1:1} == "'" ]]; then
                    _isx_quote="\\$'" before=$_isx_kept _isx_kept=$word
                    (( i++ ))
                  else
                    word+=$c
                    [[ $breaks == *"$c"* ]] && _isx_kept=$word
                  fi
                fi
              done
              _isx_request+=("$word")
              _isx_request=("${_isx_request[@]:1}")
            }

            # REPLY is $1 quoted for where readline inserts it, inside the quote the word left open, so it
            # stays one literal word and never runs: %q outside quotes, and inside them the escapes that
            # quote allows. %q is never used inside a quote: its $'...' form keeps a $(...) live there.
            # History expansion still reads a ! inside "...", and bash 3.2 (macOS) inside $'...' too, so it
            # is written outside them, as "\\!" or '\\!$'.
            # Inside $'...' a ' is \\x27: readline would read \\' as the end of the quote on the next TAB.
            _isx_quote_for() {
              case $_isx_quote in
                "") printf -v REPLY %q "$1"; [[ $REPLY == "\\$'"* ]] && REPLY=${REPLY//!/\\'\\\\!\\$\\'} ;;
                "'") REPLY=${1//\\'/\\'\\\\\\'\\'} ;;
                '"') REPLY=${1//\\\\/\\\\\\\\}; REPLY=${REPLY//\\$/\\\\\\$}; REPLY=${REPLY//\\`/\\\\\\`}; REPLY=${REPLY//\\"/\\\\\\"}; REPLY=${REPLY//!/\\"\\\\!\\"} ;;
                *) REPLY=${1//\\\\/\\\\\\\\}; REPLY=${REPLY//\\'/\\\\x27}; REPLY=${REPLY//!/\\'\\\\!\\$\\'} ;;
              esac
            }

            _isx() {
              _isx_words
              local candidate kept=$_isx_kept typed="" prefix="" home=""
              # Not [-1]: macOS's bash 3.2 has no negative subscripts.
              (( ${#_isx_request[@]} )) && typed=${_isx_request[${#_isx_request[@]}-1]}
              # A redirection's target is a file: ask isx nothing, and let readline complete it.
              local redirect='^[0-9&]*[<>][<>&|]*$' redirected='^[0-9&]*[<>]'
              if [[ $typed =~ $redirected ]] || { (( ${#_isx_request[@]} > 1 )) && [[ ${_isx_request[${#_isx_request[@]}-2]} =~ $redirect ]]; }; then
                COMPREPLY=()
                compopt -o default 2>/dev/null
                return 0
              fi
              # isx answers a typed ~/ (or --opt=~/) with the home directory: in what readline keeps, it
              # is matched as typed.
              if [[ $typed == "~/"* ]]; then
                home=1
              elif [[ $typed == --*="~/"* ]]; then
                home=1 prefix=${typed%%=*}=
              fi
              COMPREPLY=()
              while IFS= read -r candidate; do
                candidate=${candidate%%$'\\t'*}
                if [[ -n $home && ${#kept} -gt ${#prefix} && $candidate == "$prefix$HOME/"* ]]; then
                  candidate=$prefix"~/"${candidate#"$prefix$HOME/"}
                fi
                [[ $candidate == "$kept"?* ]] || continue
                # A directory or a bare <namespace>= is completed further, so no space follows it.
                [[ $candidate == */ || $candidate == *= ]] && compopt -o nospace 2>/dev/null
                _isx_quote_for "${candidate#"$kept"}"
                COMPREPLY+=("$REPLY")
              done < <(isx --aesh-complete -- "${_isx_request[@]}" 2>/dev/null)
              # Readline closes a lone match's quote, and only when the line does not already end in
              # it; _isx_quote_for's escapes can end in one that reopens the quote or is escaped in it.
              local close=${_isx_quote#\\$}
              if [[ ${#COMPREPLY[@]} == 1 && -n $close && ${COMPREPLY[0]} == *"$close" ]]; then COMPREPLY[0]+=$close; fi
            }

            # bash 3.2 (macOS) has no compopt, so there an empty answer falls back to file names.
            if (( BASH_VERSINFO[0] >= 4 )); then complete -F _isx isx; else complete -o default -F _isx isx; fi
            """;

    // ── Fish completion ─────────────────────────────────────────────────────────

    /**
     * isx answers in fish's own format, a candidate then a TAB and its description, and fish
     * quotes what it inserts. A command substitution's output is never evaluated again, so what
     * isx prints stays text.
     */
    private static final String FISH_COMPLETION = """
            # fish completion for isx (incus-spawn): every TAB asks isx itself.

            # The words up to the cursor as isx receives them, their quoting removed; then what it answers.
            function __isx_complete
                set -l token (commandline -ct | string unescape)
                set -l answer (isx --aesh-complete -- (commandline -opc)[2..-1] "$token" 2>/dev/null)
                # isx answers a typed ~/ (or --opt=~/) with the home directory, which fish would not match against it.
                if string match -qr -- '^(--[^=]*=)?~/' "$token"
                    set -l prefix (string match -r -- '^--[^=]*=' "$token")
                    set answer (string replace -r -- '^'(string escape --style=regex -- "$prefix$HOME/") "$prefix~/" $answer)
                end
                printf '%s\\n' $answer
            end

            complete -c isx -f -a '(__isx_complete)'
            """;
}
