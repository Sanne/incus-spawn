#!/usr/bin/env bash
# The engine reads yq's output whole, so validate passes however the output reaches it (#2576). Under
# pipefail, a reader that exits at its first match (grep -q, head -1) kills a yq still writing with
# SIGPIPE, and the pipeline fails: on a busy runner, now and then, validate said a pin named no file
# label it did hold. A yq that hands its second line to the pipe a moment after its first makes that
# race lose every time, so this runs validate on the committed manifest under one. It runs with SIGPIPE
# ignored, as GitHub's runner starts jobs and as a shell cannot undo: the late write then fails with
# EPIPE instead of killing the writer, and the fake yq names the call whose reader stopped early. That
# also catches a reader in a process substitution, whose early exit fails nothing but prints a write
# error from a yq still writing. Needs bash, jq, yq.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
mkdir -p "$WORK/bin"
REAL_YQ="$(command -v yq)" || fail "yq is not installed"
export REAL_YQ
cat > "$WORK/bin/yq" <<'FAKE'
#!/bin/bash
"$REAL_YQ" "$@" | { IFS= read -r l || exit 0; printf '%s\n' "$l"; IFS= read -r l || exit 0; sleep 0.05; { printf '%s\n' "$l" && cat; } 2>/dev/null || { echo "yq $*: the reader stopped before the second line" >&2; exit 141; }; }
s=("${PIPESTATUS[@]}"); (( s[1] )) && exit "${s[1]}"; exit "${s[0]}"
FAKE
chmod +x "$WORK/bin/yq"
out="$(trap '' PIPE; PATH="$WORK/bin:$PATH" bash "$SRC/upstream/engine/upstream.sh" validate 2>&1)" || fail "validate failed when yq's lines came apart: $out"
[[ "$out" == "manifest ok"* ]] || fail "validate did not say the manifest is ok: $out"
echo "slow pipe: ok"
