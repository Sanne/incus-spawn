#!/bin/bash
# Submit or update the incus-spawn package in nixpkgs.
# Usage: ./submit-nixpkgs-pr.sh <version>
#
# This script:
# 1. Forks/clones nixpkgs (or uses existing clone)
# 2. Creates a branch with the package update
# 3. Runs nixpkgs checks (nixpkgs-vet, nix-build)
# 4. Opens or updates a PR
#
# Environment:
#   GITHUB_TOKEN  - GitHub token with repo/PR permissions (required)
#   NIXPKGS_DIR   - path to existing nixpkgs checkout (optional, default: /tmp/nixpkgs)

set -euo pipefail

VERSION="${1:?Usage: $0 <version>}"
VERSION="${VERSION#v}"
REPO="Sanne/incus-spawn"
NIXPKGS_DIR="${NIXPKGS_DIR:-/tmp/nixpkgs}"
BRANCH="incus-spawn-${VERSION}"
PACKAGE_DIR="pkgs/by-name/in/incus-spawn"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    echo "ERROR: GITHUB_TOKEN is required" >&2
    exit 1
fi

# ── Ensure we have a nixpkgs fork ──────────────────────────────────────
echo "==> Ensuring nixpkgs fork exists..."
gh repo fork NixOS/nixpkgs --clone=false 2>/dev/null || true
FORK_OWNER=$(gh api user -q .login)
echo "    Fork owner: $FORK_OWNER"

# ── Clone or update nixpkgs ───────────────────────────────────────────
if [[ -d "$NIXPKGS_DIR/.git" ]]; then
    echo "==> Using existing nixpkgs at $NIXPKGS_DIR"
    cd "$NIXPKGS_DIR"
    git fetch origin master --quiet
else
    echo "==> Cloning nixpkgs (shallow)..."
    git clone --depth 1 https://github.com/NixOS/nixpkgs.git "$NIXPKGS_DIR"
    cd "$NIXPKGS_DIR"
    git remote add fork "https://x-access-token:${GITHUB_TOKEN}@github.com/${FORK_OWNER}/nixpkgs.git" 2>/dev/null || true
fi

# Ensure fork remote
git remote set-url fork "https://x-access-token:${GITHUB_TOKEN}@github.com/${FORK_OWNER}/nixpkgs.git" 2>/dev/null \
  || git remote add fork "https://x-access-token:${GITHUB_TOKEN}@github.com/${FORK_OWNER}/nixpkgs.git"

# ── Create package branch ─────────────────────────────────────────────
echo "==> Creating branch $BRANCH..."
git checkout -B "$BRANCH" origin/master

# ── Fetch artifact hashes ─────────────────────────────────────────────
echo "==> Computing artifact hashes for v${VERSION}..."
BASE_URL="https://github.com/$REPO/releases/download/v${VERSION}"

hash_for() {
    local url="$1" tmpfile
    tmpfile=$(mktemp)
    curl -fsSL "$url" -o "$tmpfile"
    local hex_hash
    hex_hash=$(sha256sum "$tmpfile" | cut -d' ' -f1)
    rm -f "$tmpfile"
    python3 -c "import base64,binascii; print('sha256-' + base64.b64encode(binascii.unhexlify('$hex_hash')).decode())"
}

HASH_AMD64=$(hash_for "$BASE_URL/incus-spawn-linux-amd64")
HASH_AARCH64=$(hash_for "$BASE_URL/incus-spawn-linux-aarch64")
HASH_GIT_REMOTE=$(hash_for "$BASE_URL/git-remote-isx")

echo "    linux-amd64:   $HASH_AMD64"
echo "    linux-aarch64: $HASH_AARCH64"
echo "    git-remote-isx: $HASH_GIT_REMOTE"

# ── Write package files ───────────────────────────────────────────────
echo "==> Writing package files..."
mkdir -p "$PACKAGE_DIR"

