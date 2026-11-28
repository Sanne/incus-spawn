---
description: Give herdr on your host isx sandboxes as SSH machines, each added to herdr on its first start and removed when you destroy it.
---

# Use herdr with isx sandboxes

This guide takes you from an installed isx to herdr running agents in isx machines.
[herdr](https://herdr.dev) runs agents in terminal workspaces, on your host or on SSH machines it lists.
isx adds each machine you branch to that list by itself, and removes it again when you destroy the machine.

## Before you start

You need isx set up with `isx init`: the [Quick Start](../../README.md#quick-start) covers it, and builds `tpl-agent`, a template with your coding agent.
You also need herdr installed on your host, so that `herdr` is on your `PATH`.
isx adds machines to herdr only when it finds it there.

## Make a template with herdr

Create a template that adds the `herdr` tool to `tpl-agent`:

```shell
isx templates new tpl-herdr
```

Fill in the file your editor opens:

```yaml
name: tpl-herdr
description: My agent, reached from herdr on my host
parent: tpl-agent
tools:
  - herdr
```

The `herdr` tool installs herdr's server in the template, and `sshd`, which herdr connects through.
[Agents from the host's herdr](../TOOLS.md#agents-from-the-hosts-herdr) on the Tools page has its details.

Then build it:

```shell
isx build tpl-herdr
```

## Branch a machine

```shell
isx branch agent-1 --from tpl-herdr
```

On the machine's first start, isx adds it to herdr and prints a line that says so: `Added agent-1 to herdr`.
herdr then starts its server in the machine.

At a terminal, the branch then opens your agent in the machine, since `tpl-agent` starts it by default.
Leave it running, and run every command below in another terminal on your host, where herdr is installed.

## Check that it worked

`herdr machine list` lists the machine by its name, `agent-1`, and this reaches its server:

```shell
herdr --machine agent-1 agent list
```

In isx's TUI, the machine's details (F3) say isx added it to herdr.

If isx printed that it is not adding the machine yet because `ssh agent-1` does not reach it, the SSH setup is missing.
Run `ssh agent-1` to see why: once it works, isx adds the machine on its next start.

## Stop and start it

Stop the machine, and start it again:

```shell
isx stop agent-1
isx start agent-1
```

After the stop, `herdr machine list` shows the machine `disabled`, and herdr leaves it stopped.
After the start, it shows the machine `enabled` again.
[herdr](../INTEGRATIONS.md#herdr) on the integrations page says what disabling and enabling the machine do, and what a stop without isx does.

herdr's server in the machine does not run again until herdr opens the machine.
Until then, `herdr machine status` reports the server stopped, and `herdr --machine agent-1` cannot reach it.
Open the machine in herdr, and both work again.

## Clean up

```shell
isx destroy agent-1
```

isx removes the machine from herdr, as [herdr](../INTEGRATIONS.md#herdr) on the integrations page says.

## Next steps

- Branch one machine per agent: each `isx branch` from `tpl-herdr` appears in herdr as a machine of its own.
- Give the template your project, so each agent starts in it: [Write an isx template for your project](project-template.md).
- Take the agents' commits back to your host: [Review and take an agent's commits](agent-commits.md).
