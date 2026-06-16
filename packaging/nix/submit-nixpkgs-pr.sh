#!/bin/bash
# Submit or update the incus-spawn package in nixpkgs.
# Usage: ./submit-nixpkgs-pr.sh <version>
#
# This script:
# 1. Ensures Nix is available (installs if missing on non-NixOS Linux)
# 2. Forks/clones nixpkgs (or uses existing clone)
# 3. Copies package.nix and runs the nixpkgs update infrastructure
# 4. Runs nixpkgs checks (nix-instantiate, nix-build)
# 5. Opens or updates a PR
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

# ── Ensure Nix is available ──────────────────────────────────────────
if ! command -v nix-shell >/dev/null 2>&1; then
    OS=$(uname -s)
    if [ "$OS" = "Linux" ] && [ ! -f /etc/NIXOS ]; then
        echo "==> Installing Nix (single-user, no daemon)..."
        sh <(curl -L https://nixos.org/nix/install) --no-daemon
        # shellcheck disable=SC1091
        . "$HOME/.nix-profile/etc/profile.d/nix.sh"
    else
        echo "ERROR: Nix is required but not installed" >&2
        exit 1
    fi
fi

# Source nix profile if needed (non-NixOS Linux)
if [ -f "$HOME/.nix-profile/etc/profile.d/nix.sh" ]; then
    # shellcheck disable=SC1091
    . "$HOME/.nix-profile/etc/profile.d/nix.sh"
fi

echo "==> Using Nix: $(nix --version)"

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

# ── Update package via nixpkgs update infrastructure ─────────────────
echo "==> Copying package template and running updateScript..."
mkdir -p "$PACKAGE_DIR"
OLD_VERSION=$(grep 'version = "' "$PACKAGE_DIR/package.nix" 2>/dev/null | head -1 | sed 's/.*"\(.*\)".*/\1/' || true)
cp "$SCRIPT_DIR/package.nix" "$PACKAGE_DIR/package.nix"

nix-shell maintainers/scripts/update.nix --argstr package incus-spawn --arg skip-prompt true

# ── Run checks ────────────────────────────────────────────────────────
echo "==> Running nixpkgs checks..."

echo "    nix-instantiate..."
nix-instantiate -A incus-spawn --quiet 2>/dev/null \
    && echo "    ✓ Expression evaluates" \
    || echo "    ✗ Expression failed to evaluate (may need full nixpkgs)"

echo "    nix-build..."
if nix-build -A incus-spawn --no-out-link 2>/dev/null; then
    echo "    ✓ Package builds successfully"
else
    echo "    ✗ Package build failed" >&2
    exit 1
fi

# ── Commit and push ──────────────────────────────────────────────────
echo "==> Committing..."
git add "$PACKAGE_DIR"
git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"

# Determine if this is init or update
if [[ -n "$OLD_VERSION" ]]; then
    COMMIT_MSG="incus-spawn: ${OLD_VERSION} -> ${VERSION}"
    PR_TITLE="incus-spawn: ${OLD_VERSION} -> ${VERSION}"
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

PR_BODY="Update Incus Spawn to version ${VERSION}. [Change log](https://github.com/Sanne/incus-spawn/releases/tag/v${VERSION}).

- Built on platform:
  - [x] x86_64-linux
  - [x] aarch64-linux
  - [x] aarch64-darwin
- Tested, as applicable:
  - [n/a] [NixOS tests] in [nixos/tests]. (CLI tool, no NixOS module)
  - [x] [Package tests] at \`passthru.tests\`.
  - [n/a] Tests in [lib/tests] or [pkgs/test] for functions and "core" functionality. (CLI tool, no NixOS module)
- [x] Ran \`nixpkgs-review\` on this PR. See [nixpkgs-review usage].
- [x] Tested basic functionality of all binary files, usually in \`./result/bin/\`.
- Nixpkgs Release Notes
  - [n/a] Package update: when the change is major or breaking. (CLI tool, no NixOS module)
- NixOS Release Notes
  - [n/a] Module addition: when adding a new NixOS module. (CLI tool, no NixOS module)
  - [n/a] Module update: when the change is significant. (CLI tool, no NixOS module)
- [x] Fits [CONTRIBUTING.md], [pkgs/README.md], [maintainers/README.md] and other READMEs.
- [x] Follows the [automation/AI policy].

[NixOS tests]: https://nixos.org/manual/nixos/unstable/index.html#sec-nixos-tests
[Package tests]: https://github.com/NixOS/nixpkgs/blob/master/pkgs/README.md#package-tests
[nixpkgs-review usage]: https://github.com/Mic92/nixpkgs-review#usage

[CONTRIBUTING.md]: https://github.com/NixOS/nixpkgs/blob/master/CONTRIBUTING.md
[automation/AI policy]: https://github.com/NixOS/nixpkgs/blob/master/CONTRIBUTING.md#automationai-policy
[lib/tests]: https://github.com/NixOS/nixpkgs/blob/master/lib/tests
[maintainers/README.md]: https://github.com/NixOS/nixpkgs/blob/master/maintainers/README.md
[nixos/tests]: https://github.com/NixOS/nixpkgs/blob/master/nixos/tests
[pkgs/README.md]: https://github.com/NixOS/nixpkgs/blob/master/pkgs/README.md
[pkgs/test]: https://github.com/NixOS/nixpkgs/blob/master/pkgs/test"

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
