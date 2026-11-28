---
title: Write a sandbox template for your project
description: Write an isx template for your project, with your coding agent, its packages and a clone of its code, so every sandbox you branch starts ready to work.
---

# Write an isx template for your project

A template is a machine image you build once, and every machine you branch from it starts with its contents in place.
This guide makes a template for one project: your agent, the packages the project builds with and a clone of its code.
It starts from `tpl-agent`, the template `isx init` built for your agent.

## Before you start

You need isx set up with `isx init`, which built `tpl-agent`: the [Quick Start](../../README.md#quick-start) covers both.
Have your project's clone URL at hand, in its HTTPS form.

## Create the template

Create a template named after your project:

```shell
isx templates new tpl-my-app
```

Your editor opens a short file.
Fill it in for your project:

```yaml
name: tpl-my-app
description: My app, with my agent and its build tools
parent: tpl-agent
packages:
  - maven
repos:
  - url: https://github.com/myorg/my-app.git
    path: ~/my-app
workdir: ~/my-app
```

`parent: tpl-agent` gives the template your agent and its accounts.
Under `packages:`, list the Fedora packages the project needs to build.
Save and close the editor: isx checks the file and says what is wrong, if anything.

[Image schema fields](../TEMPLATES.md#image-schema-fields) lists every other field, and [Declarative Repos](../TEMPLATES.md#declarative-repos) covers private repositories and branches.

## Build it

```shell
isx build tpl-my-app
```

The build clones the repository once and installs the packages.
Building `tpl-my-app` first builds `tpl-agent` if it is missing.

## Branch a machine from it

```shell
isx branch my-app-1 --from tpl-my-app
```

Your agent opens in `~/my-app`, in a machine of its own.

## Check that it worked

In a shell in the machine, from `isx shell my-app-1`, `git -C ~/my-app log -1` shows the commit the build cloned.
A package you listed answers there, such as `mvn --version` for `maven`.
On the host, `isx templates list` shows `tpl-my-app` with its description.

## Change it later

Open the file again with `isx templates edit tpl-my-app`, then rebuild with `isx build tpl-my-app`.
The next machine you branch gets the changed template.

## Next steps

- Give the agent its work: [Run Claude Code in an isx sandbox](claude-code.md) goes from a template to a task.
- Take the agent's commits back: [Review and take an agent's commits](agent-commits.md).
- See a real project's template: [Contribute to Quarkus from an isx sandbox](contribute-to-quarkus.md), [Contribute to Hibernate ORM from an isx sandbox](contribute-to-hibernate-orm.md) or [Build and debug OpenJDK with an agent in an isx sandbox](openjdk.md).
- Build a family of templates on a shared parent: [Inheritance](../TEMPLATES.md#inheritance).
