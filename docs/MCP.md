# Delegating from an agent on your host (MCP)

An agent on your host, such as Claude Code, can use isx itself: create disposable instances from templates you approved, run builds and tests in them, and hand whole tasks to Claude Code or Codex inside an instance.
`isx mcp` serves the [Model Context Protocol](https://modelcontextprotocol.io) over stdio.
This page covers setup, the tools, and routes to a program of your own.

## Setting it up

`isx init` offers to set this up; it is experimental, so off by default.
It registers `isx mcp` with Claude Code for all projects and asks, for each template that installs Claude Code or Codex, whether agents may use it.

Once it is set up, a re-run of `isx init` asks one question instead: `t` to change the approved templates, `n` to see how to turn it off, Enter to keep everything.

To register it with Claude Code by hand instead:

```shell
claude mcp add --scope user isx -- ~/.local/bin/isx mcp
```

## Approving templates: `mcp.yaml`

Nothing is available until you approve templates in `~/.config/incus-spawn/mcp.yaml`, a file of its own beside `config.yaml`.
Agents never can: `isx mcp` reads this file on every call and has no way to write it.

```yaml
templates: [tpl-java, tpl-dev]   # the only templates an agent may branch from
max-instances: 8                 # per host user, across sessions (default 8)
max-concurrent-tasks: 8          # background commands and delegated agents, per host user, across sessions (default 8)
# delegate-max-turns: 200        # optional Claude turn ceiling; Codex is refused while this is set
delegate-permission-mode: bypassPermissions   # Claude's --permission-mode (default shown)
delegate-permission-modes:       # per-template overrides, e.g. a reviewer that only plans
  tpl-review: plan
codex:
  sandbox-mode: danger-full-access  # Codex always runs with approval_policy=never
  sandbox-modes:
    tpl-codex-review: read-only
orphan-grace-hours: 24           # how long an instance outlives its session (default 24)
dormant-after-hours: 24          # past that, one kept only for a delegate showing no activity
                                 # this long is stopped instead (default 24)
dormant-grace-hours: 168         # and destroyed this long after (default 168)
summary-model: haiku             # answers the tools' `ask` inside the instance (default haiku)
```

| Key | Default | What it does |
|-----|---------|--------------|
| `templates` | none | The only templates an agent may branch from. |
| `max-instances` | 8 | Instances per host user, across sessions. |
| `max-concurrent-tasks` | 8 | Background commands and delegated agents, per host user, across sessions. |
| `delegate-max-turns` | not set | A ceiling on Claude's turns. Codex is refused while this is set. |
| `delegate-permission-mode` | `bypassPermissions` | Claude's `--permission-mode`. |
| `delegate-permission-modes` | not set | Per-template overrides, such as a reviewer that only plans. |
| `codex.sandbox-mode` | `danger-full-access` | Codex's sandbox mode. Codex always runs with `approval_policy=never`. |
| `codex.sandbox-modes` | not set | Per-template Codex sandbox modes. |
| `orphan-grace-hours` | 24 | How long an instance outlives its session. |
| `dormant-after-hours` | 24 | Past the orphan grace, an instance kept only for a delegate that shows no activity this long is stopped instead. |
| `dormant-grace-hours` | 168 | How long after that stop the instance is destroyed. |
| `summary-model` | `haiku` | The model that answers the tools' `ask` inside the instance. |

A missing or empty file approves nothing.
A file `isx mcp` cannot use refuses every call that depends on it, naming `mcp.yaml` and what is wrong, until you fix it.
Unusable means unreadable, malformed, an unknown key or a value it does not accept.
A change takes effect on the next call, with no restart.

`max-instances` and `max-concurrent-tasks` are the two knobs to raise for a controller, one session running many tasks at once.
They are a safety net against runaway creation, not a budget: the only cost of a high number is host memory.
`list_templates` reports both, under the same names (`max_instances`, `max_concurrent_tasks`).

### If `config.yaml` has an `mcp:` section

The `mcp:` section of `config.yaml` is not read, not even as a fallback.
If you have one, move its contents into `mcp.yaml` and drop the `mcp:` line, un-indenting what was under it.

Until `mcp.yaml` exists, that section makes `isx mcp` refuse everything and `isx init` leave the step alone, so its limits are never quietly replaced by the defaults.
Once `mcp.yaml` exists, both only remind you to delete the old section.

## The tools

An agent gets these tools, and no others.

| Tool | What it does | What limits it |
|------|--------------|----------------|
| `list_templates` | The approved templates: their `delegate_agents`, default agent, model and permissions, capability differences and policy refusals. Also `max_instances` and `max_concurrent_tasks`. | Only templates listed in `mcp.yaml`. |
| `create_instance` | A fresh copy-on-write branch of an approved template, as `isx branch` would make it, with an optional `purpose` and `name_hint`. With `from_instance`, a fork of one of your stopped instances: prepare once, fork N times. | `max-instances`; the source of a fork must be stopped. |
| `stop_instance` / `start_instance` | Stop an instance, to fork it, and start it again. | Stop is refused while a task runs, unless `force`. |
| `list_instances` / `adopt_instance` | Your instances and their tasks, including those an ended session left behind; take one back. | Your instances only. |
| `exec` | Run a command as `agentuser`, or with `background: true` as a task. | No time limit unless the agent sets one; `max-concurrent-tasks` for a task. |
| `delegate` | Give an instruction or installed skill to an instance, or to a fresh one from a template, with `purpose` and `name_hint` for a fresh one. | `max-concurrent-tasks`, `delegate-max-turns` and the permission modes in `mcp.yaml`. |
| `task_status` / `wait_any` / `task_result` | Follow a task, optionally waiting; wait for whichever of several finishes first; read its outcome or the agent's report. | Your instances only. |
| `send_message` | Continue a delegated agent's conversation, for example "push and open a PR". | Refused while you are in that conversation. |
| `get_diff` | What a delegated task changed, committed or not: a patch, or with `stat` only the files and line counts. | Your instances only. |
| `instance_activity` | What the proxy saw of an instance's Claude model calls: made and in flight, when the last one ended, tokens spent; and of all its traffic (`traffic_*`). | Reads the proxy's counts, never the instance. |
| `cancel_task` / `destroy_instance` | Stop a task, or throw an instance away. | Your instances only. |
| `keep_instance` | Hand an instance over to you for good. | Your instances only. |

Every tool returns its facts as `structuredContent` matching the `outputSchema` it lists, so a program need not parse the text.
A refusal says why in `_meta["dev.incusspawn/error"].code`.

A task's `state` is the same word in every tool: `running`, `finished`, `attached` (finished, and a person is in its conversation), `lost` or `unknown`.
`list_instances` gives it as the session last saw it, so `unknown` there means nothing has read how the task ended yet, as after `cancel_task`.
`task_status` reads it.

### Delegating to Claude Code or Codex

`delegate` runs Claude Code or Codex inside the instance; this is how an agent picks one.

- `list_templates` reports each template's `delegate_agents` and `default_delegate_agent`.
- Select an installed agent explicitly with `agent: "claude"` or `agent: "codex"` on `delegate`, optionally with `model` and the Claude-only `max_turns`.
  When `agent` is omitted, Claude is selected if installed, otherwise Codex.
- `send_message` keeps the task's agent and resumes its exact saved conversation.
- The coordinator may be Claude Code regardless of which agent the worker runs.

Each descriptor reports `supports_max_turns`, `supports_model_preflight`, its configured model and permission settings, and a `refusal` if current host policy prevents launch.
`supports_ask` and `summary_agent` describe the separate summary service.

Models and permissions come from the selected agent's configuration; a task's `model` override persists across follow-ups and adoption.
Claude validates a chosen model against its account before launching; Codex reports model-access errors in the task result.

Codex differs from Claude in these ways:

- Codex has no equivalent to Claude's `max_turns`: an explicit argument is rejected, and a configured `delegate-max-turns` in `mcp.yaml` prevents Codex delegation and continuation.
  Revise that policy to use Codex.
- `ask` requires Claude's tool-free summarizer and is refused on Codex-only instances before its command runs.
  Ordinary output, reports and diffs work with either agent.
- Codex uses the template's `openai` credential account and always runs without approval prompts.
- If a template has a restrictive Claude permission policy, configure its Codex sandbox mode explicitly before selecting Codex.
  One restricted in `delegate-permission-modes` needs its own `codex.sandbox-modes` entry, as the section-wide `sandbox-mode` does not apply to it.
- The proxy's `model_usage_providers` lists Anthropic.
  OpenAI traffic is included in `traffic_*`, and Codex reports task token usage when available.

After upgrading isx, restart existing MCP server connections to enable Codex workers.
Older isx servers cannot manage the new Codex task records; keep outstanding Codex instances before downgrading.

### Being told when a task changes

A client that drives `isx mcp` from code rather than from a model can ask to be told instead of waiting.
List `isx/task_changed` under `capabilities.experimental` in `initialize`.
Every change of a task's state then arrives as a `notifications/isx/task_changed`, carrying `task_id`, `instance`, `run`, `state` and, once finished, `exit_code`.
The `state` there is `running`, `finished`, `lost`, `attached` or `released`.

Clients that do not ask are not polled for it.

### Retrying safely after a crash

A controller that recovers from a crash by re-running what it has no result for can make that safe.
`create_instance`, `delegate` and `exec` with `background: true` take an optional `idempotency_key` (1 to 64 characters of `A-Za-z0-9._:-`).

A call whose key already made something returns that instance or task, with `replayed: true`, instead of making a second one.
This holds across sessions too: an orphan made under the key is adopted.

The key lives exactly as long as what it made.
A repeat must ask for the same thing (template or source, instance, kind of task).

One that asks for something else is refused, never redirected, so a controller that picks the instance at run time records that choice with its intent.
One outcome needs looking up rather than retrying: a key whose instance was kept with `keep_instance` is refused.

### Telling working from stuck

`instance_activity` answers from the host proxy's view, without touching the instance.
A call in flight means the agent is working.
A growing `idle_seconds` means it is stuck or finished without reporting.
An agent with no model call in flight but a build pulling dependencies is still working, which the traffic counts show.

| Field | What it holds |
|-------|---------------|
| `input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens` | Tokens spent, as the API reports them, from `counting_since`. |
| `counting_since` | When the proxy began counting that instance. Absent while the proxy does not know the instance yet. |
| `traffic_*` | Every request the instance made through the proxy from the same `counting_since`, model calls included: package downloads, git, WebSockets (in flight while open). |

For what a task spent, read before and after it and subtract, but only when both reads carry the same `counting_since`.
A different or missing one means the counts started afresh in between, because the proxy restarted, and the difference is not the task's.
`isx proxy activity` shows the same counts for every instance on the host.

### Asking a question instead of reading the output

`exec`, `task_result` and `get_diff` take an optional `ask`.
Instead of returning the text, a one-shot Claude Code on `summary-model` reads it inside the instance and answers the question, such as "which tests fail?", so a long log or patch never fills the host agent's context.

The answer is a model's reading, untrusted and lossy, so anything that gates a merge stays deterministic: `get_diff(stat)`, CI, a reviewer.

## What an agent gets

What an agent gets is deliberately narrow.

- **Only approved templates.**
  Never a project-local (`.incus-spawn/`) definition or an image built from one, and never an unbuilt one: the agent is told to ask you to run `isx build`.
- **Template defaults, no choices.**
  Network mode, credential accounts, resources: exactly what `isx branch --from <template>` gives.
  Credentials stay in the host proxy as always; which GitHub account a delegate can push with is the one you pinned on the template.
- **Your instances, one session at a time.**
  Every instance is stamped with your host user, an optional purpose (`#870 implement`), and the session holding it; every tool checks that stamp.
- **Taking over is safe.**
  You can join a delegate's conversation yourself with `isx shell <instance> --task`, as described below.
- **No host access.**
  Nothing runs on the host and no host files are read or written: results come back as text, and `get_diff` returns a patch rather than touching your checkout.
- **An audit trail.**
  Every call is logged, with credentials scrubbed, to `~/.local/state/incus-spawn/mcp.log`.

### Orphans and adoption

Instances outlive their session, because a coordinating agent restarts while its workers wait.
When a session ends or dies, its instances become orphans that any later session of yours can take back with `adopt_instance`, tasks included.

An orphan nobody adopts is destroyed by the next `isx mcp` to start once `orphan-grace-hours` have passed.
It is never destroyed while someone is in it with `isx shell` or an agent it delegated to is still working, and never once handed to you with `keep_instance`.

### Names and the `MCP` column

Instances are named `mcp-<template>-<hint>-<suffix>`.
The hint is the `name_hint` an agent gave `create_instance` or `delegate` (`870-impl`; `task` for a delegate without one).
Do not give your own instances `mcp-*` names.

`isx list` adds an `MCP` column when there are any, showing each instance's state and purpose (`held: #870 implement`, `orphaned`, `kept: spike`).
The TUI shows the same in an instance's details (F3), with who holds it, and has no column for it.

### Joining a delegate's conversation

`isx shell <instance> --task` resumes a delegate's conversation in the directory it worked in.
`task_status.join_command` holds the exact command, with the task id; in the TUI, Shift+F2.

Plain `isx shell` into such an instance prints the command before the prompt.
With several tasks in the instance it lists them, each with its command, rather than guess; a task mid-turn, or one somebody is already in, is refused.

While you are in it, `task_status` says `attached` and `send_message` is refused.
A turn that was already on its way when you joined does not start either, since a turn and a joined agent each hold the conversation's lock.

That lock is taken by `isx shell --task`.
Resuming by hand (`claude --resume` in a plain `isx shell`) is only covered by the `attached` check, which a turn starting at the same moment can miss.

### Keeping the agent to the MCP tools

`isx mcp` runs as you, so these limits hold for the MCP tools, not for an agent that can also run `isx` through its shell.
Allow the MCP tools and deny the rest in Claude Code's settings:

```json
{
  "permissions": {
    "allow": ["mcp__isx__*"],
    "deny": ["Bash(isx:*)", "Edit(~/.config/incus-spawn/**)", "Write(~/.config/incus-spawn/**)"]
  }
}
```

Delegated agents use the template's selected `claude` or `openai` account.
Text coming back from an instance (command output, a delegate's report, a diff) is data produced inside the sandbox, and the tool descriptions tell the host agent to treat it that way.

## A coordinator in an instance

The agent that coordinates the others can itself run in an isx instance.
It then acts with the instance's credentials, such as a bot's GitHub login, instead of yours, and reaches your host only through the MCP tools.
Branch it with `--mcp-client`:

```shell
isx branch coord --from tpl-dev --proxy-only --mcp-client
```

Inside it, the host proxy serves `isx mcp` at `https://mcp.isx.internal/mcp` (MCP's Streamable HTTP transport), running one host `isx mcp` for the instance.
Each request must come from the instance's own address and carry the secret isx gives it at every start, in `X-Isx-Instance-Secret`.

`isx branch --mcp-client` asks the server from inside the new instance once it has started.
It says whether the server was reached, with its version and tool count, or why not, and whether the instance's Claude Code has the server.

If the template has the `claude` tool, there is nothing to set up.
Every isx start registers the server in the instance's Claude Code (`claude mcp list` shows it as `isx`), with a `headersHelper` that reads the current secret from `/run/isx/instance-secret`.

So interactive sessions, `claude --continue` and `claude -p` under a systemd unit all have the tools.
The server also offers the coordination workflow as a prompt, which Claude Code lists as `/mcp__isx__coordinate`.

Claude Code runs a `headersHelper` only in a trusted workspace.
The `claude` tool already trusts the home directory and every repository the template clones, so a coordinator started from anywhere else meets Claude Code's ordinary trust prompt once.
A copy of the coordinator (`isx branch --from coord`) loses the registration on its first start, along with the grant.

It gets exactly the tools, approved templates and limits a host agent gets, and the same `mcp.yaml` applies.
What differs:

- **The session is the instance.**
  What it creates stays held by it across restarts of its Claude Code and of the instance itself, and is picked up again on the next connection: nothing to adopt.
- **Its instances are released only with it.**
  They become orphans only once it is destroyed, or once it may not call any more; a new coordinator under the same name is another session and gets none of them.
- **A withdrawn template releases its instances.**
  One whose template you have since removed from the approved list is released when the coordinator next connects, as a host session's would be when it ends.
- **One connection at a time.**
  A new connection from the instance ends its previous one.
- **No coordinator makes another.**
  Only `isx branch --mcp-client` grants it, never to a copy: not to `isx branch --from coord`, and not to anything created through MCP.
- **Only the host proxy is reached.**
  `mcp.isx.internal` resolves to the bridge gateway, so this works with `--proxy-only`; an `--airgap` instance cannot use it.

## Routes to a controller on the host

A program of your own on the host, such as a coordinator that agents report to, can be reached from instances by a name you choose, through the proxy and in `--proxy-only` mode too.
List it in `~/.config/incus-spawn/config.yaml`:

```yaml
host-routes:
  work.isx.internal:
    target: 127.0.0.1:7070       # on this host's loopback: localhost, 127.0.0.1 or [::1]
    templates: [tpl-isx-agent]   # required: only instances of these templates may call it
```

An instance then calls `https://work.isx.internal/...`, sending the secret isx gave it at its last start as `X-Isx-Instance-Secret`.
That secret is in the file `$ISX_INSTANCE_SECRET_FILE` names.

The proxy checks the secret and the instance's address together, and the instance's template against `templates:`.
It forwards the request over plain HTTP to the target with `X-Isx-Instance` and `X-Isx-Template` set to who called.

The proxy first removes any `X-Isx-*` header the instance sent, so the controller never sees the instance's secret.
Anything else gets an error and never reaches the controller: a wrong or missing secret, a template the route does not list, an instance built from a project-local definition, a WebSocket.

Names must end in `.internal` and `templates:` must list at least one template.
The proxy skips an entry that is wrong in any way, with a warning in its log.

### Checking the route secret

The controller must check the route secret before it trusts those headers.

The proxy also sends `X-Isx-Route-Secret`, a secret of this route alone that only this host holds.
It is in `~/.config/incus-spawn/host-routes/<name>.secret` (here `work.isx.internal.secret`), which the proxy creates readable by you only the first time it serves the route.
No instance ever has it, and the proxy removes it from the controller's response headers.

Compare it in constant time, and refuse the request when it does not match, for example in Python:

```python
expected = open(os.path.expanduser("~/.config/incus-spawn/host-routes/work.isx.internal.secret")).read().strip()
if not hmac.compare_digest(request.headers.get("X-Isx-Route-Secret", ""), expected):
    return 403
```

### Rules for the controller

These rules keep the secret with the proxy and the controller, and nowhere else.

- Treat a request without the secret as one that did not come through the proxy: another program on this host, for example.
- Keep the controller listening on loopback only: `127.0.0.1`, never `0.0.0.0`, which instances that are not `--proxy-only` reach at the bridge gateway.
- Never echo the secret back, in a response body least of all.
  The proxy removes it from response headers, but relays the body as it is.
- To rotate the secret, delete the file; the proxy makes a new one at once.
- Rotate it too if the controller was down while something else held its port: whatever held it received the secret.
- Keep the secret file, its directory and `~/.config/incus-spawn` owned by you, the file readable by nobody else, and the directories writable by nobody else.
  The proxy refuses (503) every request to a route that breaks this.
  The config directory may be writable by your own private group, as a umask of 002 makes it, never by a group you share.
- Keep the directories above `~/.config/incus-spawn` free of write access for anyone else, without relying on POSIX ACLs: the proxy does not check those directories and does not see `setfacl`.
- Once you fix one with `chmod` or `chown`, the proxy serves the route again at once on Linux.
  On macOS, run `isx proxy restart`, since its file watcher does not see permission changes (a rewritten or deleted secret it does see).
