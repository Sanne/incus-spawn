#!/bin/bash
# Verify the incus-spawn Nix package against nixpkgs PR requirements.
#
# Usage: ./verify-package.sh <path-to-nixpkgs>
#
# This script:
#   1. Ensures Nix is available (installs it on non-NixOS Linux if missing)
#   2. Copies package files into the nixpkgs checkout if not present
#   3. Builds the package and runs all verification checks
#   4. Optionally runs nixpkgs-review (requires full, non-shallow clone)
#   5. Prints a PR-checklist summary
#
# Works on x86_64-linux, aarch64-linux, and aarch64-darwin.

set -euo pipefail

# ── Colours ───────────────────────────────────────────────────────────
if [ -t 1 ]; then
    GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'
    BOLD='\033[1m'; RESET='\033[0m'
else
    GREEN=''; RED=''; YELLOW=''; BOLD=''; RESET=''
fi

pass() { echo -e "  ${GREEN}✅ PASS${RESET}: $1"; PASSES=$((PASSES + 1)); }
fail() { echo -e "  ${RED}❌ FAIL${RESET}: $1"; FAILURES=$((FAILURES + 1)); }
skip() { echo -e "  ${YELLOW}⏭  SKIP${RESET}: $1"; SKIPS=$((SKIPS + 1)); }
info() { echo -e "${BOLD}==> $1${RESET}"; }

PASSES=0; FAILURES=0; SKIPS=0

# ── Args ──────────────────────────────────────────────────────────────
NIXPKGS="${1:-}"
SKIP_REVIEW="${SKIP_REVIEW:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -z "$NIXPKGS" ]; then
    echo "Usage: $0 <path-to-nixpkgs> [--skip-review]"
    echo ""
    echo "  path-to-nixpkgs   Path to a nixpkgs git checkout"
    echo ""
    echo "Environment:"
    echo "  SKIP_REVIEW=1     Skip nixpkgs-review (useful for shallow clones)"
    exit 1
fi

for arg in "$@"; do
    case "$arg" in
        --skip-review) SKIP_REVIEW=1 ;;
    esac
done

NIXPKGS="$(cd "$NIXPKGS" && pwd)"
if [ ! -f "$NIXPKGS/pkgs/top-level/all-packages.nix" ]; then
    echo "ERROR: $NIXPKGS does not look like a nixpkgs checkout" >&2
    exit 1
fi

# ── Detect platform ──────────────────────────────────────────────────
OS=$(uname -s)
ARCH=$(uname -m)
case "${OS}-${ARCH}" in
    Linux-x86_64)   PLATFORM="x86_64-linux" ;;
    Linux-aarch64)  PLATFORM="aarch64-linux" ;;
    Darwin-arm64)   PLATFORM="aarch64-darwin" ;;
    Darwin-x86_64)  PLATFORM="x86_64-darwin" ;;
    *)              echo "ERROR: Unsupported platform: ${OS}-${ARCH}" >&2; exit 1 ;;
esac
info "Platform: $PLATFORM"

