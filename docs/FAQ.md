# FAQ

Answers to the questions that come up once an agent is working in an instance.
Each answer says what to do first, then why.

### Why does clicking a URL in Codex fail with "No valid browsers detected"?

Use **Ctrl+Shift-click** to open the URL in your host browser.
This worked in our testing: adding Shift lets the host terminal handle the link instead of passing the click to Codex.
The exact modifier keys depend on your terminal; macOS users have also reported success with **Cmd+Shift-click**.

Codex CLI can intercept URL clicks and try to launch a browser inside the isx instance, producing `Failed to open browser for ...: No valid browsers detected`.
This also affects remote SSH sessions and is tracked upstream in [openai/codex#52766](https://github.com/openai/codex/issues/52766).
The `tui.link_activation = "disabled"` setting did not resolve it in our testing, and other users report the same limitation in [openai/codex#48886](https://github.com/openai/codex/issues/48886).

### Why doesn't "copied" from an agent reach my host clipboard?

Agents copy with OSC 52, which isx forwards but cannot apply: your host terminal must support it.
Apple Terminal and GNOME Terminal/VTE do not support OSC 52.
Ghostty, kitty, WezTerm, foot, Alacritty, and iTerm2 support OSC 52.
In iTerm2 enable **Settings → General → Selection → Applications in terminal may access clipboard**.
In tmux set `set -g set-clipboard on` and `set -as terminal-features '*:clipboard'` (tmux 3.2 or newer) in every tmux between the agent and your terminal, on the host and in the instance; one that is missing them drops the copy silently.

### Why can't I mount a host directory read-write to follow agent work in my IDE?

A project directory is not just data — it is an implicit code execution channel.
Build tools, package managers, and IDEs all trust its contents and execute them with your full host privileges.
A read-write mount turns the agent's output into unreviewed host-side code execution, which is exactly the threat model isx exists to prevent.

Two attack surfaces make this dangerous even for "just the project directory":

1. **Executable project content.**
   Build plugins, Makefiles, `gradlew`, `.mvn/jvm.config`, git hooks, IDE run configurations, and dependency declarations with local path references (`<systemPath>`, `file:` deps, Go `replace` directives) all execute when you run a normal build command.
   An agent that modifies a Maven build plugin or a git pre-commit hook gets code execution on your host with your credentials and network access — without you ever intentionally "running the agent's code."

2. **IDE auto-execution.**
   VS Code, IntelliJ, and most editors auto-execute project configuration the moment you open a directory: `.vscode/settings.json` (task auto-run), `.idea/` workspace files, ESLint/TypeScript/Pyright configs that load plugins.
   An agent writing to the project directory can trigger code execution on your host just by the folder being open — no build command required.

These risks are compounded by a **race condition**: with a live read-write mount, files can change between review and execution.
You inspect a git hook or build script, decide it's safe, and run your build — but the agent modified the file between your review and your command.
Unlike `git fetch`, which gives you a specific immutable commit to review and act on, a live mount means your review is never final.

Beyond security, a shared project directory is also **misleading**.
The agent's code runs against the container's execution context — container-local SNAPSHOT dependencies, container-local `node_modules`, container-local pip packages.
None of that comes through the mount.
The source code on the host *looks* like a complete project, but when you build it locally it may behave differently or break entirely because the dependency state is invisible.
The project directory is only a partial view of the agent's environment.

**What to use instead:**

- **`isx://` git remotes** ([Git Remotes](../README.md#git-remotes)): `git fetch <instance>` pulls a consistent, atomic snapshot — a specific commit you can review with `git diff` before merging.
  Even if the agent pushes new commits between your review and your merge, you act on the exact commit you reviewed.
  Git's content-addressed model eliminates torn reads by design.
  This is the intended workflow for getting work out of containers.
- **VS Code Remote SSH / JetBrains Gateway** ([Remote IDE Access](TOOLS.md#remote-ide-access)): the IDE backend runs inside the container while the UI runs on your host.
  You see live edits, have full debugging, and the security boundary stays intact.
  Both are built-in tools (`vscode-remote`, `idea-backend`).
- **`readonly` and `overlay` host-resources** ([Host Resources](TEMPLATES.md#host-resources)): for sharing files *into* the container safely.
  `readonly` is a read-only bind mount; `overlay` gives the container a writable view backed by an ephemeral layer, without modifying the host.
