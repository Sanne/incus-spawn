# Delegating from an agent on your host (MCP)

An agent on your host, such as Claude Code, can use isx itself: create instances from templates you approved, run builds and tests in them, and hand whole tasks to Claude Code, Codex or Pi inside an instance.
`isx mcp` serves the [Model Context Protocol](https://modelcontextprotocol.io) over stdio.

This page covers setup, what the agent gets, and running the coordinating agent in an instance of its own.
A program that drives `isx mcp` from code has [a page of its own](MCP-PROTOCOL.md).

## Setting it up

`isx init` offers to set this up.
`isx mcp` is off by default, since it lets an agent on your host create and run instances.

`isx init` asks, for each template that installs Claude Code, Codex or Pi, whether agents may use it.
When Claude Code is installed on the host, `isx init` also registers `isx mcp` in Claude Code for all projects.

The step needs a terminal to ask from.
Without one, `isx init` skips it and says so.

To run this step alone, without the rest of `isx init`, use `isx mcp setup`.
It asks the same questions and needs a terminal too, so an agent that installed isx for you cannot run this step and leaves it to you.

Once `isx mcp` is set up, a re-run of `isx init` or `isx mcp setup` asks one question instead: `t` to change the approved templates, `n` to see how to turn it off, Enter to keep everything.

To register `isx mcp` with Claude Code by hand instead:

```shell
claude mcp add --scope user isx -- ~/.local/bin/isx mcp
```

### Other agents

Any agent that speaks MCP over stdio can use `isx mcp`.
`isx mcp config` prints the entry to add to that agent's settings:

```json
{
  "mcpServers" : {
    "isx" : {
      "command" : "/home/you/.local/bin/isx",
      "args" : [ "mcp" ]
    }
  }
}
```

Cursor reads it from `~/.cursor/mcp.json`, and Gemini CLI from `~/.gemini/settings.json`.
Most desktop apps and editors take the same `mcpServers` object in their MCP settings.
If the file already holds servers, add `isx` beside them.
The command only prints the entry and does not write any file, so you add the entry yourself.

Codex and Gemini CLI also register `isx mcp` with their own commands:

```shell
codex mcp add isx -- ~/.local/bin/isx mcp
gemini mcp add --scope user isx ~/.local/bin/isx mcp
```

Approve templates with `isx mcp setup` whichever agent you use.

## Approving templates: `mcp.yaml`

Nothing is available until you approve templates in `~/.config/incus-spawn/mcp.yaml`, a file of its own beside `config.yaml`.
An agent can never approve a template: `isx mcp` reads this file on every call and has no way to write it.

```yaml
templates: [tpl-java, tpl-dev]
max-instances: 8
host-tools:
  instance-inventory:
    command: ~/scripts/instance-inventory.sh
    description: Read-only inventory of stopped isx instances
    timeout: 300
    min-interval: 10
```

| Key | Default | What it does |
|-----|---------|--------------|
| `templates` | none | The only templates an agent may branch from. |
| `max-instances` | 8 | Instances per host user, across sessions. A session is one run of `isx mcp`. |
| `max-concurrent-tasks` | 8 | Background commands and delegated agents, per host user, across sessions. |
| `orphan-grace-hours` | 24 | How long an instance outlives its session. |
| `dormant-after-hours` | 24 | After `orphan-grace-hours`, an instance may remain only because its delegated agent has not finished. When that agent has shown no activity for this long, `isx mcp` stops the instance. |
| `dormant-grace-hours` | 168 | How long after that stop the instance is destroyed. |
| `summary-model` | `haiku` | The model that answers the tools' `ask` inside the instance. |
| `host-tools` | none | User-defined MCP tools that run a command on the host. Each key is a tool name with `command:`, `description:`, optional `timeout:` (default 60, max 600, 0 = no limit) and `min-interval:` (default 10, 0 = block instead of refuse). |

A missing or empty file does not approve any template.
`isx mcp` refuses every call that depends on a file it cannot use, naming `mcp.yaml` and what is wrong, until you fix the file.
The file is unusable when it is unreadable or malformed, or holds an unknown key or a value `isx mcp` does not accept.

A change takes effect on the next call, with no restart.

Older releases read `delegate-max-turns`, `delegate-permission-mode`, `delegate-permission-modes` and a `codex:` section with `sandbox-mode` or `sandbox-modes`.
These keys still load but have no effect, and `isx mcp` and `isx init` say so; delete them.

`max-instances` and `max-concurrent-tasks` are the two limits to raise for an agent running many tasks at once.
They exist to stop an agent that creates instances or tasks without end, not to ration them: the only cost of a high number is host memory.

### If `config.yaml` has an `mcp:` section

The `mcp:` section of `config.yaml` is not read, not even as a fallback.
If you have one, move its contents into `mcp.yaml` and drop the `mcp:` line, un-indenting what was under it.

Until `mcp.yaml` exists, that section makes `isx mcp` refuse every call that depends on these settings and `isx init` skip the MCP step, so the defaults never silently replace the section's limits.
Once `mcp.yaml` exists, both only remind you to delete the old section.

## The tools

An agent gets these tools, and no others.

| Tool | What it does |
|------|--------------|
| `list_templates` | The approved templates, with the agents each can delegate to and the limits in `mcp.yaml`. |
| `create_instance` | A fresh branch of an approved template, as `isx branch` would make it. With `from_instance`, a fork of one of the agent's stopped instances: prepare once, fork many times. |
| `stop_instance` / `start_instance` | Stop an instance, to fork it, and start it again. Stop is refused while a task runs, unless forced. |
| `list_instances` / `adopt_instance` | The agent's instances and their tasks, including the instances an ended session left behind. `adopt_instance` gives one of those to the current session. |
| `exec` | Run a command as `agentuser`, in the foreground or as a background task. |
| `delegate` | Give an instruction or an installed skill to Claude Code, Codex or Pi in an instance, or in a fresh one from a template. |
| `task_status` / `wait_any` / `task_result` | Follow a task, wait for whichever of several finishes first, read its outcome or the agent's report. |
| `send_message` | Continue a delegated agent's conversation, for example "push and open a PR". |
| `get_diff` | What a delegated task changed, committed or not: a patch, or only the files and line counts. |
| `instance_activity` | What the proxy saw of an instance's model calls and of all its traffic. It shows whether an agent is working or stuck, without running anything in the instance. |
| `cancel_task` / `destroy_instance` | Stop a task, or delete an instance. |
| `keep_instance` | Give an instance to you permanently. |

### Delegating to Claude Code, Codex or Pi

`delegate` runs Claude Code, Codex or Pi inside the instance.
The agent picks one with `agent: "claude"`, `"codex"` or `"pi"`; without a choice, `delegate` runs Claude Code when the template installs it, otherwise Codex, otherwise Pi.
`send_message` keeps the task's agent and resumes its saved conversation.
The agent that delegates may be Claude Code whichever agent it delegates to.

Every delegated agent runs with full access inside its instance and no turn limit, since the instance is the boundary.
Claude runs in `bypassPermissions` mode, Codex in `danger-full-access` without approval prompts, and Pi with every tool.
The model is the template's unless the call names one.

Codex uses the template's `openai` credential account.
Pi uses the account of its template's `provider`: the Claude account, the `openai` one or the `gemini` one.

### Asking a question instead of reading the output

`exec`, `task_result` and `get_diff` take an optional `ask`.
The tool then does not return the text: a single run of Claude Code on `summary-model` reads the text inside the instance and answers the question, such as "which tests fail?".
A long log or patch then never fills the host agent's context.

This needs Claude Code in the instance; a Codex-only or Pi-only instance refuses `ask`.

The answer is a model's reading of the text, so it can be wrong and it leaves details out.
Whatever decides a merge stays deterministic: `get_diff` with `stat`, CI, a reviewer.

## What an agent gets

What an agent gets is deliberately narrow.

- Only approved templates.
  The agent can never use a project-local definition, one a cloned repository ships in `.incus-spawn/`, or an image built from one.
  For a template that is not built yet, `isx mcp` tells the agent to ask you to run `isx build`.
- Template defaults, no choices.
  Network mode, credential accounts, resources: exactly what `isx branch --from <template>` gives.
  Credentials stay in the host proxy as always; a delegated agent pushes with the GitHub account you pinned on the template.
- Its own instances, one session at a time.
  `isx mcp` stamps every instance with your host user, an optional purpose (`#870 implement`) and the session holding it; every tool checks that stamp.
- No host access, except host tools.
  Nothing runs on the host unless you defined it in `host-tools:` in `mcp.yaml`.
  The agent can trigger those commands but cannot influence the command string.
  Results come back as text, and `get_diff` returns a patch without changing your checkout.
- An audit trail.
  `isx mcp` logs every call, with credentials removed, to `~/.local/state/incus-spawn/mcp.log`.

Text coming back from an instance (command output, a delegated agent's report, a diff) is data produced inside the instance.
The tool descriptions tell the host agent to treat that text as data, not as instructions.

### Orphans and adoption

Instances outlive their session, because an agent on your host can restart while the agents it delegated to are still working.
When a session ends or dies, its instances become orphans that any later session of yours can take over with `adopt_instance`, tasks included.

Once `orphan-grace-hours` have passed, the next `isx mcp` to start destroys an orphan nobody adopted.
`isx mcp` never destroys an orphan while someone is in it with `isx shell` or while a delegated agent in it is still working, and never destroys one that `keep_instance` gave to you.

An orphan past `orphan-grace-hours` may remain only because its delegated agent has not finished.
When that agent has shown no activity for `dormant-after-hours`, `isx mcp` stops the orphan, and destroys it `dormant-grace-hours` after that stop.
`adopt_instance` starts a stopped orphan again.

### Names and the `MCP` column

Instances are named `mcp-<template>-<hint>-<suffix>`.
The hint is the `name_hint` an agent gave `create_instance` or `delegate`, such as `870-impl`; a `delegate` call without a hint gets `task`.
Do not give your own instances `mcp-*` names.

`isx list` adds an `MCP` column when there are any, showing each instance's state and purpose (`held: #870 implement`, `orphaned`, `kept: spike`).
The TUI shows the same in an instance's details (F3), with who holds it.

### Joining a delegate's conversation

`isx shell <instance> --task` resumes a delegated agent's conversation in the directory that agent worked in.
`task_status` gives the agent the exact command, with the task id; in the TUI, press Shift+F2.

Plain `isx shell` into such an instance prints the command before the prompt.
With several tasks in the instance it lists them, each with its command, and does not choose one.

`isx shell --task` refuses a task that is in the middle of a turn, or one somebody has already joined.
It also refuses a Pi task whose saved session is gone, since Pi would open an empty conversation in its place.

While you are in the conversation, `task_status` says `attached` and `send_message` is refused.
Resuming by hand (`claude --resume`, `codex resume` or `pi --session-id` in a plain `isx shell`) gives up that protection: a turn that `send_message` starts at the same moment runs in the same conversation as you.

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

The agent that controls the other instances can itself run in an isx instance, which is then called a coordinator.
That agent then acts with the instance's credentials, such as a bot's GitHub login, instead of yours, and reaches your host only through the MCP tools.

### From a fresh install

These steps take the [Quick Start](../README.md#quick-start)'s `tpl-agent` as the template the coordinator branches.
They add a coordinator template whose parent is `tpl-agent`, approve `tpl-agent`, and open the coordinator:

```shell
isx templates new tpl-coordinator --from - <<'EOF'
name: tpl-coordinator
description: Claude Code that branches tpl-agent over MCP
parent: tpl-agent
coordinator:
  control-over: [tpl-agent]
EOF
isx build tpl-coordinator
isx mcp setup
isx branch coord --from tpl-coordinator
```

You run `isx mcp setup` yourself, never an agent: enable the server, then approve only `tpl-agent`.
Without Claude Code on the host it skips, so list it in `mcp.yaml` yourself instead: `templates: [tpl-agent]`.
The branch opens Claude Code in `coord`, where Claude Code may branch `tpl-agent` and no other template.

To let the coordinator branch other templates, list them in both places: `control-over` above, and `templates` in `mcp.yaml`.

### Making a coordinator

A template meant for coordinators says which templates they control:

```yaml
coordinator:
  control-over: [tpl-java, tpl-dev]
```

Every branch of that template is then a coordinator, and the TUI's branch dialog (F4) starts with MCP control ticked.
`isx branch --no-mcp-client` or unticking the box makes a plain instance.
isx ignores the `coordinator:` of a project-local template, and an airgapped branch is a plain instance.

A branch is a coordinator by default exactly when `control-over` lists a template.
`isx-control-capability: false` beside `control-over` turns the default off, and isx warns of that, since the list suggests the template is meant for coordinators.
`isx-control-capability: true` turns it on for a template whose `control-over` is empty or missing.

`isx branch --mcp-client`, or ticking MCP control, makes a coordinator of a branch of any other template.
The box is not offered while the network is Airgapped.
Such a coordinator cannot control any template until its template lists templates under `control-over`, and isx warns of that with a link to the steps above.

Once the instance has started, isx checks the connection to the `isx mcp` server from inside the instance.
It says whether the server was reached, with its version and tool count, or why not, and whether the instance's Claude Code or Pi has the server registered.

If the template has the `claude` or `pi` tool, there is nothing to set up.
Every isx start registers the server in whichever of the two the instance has, where `claude mcp list` or `pi mcp list` shows it as `isx`.
Interactive sessions, and `claude -p` or `pi -p` under a systemd unit, all have the tools.

Claude Code also lists the coordination workflow as the prompt `/mcp__isx__coordinate`; Pi does not list MCP prompts.

Claude Code uses the registration only in a trusted workspace.
The `claude` tool already trusts the home directory and every repository the template clones, so only a Claude Code started in another directory shows its ordinary trust prompt, once.

### What a coordinator may control

A coordinator controls only the templates both lists name: `templates` in `mcp.yaml` and its own template's `coordinator.control-over`.

```yaml
coordinator:
  control-over: [tpl-java, tpl-dev]
```

`mcp.yaml` limits every agent, so when you never list a template holding secrets there, no coordinator can control it, whatever a definition says.
The definition says what this coordinator is for, so a new coordinator template for templates you already approved needs no edit to `mcp.yaml`.

A template whose `control-over` is empty or missing does not let its coordinators control any template, and neither does one from a project-local definition.

When several templates in a chain of parents set `coordinator:`, isx uses the one nearest to the branch that sets either key, and ignores those of its parents.
The coordinators of a child that sets only `isx-control-capability: true` cannot control any template until the child lists some.

A template that only one of the two lists names is a mismatch, and its warning names the template, the list missing it and the file to edit.
`isx branch` and the TUI print what a new coordinator may control, and warn of each mismatch.
`isx doctor` reports the same, and so does a refusal from `isx mcp` and its `list_templates` answer.

The TUI's instance details show what a coordinator may control now.
An edit to either file reaches a running coordinator on its next call.
When the coordinator next connects, `isx mcp` releases as orphans its instances from a template it may no longer control.

Otherwise the coordinator gets the tools and limits a host agent gets, under the same `mcp.yaml`.
What differs:

- The session is the instance.
  What the coordinator creates stays held by it across restarts of its Claude Code and of the instance itself, so there is nothing to adopt.
- Its instances are released only with it.
  They become orphans once the coordinator is destroyed, or once it may no longer use the `isx mcp` server.
  A new coordinator under the same name is another session and does not get any of them.
- A withdrawn template releases its instances.
  An instance whose template you have since removed from the approved list is released when the coordinator next connects.
- One connection at a time.
  A new connection from the instance ends its previous one.
- No coordinator makes another.
  Only `isx branch`, from the flag or the template's default, or the dialog's box makes an instance a coordinator.
  The grant never passes to a copy: not to `isx branch --from coord`, and not to anything created through MCP.
  A copy of the coordinator loses the grant and its Claude Code and Pi registrations on its first start.
- The coordinator reaches only the host proxy.
  The proxy serves the `isx mcp` server to the coordinator, so a coordinator works with `--proxy-only`; an `--airgap` instance cannot be a coordinator.
