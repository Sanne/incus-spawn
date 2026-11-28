---
description: Keep a coding agent working in its isx sandbox when you close the terminal or lose the SSH connection, and find it again from any terminal with zmx.
---

# Keep agent sessions alive with zmx and isx

An agent in an isx machine runs in the terminal you started it from, so closing that terminal ends it.
With the `zmx` tool in the template, the agent runs in a zmx session inside the machine, which outlives every terminal.
This guide takes you from an installed isx to an agent still at work when you return.

## Before you start

You need an agent template, such as the `tpl-agent` that `isx init` writes.
[Run Claude Code in an isx sandbox](claude-code.md) sets one up, and this guide uses it.
Any other template takes the tool the same way.

## Add zmx to the template

Open `~/.config/incus-spawn/images/tpl-agent.yaml` and add `zmx` to its `tools:` list:

```yaml
tools:
  - claude
  - zmx
```

Then rebuild the template:

```shell
isx build tpl-agent
```

A machine branched before the rebuild does not get zmx, so branch a new one for the next task.
[Terminal Session Persistence](../TOOLS.md#terminal-session-persistence-zmx) has the tool's `auto_attach` parameter, whose `"false"` installs zmx without entering it.

## Start the agent in its session

Branch a machine for the task:

```shell
isx branch fix-login --from tpl-agent
```

Claude Code opens inside the machine's zmx session, named `isx`.
Give it a task that takes a while.

## Open the session from a second terminal

Every `isx shell` enters the same session `isx`, so a second terminal shows the same screen:

```shell
isx shell fix-login
```

Both terminals show the agent, and either one can type to it.
In a host terminal that runs tmux, `isx shell` opens a plain shell instead, so enter the agent's session with `--session isx`.
For a shell of your own beside the agent, enter another session, which `--session` creates when it is missing:

```shell
isx shell fix-login --session work
```

In the TUI, `n` on the machine opens a new session.
[`isx shell`](../CLI.md#isx-shell) has the rules for a session's name, and `--no-attach` for a shell outside zmx.

## Check that it worked

Close every terminal that shows the agent while it is in the middle of its task.
A dropped SSH connection to the host you run isx on does the same.
Then open a new terminal and enter the session again:

```shell
isx shell fix-login
```

The agent is still there, and so is what it did while no terminal showed it.
On Linux, the host's own zmx lists the session as `isx-fix-login`: [Terminal Session Persistence](../TOOLS.md#terminal-session-persistence-zmx) has the commands that reach it.

## Clean up

Destroy the machine once you have taken its work, which ends its sessions too:

```shell
isx destroy fix-login
```

## Next steps

- Take the agent's work back to your host: [Review and take an agent's commits from an isx sandbox](agent-commits.md).
- Run several agents at once, each in a machine and session of its own: every `isx branch` is one.
