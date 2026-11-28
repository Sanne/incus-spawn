---
description: Run Claude Code with full autonomy in an isx sandbox, a Linux machine of its own with your project cloned in and no credential inside it.
---

# Run Claude Code in an isx sandbox

This guide takes you from an installed isx to Claude Code at work on your project, in a machine of its own.
Claude Code runs there with every permission, and your Claude credential stays on your host.
The first build takes a few minutes, and every machine after it is instant.

## Before you start

You need isx installed: the [Quick Start](../../README.md#quick-start) has the command for your system.
You also need a Claude credential: an Anthropic API key, a Claude Pro or Max plan, or a Vertex AI project.

## Set up the host

Run init, and choose Claude Code when it asks which coding agent you use:

```shell
isx init
```

Init then asks for your Claude credential, and for a GitHub account, which you may skip.
If you skip GitHub, answer yes when init asks whether the agent's commits carry your name, or the agent cannot commit.

Init sets up the host and builds a template called `tpl-agent`: a machine image with Claude Code installed.
The [Quick Start](../../README.md#quick-start) says what init does on each system.

## Give the template your project

Open `~/.config/incus-spawn/images/tpl-agent.yaml`, which init wrote.
Uncomment its `repos:` and `workdir:` lines, and put your repository's URL in them:

```yaml
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
```

Keep the lines init wrote, such as `tools:` and `default-action: claude`.

Then rebuild the template, which clones the repository into it:

```shell
isx build tpl-agent
```

[Write an isx template for your project](project-template.md) goes further, with the packages your project builds with.
[Declarative Repos](../TEMPLATES.md#declarative-repos) has the other `repos:` fields, such as a branch.
[Claude Code's parameters](../AGENTS.md#parameters) set its model and effort in the same file.

## Branch a machine for one task

A branch is an instant copy-on-write machine made from the template.
Make one for the task, named after it:

```shell
isx branch fix-login --from tpl-agent
```

Claude Code opens inside it, in `~/myproject`, with no sign-in and no permission prompts.
Give it the task.
`isx shell fix-login` opens a shell in the same machine later, from any terminal.

## Check that it works

Claude Code answering your first message shows that the proxy signed its requests.
On the host, `isx proxy activity` lists `fix-login` with the model calls it made.
Inside the machine, no variable or file holds your credential: [Credential Isolation](../../README.md#credential-isolation) says what it holds in its place.

## Take the work back

The agent's work reaches your host as commits.
In your checkout of the project on the host, add the machine as a git remote, fetch, and list its branches:

```shell
git remote add fix-login isx://fix-login/~/myproject
git fetch fix-login
git branch -r --list 'fix-login/*'
```

The agent may commit on a branch of its own rather than the one it found.
Compare the branch it used with yours, here `fix-login/fix-login`:

```shell
git diff HEAD...fix-login/fix-login
```

Take what you like with `git cherry-pick` or `git merge`.
[Review and take an agent's commits](agent-commits.md) covers the rest, such as letting isx add this remote on every branch.

## Clean up

Destroy the machine once you took its work:

```shell
isx destroy fix-login
```

The template stays, so the next task is one `isx branch` away.

## Next steps

- Make a template for each of your projects: [Write an isx template for your project](project-template.md).
- Run several tasks at once: each `isx branch` is a machine of its own.
- Restrict what the machine can reach with a [network mode](../../README.md#network-modes).
- Let Claude Code on your host hand tasks to these machines: [Delegate tasks from Claude Code to agents in isx sandboxes](delegate-over-mcp.md).
