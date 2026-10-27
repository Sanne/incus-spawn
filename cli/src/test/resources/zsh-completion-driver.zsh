#!/usr/bin/env zsh
# Drive one completion in a real interactive zsh and print the buffer it leaves behind.
#
# Usage: zsh -f zsh-completion-driver.zsh <fake-bin-dir> <completion-script> <line> <out-file>
#
# zsh only runs completion inside zle, so this borrows the zpty approach from zsh's own test
# suite: type <line>, press TAB, and let a widget write $BUFFER to <out-file>. The fake isx on
# PATH answers each source with exactly one candidate, so TAB inserts it unambiguously and the
# captured buffer shows what the completion offered instead of what the terminal rendered.
emulate -R zsh
zmodload zsh/zpty || exit 77
fake=$1; script=$2; line=$3; out=$4
rm -f -- $out
zpty z zsh -f -i || exit 77
zpty -w z "PATH='$fake':\$PATH; PS1='ISXREADY> '; autoload -Uz compinit; compinit -u; source '$script'"$'\n'

# Wait for the configured prompt rather than sleeping a fixed time.
typeset buf= chunk
integer i
for (( i = 0; i < 500; i++ )); do
  zpty -rt z chunk 2>/dev/null && buf+=$chunk
  [[ $buf == *ISXREADY* ]] && break
  sleep 0.02
done

# ^X is a prefix key in the default emacs keymap, so bind the two-key sequence.
zpty -w z "dump_buffer() { print -r -- \"\$BUFFER\" > $out; zle kill-whole-line }; zle -N dump_buffer; bindkey '^X^X' dump_buffer"$'\n'
sleep 0.2

zpty -w z "$line"$'\t\x18\x18'
for (( i = 0; i < 500; i++ )); do
  [[ -s $out ]] && break
  sleep 0.02
done
zpty -d z 2>/dev/null

[[ -s $out ]] || exit 1
cat -- $out