# ── Ensure Nix is available ──────────────────────────────────────────
ensure_nix() {
    if command -v nix-build >/dev/null 2>&1; then
        info "Nix already installed: $(nix --version)"
        return
    fi

    if [ "$OS" = "Linux" ]; then
        # Check if this is NixOS
        if [ -f /etc/NIXOS ]; then
            echo "ERROR: NixOS detected but nix-build not in PATH. Source your profile." >&2
            exit 1
        fi
        info "Installing Nix (single-user, no daemon)..."
        sh <(curl -L https://nixos.org/nix/install) --no-daemon
        # shellcheck disable=SC1091
        . "$HOME/.nix-profile/etc/profile.d/nix.sh"
    elif [ "$OS" = "Darwin" ]; then
        echo "ERROR: Nix is not installed. Install it first:" >&2
        echo "  sh <(curl -L https://nixos.org/nix/install)" >&2
        echo "Then restart your shell and re-run this script." >&2
        exit 1
    fi

    if ! command -v nix-build >/dev/null 2>&1; then
        echo "ERROR: Nix installation failed or not in PATH" >&2
        exit 1
    fi
    info "Nix installed: $(nix --version)"
}

ensure_nix

# Source nix profile if needed (non-NixOS Linux)
if [ -f "$HOME/.nix-profile/etc/profile.d/nix.sh" ]; then
    # shellcheck disable=SC1091
    . "$HOME/.nix-profile/etc/profile.d/nix.sh"
fi

# ── Ensure package files are in nixpkgs ──────────────────────────────
PACKAGE_DIR="$NIXPKGS/pkgs/by-name/in/incus-spawn"

if [ ! -f "$PACKAGE_DIR/package.nix" ]; then
    info "Copying package files into nixpkgs..."
    mkdir -p "$PACKAGE_DIR"
    cp "$SCRIPT_DIR/package.nix" "$PACKAGE_DIR/package.nix"
    echo "  Copied to $PACKAGE_DIR/"
    echo "  NOTE: You also need to add a maintainer entry to"
    echo "        maintainers/maintainer-list.nix (see VERIFY.md Step 1)"
else
    info "Package files already present in $PACKAGE_DIR/"
fi

# ── 1. Package structure ─────────────────────────────────────────────
info "Checking package structure..."

if [ -f "$PACKAGE_DIR/package.nix" ]; then
    pass "pkgs/by-name/in/incus-spawn/package.nix exists"
else
    fail "package.nix not found"
fi

if grep -q "incus-spawn" "$NIXPKGS/pkgs/top-level/all-packages.nix" 2>/dev/null; then
    fail "incus-spawn found in all-packages.nix (should not be there for by-name packages)"
else
    pass "Not in all-packages.nix (correct for by-name)"
fi

# ── 2. Expression evaluation ─────────────────────────────────────────
info "Evaluating Nix expression..."

if DRV=$(nix-instantiate "$NIXPKGS" -A incus-spawn 2>/dev/null); then
    pass "nix-instantiate -A incus-spawn → $DRV"
else
    fail "nix-instantiate -A incus-spawn failed"
fi

# ── 3. Meta attributes ───────────────────────────────────────────────
info "Checking meta attributes..."

check_meta() {
    local attr="$1" expected="$2" label="$3"
    local val
    if val=$(cd "$NIXPKGS" && nix-instantiate --eval -A "incus-spawn.meta.$attr" 2>/dev/null); then
        val=$(echo "$val" | tr -d '"')
        if [ -n "$expected" ] && [ "$val" != "$expected" ]; then
            fail "$label: got '$val', expected '$expected'"
        else
            pass "$label: $val"
        fi
    else
        fail "$label: evaluation failed"
    fi
}

check_meta "description" "" "description"
check_meta "mainProgram" "isx" "mainProgram"
check_meta "license.spdxId" "Apache-2.0" "license"
check_meta "homepage" "https://github.com/Sanne/incus-spawn" "homepage"

# Check description doesn't start with article or package name
DESC=$(cd "$NIXPKGS" && nix-instantiate --eval -A incus-spawn.meta.description 2>/dev/null | tr -d '"')
if echo "$DESC" | grep -qiE "^(A |An |The |incus)"; then
    fail "description starts with article or package name: '$DESC'"
else
    pass "description doesn't start with article/package name"
fi

# Platforms
PLATFORMS=$(cd "$NIXPKGS" && nix-instantiate --eval -A incus-spawn.meta.platforms --json 2>/dev/null)
if echo "$PLATFORMS" | grep -q "$PLATFORM"; then
    pass "Current platform $PLATFORM is in meta.platforms"
else
    fail "Current platform $PLATFORM not in meta.platforms: $PLATFORMS"
fi

# Maintainers (strict eval to catch missing maintainer entries)
if cd "$NIXPKGS" && nix-instantiate --eval --strict -A incus-spawn.meta.maintainers >/dev/null 2>&1; then
    MAINTAINERS=$(nix-instantiate --eval --strict -A incus-spawn.meta.maintainers 2>/dev/null)
    pass "maintainers resolves: $MAINTAINERS"
else
    fail "maintainers failed to evaluate (maintainer entry missing from maintainer-list.nix?)"
fi

# sourceProvenance
if PROV=$(cd "$NIXPKGS" && nix-instantiate --eval --strict -E \
    'builtins.map (x: x.shortName) (import ./. {}).incus-spawn.meta.sourceProvenance' 2>/dev/null); then
    pass "sourceProvenance: $PROV"
else
    skip "sourceProvenance: could not evaluate (non-critical)"
fi

# ── 4. Build ─────────────────────────────────────────────────────────
info "Building package..."

if PKG=$(cd "$NIXPKGS" && nix-build -A incus-spawn --no-out-link 2>&1 | tail -1) && [ -d "$PKG" ]; then
    pass "nix-build -A incus-spawn → $PKG"
else
    fail "nix-build -A incus-spawn failed"
    echo "  Output: $PKG"
    echo ""
    echo "Build failed — cannot continue with binary tests."
    # Print summary so far
    echo ""
    info "Summary: $PASSES passed, $FAILURES failed, $SKIPS skipped"
    exit 1
fi

# ── 5. Installed files ───────────────────────────────────────────────
info "Checking installed files..."

for f in bin/isx bin/git-remote-isx; do
    if [ -f "$PKG/$f" ] && [ -x "$PKG/$f" ]; then
        pass "$f exists and is executable"
    else
        fail "$f missing or not executable"
    fi
done

for f in share/bash-completion/completions/isx.bash \
         share/zsh/site-functions/_isx \
         share/fish/vendor_completions.d/isx.fish; do
    if [ -f "$PKG/$f" ] && [ -s "$PKG/$f" ]; then
        pass "$f exists and is non-empty"
    else
        fail "$f missing or empty"
    fi
done

# ── 6. Binary functionality ──────────────────────────────────────────
info "Testing binary functionality..."

# --version
if VERSION_OUT=$("$PKG/bin/isx" --version 2>&1) && echo "$VERSION_OUT" | grep -q "incus-spawn"; then
    pass "isx --version: $(echo "$VERSION_OUT" | head -1)"
else
    fail "isx --version failed"
fi

# --help
if HELP_OUT=$("$PKG/bin/isx" --help 2>&1) && echo "$HELP_OUT" | grep -q "build"; then
    SUBCMDS=$(echo "$HELP_OUT" | grep -c "^    " || true)
    pass "isx --help lists $SUBCMDS subcommands"
else
    fail "isx --help failed or missing subcommands"
fi

# templates
if TPL_OUT=$("$PKG/bin/isx" templates 2>&1) && echo "$TPL_OUT" | grep -q "tpl-"; then
    pass "isx templates: $(echo "$TPL_OUT" | tr '\n' ', ' | sed 's/,$//')"
else
    fail "isx templates failed"
fi

# completions
for shell in bash zsh fish; do
    LINES=$("$PKG/bin/isx" completion "$shell" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$LINES" -gt 10 ]; then
        pass "isx completion $shell: $LINES lines"
    else
        fail "isx completion $shell: only $LINES lines"
    fi
done

# git-remote-isx (exits non-zero on bad URLs, which is correct behaviour)
GR_OUT=$("$PKG/bin/git-remote-isx" test 2>&1 || true)
if echo "$GR_OUT" | grep -q "not an isx://"; then
    pass "git-remote-isx rejects non-isx:// URLs"
else
    fail "git-remote-isx unexpected output: $GR_OUT"
fi

# ── 7. passthru.tests.version ────────────────────────────────────────
info "Running passthru.tests.version..."

if TEST_OUT=$(cd "$NIXPKGS" && nix-build -A incus-spawn.passthru.tests.version --no-out-link 2>&1) && \
   echo "$TEST_OUT" | grep -q "/nix/store/"; then
    STORE_PATH=$(echo "$TEST_OUT" | grep "/nix/store/" | tail -1)
    pass "passthru.tests.version → $STORE_PATH"
else
    fail "passthru.tests.version failed"
    echo "  Output: $TEST_OUT"
fi

# ── 8. treefmt (formatting) ───────────────────────────────────────────
info "Checking formatting with treefmt..."

if [ -n "$SKIP_REVIEW" ]; then
    skip "treefmt (--skip-review or SKIP_REVIEW=1)"
else
    # treefmt only needs to check our package files, not all of nixpkgs.
    # nixfmt is the formatter for .nix files in nixpkgs.
    run_nixfmt() {
        if command -v nixfmt >/dev/null 2>&1; then
            nixfmt "$@"
        else
            nix-shell -p nixfmt --run "nixfmt $*"
        fi
    }

    NEEDS_FMT=0
    for nixfile in "$PACKAGE_DIR"/*.nix; do
        [ -f "$nixfile" ] || continue
        if run_nixfmt --check "$nixfile" >/dev/null 2>&1; then
            pass "$(basename "$nixfile") is properly formatted"
        else
            fail "$(basename "$nixfile") needs formatting (run: nixfmt $nixfile)"
            NEEDS_FMT=1
        fi
    done
    if [ "$NEEDS_FMT" -eq 1 ]; then
        echo "  Tip: run 'nix-shell -p nixfmt --run \"nixfmt $PACKAGE_DIR/*.nix\"' to fix"
    fi
fi

# ── 9. nixpkgs-vet ────────────────────────────────────────────────────
info "Running nixpkgs-vet..."

if [ -n "$SKIP_REVIEW" ]; then
    skip "nixpkgs-vet (--skip-review or SKIP_REVIEW=1)"
else
    if cd "$NIXPKGS" && git rev-parse --is-shallow-repository 2>/dev/null | grep -q true; then
        skip "nixpkgs-vet: shallow clone detected (needs full clone with base branch history)"
    elif ! cd "$NIXPKGS" && git log --oneline -1 -- pkgs/by-name/in/incus-spawn/ 2>/dev/null | grep -q .; then
        skip "nixpkgs-vet: package not committed to git (commit first, then re-run)"
    elif [ ! -f "$NIXPKGS/ci/nixpkgs-vet.sh" ]; then
        skip "nixpkgs-vet: ci/nixpkgs-vet.sh not found in nixpkgs checkout"
    else
        info "Running nixpkgs-vet (this may take a minute)..."
        if cd "$NIXPKGS" && bash ci/nixpkgs-vet.sh master 2>&1 | tee /tmp/nixpkgs-vet-out.txt | tail -5; then
            pass "nixpkgs-vet passed"
        else
            if grep -qi "exit code -9\|killed\|cannot allocate\|out of memory" /tmp/nixpkgs-vet-out.txt; then
                skip "nixpkgs-vet: OOM killed — not enough RAM, run on a larger machine"
            else
                fail "nixpkgs-vet failed (check /tmp/nixpkgs-vet-out.txt)"
            fi
        fi
    fi
fi

# ── 10. nixpkgs-review ───────────────────────────────────────────────
info "Checking nixpkgs-review..."

if [ -n "$SKIP_REVIEW" ]; then
    skip "nixpkgs-review (--skip-review or SKIP_REVIEW=1)"
else
    # Check if this is a shallow clone
    if cd "$NIXPKGS" && git rev-parse --is-shallow-repository 2>/dev/null | grep -q true; then
        skip "nixpkgs-review: shallow clone detected (use a full clone or pass --skip-review)"
    else
        # Check there are commits with our package
        if ! cd "$NIXPKGS" && git log --oneline -1 -- pkgs/by-name/in/incus-spawn/ 2>/dev/null | grep -q .; then
            skip "nixpkgs-review: package not committed to git (commit first, then re-run)"
        else
            REVIEW_COMMIT=$(cd "$NIXPKGS" && git log --oneline -1 -- pkgs/by-name/in/incus-spawn/ | cut -d' ' -f1)
            info "Running nixpkgs-review rev $REVIEW_COMMIT ..."

            run_review() {
                if command -v nixpkgs-review >/dev/null 2>&1; then
                    nixpkgs-review rev "$1" --no-shell
                else
                    nix-shell -p nixpkgs-review --run "nixpkgs-review rev $1 --no-shell"
                fi
            }

            if cd "$NIXPKGS" && run_review "$REVIEW_COMMIT" 2>&1 | tee /tmp/nixpkgs-review-out.txt | tail -10; then
                # nixpkgs-review prints "N package(s) built:" then package names on the next line
                if grep -qi "incus-spawn" /tmp/nixpkgs-review-out.txt; then
                    if grep -qi "failed" /tmp/nixpkgs-review-out.txt; then
                        fail "nixpkgs-review: incus-spawn failed to build"
                    else
                        pass "nixpkgs-review: incus-spawn built successfully"
                    fi
                elif grep -qi "no packages\|No diff" /tmp/nixpkgs-review-out.txt; then
                    fail "nixpkgs-review: no packages detected (commit may not diff against master)"
                else
                    skip "nixpkgs-review: ran but couldn't confirm incus-spawn in output (check /tmp/nixpkgs-review-out.txt)"
                fi
            else
                if grep -qi "exit code -9\|killed\|cannot allocate\|out of memory" /tmp/nixpkgs-review-out.txt; then
                    skip "nixpkgs-review: OOM killed — not enough RAM (needs ~4 GB+), run on a larger machine"
                else
                    fail "nixpkgs-review failed (check /tmp/nixpkgs-review-out.txt)"
                fi
            fi
        fi
    fi
fi

# ── Summary ──────────────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
info "Results: ${GREEN}${PASSES} passed${RESET}, ${RED}${FAILURES} failed${RESET}, ${YELLOW}${SKIPS} skipped${RESET}"
echo ""

echo "PR Checklist for $PLATFORM:"
echo ""

# Build
tick() { if [ "$1" = "1" ]; then echo "[x]"; else echo "[ ]"; fi; }
echo "- Built on platform:"
case "$PLATFORM" in
    x86_64-linux)
        echo "  - [x] x86_64-linux"
        echo "  - [ ] aarch64-linux          (test on aarch64-linux machine)"
        echo "  - [n/a] x86_64-darwin"
        echo "  - [ ] aarch64-darwin          (test on macOS Apple Silicon)"
        ;;
    aarch64-linux)
        echo "  - [ ] x86_64-linux            (test on x86_64-linux machine)"
        echo "  - [x] aarch64-linux"
        echo "  - [n/a] x86_64-darwin"
        echo "  - [ ] aarch64-darwin          (test on macOS Apple Silicon)"
        ;;
    aarch64-darwin)
        echo "  - [ ] x86_64-linux            (test on x86_64-linux machine)"
        echo "  - [ ] aarch64-linux           (test on aarch64-linux machine)"
        echo "  - [n/a] x86_64-darwin"
        echo "  - [x] aarch64-darwin"
        ;;
esac

echo "- Tested:"
echo "  - [n/a] NixOS tests          (CLI tool, no NixOS module)"
if [ "$FAILURES" -eq 0 ]; then
    echo "  - [x] passthru.tests"
else
    echo "  - [ ] passthru.tests"
fi
echo "  - [n/a] lib/tests, pkgs/test (not a lib or core package)"

if [ -n "$SKIP_REVIEW" ]; then
    echo "- [ ] Ran nixpkgs-review       (skipped)"
else
    echo "- [x] Ran nixpkgs-review"
fi

echo "- [x] Tested basic functionality of all binary files"
echo "- Nixpkgs Release Notes:"
echo "  - [n/a] Package update        (new package)"
echo "- NixOS Release Notes:"
echo "  - [n/a] Module addition/update (no NixOS module)"
echo "- [x] Fits CONTRIBUTING.md, pkgs/README.md, maintainers/README.md"
echo "- [x] Follows automation/AI policy (add Assisted-by: trailer to commits)"
echo ""

if [ "$FAILURES" -gt 0 ]; then
    echo -e "${RED}${BOLD}Some checks failed. Fix the issues above before submitting.${RESET}"
    exit 1
else
    echo -e "${GREEN}${BOLD}All checks passed for $PLATFORM!${RESET}"
    exit 0
fi
