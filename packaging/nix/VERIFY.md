# Verifying the incus-spawn Nix package

Step-by-step instructions to tick every checklist item on the nixpkgs PR.

The package supports `x86_64-linux`, `aarch64-linux`, and `aarch64-darwin`
(pre-built native binaries from GitHub Releases).

---

## Prerequisites

### Install Nix (if not already installed)

**Linux (x86_64 or aarch64):**

```bash
sh <(curl -L https://nixos.org/nix/install) --no-daemon
. ~/.nix-profile/etc/profile.d/nix.sh
```

**macOS (aarch64-darwin):**

```bash
sh <(curl -L https://nixos.org/nix/install)
# Restart your shell after installation
```

### Clone nixpkgs (full clone, not shallow)

`nixpkgs-review` requires a full clone. This is ~2 GB and takes a few minutes:

```bash
git clone https://github.com/NixOS/nixpkgs.git ~/nixpkgs
cd ~/nixpkgs
```

If you already have a shallow clone, convert it:

```bash
cd ~/nixpkgs
git fetch --unshallow
```

---

## Step 1: Set up the package locally

```bash
cd ~/nixpkgs

# Create the package directory
mkdir -p pkgs/by-name/in/incus-spawn

# Copy package files from incus-spawn repo (adjust path as needed)
ISX_REPO=~/incus-spawn  # or wherever you cloned it
cp "$ISX_REPO/packaging/nix/package.nix" pkgs/by-name/in/incus-spawn/package.nix
```

### Add maintainer entry

Find the right alphabetical position in `maintainers/maintainer-list.nix`
(after any entry starting with "gal", before "galen"):

```bash
# Find insertion point
grep -n 'galen = {' maintainers/maintainer-list.nix
```

Add this block just before the `galen` entry:

```nix
  galder = {
    github = "galderz";
    githubId = 50187;
    name = "Galder Zamarreño";
  };
```

### Commit

```bash
git add maintainers/maintainer-list.nix
git commit -m "maintainers: add galder" \
  --trailer "Assisted-by: Claude, Anthropic (claude-sonnet-4-20250514)"

git add pkgs/by-name/in/incus-spawn/
git commit -m "incus-spawn: init at 0.2.6" \
  -m "https://github.com/Sanne/incus-spawn/releases/tag/v0.2.6" \
  --trailer "Assisted-by: Claude, Anthropic (claude-sonnet-4-20250514)"
```

---

## Step 2: Build on x86_64-linux

Run these on a Linux x86_64 machine (or inside an incus-spawn container).

### 2a. Build the package

```bash
cd ~/nixpkgs
nix-build -A incus-spawn
```

Expected output: a `/nix/store/...-incus-spawn-X.Y.Z` path and a `./result` symlink.

### 2b. Test binary functionality

```bash
# Version
./result/bin/isx --version
# Expected: incus-spawn X.Y.Z (...)

# Help (all subcommands listed)
./result/bin/isx --help

# Built-in templates
./result/bin/isx templates
# Expected: tpl-minimal, tpl-dev, tpl-java

# Shell completions
./result/bin/isx completion bash | wc -l   # should be > 100
./result/bin/isx completion zsh  | wc -l   # should be > 100
./result/bin/isx completion fish | wc -l   # should be > 50

# git-remote-isx (should reject non-isx:// URLs)
./result/bin/git-remote-isx test 2>&1
# Expected: error: not an isx:// URL: test
```

### 2c. Verify installed files

```bash
find ./result -type f | sort
```

Expected:

```
./result/bin/git-remote-isx
./result/bin/isx
./result/share/bash-completion/completions/isx.bash
./result/share/fish/vendor_completions.d/isx.fish
./result/share/zsh/site-functions/_isx
```

### 2d. Run passthru.tests.version

```bash
nix-build -A incus-spawn.passthru.tests.version
```

Expected: builds a test derivation that runs `isx --version` and checks the
version string matches. Success = a store path is printed with no errors.

### 2e. Run nixpkgs-review

```bash
cd ~/nixpkgs
nixpkgs-review rev HEAD --no-shell
```

Expected output should include:

```
Built: incus-spawn
```

If it says "No diff detected", make sure your package commit is HEAD and
you have a proper git history (not a shallow clone).

---

## Step 3: Build on aarch64-darwin (macOS Apple Silicon)

Run these on a Mac with Apple Silicon.

### 3a. Build the package

```bash
cd ~/nixpkgs
nix-build -A incus-spawn
```

