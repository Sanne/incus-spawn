# Agents

Each agent is a tool: add it to a template's `tools:` list, and every branch of the template has it, set up for unattended work.
The agent's credentials stay on the host, where `isx init` configures them, and the [proxy](../README.md#credential-isolation) adds them to the agent's requests as they leave the instance.
The sections below cover one agent each.

An agent that uses a model server on your host, such as Ollama, reaches it once you [expose its port](CONFIGURATION.md#a-service-on-the-host) and point the agent at `host.isx.internal`.

## Claude Code

Claude Code is Anthropic's official CLI for Claude, and works with all three Claude auth modes: API key, Claude Pro/Max OAuth and Vertex AI.
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
The key or token stays on the host, and the proxy adds it to Claude Code's requests as they leave the instance.

### Parameters

| Parameter | Default | What it does |
| --- | --- | --- |
| `model` | Claude Code's own default | The model Claude Code uses |
| `effort` | Claude Code's own default | Reasoning effort: `low`, `medium`, `high` or `xhigh`; needs `model` |
| `advisor-model` | Claude Code's own default | The model Claude Code consults as its advisor, such as `opus` |
| `theme` | Claude Code's own default | Colour theme: `dark`, `light`, `dark-daltonized` or `light-daltonized` |
| `editor-mode` | Claude Code's own default | Input editor mode: `normal` or `vim` |
| `output-style` | Claude Code's own default | Response style: `Default`, `Proactive`, `Explanatory` or `Learning` |
| `attribution-commit` | Claude Code's own default | The trailer Claude Code adds to its commits, such as `Assisted-By: Claude Code <noreply@anthropic.com>` |
| `attribution-pr` | Claude Code's own default | The text Claude Code adds to its pull requests; an empty string omits it |

`model` takes a `claude-*` ID such as `claude-sonnet-5-5`, a family alias (`opus`, `sonnet`, `haiku` or `fable`), or `best`, `opusplan`, `opus[1m]`, `sonnet[1m]` or `fable[1m]`.
Each family alias follows the current model of that family.
`advisor-model` takes a `claude-*` ID, `opus`, `sonnet`, `haiku` or `fable`.
A child template can set any of these parameters again without reinstalling Claude Code.

The build writes `effort` to `~/.claude/settings.json` under `modelSettings` for the selected model, the form `/effort` writes, so `/effort` can still change it in a session.

`effort` needs `model`, because Claude Code keeps it per model, and a build with `effort` alone fails.
A child template's entry replaces the parent's parameters and does not carry `effort` over, so a child that sets `model` again must set `effort` again too.
`max` is not accepted, since Claude Code keeps it for a session only (`--effort max`).

```yaml
tools:
  - claude:
      model: claude-sonnet-5-5
```

### What the build sets up

The build gives Claude Code bypass-permissions mode with every tool allowed, and turns off auto-update, error reporting, the feedback survey and its own sandbox.
It leaves Claude Code's telemetry on, because turning it off also turns off the advisor.

It accepts the onboarding and the trust prompt for the home directory and for each repository the template clones, so the first session starts without questions.

The footer shows `Running unconstrained in isx [version] [container name]`.
The default action (`default-action: claude` above) resumes your most recent session, in that session's directory.

## Codex CLI

Codex CLI is OpenAI's coding agent, and signs in with an OpenAI API key or, experimentally, a ChatGPT plan.
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

To sign Codex in with an OpenAI API key, [create one here](https://platform.openai.com/api-keys).
API usage needs billing credits, even on free accounts.
Run `isx init` to configure the key: it stays on the host, and the proxy adds it to Codex's requests as they leave the instance.

### A ChatGPT plan, experimental

With a ChatGPT plan, you can sign Codex in with ChatGPT instead of an API key.
Run `isx account add openai` on the host, choose one of the ChatGPT options, and answer yes after its notice.
The notice says what the next three paragraphs say, so you can read it before you run `isx init`.

The sign-in is experimental, and it may stop working when Codex or OpenAI changes.
isx signs in as Codex's own client, which OpenAI has not endorsed.

The sign-in and its tokens stay on the host, which also renews them.
Your instances never see them: Codex there holds placeholders, which the proxy swaps for the real tokens.

The browser sign-in has been tested with a real ChatGPT Enterprise plan.
The sign-in with a code on another device has not been tested with OpenAI yet.

### Parameters

By default Codex picks both the model and the reasoning effort.

| Parameter | Default | What it does |
| --- | --- | --- |
| `model` | Codex picks | Any model your OpenAI account can use ([model list](https://platform.openai.com/docs/models)) |
| `effort` | Codex picks | Reasoning effort: `minimal`, `low`, `medium`, `high` or `xhigh` |

```yaml
tools:
  - codex:
      model: gpt-6-sol
      effort: high
```

### What the build sets up

The build configures Codex for unattended use, turns off its update check and marks the home directory and each repository the template clones as trusted.
The build also gives Codex the [environment primer](#agent-environment-context), a short text that tells an agent about its instance, and does not write `~/.codex/AGENTS.md`.
The default action resumes your most recent session.

The footer shows the model, the current directory and `Running unconstrained in isx [version] [container name]`.
That message is the name of a permission profile with full filesystem and network access.
The build also turns on live web search.

The version and instance name refresh at login, including in a new branch of a template.
Use `/statusline` to customize the footer; the `permissions` item displays the isx message.

## Pi Coding Agent

Pi is a provider-agnostic CLI coding agent.
Its default provider is `anthropic`, so it uses your Claude account: an API key, Claude Pro/Max OAuth or Vertex AI.
A template with Pi alone needs a Claude account unless it sets Pi's `provider` parameter, described below.
`isx init` asks a Pi user which provider to use, and writes it into the first template.

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

The credential stays on the host, and the proxy adds it to Pi's requests as they leave the instance.
To use Pi without making it the default shell, omit `shell-command` and launch it manually after `isx shell`.
With `default-action: pi` instead, the instance opens Pi resuming your most recent session, in that session's directory.

Every template build puts the [environment primer](#agent-environment-context) at `/AGENTS.md`, which Pi loads in every directory.
Pi gets this file whether or not the template also installs Claude Code.
Your own `~/.pi/agent/AGENTS.md` and project `AGENTS.md` files load as usual; isx never writes them.

Pi's footer adds `Running unconstrained in isx [version] [container name]` below the model and directory.
It comes from an extension at `/etc/isx/pi/isx-status.ts`, loaded by the `extensions` entry isx writes in `~/.pi/agent/settings.json`.
The version and instance name are read when Pi starts, so a branch shows its own name.
Your own extensions in `~/.pi/agent/extensions/` load as usual.

### Parameters

| Parameter | Default | What it does |
| --- | --- | --- |
| `provider` | `anthropic` | The API Pi talks to; also accepts `openai`, `google`, `google-vertex` or `vertex` |
| `model` | `claude-sonnet-5-5` for `anthropic`, otherwise Pi's own | The model Pi uses |
| `effort` | Pi's own default | Thinking level: `off`, `minimal`, `low`, `medium`, `high` or `xhigh` |

The `openai` provider uses your OpenAI key, as shown below.
A local model needs no credential, and a [proxy tool](TOOLS.md#proxy-credentials) keeps another gateway's key on the host.
A model ID may hold a `/`, as a gateway names its models, such as `zai-org/glm-5-3`.

The `google` provider is the Gemini API, and uses your Gemini key, as [Gemini CLI](#gemini-cli) does.
The `google-vertex` provider runs Gemini models on Vertex AI, and needs a Vertex AI Claude account.
isx also accepts `vertex` as a name for this provider, and writes `google-vertex`, the name Pi knows, in Pi's settings.

On Vertex AI, Pi sends its calls with an API key that is a proof: a token isx gives the instance in place of the credential.
The proxy checks the proof and moves each call into your account's project and region.

The proxy sends only the request fields Pi uses for a model call, and refuses any other request with a 403 that names the field.
So a cached context, a search tool or a Cloud Storage file in a request is refused, since these could read data your Google account can reach.

The proxy signs the call with the host's GCP credentials, which never enter the instance.
It uses those credentials only for Gemini model calls, and refuses any other Vertex AI call that carries the proof.

Pi can also run against OpenAI, using the same key as [Codex CLI](#codex-cli):

```yaml
tools:
  - pi:
      provider: openai
      model: gpt-6-luna
```

## opencode

[opencode](https://opencode.ai) is a provider-agnostic CLI coding agent.
Like Pi, its default provider is `anthropic`, so it uses your Claude account: an API key, Claude Pro/Max OAuth or Vertex AI.
As for Pi, `isx init` asks which provider opencode should use.
Anthropic bills a Claude Pro or Max subscription used from opencode as extra usage, since opencode is not Claude Code.

Add it to any template with `tools: [opencode]`:

```yaml
name: tpl-opencode-dev
parent: tpl-minimal
tools:
  - opencode
default-action: opencode
```

The default action opens opencode in the directory of your most recent session, and continues that session.
The credential stays on the host, and the proxy adds it to opencode's requests as they leave the instance.
opencode reads a proof from `OPENCODE_ANTHROPIC_API_KEY`, which its settings name, so Claude Code's own sign-in is left alone.

### What the build sets up

isx writes `~/.config/opencode/opencode.json`, which pins the model and turns off update checks and session sharing.
It also allows what opencode would otherwise ask about: reading `.env` files, directories outside the project, and repeating a tool call.
opencode's plan agent still cannot edit files.
The settings load the [environment primer](#agent-environment-context) from `/AGENTS.md`, the file Pi reads.

Skills isx installs in `~/.agents/skills` load in opencode as they do in Claude Code.
Your own `~/.config/opencode/AGENTS.md` and project `AGENTS.md` files load as usual.
Each build writes `opencode.json` again, so keep your own settings in a project `opencode.json`.
opencode shows no isx footer: it has no place for one.

### Parameters

| Parameter | Default | What it does |
| --- | --- | --- |
| `provider` | `anthropic` | The API opencode talks to; also accepts `openai` and `google` |
| `model` | `claude-sonnet-5-5` for `anthropic`, otherwise opencode's own | The model opencode uses |
| `small-model` | opencode's own | The model for small tasks such as session titles; setting `model` does not change it |
| `effort` | The model's own default | Reasoning effort: `none`, `minimal`, `low`, `medium`, `high`, `xhigh` or `max`; needs `model`, except with `anthropic` |

The `openai` provider uses your OpenAI key, as [Codex CLI](#codex-cli) does.
The `google` provider is the Gemini API, and uses your Gemini key, as [Gemini CLI](#gemini-cli) does.
A local model needs no credential, and a [proxy tool](TOOLS.md#proxy-credentials) keeps another gateway's key on the host.
A model ID may hold a `/`, as a gateway names its models, such as `zai-org/glm-5-3`.

The build writes `effort` as the variant opencode's build and plan agents start with, which `ctrl+t` still changes in a session.
Which variants exist depends on the model, and opencode ignores one the model does not have.
The variant applies while an agent runs the template's model, so a model you pick in a session starts at its own default.

```yaml
tools:
  - opencode:
      provider: openai
      model: gpt-6-luna
```

## Gemini CLI

Google's [Gemini CLI](https://geminicli.com/docs/get-started/installation/) signs in with a [Gemini API key](https://aistudio.google.com/apikey).
Add it to a template with `tools: [gemini-cli]`:

```yaml
name: tpl-gemini-dev
parent: tpl-minimal
tools:
  - gemini-cli
default-action: gemini-cli
```

Run `isx init` on the host and select Gemini CLI to configure the key.
isx stores keys in host-side `gemini.accounts.<name>.apiKey`, with `gemini.default` selecting the default account.
Use `accounts: { gemini: work }` in a template or `isx branch --from tpl-gemini-dev --account gemini=work my-gemini` to select a named account.

The instance receives a `GEMINI_API_KEY` proof placeholder.
The proxy verifies it and adds the selected key to requests to `generativelanguage.googleapis.com` as they leave the instance.
Gemini CLI reads the placeholder when launched, so no API key or proof is saved in its settings.

This integration supports Gemini API keys; Google OAuth is not supported.
Gemini models on Vertex AI are served through [Pi](#pi-coding-agent) instead.

After upgrading both `isx` and `isx-proxy`, rebuild existing Gemini templates and branch new instances.
An instance from an older template keeps a login notice until its template is rebuilt.
The build removes that notice, including when a child template inherits Gemini, and adds the proof environment.

### Parameters

| Parameter | Default | What it does |
| --- | --- | --- |
| `model` | Gemini CLI's own default | The model, as a native Gemini model ID |
| `effort` | The model's own default | Thinking level: `minimal`, `low`, `medium`, `high` or `xhigh`; needs `model` |

A child template can set `model` and `effort` again without reinstalling the CLI.
The child's own entry replaces the parent's, so a child that sets `effort` sets `model` beside it.

The build writes `effort` to `~/.gemini/settings.json` as the thinking level of requests to that model.
Which levels a model takes depends on the model, and the Gemini API refuses a request with one it does not.

```yaml
tools:
  - gemini-cli:
      model: gemini-4-argon
```

### What the build sets up

The tool installs `@google/gemini-cli` and writes `~/.gemini/settings.json`, which disables:

- telemetry and usage statistics;
- automatic updates and update notifications;
- startup tips, the banner and home-directory warnings;
- folder trust prompts;
- the CLI's inner sandbox.

The tool also writes `~/.gemini/policies/isx-auto-approve.toml`, which auto-approves tool calls in normal, auto-edit and YOLO modes.
The policy applies to direct `gemini` launches, headless invocations and the default action, without extra flags.
Plan mode retains its normal policy behavior.

Gemini's shell checks can still require approval for commands outside the workspace or commands it cannot parse; `--yolo` remains available for full YOLO behavior.

The policy uses priority zero, so higher-priority user rules can override it.
Remove the file to restore upstream approval defaults.

When `gemini-cli` is installed, every template build writes the [environment primer](#agent-environment-context) to `~/.gemini/extensions/isx/GEMINI.md`, including inherited template notes, installed tools and cloned repositories.
That file belongs to an extension named `isx`, which Gemini CLI loads beside your own `~/.gemini/GEMINI.md`.
The build never writes your file, and project `GEMINI.md` files remain separate.

The extension file is owned by `agentuser` and regenerated on rebuild.
A rebuild also removes the copy an older build left in `~/.gemini/GEMINI.md`, unless you changed its first line.
Launching with `--extensions` naming other extensions leaves the primer out.
The tool leaves `context.fileName` unset.

## Antigravity CLI

Google's [Antigravity CLI](https://antigravity.google/docs/cli/reference/), `agy`, is the terminal agent that replaced Gemini CLI for Google's personal plans.
isx runs it on the `gemini` account you configured in `isx init`: a [Gemini API key](https://aistudio.google.com/apikey), or a Google account for an AI Pro or AI Ultra plan.
Add it to a template with `tools: [antigravity]`:

```yaml
name: tpl-antigravity-dev
parent: tpl-minimal
tools:
  - antigravity
default-action: antigravity
```

The default action resumes your most recent conversation, in a directory where you used it.

With an API key account, the instance gets the same `GEMINI_API_KEY` proof placeholder as Gemini CLI.
`agy` sends it to `generativelanguage.googleapis.com`, where the proxy checks it and adds the selected key.
Select a named account as for Gemini CLI, with `accounts: { gemini: work }` or `--account gemini=work`.

### Signing in with a Google account

Choose "Google account" for Gemini in `isx init` to let an AI Pro or AI Ultra plan pay for `agy`.
The account holds no key, and `isx init` saves it as `type: google`.
The sign-in happens the first time `agy` runs in an instance on that account.

Start `agy` in the instance, from `isx shell`, and it prints a Google sign-in link in that terminal.
Open it on this machine, sign in, and paste the code the page shows back into `agy`.
The proxy keeps the session on the host, and the instance holds only placeholders.

#### What the instance holds

Your Google password never reaches the instance, because you sign in through a browser on the host.
Google's tokens stay on the host, in `~/.config/incus-spawn/gemini/<account>/session.json`, which only the proxy reads.
The instance holds look-alikes starting with `ya29.isx_` and `1//isx_`, which Google refuses.
The proxy puts the real token in each request to Google.

To check, run `cat ~/.gemini/antigravity-cli/antigravity-oauth-token` in the instance.
A token without `isx_` in it is a real one, so report it.

The code you paste is the one secret that passes through the instance, and the proxy spends it within seconds.
In full network mode, a hostile program in the instance could catch your paste and spend it first, for real tokens.
A `--proxy-only` instance reaches Google only through the proxy, so sign in from one when that matters.

Sign in only from an instance on the Google account.
On Linux, the proxy does not keep a sign-in made on an API key account, so Google's real tokens would stay in that instance.

#### Changing an instance's account

`isx account set <instance> gemini=<account>` moves `agy` between an API key account and a Google account in place.
Then quit `agy` and start it again from a new `isx shell`, since it reads its settings and its sign-in when it starts.
`isx account set` says so when a running instance has `agy`.

If `agy` still sends API key requests on a Google account, the proxy refuses them, says why, and sends nothing to Google.
If that refusal repeats after a restart, rebuild the template.

#### Sharing and signing out

Every instance on that account shares the session, and a sign-in again from any of them replaces it.
`isx account list` shows which Google account is signed in.
In `agy`, `/logout` forgets the sign-in in that instance only, and the next login shell signs it back in.

This integration was built against a recording of what `agy` sends, not against a Google account.
Treat it as experimental and report what you see.

On macOS the proxy cannot tell instances apart by address, so it keeps a sign-in only for the instance that just asked for one.
Start `agy` from a login shell in the instance.

For the next few minutes the proxy takes a Google sign-in completed from any instance as that one's.
Sign in from one instance at a time.

After `/logout` in `agy`, open a new login shell before you sign in again.
While a Google account is configured, an instance on a Gemini API key cannot sign `agy` in to Google on macOS.

Gemini CLI, Pi and opencode use API keys only, so an instance on a Google account gives them no Gemini credential.
For Pi and opencode, `isx branch` reports that before it creates the instance.
The Antigravity desktop app and IDE are not supported.

### A free-tier key has no Pro quota

`agy` starts on Gemini 3.1 Pro unless you choose another model.
A Gemini API key on Google's free tier has no quota for that model, so Google answers 429 `RESOURCE_EXHAUSTED` ("free tier ... limit: 0") to the first prompt.
The key is accepted: only the model is out of reach.

Give the template a Flash model with the tool's `model` parameter, and `agy` starts on it.
`agy models` lists the IDs it takes.

```yaml
tools:
  - antigravity:
      model: gemini-4-argon
      effort: medium
```

`effort` is `low`, `medium`, `high`, `xhigh` or `max`.
Both are optional and can be changed in a child template.

The `Antigravity CLI` action and tasks that `isx mcp` delegates to `agy` use them, and a task's own `model` wins.
A plain `agy` typed at the prompt does not, so pass `--model` there.

isx does not write the model into `agy`'s `settings.json`, since `agy` keeps its saved model by label, not by the ID that `--model` takes.
A paid key or a Google account for an AI Pro or AI Ultra plan has the Pro quota, so it needs no model.

### What the build sets up

The tool installs a pinned `agy` release from Google's download bucket, checked against its SHA-256, and links it as `/usr/local/bin/agy`.
It sets `AGY_CLI_DISABLE_AUTO_UPDATE`, so `agy` never replaces the pinned release itself; a new release comes with a new isx.
It writes these settings into `~/.gemini/antigravity-cli/settings.json`, keeping any others there:

- `modelProvider: gemini`, the Gemini API key mode, when the account is an API key; with a Google account the key is left out, so `agy` signs in;
- every tool call and artifact proceeding without a question, with files outside the workspace reachable;
- no terminal sandbox, which would need a container inside the instance;
- your home folder and each repository the template clones trusted, so `agy` asks once for each other folder you start it in;
- no tips and no feedback surveys.

It also marks the first-run questions as answered.
It installs Node.js as well, which `isx mcp` needs to hand a task to `agy` ([MCP](MCP.md)).

With a Google account it writes a login script that keeps `agy`'s token file holding the instance's placeholders.
No sign-in token is ever stored in the instance.
Every build writes the [environment primer](#agent-environment-context) to `~/.gemini/config/rules/isx.md`, a rule `agy` always loads.
Your own `~/.gemini/GEMINI.md` and `AGENTS.md` stay yours.

[Skills](#agent-skills) reach `agy` through `~/.gemini/config/skills`, a link to `~/.agents/skills`.
Skills already in a folder there are copied into `~/.agents/skills` first, unless isx installs a skill of the same name.

MCP servers that tools declare go to `~/.gemini/config/mcp_config.json`.
`agy` reads no variable in that file.
A server that takes its token from `bearer-token-env` goes through a stdio bridge, which reads the variable when agy starts it.

`agy` also reports usage to Google's logging service at `play.googleapis.com`, which isx does not turn off.
That traffic carries no credential, since the instance holds only the proof placeholder.

## Bob Shell

Bob Shell is IBM's AI-powered coding assistant, and signs in with an IBM API key ([create one here](https://bob.ibm.com/docs/ide/account/api-keys#create-an-api-key)).
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

Run `isx init` to configure the key: it stays on the host, and the proxy adds it to Bob's requests as they leave the instance.
An instance holds only a placeholder value.

During `isx init` you are asked to accept the IBM license agreement.
When you accept, isx configures the acceptance in every instance, so Bob Shell does not prompt again.

The default action resumes your most recent task with `--auto-approve`, which auto-approves all tool calls; running `bob` manually in a shell uses normal approval mode.
The build trusts the home directory and turns off auto-update and the update reminder.

### Parameters

| Parameter | Default | What it does |
| --- | --- | --- |
| `max-session-turns` | Bob's own default (100 in 2.1) | A positive integer limits turns per session. Use `0` for unlimited turns |
| `compression-threshold` | Bob's own default (0.9 in 2.1) | The share of the context window, `0.0` to `1.0`, at which Bob compresses the conversation |

Replace negative `max-session-turns` with `0` for unlimited turns.
Remove `checkpointing` from existing Bob tool parameters because Bob Shell 2.1 does not support it.

A child template can set the turn limit and compression threshold without reinstalling Bob.
If you pass `--max-turns` to Bob, it takes precedence for that session.

## GitHub Copilot CLI

GitHub Copilot CLI signs in with the same GitHub token as the `gh` tool; no separate credential is needed.

A fine-grained token also needs the Copilot Requests permission, which the pre-filled token page of `isx init` and `isx account add github` does not add.
Copilot CLI ignores a classic token, so a `gh` login's token works when `gh` signed in through the browser, and not when it was given a classic token.

Add Copilot CLI to a template with `tools: [copilot]`:

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

Run `isx init` to configure your GitHub token if you have not already.
The build auto-approves every tool call, in interactive and headless runs alike, the same as `--yolo`.

### Parameters

By default Copilot picks the model, the reasoning effort and the context window tier.

| Parameter | Default | What it does |
| --- | --- | --- |
| `model` | Copilot picks | Any model your Copilot plan offers, such as `claude-opus-5.5`, or `auto` |
| `effort` | Copilot picks | Reasoning effort: `none`, `minimal`, `low`, `medium`, `high`, `xhigh` or `max`; the levels a model supports vary |
| `context` | Copilot picks | Context window tier: `default` or `long_context`, for models that offer one |

```yaml
tools:
  - copilot:
      model: claude-opus-5.5
      effort: medium
      context: long_context
```

The build writes these parameters to Copilot's `~/.copilot/settings.json` as `model`, `effortLevel` and `contextTier`, and a child template can set any of them again without reinstalling Copilot.
The same file always holds `defaultPermissionMode: allow-all`, so interactive sessions start in Allow All.

## Agent Skills

A template can declare [agent skills](https://skills.sh) for the build to install.
Skills are installed once into the template, at `~/.agents/skills`, and inherited by every instance branched from it.
Codex, Pi, opencode, Gemini CLI and Copilot read that directory themselves.
Claude Code finds it through `~/.claude/skills`, and `agy` through `~/.gemini/config/skills`.

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

There is no implicit default catalog: `repo` is only needed to resolve bare skill names, such as `security-review` above.
When all entries use the fully qualified `owner/repo@skill` or `owner/repo` form, you can omit `repo` and use the list shorthand:

```yaml
skills:
  - xixu-me/skills@xget
  - myorg/catalog
```

Skill source formats:

| Form | What it selects |
| --- | --- |
| `owner/repo@skill-name` | One skill from a GitHub repo |
| `owner/repo` | All skills from a GitHub repo |
| `https://github.com/owner/repo` | The same as `owner/repo`, with `@skill-name` accepted too |
| `./local-path` | A local directory, relative to where `isx build` runs |
| `/absolute/path` | A local directory, by absolute path |
| `skill-name` | A bare name, resolved against the `skills.repo` field, which bare names require |

A local directory that holds a `SKILL.md` is one skill, named after the directory; otherwise each subdirectory with a `SKILL.md` is one.
From a GitHub repo, every top-level `<name>/SKILL.md` on `main` or `master` is a skill.
Only the `SKILL.md` is installed; isx caches the GitHub ones on the host at `~/.cache/incus-spawn/skills/`.

A template or tool in a project's `.incus-spawn/` directory reads a relative local path from the project's root.
It may name a local directory only inside its project, and the build fails otherwise.

### Skills pinned to a release

A skill describes a tool's commands, so it should come from the same release as the tool's binary.
Set `ref` to that release's tag and `commit` to the commit the tag names, and the bare names in `list` are read at that commit.

Each one is then installed as its whole directory, so a `references/` folder or a script beside `SKILL.md` arrives too.
`path` names the directory that holds the skills, when it is not the repository's root.
An empty `list` installs every skill under `path`.

```yaml
skills:
  repo: rgctl/rgctl
  ref: v0.4.29
  commit: ab6833d6f9676e22e0dfa059324cd487203cb922
  path: skills
  list: [rgctl, rgctl-review]
```

Take the commit from the publisher's repository, never from a build log:

```shell
git ls-remote https://github.com/rgctl/rgctl 'refs/tags/v0.4.29^{}'
```

For a lightweight tag, which has no `^{}` line, take the plain line.
If the tag later names another commit, a build that fetches the release fails rather than install what it now holds.

isx reads each file at the commit and checks it against the commit's own tree.
It refuses a skill that holds a symlink or a submodule.
A pinned release is cached on the host and not fetched again.
When a template names one skill from two different pins, the build fails.

To find available skills, browse [skills.sh](https://skills.sh).

## Agent Environment Context

Every build writes a short primer for the agents the template installs, so each starts its sessions knowing where it is.
The primer says that the instance is disposable, `sudo` needs no password, the proxy handles credentials, `isx-trust` fixes a runtime's own CA bundle, and outward-facing actions such as opening a PR wait until asked.

It also lists the tools and repositories the template provides.

The build stores it once, in `/etc/isx/agent-instructions.md`.
With Claude Code it appears at `/etc/claude-code/CLAUDE.md`, and with Pi or opencode at `/AGENTS.md`, both links to that one file.
With Gemini CLI, the `isx` extension's `~/.gemini/extensions/isx/GEMINI.md` gets a copy you own.

Editing one of the links changes the stored file, so both agents read the edit, and the next build replaces every copy of the primer.

With Codex CLI, `/etc/codex/requirements.toml` holds the primer as managed developer instructions, which Codex adds to your own.
A template that ships its own `requirements.toml` keeps it, so Codex does not get the primer, and the build warns you of that.

Your own `~/.claude/CLAUDE.md` and project `CLAUDE.md` files load as usual.

Templates and tools can add their own lines to the primer with `agent_note`:

```yaml
name: tpl-openjdk
parent: tpl-minimal
agent_note: |
  The build needs a boot JDK of 26/27/28. This box ships 26 at $JAVA_HOME and
  deliberately omits 25.
```

A tool's note, and any `skills` it ships, are added to every template that installs the tool.
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

The agent reads its notes in every session, so write a note only for things the agent would otherwise get wrong: a required flag, a version to avoid, a fix that looks obvious and is wrong.
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
| `agent_note: maven, podman and jtreg are installed` | Nothing: the primer already lists installed tools and cloned repos |
| `agent_note: Build with 'make images', test with 'make test TEST=tier1'` | A [skill](#agent-skills), which loads on demand rather than in every session |
| `agent_note: Be careful when editing the parser` | A specific constraint, or nothing at all |
| `agent_note: Follow the Quarkus code style` | The repository's own `CLAUDE.md`, which applies only to work in that repo |

Changing a note marks the templates that use it as out of sync (`△` in the TUI); rebuild them with `isx build --out-of-sync`.
