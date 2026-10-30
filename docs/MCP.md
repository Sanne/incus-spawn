# Delegating from an agent on your host (MCP)

An agent on your host, such as Claude Code, can use isx itself: create instances from templates you approved, run builds and tests in them, and hand whole tasks to Claude Code or Codex inside an instance.
`isx mcp` serves the [Model Context Protocol](https://modelcontextprotocol.io) over stdio.

This page covers setup, what the agent gets, and running the coordinating agent in an instance of its own.
A program that drives `isx mcp` from code has [a page of its own](MCP-PROTOCOL.md).

## Setting it up

`isx init` offers to set this up.
It is off by default, since it lets an agent on your host create and run instances.
It registers `isx mcp` with Claude Code for all projects and asks, for each template that installs Claude Code or Codex, whether agents may use it.

The step needs Claude Code installed on the host and a terminal to ask from.
Without either, `isx init` skips it and says so.

Once it is set up, a re-run of `isx init` asks one question instead: `t` to change the approved templates, `n` to see how to turn it off, Enter to keep everything.

To register it with Claude Code by hand instead:

```shell
claude mcp add --scope user isx -- ~/.local/bin/isx mcp
```

## Approving templates: `mcp.yaml`

Nothing is available until you approve templates in `~/.config/incus-spawn/mcp.yaml`, a file of its own beside `config.yaml`.
Agents never can: `isx mcp` reads this file on every call and has no way to write it.

```yaml
templates: [tpl-java, tpl-dev]
max-instances: 8
```

| Key | Default | What it does |
|-----|---------|--------------|
| `templates` | none | The only templates an agent may branch from. |
| `max-instances` | 8 | Instances per host user, across sessions. |
| `max-concurrent-tasks` | 8 | Background commands and delegated agents, per host user, across sessions. |
| `delegate-max-turns` | not set | A ceiling on Claude's turns. Codex is refused while this is set. |
| `delegate-permission-mode` | `bypassPermissions` | Claude's `--permission-mode`. |
| `delegate-permission-modes` | not set | Per-template overrides, such as a reviewer that only plans: `tpl-review: plan`. |
| `codex.sandbox-mode` | `danger-full-access` | Codex's sandbox mode. Codex always runs without approval prompts. |
| `codex.sandbox-modes` | not set | Per-template Codex sandbox modes. |
| `orphan-grace-hours` | 24 | How long an instance outlives its session. |
| `dormant-after-hours` | 24 | Past the orphan grace, an instance kept only for a delegate that shows no activity this long is stopped instead. |
| `dormant-grace-hours` | 168 | How long after that stop the instance is destroyed. |
| `summary-model` | `haiku` | The model that answers the tools' `ask` inside the instance. |

A missing or empty file approves nothing.
A file `isx mcp` cannot use refuses every call that depends on it, naming `mcp.yaml` and what is wrong, until you fix it.
Unusable means unreadable, malformed, an unknown key or a value it does not accept.
A change takes effect on the next call, with no restart.

`max-instances` and `max-concurrent-tasks` are the two knobs to raise for a coordinator running many tasks at once.
They are a safety net against runaway creation, not a budget: the only cost of a high number is host memory.

### If `config.yaml` has an `mcp:` section

The `mcp:` section of `config.yaml` is not read, not even as a fallback.
If you have one, move its contents into `mcp.yaml` and drop the `mcp:` line, un-indenting what was under it.

Until `mcp.yaml` exists, that section makes `isx mcp` refuse everything and `isx init` leave the step alone, so its limits are never quietly replaced by the defaults.
Once `mcp.yaml` exists, both only remind you to delete the old section.

## The tools

An agent gets these tools, and no others.

| Tool | What it does |
|------|--------------|
| `list_templates` | The approved templates, with the agents each can delegate to and the limits in `mcp.yaml`. |
| `create_instance` | A fresh branch of an approved template, as `isx branch` would make it. With `from_instance`, a fork of one of the agent's stopped instances: prepare once, fork many times. |
| `stop_instance` / `start_instance` | Stop an instance, to fork it, and start it again. Stop is refused while a task runs, unless forced. |
| `list_instances` / `adopt_instance` | The agent's instances and their tasks, including those an ended session left behind; take one back. |
| `exec` | Run a command as `agentuser`, in the foreground or as a background task. |
| `delegate` | Give an instruction or an installed skill to Claude Code or Codex in an instance, or in a fresh one from a template. |
| `task_status` / `wait_any` / `task_result` | Follow a task, wait for whichever of several finishes first, read its outcome or the agent's report. |
| `send_message` | Continue a delegated agent's conversation, for example "push and open a PR". |
| `get_diff` | What a delegated task changed, committed or not: a patch, or only the files and line counts. |
| `instance_activity` | What the proxy saw of an instance's model calls and of all its traffic, to tell working from stuck without touching the instance. |
| `cancel_task` / `destroy_instance` | Stop a task, or throw an instance away. |
| `keep_instance` | Hand an instance over to you for good. |

### Delegating to Claude Code or Codex

`delegate` runs Claude Code or Codex inside the instance.
The agent picks one with `agent: "claude"` or `agent: "codex"`; without a choice, Claude when the template installs it, otherwise Codex.
`send_message` keeps the task's agent and resumes its saved conversation.
The coordinator may be Claude Code whichever agent the worker runs.

Models and permissions come from the chosen agent's configuration in `mcp.yaml`.
Codex has no turn ceiling, so a `delegate-max-turns` in `mcp.yaml` refuses Codex until you remove it.
Codex uses the template's `openai` credential account.
A template with a restrictive Claude permission mode in `delegate-permission-modes` needs its own entry in `codex.sandbox-modes`, as the section-wide `sandbox-mode` does not apply to it.

### Asking a question instead of reading the output

`exec`, `task_result` and `get_diff` take an optional `ask`.
Instead of returning the text, a one-shot Claude Code on `summary-model` reads it inside the instance and answers the question, such as "which tests fail?".
A long log or patch then never fills the host agent's context.
This needs Claude Code in the instance; a Codex-only instance refuses `ask`.

The answer is a model's reading, untrusted and lossy.
Anything that gates a merge stays deterministic: `get_diff` with `stat`, CI, a reviewer.

## What an agent gets

What an agent gets is deliberately narrow.

- Only approved templates.
  Never a project-local (`.incus-spawn/`) definition or an image built from one, and never an unbuilt one: the agent is told to ask you to run `isx build`.
- Template defaults, no choices.
  Network mode, credential accounts, resources: exactly what `isx branch --from <template>` gives.
  Credentials stay in the host proxy as always; a delegate pushes with the GitHub account you pinned on the template.
- Its own instances, one session at a time.
  Every instance is stamped with your host user, an optional purpose (`#870 implement`) and the session holding it; every tool checks that stamp.
- No host access.
  Nothing runs on the host and no host files are read or written.
  Results come back as text, and `get_diff` returns a patch rather than touching your checkout.
- An audit trail.
  Every call is logged, with credentials scrubbed, to `~/.local/state/incus-spawn/mcp.log`.

Text coming back from an instance (command output, a delegate's report, a diff) is data produced inside the sandbox.
The tool descriptions tell the host agent to treat it that way.

### Orphans and adoption

Instances outlive their session, because a coordinating agent restarts while its workers wait.
When a session ends or dies, its instances become orphans that any later session of yours can take back with `adopt_instance`, tasks included.

An orphan nobody adopts is destroyed by the next `isx mcp` to start once `orphan-grace-hours` have passed.
It is never destroyed while someone is in it with `isx shell` or an agent it delegated to is still working, and never once handed to you with `keep_instance`.

One kept past its grace only for a delegate that shows no activity for `dormant-after-hours` is stopped instead, and destroyed `dormant-grace-hours` after that stop.
`adopt_instance` starts it again.

### Names and the `MCP` column

Instances are named `mcp-<template>-<hint>-<suffix>`.
The hint is the `name_hint` an agent gave `create_instance` or `delegate`, such as `870-impl`; `task` for a delegate without one.
Do not give your own instances `mcp-*` names.

`isx list` adds an `MCP` column when there are any, showing each instance's state and purpose (`held: #870 implement`, `orphaned`, `kept: spike`).
The TUI shows the same in an instance's details (F3), with who holds it.

### Joining a delegate's conversation

`isx shell <instance> --task` resumes a delegate's conversation in the directory it worked in.
`task_status` gives the agent the exact command, with the task id; in the TUI, press Shift+F2.

Plain `isx shell` into such an instance prints the command before the prompt.
With several tasks in the instance it lists them, each with its command, rather than guess.
A task mid-turn, or one somebody is already in, is refused.

While you are in it, `task_status` says `attached` and `send_message` is refused.
Resuming by hand (`claude --resume` in a plain `isx shell`) gives up that protection: a turn starting at the same moment can collide with you.

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

## A coordinator in an instance

The agent that coordinates the others can itself run in an isx instance.
It then acts with the instance's credentials, such as a bot's GitHub login, instead of yours, and reaches your host only through the MCP tools.
Branch it with `--mcp-client`:

```shell
isx branch coord --from tpl-dev --proxy-only --mcp-client
```

In the TUI's branch dialog (F4), tick MCP control instead.
It starts unticked for every template, and is not offered while the network is Airgapped.

Once the instance has started, the branch checks the connection from inside it.
It says whether the server was reached, with its version and tool count, or why not, and whether the instance's Claude Code or Pi has the server.

If the template has the `claude` or `pi` tool, there is nothing to set up.
Every isx start registers the server in whichever of the two the instance has, where `claude mcp list` or `pi mcp list` shows it as `isx`.
Interactive sessions, and `claude -p` or `pi -p` under a systemd unit, all have the tools.

Claude Code also lists the coordination workflow as the prompt `/mcp__isx__coordinate`; Pi does not list MCP prompts.

Claude Code uses the registration only in a trusted workspace.
The `claude` tool already trusts the home directory and every repository the template clones, so a coordinator started from anywhere else meets Claude Code's ordinary trust prompt once.

The coordinator gets exactly the tools, approved templates and limits a host agent gets, and the same `mcp.yaml` on the host applies.
What differs:

- The session is the instance.
  What it creates stays held by it across restarts of its Claude Code and of the instance itself: nothing to adopt.
- Its instances are released only with it.
  They become orphans once it is destroyed, or once it may not call any more.
  A new coordinator under the same name is another session and gets none of them.
- A withdrawn template releases its instances.
  One whose template you have since removed from the approved list is released when the coordinator next connects.
- One connection at a time.
  A new connection from the instance ends its previous one.
- No coordinator makes another.
  Only `isx branch --mcp-client` or the dialog's box grants it, never to a copy: not to `isx branch --from coord`, and not to anything created through MCP.
  A copy of the coordinator loses the grant and both registrations on its first start.
- Only the host proxy is reached.
  The server is served through the proxy, so this works with `--proxy-only`; an `--airgap` instance cannot use it.
