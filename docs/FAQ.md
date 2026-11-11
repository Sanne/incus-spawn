# FAQ

Answers to the questions that come up once an agent is working in an instance.
Each answer says what to do first, then why.

### Why does clicking a URL in Codex fail with "No valid browsers detected"?

Use Ctrl+Shift-click to open the URL in your host browser.
In our testing, adding Shift lets the host terminal handle the link instead of passing the click to Codex.
The exact modifier keys depend on your terminal; macOS users have also reported success with Cmd+Shift-click.

Codex CLI can intercept URL clicks and try to launch a browser inside the isx instance, producing `Failed to open browser for ...: No valid browsers detected`.
This also affects remote SSH sessions and is tracked upstream in [openai/codex#52766](https://github.com/openai/codex/issues/52766).
The `tui.link_activation = "disabled"` setting did not resolve it in our testing, and other users report the same limitation in [openai/codex#48886](https://github.com/openai/codex/issues/48886).

### Why doesn't "copied" from an agent reach my host clipboard?

Use a terminal that supports OSC 52, the escape sequence that sets a terminal's clipboard, and let every tmux between the agent and that terminal pass the sequence through.
Agents copy with OSC 52, which isx forwards to your host terminal but cannot apply itself, so your host terminal must support it.

| Terminal | OSC 52 |
|---|---|
| Ghostty, kitty, WezTerm, foot, Alacritty | Supported |
| iTerm2 | Supported; enable Settings → General → Selection → Applications in terminal may access clipboard |
| Apple Terminal, GNOME Terminal/VTE | Not supported |

In tmux set `set -g set-clipboard on` and `set -as terminal-features '*:clipboard'` (tmux 3.2 or newer).
Both settings go in every tmux between the agent and your terminal, on the host and in the instance: a tmux without them drops the copy silently.

### What do I do when an agent in an instance reports an authentication error?

Run `isx doctor` on the host, and do not log in inside the instance, even when the agent asks you to.
The credential that failed is stored on the host, and `isx doctor` reports it with the command that replaces it.

A login inside the instance would put a real credential where the agent can read it.

### Why does a tool I installed fail with a certificate error?

Run `isx-trust <dir>` on the directory that holds the tool's runtime, then repeat the command that failed.
Never turn certificate verification off instead: the tool would then accept a certificate from anyone.

The error reads `certificate_unknown`, `PKIX path building failed` or `CERTIFICATE_VERIFY_FAILED`.
It means the tool brought its own bundle of trusted root certificates, and the isx CA is not in that bundle.
A JDK or a Python `certifi` package from a source other than Fedora can ship such a bundle.

The proxy signs the HTTPS traffic it intercepts from an instance with the isx CA, which the instance's system certificate store trusts.
Most tools read the system store, Fedora's own JDK and Python packages included.

`isx-trust` replaces the runtime's bundle with a link to the system store, so certificate verification stays on.
isx does the same without being asked when the runtime is placed directly in a directory isx watches (such as `/opt`).

A runtime in any other directory needs the command, such as a vendor JDK unpacked into `~/tools` or a `certifi` that pip installs into a project's virtualenv.

With no argument, `isx-trust` binds every runtime in the watched directories, which covers a runtime the watch has not bound yet.
[What differs from Fedora](FEDORA.md#trust) lists those directories, and the environment variables that cover other runtimes.

### Why can't I mount a host directory read-write to follow agent work in my IDE?

Fetch the agent's work as commits over an `isx://` git remote, or run your IDE's backend inside the instance.
Both are described under "What to use instead" at the end of this answer.

isx never mounts a host directory read-write into an instance.
You give up having the agent's files appear directly in a directory on your host.
In return, nothing the agent writes reaches your host until you fetch it.

Your build tools, package managers and IDE run what a project directory holds, with your full privileges on the host.
With a read-write mount the agent writes those files, so what the agent writes runs on your host without your review.

Two things make a read-write mount dangerous even when it holds only the project directory:

1. **Executable project content.**
   Build plugins, Makefiles, `gradlew`, `.mvn/jvm.config`, git hooks, IDE run configurations, and dependency declarations with local path references (`<systemPath>`, `file:` deps, Go `replace` directives) all execute when you run a normal build command.

   An agent that modifies a Maven build plugin or a git pre-commit hook gets code execution on your host with your credentials and network access, without you ever intentionally "running the agent's code".

2. **IDE auto-execution.**
   VS Code, IntelliJ and most editors auto-execute project configuration the moment you open a directory: `.vscode/settings.json` (task auto-run), `.idea/` workspace files, ESLint/TypeScript/Pyright configs that load plugins.

   An agent writing to the project directory can trigger code execution on your host just by the folder being open, with no build command required.

**Race condition**: a live mount compounds both risks, since files can change between review and execution.

You inspect a git hook or build script and decide it is safe.
The agent is still working, and it changes that file while you read or just afterwards.
Nothing on your side shows the change.
You run your build, and the build runs a version you never reviewed.

Unlike `git fetch`, which gives you one immutable commit to review and act on, a live mount means your review is never final.

**Misleading view**: a shared project directory shows only part of the agent's environment.
The agent builds and runs the code inside the instance, with dependencies installed there: local SNAPSHOT dependencies and pip packages, neither of which the mount shows on the host.

The source on the host looks like a complete project, but built on the host it may behave differently or break, because the host does not have the instance's dependencies.

**What to use instead:**

- **`isx://` git remotes** ([Git Remotes](../README.md#git-remotes)): `git fetch <instance>` pulls a consistent, atomic snapshot: a commit you review with `git diff` before merging.
  The code you reviewed is the code you merge, whatever the agent does afterwards.

  Git identifies each commit by a hash of its content, so a fetch never gives you a mix of old and new files.
  This is the intended way to get work out of an instance.
- **VS Code Remote SSH / JetBrains Gateway** ([Remote IDE Access](TOOLS.md#remote-ide-access)): the IDE backend runs inside the instance while the UI runs on your host.
  You see live edits and have full debugging, and the project's build and configuration run inside the instance, not on your host.
  Both are built-in tools (`vscode-remote`, `idea-backend`).
- **`readonly` and `overlay` host-resources** ([Host Resources](TEMPLATES.md#host-resources)): for sharing host files with the instance, which cannot change them on the host.
  `readonly` is a read-only bind mount; `overlay` gives the instance a writable view backed by an ephemeral layer, without modifying the host.
