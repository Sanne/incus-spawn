# CLI reference

Every command, with what `--help` does not say.
`isx <command> --help` lists all of its options.

## `isx`

Launch the interactive TUI.
Falls back to plain-text listing when stdout is not a terminal.

    isx

## `isx init`

One-time host setup: install Incus, configure credentials and their [accounts](CONFIGURATION.md#credential-accounts), test connectivity.
Safe to re-run, e.g. to add, rename or remove an account.

    isx init

## `isx build`

Build or rebuild a template image.

    isx build [<template>...] [options]

A template's name may be at most 52 characters, since it is built as `<name>-rebuilding` first and swapped in once built; a longer one is refused before anything is built, its parents included.

Several templates build in one invocation: plainly, each in turn, a named parent before its child (each with any parent that is missing or out of sync, so a parent they share is built once); with `--with-parents` or `--with-descendants`, as one batch with one confirmation, parents before children and every template once.
An unknown name anywhere in the list builds nothing.
A target that fails does not stop the others: one that inherits from it is skipped, the rest are built, and the build then exits 1 with one `Some templates failed to build:` line on stderr naming every template it left unbuilt (a parent a target's chain failed on included).

| Option | Description |
|--------|-------------|
| `--all` | Rebuild all defined templates |
| `--out-of-sync` | Rebuild templates whose definition or isx version changed, or whose parent was rebuilt after them, with everything inheriting from them |
| `--with-parents` | Rebuild the templates and all their parents unconditionally |
| `--with-descendants` | Rebuild the templates and all templates inheriting from them |
| `--missing` | Build only templates that don't exist yet; like several named templates, a failure skips what inherits from it and the rest are still built, then exits 1 naming what was not |
| `--type <type>` | Instance type: `container`, `vm`, or `kvm` (overrides image definition); `vm` and `kvm` work on Linux only |
| `--yes` | Skip interactive confirmations |
| `--skip-git-refresh` | Compatibility option; builds no longer refresh host checkouts |

## `isx branch`

Create a new instance as a copy-on-write clone from a template or existing instance.

    isx branch <name> [options]

| Option | Description |
|--------|-------------|
| `--from <source>` | Source instance to branch from (auto-detected from cwd if omitted) |
| `--gui` | Enable GUI passthrough (Wayland + GPU + audio); the default for a `gui: true` container template (not a project-local one) when isx runs in a Wayland session |
| `--no-gui` | Disable GUI passthrough even if the template has `gui: true` |
| `--kvm` | Expose /dev/kvm for nested virtualization (Linux only: refused on macOS) |
| `--no-kvm` | Disable KVM even if the template was built with `type: kvm` |
| `--airgap` | No network devices: every NIC, InfiniBand device and outbound `proxy` device is masked, and `pci`/`usb` passthrough is refused |
| `--proxy-only` | Restrict network to the host proxy only |
| `--inbox <dir>` | Host directory to mount read-only at ~/inbox inside the instance |
| `--cpu <N>` | CPU core limit (default: none for containers; for VMs host CPUs minus 2, at most 8, and on a hybrid host at most its top tier's physical cores) |
| `--memory <size>` | Memory limit, e.g. `8GB` (default: adaptive) |
| `--disk <size>` | Disk size limit (default: adaptive) |
| `--no-start` | Don't start the instance after creation |
| `--shell` | Open a plain shell instead of running the default action |
| `--account <ns>=<account>` | Use this [credential account](CONFIGURATION.md#credential-accounts) instead of the template's or the default; repeatable |
| `--mcp-client` | Let the instance drive isx over MCP, at `https://mcp.isx.internal/mcp`: a [coordinator in an instance](MCP.md#a-coordinator-in-an-instance). Never inherited by its copies |

## `isx shell`

Open a shell in an existing instance.

    isx shell <instance> isx shell <instance> --task [<task-id>]

With `--task`, it opens the conversation of a task an agent [delegated over MCP](MCP.md) instead of a shell: the agent resumes it in the directory it worked in, and leaving it ends the session.
Without an id it joins the only task in the instance; with several it lists them, each with the command that joins it, and exits.
A task in the middle of a turn is refused until the turn ends.
Plain `isx shell` into an instance `isx mcp` made prints that command before the prompt.

Copying from an agent inside the instance needs an OSC 52-capable host terminal.
See the [FAQ](../README.md#why-doesnt-copied-from-an-agent-reach-my-host-clipboard).

When the `shell-status-bar` feature is enabled, a two-line status bar is pinned at the bottom of the terminal showing the instance name, template, IP address, and network mode.
Press **F12** to open a quick-action menu with shortcuts from tool actions that set `shell_menu: true`.
Enable with:

```yaml
# ~/.config/incus-spawn/config.yaml
features:
  - shell-status-bar
```

## `isx run`

Run the default action or a specific action on an instance.

    isx run <instance> [options]

| Option | Description |
|--------|-------------|
| `--action <ref>` | Action to run (`tool-name` or `tool-name:action-id`) |

## `isx destroy`

Destroy an instance, or bulk-destroy all templates or instances.

    isx destroy <instance> isx destroy --all-templates # destroy all built templates (derived first) isx destroy --all-instances # destroy all instances

| Option | Description |
|--------|-------------|
| `--all-templates` | Destroy all built templates (reverse order, derived first) |
| `--all-instances` | Destroy all instances |
| `--skip-confirmation` | Skip the confirmation prompt |

## `isx list`

List the instances isx created, without opening the TUI.
Templates are not listed: `isx templates` lists those.
On macOS it starts the VM if it is stopped, except with `-q`, which shell completion runs.
See [Scripting isx](#scripting-isx) for the `plain` and `json` formats.

    isx list [options]

| Option | Description |
|--------|-------------|
| `--format=<format>` | `table` (the default, for people), `plain` or `json` (for scripts) |
| `--plain` | Same as `--format=plain`. Before this release it was a no-op that printed the table, so a script that skipped a header with it now gets records from the first line |
| `-q`, `--quiet` | Print instance names only, one per line |
| `--status=<status>` | Only instances that are `running` or `stopped` |

`isx instances`, which printed the names of every Incus instance not named `tpl-*`, is deprecated and will be removed in a later release; it now runs `isx list -q`.
Shell completion scripts generated by this release no longer call it.

## `isx update-base`

Check for and install base image updates.

    isx update-base [<release-tag>] [options]

Pass a release tag (e.g. `fedora-44-v2`) to pin to that version.

| Option | Description |
|--------|-------------|
| `--list` | List available versions |
| `--latest` | Track the latest version (remove any pin) |

## `isx update-all`

Update system packages, npm globals, and git-fetch repos in all templates.
Does not re-clone repos or reinstall tools; for that, use `isx build`.

    isx update-all [options]

| Option | Description |
|--------|-------------|
| `--prime` | Also re-run prime commands (e.g. `mvn install -DskipTests`) for repos that define one |

## `isx templates`

Manage template definitions.
Running bare `isx templates` defaults to `list`.

    isx templates <subcommand>

| Subcommand | Description |
|------------|-------------|
| `list` | List available template names |
| `new` | Create a new template definition |
| `edit` | Edit a template definition in `$EDITOR` |

### `isx templates list`

    isx templates list [options]

| Option | Description |
|--------|-------------|
| `-v`, `--verbose` | Show source and description |

### `isx templates new`

    isx templates new [<name>]

| Option | Description |
|--------|-------------|
| `--project` | Create in project-local directory (`.incus-spawn/images/`) |

### `isx templates edit`

    isx templates edit <name>

## `isx tools`

List and inspect available tool definitions.
Running bare `isx tools` defaults to `list`.

    isx tools <subcommand>

| Subcommand | Description |
|------------|-------------|
| `list` | List available tools |
| `show` | Show details of a tool definition |

### `isx tools list`

    isx tools list [options]

| Option | Description |
|--------|-------------|
| `-v`, `--verbose` | Show source and description |

### `isx tools show`

    isx tools show <name>

## `isx account`

Add credentials, and show or change which [credential account](CONFIGURATION.md#credential-accounts) an instance uses.
Running bare `isx account` defaults to `list`.

    isx account <subcommand>

| Subcommand | Description |
|------------|-------------|
| `list` | Configured accounts per credential, the default, and the instances pinned to each |
| `add [<credential>]` | Add or replace one credential with the step `isx init` runs for it, and nothing else: no sudo, no other step. Name it by namespace (`github`, `claude`, `openai`) or by tool (`gh`, `codex`); without a name, choose from init's credential menu. Adding a second account goes through the same account menu. The proxy picks the change up by itself |
| `show <instance>` | The account the instance uses for each credential, and where it comes from |
| `set <instance> <ns>=<account>...` | Pin the instance to these accounts; takes effect on its next request |
| `unset <instance> <ns>...` | Remove the pins, so the instance follows the default again |
| `reset-proofs <instance>` | Revoke shared proofs and deliver replacements; waits for proxy acknowledgement. Restart tools and nested services with the new tokens |

## `isx project`

Manage project templates defined by an `incus-spawn.yaml` file.

    isx project <subcommand>

| Subcommand | Description |
|------------|-------------|
| `create` | Create a project template from a parent base image |
| `update` | Update an existing project template (packages, repos, deps) |

### `isx project create`

    isx project create <name> [options]

| Option | Description |
|--------|-------------|
| `--config <path>` | Path to `incus-spawn.yaml` (default: auto-detect from cwd) |

A project name may be at most 52 characters, since it is built as `<name>-rebuilding` first.
Re-creating an existing template builds the new one alongside it and replaces it only once the build succeeds.
If a repository fails to clone or the pre-build fails, the command exits non-zero, deletes the incomplete build and keeps the previous template.

### `isx project update`

    isx project update <name> [options]

| Option | Description |
|--------|-------------|
| `--config <path>` | Path to `incus-spawn.yaml` (default: auto-detect from cwd; a detected file must name this template) |

If any step fails (system update, npm update, git fetch, pre-build), the command exits non-zero and the template is kept.

## `isx proxy`

Manage the MITM authentication proxy.

    isx proxy <subcommand>

| Subcommand | Description |
|------------|-------------|
| `start` | Start the proxy |
| `stop` | Stop the proxy (handles both systemd and manual processes) |
| `restart` | Restart the proxy service |
| `status` | Check if the proxy is running |
| `activity` | What each instance sent through the proxy: requests, in flight, bytes, model calls and tokens (`--by-domain` for one row per domain, `--format=plain\|json` for scripts) |
| `install` | Install as a systemd user service (auto-starts on boot) |
| `uninstall` | Stop and remove the systemd proxy service |
| `logs` | Follow the proxy log in real time |
| `dump` | Run a pass-through proxy for host-side traffic capture |

### `isx proxy start`

    isx proxy start [options]

| Option | Description |
|--------|-------------|
| `--port <port>` | MITM TLS proxy port (default: `18443`) |
| `--health-port <port>` | Health check HTTP port (default: `18080`) |
| `--gateway-ip <ip>` | Incus bridge gateway IP, for when Incus cannot report it. On Linux it must be the bridge's own address, which is where every `isx` command looks for the proxy: when the bridge can be read, exactly its gateway; when not, a private (RFC 1918) or loopback address matching the gateway `isx init` recorded, if any. On macOS, the host's VM-facing bridge address |
| `--debug` | Log full request/response details |

### `isx proxy dump`

    isx proxy dump [options]

| Option | Description |
|--------|-------------|
| `--port <port>` | Local HTTP port (default: `19080`) |

## `isx doctor`

Diagnose host, proxy, VM, and tunnel health; offers to fix problems found.

    isx doctor [options]

| Option | Description |
|--------|-------------|
| `--deep` | Run per-instance checks (DNS, TLS, resolv.conf) |
| `--bundle` | Collect findings and logs into a support archive (.tar.gz); implies `--deep` |

Findings are marked `✓` healthy, `⚠` worth a look, `✗` broken, or `·` for a note.
Notes don't affect the exit code.

`--bundle` creates an archive to attach to a GitHub issue.
Credentials are redacted before anything is written, and `REDACTIONS.txt` in the archive lists what was removed.

## `isx clean`

Remove cached data, state, or configuration.

    isx clean <subcommand> [options]

| Subcommand | Description |
|------------|-------------|
| `cache` | Remove cached downloads, registry blobs, and build caches |
| `state` | Remove VM state, logs, and appliance artifacts; on macOS the VM's data disk (every instance, template and image) is kept unless you pass `--delete-instances`. Run `isx init` afterwards: the VM needs the appliance again before it boots on the kept disk |
| `config` | Remove configuration, SSH keys, and CA certificate |
| `pool` | Reclaim space from the storage pool (failed builds, unused images, DNF cache); `--base-images` also removes the downloaded base images, which the next build downloads again |
| `all` | Remove cache, state, and configuration (does not touch Incus templates or instances unless you pass `--delete-instances`, which on macOS also deletes the VM's data disk) |

All subcommands accept these options:

| Option | Description |
|--------|-------------|
| `--dry-run` | Show what would be deleted without deleting |
| `--skip-confirmation` | Skip the confirmation prompt |

## `isx reset`

Return isx to a freshly installed state.
Removes all instances and templates, cached images and downloads, the proxy service, the VM (macOS) and all configuration, including SSH keys and the CA certificate.
It shows what will be removed and asks first.
Run `isx init` afterwards to set up again.

    isx reset

| Option | Description |
|--------|-------------|
| `--skip-confirmation` | Skip the confirmation prompt |

## `isx vm`

Manage the incus-spawn VM appliance. macOS only.

    isx vm <subcommand>

| Subcommand | Description |
|------------|-------------|
| `start` | Start the VM (creates disk image on first run) |
| `stop` | Stop the VM (graceful shutdown). It asks the guest to shut down (Incus and its instances stop, the disks are synced) and waits up to 30 s for it. It warns when the guest did not and had to be stopped with signals, which loses writes of the last few minutes; that is every stop of an appliance from before the fix for [#881](https://github.com/Sanne/incus-spawn/issues/881), until `isx vm restart` applies a newer one; `--require-clean` also exits 4 then |
| `restart` | Stop and restart the VM (applies pending appliance updates) |
| `status` | Show VM status and system diagnostics |
| `resize` | Grow the VM data disk that backs the storage pool |
| `reset` | Wipe the VM data disk: deletes every instance, template and cached image |
| `console` | Follow VM serial console output |
| `check-version` | Say whether the running appliance matches the installed version; with `--quiet`, only when a restart would change it |

### `isx vm restart`

    isx vm restart [options]

Stops and restarts the VM, applying any pending appliance updates.
Running containers will be stopped.

| Option | Description |
|--------|-------------|
| `-y`, `--yes` | Skip the confirmation prompt |

### `isx vm resize`

    isx vm resize <size>

Size must be larger than the current disk (grow-only), e.g. `100G`.

| Option | Description |
|--------|-------------|
| `-y`, `--yes` | Skip the confirmation prompt |

### `isx vm reset`

    isx vm reset [options]

Wipes the VM's storage pool, for when it's beyond repair (for example, orphaned subvolumes reported by `isx doctor`).
Every instance, template and cached image is deleted; isx lists them and asks first, or needs `--yes` without a terminal.
Rebuild your templates afterwards with `isx build --all`.

| Option | Description |
|--------|-------------|
| `-y`, `--yes` | Skip the confirmation prompt |

## `isx ask`

AI-powered help.
Ask any question about incus-spawn (uses AI tokens).

    isx ask <question...>

| Option | Description |
|--------|-------------|
| `--with-templates` | Include template and tool definitions in the AI context |

## `isx mcp`

Serve isx to an agent on this machine over the Model Context Protocol (stdio).
Started by the agent, not by hand:

    claude mcp add isx -- isx mcp

See [Delegating from an agent on your host](MCP.md#delegating-from-an-agent-on-your-host-mcp) for what it offers and how to approve templates.

`isx mcp --caller-instance <name>` serves an instance branched with `--mcp-client` instead; the proxy runs it for each connection that instance makes to `https://mcp.isx.internal/mcp` ([a coordinator in an instance](MCP.md#a-coordinator-in-an-instance)).

## `isx completion`

Print a shell completion script.

    isx completion [<shell>]

Supported shells: `bash` (default), `zsh`, `fish`.

The scripts also complete `<namespace>=<account>` for `isx branch --account` and `isx account set`, and the namespaces for `isx account unset`.
They read account names from `isx account list --completion`, which prints names only, never credentials.

| Option | Description |
|--------|-------------|
| `--install` | Print installation instructions instead of the script |

## Scripting isx

Query commands take `--format=table|plain|json`: `isx list`, `isx templates [list]`, `isx tools list`, `isx tools show`, `isx account list`, `isx account show`, `isx proxy status`, `isx proxy activity`, `isx doctor`, `isx vm status` (macOS), `isx update-base --list` and `isx branch`.

- `table` is for people.
  It may change in any release, so don't parse it.
- `plain` prints one record per line, its fields tab-separated, with `-` for an empty field.
  There is no header, padding, colour or glyph, and no results means no output.
- Neither format lets a value write a control character to your terminal.
  A value can hold one: an instance stamp set by hand with `incus config set`, say, escape sequence and all.
  `plain` keeps each record on one line and safe to print, so every control character in a value becomes a space: the C0 controls U+0000 to U+001F (tab, line feed, carriage return, backspace, form feed and ESC among them), DEL, the 8-bit controls U+0080 to U+009F, U+2028/U+2029, and the bidirectional embeddings, overrides and isolates U+202A to U+202E and U+2066 to U+2069, which would make your terminal show the rest of the line out of order.
  That loses them, so read `json` when you need a value exactly: it keeps every value as it is, writing each of those characters as a JSON escape that any JSON parser turns back into the original: `\t`, `\n`, `\r`, `\b` and `\f` for tab, line feed, carriage return, backspace and form feed, and `\uXXXX` for every other one.
- `json` prints an array of objects, or one object for a command about one thing (`tools show`, `proxy status`, `vm status`, `branch`).
  A list value is an array in `json` and its elements joined by `,` in `plain`.
  An absent value is `null`, and sizes are in bytes.
  Times are ISO-8601 with their offset: `2026-10-05T12:34:56+02:00`, or `2026-10-05T12:34:56Z` on a host whose zone is UTC, so match both forms.
  Instances stamped by older isx releases recorded only a date, which prints as an ISO-8601 date (`2026-09-01`).
  A time isx cannot read is `null` (`-` in `plain`).
- When isx cannot read what Incus answered, the command fails (exit 1, the reason on stderr).
  It never prints an empty result.
- Both `plain` and `json` are stable.
  Fields may be added at the end, but are never renamed, removed or reordered.
- Results go to stdout.
  Errors and diagnostics go to stderr, so stdout holds only results.
  Commands that act (`build`, `branch`, `destroy`, ...) report their steps on stdout and their warnings and notes on stderr.
  A status command's report is its result in every state: `isx proxy status` prints it on stdout whether or not the proxy is healthy, and the exit code says which.
- isx writes colour, clickable links and animated progress only when stdout is a terminal.
  When stdout is redirected or piped, or `NO_COLOR` is set to a non-empty value, neither stdout nor stderr holds escape sequences. isx cannot tell whether stderr alone is a terminal, so `isx build tpl-minimal 2>build.log` run from a terminal still colours the warnings in `build.log`.
  For a clean log, redirect both streams or set `NO_COLOR=1`.

`isx list` prints `name`, `status` (`running`, `stopped`, ...), `ipv4`, `parent`, `runtime` (`container` or `virtual-machine`), `created`, `mcp_state` and `mcp_purpose`, in that order.
The last two are `null` (`-`) unless an [`isx mcp`](MCP.md#delegating-from-an-agent-on-your-host-mcp) session made the instance.
`mcp_state` is then `held` (a session holds it), `orphaned` (its session ended and nobody has adopted it yet) or `kept` (an agent handed it to you with `keep_instance`), and `mcp_purpose` is what the agent said the instance is for, or `null`.
For names alone, use `isx list -q`:

```shell
for name in $(isx list -q --status=stopped); do isx destroy "$name" --skip-confirmation; done
isx list --format=json | jq -r '.[] | select(.parent == "tpl-java") | .name'
```

The fields of the other commands, in order:

| Command | Fields |
|---------|--------|
| `isx templates` | `name`, `parent`, `source` (`built-in` or the file), `description`, `built`, `built_at`, `version_outdated` (built by another isx version), `definition_changed` (its definition or a tool it uses changed since), `parent_rebuilt` (its parent was built after it, and it was copied from the parent: never for a VM over a container parent, or the reverse, which is built from the definitions). The staleness flags are `null` for a template that is not built, and everything from `built` on is `null` when Incus could not be asked, with the reason on stderr. `isx build --out-of-sync` builds the templates that are not built, rebuilds those with `version_outdated`, `definition_changed` or `parent_rebuilt`, and rebuilds the descendants of every template it builds |
| `isx tools list` | `name`, `source`, `description` |
| `isx tools show <tool>` | `name`, `description`, `source`, `feature`, `requires`, `packages`, `parameters`, `actions` (labels), `downloads` (URLs), `proxy_domains` |
| `isx account list` | one record per account: `namespace`, `account`, `description`, `default`, `problem` (`incomplete`, or `not-configured` for an account only a pin names), `pinned_by`, `following` (the instances that follow the default). The last two are `null` when Incus could not be asked, with the reason on stderr; `plain` prints that as `-`, like an empty list, so read `json` to tell them apart |
| `isx account show <instance>` | one record per namespace: `namespace`, `account`, `description`, `chosen_by` (`default`, `template`, `explicit`, `copied` or `unknown`), `chosen_in` (the template or instance it came from), `template`, `template_account`, `problem`, `identity_pending` (the git identity the instance takes on its next start or shell, from its stamps: one missing from an instance whose template an older isx built is filled in then too, but not listed), `template_problem` (why the template's own choice cannot be used) |
| `isx proxy status` | `status` (`running`, `waiting_for_dns`, `not_running`, `stale_dns`, `stale_gateway`, or `unknown` when the state could not be checked), `version`, `git_sha`, `runtime`, `dns_overrides`, `drift` (one string), `auth_error`, `health_endpoint`, `mitm_port`, `service_installed`, `managed_by` (`systemd`, `launchd` or `manual`), `restart_helps` (whether restarting clears the drift; `null` without drift), `check_error` (why the state is `unknown`). Printed in every state; the exit code is the table's, so `unknown` exits 1 like `not_running`: read `status` to tell them apart |
| `isx proxy activity` | one record per instance the proxy knows, by name, then one whose `instance` is `null`: traffic from no known instance (the host, or an address the proxy has not listed). `instance`, `counting_since`, then all traffic, model calls included: `requests`, `in_flight` (an open WebSocket included), `last_activity_at`, `request_bytes`, `response_bytes` (body bytes, counted as each request ends); then model calls alone: `model_requests`, `model_in_flight`, `last_model_request_at`, `last_model_response_at`, `input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`, then `identified_by_proof` (its proof or secret alone named it, not its address) (`null` on the unattributed record); then, on the unattributed record alone and `null` on the others, the proxy's `refused_proofs` (checked and matched nothing), `refused_address_misses` (from an address that is no instance, never checked), `registry_stale` (its last instance listing failed, or the one under way has hung), `unplaced_running` (running instances with no traffic attributed) and `last_address_miss_at`. The table ends with the refused proofs and a failed listing when there are any, and the unplaced instances when there are address misses. Subtract two reads only when both carry the same `counting_since`. With `--by-domain`, one record per instance and domain: `instance`, `domain` (an intercepted domain, a subdomain counted under it, or `(other)`), `counting_since`, `requests`, `in_flight`, `last_activity_at`, `request_bytes`, `response_bytes`. Exits 1 when the proxy cannot be read |
| `isx doctor` | one record per check: `level` (`ok`, `note`, `warn`, `fail`), `label`, `detail`, `remediation`. `detail` and `remediation` are free text for people: key a script on `level` and `label`. Nothing is offered or applied; `--bundle` cannot be combined with it |
| `isx vm status` | `running`, `pid`, `rest_api`, `log`, `appliance`, `appliance_pending` (the installed appliance a restart would apply), `vsock_connections`, `incus_reachable`, `incus_error`, `vsock_connections_high` (the in-VM forwarder may be leaking streams; `isx vm restart` clears them). Exits 1 when Incus is unreachable |
| `isx update-base --list` | newest first: `tag`, `date`, `latest`, `current`, `pinned` (the current release is pinned, so builds stay on it), `listed` (`false` for the current base image when it is not among the releases fetched, e.g. older than all of them, unpublished or deleted: it then gets a record of its own, last, with no `date`. That alone says nothing about its age) |
| `isx branch <name>` | `name`. Progress goes to stderr and no shell is opened, so `name=$(isx branch my-box --from tpl-java --format=plain)` captures it |

Exit codes:

| Code | Meaning |
|------|---------|
| 0 | Success, including a listing with no results |
| 1 | The command failed, with the reason on stderr: a value it rejects (`--format=yaml`, `--status=frozen`), Incus unreachable, ... `isx doctor` also exits 1 when a check fails, `isx vm status` when Incus is unreachable, and `isx proxy status` when the proxy is not running (that report is on stdout) |
| 2 | The command line could not be parsed (an unknown option, an option missing its value, a stray argument), with the usage on stderr. For `isx proxy status` only, 2 also means the proxy is down but its DNS overrides are still active |
| 3 | `isx proxy status` only: the proxy runs on an old bridge address |
| 4 | `isx vm stop --require-clean` only: the VM is stopped, but with signals rather than a guest shutdown |
| 78 | `isx proxy start` cannot run the proxy at all, e.g. `isx-proxy` is not installed |
