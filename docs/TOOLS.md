# Tools

Templates are made of tools: packages, downloads and setup that any template can add by name.
This page lists the tools that ship with isx and shows how to define your own.
You need it when a template lacks something, or when a tool must carry a credential of its own.

## Built-in Tools

These tools ship with isx; a template's `tools:` list names them.

| Tool | Description |
|------|-------------|
| `claude` | Claude Code: AI coding assistant |
| `gh` | GitHub: PAT for git operations |
| `pi` | Pi: AI coding assistant |
| `gemini-cli` | Gemini CLI: Google coding assistant (proxied Gemini API key) |
| `bob` | Bob Shell: IBM AI coding assistant |
| `codex` | Codex CLI: OpenAI coding assistant |
| `copilot` | GitHub Copilot CLI: AI coding assistant (shares `gh` token) |
| `atlassian-mcp` | Atlassian MCP server for Jira and Confluence |
| `maven-3` | Apache Maven |
| `mvnd` | Apache Maven Daemon |
| `podman` | Podman container runtime configured for Testcontainers |
| `perf` | Linux perf tools and tracing utilities for performance analysis |
| `sshd` | OpenSSH server for remote access |
| `idea-backend` | JetBrains IntelliJ IDEA Remote Development backend |
| `vscode-remote` | VS Code Remote Development via SSH |
| `starship` | Starship cross-shell prompt with incus-spawn indicator |
| `tmux` | Terminal multiplexer with incus-spawn session integration |
| `zmx` | zmx: session attach/detach for the terminal |
| `headroom` | Headroom context optimization for Claude Code |

Run `isx tools list -v` to see all available tools, including user-defined and project-local definitions.
Use `isx tools show <name>` to inspect a tool's dependencies, packages, configurable parameters, downloads, TUI actions and proxy domains.

## Custom Tools

A custom tool is a YAML file that any template can name in its `tools:` list.
Template inheritance forms a single chain: a template has exactly one parent.
Tools provide composition: reusable capabilities that any template can mix in independently.

A `gradle` tool can be added to a Java template, a Kotlin template or a project-local template without duplicating definitions or creating diamond inheritance.
An image definition names a tool in `tools:`:

```yaml
# .incus-spawn/tools/gradle.yaml
name: gradle
description: Gradle 9.4.1

downloads:
  - url: https://services.gradle.org/distributions/gradle-9.4.1-bin.zip
    sha256: 2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
    extract: /opt
    links:
      /opt/gradle-9.4.1/bin/gradle: /usr/local/bin/gradle

verify: gradle --version

```

isx caches these downloads on the host at `~/.cache/incus-spawn/downloads/`, so a rebuild does not download an unchanged artifact again.
isx extracts on the host, so the instance needs no `tar`, `unzip` or `curl`.

#### Tool schema fields

Every field is optional except `name`.