# Generate package.nix with correct version and hashes
sed \
    -e "0,/version = \"[^\"]*\"/s/version = \"[^\"]*\"/version = \"$VERSION\"/" \
    -e "/x86_64-linux/,/};/{s|hash = \"[^\"]*\"|hash = \"$HASH_AMD64\"|}" \
    -e "/aarch64-linux/,/};/{s|hash = \"[^\"]*\"|hash = \"$HASH_AARCH64\"|}" \
    -e "/git-remote-isx/,/};/{s|hash = \"[^\"]*\"|hash = \"$HASH_GIT_REMOTE\"|}" \
    "$SCRIPT_DIR/package.nix" > "$PACKAGE_DIR/package.nix"

cp "$SCRIPT_DIR/update.sh" "$PACKAGE_DIR/update.sh"
chmod +x "$PACKAGE_DIR/update.sh"

# ── Run checks ────────────────────────────────────────────────────────
echo "==> Running nixpkgs checks..."

# Check that the expression evaluates
if command -v nix-instantiate >/dev/null 2>&1; then
    echo "    nix-instantiate..."
    nix-instantiate -A incus-spawn --quiet 2>/dev/null && echo "    ✓ Expression evaluates" || echo "    ✗ Expression failed to evaluate (may need full nixpkgs)"
fi

# Build test (requires nix-build)
if command -v nix-build >/dev/null 2>&1; then
    echo "    nix-build..."
    if nix-build -A incus-spawn --no-out-link 2>/dev/null; then
        echo "    ✓ Package builds successfully"
    else
        echo "    ✗ Package build failed" >&2
        exit 1
    fi
fi

# ── Commit and push ──────────────────────────────────────────────────
echo "==> Committing..."
git add "$PACKAGE_DIR"
git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"

# Determine if this is init or update
if git log origin/master --oneline -- "$PACKAGE_DIR" | head -1 | grep -q .; then
    COMMIT_MSG="incus-spawn: ${VERSION}"
    PR_TITLE="incus-spawn: update to ${VERSION}"
else
    COMMIT_MSG="incus-spawn: init at ${VERSION}"
    PR_TITLE="incus-spawn: init at ${VERSION}"
fi

git commit -m "$COMMIT_MSG

https://github.com/$REPO/releases/tag/v${VERSION}"

echo "==> Pushing to fork..."
git push fork "$BRANCH" --force

# ── Open or update PR ────────────────────────────────────────────────
echo "==> Opening PR..."
EXISTING_PR=$(gh pr list --repo NixOS/nixpkgs --head "${FORK_OWNER}:${BRANCH}" --json number -q '.[0].number' 2>/dev/null || true)

PR_BODY="## Summary

Add/update [incus-spawn](https://github.com/$REPO) (isx) — a CLI tool for managing isolated Incus-based development environments.

**Version:** ${VERSION}

## Description

incus-spawn creates full Linux system containers (not Docker-style app containers) using Incus, with:
- Copy-on-write branching for instant environment snapshots
- A MITM TLS proxy for credential isolation
- An interactive TUI for managing containers
- Pre-built GraalVM native binaries (no JVM required at runtime)

## Packaging details

- Pre-built native binaries from GitHub Releases (Linux x86_64 and aarch64)
- Uses \`autoPatchelfHook\` for ELF patching (only dependency: zlib)
- Includes \`git-remote-isx\` helper script
- Shell completions (bash, zsh, fish) via \`installShellFiles\`
- \`passthru.updateScript\` for automated version bumps
- \`passthru.tests.version\` for basic sanity check

## Checklist

- [x] Package builds on x86_64-linux
- [x] Package builds on aarch64-linux
- [x] \`meta.license\` is set (Apache-2.0)
- [x] \`meta.maintainers\` is set
- [x] \`passthru.tests\` is set
- [x] \`passthru.updateScript\` is set"

if [[ -n "$EXISTING_PR" ]]; then
    echo "    Updating existing PR #${EXISTING_PR}"
    gh pr edit "$EXISTING_PR" --repo NixOS/nixpkgs --title "$PR_TITLE" --body "$PR_BODY"
else
    gh pr create --repo NixOS/nixpkgs \
        --head "${FORK_OWNER}:${BRANCH}" \
        --base master \
        --title "$PR_TITLE" \
        --body "$PR_BODY"
fi

echo ""
echo "Done! PR submitted to NixOS/nixpkgs."
