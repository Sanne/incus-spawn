---
paths:
  - "cli/src/main/java/dev/incusspawn/IncusSpawn*.java"
  - "cli/src/main/java/dev/incusspawn/command/**"
  - "cli/src/main/java/dev/incusspawn/tui/**"
---

# Entry Point and Command Structure

`IncusSpawn.java` is the aesh `@CommandDefinition` top command. With no subcommand, it launches the TUI (`ListCommand`). Each subcommand in `command/` is an aesh `@CommandDefinition` with Quarkus DI.

**Platform-specific command tree**: aesh bakes `groupCommands` into the annotation at compile time, so macOS-only commands can't exist on one platform without a second top command. `IncusSpawn` defines two variants -- `IncusSpawnCommand` (macOS, includes the `vm` group) and `IncusSpawnLinuxCommand` (identical minus `vm`) -- and picks one at runtime via `Platform.isMacOS()`. The result: on Linux `isx vm` is an unknown command and never appears in `isx --help` (Incus runs natively there, so there is no appliance VM to manage). `CompletionCommand.stripVmCommand()` keeps the generated shell completions in step by removing the `vm` command on Linux (leaving the unrelated `build --type vm` value untouched). When adding a *shared* command, add it to **both** command lists -- `IncusSpawnCommandTreeTest` fails if they drift (the two lists must be identical except for `vm`). When adding another macOS-only command, add it to `IncusSpawnCommand` only and extend the completion strip.

# Init and Versioned Completion

`InitCommand` runs first-time setup (dependencies, Incus, firewall, CA, proxy service, etc.). On Linux it also installs a tightly-scoped NOPASSWD sudoers rule (`configureBtrfsUsageAccess` -> `/etc/sudoers.d/incus-spawn-btrfs`) permitting only read-only `btrfs qgroup show`/`subvolume list` plus `btrfs quota rescan` (rebuilds accounting counters, never touches data — the auto-repair for inconsistent qgroups, see `BtrfsUsage`) against the CoW pool mount, so the non-root TUI/build can read referenced sizes for per-template disk accounting and repair them when btrfs flags them stale (validated with `visudo -cf` before install). Commands that need a working environment call `InitCommand.requireInit()`, which auto-launches init if it hasn't completed. Completion is tracked by a sentinel file `~/.config/incus-spawn/.init-complete` containing `INIT_VERSION` -- a version integer defined in `InitCommand`. Every init step is idempotent, so re-running is safe.

**When to bump `INIT_VERSION`**: increment it when adding a new infrastructure step to init that existing installations need (new dependency, firewall rule, systemd service, config field). A sentinel *older* than `INIT_VERSION` causes `hasBeenInitialized()` to return false, triggering a re-run on the next command. Do NOT bump it for changes that don't affect host configuration (new template features, TUI changes, proxy logic changes).

The comparison is `>=`, not equality: the sentinel is a monotonic floor, so a binary that finds a *newer* sentinel treats itself as initialized rather than concluding init never ran. This matters when two isx builds are installed at once (e.g. `/usr/bin/isx` and `~/.local/bin/isx`) -- under equality the older one both hard-failed the proxy service and re-ran init to write the sentinel back down, ping-ponging with the newer binary. Never renumber `INIT_VERSION` downward.