Expected: a `/nix/store/...-incus-spawn-X.Y.Z` path and a `./result` symlink.

### 3b. Test binary functionality

Same tests as Step 2b:

```bash
./result/bin/isx --version
./result/bin/isx --help
./result/bin/isx templates
./result/bin/isx completion bash | wc -l
./result/bin/isx completion zsh  | wc -l
./result/bin/isx completion fish | wc -l
./result/bin/git-remote-isx test 2>&1
```

### 3c. Verify installed files

```bash
find ./result -type f | sort
```

Expected (same as Linux):

```
./result/bin/git-remote-isx
./result/bin/isx
./result/share/bash-completion/completions/isx.bash
./result/share/fish/vendor_completions.d/isx.fish
./result/share/zsh/site-functions/_isx
```

### 3d. Run passthru.tests.version

```bash
nix-build -A incus-spawn.passthru.tests.version
```

### 3e. Run nixpkgs-review

```bash
nixpkgs-review rev HEAD --no-shell
```

## Step 4: Build on aarch64-linux

### Option A: You have an aarch64-linux machine

Repeat Steps 2a–2e on the aarch64-linux machine.

### Option B: From an aarch64-darwin Mac using a remote Linux builder

If you have a Linux builder configured (e.g., via `nix.linux-builder` in
nix-darwin, or a remote builder in `/etc/nix/machines`):

```bash
cd ~/nixpkgs

# Build targeting aarch64-linux via the Linux builder
nix-build -A incus-spawn --system aarch64-linux

# The binary won't run on macOS, but verify the derivation built:
find ./result -type f | sort

# Run the version test (also builds on the Linux builder)
nix-build -A incus-spawn.passthru.tests.version --system aarch64-linux
```

### Option C: Expression evaluation only (no builder needed)

```bash
cd ~/nixpkgs
nix-instantiate -A incus-spawn --system aarch64-linux
# Expected: /nix/store/...-incus-spawn-X.Y.Z.drv
```

---

## Step 5: Verify conventions

### 5a. Commit message format

```bash
cd ~/nixpkgs
git log --oneline -2
```

Expected:

```
abc1234 incus-spawn: init at 0.2.6
def5678 maintainers: add galder
```

### 5b. AI policy compliance

```bash
git log -2 --format="%B---"
```

Both commit messages should contain:

```
Assisted-by: Claude, Anthropic (claude-sonnet-4-20250514)
```

### 5c. Package structure

```bash
# Correct by-name prefix (first 2 chars of "incus-spawn")
ls pkgs/by-name/in/incus-spawn/
# Expected: package.nix

# Not added to all-packages.nix (by-name packages are auto-discovered)
grep incus-spawn pkgs/top-level/all-packages.nix
# Expected: no output
```

### 5d. Meta attributes

```bash
cd ~/nixpkgs

# Description exists, doesn't start with article
nix-instantiate --eval -A incus-spawn.meta.description
# "CLI tool for managing isolated Incus development environments"

# License
nix-instantiate --eval -A incus-spawn.meta.license.spdxId
# "Apache-2.0"

# Source provenance (binary package)
nix-instantiate --eval -E \
  'builtins.map (x: x.tag) (import ./. {}).incus-spawn.meta.sourceProvenance' --json
# ["binaryNativeCode"]

# Maintainer resolves
nix-instantiate --eval --strict -A incus-spawn.meta.maintainers
# Should show the galder record

# mainProgram
nix-instantiate --eval -A incus-spawn.meta.mainProgram
# "isx"
```

---

## Checklist summary

After running the steps above, you can tick:

```
- Built on platform:
  - [x] x86_64-linux           (Step 2a)
  - [x] aarch64-linux          (Step 4)
  - [n/a] x86_64-darwin        (no x86_64 macOS binary published)
  - [x] aarch64-darwin         (Step 3)
- Tested, as applicable:
  - [n/a] NixOS tests          (CLI tool, no NixOS module)
  - [x] passthru.tests         (Step 2d)
  - [n/a] lib/tests, pkgs/test (not a lib or core package)
- [x] Ran nixpkgs-review       (Step 2e)
- [x] Tested basic functionality of all binary files (Step 2b)
- Nixpkgs Release Notes:
  - [n/a] Package update        (new package, not an update)
- NixOS Release Notes:
  - [n/a] Module addition       (no NixOS module)
  - [n/a] Module update         (no NixOS module)
- [x] Fits CONTRIBUTING.md, pkgs/README.md, maintainers/README.md (Step 5)
- [x] Follows automation/AI policy (Step 5b)
```
