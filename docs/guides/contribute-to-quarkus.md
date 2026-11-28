---
description: Build and test Quarkus with your coding agent in an isx sandbox, and contribute within Quarkus's AI policy, where you understand and own every change you send.
---

# Contribute to Quarkus from an isx sandbox

This guide sets up a machine for working on Quarkus: a clone of the code, the JDK and the container runtime its tests use, and your coding agent.
You branch a fresh machine for each change, and the agent builds and tests there with full autonomy.

In this setup, only your own contribution is supposed to reach the Quarkus project, and you send it yourself.

## Quarkus's rules for AI-assisted work

Read the [Quarkus AI and LLM Usage Policy](https://github.com/quarkusio/quarkus/blob/main/AI_POLICY.md) before you start, since it applies to every pull request, issue and comment.
In short, an agent may help you draft code, tests and documentation, but you must understand, validate and take responsibility for all of it.

A contribution that looks copied from an agent's output, with little human oversight, is not acceptable.
An agent or bot may not open pull requests, file issues or post comments on its own.

So in this guide the agent is supposed to work only inside its machine, and you decide what leaves it:

- You read and understand every commit you take from the machine, well enough to argue for it in review.
- You write the pull request description yourself, the way you would without an agent.
- You keep tests focused and in the project's style, as the policy asks.
- You open the pull request from your own fork, as the [Quarkus contributing guide](https://github.com/quarkusio/quarkus/blob/main/CONTRIBUTING.md) asks.

## Before you start

You need isx set up with `isx init`: the [Quick Start](../../README.md#quick-start) covers it.
The machine's first build compiles all of Quarkus, which takes a long while and leaves several gigabytes of Maven artifacts in the template.
Every machine you branch afterwards starts with that build done.

Give the agent its own GitHub account, which `isx init` recommends, or no GitHub account at all.
Its token from the page `isx init` gives cannot push, open a pull request or an issue, or comment on `quarkusio/quarkus` ([why](../GITHUB.md#what-the-token-page-gives)).
With a token for your own account, the agent acts on GitHub as you, so give it another account first.

## Create the template

This guide starts with a small template, so you can see each line Quarkus needs.
For daily work, use the fuller one in [Use the maintained Quarkus template](#use-the-maintained-quarkus-template).

Create a template for Quarkus work:

```shell
isx templates new quarkus-contrib
```

Fill it in:

```yaml
name: quarkus-contrib
description: Quarkus contributions, with Claude Code
parent: tpl-minimal
packages:
  - java-25-openjdk-devel
  - unzip
tools:
  - claude
  - podman
default-action: claude
env:
  - name: JAVA_HOME
    value: /usr/lib/jvm/java-25-openjdk
  - name: MAVEN_OPTS
    value: -Xmx4g
repos:
  - url: https://github.com/quarkusio/quarkus.git
    path: ~/quarkus
    prime: ./mvnw -Dquickly
workdir: ~/quarkus
```

The lines that are particular to Quarkus:

- `java-25-openjdk-devel` and `JAVA_HOME`: Quarkus builds with Java 21 or later, and Fedora packages JDK 25.
- `unzip`: the Quarkus build calls it.
- `podman`: Quarkus tests start databases and other services in containers, through Testcontainers.
- `MAVEN_OPTS`: 4 GB is the smallest heap the Quarkus build works with.
  If your host has the memory, give it more, such as `-Xmx8g`.
- `prime: ./mvnw -Dquickly`: the build runs the Quarkus quick build once, which skips tests and fills the Maven repository, so a machine starts with every module built.

The agent here is Claude Code: `claude` under `tools:` installs it, and `default-action: claude` opens it in each new machine.
To use another agent, put its tool name in both places, such as `codex`.
[Agents](../AGENTS.md) lists each agent, its tool name and the credential it signs in with.

[Image schema fields](../TEMPLATES.md#image-schema-fields) describes every field a template can have, and [A template with every field](../TEMPLATE-EXAMPLE.md) shows them all in one file.

## Build it

```shell
isx build quarkus-contrib
```

The build clones Quarkus and runs the quick build.
If it fails, the error names the prime command, and `isx build quarkus-contrib --verbose` shows its output.

## Branch a machine for one change

Name the machine after the change:

```shell
isx branch fix-config-docs --from quarkus-contrib
```

Your agent opens in `~/quarkus`.
Describe the change, and let it find the code, build and run the tests in the machine.

## Check that it works

Open a shell in the machine with `isx shell fix-config-docs`, and run one extension's build with its tests:

```shell
./mvnw -f extensions/arc clean install
```

Then check that the machine runs containers, which Quarkus tests start through Testcontainers:

```shell
podman run --rm quay.io/podman/hello
```

A passing build shows that the JDK and the Maven repository work, and the greeting shows that the container runtime does.
The [Quarkus contributing guide](https://github.com/quarkusio/quarkus/blob/main/CONTRIBUTING.md#build) lists the builds to run before you send a change.

## Take the change and send it yourself

Fetch the agent's commits into your own checkout of Quarkus on the host, as [Review and take an agent's commits](agent-commits.md) shows.
Read every line, run the tests yourself, and rework what you would not have written.
Squash the commits into the atomic, meaningful ones the contributing guide asks for.

Then push the branch to your fork and open the pull request in the browser, with a description you wrote.
In review, answer every question from your own understanding of the change, never from the agent's.

## Use the maintained Quarkus template

The [incus-spawn-templates](https://github.com/Sanne/incus-spawn-templates) repository keeps `tpl-quarkus`, the template isx's maintainer uses for Quarkus work.
It goes further than the one above, and it is the one to move to once that one builds.

What it adds:

- GraalVM, so the agent can run native builds and the `-Dnative` integration tests.
- The Maven daemon, `mvnd`, with a prime that resolves dependencies in parallel.
- `jasolo`, which lets the agent read the Java sources of Quarkus's dependencies.
- A second clone, the Quarkus quickstarts.
- Its parent templates: JDK 25 with its sources, `gh`, tmux, Starship, Claude Code with a chosen model, and the herdr and zmx terminal tools.

Its prime gives the Maven daemon up to 10 GB of heap, so it needs a host with room for that.
Because its chain includes `gh`, give the agent its own GitHub account first, as [Before you start](#before-you-start) says.

To use it, clone the repository and add it to `searchPaths` in `~/.config/incus-spawn/config.yaml`, as [its README](https://github.com/Sanne/incus-spawn-templates#setup) shows.
Then open `tpl-mydev`, its layer of personal preferences, with `isx templates edit tpl-mydev`, and put your own agent there.
Build and branch it the same way:

```shell
isx build tpl-quarkus
isx branch fix-config-docs --from tpl-quarkus
```

The same repository holds templates for Hibernate ORM, OpenJDK, GraalVM and other projects.

## Clean up

```shell
isx destroy fix-config-docs
```

The template stays, so the next change starts from a built Quarkus.
To bring the template up to the latest Quarkus, rebuild it with `isx build quarkus-contrib`.

## What this setup gives you

Each change gets a full Quarkus workstation of its own, ready the moment you branch it, and you can run several side by side.

- Container images and dependencies download once.
  The proxy on your host keeps every Maven artifact the machines pull, and every container image they pull from Docker Hub, GHCR or Quay, each checked against its digest or upstream checksum ([Caching](../CONFIGURATION.md#caching)).
  Ten machines running the same integration tests pull each database image once, and ask your Maven mirror once.
- Every machine starts the same.
  Each one is a copy of the template, with the same JDK, packages and built Quarkus.
  Its Maven repository holds no snapshots or leftovers from earlier experiments, so a result in one machine holds in the next, and a broken machine is replaced by branching again.
- The agent is free to investigate.
  It runs builds, tests and experiments without asking you first, and can clone other projects to compare.
  Your host's files and your credentials stay out of its reach ([Credential Isolation](../../README.md#credential-isolation)).
- Integration tests do not collide.
  Each machine runs its own Podman, so the databases and services an integration test starts in one machine never meet another machine's, or your host's services.
- You can run a reproducer without auditing it first.
  A reproducer from a bug report brings its own code, dependencies and build plugins, which you would otherwise audit before running them.
  In a fresh machine it cannot read your host's files or any real credential, and you destroy it afterwards.
- Stricter limits are a flag or a line away.
  Branch with `--airgap`, and the machine has no network at all ([Network Modes](../../README.md#network-modes)).
  Set `type: vm` in the template, and its machines are virtual machines with a kernel of their own, on Linux ([Instance types](../TEMPLATES.md#instance-types)).

Through all of it, nothing is supposed to reach Quarkus until you have read it, understood it and sent it yourself.
