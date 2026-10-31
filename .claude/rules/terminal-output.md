---
paths:
  - "common/src/main/java/dev/incusspawn/util/BuildOutput.java"
  - "common/src/main/java/dev/incusspawn/util/Output.java"
  - "common/src/main/java/dev/incusspawn/util/TerminalOutput.java"
  - "common/src/main/java/dev/incusspawn/util/CopyingOutput.java"
  - "common/src/main/java/dev/incusspawn/util/TerminalProgress.java"
  - "common/src/main/java/dev/incusspawn/tool/**"
  - "cli/src/main/java/dev/incusspawn/command/BuildProgress.java"
  - "cli/src/main/java/dev/incusspawn/command/RepoCloner.java"
  - "common/src/main/java/dev/incusspawn/Warnings.java"
  - "cli/src/test/java/dev/incusspawn/RawOutputGateTest.java"
  - "cli/src/test/java/dev/incusspawn/StreamSwapGateTest.java"
  - "cli/src/test/java/dev/incusspawn/StepLabelGateTest.java"
---

# Terminal Output

All multi-step lifecycle commands use `BuildOutput` (`common/.../util/BuildOutput.java`) for terminal output formatting -- not just build/branch but also `vm` (start/stop/resize), `destroy`, `update-all`, `update-base`, `project`, and the shared `VmManager`.
`isx init` keeps its own frames (the setup banner, the numbered step boxes, the closing box), which `InitCommand` lays out and `BuildOutput.frame` draws as given (a kind of its own: not a result, nothing off a terminal); what it reports between them is steps, warnings and errors like any command's (#1703).
Its questions are shown by the `Prompts` that reads their answers (`show`, `ask`, and `say` for a line said only because one is asked), which the terminal's shows through `BuildOutput.prompt` at the step indent; `BaseCommand.askYesNo` asks a yes/no question that way.
Every question in `cli/command` is asked through `Prompts` (`askYesNo` for yes or no), `isx update-base`'s choice and `isx run`'s wait included; the old `askConfirmation` overloads, which printed the question as the caller wrote it, are gone.
This centralizes ANSI constants and step patterns -- individual commands should not define their own.
`BuildOutput`'s printing methods delegate to the bound `Output` (#1703); the printing itself, with the indent depth and the live step, lives in `TerminalOutput`.
A change to how a line looks goes there; a new kind of message is a method on `Output`, with a default for outputs that are not terminals.
Key helpers: `section()` for a block of work at column 0, `header()` (bold bullet) / `buildHeader()` / `branchHeader()`, `group(title, detail)` (try-with-resources; `▸ Title  detail`, children one level deeper, a blank line on close -- used for Packages, Tools, Skills, Repositories) with `list()` for a wrapping comma-separated list, `step()` for complete lines, `ok()` for a `✓` result line, `stepStart()`/`stepDone()`/`stepDone(detail)`/`stepBreak()` for slow operations, `stepProgress(detail)` for the live phase (a `Supplier<String>`, read again on every frame, for a value that keeps changing), `stepNote()`/`stepWarn()` for detail one level under the step above, `note()`, `warn()` (both warnings end with a blank line, which a closing group does not repeat), `error()` (why a command failed: red `Error:` on stderr, no blank line), `warnBanner()`, `success()`.

