---
description: Build, debug and explore OpenJDK with your coding agent in an isx sandbox, within OpenJDK's policy that no AI-generated content is ever contributed.
---

# Build and debug OpenJDK with an agent in an isx sandbox

This guide sets up a machine for working on the JDK: a clone of `openjdk/jdk`, the toolchain and libraries its build needs, and your coding agent.
You branch a fresh machine for each question, and the agent builds, debugs and experiments there with full autonomy.

OpenJDK does not accept AI-generated content, so in this setup the agent helps you understand the JDK, and nothing it writes is supposed to reach the project.

## OpenJDK's rules for AI-assisted work

Read the [OpenJDK Interim Policy on Generative AI](https://openjdk.org/legal/ai) before you start.
It says that contributions must not include content generated, in part or in full, by large language models or similar tools.
That covers source code, tests, text and images, in the Git repositories, pull requests, mailing lists, the wiki and JBS issues.

A contribution is still AI-generated if you edit the agent's lines yourself: the policy's own example is 100 generated lines with ten of them edited.

The same policy allows AI tools used privately: to help you comprehend, debug and review OpenJDK code, and to do research.
So in this guide the agent is a research assistant:

- It explains code, finds where a behaviour lives, and builds and runs the JDK to test an idea.
- It reproduces a bug, narrows it down, and tries candidate fixes in its machine.
- Anything you contribute afterwards, you write yourself, from your own understanding, and you check the policy box on the pull request.
- When you share what the agent produced with a colleague, you mark it as AI-generated, as the policy asks.

## Before you start

You need isx set up with `isx init`: the [Quick Start](../../README.md#quick-start) covers it.
The template installs the JDK's build dependencies, and each machine builds the JDK once, which takes a long while and needs several gigabytes of disk.


Give the agent its own GitHub account, which `isx init` recommends, or no GitHub account at all.
That account's token cannot push, open a pull request or an issue, or comment on `openjdk/jdk` ([why](../GITHUB.md#what-the-token-page-gives)).
With a token for your own account, the agent acts on GitHub as you, so give it another account first.

## Create the template

This guide's template has the packages of the maintained `tpl-openjdk`, and none of the tools the build does not need.
For daily work, use the full one in [Use the maintained OpenJDK template](#use-the-maintained-openjdk-template).

Create a template for JDK work:

```shell
isx templates new openjdk-research
```

Fill it in with these packages, which have been tested to build the JDK:

```yaml
name: openjdk-research
description: OpenJDK builds and research, with Claude Code
parent: tpl-minimal
tools:
  - claude
default-action: claude
packages:
  # Boot JDK: JDK 27 on Fedora 45
  - java-latest-openjdk-devel
  # Core build toolchain
  - gcc
  - gcc-c++
  - make
  - autoconf
  - gdb
  - git
  - zip
  - unzip
  - file
  - cpio
  - gawk
  - which
  - diffutils
  - findutils
  - tar
  - patch
  - pkgconf-pkg-config
  # Client libraries: AWT/Swing, sound, printing, image codecs, color management
  - alsa-lib-devel
  - cups-devel
  - fontconfig-devel
  - freetype-devel
  - libXtst-devel
  - libXt-devel
  - libXrender-devel
  - libXrandr-devel
  - libXi-devel
  - libjpeg-turbo-devel
  - libpng-devel
  - giflib-devel
  - lcms2-devel
  - libffi-devel
  # hsdis (JIT disassembly) backends
  - capstone-devel
  - binutils-devel
  # Faster incremental rebuilds
  - ccache
  # make docs
  - graphviz
  - pandoc
  # Debugging and profiling
  - perf
  - strace
env:
  - name: JAVA_HOME
    value: /usr/lib/jvm/java-latest-openjdk
agent_note: |
  Run configure with CC=/usr/bin/gcc CXX=/usr/bin/g++. Without them it aborts on
  /usr/lib64/ccache/gcc being a symlink; dropping --enable-ccache "fixes" it and
  silently costs every later rebuild.
repos:
  - url: https://github.com/openjdk/jdk.git
    path: ~/jdk
workdir: ~/jdk
```

The lines that are particular to the JDK:

- `java-latest-openjdk-devel` and `JAVA_HOME`: the boot JDK, which must be the release before the one you build, or the same one.
  On Fedora 45 it is JDK 27, which builds the mainline JDK 28.
  The template builds on `tpl-minimal` rather than a Java template, so no older JDK is installed to confuse `configure`.
- The toolchain group: the compilers and tools the JDK build runs.
- The client libraries: the headers for the desktop, sound, printing, image and colour libraries that `configure` checks for.
- `capstone-devel` and `binutils-devel`: backends for hsdis, which shows the code the JIT compilers produce.
- `ccache`, `graphviz` and `pandoc`: faster rebuilds, and the tools `make docs` uses.
- `gdb`, `perf` and `strace`: a debugger and profilers for the JVM itself.
- `agent_note`: a line for the agent's primer, which it reads in every session, so it runs `configure` the right way the first time ([When to write a note](../AGENTS.md#when-to-write-a-note)).

The agent here is Claude Code: `claude` under `tools:` installs it, and `default-action: claude` opens it in each new machine.
To use another agent, put its tool name in both places, such as `codex`.
[Agents](../AGENTS.md) lists each agent, its tool name and the credential it signs in with.

[Image schema fields](../TEMPLATES.md#image-schema-fields) describes every field a template can have, and [A template with every field](../TEMPLATE-EXAMPLE.md) shows them all in one file.
The JDK's own [build instructions](https://github.com/openjdk/jdk/blob/master/doc/building.md) list every dependency and `configure` option.

## Build it

```shell
isx build openjdk-research
```

The build installs the packages and clones the JDK.

## Branch a machine for one question

Name the machine after what you are looking into, such as a JBS issue:

```shell
isx branch jdk-8360000 --from openjdk-research
```

Your agent opens in `~/jdk`.
Ask it to build the JDK, explain the code involved, reproduce the bug and narrow it down.

## Check that it works

Open a shell in the machine with `isx shell jdk-8360000`, then configure and build the JDK:

```shell
CC=/usr/bin/gcc CXX=/usr/bin/g++ bash configure \
  --with-boot-jdk=/usr/lib/jvm/java-latest-openjdk \
  --with-hsdis=capstone --enable-hsdis-bundling \
  --enable-ccache
make images
build/*/images/jdk/bin/java -version
```

The last command prints the version of the JDK you built, not the boot JDK's.
The `CC=` and `CXX=` prefix is needed: Fedora's `ccache` package puts its compiler links first on the `PATH`, and `configure` refuses them.
The template's `agent_note` tells the agent the same.
After a change, `make images` rebuilds only what the change touched.

## Use what the agent found, not what it wrote

What you take from the machine is understanding: where the bug lives, why it happens, and which approach worked.
Read the agent's explanation, its reproducer and its experiments, and check each claim against the code yourself.

If you then contribute, write the change in your own checkout of the JDK, from scratch.
Never fetch, copy or adapt the agent's commits, tests or text into a contribution, since any of it makes the contribution AI-generated.
The [OpenJDK Developers' Guide](https://openjdk.org/guide/) describes how a change is proposed, reviewed and integrated.

## Use the maintained OpenJDK template

The [incus-spawn-templates](https://github.com/Sanne/incus-spawn-templates) repository keeps `tpl-openjdk`, the template isx's maintainer uses for JDK work.
It goes further than the one above, and it is the one to move to once that one builds.

What it adds:

- jtreg, so the agent can run the JDK's regression tests with `make test`.
- GoogleTest, for the HotSpot C++ unit tests.
- The Skara command line tools that OpenJDK's workflow uses, such as `git jcheck` and `git webrev`.
- A primer for Claude Code that says how the machine's build is wired, `configure` line included.
- `gh`, tmux, Starship and Podman.

The first four come from the same repository rather than isx, which is why the template above leaves them out.
Because it includes `gh`, give the agent its own GitHub account first, as [Before you start](#before-you-start) says.

To use it, clone the repository and add it to `searchPaths` in `~/.config/incus-spawn/config.yaml`, as [its README](https://github.com/Sanne/incus-spawn-templates#setup) shows.
Then build and branch it the same way:

```shell
isx build tpl-openjdk
isx branch jdk-8360000 --from tpl-openjdk
```

## Clean up

```shell
isx destroy jdk-8360000
```

The template stays, so the next question starts with every dependency installed.
To bring its clone up to the latest JDK, rebuild it with `isx build openjdk-research`.

## What this setup gives you

Each question gets a full JDK workstation of its own, ready the moment you branch it, and you can run several side by side.

- Build packages download once.
  isx keeps the packages a build installs on your host, so rebuilding the template, or building another with the same toolchain, does not download them again ([Caching](../CONFIGURATION.md#caching)).
- Every machine starts the same.
  Each one is a copy of the template, with the same boot JDK, toolchain and libraries.
  Its build directory holds no leftovers from earlier experiments, so a result in one machine holds in the next.
- The agent is free to investigate.
  It builds, runs a debugger and tries changes without asking you first, as root in a machine of its own.
  Your host's files and your credentials stay out of its reach ([Credential Isolation](../../README.md#credential-isolation)).
- Experiments do not collide.
  One machine can build the JDK with a candidate fix while another builds it without, and the agent runs the same reproducer on both.
  Each has its own build directory, so different `configure` options never mix.
- Long hunts run without you.
  The agent can bisect the JDK's history, building and testing at each step for hours, while you keep working in your own checkout.
- You can run a crash reproducer without auditing it first.
  In a fresh machine it cannot read your host's files or any real credential, and you destroy it afterwards.
- Stricter limits are a flag or a line away.
  Branch with `--airgap`, and the machine has no network at all ([Network Modes](../../README.md#network-modes)).
  Set `type: vm` in the template, and its machines are virtual machines with a kernel of their own, on Linux ([Instance types](../TEMPLATES.md#instance-types)).

Through all of it, the agent's work is supposed to stay in its machine, and what you contribute to OpenJDK must be your own.
