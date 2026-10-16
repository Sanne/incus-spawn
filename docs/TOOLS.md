# Tools

Templates are made of tools: packages, downloads and setup that any template can add by name. This page lists the tools that ship with isx and shows how to define your own.

## Built-in Tools

These tools ship with incus-spawn and can be referenced by name in a template's `tools:` list:

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
| `sshd` | OpenSSH server for remote access |
| `idea-backend` | JetBrains IntelliJ IDEA Remote Development backend |
| `vscode-remote` | VS Code Remote Development via SSH |
| `starship` | Starship cross-shell prompt with incus-spawn indicator |
| `tmux` | Terminal multiplexer with incus-spawn session integration |
| `zmx` | zmx: session attach/detach for the terminal |
| `headroom` | Headroom context optimization for Claude Code |

Run `isx tools list -v` to see all available tools including user-defined and project-local definitions. Use `isx tools show <name>` to inspect a tool's dependencies, packages, configurable parameters, downloads, TUI actions, and proxy domains.

## Custom Tools

Template inheritance forms a single chain -- a template has exactly one parent. Tools provide composition: reusable capabilities that any template can mix in independently. A `gradle` tool can be added to a Java template, a Kotlin template, or a project-local template without duplicating definitions or creating diamond inheritance.

Tools are defined as YAML files and referenced from image definitions via `tools:`:

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

Downloads declared this way are cached on the host at `~/.cache/incus-spawn/downloads/`, so rebuilding images doesn't re-download unchanged artifacts.
Extraction happens on the host -- the container doesn't need `tar`, `unzip`, or `curl`.

