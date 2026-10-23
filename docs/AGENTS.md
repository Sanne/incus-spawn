# Agents

Each agent is a tool: add it to a template's `tools:` list, and every branch of the template has it, set up for unattended work.
Its credentials stay on the host, where `isx init` configures them, and the [proxy](../README.md#credential-isolation) adds them to its requests as they leave the instance.

## Claude Code

Claude Code is Anthropic's official CLI for Claude.
Add it to any template with `tools: [claude]`:

```yaml
name: tpl-claude-dev
description: Isolated dev environment with Claude Code
parent: tpl-minimal
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - claude
default-action: claude
```

The `claude` tool downloads the latest Claude Code binary, configures permissions for unattended agent use, and sets up authentication.
All three auth modes work transparently — the [MITM proxy](../README.md#credential-isolation) injects credentials so no real API keys or tokens enter the container.

To preconfigure which model Claude Code uses, pass the `model` parameter:

```yaml
tools:
  - claude:
      model: claude-sonnet-4-6
```

When omitted, Claude Code uses its own default.
Model IDs follow the `claude-*` naming convention (e.g. `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5-20251001`).

## Codex CLI

Codex CLI is OpenAI's coding agent.
Add it to any template with `tools: [codex]`:

```yaml
name: tpl-codex-dev
description: Isolated dev environment with Codex CLI
parent: tpl-minimal
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - codex
default-action: codex
```

Codex needs an OpenAI API key ([create one here](https://platform.openai.com/api-keys); API usage needs billing credits, even on free accounts).
Run `isx init` to configure it: the key stays on the host and the [MITM proxy](../README.md#credential-isolation) injects it.

The build configures Codex for unattended use and trusts the template's repos.
The default action resumes your most recent session.
The footer shows the model, the current directory and `Running unconstrained in isx [version] [container name]`, the name of a permission profile with full filesystem and network access (and live web search).
The version and container name refresh at login, including after cloning a template.
Use `/statusline` to customize the footer; the `permissions` item displays the isx message.

To pin a model or reasoning effort (by default Codex picks both):

```yaml
tools:
  - codex:
      model: gpt-5.3-codex
      effort: high
```

`model` is any model your OpenAI account can use ([model list](https://platform.openai.com/docs/models)); `effort` is `minimal`, `low`, `medium`, `high` or `xhigh`.

## Pi Coding Agent

Pi is a provider-agnostic CLI coding agent that uses the standard Anthropic API.
Add it to any template with `tools: [pi]`:

```yaml
name: tpl-pi-dev
description: Isolated dev environment with Pi coding agent
parent: tpl-minimal
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - pi
shell-command: pi
```

Pi works out of the box with all three Anthropic auth modes (API key, Claude Pro/Max OAuth, Vertex AI) — the [MITM proxy](../README.md#credential-isolation) injects credentials transparently.
To use Pi without making it the default shell, omit `shell-command` and launch it manually after `isx shell`.

Pi can also run against OpenAI, using the same key as [Codex CLI](#codex-cli):

```yaml
tools:
  - pi:
      provider: openai
      model: gpt-5.3
```

`provider` defaults to `anthropic` and also accepts `vertex`/`google`; `model` defaults to `claude-sonnet-4-6`.

## Gemini CLI

Add Google's [Gemini CLI](https://geminicli.com/docs/get-started/installation/) to a template with `tools: [gemini-cli]`:

```yaml
name: tpl-gemini-dev
parent: tpl-minimal
tools:
  - gemini-cli
default-action: gemini-cli
```

Run `isx init` on the host and select Gemini CLI to configure a [Gemini API key](https://aistudio.google.com/apikey).
Keys are stored in host-side `gemini.accounts.<name>.apiKey`, with `gemini.default` selecting the default account.
Use `accounts: { gemini: work }` in a template or `isx branch --from tpl-gemini-dev --account gemini=work my-gemini` to select a named account.
The box receives a `GEMINI_API_KEY` proof placeholder; the proxy verifies it and injects the selected key into requests to `generativelanguage.googleapis.com`.
Gemini CLI reads the placeholder when launched, so no API key or proof is saved in its settings.
This integration supports Gemini API keys; Google OAuth and Vertex authentication remain separate work ([#1114](https://github.com/Sanne/incus-spawn/issues/1114)).

After upgrading both `isx` and `isx-proxy`, rebuild existing Gemini templates and branch new instances.
The build removes the old login notice, including when a child template inherits Gemini, and adds its proof environment.
An existing instance from an older template keeps its login notice until rebuilt.

The tool installs `@google/gemini-cli` and writes `~/.gemini/settings.json`.
It disables telemetry, usage statistics, automatic updates and update notifications, startup tips, the banner and home-directory warnings, folder trust prompts, and the CLI's inner sandbox.
The tool writes `~/.gemini/policies/isx-auto-approve.toml` to auto-approve tool calls in normal, auto-edit, and YOLO modes.
This applies to direct `gemini` launches, headless invocations, and the default action, without extra flags.
Plan mode retains its normal policy behavior.
Gemini's shell checks can still require approval for commands outside the workspace or commands it cannot parse; `--yolo` remains available for full YOLO behavior.
The policy uses priority zero so higher-priority user rules can override it; remove this file to restore upstream approval defaults.

To pin a model, use the native Gemini model ID:

```yaml
tools:
  - gemini-cli:
      model: gemini-4-argon
```

When `gemini-cli` is installed, every template build writes the generated environment instructions to `~/.gemini/GEMINI.md`, including inherited template notes, installed tools, and cloned repositories.
This file is owned by `agentuser` and regenerated on rebuild; project `GEMINI.md` files remain separate.
The tool leaves `context.fileName` unset.

Without `model`, Gemini CLI chooses its own default.
The model parameter is reconfigurable in child templates without reinstalling the CLI.

## Bob Shell

Bob Shell is IBM's AI-powered coding assistant.
Add it to any template with `tools: [bob]`:

```yaml
name: tpl-bob-dev
description: Isolated dev environment with Bob Shell
parent: tpl-minimal
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - bob
default-action: bob
```

Bob Shell requires an IBM API key ([create one here](https://bob.ibm.com/docs/ide/account/api-keys#create-an-api-key)).
Run `isx init` to configure it — the real key stays on the host and the [MITM proxy](../README.md#credential-isolation) injects it transparently.
Containers only hold a placeholder value.
During `isx init` you'll be asked to accept the IBM license agreement; accepting pre-configures it in all containers so Bob Shell won't prompt again.
The default action launches Bob with `--auto-approve` (auto-approve all tool calls); running `bob` manually in a shell uses normal approval mode.

## GitHub Copilot CLI

Add GitHub Copilot CLI to a template with `tools: [copilot]`:

```yaml
name: tpl-copilot-dev
description: Isolated dev environment with GitHub Copilot CLI
parent: tpl-minimal
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - copilot
shell-command: copilot
```

Copilot uses the same GitHub PAT as the `gh` tool -- run `isx init` to configure your GitHub token if you haven't already.
No separate credential is needed.

## Agent Skills

Template images can declare [agent skills](https://skills.sh) to bake in at build time.
Skills are installed once into the template, at `~/.agents/skills`, the shared location any agent can read (Claude Code finds them through `~/.claude/skills`), and inherited by every instance branched from it.

```yaml
name: tpl-skills
description: Agent with security skills
parent: tpl-minimal
skills:
  repo: myorg/claude-skills      # default catalog for bare skill names
  list:
    - security-review            # short name → myorg/claude-skills@security-review
    - code-review                # short name → myorg/claude-skills@code-review
    - xixu-me/skills@xget        # explicit owner/repo@skill-name
    - myorg/catalog              # all skills from a repo
```

There is no implicit default catalog -- `repo` is only needed to resolve bare skill names (like `security-review` above).
When all entries use the fully qualified `owner/repo@skill` or `owner/repo` form, you can omit `repo` and use the list shorthand:

```yaml
skills:
  - xixu-me/skills@xget
  - myorg/catalog
```

Skill source formats:
- `owner/repo@skill-name` -- specific skill from a GitHub repo
- `owner/repo` -- all skills from a GitHub repo
- `./local-path` -- local directory (relative to where `isx build` is run)
- `skill-name` -- bare name, resolved using the `skills.repo` field (required for bare names)

To find available skills, browse [skills.sh](https://skills.sh).

## Agent Environment Context

Every build writes a short primer to `/etc/claude-code/CLAUDE.md`, so Claude Code starts each session knowing where it is: the box is disposable, `sudo` needs no password, credentials are handled by the proxy, and outward-facing actions such as opening a PR wait until asked.
It also lists the tools and repositories the template provides.
Your own `~/.claude/CLAUDE.md` and project `CLAUDE.md` files load as usual.

Templates and tools can add their own lines with `agent_note`:

```yaml
name: tpl-openjdk
parent: tpl-minimal
agent_note: |
  The build needs a boot JDK of 26/27/28. This box ships 26 at $JAVA_HOME and
  deliberately omits 25.
```

A tool's note, and any `skills` it ships, come along to every template that installs the tool.
Bare skill names in a tool resolve against the tool's own `skills.repo`:

```yaml
name: mvnd
description: Apache Maven Daemon 1.x
agent_note: |
  `mvnd` is Maven running as a resident daemon: prefer it over `mvn` for repeated
  builds in the same repo. It takes the same goals and flags as `mvn`.
skills:
  repo: myorg/catalog
  list:
    - mvnd-builds
```

### When to write a note

Notes are read in every session, so save them for things an agent would otherwise get wrong: a required flag, a version to avoid, an "obvious" fix that isn't.
Be specific:

```yaml
agent_note: |
  Run configure with CC=/usr/bin/gcc CXX=/usr/bin/g++. Without them it aborts on
  /usr/lib64/ccache/gcc being a symlink; dropping --enable-ccache "fixes" it and
  silently costs every later rebuild.
```

Some content is better placed elsewhere:

| Instead of | Use |
| --- | --- |
| `agent_note: Maven 3.9 build tool` | `description`, which is what template and tool listings show |
| `agent_note: maven, podman and jtreg are installed` | Nothing -- the generated file already lists installed tools and cloned repos |
| `agent_note: Build with 'make images', test with 'make test TEST=tier1'` | A [skill](#agent-skills), which loads on demand rather than in every session |
| `agent_note: Be careful when editing the parser` | A specific constraint, or nothing at all |
| `agent_note: Follow the Quarkus code style` | The repository's own `CLAUDE.md`, which applies only to work in that repo |

Changing a note marks the templates that use it as out of sync (`△` in the TUI); rebuild them with `isx build --out-of-sync`.
