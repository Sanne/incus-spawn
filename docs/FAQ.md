# FAQ

Answers to the questions that come up once an agent is working in an instance.
Each answer says what to do first, then why.

### How do I use my ChatGPT subscription for Codex without paying API charges?

On the host, run `isx account add openai`, choose a **ChatGPT** option, and answer yes after its notice.
During first-time setup, choose Codex in `isx init` to get the same sign-in options.
Open the sign-in link in a browser on the host, or choose the device-code option if your browser is on another computer.

Sign in with the account that holds your subscription so Codex uses the plan's included allowance.
This includes ChatGPT Pro through [Codex for Open Source](https://developers.openai.com/community/codex-for-oss).
Choosing an OpenAI API key sends usage to that key's API billing account.

If your instance already uses an API key, add the ChatGPT account under a separate name, such as `oss-pro`.
For an instance named `dev`, run `isx account set dev openai=oss-pro` on the host, then stop and start the instance.

Open a fresh shell in the instance, start `codex`, and enter `/status` to check the account before sending a prompt.
This sign-in is experimental, and isx signs in as Codex's own client, which OpenAI has not endorsed.
Read [A ChatGPT plan, experimental](AGENTS.md#a-chatgpt-plan-experimental) before you choose it.

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

### My build fails with HTTP 429 (Too Many Requests). What do I do?

Wait for the time the error names, then run the build again, and run `isx doctor` on the host to see which registry refused.
The registry refused your host's address or account, and isx did not cause it.

Maven Central and Docker Hub limit the requests from one address, and a shared office or CI address reaches the limit sooner.

isx already sends the registry fewer requests: the proxy's cache is shared by every instance, so a dependency is fetched once.
While the registry refuses, the proxy serves what it has cached, and waits and asks again for what it has not.

The proxy cannot lift the limit, so a host on a throttled address with an empty cache is still refused.
[Registry rate limits](CONFIGURATION.md#registry-rate-limits) has the details.

On a CI runner, keep the proxy's cache between runs, so that each run starts with the artifacts the last one fetched.

### Can an instance use a model server on my host, such as Ollama?

Yes: list its port in `~/.config/incus-spawn/config.yaml`, and point the agent at `host.isx.internal`.
For Ollama, add `exposed-host-ports: [11434]` to `config.yaml`, and an `env:` entry to the template setting `OLLAMA_HOST` to `host.isx.internal:11434`.

Every instance with network then gets the same access to that service, whatever its template.
[A service on the host](CONFIGURATION.md#a-service-on-the-host) says what that access gives away.

### Why does a tool I installed fail with a certificate error?

Run `isx-trust <dir>`, naming the directory that holds the tool's runtime, as in `isx-trust ~/tools/jdk-21`.
Then repeat the command that failed.
Never turn certificate verification off instead: the tool would then accept a certificate from anyone.

The proxy signs the HTTPS traffic it intercepts from an instance with its own certificate authority, the isx CA.
The instance's system certificate store trusts the isx CA, and most tools read that store, Fedora's own JDK and Python packages included.

A JDK or a Python `certifi` package from another source can bring its own bundle of trusted root certificates.
The isx CA is not in that bundle, so the tool rejects the proxy's certificate.
The error reads `certificate_unknown`, `PKIX path building failed` or `CERTIFICATE_VERIFY_FAILED`.

`isx-trust` replaces the runtime's bundle with a link to the system store, so certificate verification stays on.

isx watches a few directories, such as `/opt`.
If you unpack a runtime there, such as `/opt/jdk-21`, isx links it automatically, shortly afterwards.
A runtime anywhere else needs `isx-trust <dir>`, such as a vendor JDK in `~/tools` or a `certifi` that pip installs into a project's virtualenv.

With no argument, `isx-trust` binds every runtime in the watched directories, which covers a runtime the watch has not bound yet.
[What differs from Fedora](FEDORA.md#trust) lists those directories, and the environment variables that cover other runtimes.

A JDK you build from source, such as an OpenJDK build tree, is outside the watched directories too.
Run `isx-trust` on its build directory after the build.
Run it again after a rebuild that re-creates the image, such as a `make images` after a source change, or whenever HTTPS from that JDK fails with `PKIX path building failed`.

The link changes the JDK you test, so OpenJDK's own `VerifyCACerts` test fails on it.
Before that test, replace the link in the image with a copy of the bundle the build made.
If you test the exploded JDK in `jdk`, point its link back at that bundle.
Run `isx-trust` on the build directory again afterwards.

```sh
cd ~/jdk/build/linux-$(uname -m)-server-release
cp --remove-destination support/modules_libs/java.base/security/cacerts images/jdk/lib/security/cacerts
ln -sfn ../../../support/modules_libs/java.base/security/cacerts jdk/lib/security/cacerts
```

### Why can't I mount a host directory read-write to follow agent work in my IDE?

Fetch the agent's work as commits over an `isx://` git remote, or run your IDE's backend inside the instance.
Both are described under "What to use instead" at the end of this answer.

Your build tools, package managers and IDE run what a project directory holds, with your full privileges on the host.
With a read-write mount the agent writes those files, so what the agent writes runs on your host without your review.

For that reason isx does not mount your project directory read-write into an instance.
You give up having the agent's files as a local folder on your host.
In return, the agent's files do not reach your host until you fetch them.

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
The agent builds and runs the code inside the instance, with dependencies installed there: instance-local SNAPSHOT dependencies and pip packages, neither of which the mount shows on the host.

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

### Why did isx stop updating after I upgraded Ubuntu?

Replace the old repository file with the one the [Quick Start](../README.md#quick-start) now writes, then update.
Run this, with `incus-spawn-dev.sources` in place of `incus-spawn.sources` in the address if you follow the dev channel:

```shell
curl -fsSL https://sanne.github.io/isx-apt-releases/incus-spawn.sources | sudo tee /etc/apt/sources.list.d/incus-spawn.sources >/dev/null
sudo rm -f /etc/apt/sources.list.d/incus-spawn.list /etc/apt/sources.list.d/incus-spawn.list.disabled
sudo apt update && sudo apt upgrade
```

Older install steps wrote `incus-spawn.list`, in the one-line format that apt now deprecates.
An Ubuntu release upgrade turns off third-party repositories, and it cannot convert that file, so it leaves `incus-spawn.list.disabled`.
Until you replace it, apt no longer sees new isx releases.

### How do I upgrade a Mac whose VM ran with vfkit?

isx used to run the Mac's VM with vfkit, and now runs it with its own launcher, `isx-vm`.
`brew upgrade incus-spawn` installs it beside `isx`.
Your VM's disks, instances, templates and settings stay as they are.

A VM that was running during the upgrade keeps running under vfkit until it restarts.
Quit every isx you started before the upgrade, then run `isx proxy restart` and `isx vm restart` once.
Your next login or reboot also starts it under `isx-vm`.
`isx vm status` then shows `Launcher: isx-vm`, and `isx doctor` no longer warns that the VM runs under vfkit.

An older isx does not see a VM that `isx-vm` runs, and removes the files the new isx reaches it by.
That includes the TUI, an agent's `isx mcp` and the proxy service.
`pgrep -fl 'isx( |-proxy|$)'` lists the isx processes still running.
An agent's client starts `isx mcp` again from the old binary until you restart the client itself.

Every start of the VM first restarts the proxy service when it runs another version or an older config.
If the new isx then cannot reach the VM, run `isx vm restart` again.

`isx vm restart` also downloads the new VM image before it stops the VM, so a failed download leaves your VM running.
While the new image cannot be downloaded, each start of the VM tries again, warns, and boots the current one.

macOS may ask once more for your home folder and the local network, since the program asking is new.
Both are safe to approve, as before, and isx says so on that restart.
macOS may ask again after a later upgrade, since each release of `isx-vm` is a new program to it.

Homebrew leaves vfkit installed, and `brew autoremove` removes it when nothing else needs it.

A JBang install on a Mac needs `isx-vm` beside `isx`.
Download `isx-vm-macos-aarch64` from the release with `curl`, save it in `~/.local/bin` as `isx-vm`, and make it executable.
Check it against the release's `SHA256SUMS` before you run it.
Without it, `isx vm restart` leaves the VM running and prints the commands for your version.

To go back to an older isx, run `isx vm stop` before you downgrade.