Tool schema fields (all optional except `name`):
- `packages` -- dnf packages to install
- `downloads` -- artifacts to download, cache on the host, and copy and/or extract into the container
- `requires` -- list of other tool names that must be installed first (resolved transitively; circular dependencies are detected and rejected)
- `run` -- shell commands as root
- `run_as_user` -- shell commands as agentuser
- `files` -- files to write (with optional `owner`)
- `env` -- environment variables written to `/etc/profile.d/isx-env.sh` (supports structured entries with merge strategies; see below)
- `verify` -- verification command (logged, non-fatal); runs after every tool is installed, as `agentuser` in a login shell with the image environment (`/etc/profile.d/isx-env.sh`) loaded, so it may rely on another tool's variables such as `JAVA_HOME`
- `verify_as_root` -- set to `true` when the verify needs root (for example `sshd -t`, which reads the host keys); it then runs as root in root's own environment (`HOME=/root`, the default `PATH`), **without** the environment file, so it cannot rely on another tool's variables
- `actions` -- runtime actions available from the TUI when the tool is installed (see [Tool Actions](#tool-actions))
- `agent_note` -- always-true fact about this tool that an agent must know before acting (see [Agent Environment Context](AGENTS.md#agent-environment-context))
- `skills` -- skills to install alongside the tool, teaching an agent how to drive it; same forms as the image-level field, but bare names resolve against this tool's own `skills.repo`
- `proxy` -- credential injection rules for the MITM proxy (see [Proxy Credentials](#proxy-credentials))

Download entry fields:
- `url` (required) -- `http://` or `https://` download URL (local addresses such as `localhost` are refused)
- `sha256` (recommended) -- SHA-256 checksum; enables cache reuse and verifies integrity
- `extract` (optional) -- directory in the container to extract into
- `destination_file` (optional) -- exact path at which to expose the downloaded file in the container; `~/` resolves to `/home/agentuser/`
- `extract_in_container` (optional) -- extract inside the container instead of on the host
- `links` (optional) -- map of `source_path: symlink_path` to create after extraction

Each download must set `extract`, `destination_file`, or both. Setting both preserves the downloaded archive at `destination_file` and also exposes its extracted contents. Supported archive formats for `extract` are `.tar.gz`/`.tgz`, `.tar.bz2`, `.tar.xz`, `.zip`.

Execution order during `install()`: packages → downloads → `run` → `run_as_user` → `files`. Environment variables are collected from all tools and the template chain after install, then written centrally; each tool's `verify` runs after that. Resolution follows the same order as templates (see [Configuration](CONFIGURATION.md#configuration)).

A tool that needs logind, to linger or to run a systemd user unit, lists `dbus-broker` and `systemd-pam` in `packages`: the base image ships no system bus, and without one logind does not start. Enable lingering with `mkdir -p /var/lib/systemd/linger && touch /var/lib/systemd/linger/agentuser` in `run`, not `loginctl enable-linger`, which fails during the build; isx warns about the latter when it loads the tool. The built-in `podman` and `headroom` tools do both.

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

All env entries from the template chain and installed tools are collected by the build system and written to `/etc/profile.d/isx-env.sh`. **Conflicting definitions are caught at build time**: if two tools both `set` the same variable to different values, the build fails with a descriptive error naming both sources. Every entry must use the structured `name`/`value` form; a shell string such as `- export FOO=bar` is rejected when the definition is loaded.

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

Tools can register domains with the [MITM proxy](../README.md#credential-isolation) for transparent credential injection. This lets you add authentication to any HTTPS API without exposing secrets inside containers. The built-in `claude`, `gh`, `bob`, `codex`, `copilot`, and `gemini-cli` tools use this mechanism -- but you can declare the same for your own tools.

A `proxy:` block declares a `config-namespace`, `configuration`, `auth`, and `placeholders`.
The namespace scopes config paths and uses letters, digits, `.`, `-` and `_`, starting with a letter or digit.
The configuration map declares host credentials, auth entries map domains to authentication rules, and placeholders declare the guest variables that carry proofs.

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

After placing this file, `isx init` will prompt for the Artifactory token alongside other credentials.
The token is stored in `~/.config/incus-spawn/config.yaml` under the `artifactory` namespace's selected account.
Add the tool to your template's `tools:` list and rebuild it so the guest exports `ARTIFACTORY_TOKEN`.
Each start replaces `artifactory_placeholder` with that instance's current proof in a login shell.
Send it in the declared authentication header:

```shell
curl -H "Authorization: Bearer $ARTIFACTORY_TOKEN" https://artifactory.internal.example.com/
```

The proxy verifies that proof and replaces it with the selected host account's real token.
The `env:` value must start with the declared prefix followed by `placeholder`.
Tools must read the variable when they run; a build proof is revoked before publication, and an explicit reset replaces an instance's proofs.
Tools that read an auth file must refresh it from the variable at login or launch.
Place credential-injecting tool definitions in the host's tool search paths, such as `~/.config/incus-spawn/tools/`; project-local definitions cannot grant access to host credentials.

A proxied auth namespace without declared `placeholders:` cannot authorize credential injection.
The proxy logs a warning when loading its configuration, and tool definition validation reports missing declarations for the tool's own namespace.
A tool borrowing a namespace through an absolute `config-path`, such as `github.token` without `config-namespace`, can reuse that namespace's proof declarations.
Install the tool supplying those declarations and send its current proof with each request.
The proxy checks declarations across all loaded tools and warns if the borrowed namespace has none.
An unscoped top-level credential needs a `config-namespace` and relative config paths before it can declare placeholders.
Another tool sharing that domain is not affected: a request needs only the proofs of the entry it selects.
Requests without valid proofs are forwarded with their own authentication unchanged.

Three auth types are supported:

| Type | Injected header | Required fields |
|------|----------------|-----------------|
| `bearer` | `Authorization: Bearer <token>` | `token` |
| `basic` | `Authorization: Basic <base64(username:password)>` | `username`, `password` |
| `header` | Custom header | `name`, `value` |

Auth fields can be literal values (e.g. `username: "deploy-bot"`) or `${configKey}` references resolved against the `configuration` map. A tool with all three types:

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
The most specific wildcard wins when multiple tools register overlapping suffixes.
In this example, send `$MY_SERVICE_TOKEN` as the bearer token, Basic password, or `X-API-Key` value for the corresponding domain.

##### Path-scoped injection

An auth entry can restrict credential injection to specific request paths with `paths:`.
When set, the proxy injects the credential only for requests whose path equals one of the listed prefixes or starts with one followed by `/`.
Query strings are stripped before matching.
When `paths:` is omitted, the credential is injected for every request to the domain, as before.

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

Two tools can register auth entries for the same domain with different path scopes.
Path-scoped entries are tried first (they are more specific), then unscoped entries.
When path scopes overlap, the longest matching prefix wins: with `/api` and `/api/admin`, a request to `/api/admin/users` gets the `/api/admin` entry's credential, whichever tool was loaded first.
A prefix matches whole path segments, so `/api` does not match `/apis`.
When two tools register unscoped entries for the same domain, the first one loaded wins and a warning is logged.
Trailing slashes in prefixes are normalized (`/mcp/` is treated as `/mcp`), and a root scope of `/` matches every path.
The request path is compared as sent, never normalized.
A path an upstream may serve as another path therefore matches no scope but the root.
Such a path has any of these:
- a raw character outside RFC 3986 `pchar` and `/`: a backslash, `#`, a space, a control, any non-ASCII byte
- a segment of only dots and spaces (`..`, `...`, `..%20`, `..+`, also as `..;`), counted once every non-ASCII character is dropped (`..%CC%81`, so also `日.`)
- a segment of only combining marks or Hangul fillers, or an empty segment such as `//`
- a percent-escape of an unreserved character, `/`, `\`, `;`, `%`, `?`, `#`, a control or DEL (`/mcp/%2e%2e/admin`, `/api/%61dmin`, `..%00`), or a malformed one
- escapes that are not strict UTF-8 (the overlong `%c0%ae`)
- an escaped character other than a letter, digit or combining mark (`%e2%88%95`, `%e2%80%8b`), or a solidus overlay mark (`%cc%b8`)
- escaped text whose NFKC form has other ASCII than its NFC form (the fullwidth `%ef%bc%8e`, the ligature `%ef%ac%81`)

Such a request is served as if no scoped entry matched.
An entry that serves every path (unscoped, or scoped to `/`, on the domain or else on a wildcard covering it) injects its credential when the request proves its namespace.
Otherwise the request is relayed with its own authentication.
Each prefix is a literal path starting with `/`, without a query, wildcard, whitespace, `;`, empty segment, `.`/`..` segment or any other form listed above.
A tool definition with any other prefix is skipped with a warning when definitions load: such a prefix could never match, and servers that drop `;` path parameters would serve another path.
When tools with different credential namespaces share a domain, a request needs only the proof of the entry its path selects; a proof it does carry for another namespace must still be valid.
This needs distinct placeholder prefixes: when two such tools send their proofs in the same header under the same prefix, the proxy cannot tell which namespace a proof is for, and a request must carry both.
An entry is never injected for a request that did not prove its own namespace: with an `/mcp` request carrying only the other tool's proof, or one that falls back to an unscoped entry of another tool, the request is relayed with its own authentication and the proxy logs a warning.

Configuration entries can also use `value` for hardcoded literals (no prompt during `isx init`) and `type: confirm` for yes/no prompts like license acceptance.

If the tool insists on having a token locally before it will send a request, give it a placeholder through `env:` and let it read that variable whenever it runs. Never copy the value into a file with `files:` or a build step: the token is unique to an instance and can be explicitly reset, so a copy made at build time is revoked before publication, and isx warns when it finds one.

Tool YAML files with `proxy:` blocks must be placed in `~/.config/incus-spawn/tools/` or a configured search path -- project-local tools (`.incus-spawn/tools/`) cannot declare proxy rules because the proxy daemon runs independently of any project directory.

### Remote IDE Access

Both VS Code and JetBrains IntelliJ can connect to containers with their UI running natively on the host and all backend processing (indexing, builds, terminals, extensions) running inside the container. SSH keys are managed automatically: `isx init` generates a dedicated passphraseless key pair at `~/.config/incus-spawn/ssh/`, and each branch injects it into the container along with your personal `~/.ssh` key. Container host keys are pre-validated so `ssh <instance-name>` just works — no passphrase prompt, no host key warning. Entries are cleaned up when instances are destroyed.

Both tools declare TUI actions — press **F9** on a running instance to open a repo directly in your IDE.

#### VS Code (Remote - SSH)

The built-in `vscode-remote` tool provides one-click "Open in VS Code" actions. It declares `requires: [sshd]`, so the SSH server is installed automatically. No backend is pre-installed inside the container — VS Code downloads its own server component on first connect.

**Host prerequisite**: install the [Remote - SSH](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-ssh) extension in VS Code.

```yaml
name: tpl-java-vscode
parent: tpl-java
tools:
  - vscode-remote    # auto-installs sshd via requires
```

#### JetBrains IntelliJ (Gateway)

The built-in `idea-backend` tool installs the JetBrains IntelliJ IDEA remote development backend inside the container. It declares `requires: [sshd]`, so the SSH server is installed automatically.

**Host prerequisite**: install [JetBrains Gateway](https://www.jetbrains.com/remote-development/gateway/).

```yaml
name: tpl-java-ide
parent: tpl-java
tools:
  - idea-backend    # auto-installs sshd via requires
```

The `idea-backend` tool accepts a `memory` parameter to control the JVM heap size (default `2g`). Use the map form to customize it:

```yaml
tools:
  - idea-backend:
      memory: "8g"
```

### Terminal Session Persistence (zmx)

[zmx](https://zmx.sh) provides session attach/detach for the terminal — persistent shell sessions that survive disconnections, with native scrollback and multi-client support. Unlike tmux, it delegates window management to your OS rather than reimplementing it.

```yaml
name: tpl-zmx
parent: tpl-minimal
tools:
  - zmx
```

By default, `auto_attach` is enabled: shelling into the container automatically attaches to a persistent zmx session named `isx`. To install zmx without auto-attaching:

```yaml
tools:
  - zmx:
      auto_attach: "false"
```

On Linux, the container's zmx socket directory is shared with the host via a disk device. Container sessions appear in your native zmx socket directory with an `isx-` prefix — no configuration needed:

```bash
zmx list                              # shows isx-my-branch alongside local sessions
zmx attach isx-my-branch              # attach to the container's session from the host
zmx history isx-my-branch             # view scrollback from the container
zmx run isx-my-branch git status      # run a command in the container's session
```

All zmx commands work natively, including interactive `zmx attach`. On macOS, where Incus runs inside a VM, host-side socket sharing is not available. zmx sessions still work inside containers — `isx shell` (or Enter in the TUI) auto-attaches, and sessions persist across disconnections.

### Context Optimization (headroom)

The built-in `headroom` tool installs [Headroom](https://github.com/nicobrenner/headroom), a context optimization proxy for Claude Code. It runs as a local proxy on port 8787 that compresses conversation context, reducing token usage and improving response quality on long sessions. An MCP server is also registered with Claude Code for direct integration.

```yaml
name: tpl-headroom
parent: tpl-minimal
tools:
  - headroom    # auto-installs claude via requires
```

The tool sets `ANTHROPIC_BASE_URL=http://localhost:8787` so Claude Code routes through the optimization proxy. The proxy runs as a systemd user service and starts automatically on container boot.

### Tool Parameters

Tools can define parameters for build-time configuration. Parameter types: `string` (with optional `pattern`), `integer` (with `min`/`max`), `boolean`, and `enum` (with `options`). Use `${param_name}` to reference values in scripts, env, and file content:

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

Tools can declare runtime actions that appear in the TUI (press **F9** on a running instance, or **Enter** to run the template's default action) and can be invoked from the CLI via `isx run <instance> --action <tool:action-id>`. Running `isx run <instance>` with no `--action` flag executes the template's default action. Actions can be declared in YAML tool definitions or programmatically by Java/CDI tools. The built-in `claude` and `pi` tools automatically contribute shell actions ("Claude Code" and "Pi Coding Agent") when included in a template's `tools` list.

Action entry fields:

- `label` (required) -- display text shown in the F9 menu; supports template variables
- `type` (required) -- one of: `url`, `command`, `shell`, `copy-to-clipboard`
- `id` -- stable identifier for referencing from `default-action: tool:action-id`
- `requires_running` -- whether the instance must be running (default: `true`)
- `expand` -- set to `repos` to generate one action per declared repository
- `auto_return` -- return to TUI automatically after the action completes (default: `false`; only meaningful for `command` and `shell`)

Type-specific fields:

- **`url`**: `url` -- URL to open in the host browser
- **`command`**: `command` -- shell command to run on the host
- **`shell`**: `command` -- command to run inside the container as an interactive terminal session
- **`copy-to-clipboard`**: `text` -- text to copy to the host clipboard

Action visibility fields:

- `shell_menu` -- when `true`, the action also appears in the **F12 shell status bar menu** during `isx shell` sessions (only `url` actions, which open on the host without taking over the terminal)
- `shortcut` -- the single key that runs the action from that menu: one printable ASCII character, not shared with another menu action (case-insensitively); an action without a usable one is left out of the menu with a warning

Template variables available in `label`, `url`, `command`, and `text`: `${ip}`, `${name}`, `${parent}`. When `expand: repos` is set, repo-specific variables are also available: `${repo_name}`, `${repo_path}`, `${repo_url}`.

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
