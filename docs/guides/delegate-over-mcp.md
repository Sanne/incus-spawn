---
title: Delegate tasks from Claude Code to sandboxed agents
description: Let Claude Code or Codex on your host hand whole tasks to coding agents in isx sandboxes over MCP, each in a machine of its own, and review what comes back.
---

# Delegate tasks from Claude Code to agents in isx sandboxes

This guide lets the coding agent on your host hand work to agents in isx machines, over the Model Context Protocol (MCP).
Your host agent creates a machine from a template you approved, gives the agent inside a task, and follows it while you keep working.
You approve which templates it may use, and you review what comes back.

## Before you start

You need isx set up with `isx init`, and a template that installs a coding agent, such as the `tpl-agent` init builds: the [Quick Start](../../README.md#quick-start) covers both.
You also need a coding agent on your host that speaks MCP; this guide uses Claude Code.

[Run Claude Code in an isx sandbox](claude-code.md) shows how to give `tpl-agent` your project, so a delegated agent starts in it.

## Approve the templates agents may use

An agent on your host can use only the templates you approve, and only you can approve them.
Run the setup step at your own terminal:

```shell
isx mcp setup
```

It asks, for each template that installs a coding agent, whether agents may use it.
Answer yes for `tpl-agent`.
Your answers go to `~/.config/incus-spawn/mcp.yaml`, which no agent can write: [Approving templates](../MCP.md#approving-templates-mcpyaml) lists its other settings.

## Connect your host agent

`isx init` and `isx mcp setup` register isx's MCP server with Claude Code when it is installed on your host.
To register it yourself, or with Codex instead:

```shell
isx mcp register claude
isx mcp register codex
```

For any other agent, `isx mcp config` prints the entry to add to its MCP settings ([Other agents](../MCP.md#other-agents)).
Start a new session of your host agent, so it loads the server.

## Check that it works

Ask your host agent which isx templates it may use.
It calls the `list_templates` tool, and its answer names `tpl-agent` and the agents that template can run.
An empty list means no template is approved yet: run `isx mcp setup` again.

## Delegate a task

Ask your host agent for a task in plain words, and say that an isx machine should do it:

```text
Use isx to delegate this to tpl-agent: make the login form reject an empty
password, with a test. Report back with what changed.
```

The host agent creates a machine from `tpl-agent`, starts the agent inside it with your instruction, and follows the task.
The machine is named `mcp-agent-<hint>-<suffix>`, and `isx list` shows it with `mcp held` in its `OWNER` column.
The agent inside runs with full access to its machine and no approval prompts, since the machine is the boundary.

You can give several tasks at once, and each gets a machine of its own.

## Join the conversation

To steer a task yourself, open its conversation from any terminal, between the agent's turns:

```shell
isx shell mcp-agent-login-a1b2c --task
```

The agent resumes in the directory it worked in.

The command refuses while the agent is in the middle of a turn, or while someone else has joined: wait for the turn to end, and run it again ([Joining a delegate's conversation](../MCP.md#joining-a-delegates-conversation)).
While you are in, your host agent cannot send the task new messages, and when you leave it can again.

## Review what comes back

When the task ends, your host agent reads the delegated agent's report and the change it made.
Ask it for the diff, then review it yourself.

To take the commits into your own checkout, fetch them from the machine as [Review and take an agent's commits](agent-commits.md) shows, with the machine's name as the remote.

## Keep your host agent to the MCP tools

`isx mcp` runs as you, so its limits hold for the MCP tools, not for a host agent that can also run `isx` in its shell.
In Claude Code's settings, allow the isx tools and deny the rest, as [Keeping the agent to the MCP tools](../MCP.md#keeping-the-agent-to-the-mcp-tools) shows.

## Clean up

Ask your host agent to destroy the machines it no longer needs, or run `isx destroy <name>` yourself.
A machine whose session ended is destroyed once 24 hours have passed by default, never while you or its agent are still working in it ([Orphans and adoption](../MCP.md#orphans-and-adoption)).

## Next steps

- Raise `max-instances` and `max-concurrent-tasks` in `mcp.yaml` to run more tasks at once ([Approving templates](../MCP.md#approving-templates-mcpyaml)).
- Run the coordinating agent in a machine of its own as well: [A coordinator in an instance](../MCP.md#a-coordinator-in-an-instance).
- Drive `isx mcp` from a program of your own: [Driving isx mcp from a program](../MCP-PROTOCOL.md).