**Five kinds, and bare labels (#1791).**
Every line under a header is picked without judgement: work that was done is a live step (`stepStart`/`stepDone`, or `ok` when it was already complete), work that was skipped is a `note`, the command's own answer is a `step`, detail about the step above is `stepNote`/`stepWarn`, and `success` closes the block.
`success` is drawn at the header's column, `  ✓ <bold msg>`, after a blank line, worded like any ✓ line (`tpl-dev built`, no period).
`stepFail(msg)` is `stepBreak()` and then `msg` as a `stepWarn` under the `✗`.
`BuildOutput.liveStep(label, work)` runs work that starts no step of its own as a live step: a ✓ when it returns true, a ✗ when it returns false or throws, and what the work prints meanwhile freezes the line and is shown below it.
A label is a bare phrase in source, with no `...` and no period: `TerminalOutput` prints `label... done.` on a plain terminal and the spinner and `✓ label` on an ANSI one.
`StepLabelGateTest` fails on a `step`, `stepStart`, `liveStep`, `ok`, `success`, `note` or `stepFail` call whose last string literal (a `liveStep`'s first argument's) ends in `...` or `…`, or in a period for `stepStart`, `liveStep`, `ok` and `success`.

**The seam (#1703).**
`BuildOutput` is the one way out: its static methods hand every message to the bound `Output`, a `TerminalOutput` unless the owner of the terminal bound another with `BuildOutput.bind`.
Only owners bind: the TUI binds `Output.diagnosticsTo(warningLog::add)` while it draws or reloads, and `Output.copying(terminal, drawing)` around every action it lends the terminal to.
`PreTuiOutput` binds `Output.holding`, `isx mcp` binds `TerminalOutput.stderrOnly()` (`StdioGuard`), and the proxy binds `Output.logTo(ProxyLog::info, ProxyLog::trace, ProxyLog::warn)`: progress as info, notes (a request's debug line) on stderr alone, warnings as warnings.
An output that is not a terminal drops progress and notes, and still receives every diagnostic: `error`, `warnAside`, `stepWarn` and `warnBanner` default to `warn`, and `stepFail` reports through `stepWarn`.
Bindings nest, and closing one out of order throws.
Shared code takes an `Output` from its caller (`InstanceLifecycle.startForUse(..., out)`, `ensureReady(..., out)`, `StaticIpAllocator.claim`): the CLI passes `BuildOutput.bound()`, the TUI its `drawing` log from code it runs while it draws, background threads included.
Code the TUI runs once it has released the terminal (the `pendingAction` switch, `ShellLaunch.shellInto`, a branch) reports through `BuildOutput`, and the copying binding keeps its warnings in the log (#1646); a failed step is not copied, since the status line reports it.
Code with no caller to take a sink from (a definition loader built several layers down, as `ToolProxyResolver` builds `ToolDefLoader`s) reports through `Warnings.warn()`: to the bound output, on stderr alone on a terminal (`Output.warnAside`, so a query's stdout holds only results), each distinct message once per destination.
A long-running process that re-reads definitions calls `Warnings.forgetReported()`, as the proxy does on each reload.
`common/lifecycle`, `common/config`, `common/vm` and `common/proxy` report through the caller's output or `BuildOutput` alone (#1690): GUI and KVM passthrough return why they could not (`Unavailable`), and the branch warns of it once, the cause with its advice and what it does instead.
Before the TUI opens, bare `isx` holds `Warnings` for the TUI's log and waits for Enter if anything else was printed (`PreTuiOutput`, #1154), so a pre-TUI notice is a `Warnings.warn`.
The proxy daemon's own lines go through `ProxyLog`, its one gate: `stdout` for its help, version and start banner, the levels (`info`, `warn`, `error`, with advice lines) for what its lifecycle log keeps, and `trace` for a request's line, on stderr alone.

**Clean off a terminal (#1037).**
Every escape `BuildOutput` writes goes through `styled()`/`ansi()`, which is `TerminalProgress.isAnsiTerminal()`: false without a console, for `TERM=dumb`, and under a non-empty `NO_COLOR`.
`warn`, `stepWarn`, `note` and `warnBanner` write to stderr; steps, headers, groups and `success` to stdout, so a piped action command's stdout holds what it did, not diagnostics.
A line that *is* the command's result (a dry-run's "Would delete ...", pool usage, the current base image, "already installed") is a `step()`, never a `note()`.
A query's answer (a status report, a table, `OutputFormat`'s records) is printed as given to `BuildOutput.results()`: the terminal's stdout, nothing while the TUI draws.
Printers take it as a `PrintStream`, and tests hand them a `RecordingOutput`'s `results()`, which records each line as a `RESULT`.
Styled text anywhere, a command included, goes through the public `BuildOutput.styled(style, text)` with its colour constants; only paths that draw on an ANSI terminal alone (the live step, `TerminalProgress` line formatters, the shell status bar) append them directly.
`TerminalLink.link()` asks the same gate (`BuildOutput.ansi()`) and prints the bare URL off a terminal.
An AI answer never reaches the terminal raw (#1413): `TerminalLink.plainText` turns what `OutputFormat.isControl` names into spaces, line feeds and tabs apart.
`TerminalLink.parseSegments` and `linkify` pass the text around a link through it, and `isx ask` prints a chunk through it off a terminal.
An AI request's error goes through `OutputFormat.oneLine`: `AiHelpClient` passes a provider's text through it, and AI Help every error it shows.
A build step's label quotes definition text too (#1610): `RepoCloner.repoDisplayName`, `BuildProgress`'s step runners (`runWithSpinner`, `runDnf`, `runLiveStep`) and its shared renderer (`formatStepLine`, with the dim context) pass it through `oneLine`.
So step lines and output headers show a repository's URL or a COPR name as text.
Output captured from a guest command is someone else's text too (#1588).
`BuildOutput.guestText` is how such output is shown: complete control strings (OSC, DCS, APC and the like), control sequences and other escapes dropped, each line's last text a carriage return left, then `TerminalLink.plainText`.
Its one pattern ends a control string at the next escape or introducer, as a terminal does, so a guest's long line costs linear time, never a stall.
A build step's `StepProgress` cleans its detail and log through it as it is made, the detail also through `OutputFormat.oneLine`, since a prime is code the repository controls.
So the live line, the plain failure line, the log's warning and the saved failure report all show it as text, and the failure line is the last one with text left.
A build's single steps (`BuildProgress.runDnf`, `runWithSpinner`, `runLiveStep`) are the seam's live step, drawn by the renderer every step uses (#1791): a failed one ends in its ✗ (a plain terminal ends its `label...` line), then, when it captured a log, one warning, `label failed` with the detail when the log does not end on it, the end of the log under it.
The exception carries all of the log for the failure report (#1703).
A failed prime also names its step and command (#1742), its carriage returns turned to spaces before `guestText`, so one cannot draw over the step's name: the failure line reads ``prime `<command>` failed`` with the command's first line cut to 40 characters, so the reason after it survives the terminal's width.
Its output's heading reads `prime output`, and the whole command leads that output as a shell shows it (`$ `, then `> ` per continued line), so a report trimmed to the last 100 lines keeps it.
The failure report holds only the command that failed, so each build also keeps a log of every command its definitions declared (`BuildLog`, `Environment.buildLogFile`): tools' `run`, `run_as_user` and `verify`, repositories' `prime`.
A command whose exec throws is recorded too, as exit status -1 with why, and the file is readable by its owner alone.
They run through `Container.runDeclared` (`runDeclaredAsUser`, `shDeclaredAsUser`), which record each into the `CommandLog` its build container was made with, as `$ <command>`, all its output and its exit status.
Only those commands, never isx's own: those can carry an instance's proofs, and each entry is also scrubbed and shown through `guestText` (`SecretRedactor.scrubGuest`).
A build's own temporary proofs do reach those commands, since a login shell sources them, so the scrub removes the marker and digest of every proof isx mints and leaves its public prefix (`ProofToken.ANY_PROOF`, from the parts `tokenFor` joins).
A pattern that also took the prefix would open with an unbounded run, and searching a long line of such characters would take quadratic time.
`ProofScrubTest` checks one proof per placeholder a built-in tool declares, so a new kind is covered without being listed.
Every saved copy of what a guest printed is scrubbed as printed and as shown (`SecretRedactor.scrubGuest`, #1878): the build log, the failure report and the instance's `~/inbox/BUILD_FAILURE.txt`.
Neither scrub alone is enough.
Colour codes can split a token that matches only once they are gone, as `grep --color` splits `isx` from `_<digest>` (#1864).
A control byte turned into a space, or a carriage return dropping a key's BEGIN line, hides a shape only the raw text shows.
So both scrubs run, `BuildOutput.shownGuestText` says which raw character each shown one came from, and each run of what either redacts becomes one marker.
No order of two passes does the same: a raw pass first leaves a marker that cuts a split token short, and a shown pass first misses what showing hid.
So the scrub gets the raw text: the error line quotes stderr raw, and `appendDiag` keeps the LXC log and dmesg raw.
`Container.failed`, `StepProgress` and npm's skip reasons scrub their output while it is still raw.
`StepProgress` keeps one form for showing and saving, so its detail and log are redacted on the terminal too.
A detail that ends in the log's last line takes that line from the scrubbed log, since a key cut short leaves its last body line with no BEGIN header of its own.
A line picked out of guest output to quote, such as the last one or Git's first `fatal:`, comes from the whole output scrubbed this way (`BuildProgress.lastNonEmptyLine`, `RepoCloner.firstGitError`), so `update-all`'s warnings and a clone's failure message keep no secret the log redacts.
The log is written as each command ends, the failure report names it, and `isx build --verbose` prints it through `BuildOutput.frame` once the template's build is over, before its failure, so a live batch's lines are never interleaved with it.
`Container.runQuiet`, `runAsUserQuiet` and `NpmGlobalInstall` show a failed command's output through it too, since a tool's `run:` step or an npm package's install script decides that output.
They report it as one warning, its lines as details (`BuildOutput.guestLines`), to the container's `Output`: the caller's when it made the `Container` with one, else the bound output (#1703).
A long output shows its last 40 lines (`BuildOutput.lastOf`), after one saying how many came before.
The exception thrown carries all of it (`Container.failed`, `withStepOutput`), so the build's failure report keeps what the warning leaves out.
`VmAgentFailure` quotes a VM's console lines through it too, since whatever runs in the guest can write its console.
`AnsiEscapeGateTest` fails on any ESC literal (`\033`, `\33`, `\u001b`, `(char) 27` with 27 in any literal form, `Character.toString(27)`, `Character.toChars(27)`, whatever follows it) whose line lacks a `// raw ANSI: <why>` comment, apart from the gates, `TerminalProgress` and `ShellStatusBar`, named by repository path (#1082).
A regular expression that matches escapes carries the marker too (`// raw ANSI: matches escapes, writes none`), and only a `//` comment holds a marker, never a string, a text block or a block comment; a byte `0x1B` (`ShellStatusBar`, `EscapeSequenceParser`) is beyond the scan.
`RawOutputGateTest` fails on raw stream use or replacement (`System.setOut`/`setErr`), console or `printStackTrace()` use and on a print literal that starts with spaces, outside the gate classes (#1703): its `EXEMPT` map, each with why, and the build-time `graal/*Feature` classes.
`Thread.dumpStack()`, a logger API and reflection on `System.class` count as raw stream use.
Only the files `MAY_INHERIT_TERMINAL` names may hand a child process the terminal (`inheritIO()`, `Redirect.INHERIT`), and only those `MAY_OPEN_TERMINAL_DEVICE` names may name `/dev/tty`, `/dev/stdout`, `/dev/stderr`, `/dev/console`, `/dev/pts/`, `/dev/fd/0` to `2` or `/proc/self/fd/0` to `2`, in a string or a text block (`Presence`'s guest script stats `/dev/console`), each listed file only the one device it names; `/proc/thread-self/`, `/proc/<pid>/task/<tid>/` and `//` or `/./` separators count too.
Every source gate decodes Unicode escapes before it scans (`RawOutputGateTest.unicodeDecoded`), so an escaped identifier, ESC or comment end cannot hide a use.
An exemption whose file no longer writes fails too, and a static import of a stream fails in any file.
Progress or a diagnostic that needs an indent goes in a `group`, whose children sit one level deeper; a result is content and lays out its own fields (`ProxyCommand.Status.field`).
Code that hands the terminal over or draws on it whole (an interactive guest command's inherited output, an interactive shell's raw bytes and its status bar, `TerminalProgress`, `isx git-remote-helper`'s and `isx ssh-proxy`'s protocol streams) takes the process's streams and console from the gate `TerminalStreams` (`common/util`), so a stray print in the same file is still caught.
A window title is no such thing: `InteractiveShell` sets and resets it through `BuildOutput.windowTitle`; its reconnect notice is one `warn` per lost connection, each retry after it a `note`, and a session that stayed up past the longest wait (10 s) and dropped counts its retries afresh, while one that drops at once is a retry.
`TerminalStreams.terminal()` also holds the one copy of the JDK 22 to 24 check that stdin and stdout are a terminal, which `Prompts.terminal()` calls, and `BuildInterrupt.atTerminal()` through it.
Only the files `RawOutputGateTest.MAY_USE_TERMINAL_STREAMS` names may call it, a method reference included, and an entry that no longer calls it fails.
Tests assert on `RecordingOutput`'s typed events (common test-jar) instead of swapping streams: bind it (`BuildOutput.bind`, `RecordingOutput.during`) or hand it to code that takes an `Output`.
`StreamSwapGateTest` allows a swap only in the test classes its `MAY_SWAP` map lists, where the streams themselves are what they test (`BuildOutputTest`, `OutputBindingTest`, the pre-TUI hold, the proxy's `ProxyLog` lines).
Escapes follow stdout: `System.console()` never looks at stderr, so `2>log` alone keeps colour (documented, not fixable without native code).
A warning's closing blank line goes to stdout, so stdout's flow keeps its layout when stderr is redirected.
A styled string is never a `static final` (it would be decided when the native image is built): `InitCommand`'s borders are methods for that reason.

**Live steps.**
On an ANSI terminal `stepStart` animates a spinner (plus the `stepProgress` detail) until `stepDone`, which rewrites it as `✓ label` (elapsed time appended past 2s); `stepBreak` gives `✗ label`.
Without a terminal the same calls print `label... done.` (`done in 41s.` past 2s).
A step must cover *all* of its work -- e.g. `ToolVerifier` gives each `verify` its own step and reports it only after the command returns, since a slow verify after "done" looks like a hang.
While a step is live, `System.out`/`System.err` are wrapped so any other write first freezes the line (`… label`) and stops the spinner; `BaseCommand` calls `abandonStep()` after every command so an exception cannot leave the wrap installed.
Anything that hands the terminal to a child directly (inherited IO, a `sudo` that may prompt on the tty) must call `BuildOutput.releaseTerminal()` first -- the wrap cannot see that output.
Host commands run inside a step should capture output rather than inherit it.
`TerminalProgress`-based blocks (repo clones, host repo refresh: N live lines at once) render at `BuildOutput.indent()` and finish with the same `✓`/`✗` glyphs (`BuildOutput.CHECK`/`CROSS`).
Off an ANSI terminal `RepoCloner`'s parallel block reports each repository as it ends, from its state (`TerminalProgress.run` with `plainDone`): a step when ready, a warning when it failed.
On any terminal the start of a failed repository's command and the end of its log are one warning (#1703).
Headers live in command classes; shared helpers like `VmManager` emit only steps so they nest correctly under whichever header the caller prints.
A build that fails says why as an error, its diagnostics as notes as they are found (a later probe may hang), and where the failure report was saved, with the container promoted for inspection, as warnings, so the TUI's log keeps where to look (`BuildCommand.reportBuildFailure`, #1703).
See DESIGN.md "Terminal Output Visual Language" for the full spec.
