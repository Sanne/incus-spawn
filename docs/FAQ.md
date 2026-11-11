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

Run `isx doctor` on the host, never a login inside the instance.
The credential is stored on the host, so following an agent's advice to log in inside the instance would put a real credential in the instance.
`isx doctor` reports a credential the provider rejected, and the command that replaces it.

### Why does a tool I installed fail with a certificate error?

The proxy signs the HTTPS traffic it intercepts from an instance with the isx CA, which the instance's system certificate store trusts.
Most tools read the system store, Fedora's own JDK and Python packages included.

A JDK or Python `certifi` package from another source can ship its own bundle of trusted root certificates, which lacks the isx CA, so it rejects the proxy's certificate.
The error reads `certificate_unknown`, `PKIX path building failed` or `CERTIFICATE_VERIFY_FAILED`.

isx binds such a runtime to the system store, replacing its bundle with a link, when the runtime is placed directly in a directory isx watches (such as `/opt`).

For a runtime in any other directory, such as a vendor JDK unpacked into `~/tools` or a `certifi` that pip installs into a project's virtualenv, run `isx-trust <dir>` on that directory.
Then repeat the command that failed.

`isx-trust` replaces the runtime's bundle with a link to the system store, so certificate verification stays on.
Never turn verification off instead.
With no argument, `isx-trust` binds every runtime in the watched directories, which covers a runtime the watch has not bound yet.
[What differs from Fedora](FEDORA.md#trust) lists those directories, and the environment variables that cover other runtimes.

### Why can't I mount a host directory read-write to follow agent work in my IDE?

Fetch the agent's work as commits over an `isx://` git remote, or run your IDE's backend inside the instance.
Both are described under "What to use instead" at the end of this answer.

A project directory is not just data: it is an implicit code execution channel.
Build tools, package managers and IDEs all trust its contents and execute them with your full host privileges.
With a read-write mount, what the agent writes is executed on your host without review, which is exactly the threat isx exists to prevent.

Two attack surfaces make a read-write mount dangerous even when it holds "just the project directory":

1. **Executable project content.**
   Build plugins, Makefiles, `gradlew`, `.mvn/jvm.config`, git hooks, IDE run configurations, and dependency declarations with local path references (`<systemPath>`, `file:` deps, Go `replace` directives) all execute when you run a normal build command.

   An agent that modifies a Maven build plugin or a git pre-commit hook gets code execution on your host with your credentials and network access, without you ever intentionally "running the agent's code".

2. **IDE auto-execution.**
   VS Code, IntelliJ and most editors auto-execute project configuration the moment you open a directory: `.vscode/settings.json` (task auto-run), `.idea/` workspace files, ESLint/TypeScript/Pyright configs that load plugins.

   An agent writing to the project directory can trigger code execution on your host just by the folder being open, with no build command required.

**Race condition**: a live mount compounds both risks, since files can change between review and execution.
You inspect a git hook or build script, decide it is safe and run your build, but the agent changed the file after you inspected it.

Unlike `git fetch`, which gives you one immutable commit to review and act on, a live mount means your review is never final.

**Misleading view**: a shared project directory shows only part of the agent's environment.
The agent's code runs against the instance's execution context: instance-local SNAPSHOT dependencies, `node_modules` and pip packages, none of which the mount shows on the host.

The source on the host looks like a complete project, but built on the host it may behave differently or break, because the host does not have the instance's dependencies.

**What to use instead:**

- **`isx://` git remotes** ([Git Remotes](../README.md#git-remotes)): `git fetch <instance>` pulls a consistent, atomic snapshot: a commit you review with `git diff` before merging.
  Even if the agent pushes new commits between your review and your merge, you act on the exact commit you reviewed.

  Git identifies each commit by a hash of its content, so a fetch never gives you a mix of old and new files.
  This is the intended way to get work out of an instance.
- **VS Code Remote SSH / JetBrains Gateway** ([Remote IDE Access](TOOLS.md#remote-ide-access)): the IDE backend runs inside the instance while the UI runs on your host.
  You see live edits, have full debugging, and the security boundary stays intact.
  Both are built-in tools (`vscode-remote`, `idea-backend`).
- **`readonly` and `overlay` host-resources** ([Host Resources](TEMPLATES.md#host-resources)): for sharing files into the instance safely.
  `readonly` is a read-only bind mount; `overlay` gives the instance a writable view backed by an ephemeral layer, without modifying the host.
