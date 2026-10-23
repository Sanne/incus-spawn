# Delegating from an agent on your host (MCP)

An agent running on your machine -- Claude Code, say -- can use isx itself: create disposable instances from templates you approved, run builds and tests in them, and hand whole tasks to Claude Code or Codex inside an instance.
`isx mcp` serves the [Model Context Protocol](https://modelcontextprotocol.io) over stdio:

`isx init` offers to set this up (it is experimental, so off by default): it registers `isx mcp` with Claude Code for all projects and asks, for each template that installs Claude Code or Codex, whether agents may use it.
Once it is set up, a re-run of `isx init` asks one question instead: `t` to change the approved templates, `n` to see how to turn it off, Enter to keep everything.

To register it with Claude Code by hand instead:

```shell
claude mcp add --scope user isx -- ~/.local/bin/isx mcp
```

Nothing is available until you approve templates in `~/.config/incus-spawn/mcp.yaml`, a file of its own beside `config.yaml`.
Agents never can -- `isx mcp` reads this file on every call and has no way to write it:

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

A missing or empty file approves nothing.
A file `isx mcp` cannot use -- unreadable, malformed, an unknown key or a value it does not accept -- refuses every call that depends on it, naming `mcp.yaml` and what is wrong, until you fix it; a change takes effect on the next call, with no restart.

Earlier versions kept these settings in the `mcp:` section of `config.yaml`.
That section is no longer read, not even as a fallback: move its contents into `mcp.yaml` and drop the `mcp:` line, un-indenting what was under it.
Until `mcp.yaml` exists, the old section makes `isx mcp` refuse everything and `isx init` leave the step alone, so its limits are never quietly replaced by the defaults.
Once `mcp.yaml` exists, both only remind you to delete the old section.

`max-instances` and `max-concurrent-tasks` are the two knobs to raise for a controller, one session running many tasks at once.
They are a safety net against runaway creation, not a budget: the only cost of a high number is host memory.
`list_templates` reports both, under the same names (`max_instances`, `max_concurrent_tasks`).
Every tool also returns its facts as `structuredContent` matching the `outputSchema` it lists, so a program need not parse the text.
A task's `state` is the same word in every tool: `running`, `finished`, `attached` (finished, and a person is in its conversation), `lost` or `unknown` (`list_instances` gives it as the session last saw it, so `unknown` there means nothing has read how it ended yet, as after `cancel_task`; `task_status` reads it).
A refusal says why in `_meta["dev.incusspawn/error"].code`.

| Tool | What it does |
|------|--------------|
| `list_templates` | Approved templates, their `delegate_agents`, default agent, model and permissions, capability differences, and policy refusals |
| `create_instance` | A fresh CoW branch of an approved template, as `isx branch` would make it, with an optional `purpose` and `name_hint`; or, with `from_instance`, a fork of one of your stopped instances -- prepare once, fork N times |
| `stop_instance` / `start_instance` | Stop an instance (to fork it; refused while a task runs, unless `force`), and start it again |
| `list_instances` / `adopt_instance` | Your instances and their tasks, including those an ended session left behind; take one back |
| `exec` | Run a command as `agentuser`; no time limit unless the agent sets one, or `background: true` for a task |
| `delegate` | Give an instruction or installed skill to an instance, or a fresh one from a template; select `agent: "claude"` or `"codex"`, optionally with `model` and Claude-only `max_turns`; `purpose`/`name_hint` for a fresh one |
| `task_status` / `wait_any` / `task_result` | Follow a task (optionally waiting), wait for whichever of several finishes first, read its outcome or the agent's report |
| `send_message` | Continue a delegated agent's conversation (e.g. "push and open a PR") |
| `get_diff` | What a delegated task changed, committed or not: a patch, or with `stat` just the files and line counts |
| `instance_activity` | What the proxy saw of an instance's Claude model calls: made and in flight, when the last one ended, tokens spent; and of all its traffic (`traffic_*`) |
| `cancel_task` / `destroy_instance` | Stop a task, or throw an instance away |
| `keep_instance` | Hand an instance over to you for good |

`list_templates` reports each template's `delegate_agents` and `default_delegate_agent`.
Use `agent: "claude"` or `agent: "codex"` on `delegate` to select an installed agent explicitly.
When omitted, Claude is selected if installed, otherwise Codex.
`send_message` keeps the task's agent and resumes its exact saved conversation.
The coordinator may be Claude Code regardless of which agent the worker runs.

Each descriptor reports `supports_max_turns`, `supports_model_preflight`, its configured model and permission settings, and a `refusal` if current host policy prevents launch.
`supports_ask` and `summary_agent` describe the separate summary service.
Models and permissions come from the selected agent's configuration; task `model` overrides persist across follow-ups and adoption.
Claude validates a chosen model against its account before launching; Codex reports model-access errors in the task result.

Codex has no equivalent to Claude's `max_turns`: an explicit argument is rejected, and a configured `delegate-max-turns` in `mcp.yaml` prevents Codex delegation and continuation.
The user must revise that policy to use Codex.
`ask` requires Claude's tool-free summarizer and is refused on Codex-only instances before its command runs; ordinary output, reports and diffs work with either agent.
Codex uses the template's `openai` credential account and always runs without approval prompts.
If a template has a restrictive Claude permission policy, configure its Codex sandbox mode explicitly before selecting Codex: one restricted in `delegate-permission-modes` needs its own `codex.sandbox-modes` entry, as the section-wide `sandbox-mode` does not apply to it.
The proxy's `model_usage_providers` currently lists Anthropic; OpenAI traffic is included in `traffic_*`, and Codex reports task token usage when available.

Restart existing MCP server connections after upgrading to enable Codex workers.
Older isx servers cannot manage the new Codex task records; keep outstanding Codex instances before downgrading.


A client that drives `isx mcp` from code rather than from a model can ask to be told instead of waiting: list `isx/task_changed` under `capabilities.experimental` in `initialize`, and every change of a task's state arrives as a `notifications/isx/task_changed` (`task_id`, `instance`, `run`, `state`: `running`, `finished`, `lost`, `attached` or `released`, and `exit_code` once finished).
Clients that do not ask are not polled for it.

A controller that recovers from a crash by re-running what it has no result for can make that safe: `create_instance`, `delegate` and `exec` with `background: true` take an optional `idempotency_key` (1-64 characters of `A-Za-z0-9._:-`).
A call whose key already made something returns that instance or task, with `replayed: true`, instead of making a second one -- across sessions too: an orphan made under the key is adopted.
The key lives exactly as long as what it made.
A repeat must ask for the same thing (template or source, instance, kind of task): one that asks for something else is refused, never redirected, so a controller that picks the instance at run time records that choice with its intent.
One outcome needs looking up rather than retrying: a key whose instance was kept with `keep_instance` is refused.

`instance_activity` answers from the host proxy's view, without touching the instance: a call in flight means the agent is working, a growing `idle_seconds` that it is stuck or finished without reporting.
Its token counts (`input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`, as the API reports them) run from `counting_since`, when the proxy began counting that instance (absent while the proxy does not know the instance yet).
For what a task spent, read before and after it and subtract -- only when both reads carry the same `counting_since`; a different or missing one means the counts started afresh in between (the proxy restarted) and the difference is not the task's.
Its `traffic_*` fields count every request the instance made through the proxy, model calls included (package downloads, git, WebSockets, which are in flight while open), from the same `counting_since`: an agent with no model call in flight but a build pulling dependencies is still working.
`isx proxy activity` shows the same counts for every instance on the host.

`exec`, `task_result` and `get_diff` take an optional `ask`: instead of the text, a one-shot Claude Code on `summary-model` reads it *inside the instance* and answers the question ("which tests fail?"), so a long log or patch never fills the host agent's context.
The answer is a model's reading -- untrusted and lossy -- so anything that gates a merge stays deterministic: `get_diff(stat)`, CI, a reviewer.

What an agent gets is deliberately narrow:

- **Only approved templates.**
  Never a project-local (`.incus-spawn/`) definition or an image built from one, and never an unbuilt one -- it is told to ask you to run `isx build`.
- **Template defaults, no choices.**
  Network mode, credential accounts, resources: exactly what `isx branch --from <template>` gives.
  Credentials stay in the host proxy as always; which GitHub account a delegate can push with is the one you pinned on the template.
- **Your instances, one session at a time.**
  Every instance is stamped with your host user, an optional purpose (`#870 implement`), and the session holding it; every tool checks that stamp.
  Instances outlive their session, because a coordinating agent restarts while its workers wait: when a session ends (or dies), its instances become orphans that any later session of yours can take back with `adopt_instance`, tasks included.
  An orphan nobody adopts is destroyed by the next `isx mcp` to start once `orphan-grace-hours` have passed -- never while someone is in it with `isx shell` or an agent it delegated to is still working, and never one handed to you with `keep_instance`.
  Instances are named `mcp-<template>-<hint>-<suffix>`, where the hint is the `name_hint` an agent gave `create_instance` or `delegate` (`870-impl`; `task` for a delegate without one); don't give your own instances `mcp-*` names.
  `isx list` adds an `MCP` column when there are any, showing each instance's state and purpose (`held: #870 implement`, `orphaned`, `kept: spike`).
  The TUI shows the same in an instance's details (F3), with who holds it, and has no column for it.
- **Taking over is safe.**
  You can join a delegate's conversation yourself: `isx shell <instance> --task` resumes it in the directory it worked in (`task_status.join_command` holds the exact command, with the task id; in the TUI, Shift+F2).
  With several tasks in the instance it lists them, each with its command, rather than guess; a task mid-turn, or one somebody is already in, is refused.
  While you are in it, `task_status` says `attached` and `send_message` is refused; a turn that was already on its way when you joined does not start either, since a turn and a joined agent each hold the conversation's lock.
  That lock is taken by `isx shell --task`; resuming by hand (`claude --resume` in a plain `isx shell`) is only covered by the `attached` check, which a turn starting at the same moment can miss.
  Plain `isx shell` into such an instance prints the command before the prompt.
- **No host access.**
  Nothing runs on the host and no host files are read or written: results come back as text, and `get_diff` returns a patch rather than touching your checkout.
- **An audit trail.**
  Every call is logged, with credentials scrubbed, to `~/.local/state/incus-spawn/mcp.log`.

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
Text coming back from an instance -- command output, a delegate's report, a diff -- is data produced inside the sandbox, and the tool descriptions tell the host agent to treat it that way.

## A coordinator in an instance

The agent that coordinates the others can itself run in an isx instance, so that it acts with the instance's credentials -- a bot's GitHub login, say -- instead of yours, and reaches your machine only through the MCP tools.
Branch it with `--mcp-client`:

```shell
isx branch coord --from tpl-dev --proxy-only --mcp-client
```

Inside it, `isx mcp` is served at `https://mcp.isx.internal/mcp` (MCP's Streamable HTTP transport) by the host proxy, which runs one host `isx mcp` for the instance.
Each request must come from the instance's own address and carry the secret isx gives it at every start, in `X-Isx-Instance-Secret`.
If the template has the `claude` tool, there is nothing to set up: every isx start registers the server in the instance's Claude Code (`claude mcp list` shows it as `isx`), with a `headersHelper` that reads the current secret from `/run/isx/instance-secret`, so interactive sessions, `claude --continue` and `claude -p` under a systemd unit all have the tools.
Claude Code runs a `headersHelper` only in a trusted workspace; the `claude` tool already trusts the home directory and every repository the template clones, so a coordinator started from anywhere else meets Claude Code's ordinary trust prompt once.
A copy of the coordinator (`isx branch --from coord`) loses the registration on its first start, along with the grant.
The server also offers the coordination workflow as a prompt, which Claude Code lists as `/mcp__isx__coordinate`.
`isx branch --mcp-client` asks the server from inside the new instance once it has started, and says whether it was reached (with the server's version and tool count) or why not, and whether its Claude Code has the server.

It gets exactly the tools, approved templates and limits a host agent gets, and the same `mcp.yaml` applies.
What differs:

- **The session is the instance.**
  What it creates stays held by it across restarts of its Claude Code and of the instance itself, and is picked up again on the next connection: nothing to adopt.
  Its instances become orphans only once it is destroyed, or no longer allowed to call; a new coordinator under the same name is another session and gets none of them.
  One whose template you no longer approve is released when the coordinator next connects, as a host session's would be when it ends.
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

An instance then calls `https://work.isx.internal/...`, sending the secret isx gave it at its last start as `X-Isx-Instance-Secret` (it is in the file `$ISX_INSTANCE_SECRET_FILE` names).
The proxy checks the secret and the instance's address together, and the instance's template against `templates:`, and forwards the request over plain HTTP to the target with `X-Isx-Instance` and `X-Isx-Template` set to who called.
Any `X-Isx-*` header the instance sent is removed first, so the controller never sees the instance's secret.
Anything else gets an error and never reaches the controller: a wrong or missing secret, a template the route does not list, an instance built from a project-local definition, a WebSocket.
Names must end in `.internal`, `templates:` must list at least one template, and an entry that is wrong in any way is skipped with a warning in the proxy's log.

**The controller must check the route secret before it trusts those headers.**
The proxy also sends `X-Isx-Route-Secret`, a secret of this route alone that only this host holds: it is in `~/.config/incus-spawn/host-routes/<name>.secret` (here `work.isx.internal.secret`), which the proxy creates readable by you only the first time it serves the route.
No instance ever has it, and the proxy removes it from the controller's response headers.
Compare it in constant time, and refuse the request when it does not match, for example in Python:

```python
expected = open(os.path.expanduser("~/.config/incus-spawn/host-routes/work.isx.internal.secret")).read().strip()
if not hmac.compare_digest(request.headers.get("X-Isx-Route-Secret", ""), expected):
    return 403
```

Anything that reaches the controller without it did not come through the proxy: another program on this host, say.
Keep the controller listening on loopback only (`127.0.0.1`, never `0.0.0.0`, which instances that are not `--proxy-only` reach at the bridge gateway), and never echo the secret back, in a response body least of all: the proxy removes it from response headers, but relays the body as it is.
To rotate the secret, delete the file; the proxy makes a new one at once.
If the controller was down while something else held its port, whatever held it received the secret, so rotate it then too.
The proxy refuses (503) every request to a route whose secret file, its directory or `~/.config/incus-spawn` is not yours, whose secret file anyone but you can read, or whose directories others can write to (the config directory may be writable by your own private group, as a umask of 002 makes it; never by a group you share).
The proxy does not see POSIX ACLs (`setfacl`) and does not check the directories above `~/.config/incus-spawn`: keep both free of write access for anyone else.
Once you fix one with `chmod` or `chown`, the route is served again at once on Linux; on macOS, run `isx proxy restart`, since its file watcher does not see permission changes (a rewritten or deleted secret it does see).