| Field | What it does |
|-------|--------------|
| `description` | One line shown by `isx tools` and in build output |
| `packages` | dnf packages to install |
| `package_repos` | Additional package repositories to enable, as in a template |
| `downloads` | Artifacts to download, cache on the host, and copy or extract into the instance |
| `requires` | Other tools that must be installed first; an entry is a name, or a map such as `idea-backend: {memory: "8g"}` to pass parameters |
| `run` | Shell commands run as root |
| `run_as_user` | Shell commands run as agentuser |
| `files` | Files to write, each with an optional `owner` |
| `env` | Environment variables written to `/etc/profile.d/isx-env.sh` |
| `verify` | Verification command, logged and non-fatal |
| `verify_as_root` | `true` when the verify needs root |
| `ready` | Command each start polls until it succeeds, for up to 15 seconds, before handing the instance over; a tool that is not ready in time only warns |
| `parameters` | Build-time parameters a template sets in its `tools:` list, see [Tool Parameters](#tool-parameters) |
| `feature` | A feature flag the tool needs; a build skips the tool, with a warning, until the flag is listed under `features:` in `config.yaml` |
| `actions` | Runtime actions the TUI offers when the tool is installed, see [Tool Actions](#tool-actions) |
| `agent_note` | An always-true fact about this tool that an agent must know before acting |
| `skills` | Skills to install alongside the tool, teaching an agent how to drive it |
| `proxy` | Credential injection rules for the MITM proxy, see [Proxy Credentials](#proxy-credentials) |

isx resolves `requires` transitively, and detects and rejects a circular dependency.

`env` supports structured entries with merge strategies, see [Environment Variables](#environment-variables).

`verify` runs after every tool is installed, as `agentuser` in a login shell with the image environment (`/etc/profile.d/isx-env.sh`) loaded.
So it may rely on another tool's variables, such as `JAVA_HOME`.

`verify_as_root: true` is for a verify that needs root, such as `sshd -t`, which reads the host keys.
It then runs as root in root's own environment (`HOME=/root`, the default `PATH`), without the environment file, so it cannot rely on another tool's variables.

`agent_note` is explained in [Agent Environment Context](AGENTS.md#agent-environment-context).

`skills` takes the same forms as the image-level field, but bare names resolve against this tool's own `skills.repo`.

isx ignores a key it does not know, here or in an entry, with a warning when the tool loads.
The warning names where the key is (`downloads[0].sha265`) and the key it probably meant.

isx skips a tool file without `name:`, or with a blank one, with a warning.
`isx build` refuses until it is fixed, since the file may have been meant to replace a tool of the same name from another layer.

#### Download entry fields

`url` is required and `sha256` is recommended; the other fields are optional.

| Field | What it does |
|-------|--------------|
| `url` | The `http://` or `https://` download URL; a local address such as `localhost` is refused |
| `sha256` | SHA-256 checksum; enables cache reuse and verifies integrity |
| `extract` | Directory in the instance to extract into |
| `destination_file` | Exact path at which to expose the downloaded file in the instance; `~/` resolves to `/home/agentuser/` |
| `extract_in_container` | Extract inside the instance with its `tar` instead of on the host; a VM ignores it and always extracts on the host |
| `arch` | `x86_64` or `aarch64`; isx skips the download when the instance has another architecture |
| `links` | Map of `source_path: symlink_path` to create after extraction |

Each download must set `extract`, `destination_file`, or both.
Setting both preserves the downloaded archive at `destination_file` and also exposes its extracted contents.
Supported archive formats for `extract` are `.tar.gz`/`.tgz`, `.tar.bz2`, `.tar.xz`, `.zip`.

#### Install order

A tool installs in this order: packages → downloads → `run` → `run_as_user` → `files`.
After install, isx collects the environment variables from all tools and the template chain and writes them centrally.
Each tool's `verify` runs after that.
Resolution follows the same order as templates (see [Configuration](CONFIGURATION.md#configuration)).

A tool that needs logind, to linger or to run a systemd user unit, lists `dbus-broker` and `systemd-pam` in `packages`.
The base image ships no system bus, and without one logind does not start.

Enable lingering with `mkdir -p /var/lib/systemd/linger && touch /var/lib/systemd/linger/agentuser` in `run`.
`loginctl enable-linger` fails during the build, and isx warns about it when it loads the tool.
The built-in `podman` and `headroom` tools do both.

#### Environment Variables

Environment variables support four merge strategies:

```yaml
env:
  # Simple set (default strategy):
  - name: MAVEN_HOME
    value: /opt/apache-maven-3.9.16

  # Set only if not already defined:
  - name: DOCKER_HOST
    value: "unix:///var/run/docker.sock"
    strategy: set-if-unset

  # Prepend to existing value (with separator):
  - name: MAVEN_OPTS
    value: "-Xmx2g"
    strategy: prepend
    separator: " "

  # Append to existing value:
  - name: PATH
    value: /opt/bin
    strategy: append
    separator: ":"
```

The build collects all env entries from the template chain and installed tools and writes them to `/etc/profile.d/isx-env.sh`.
Every entry must use the structured `name`/`value` form.
isx rejects a shell string such as `- export FOO=bar` when it loads the definition.

The build catches conflicting definitions.
If two tools both `set` the same variable to different values, the build fails with an error naming both sources.

Templates can also declare environment variables directly:

```yaml
name: tpl-my-project
parent: tpl-minimal
env:
  - name: MY_DEBUG
    value: "true"
    strategy: set-if-unset
```

#### Proxy Credentials

A tool can register domains with the [MITM proxy](../README.md#credential-isolation), which adds the credentials as the requests leave the instance.
This gives any HTTPS API authentication without a secret inside the instance.
The built-in `claude`, `gh`, `bob`, `codex`, `copilot`, `gemini-cli` and `atlassian-mcp` tools use this mechanism, and your own tools can declare the same.

A `proxy:` block declares four keys.

| Key | What it declares |
|-----|------------------|
| `config-namespace` | The namespace that scopes the config paths |
| `configuration` | The host credentials |
| `auth` | Entries mapping domains to authentication rules |
| `placeholders` | The guest variables that carry proofs |

The namespace uses letters, digits, `.`, `-` and `_`, starting with a letter or digit.
A proof is the per-instance value the guest sends in place of the credential; the proxy recognizes it and swaps in the real one.

A configuration entry can also use `value` for a hardcoded literal, which `isx init` does not prompt for, and `type: confirm` for a yes/no prompt such as license acceptance.
A `help` list of lines is printed before the prompt in place of the description.

```yaml
# ~/.config/incus-spawn/tools/artifactory.yaml
name: artifactory
description: JFrog Artifactory

env:
  - name: ARTIFACTORY_TOKEN
    value: artifactory_placeholder

proxy:
  config-namespace: artifactory
  placeholders:
    - env: ARTIFACTORY_TOKEN
      prefix: artifactory_
  configuration:
    token:
      config-path: "token"
      description: "Artifactory API token"
      secret: true
  auth:
    - domains:
        - artifactory.internal.example.com
      type: bearer
      token: "${token}"
```

After placing this file, `isx init` prompts for the Artifactory token alongside other credentials.
isx stores the token in `~/.config/incus-spawn/config.yaml` under the `artifactory` namespace's selected account.

Add the tool to your template's `tools:` list and rebuild it so the guest exports `ARTIFACTORY_TOKEN`.
Each start replaces `artifactory_placeholder` with that instance's current proof in a login shell.
Send it in the declared authentication header:

```shell
curl -H "Authorization: Bearer $ARTIFACTORY_TOKEN" https://artifactory.internal.example.com/
```

The proxy verifies that proof and replaces it with the selected host account's real token.
The `env:` value must start with the declared prefix followed by `placeholder`.

Tools must read the variable when they run: a build proof is revoked before publication, and an explicit reset replaces an instance's proofs.
Tools that read an auth file must refresh it from the variable at login or launch.

##### Placeholder declarations

The proxy injects a credential only for a namespace with declared placeholders, under these rules.

- A proxied auth namespace without declared `placeholders:` cannot authorize credential injection.
  The proxy logs a warning when loading its configuration, and tool definition validation reports missing declarations for the tool's own namespace.
- A tool borrowing a namespace through an absolute `config-path`, such as `github.token` without `config-namespace`, can reuse that namespace's proof declarations.
  Install the tool supplying those declarations and send its current proof with each request.
- The proxy checks declarations across all loaded tools and warns if the borrowed namespace has none.
- An unscoped top-level credential needs a `config-namespace` and relative config paths before it can declare placeholders.
- Another tool sharing that domain is not affected: a request needs only the proofs of the entry it selects.
- The proxy forwards a request without valid proofs with its own authentication unchanged.

##### Auth types

Three auth types are supported:

| Type | Injected header | Required fields |
|------|----------------|-----------------|
| `bearer` | `Authorization: Bearer <token>` | `token` |
| `basic` | `Authorization: Basic <base64(username:password)>` | `username`, `password` |
| `header` | Custom header | `name`, `value` |

An auth field holds a literal value (`username: "deploy-bot"`) or a `${configKey}` reference, resolved against the `configuration` map.
A tool with all three types:

```yaml
name: my-service
env:
  - name: MY_SERVICE_TOKEN
    value: myservice_placeholder
proxy:
  config-namespace: myService
  placeholders:
    - env: MY_SERVICE_TOKEN
      prefix: myservice_
  configuration:
    api-key:
      config-path: "apiKey"
      description: "API key for myservice.com"
      secret: true
    password:
      config-path: "password"
      description: "Registry password"
      secret: true
  auth:
    - domains:
        - api.myservice.com
      type: bearer
      token: "${api-key}"
    - domains:
        - registry.myservice.com
      type: basic
      username: "deploy-bot"
      password: "${password}"
    - domains:
        - "*.internal.myservice.com"
      type: header
      name: X-API-Key
      value: "${api-key}"
```

Wildcard domains (`*.internal.myservice.com`) match any subdomain.
An entry for the exact domain is tried first.
The most specific wildcard wins when multiple tools register overlapping suffixes.
In this example, send `$MY_SERVICE_TOKEN` as the bearer token, Basic password or `X-API-Key` value for the corresponding domain.

##### Path-scoped injection

An auth entry can restrict credential injection to specific request paths with `paths:`.
When set, the proxy injects the credential only for requests whose path equals one of the listed prefixes or starts with one followed by `/`.
The proxy strips query strings before matching.
Without `paths:`, the proxy injects the credential for every request to the domain.

```yaml
# Only inject the credential for MCP requests, not for other API paths
name: develocity-mcp
description: Develocity MCP server

env:
  - name: DEVELOCITY_TOKEN
    value: develocity_placeholder

proxy:
  config-namespace: develocity
  placeholders:
    - env: DEVELOCITY_TOKEN
      prefix: develocity_
  configuration:
    token:
      config-path: "token"
      description: "Develocity access key"
      secret: true
  auth:
    - domains:
        - develocity.example.com
      paths:
        - /mcp
      type: bearer
      token: "${token}"
```

Here, a request to `develocity.example.com/mcp` or `develocity.example.com/mcp/messages` receives the credential.
A request to `develocity.example.com/api/build-scan` does not.

When several entries cover one domain, the proxy picks one by these rules.

- Two tools can register auth entries for the same domain with different path scopes.
- The proxy tries path-scoped entries first, as they are more specific, then unscoped entries.
- When path scopes overlap, the longest matching prefix wins.
  With `/api` and `/api/admin`, a request to `/api/admin/users` gets the `/api/admin` entry's credential, whichever tool was loaded first.
- A prefix matches whole path segments, so `/api` does not match `/apis`.
- When two tools register unscoped entries for the same domain, the first one loaded wins and the proxy logs a warning.
- A trailing slash in a prefix is normalized: `/mcp/` is treated as `/mcp`.
  A root scope of `/` matches every path.
- The proxy compares the request path as sent, never normalized.
  A path an upstream may serve as another path therefore matches no scope but the root.

Such a path has any of these:
- a raw character outside RFC 3986 `pchar` and `/`: a backslash, `#`, a space, a control, any non-ASCII byte
- a segment of only dots and spaces (`..`, `...`, `..%20`, `..+`, also as `..;`), counted once every non-ASCII character is dropped (`..%CC%81`, so also `日.`)
- a segment of only combining marks or Hangul fillers, or an empty segment such as `//`
- a percent-escape of an unreserved character, `/`, `\`, `;`, `%`, `?`, `#`, a control or DEL (`/mcp/%2e%2e/admin`, `/api/%61dmin`, `..%00`), or a malformed one
- escapes that are not strict UTF-8 (the overlong `%c0%ae`)
- an escaped character other than a letter, digit or combining mark (`%e2%88%95`, `%e2%80%8b`), or a solidus overlay mark (`%cc%b8`)
- escaped text whose NFKC form has other ASCII than its NFC form (the fullwidth `%ef%bc%8e`, the ligature `%ef%ac%81`)

Such a request, and a prefix of such a form, are handled by these rules.

- The proxy serves such a request as if no scoped entry matched.
- An entry that serves every path injects its credential when the request proves its namespace.
  That is an entry unscoped or scoped to `/`, on the domain or else on a wildcard covering it.
  Otherwise the proxy relays the request with its own authentication.
- Each prefix is a literal path starting with `/`, without a query, wildcard, whitespace, `;`, empty segment, `.`/`..` segment or any other form listed above.
- isx refuses a tool definition with any other prefix when definitions load, with a warning, and `isx build` refuses until it is fixed.
  Such a prefix could never match, and servers that drop `;` path parameters would serve another path.

When tools with different credential namespaces share a domain, these rules apply.

- A request needs only the proof of the entry its path selects.
  A proof it does carry for another namespace must still be valid.
- This needs distinct placeholder prefixes.
  When two such tools send their proofs in the same header under the same prefix, the proxy cannot tell which namespace a proof is for, and a request must carry both.
- The proxy never injects an entry for a request that did not prove its own namespace.
  With an `/mcp` request carrying only the other tool's proof, or one that falls back to an unscoped entry of another tool, the proxy relays the request with its own authentication and logs a warning.

##### Where the token lives

If the tool insists on having a token locally before it will send a request, give it a placeholder through `env:` and let it read that variable whenever it runs.

Never copy the value into a file with `files:` or a build step.
The token is unique to an instance and can be explicitly reset, so a copy made at build time is revoked before publication, and isx warns when it finds one.

Tool YAML files with `proxy:` blocks must be placed in `~/.config/incus-spawn/tools/` or a configured search path.
Project-local tools (`.incus-spawn/tools/`) cannot declare proxy rules, because the proxy runs independently of any project directory, and so cannot grant access to host credentials.

### Remote IDE Access

Both VS Code and JetBrains IntelliJ can connect to an instance with their UI running natively on the host and all backend processing (indexing, builds, terminals, extensions) running inside the instance.

isx manages the SSH keys: `isx init` generates a dedicated passphraseless key pair at `~/.config/incus-spawn/ssh/`.
Each start of an instance with an SSH server injects it, along with your personal `~/.ssh` key: the first of `id_ed25519.pub`, `id_ecdsa.pub` and `id_rsa.pub`.

isx writes a `Host` entry per instance to `~/.config/incus-spawn/ssh/config`, included from your `~/.ssh/config`, with host key checking off.
So `ssh <instance-name>` just works: no passphrase prompt, no host key warning.
isx removes the entry when it destroys an instance.

Both tools declare TUI actions: press F9 on a running instance to open a repo directly in your IDE.

#### VS Code (Remote SSH)

The built-in `vscode-remote` tool provides one-click "Open in VS Code" actions.
It declares `requires: [sshd]`, so the SSH server is installed automatically.
No backend is pre-installed in the instance: VS Code downloads its own server component on first connect.

On the host, install the [Remote SSH](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-ssh) extension in VS Code.

```yaml
name: tpl-java-vscode
parent: tpl-java
tools:
  - vscode-remote    # auto-installs sshd via requires
```

#### JetBrains IntelliJ (Gateway)

The built-in `idea-backend` tool installs the JetBrains IntelliJ IDEA remote development backend in the instance.
It declares `requires: [sshd]`, so the SSH server is installed automatically.

On the host, install [JetBrains Gateway](https://www.jetbrains.com/remote-development/gateway/).

```yaml
name: tpl-java-ide
parent: tpl-java
tools:
  - idea-backend    # auto-installs sshd via requires
```

The `idea-backend` tool accepts a `memory` parameter to control the JVM heap size (default `2g`).
Use the map form to customize it:

```yaml
tools:
  - idea-backend:
      memory: "8g"
```

The `index-mcp` parameter gives the agent the IDE's code intelligence: find references, go to definition and diagnostics.
With `index-mcp: true`, isx installs the [IDE Index MCP Server](https://plugins.jetbrains.com/plugin/29174-ide-index-mcp-server) plugin and registers it in Claude Code as `intellij-index`.
The server runs while the IDE backend runs, which is while a Gateway session is open.
It defaults to `false`.

```yaml
tools:
  - claude
  - idea-backend:
      index-mcp: true
```

Claude Code must be installed first, in a parent template or earlier in the list, or the build fails.

### Terminal Session Persistence (zmx)

[zmx](https://zmx.sh) provides session attach/detach for the terminal: persistent shell sessions that survive disconnections, with native scrollback and multi-client support.
Unlike tmux, it delegates window management to your OS rather than reimplementing it.

```yaml
name: tpl-zmx
parent: tpl-minimal
tools:
  - zmx
```

By default, `auto_attach` is enabled: shelling into the instance automatically attaches to a persistent zmx session named `isx`.
To install zmx without auto-attaching:

```yaml
tools:
  - zmx:
      auto_attach: "false"
```

On Linux, isx shares the instance's zmx socket directory with the host through a disk device.
Instance sessions appear in your native zmx socket directory with an `isx-` prefix, with no configuration needed:

```bash
zmx list                              # shows isx-my-branch alongside local sessions
zmx attach isx-my-branch              # attach to the container's session from the host
zmx history isx-my-branch             # view scrollback from the container
zmx run isx-my-branch git status      # run a command in the container's session
```

All zmx commands work natively, including interactive `zmx attach`.
On macOS, where Incus runs inside a VM, host-side socket sharing is not available.
zmx sessions still work inside instances: `isx shell` (or Enter in the TUI) auto-attaches, and sessions persist across disconnections.

### Context Optimization (headroom)

The built-in `headroom` tool installs [Headroom](https://github.com/nicobrenner/headroom), a context optimization proxy for Claude Code.
It runs as a local proxy on port 8787 that compresses conversation context, reducing token usage and improving response quality on long sessions.
The tool also registers an MCP server with Claude Code for direct integration.

```yaml
name: tpl-headroom
parent: tpl-minimal
tools:
  - headroom    # auto-installs claude via requires
```

The tool sets `ANTHROPIC_BASE_URL=http://localhost:8787` so Claude Code routes through the optimization proxy.
The proxy runs as a systemd user service and starts automatically when the instance boots.

### Tool Parameters

A tool can define parameters for build-time configuration.

| Type | Options |
|------|---------|
| `string` | optional `pattern` |
| `integer` | `min`, `max` |
| `boolean` | none |
| `enum` | `options` |

Every parameter also takes `default`, `description`, `optional` and `reconfigurable`.
A parameter with no value and no default is an error unless it is `optional` or `reconfigurable`.
A child template may change a `reconfigurable` parameter of a tool an ancestor installed; changing any other one there is an error.

A parameter `memory` is `${param_memory}` in `run`, `run_as_user`, `verify`, env values and file paths and content:

```yaml
# tools/my-server.yaml
name: my-server
parameters:
  memory:
    type: string
    default: "2g"
    pattern: "^[0-9]+[gGmM]$"
env:
  - name: SERVER_MEMORY
    value: ${param_memory}
```

Pass parameter values using the map form in image definitions (the `idea-backend` memory example above shows this pattern).

### Tool Actions

A tool can declare runtime actions that appear in the TUI: press F9 on a running instance, or Enter to run the template's default action.
From the CLI, `isx run <instance> --action <tool:action-id>` invokes one.
Running `isx run <instance>` with no `--action` flag executes the template's default action.

Actions can be declared in YAML tool definitions or programmatically by Java/CDI tools.
The built-in `claude`, `pi`, `gemini-cli`, `bob` and `codex` tools each contribute a shell action that launches the agent when a template's `tools` list includes them.

#### Action entry fields

`label` and `type` are required; the other fields are optional.

| Field | What it does |
|-------|--------------|
| `label` | Display text shown in the F9 menu; only the repo variables apply, not `${ip}`, `${name}` or `${parent}` |
| `type` | One of `url`, `command`, `shell`, `copy-to-clipboard` |
| `id` | Stable identifier for referencing from `default-action: tool:action-id` |
| `requires_running` | Whether the instance must be running (default `true`) |
| `expand` | Set to `repos` to generate one action per declared repository |
| `auto_return` | Return without waiting for a key after the action completes (default `false`); a `shell` action never waits, and the TUI never waits after a `url` or `copy-to-clipboard` action |

Each type takes one field of its own.

| Type | Field | What it holds |
|------|-------|---------------|
| `url` | `url` | URL to open in the host browser |
| `command` | `command` | Shell command to run on the host |
| `shell` | `command` | Command to run inside the instance as an interactive terminal session |
| `copy-to-clipboard` | `text` | Text to copy to the host clipboard |

Two fields control where else an action shows.

- `shell_menu`: when `true`, the action also appears in the F12 shell status bar menu during `isx shell` sessions.
  The menu exists only with the `shell-status-bar` feature enabled in `config.yaml`.
  Only `url` actions can, as they open on the host without taking over the terminal.
- `shortcut`: the single key that runs the action from that menu.
  It is one printable ASCII character, not shared with another menu action (case-insensitively); an action without a usable one is left out of the menu with a warning.

Template variables available in `url`, `command`, and `text`: `${ip}`, `${name}`, `${parent}`.
When `expand: repos` is set, repo-specific variables are also available: `${repo_name}`, `${repo_path}`, `${repo_url}`.

```yaml
actions:
  - label: "Open repo '${repo_name}' in Gateway"
    type: url
    expand: repos
    url: "jetbrains-gateway://connect#host=${ip}&projectPath=${repo_path}"
  - label: "Launch agent"
    id: launch
    type: shell
    command: "my-agent --continue"
    auto_return: true
```
