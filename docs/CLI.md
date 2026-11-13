# CLI reference

Every isx command, with what `--help` does not say.
`isx <command> --help` lists all of a command's options.
[Scripting isx](#scripting-isx) covers the `plain` and `json` output formats, the fields each query command prints and the exit codes.

## `isx`

Launch the interactive TUI.
When the TUI cannot open, as when stdout is not a terminal, it prints the instance table instead.

```shell
isx
```

## `isx init`

Set up the host once: install Incus, configure credentials and their [accounts](CONFIGURATION.md#credential-accounts), and test connectivity.
Running it again is safe, and is how you add, rename or remove an account.
On a fresh Linux host it adds you to the `incus-admin` group, which other terminals get at your next login or with `newgrp incus-admin`.

```shell
isx init [--no-sudo] [--search-path=<dir>,...] [--code-dir=<dir>,...] [--fork-templates] [--service | --no-service] [--format=table|plain|json]
isx init --plan [--format=table|plain|json]
```

### Without a terminal

`isx init` can run with no terminal, as an agent runs it.
Each question that is not a secret has a flag that answers it, with or without a terminal:

| Option | What it answers |
|--------|-----------------|
| `--search-path=<dir>,...` | Adds these directories to the template search paths |
| `--code-dir=<dir>,...` | Adds these directories to the code directories |
| `--fork-templates` | Forks the community templates with `gh` if you have no copy, then clones and adds it |
| `--service`, `--no-service` | Whether to install the proxy as a service, and on macOS the VM too |

There is no flag, variable or piped input for a secret.
Adding a missing credential stays your step, which you do with the `isx account add` command.
Approving templates for agents stays your step too, which you do with `isx mcp setup`.
A question no flag answers is skipped when there is no terminal, and the step says how to answer it later.

With `--format=json` or `plain`, init ends with a summary on stdout and its progress on stderr.
The summary has a record per step, with the fields `isx init --plan` prints, their values read after the run.
A field `skipped` says that init skipped the step's question because there was no terminal.

The last field, `declined`, says the answer was no, given by you or by a flag such as `--no-service`.

The run then exits 76 when it finished but left a step only you can do, such as a credential, an approval or a step that needs root.
A run that waits on the root script, exit 75, prints the summary too; a run that fails exits 1 and does not print the summary.

`isx init --plan` says what `isx init` would change on this host, and does not change the host.
It does not ask a question, write a file or run sudo, and it asks Incus only to read.
On macOS it does not start the VM, so what Incus holds stays unknown while the VM is stopped.

On Linux, `isx init` itself starts with `sudo -v`, so running it needs sudo even when every step is done.

Some of what init checks only root can read, such as the btrfs sudoers rule.
Firewall rules are read without sudo where the host's polkit policy allows it or polkit is not installed, and never in a way that asks for a password.

The plan reports anything it could not read as `unknown`, saying what it skipped and why.
Its `check_commands` are the sudo commands that let you check what the plan skipped.

The plan lists every step init would take, in its order, with what it is for and its state:

| State | Meaning |
|-------|---------|
| `done` | Nothing left to change |
| `pending` | Init changes something here |
| `optional` | Init only offers a change, which you may decline |
| `unknown` | Not checked: only root can read it here, or the plan could not read it, such as when Incus does not answer |

Each step also says who must do what is left of it:

| Who | Meaning |
|-----|---------|
| `none` | No privilege, secret or approval: an agent may do it for you |
| `sudo` | A command run with sudo |
| `secret` | A credential you type at a terminal, with `isx account add` |
| `approval` | Your choice of the templates agents may create instances from |

With `--format=json` each step is an object with these fields, in order:

| Field | Meaning |
|-------|---------|
| `step` | The step's name, such as `firewall` or `proxy-service` |
| `title` | The step's header in `isx init` |
| `state` | `done`, `pending`, `optional` or `unknown` |
| `who` | `none`, `sudo`, `secret` or `approval`: the most any of its changes needs |
| `asks` | Whether init asks you a question at this step |
| `changes` | What the step would change, one entry each |
| `commands` | The commands those changes run |
| `sudo_commands` | The part of `commands` that runs with sudo |
| `files` | The files those changes write |
| `note` | Why a step is unknown, or what else to know, or `null` |
| `purpose` | What the step is for |
| `check_commands` | For what the plan did not check, the commands you run with sudo to check it |
| `sudo_script` | The step's lines of the `--no-sudo` script, run as root |

`plain` prints the same fields, one step per line, as the formats below describe.
The last field, `sudo_script`, holds the step's lines of the script `isx init --no-sudo` writes.

### Without sudo

`isx init --no-sudo` does not run any step as root, for you or for an agent driving the install.
It writes every step that needs root into one script, `~/.local/state/incus-spawn/init-privileged.sh`, with a comment saying why each is there.
It then exits 75 and names the script.

Read the script, run it as root with `sudo sh`, then run `isx init --no-sudo` again.
The second run checks that each step took effect, then does the rest of the setup as you.
A fresh host needs the script once, unless the second run moves the bridge off a VPN's subnet.

Some of what the script does only root can check afterwards, such as the btrfs sudoers rule.
The script records what it ran in `/var/lib/isx/init-applied`, which the second run reads for those steps.
`isx init --plan` reads that file too, so the plan reports that work as done.

Once nothing needs root any more, the second run removes the script.
When the second run itself finds root work, such as a redirect to move after it moved the bridge off a VPN's subnet, it writes a new script and exits 75 again.

On a host with `apt`, the script refreshes the package lists once, before its first install, as `isx init` with sudo does.
A step isx cannot write as a script, such as installing packages on a host without a package manager it knows, stops the run and names what to do yourself.

On a UFW host the script edits `/etc/ufw/before.rules` itself, keeps a copy, and puts the copy back if UFW refuses the new file.
If the blocks isx marks in that file are broken, the script stops and leaves the file as it was.
On macOS `isx init` needs no administrator, so `--no-sudo` has no effect there.

## `isx build`

Build or rebuild a template image.
While another isx builds or updates the same template, the build is refused.

```shell
isx build [<template>...] [options]
```

| Option | What it does |
|--------|--------------|
| `--all` | Rebuild all defined templates |
| `--out-of-sync` | Rebuild templates whose definition or isx version changed, or whose parent was rebuilt after them, with everything inheriting from them |
| `--with-parents` | Rebuild the templates and all their parents unconditionally |
| `--with-descendants` | Rebuild the templates and all templates inheriting from them |
| `--missing` | Build only templates that do not exist yet. As with several named templates, a failure skips what inherits from it, the rest still build, and the command exits 1 naming what it did not build |
| `--type <type>` | Instance type: `container`, `vm` or `kvm`, overriding the image definition. `vm` needs an Incus that runs VMs, which the macOS appliance cannot, and `kvm` passthrough is off on macOS |
| `--yes` | Skip interactive confirmations |
| `--skip-git-refresh` | Accepted for compatibility; builds do not refresh host checkouts |
| `--verbose` | Show every command the definitions ran, with its output, once each template's build is over, and how long each repository took to prepare |

Every build keeps the commands its definitions ran in `~/.local/state/incus-spawn/build-logs/<template>.log`, with all that each printed and its exit status.
These are each tool's `run`, `run_as_user` and `verify` commands and each repository's `prime`.
The log is written as each command ends, so `tail -f` follows a build, and a failure report names it.

A template's name may be at most 52 characters, since isx builds it as `<name>-rebuilding` first and swaps it in once built.
isx refuses a longer name before it builds anything, the template's parents included.

Several templates build in one invocation:

- With plain names, each builds in turn, a named parent before its child.
  Each gets any parent that is missing or out of sync, so a parent they share builds once.
- With `--with-parents` or `--with-descendants`, they build as one batch with one confirmation, parents before children and every template once.
- With an unknown name anywhere in the list, isx does not build any of them.

A target that fails does not stop the others: isx skips what inherits from it and builds the rest.
The build then exits 1 with one `Some templates failed to build:` line on stderr naming every template it left unbuilt, including a parent that failed to build for a target.

## `isx branch`

Create a new instance as a copy-on-write clone of a template or of an existing instance.

```shell
isx branch <name> [options]
```

| Option | What it does |
|--------|--------------|
| `--from <source>` | Source instance to branch from, detected from the current directory if omitted |
| `--gui` | Enable GUI passthrough: Wayland, GPU and audio. The default for a `gui: true` container template when isx runs in a Wayland session, except for a project-local template, one a cloned repository ships in `.incus-spawn/` |
| `--no-gui` | Disable GUI passthrough even if the template has `gui: true` |
| `--kvm` | Expose `/dev/kvm` for nested virtualization. Linux only: refused on macOS |
| `--no-kvm` | Disable KVM even if the template was built with `type: kvm` |
| `--airgap` | No network devices: every NIC, InfiniBand device and outbound `proxy` device is masked, and `pci`/`usb` passthrough is refused |
| `--proxy-only` | Restrict network to the host proxy only |
| `--inbox <dir>` | Host directory to mount read-only at `~/inbox` inside the instance |
| `--cpu <N>` | CPU core limit. Default: none for containers; for VMs the host's CPUs minus 2, at most 8, and on a hybrid host at most its top tier's physical cores |
| `--memory <size>` | Memory limit, such as `8GB`. Default: 60% of the host's RAM for a container; for a VM a quarter of it, between 4 GiB and 16 GiB |
| `--disk <size>` | Disk size limit (default: `100GB`, a ceiling on a copy-on-write pool, not an allocation) |
| `--no-start` | Do not start the instance after creation |
| `--shell` | Open a plain shell instead of running the default action |
| `--account <ns>=<account>` | Use this [credential account](CONFIGURATION.md#credential-accounts) instead of the template's or the default; repeatable |
| `--owner <name>` | Mark the instance as controlled by an external tool, a program outside isx that drives it, such as `orca`. A lowercase name; `mcp` is reserved. Copies of the instance never inherit the owner |
| `--owner-id <id>` | The owner's own handle for the instance, such as an orchestrator's workspace id. Needs `--owner` |
| `--mcp-client` | Let the instance drive isx over MCP at `https://mcp.isx.internal/mcp`, as a [coordinator in an instance](MCP.md#a-coordinator-in-an-instance). The default for a template whose `coordinator:` block says so: `isx-control-capability`, true when `control-over` lists templates. Copies of the instance never inherit this permission |
| `--no-mcp-client` | Do not make the instance a coordinator, even if the template makes coordinators by default |
| `--format=<format>` | `table` (the default), `plain` to print only the new name, or `json` to add how to reach it, with progress on stderr and no shell opened. See [Scripting isx](#isx-branch-fields) |

## `isx shell`

Open a shell in an existing instance.

```shell
isx shell <instance>
isx shell <instance> --session <name>
isx shell <instance> --no-attach
isx shell <instance> --task [<task-id>]
```

When the instance auto-attaches to zmx or tmux, every `isx shell` enters its session `isx`, so two terminals show the same screen.
`--session <name>` enters another session instead, and creates it when it is missing.
A name is 1 to 32 letters, digits, `-` or `_`, and does not start with `-`.
`--no-attach` opens a plain login shell outside any session.

With `--task`, it opens the conversation of a task an agent [delegated over MCP](MCP.md) instead of a shell.
The agent resumes the conversation in the directory it worked in, and leaving the conversation ends the session.

Without an id it joins the only task in the instance.
With several tasks, it lists them, each with the command that joins that task, and exits.

isx refuses a task in the middle of a turn until the turn ends.
Plain `isx shell` into an instance `isx mcp` made prints the `--task` command before the prompt.

Copying from an agent inside the instance needs an OSC 52-capable host terminal.
See the [FAQ](FAQ.md#why-doesnt-copied-from-an-agent-reach-my-host-clipboard).

With the `shell-status-bar` feature enabled, isx pins a two-line status bar at the bottom of the terminal.
It shows the instance name and template, then its IP address, its network mode unless that is full, and the names of its repositories.
Press F12 to open a quick-action menu with shortcuts from tool actions that set `shell_menu: true`.
Enable it with:

```yaml
# ~/.config/incus-spawn/config.yaml
features:
  - shell-status-bar
```

## `isx start`

Start an instance and make it ready for use, without opening a shell.

```shell
isx start <instance>
```

isx prepares the instance as `isx shell` would, so the instance receives its credential proofs.
An instance already running is checked and left running.
If another isx process is starting or stopping it, `isx start` waits for that first, for up to two minutes.

## `isx stop`

Stop a running instance.

```shell
isx stop <instance>
```

Stopping an instance that is already stopped succeeds, so a script can repeat it.
A frozen instance cannot shut down, so `isx stop` refuses it with an error.
If another isx process is starting or stopping it, `isx stop` waits for that first, for up to two minutes.

## `isx run`

Run the default action or a specific action on an instance.

```shell
isx run <instance> [options]
```

| Option | What it does |
|--------|--------------|
| `--action <ref>` | Action to run (`tool-name` or `tool-name:action-id`) |

It exits 1 when no action matches or the action fails.

## `isx destroy`

Destroy an instance, or every built template or every instance at once.

```shell
isx destroy <instance>
isx destroy --all-templates      # destroy all built templates (derived first)
isx destroy --all-instances      # destroy all instances
```

| Option | What it does |
|--------|--------------|
| `--all-templates` | Destroy all built templates (reverse order, derived first) |
| `--all-instances` | Destroy all instances |
| `--skip-confirmation` | Skip the confirmation prompt |
| `--owner <name>` | Destroy the instance on behalf of this external tool, and only if that tool owns it. `drivers:` in `config.yaml` must let the tool destroy its instances. Destroying an instance that is already gone succeeds |
| `--owner-id <id>` | Destroy it only if the owner's handle for it is this one. Needs `--owner` |

Destroying an instance an external tool owns without `--owner` asks first.
Without a terminal, a script must pass `--skip-confirmation`.

## `isx list`

List the instances isx created, without opening the TUI.

```shell
isx list [options]
```

| Option | What it does |
|--------|--------------|
| `--format=<format>` | `table` (the default, for people), `plain` or `json` (for scripts) |
| `--plain` | Same as `--format=plain`. There is no header line, so a script that skips one loses its first record |
| `-q`, `--quiet` | Print instance names only, one per line |
| `--status=<status>` | Only instances that are `running` or `stopped` |

It does not list templates: `isx templates` lists those.
On macOS it starts the VM if the VM is stopped, except with `-q`, which shell completion runs.
See [Scripting isx](#scripting-isx) for the `plain` and `json` formats and the fields.

## `isx update-base`

Check for base image updates and install them.

```shell
isx update-base [<release-tag>] [options]
```

| Option | What it does |
|--------|--------------|
| `--list` | List available versions |
| `--latest` | Track the latest version (remove any pin) |
| `--format=<format>` | With `--list` only: `table` (the default), `plain` or `json` |

Pass a release tag, such as `fedora-45-20261111`, to pin to that version.
The pin is written to `~/.config/incus-spawn/base-image-pin.yaml` and changes only the base image `tpl-minimal` downloads.
For a tag of another Fedora release, such as `fedora-44-...` on an isx that ships Fedora 45, the pin uses that release's own image name and file names.

`--latest` follows the newest release of the built-in's line, never another Fedora or a channel.

## `isx update-all`

Update system packages, npm globals and git-fetched repos in all templates.
It does not re-clone repos or reinstall tools: use `isx build` for that.
Each template is updated in a copy that replaces it only once every step succeeded.
If a step fails, the command exits non-zero and that template stays as it was.

```shell
isx update-all [options]
```

| Option | What it does |
|--------|--------------|
| `--prime` | Also re-run prime commands, such as `mvn install -DskipTests`, for repos that define one |

## `isx templates`

Manage template definitions.
Bare `isx templates` runs `list`.

```shell
isx templates <subcommand>
```

| Subcommand | What it does |
|------------|--------------|
| `list` | List available template names |
| `new` | Create a new template definition |
| `edit` | Edit a template definition in `$EDITOR` |

### `isx templates list`

List the available templates, each with its description.

```shell
isx templates list [options]
```

| Option | What it does |
|--------|--------------|
| `-v`, `--verbose` | Also show where each definition comes from |
| `--format=<format>` | `table` (the default), `plain` or `json`; bare `isx templates` takes it too |

### `isx templates new`

Create a new template definition.
The name is used exactly as given, and the file is named after it: `isx templates new my-app` writes `my-app.yaml`.
isx refuses a name whose file is the one an override of a built-in template uses, such as `dev`.
Without a name, it writes `new-template.yaml` for you to name in the editor.

```shell
isx templates new [<name>]
isx templates new <name> --from <file>
```

With `--from`, it takes the whole definition from a file, or from stdin for `-`, and does not open an editor.
It checks the definition as the editor does, then stores the definition only when it has no error.
The definition's `name:` must be the name you give.
It never prompts, so a script or an agent can run it.

| Option | What it does |
|--------|--------------|
| `--project` | Create it in the project-local directory (`.incus-spawn/images/`) |
| `--from <file>` | Take the whole definition from this file, or `-` for stdin, check it and store it |
| `--replace` | With `--from`, replace the template's own file when it has one; isx never replaces a built-in template or a template defined in another file |
| `--format=<format>` | With `--from`, `table` (the default), `plain` or `json`; see [Template check fields](#template-check-fields) |

With an invalid definition, isx does not write a file and exits 1.
The check also lists what the template reaches beyond its instance, so you can review a template an agent wrote before you build it.

### `isx templates validate`

Check a template definition without storing or building it.
It takes a file, `-` for stdin, or the name of a template you have.
It runs the same check as `isx templates new --from` and exits 1 when the definition has an error.

A credential the template's tools need that is not configured is a warning on `tools`, naming the command that configures it.

```shell
isx templates validate <file-or-name>
```

| Option | What it does |
|--------|--------------|
| `--format=<format>` | `table` (the default), `plain` or `json`; see [Template check fields](#template-check-fields) |

### `isx templates edit`

Edit a template definition in `$EDITOR`.
It takes the template's exact name, as `isx build` and `isx branch` do.
Editing a built-in template creates a user-level override of it.

```shell
isx templates edit <name>
```

## `isx tools`

List and inspect the available tool definitions.
Bare `isx tools` runs `list`.

```shell
isx tools <subcommand>
```

| Subcommand | What it does |
|------------|--------------|
| `list` | List available tools |
| `show` | Show details of a tool definition |

### `isx tools list`

List the available tools.

```shell
isx tools list [options]
```

| Option | What it does |
|--------|--------------|
| `-v`, `--verbose` | Show source and description |
| `--format=<format>` | `table` (the default), `plain` or `json`; bare `isx tools` and `isx tools show` take it too |

### `isx tools show`

Show the details of one tool definition.

```shell
isx tools show <name>
```

## `isx account`

Add credentials, and show or change which [credential account](CONFIGURATION.md#credential-accounts) an instance uses.
Bare `isx account` runs `list`.

```shell
isx account <subcommand>
```

| Subcommand | What it does |
|------------|--------------|
| `list` | Configured accounts per credential, the default, and the instances pinned to each |
| `add [<credential>]` | Add or replace one credential with the step `isx init` runs for it |
| `show <instance>` | The account the instance uses for each credential, and where it comes from |
| `set <instance> <ns>=<account>...` | Pin the instance to these accounts; takes effect on its next request |
| `unset <instance> <ns>...` | Remove the pins, so the instance follows the default again |
| `reset-proofs <instance>` | Revoke shared proofs and deliver replacements, waiting for the proxy to acknowledge. Afterwards, restart tools and nested services yourself so they use the new tokens |

`isx account add` runs that one step and nothing else: no sudo, no other step.
Name the credential by namespace (`github`, `claude`, `openai`) or by tool (`gh`, `codex`); without a name, choose from init's credential menu.

Adding a second account goes through the same account menu.
The proxy applies the change automatically.

## `isx project`

Manage project templates defined by an `incus-spawn.yaml` file.

```shell
isx project <subcommand>
```

| Subcommand | What it does |
|------------|--------------|
| `create` | Create a project template from a parent base image |
| `update` | Update an existing project template (packages, repos, deps) |

### `isx project create`

Create a project template from a parent base image.

```shell
isx project create <name> [options]
```

| Option | What it does |
|--------|--------------|
| `--config <path>` | Path to `incus-spawn.yaml` (default: detected from the current directory) |

A project name may be at most 52 characters, since isx builds it as `<name>-rebuilding` first.
Re-creating an existing template builds the new one alongside it and replaces it only once the build succeeds.
If a repository fails to clone or the pre-build fails, the command exits non-zero, deletes the incomplete build and keeps the previous template.

### `isx project update`

Update an existing project template: its packages, repos and dependencies.

```shell
isx project update <name> [options]
```

| Option | What it does |
|--------|--------------|
| `--config <path>` | Path to `incus-spawn.yaml` (default: detected from the current directory; a detected file must name this template) |

The update runs in a copy that replaces the template only once every step succeeded.
If any step fails (system update, npm update, git fetch, pre-build), the command exits non-zero and keeps the template as it was.

## `isx proxy`

Manage the MITM authentication proxy.

```shell
isx proxy <subcommand>
```

| Subcommand | What it does |
|------------|--------------|
| `start` | Start the proxy |
| `stop` | Stop the proxy (handles both systemd and manual processes) |
| `restart` | Restart the proxy service |
| `status` | Check if the proxy is running |
| `activity` | What each instance sent through the proxy: requests, in flight, bytes, model calls and tokens. `--by-domain` gives one row per domain, `--format=plain\|json` serves scripts |
| `install` | Install as a systemd user service (auto-starts on boot) |
| `uninstall` | Stop and remove the systemd proxy service |
| `logs` | Follow the proxy log in real time |
| `dump` | Run a pass-through proxy for host-side traffic capture |

### `isx proxy start`

Start the proxy.

```shell
isx proxy start [options]
```

| Option | What it does |
|--------|--------------|
| `--port <port>` | MITM TLS proxy port (default: `18443`) |
| `--health-port <port>` | Health check HTTP port (default: `18080`) |
| `--gateway-ip <ip>` | The bridge address, where instances reach the proxy, for when Incus cannot report it |
| `--debug` | Log full request/response details |

On Linux, `--gateway-ip` must be the bridge's own address, which is where every `isx` command looks for the proxy.

When the bridge can be read, the value must be exactly the bridge's gateway address.
When the bridge cannot be read, the value must be a private (RFC 1918) or loopback address that matches the gateway `isx init` recorded, if init recorded one.

On macOS, the value must be the host's VM-facing bridge address.

### `isx proxy dump`

Run a pass-through proxy for host-side traffic capture.

```shell
isx proxy dump [options]
```

| Option | What it does |
|--------|--------------|
| `--port <port>` | Local HTTP port (default: `19080`) |

## `isx doctor`

Diagnose host, proxy, VM and tunnel health, and offer to fix the problems found.

```shell
isx doctor [options]
```

| Option | What it does |
|--------|--------------|
| `--deep` | Run per-instance checks (DNS, TLS, resolv.conf) |
| `--bundle` | Collect findings and logs into a support archive (.tar.gz); implies `--deep` |
| `--explain` | Ask your AI Help account for a first diagnosis of the material `--bundle` collects (uses AI tokens); implies `--deep` |
| `--format=<format>` | `table` (the default), `plain` or `json`; not with `--bundle` or `--explain` |

Each finding carries a mark:

| Mark | Meaning |
|------|---------|
| `✓` | Healthy |
| `⚠` | A warning, which you should check |
| `✗` | Broken |
| `·` | A note, which does not affect the exit code |

A fix in a base image reaches only instances built after it.
So doctor notes the templates on an older base image than a rebuild would use, and the instances branched from them.
That is a note, not an issue to address, unless the older release lacks a known fix a rebuild would bring, such as the network watchdog one.

`--bundle` creates an archive to attach to a GitHub issue.
isx redacts credentials before it writes anything, and `REDACTIONS.txt` in the archive lists what it removed.

`--explain` sends the findings and the archive's redacted files to the account `isx ask` uses, which is billed for it.
The answer lists the likely causes, the command to run next and the repository a fix belongs in.
isx prints the size of what it sends before sending, and asks first when the size is over 100 KB.

The answer is a suggestion, so check it before acting on it.

## `isx clean`

Remove cached data, state or configuration.

```shell
isx clean <subcommand> [options]
```

| Subcommand | What it does |
|------------|--------------|
| `cache` | Remove cached downloads, registry blobs and build caches, and the DNF cache volume in the storage pool |
| `state` | Remove VM state, logs and appliance artifacts |
| `config` | Remove configuration, SSH keys and the CA certificate |
| `pool` | Reclaim space from the storage pool: failed builds, unused images, DNF cache |
| `dnf` | Remove only the DNF cache volume builds share; the next build downloads its packages again |
| `all` | Remove cache, state and configuration, and on Linux the DNF cache volume |

All subcommands accept these options:

| Option | What it does |
|--------|--------------|
| `--dry-run` | Show what would be deleted without deleting |
| `--skip-confirmation` | Skip the confirmation prompt |

On macOS, `isx clean state` keeps the VM's data disk, with every instance, template and image, unless you pass `--delete-instances`.
It refuses to run while the VM runs: stop the VM first with `isx vm stop`.
Run `isx init` afterwards: the VM needs the appliance again before it boots on the kept disk.

`isx clean pool --base-images` also removes the downloaded base images, which the next build downloads again.

`isx clean all` does not delete any Incus template or instance unless you pass `--delete-instances`, which on macOS also deletes the VM's data disk.
Like `isx clean state`, it refuses to run while the VM runs.

## `isx reset`

Return isx to a freshly installed state.
It removes all instances and templates, cached images and downloads, the proxy service, the VM on macOS, and all configuration, including SSH keys and the CA certificate.
It shows what it will remove and asks first.
Run `isx init` afterwards to set up again.

```shell
isx reset
```

| Option | What it does |
|--------|--------------|
| `--skip-confirmation` | Skip the confirmation prompt |

## `isx vm`

Manage the VM that runs Incus on macOS; the command exists only there.

```shell
isx vm <subcommand>
```

| Subcommand | What it does |
|------------|--------------|
| `start` | Start the VM (creates the disk image on first run) |
| `stop` | Stop the VM with a graceful shutdown |
| `restart` | Stop and restart the VM (keeps instances, stops running containers, applies pending appliance updates) |
| `status` | Show VM status and system diagnostics |
| `resize` | Grow the VM data disk that backs the storage pool |
| `reset` | Wipe the VM data disk: deletes every instance, template and cached image |
| `console` | Follow VM serial console output |
| `check-version` | Say whether the running appliance matches the installed version; with `--quiet`, only when a restart would change it |

### When the VM stops answering

When isx cannot reach the VM, run `isx doctor` first.
It starts a stopped VM, and clears leaked connections in the VM's forwarder without a reboot, so running containers keep running.
It offers to restart the VM only when neither a stopped VM nor leaked connections are the cause.

A restart, by doctor or with `isx vm restart`, keeps your instances and templates.
It stops running containers, and applies any pending appliance update.
If the VM still does not answer, `isx vm console` follows its serial console, and `isx doctor --bundle` collects a support archive to attach to an issue.

**Last resort: `isx vm reset` deletes every instance, template and cached image in the VM, with any work stored only inside them.**
It asks first.
Rebuild your templates afterwards with `isx build --all`.

### `isx vm stop`

Ask the guest to shut down, and wait up to 30 s for it.
The guest stops Incus and its instances and syncs the disks.

isx warns when the guest did not shut down and had to be stopped with signals, which loses the writes of the last few minutes.
That happens at every stop of an appliance from before the fix for [#881](https://github.com/Sanne/incus-spawn/issues/881), until `isx vm restart` applies a newer appliance.

With `--require-clean`, such a stop also exits 4, and a VM isx could not stop exits 1.

### `isx vm restart`

Stop and restart the VM, applying any pending appliance updates.
It keeps instances and templates, but stops running containers.
When the VM is not running, it says so and does not start the VM: use `isx vm start` for that.

```shell
isx vm restart [options]
```

| Option | What it does |
|--------|--------------|
| `-y`, `--yes` | Skip the confirmation prompt |

### `isx vm resize`

Grow the VM data disk that backs the storage pool.

```shell
isx vm resize <size>
```

| Option | What it does |
|--------|--------------|
| `-y`, `--yes` | Skip the confirmation prompt |

The size, such as `100G`, must be larger than the current disk: the disk only grows.

### `isx vm reset`

Wipe the VM's storage pool, for when the pool cannot be repaired, as with orphaned subvolumes reported by `isx doctor`.

```shell
isx vm reset [options]
```

| Option | What it does |
|--------|--------------|
| `-y`, `--yes` | Skip the confirmation prompt |

It deletes every instance, template and cached image.
isx starts a stopped VM to list them and asks before it deletes them, and without a terminal it needs `--yes`.
Rebuild your templates afterwards with `isx build --all`.

## `isx ask`

Ask the AI any question about isx; it uses AI tokens.
It needs an Anthropic API key, Vertex AI or an OpenAI key, since a Claude subscription token only serves Claude Code.
Claude Max and Team plans include [monthly API credits](https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers) that pay for such a key.
`isx init` offers to add such a key beside a subscription account.

```shell
isx ask <question...>
```

| Option | What it does |
|--------|--------------|
| `--with-templates` | Include template and tool definitions in the AI context |

An answer is AI-generated and can be wrong, so isx asks the AI to end the answer with the documentation sections the answer relies on: check the answer against them.
A note on stderr says so after every answer, and stdout holds only the answer.

## `isx mcp`

Serve isx to an agent on this host over the Model Context Protocol, on stdio.
The agent starts it, not you, once `isx mcp register` registered it:

```shell
isx mcp register claude
```

See [Delegating from an agent on your host](MCP.md#delegating-from-an-agent-on-your-host-mcp) for what it offers and how to approve templates.

`isx mcp --caller-instance <name>` serves an instance branched with `--mcp-client` instead.
The proxy runs this command for each connection that instance makes to `https://mcp.isx.internal/mcp`, as [a coordinator in an instance](MCP.md#a-coordinator-in-an-instance).

### `isx mcp setup`

Run the "Agent Access over MCP" step of `isx init` alone: approve the templates agents may use, and register `isx mcp` with Claude Code when it is installed.
It asks the same questions and writes the same `mcp.yaml`.

```shell
isx mcp setup
```

It needs a terminal, and takes no option naming a template.
Without a terminal it does not change `mcp.yaml` and exits 1, since approving templates is a step for you, not for an agent.

### `isx mcp register`

Register `isx mcp` with Claude Code, Codex or opencode, through that agent's own `mcp add` command.

```shell
isx mcp register claude
isx mcp register codex
isx mcp register opencode
```

It replaces an existing `isx` entry, and exits 1 when the agent is not installed or its command fails.

On Linux the entry runs the server through `sg incus-admin` when the host has `sg` and you are in that group.
An agent started before you joined the group then still reaches Incus.
Without `sg` the entry is plain, and the agent reaches Incus once you start it again from a new login.

### `isx mcp config`

Print the entry that starts `isx mcp`, as the `mcpServers` JSON most MCP clients take in their settings.

```shell
isx mcp config
```

It names the installed `isx` by its full path and does not change any file.
On Linux its command is `sg` with `incus-admin`, as `isx mcp register` writes it, when the host has `sg` and you are in that group.
Paste it into the settings of an agent other than Claude Code, Codex and opencode, such as Cursor or Gemini CLI.

## `isx completion`

Print a shell completion script.

```shell
isx completion [<shell>]
```

| Option | What it does |
|--------|--------------|
| `--install` | Print installation instructions instead of the script |

Supported shells: `bash` (the default), `zsh`, `fish` and `pwsh`.

Every script asks isx itself on every TAB, so it offers only the commands this platform has.
It completes instance names, templates, tools, accounts and option values such as `--format`.
Add `isx completion pwsh | Out-String | Invoke-Expression` to your `$PROFILE` to load the PowerShell one.

The scripts also complete `<namespace>=<account>` for `isx branch --account` and `isx account set`, and the namespaces for `isx account unset`.
For `isx account add` they offer each credential by namespace and by tool, such as `github` and `gh`.
They offer names only, never credentials.
The zsh, fish and PowerShell scripts mark each namespace's default account; bash does not show descriptions.

## Scripting isx

Query commands print `plain` and `json` output that a script can rely on, and exit codes that say what happened.

Query commands take `--format=table|plain|json`: `isx init --plan`, `isx list`, `isx templates [list]`, `isx templates validate`, `isx tools list`, `isx tools show`, `isx account list`, `isx account show`, `isx proxy status`, `isx proxy activity`, `isx doctor`, `isx vm status` (macOS), `isx update-base --list` and `isx branch`.

### Formats

- `table` is for people.
  It may change in any release, so do not parse it.
- `plain` prints one record per line, its fields tab-separated, with `-` for an empty field.
  There is no header, padding, colour or glyph, and no results means no output.
- `json` prints an array of objects, or one object for a command about one thing (`tools show`, `proxy status`, `vm status`, `branch`).
- Both `plain` and `json` are stable.
  Fields may be added at the end, but are never renamed, removed or reordered.

### Values

- A list value is an array in `json`, and its elements joined by `,` in `plain`.
- An absent value is `null` in `json` and `-` in `plain`.
- Sizes are in bytes.
- Times are ISO-8601 with their offset: `2026-10-05T12:34:56+02:00`, or `2026-10-05T12:34:56Z` on a host whose zone is UTC, so match both forms.
- An instance stamped by an older isx release may carry only a date, which prints as an ISO-8601 date (`2026-09-01`).
  A time isx cannot read is `null`.

### Control characters

Neither format lets a value write a control character to your terminal.
A value can hold one: an instance stamp set by hand with `incus config set` can carry an escape sequence.

- `plain` keeps each record on one line and safe to print, so every control character in a value becomes a space.
  The original characters are then lost.
- `json` keeps every value unchanged, writing each control character as a JSON escape that any JSON parser turns back into the original.
  Read `json` when you need a value exactly.

The characters `plain` replaces and `json` escapes:

- the C0 controls U+0000 to U+001F, tab, line feed, carriage return, backspace, form feed and ESC among them;
- DEL;
- the 8-bit controls U+0080 to U+009F;
- U+2028 and U+2029;
- the bidirectional embeddings, overrides and isolates U+202A to U+202E and U+2066 to U+2069, which would make your terminal show the rest of the line out of order.

`json` writes `\t`, `\n`, `\r`, `\b` and `\f` for tab, line feed, carriage return, backspace and form feed, and `\uXXXX` for every other one.

### Output streams

- Results go to stdout.
  Errors and diagnostics go to stderr, so stdout holds only results.
- Commands that act (`build`, `branch`, `destroy`, ...) report their steps on stdout and their warnings and notes on stderr.
- A status command's report is its result in every state: `isx proxy status` prints it on stdout whether or not the proxy is healthy, and the exit code says which.
- When isx cannot read what Incus answered, the command fails with exit 1 and the reason on stderr.
  It never prints an empty result.

isx writes colour, clickable links and animated progress only when stdout is a terminal.
When stdout is redirected or piped, or `NO_COLOR` is set to a non-empty value, neither stdout nor stderr holds escape sequences.

isx cannot tell whether stderr alone is a terminal, so `isx build tpl-minimal 2>build.log` run from a terminal still colours the warnings in `build.log`.
For a clean log, redirect both streams or set `NO_COLOR=1`.

### Fields of `isx list`

`isx list` prints `name`, `status` (`running`, `stopped`, ...), `ipv4`, `parent`, `runtime` (`container` or `virtual-machine`), `created`, `mcp_state`, `mcp_purpose`, `base_image`, `owner`, `owner_id` and `mcp_client`, in that order.
`mcp_state` and `mcp_purpose` are `null` (`-`) unless an [`isx mcp`](MCP.md#delegating-from-an-agent-on-your-host-mcp) session made the instance.
`mcp_purpose` is then what the agent said the instance is for, or `null`, and `mcp_state` is one of:

| `mcp_state` | Meaning |
|-------------|---------|
| `held` | A session holds it |
| `orphaned` | Its session ended and nobody has adopted it yet |
| `kept` | An agent handed it to you with `keep_instance` |

`base_image` is the base image release the instance was built from, such as `fedora-44-20261001`.
It is `null` when Incus recorded none, as for an instance built from an image of your own.
A fix in a base image reaches only instances built after it, and `isx doctor` names the ones a rebuild would move to a newer release.

`owner` and `owner_id` are the external tool that controls the instance and its handle for it, as `isx branch --owner` and `--owner-id` stamped them, or `null`.
The table shows the owner in its `OWNER` column, beside how an `isx mcp` session holds its instances.

`mcp_client` is `true` for a [coordinator](MCP.md#a-coordinator-in-an-instance), an instance that may drive isx over MCP.
What it may control depends on its template's definition, which `isx list` does not read; the TUI's instance details show it.

For names alone, use `isx list -q`:

```shell
for name in $(isx list -q --status=stopped); do isx destroy "$name" --skip-confirmation; done
isx list --format=json | jq -r '.[] | select(.parent == "tpl-java") | .name'
```

### Fields of the other commands

The fields of each command, in order:

| Command | Fields |
|---------|--------|
| `isx init --plan` | one record per step: `step`, `title`, `state`, `who`, `asks`, `changes`, `commands`, `sudo_commands`, `files`, `note`, `purpose`, `check_commands`, `sudo_script` ([`isx init`](#isx-init) says what they mean) |
| `isx templates` | `name`, `parent`, `source`, `description`, `built`, `built_at`, `version_outdated`, `definition_changed`, `parent_rebuilt` |
| `isx tools list` | `name`, `source`, `description` |
| `isx tools show <tool>` | `name`, `description`, `source`, `feature`, `requires`, `packages`, `parameters`, `actions` (labels), `downloads` (URLs), `proxy_domains` |
| `isx account list` | one record per account: `namespace`, `account`, `description`, `default`, `problem`, `pinned_by`, `following` |
| `isx account show <instance>` | one record per namespace: `namespace`, `account`, `description`, `chosen_by`, `chosen_in`, `template`, `template_account`, `problem`, `identity_pending`, `template_problem` |
| `isx proxy status` | `status`, `version`, `git_sha`, `runtime`, `dns_overrides`, `drift`, `auth_error`, `health_endpoint`, `mitm_port`, `service_installed`, `managed_by`, `restart_helps`, `check_error`, `pid`, `pid_owner`, `foreign_holder`, `display_version` |
| `isx proxy activity` | one record per instance: `instance`, `counting_since`, `requests`, `in_flight`, `last_activity_at`, `request_bytes`, `response_bytes`, `model_requests`, `model_in_flight`, `last_model_request_at`, `last_model_response_at`, `input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`, `identified_by_proof`, `refused_proofs`, `refused_address_misses`, `registry_stale`, `unplaced_running`, `last_address_miss_at` |
| `isx doctor` | one record per check: `level`, `label`, `detail`, `remediation` |
| `isx vm status` | `running`, `pid`, `rest_api`, `log`, `appliance`, `appliance_pending`, `vsock_connections`, `incus_reachable`, `incus_error`, `vsock_connections_high` |
| `isx update-base --list` | newest first: `tag`, `date`, `latest`, `current`, `pinned`, `listed` |
| `isx branch <name>` | `name` |
| `isx templates validate`, `isx templates new --from` | `name`, `path`, `valid`, `written`, `findings`, `reach` |

The sections below explain the fields that need an explanation.

#### `isx templates` fields

| Field | Meaning |
|-------|---------|
| `source` | `built-in` or the file |
| `version_outdated` | Built by another isx version |
| `definition_changed` | Its definition or a tool it uses changed since it was built |
| `parent_rebuilt` | Its parent was built after it, and it was copied from the parent |

`parent_rebuilt` is never set for a VM whose parent is a container, or the reverse, because isx builds such a template from the definitions and does not copy the parent.
`version_outdated`, `definition_changed` and `parent_rebuilt` are `null` for a template that is not built.
Everything from `built` on is `null` when Incus could not be asked, with the reason on stderr.

`isx build --out-of-sync` builds the templates that are not built, rebuilds those with `version_outdated`, `definition_changed` or `parent_rebuilt`, and rebuilds the descendants of every template it builds.

#### Template check fields

`isx templates validate` and `isx templates new --from` print one object in `json`.

| Field | Meaning |
|-------|---------|
| `name` | The name the definition gives, or `null` when it gives none |
| `path` | The file checked, or the file `--from` writes |
| `valid` | Whether the definition has no error |
| `written` | Whether `--from` stored it; always `false` for `validate` |
| `findings` | Each with `severity` (`error` or `warning`), `field` and `message` |
| `reach` | What it reaches beyond its instance, each with `kind`, `field` and `detail` |

A finding's `field` is the key at fault, such as `parent`, `repos[1].depth` or `coordinator.control-over`.
It is `null` when the file does not parse at all.
A `reach` entry's `kind` is `host-image` (a `file://` base image), `host-resource`, `host-source`, `gui`, `account` or `coordinator`.
Only the definition's own fields count, not its parent's.

`plain` prints one line per finding, `<severity> <field> <message>`, then one per reach, `reach <field> <detail>`, tab-separated.

#### `isx account list` fields

| Field | Meaning |
|-------|---------|
| `problem` | `incomplete`, or `not-configured` for an account only a pin names |
| `pinned_by` | The instances pinned to the account |
| `following` | The instances that follow the default |

`pinned_by` and `following` are `null` when Incus could not be asked, with the reason on stderr.
`plain` prints that as `-`, like an empty list, so read `json` to tell them apart.

#### `isx account show` fields

| Field | Meaning |
|-------|---------|
| `chosen_by` | `default`, `template`, `explicit`, `copied` or `unknown` |
| `chosen_in` | The template or instance it came from |
| `identity_pending` | The git identity the instance takes on its next start or shell, from its stamps |
| `template_problem` | Why the template's own choice cannot be used |

On that next start or shell, isx also fills in an identity missing from an instance whose template an older isx built, but `identity_pending` does not list that identity.

#### `isx proxy status` fields

| Field | Meaning |
|-------|---------|
| `status` | `running`, `waiting_for_dns`, `not_running`, `stale_dns`, `stale_gateway`, or `unknown` when the state could not be checked |
| `drift` | One string |
| `managed_by` | `systemd`, `launchd` or `manual` |
| `restart_helps` | Whether restarting clears the drift; `null` without drift |
| `check_error` | Why the state is `unknown` |
| `pid`, `pid_owner` | The process of the proxy that answered, and its owner; `null` when it did not say |
| `foreign_holder` | Whether that proxy is not the installed service's process, while `managed_by` still names the service; `null` with no service |
| `display_version` | The running proxy's version as people read it, such as `0.5.0-dev.2+3` for a snapshot, where `version` is the version builds are compared by |

The record is printed in every state, and the exit code follows the [exit codes](#exit-codes) table.
So `unknown` exits 1 like `not_running`: read `status` to tell them apart.

#### `isx proxy activity` fields

One record per instance the proxy knows, by name, then one whose `instance` is `null`: traffic from no known instance, which is the host or an address the proxy has not listed.

| Field | Meaning |
|-------|---------|
| `counting_since` | Subtract two reads only when both carry the same value |
| `requests`, `in_flight`, `last_activity_at`, `request_bytes`, `response_bytes` | All traffic, model calls included. `in_flight` counts an open WebSocket too; the byte fields are body bytes, counted as each request ends |
| `model_requests`, `model_in_flight`, `last_model_request_at`, `last_model_response_at`, `input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens` | Model calls alone |
| `identified_by_proof` | The instance's proof or secret alone named the instance, not its address; `null` on the unattributed record |
| `refused_proofs` | Proofs the proxy checked that did not match any proof isx gave an instance |
| `refused_address_misses` | Requests from an address that no instance has, whose proofs the proxy never checked |
| `registry_stale` | The proxy's last instance listing failed, or the one under way has hung |
| `unplaced_running` | Running instances with no traffic attributed |
| `last_address_miss_at` | The time of the last address miss |

The last five fields describe the proxy, not one instance: they are set on the unattributed record alone, and are `null` on the others.
The table ends with the refused proofs and a failed listing when there are any, and the unplaced instances when there are address misses.

With `--by-domain`, one record per instance and domain: `instance`, `domain`, `counting_since`, `requests`, `in_flight`, `last_activity_at`, `request_bytes`, `response_bytes`.
`domain` is an intercepted domain, a subdomain counted under it, or `(other)`.

It exits 1 when the proxy cannot be read.

#### `isx doctor` fields

`level` is `ok`, `note`, `warn` or `fail`.
`detail` and `remediation` are free text for people: key a script on `level` and `label`.
In a script format, isx does not offer or apply any fix, and `--bundle` cannot be combined with it.

#### `isx vm status` fields

| Field | Meaning |
|-------|---------|
| `appliance_pending` | The installed appliance a restart would apply |
| `vsock_connections_high` | The in-VM forwarder may be leaking streams; `isx doctor` clears them without a reboot |

It exits 1 when Incus is unreachable.

#### `isx update-base --list` fields

| Field | Meaning |
|-------|---------|
| `latest` | The release tracking the latest follows: the newest of the built-in's line |
| `pinned` | The current release is pinned, so builds stay on it |
| `listed` | `false` for the current base image when it is not among the releases fetched |

The current base image is unlisted when it is older than every release fetched, unpublished or deleted.
It then gets a record of its own, last, with no `date`.
The `listed` field alone does not tell you the image's age.

#### `isx branch` fields

`isx branch <name> --format=plain` prints `name` alone.
Progress goes to stderr and no shell is opened, so `name=$(isx branch my-box --from tpl-java --format=plain)` captures the name.
`plain` never gains a field, so that this capture keeps working.

`--format=json` adds the fields a program needs to reach the instance:

| Field | Meaning |
|-------|---------|
| `name` | The new instance |
| `owner` | The `--owner` given, or `null` |
| `owner_id` | The `--owner-id` given, or `null` |
| `ssh_host` | The host to give `ssh`, or `null` when the template has no SSH server |
| `ssh_user` | The user to log in as |
| `ssh_port` | The port, always 22 |
| `ssh_identity_file` | The private key isx manages for its instances |
| `ssh_proxy_command` | The `ProxyCommand` that reaches the instance, starting it first if it is stopped |
| `project_root` | The directory a shell opens in |
| `mcp_client` | Whether the instance is a coordinator that may drive isx over MCP |
| `mcp_scope` | The templates such a coordinator may control, or `null` |

The SSH fields are `null` when the template does not install `sshd`, or when isx cannot create its SSH key.
isx creates the key before printing its path, and authorizes the key in the instance when the instance starts, so a `--no-start` branch is reachable once started.

A plain `ssh <name>` uses the instance's own Host block, which never starts a stopped instance.

### Exit codes

| Code | Meaning |
|------|---------|
| 0 | Success, including a listing with no results |
| 1 | The command failed, with the reason on stderr |
| 2 | The command line could not be parsed, with the usage on stderr |
| 3 | `isx proxy status` only: the proxy runs on an old bridge address |
| 4 | `isx vm stop --require-clean`: the VM is stopped, but with signals rather than a guest shutdown. `isx proxy status`: the proxy holding its ports is another user's |
| 75 | `isx init --no-sudo`: steps that need root are waiting in the script it names, for you to run |
| 76 | `isx init --format=json` or `plain`: it finished, but left steps only you can do, which its summary names |
| 78 | `isx proxy start` cannot run the proxy at all, such as when `isx-proxy` is not installed |

Exit 1 covers a value the command rejects (`--format=yaml`, `--status=frozen`), Incus unreachable, and the like.
`isx doctor` also exits 1 when a check fails, `isx vm status` when Incus is unreachable, and `isx proxy status` when the proxy is not running; that report is on stdout.

Exit 2 means an unknown option, an option missing its value or a stray argument.
For `isx proxy status` only, 2 also means the proxy is down but its DNS overrides are still active.
