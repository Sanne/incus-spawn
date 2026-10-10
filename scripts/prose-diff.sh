#!/bin/bash
# Shows what a change to Markdown prose did to its words and to its rendering, ignoring line breaks.
# Usage: ./scripts/prose-diff.sh <base>..<head> <file>...
#
# Part 1 is every changed word in context, with whitespace and line breaks ignored. Part 2 is
# every change inside fenced code, exactly. Part 3 renders both versions with GitHub's renderer
# for files (gh api markdown, mode=markdown) and diffs the HTML with whitespace collapsed. A
# change that only moves line breaks in prose prints nothing in any part (#1243).
# Needs gh logged in. The API takes 400 KB at most, so a larger file is rendered in pieces cut
# at its "## " headings, the same cuts for both versions.

set -euo pipefail

if [ $# -lt 2 ] || [[ "$1" != *..* ]]; then
    echo "Usage: $0 <base>..<head> <file>..." >&2
    exit 1
fi

BASE="${1%%..*}"
HEAD="${1##*..}"
shift
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Prints the file's HTML with whitespace collapsed, one tag per line.
render() {
    local name="$1" piece="$WORK/piece"
    [ -s "$WORK/$name" ] || return 0
    rm -f "$piece".*
    awk -v out="$piece" '
        /^(```|~~~)/ { fence = !fence }
        !fence && /^## / && size > 300000 { n++; size = 0 }
        { print > (out "." sprintf("%03d", n)); size += length($0) + 1 }
    ' "$WORK/$name"
    for p in "$piece".*; do
        gh api markdown -f mode=markdown -F text=@"$p"
    done | tr -s '[:space:]' ' ' | awk '{ gsub(/ *</, "\n<"); print }'
}

# Prints the fenced code blocks, fences included, exactly as they are: the other two parts
# ignore whitespace, which inside code is meaning.
code_blocks() {
    awk '/^[[:space:]]*(```|~~~)/ { fence = !fence; print; next } fence' "$WORK/$1"
}

# Prints every changed word of old -> new, three words of context on each side, with the
# line of the new file where the change starts. Words are compared one per line, so where the
# line breaks fall makes no difference.
changed_words() {
    local file="$1"
    awk '{ for (i = 1; i <= NF; i++) print $i }' "$WORK/old" > "$WORK/old.words"
    : > "$WORK/new.lines"
    awk -v lines="$WORK/new.lines" '{ for (i = 1; i <= NF; i++) { print $i; print NR > lines } }' \
        "$WORK/new" > "$WORK/new.words"
    { diff -U3 "$WORK/old.words" "$WORK/new.words" || true; } | awk -v file="$file" '
        NR == FNR { line[FNR] = $0; next }
        function flush() { if (text != "") print file ":" at ": " text; text = ""; at = "" }
        /^(---|\+\+\+) / { next }
        /^@@/ { flush(); split($3, c, ","); n = substr(c[1], 2) + 0; next }
        /^ / { text = text (text == "" ? "" : " ") substr($0, 2); n++; next }
        /^-/ { if (at == "") at = line[n] ? line[n] : line[n - 1]; text = text " [-" substr($0, 2) "-]"; next }
        /^\+/ { if (at == "") at = line[n]; text = text " {+" substr($0, 2) "+}"; n++; next }
        END { flush() }
    ' "$WORK/new.lines" - | sed -e 's/-\] \[-/ /g' -e 's/+} {+/ /g' -e 's/^\([^ ]*\)  */\1 /'
}

for file in "$@"; do
    if ! git cat-file -e "$BASE:$file" 2>/dev/null && ! git cat-file -e "$HEAD:$file" 2>/dev/null; then
        echo "$file is in neither $BASE nor $HEAD (paths are from the repository root)" >&2
        exit 1
    fi
    git show "$BASE:$file" > "$WORK/old" 2>/dev/null || : > "$WORK/old"
    git show "$HEAD:$file" > "$WORK/new" 2>/dev/null || : > "$WORK/new"
    cmp -s "$WORK/old" "$WORK/new" && continue
    changed_words "$file" >> "$WORK/words"
    diff -U0 --label "$BASE:$file" --label "$HEAD:$file" <(code_blocks old) <(code_blocks new) >> "$WORK/code" || true
    render old > "$WORK/old.html"
    render new > "$WORK/new.html"
    diff -U0 --label "$BASE:$file" --label "$HEAD:$file" "$WORK/old.html" "$WORK/new.html" >> "$WORK/html" || true
done

touch "$WORK/words" "$WORK/code" "$WORK/html"
echo "== Changed words (line breaks ignored)"
cat "$WORK/words"
echo "== Code blocks (exact)"
cat "$WORK/code"
echo "== Rendered HTML (whitespace collapsed)"
cat "$WORK/html"
