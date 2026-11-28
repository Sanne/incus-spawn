---
description: Build and test Hibernate ORM against real databases with your coding agent in an isx sandbox, and send every change yourself, under its contributing guide.
---

# Contribute to Hibernate ORM from an isx sandbox

This guide sets up a machine for working on Hibernate ORM: a clone of the code, the JDK it builds with, the container runtime its database tests use, and your coding agent.
You branch a fresh machine for each change, and the agent builds and tests there with full autonomy.

In this setup, only your own contribution is supposed to reach the Hibernate project, and you send it yourself.

## Hibernate's rules for AI-assisted work

Hibernate has not published rules for AI-assisted work, and its [contributing guide](https://github.com/hibernate/hibernate-orm/blob/main/CONTRIBUTING.md) does not mention it.
Check that guide before you start, since rules may have appeared since this page was written.

Two things the contributing guide asks for are yours alone to give:

- Every contribution is made under the [Developer Certificate of Origin](https://developercertificate.org/), which certifies that you have the right to submit the code.
- Every pull request confirms, in its template, that the change is licensed under Apache 2.0 and may be relicensed under LGPL 2.1.

So in this guide, as in the [Quarkus guide](contribute-to-quarkus.md), the agent is supposed to work only inside its machine, and you decide what leaves it:

- You read and understand every commit you take from the machine, well enough to answer for it in review.
- You write the pull request description yourself.
- You open the pull request from your own fork.

## Before you start

You need isx set up with `isx init`: the [Quick Start](../../README.md#quick-start) covers it.
You also need a [Hibernate Jira account](https://hibernate.atlassian.net), since each change needs an `HHH` issue whose key starts its commit messages.

Give the agent its own GitHub account, which `isx init` recommends, or no GitHub account at all.
Its token from the page `isx init` gives cannot push, open a pull request or an issue, or comment on `hibernate/hibernate-orm` ([why](../GITHUB.md#what-the-token-page-gives)).
With a token for your own account, the agent acts on GitHub as you, so give it another account first.

## Create the template

This guide starts with a small template, so you can see each line Hibernate needs.
For daily work, use the fuller one in [Use the maintained Hibernate ORM template](#use-the-maintained-hibernate-orm-template).

Create a template for Hibernate work:

```shell
isx templates new hibernate-orm-contrib
```

Fill it in:

```yaml
name: hibernate-orm-contrib
description: Hibernate ORM contributions, with Claude Code
parent: tpl-minimal
packages:
  - java-25-openjdk-devel
tools:
  - claude
  - podman
default-action: claude
env:
  - name: JAVA_HOME
    value: /usr/lib/jvm/java-25-openjdk
agent_note: |
  Tests run on the embedded H2 database unless -Pdb names another. For a bug in
  one database, start it with ./db.sh <name> and run the tests with -Pdb=<name>.
repos:
  - url: https://github.com/hibernate/hibernate-orm.git
    path: ~/hibernate-orm
    prime: ./gradlew hibernate-core:compileTestJava
workdir: ~/hibernate-orm
```

The lines that are particular to Hibernate:

- `java-25-openjdk-devel` and `JAVA_HOME`: the Hibernate build requires JDK 25 or later.
- `podman`: `./db.sh` starts PostgreSQL, MySQL and the other test databases as containers.
- `agent_note`: a line for the agent's primer, which it reads in every session, so it tests a database's bug on that database rather than on H2 ([When to write a note](../AGENTS.md#when-to-write-a-note)).
- `prime`: the build compiles `hibernate-core` and its tests once, so a machine starts with Gradle and the dependencies downloaded.

The agent here is Claude Code: `claude` under `tools:` installs it, and `default-action: claude` opens it in each new machine.
To use another agent, put its tool name in both places, such as `codex`.
[Agents](../AGENTS.md) lists each agent, its tool name and the credential it signs in with.

[Image schema fields](../TEMPLATES.md#image-schema-fields) describes every field a template can have, and [A template with every field](../TEMPLATE-EXAMPLE.md) shows them all in one file.

## Build it

```shell
isx build hibernate-orm-contrib
```

The build clones Hibernate ORM and compiles `hibernate-core`.
If it fails, the error names the prime command, and `isx build hibernate-orm-contrib --verbose` shows its output.

## Branch a machine for one change

Name the machine after the Jira issue:

```shell
isx branch hhh-12345 --from hibernate-orm-contrib
```

Your agent opens in `~/hibernate-orm`.
Give it the issue, and let it write a test that reproduces it, then the fix.
Hibernate's [test case templates](https://github.com/hibernate/hibernate-test-case-templates/tree/main/orm) are where a bug report's reproducer usually starts.

## Check that it works

Open a shell in the machine with `isx shell hhh-12345`, and run a group of tests on the embedded H2 database:

```shell
./gradlew hibernate-core:test --tests 'org.hibernate.orm.test.flush.*'
```

Then run the same tests on PostgreSQL, in a container of the machine's own:

```shell
./db.sh postgresql
./gradlew hibernate-core:test -Pdb=postgresql --tests 'org.hibernate.orm.test.flush.*'
```

Both passing shows that the JDK, Gradle and the container runtime all work.
The [Hibernate README](https://github.com/hibernate/hibernate-orm/blob/main/README.adoc#testing-and-databases) lists every database `./db.sh` starts.

## Take the change and send it yourself

Fetch the agent's commits into your own checkout of Hibernate ORM on the host, as [Review and take an agent's commits](agent-commits.md) shows.
Read every line, run the tests yourself, and rework what you would not have written.
Start each commit message with the Jira key, and rebase on `main` rather than merging, as the contributing guide asks.

Then push a branch named after the issue to your fork, and open the pull request in the browser.
Keep the pull request template's licence confirmation, and write the description yourself.

## Use the maintained Hibernate ORM template

The [incus-spawn-templates](https://github.com/Sanne/incus-spawn-templates) repository keeps `tpl-hibernate-orm`, the template isx's maintainer uses for Hibernate work.
It goes further than the one above, and it is the one to move to once that one builds.

What it adds:

- A Gradle installation of its own, beside the wrapper.
- The IntelliJ IDEA backend, so you can open the machine's clone in IntelliJ through JetBrains Gateway.
- Its parent templates: JDK 25 with its sources, `gh`, tmux, Starship, Claude Code with a chosen model, and the herdr and zmx terminal tools.

Because its chain includes `gh`, give the agent its own GitHub account first, as [Before you start](#before-you-start) says.

To use it, clone the repository and add it to `searchPaths` in `~/.config/incus-spawn/config.yaml`, as [its README](https://github.com/Sanne/incus-spawn-templates#setup) shows.
Then open `tpl-mydev`, its layer of personal preferences, with `isx templates edit tpl-mydev`, and put your own agent there.
Build and branch it the same way:

```shell
isx build tpl-hibernate-orm
isx branch hhh-12345 --from tpl-hibernate-orm
```

## Clean up

```shell
isx destroy hhh-12345
```

The template stays, so the next change starts from a built Hibernate.
To bring the template up to the latest Hibernate, rebuild it with `isx build hibernate-orm-contrib`.

## What this setup gives you

Each change gets a full Hibernate workstation of its own, ready the moment you branch it, and you can run several side by side.

- Container images and dependencies download once.
  The proxy on your host keeps every Maven artifact the machines pull, and every container image they pull from Docker Hub, GHCR or Quay, each checked against its digest or upstream checksum ([Caching](../CONFIGURATION.md#caching)).
  Ten machines testing on PostgreSQL pull its image once, and ask your Maven mirror once.
- Every machine starts the same.
  Each one is a copy of the template, with the same JDK, packages and compiled Hibernate.
  Its Gradle caches and Maven repository hold no snapshots or leftovers from earlier experiments, so a result in one machine holds in the next.
- The agent is free to investigate.
  It runs builds, tests and experiments without asking you first, and can clone other projects to compare.
  Your host's files and your credentials stay out of its reach ([Credential Isolation](../../README.md#credential-isolation)).
- Databases do not collide.
  Each machine runs its own Podman, so one machine can test on PostgreSQL while another tests on MySQL, each on its database's usual port, and neither meets your host's services.
- You can run a reproducer without auditing it first.
  A reproducer from a bug report brings its own code, dependencies and build plugins, which you would otherwise audit before running them.
  In a fresh machine it cannot read your host's files or any real credential, and you destroy it afterwards.
- Stricter limits are a flag or a line away.
  Branch with `--airgap`, and the machine has no network at all ([Network Modes](../../README.md#network-modes)).
  Set `type: vm` in the template, and its machines are virtual machines with a kernel of their own, on Linux ([Instance types](../TEMPLATES.md#instance-types)).

Through all of it, nothing is supposed to reach Hibernate until you have read it, understood it and sent it yourself.
