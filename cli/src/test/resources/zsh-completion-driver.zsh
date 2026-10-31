#!/usr/bin/env zsh
# Drive one completion in a real interactive zsh and write the buffer it leaves behind to <out-file>.
#
# Usage: zsh -f zsh-completion-driver.zsh <fake-bin-dir> <completion-script-or-fpath-dir> <line> <out-file>
#
# zsh only runs completion inside zle, so this borrows the zpty approach from zsh's own test
# suite: type <line>, press TAB, and let a widget write $BUFFER to <out-file>. The fake isx on
# PATH answers each source with exactly one candidate, so TAB inserts it unambiguously and the
# captured buffer shows what the completion offered instead of what the terminal rendered.
#
# Exit 77: zpty is unusable here (the test skips). Progress goes to stderr, so a failure or the
# watchdog says which step it stopped at.
emulate -R zsh
zmodload zsh/zpty || exit 77
fake=$1; script=$2; line=$3; out=$4
rm -f -- $out

# A stuck step is killed rather than left to the test's own timeout, so the log is kept.
zmodload zsh/zselect || exit 77
{ zselect -t 2000; print -u2 "watchdog: killed after 20s"; kill $$ } &!
watchdog=$!
# Every exit, a failed step's included, ends the watchdog and the shell on the pty. Killed by the
# watchdog, the driver closes the pty instead, which hangs that shell up.
trap 'kill $watchdog 2>/dev/null; zpty -d z 2>/dev/null' EXIT

# The setup runs from a file, so the echo of the typed command line cannot contain the two
# markers waited for below; they appear only once zsh has run the setup or the widget.
# compinit -D writes no ~/.zcompdump; -u skips the insecure-directory prompt (Homebrew's
# site-functions is group-writable), which would wait for an answer nobody types.
setup=${out:h}/zsh-completion-setup.zsh
cat > $setup <<EOF
PATH=${(q)fake}:\$PATH
# A directory is put on fpath, as the install instructions' saved _isx is; a file is sourced.
[[ -d ${(q)script} ]] && fpath=(${(q)script} \$fpath)
autoload -Uz compinit
compinit -u -D
[[ -d ${(q)script} ]] || source ${(q)script}
dump_buffer() { print -r -- "\$BUFFER" > ${(q)out}; BUFFER=; zle -M ISXDONE }
zle -N dump_buffer
# ^X is a prefix key in the default emacs keymap, so bind the two-key sequence.
bindkey '^X^X' dump_buffer
PS1='ISXREADY> '
EOF

zpty z zsh -f -i || exit 77
# Every wait is a blocking read that drains the pty up to its marker, as zsh's own completion
# tests do: output nobody reads can fill a small pty buffer (macOS) and stall the shell.
typeset buf
print -u2 "step: setup"
zpty -w z "source ${(q)setup}"
zpty -r -m z buf '*ISXREADY> *' || { print -u2 -r -- "no prompt: ${(V)buf}"; exit 1 }
print -u2 "step: complete"
zpty -n -w z "$line"$'\t\x18\x18'
zpty -r -m z buf '*ISXDONE*' || { print -u2 -r -- "no buffer: ${(V)buf}"; exit 1 }

[[ -s $out ]] || { print -u2 "empty buffer file"; exit 1 }
