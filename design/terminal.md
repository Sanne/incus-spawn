# Terminal output and the TUI

How isx writes to the terminal, for people and for scripts, and how the TUI keeps the screen.

## Terminal Output Visual Language

All multi-step command output follows a consistent visual language, centralized in `BuildOutput` (`common/.../util/BuildOutput.java`).
All output helpers live there; individual commands should not define their own ANSI constants or formatting patterns.
Beyond build/branch, this governs the other lifecycle commands too — `vm` (start/stop/resize), `destroy`, `update-all`, `update-base`, and `project` — plus the shared `VmManager`.
(`isx init`'s large interactive first-run flow is the one deliberate exception, kept in its own style for now: its boxed, numbered headers stay, but the lines under them report outcomes rather than attempts, its `[n/N]` counter is counted from the list of steps rather than written down, and host commands show their output only when they fail (#906; the rules are in `.claude/rules/commands.md`, "Init output").)

**Every line under a header is one of five kinds, picked without judgement** (#1791):

1. Work that was done is a **live step** (`stepStart`/`stepDone`, or `ok(label)` when it was already complete when reported).
2. Work that was skipped is a **note** (dim): `Base image 'fedora-44-base' is up to date (fedora-44-20261021)`.
3. The command's own answer is a plain **step**.
4. Detail about the step above is a **step detail** (`stepNote`, `stepWarn`).
5. The block's closing line is the **success** line, drawn at the header's column.

**Labels are bare, and the terminal decorates them.**
A label in source is a bare phrase with no `...` and no period: `stepStart("Masking unnecessary services")`.
`TerminalOutput` adds the ellipsis on a plain terminal (`Masking unnecessary services... done.`) and the spinner and the `✓` on an ANSI one.
A decorated label (`"Launching x..."`) is right for neither: the ✓ line would have to undecorate it again, and the label reads as a pending action when it is not.
`StepLabelGateTest` holds it: the last string literal of a `step`, `stepStart`, `liveStep` (in its first argument, before the work), `ok`, `success`, `note` or `stepFail` call may not end in `...` or `…`, nor in a period for `stepStart`, `liveStep`, `ok` and `success`, whose text is a label while a step, a note or a failure can be a sentence of advice.

**Structure:**

- **Section** (column 0): `Refreshing 8 host repos:` or `Updating 6 template(s).` via `section(msg)`.
  Introduces a block of work at the left margin, preceded by a blank line.
  Use for top-level groupings; individual operations within a section get `header()`.
- **Header** (bold bullet): `  ● Building tpl-dev  [1/3]`, `  ● my-branch  ← tpl-dev`, or the generic `  ● Resizing VM data disk` via `header(msg)`.
  Identifies the top-level operation.
  Preceded by a blank line.
- **Group** (`▸` title, children one level deeper): `    ▸ Tools  maven-3, mx` via `try (var g = group(title, detail))`.
  Frames a batch of steps that belong together (packages, tools, skills, repositories); closing it leaves a blank line so the next top-level step does not read as part of it.
  `list(items)` prints a wrapping comma-separated list at the current indent (the packages being installed).
  `header()` resets the nesting, so a group an exception left open cannot skew the next operation.
- **Step** (4-space indent, deeper inside a group): `    All templates are up to date` — a complete line, for the command's own answer.
  `ok(msg)` prints work that was already complete when reported as `✓ msg`, without a live step.
- **Live step** (`stepStart` / `stepDone`): on an ANSI terminal the line animates a braille spinner, with an optional dim detail set by `stepProgress()` (`verifying: mx version`), and turns into `✓ label` when done, with the elapsed time once it passes two seconds (`✓ Installing packages and dependencies  41s`).
  `stepDone(detail)` adds a result — `✓ Extracting root disk (4.0G)`.
  `stepProgress` takes a `Supplier<String>` that is read again on every frame, so a value that keeps changing, such as a download's rate and time left, needs no call per change.
  `stepBreak()` ends it as `✗ label` before an error; `stepFail(msg)` is `stepBreak()` and then `msg` as a `stepWarn` under the cross.
  `BuildOutput.liveStep(label, work)` wraps work that starts no step of its own: a ✓ when it returns true, a ✗ when it returns false or throws, with what the work printed meanwhile below the frozen line.
  Without a terminal the same calls print `Starting container... done.` for logs, `done in 41s.` once it passes two seconds.
  The step is not done until *all* its work is: each tool's `verify` is its own step under `▸ Verifying`, because a line saying "done" while a first `mx version` takes seconds reads as a hang.
  Never print a `Doing X...` line whose result lands on a separate line.
- **Step detail** (`stepNote`, `stepWarn`): dim or yellow lines one level under the step they describe — a tool's verified version, or why its verify failed.
  A warning (`stepWarn`, `warn`) is followed by a blank line, separating it from the regular flow; a group closing right after it does not add a second one.
- **Note** (dim, 4-space indent, stderr): work that was skipped, and other messages that should be visible but not alarming — e.g. `    Parent 'tpl-dev' already up-to-date, skipping`, in ANSI dim (`\e[2m`).
- **Warning banner** (yellow borders, stderr): `warnBanner(title, lines...)` for diagnostic warnings that need to stand out — subnet conflicts, CA mismatches, firewall issues.
  Title is bold yellow, body lines are plain.
  Distinct from `warn()` which is a single inline yellow line within step output.
- **Success** (green checkmark, bold, at the header's column): `  ✓ tpl-dev built` — the line that closes the block, preceded by a blank line.
  The header opens a block at column 2 with `●`, so its close belongs at column 2 with `✓`, worded like every ✓ line: no period, no "successfully".

`isx init`'s host changes follow the same kinds: each is a live step (`BuildOutput.liveStep`), whose ✗ follows the output and warning of a command that failed, and `InitCommand.runHost` and a `sudo` through `runHostQuiet` release the terminal first, as `ProxyService.runQuiet` does.

**Headers live in commands, steps in shared helpers.**
A shared operation that runs both standalone and nested (e.g. `VmManager.start()`/`stop()`, called both by `isx vm start` and as sub-steps of `isx vm resize`) emits only steps/notes and never its own header.
The command class prints the one `● Header`; the shared method's steps then indent correctly under whichever header the caller printed, so no duplicate or nested headers appear.
Where a shared step would otherwise repeat the header verbatim, reword it (the stop step reads `Shutting down VM...` under a `● Stopping VM` header).

**Output during a live step.**
While a step animates, `System.out` and `System.err` are wrapped: the first write from anyone else freezes the step line (`… label`) and moves below it, and the spinner stops drawing, so a warning printed mid-step is never drawn over.
Its `stepDone` then prints `✓ label` on a fresh line.
The wrap is restored when the step ends; `BaseCommand` calls `abandonStep()` after every command so a step an exception cut short shows `✗` and stops guarding the streams (the TUI resumes in the same process after a build).
A child process that writes to the terminal directly — inherited IO, or a `sudo` that may prompt for a password on the tty — bypasses the Java streams, so its caller calls `releaseTerminal()` first (`ProxyService.runQuiet("sudo", …)`).
Host commands run under a step capture their output instead of inheriting it (`YamlToolSetup.runProcess`).

**Warnings and errors** go to stderr.
Notes (dim) are for expected conditions the user may want to know about (skipped steps, cache hits).
Warnings (`warn`, on stderr) are for conditions that may need action.
The distinction: a note is "this is fine, FYI"; a warning is "you should look at this."

**Container commands during build:** tool `run:` and `run_as_user:` scripts use `Container.runQuiet()`/`runAsUserQuiet()`, which capture output and only show it on failure.
This prevents leaked output (e.g. systemd's "Created symlink" lines) from breaking alignment.
`runInteractive` is reserved for commands that genuinely need live terminal output (e.g. interactive shells).

**Adding new output:** use `BuildOutput.section()` to introduce a block of work, `header()` to frame a named multi-step operation within it, `group()` for a batch of related steps, `step()`/`ok()` for quick actions, `stepStart()`/`stepDone()`/ `stepDone(detail)` for slow ones (with `stepProgress()` when a phase can take a while), `stepNote()`/`stepWarn()` for detail about the step above, `note()` for informational dim messages, `warnBanner()` for bordered stderr warnings, and `success()` for the final confirmation.
Never print to the raw streams or indent a line with leading spaces (a `group` indents its children), and write labels bare: a `Doing X` that did work is a live step, never a `Doing X...` step left dangling.
Pure reports/tables and interactive prompts (e.g. `proxy` status, `clean` summaries, the TUI) are not step sequences and stay as plain output.

## Output for scripts: `--format=table|plain|json`

A script driving isx needs output it can parse, and the human output is the wrong thing to parse: padded columns, a header, ages like `3h ago` that contain a space, prose when the list is empty.
So query commands take `--format` (#1036), through one shared helper, `OutputFormat` (`common/.../util/OutputFormat.java`), rather than each inventing its own.

- **`table`** is the human output and the default.
  It may change in any release.
- **`plain`** is one record per line, tab-separated, `-` for an empty field, with no header, padding, colour or glyph, and no output at all for no results.
- **`json`** is an array of objects (one object for a single-item command).
  An absent value is `null`, times are ISO-8601 with their offset (`+02:00`, or `Z` on a UTC host; a legacy date-only stamp is an ISO-8601 date, an unreadable one `null`), sizes are bytes.
  A response isx cannot read is an error (exit 1), never an empty result, which a script would take for "nothing there".
- **Exit codes**: 0 success (an empty listing included), 1 when the command fails or rejects a value it checks itself (`--format=yaml`), 2 when aesh cannot parse the command line (unknown option, missing value, stray argument; the usage goes to stderr).
  2 is aesh's, for every command, so the contract documents it rather than remapping it; `ExitCodeTest` pins both.
- **The contract**: `plain` and `json` fields may be added at the end, never renamed, removed or reordered.
  Results go to stdout and only results: errors and diagnostics go to stderr.
- **Control characters** (#1118): both formats are read on terminals too, and a value can be a stamp someone set by hand, so neither writes a control character in a value raw.
  `plain` is lossy: every C0 control (tab and line breaks included), DEL, C1 control and U+2028/U+2029 becomes a space (`OutputFormat.oneLine`), which keeps a record one safe line, and so does every bidi embedding, override and isolate (U+202A-U+202E, U+2066-U+2069), which would make a terminal draw the rest of the line, the following fields included, out of order.
  The marks (U+200E, U+200F, U+061C) stay: they reorder nothing beyond their neighbours.
  `json` is exact: jjq escapes C0 as JSON requires (tab, line feed, carriage return, backspace and form feed as `\t` `\n` `\r` `\b` `\f`, the rest as `\uXXXX`).
  `OutputFormat` also escapes DEL, C1, U+2028/U+2029 and the bidi controls as `\uXXXX`, which JSON allows and every parser reverses.
  Both formats test one predicate, `OutputFormat.isControl`, so they cannot disagree on the set.
  A script that needs a value exactly reads `json`.
  Escaping in `plain` instead (`\e`, `\\`) was rejected: it would make every reader decode, and change the many values with a backslash for the rare one with a control character.
  The `isx list` table and the TUI show an instance stamp (parent, created, the MCP fields) through the same `oneLine`, so they cannot disagree; `\p{Cntrl}`, which the MCP column used before, is ASCII-only and let U+009B (8-bit CSI) through.
  The `isx templates` and `isx tools` tables (`list -v`, `tools show`) and `isx templates edit`'s validation findings do the same for definition text (#1133).
  A project-local definition ships with whatever repository was cloned, so its name, source and description are as untrusted as a stamp.
  A validation error keeps the line breaks of the advice isx writes into it.
  So the definition text it quotes goes through `oneLine` where the message is built (`HostResourceSetup`'s refusals, `YamlErrors.friendly`).
  `HostResourceSetup`'s build and start notes and warnings (mounted, copied, source not found, the macOS overlay refusal) quote a source, mode and container path the same way (#1235): a confined source is the project's real path with the definition's text appended.
  A build step's label quotes definition text too (#1610): `RepoCloner.repoDisplayName`, `BuildProgress`'s step runners (`runWithSpinner`, `runDnf`, `runLiveStep`) and its shared renderer (`formatStepLine`, with the dim context) pass it through `oneLine`.
  So step lines and output headers show a repository's URL or a COPR name as text.
  `BuildOutput.note` prints a note through `oneLine` as a whole (a note is one line; advice over several lines is several notes), and `warn`, `stepWarn`, `stepStart`, `stepNote` and `stepFail` make each line safe while keeping isx's own line breaks, so text they quote that no call site quoted (a repository's path, URL or prime command) cannot drive the terminal either.
  Once a message is assembled, a forged line break cannot be told from isx's own.
  Each line is made safe again when it is printed.
  `isx init`'s credential step prints a tool's description and each configuration entry's description and help through `oneLine` too (`CredentialSetup`, #1222): `ToolDefLoader` reads the project's `.incus-spawn/tools/`, so running init in a cloned repository shows its text.
  `isx build`'s base-image refusals do the same for the template's name and source, the image alias and the importing project (`downloadAndAliasImage`, `requireImageTrustedFor`, #1228).
  The build header printed before a refusal (`BuildOutput.buildHeader`), the too-long and stranded-storage refusals and the closing list of failed templates show the name through `oneLine` too.
  So does a refusal raised once the build has started: its "Build failed for" line (or the shutdown hook's "Build interrupted for"), the failure-report and promote lines after it, and the VM-image and broken-image refusals.
  That line also carries messages isx did not build, such as Incus quoting the instance name, so it is printed one `oneLine` at a time, keeping its line breaks.
  The account refusal quotes the template's `accounts:` entries, and the unparsable-file and name-conflict refusals quote the files a cloned repository named; all of these go through `oneLine` as well.

A command parses its `--format` with `OutputFormat.parse` (`isx list`, which also has `--plain`, with `OutputFormat.resolve`), builds each record once, as an ordered map of field name to value, and hands the list to `format.print` (or one record to `format.printOne`, an object in `json`).
Both machine formats print the same map, so the field order of `plain` is the field order of `json` and the two cannot drift apart.
Maps rather than records are what jjq serializes without a mapping or reflection registration in the native image.
A list value (`tools show`'s `packages`, `account list`'s `pinned_by`) is a JSON array and, in `plain`, its elements joined by `,`, so a record stays one line.

Every query command takes it (#1038): `templates`, `tools list`/`show`, `account list`/`show`, `proxy status`, `doctor`, `vm status`, `update-base --list`, and `branch`, whose record is the new name alone so that `$(isx branch ... --format=plain)` captures it.
`branch` is the one exception to one map for both formats (#1720): its `plain` record stays the name alone, which that capture relies on, and only `json` gains fields (who owns the instance and how a program reaches it over SSH).
Appending them to `plain` would have been the usual rule and would have broken every script that captures the name.
The bare group commands (`isx templates`, `isx tools`, `isx account`) run their `list` and take its `--format`.
A command whose work prints progress on the way (`doctor`'s checks, `branch`'s flow) runs it under `BaseCommand.withStdoutOnStderr`, so stdout holds only the result whatever the code underneath prints.
The machine formats never prompt: `doctor` offers no remediation and `branch` opens no shell.
Exit codes are those of the table output, so a script reads the same state from either.
`proxy status` prints its record in every state and exits 0/1/2/3 (when it cannot check at all, on Linux with Incus unreachable, the record says `status: "unknown"` with a `check_error`: it exits 1 like `not_running`, as the table always has, but no longer reads like it), `doctor` exits 1 when a check fails, and `vm status` exits 1 when Incus is unreachable (#1037 made the table do so).
Each command with more than one failing state keeps its codes in one `exitCode` method that every format returns, so they cannot drift.
`account list` prints `null` for `pinned_by`/`following` when Incus could not be asked, never an empty list, which would read as "nobody".
`isx templates` adds each definition's build state and staleness at the end of its record (#1115): `built`, `built_at`, and the TUI's three marks as `version_outdated`, `definition_changed`, `parent_rebuilt`.
Both read one judgement, `TemplateStaleness`, which takes only what it is handed.
So the CLI pays for one `GET /1.0/instances?recursion=1` (the table output still asks Incus nothing) and fingerprints tools only when a built template's definition is compared, never the TUI's reload.
As for `account list`, a listing that cannot be read makes those fields `null` rather than "not built".
Nothing the table tells a person about state is left out of the record.
Problems a template's choice causes (`account show`'s `template_problem`), whether a proxy restart can clear drift (`restart_helps`), a leaking vsock forwarder (`vsock_connections_high`) and a pinned base image (`pinned`) are fields.
`update-base --list` gives the current base image a record of its own (`listed: false`, last, no date) when it is not among the releases fetched -- older than all of them, unpublished or deleted, so not a statement about its age -- and a script always finds it.
Only descriptive detail (a tool's parameter types, the system diagnostics under `vm status`) stays table-only.
`doctor`'s `detail` and `remediation` are text for people, not values a script should parse; the table's wrapping parentheses are dropped from `detail`.
`QueryCommandFormatTest` pins the JSON of each, and `ExitCodeTest` that each rejects `--format=yaml` alike.

**`isx list` from the CLI is cheap.**
Outside the TUI it reads the instance listing once (`GET /1.0/instances?recursion=2`) and, against Incus, nothing else: no definition reload, tool loader, pool or btrfs probes, which the TUI's `reloadData()` runs and a script polling `isx list` should not pay for.
`-q` reads at `recursion=1`: names and states need no live state, and shell completion runs it on every TAB.
On macOS a VM that is set up but stopped is started first (`VmManager.ensureRunning`, its progress on stderr), so the list is not an error; one never set up is left to `isx init`, and `-q` never starts it, because a TAB must not boot a VM (#1393).
An empty table after `isx vm reset` says the data disk was reset and when (`VmManager.dataDiskResetOn`, a date `resetDataDisk` writes next to the disk and the first non-empty listing forgets), not the first-run hint.
`ListCommandOutputTest` pins both requests.
The `created` time comes from `Metadata.createdIso`, next to `Metadata.now()` which writes the stamp, so every command that prints one prints the same instant.
It tells templates apart by the `base` type every build stamps on them, so it needs no definitions, and never by the `tpl-` name prefix: a branch may be called `tpl-anything`.
A copy carries its template's `base` type.
So `BranchFlow` stamps `clone` in the copy request itself, as it does the instance secret.
A branch interrupted before `configureBranch` (Ctrl-C, a failed write) is still listed, never mistaken for a template and leaked (`InterruptedBranchTest`).
Incus instances isx did not create (no isx metadata) are not listed.
`-q`/`--quiet` prints names only, and `--status=running|stopped` filters.

**`isx instances` is removed** (#1802).
It listed every Incus instance not named `tpl-*`, which disagreed with `isx list` both ways.
It was deprecated, hidden, as an alias for `isx list -q` (#1036), kept for completion scripts installed by older releases, which call it by name.
Those scripts now offer no instance names until regenerated (docs/dev/MIGRATIONS.md); the scripts isx generates call `isx list -q`.

**Commands that act need clean stdout, not a format** (#1037).
`BuildOutput` writes colour only when `BuildOutput.ansi()` says so, which is `TerminalProgress.isAnsiTerminal()`: false without a console, for `TERM=dumb`, and when `NO_COLOR` is set to anything non-empty (no-color.org), so the live spinner also falls back to `label... done.` lines.
`TerminalLink.link()` asks the same gate and prints the bare URL off a terminal, since an OSC 8 hyperlink is an escape too.
An AI answer never reaches the terminal raw (#1413): `TerminalLink.plainText` turns what `OutputFormat.isControl` names into spaces, line feeds and tabs apart.
`TerminalLink.parseSegments` and `linkify` pass the text around a link through it, and `isx ask` prints a chunk through it off a terminal.
An AI request's error goes through `OutputFormat.oneLine`: `AiHelpClient` passes a provider's text through it, and AI Help every error it shows.
Output captured from a guest command is someone else's text too (#1588).
`BuildOutput.guestText` is how such output is shown: complete control strings (OSC, DCS, APC and the like), control sequences and other escapes dropped, each line's last text a carriage return left, then `TerminalLink.plainText`.
Its one pattern ends a control string at the next escape or introducer, as a terminal does, so a guest's long line costs linear time, never a stall.
A control string runs to its terminator across lines and ignores the controls in it, as a terminal reads it (#1920).
A C0 control inside a control sequence or escape is acted on as outside one, and the sequence carries on.
CAN and SUB abort a control string, as they do a sequence, so a terminator after one ends nothing.
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
**The scrub is hygiene, not a security boundary.**
It exists for the accidental leak: a template's `user:pass@host` clone URL, or a step that prints its environment, landing in a log that someone later pastes into an issue.
A guest that sets out to defeat it is outside its threat model, for three reasons.
A guest holds no real credential, only per-instance proofs that are worthless away from the proxy, and a build's proofs are revoked when the build ends.
The log is the host user's own owner-only file, and that user already holds every real credential.
A guest that wanted to leak what it holds has easier ways out than escape sequences in a log.
The screen model below already goes further than accidental output needs, and it stays as defence in depth.
A further gap is worth closing only when a real tool's ordinary output opens it, never when only crafted input does (#1919, #1920 and #1927 are the crafted kind).
Every saved copy of what a guest printed is scrubbed as printed and as shown (`SecretRedactor.scrubGuest`, #1878): the build log, the failure report and the instance's `~/inbox/BUILD_FAILURE.txt`.
Neither scrub alone is enough.
Colour codes can split a token that matches only once they are gone, as `grep --color` splits `isx` from `_<digest>` (#1864).
A control byte turned into a space, or a carriage return dropping a key's BEGIN line, hides a shape only the raw text shows.
So both scrubs run, `BuildOutput.shownGuestText` says which raw character each shown one came from, and each run of what either redacts becomes one marker.
A lone control (a NUL, SO/SI or a backspace) splits a token in both: it ends the shape raw, and the space it becomes ends it shown (#1888).
A character that draws no cell (a combining mark, a zero-width space or joiner) splits a token in both as well, though a terminal draws one unbroken word (#1900).
So two more views are scrubbed: the shown text with its lone controls and the characters that may draw no cell (a soft hyphen too) dropped, and the raw text as a terminal's screen shows it (`SecretRedactor.Screen`).
On that screen a character overwrites the cells under it and keeps the cells to its right, which is how `man` prints bold and underline with backspaces.
A backspace, a carriage return, a tab, a line feed and the cursor sequences that move along a line or up and down it (CUF, HPR, CUB, CHA, CHT, CBT, CUU, CUD, VPR, CNL, CPL, CUP, VPA, a saved position (xterm's private 1048 and 1049 too), the escapes DECBI and DECFI, and the 8-bit IND, NEL and RI) move the cursor, and REP draws the last character again; colour and other sequences move nothing.
A tab, CHT and CBT stop where the guest set its tab stops (HTS, its 8-bit form too, and TBC), until DECST8C or RIS puts back one every eight columns.
Terminals differ here: VTE also clears every stop on TBC 2 and 5, and reads CTC, which xterm ignores.
So when a guest sends one of those, a screen with VTE's stops is scrubbed as well as xterm's.
DCH and ICH shift the cells right of the cursor along its row, and what ICH pushes past the last column is gone (#1919).
DL and IL shift the rows from the cursor's down and put the cursor at the line's start, as xterm does, and SU and SD shift them from the piece's first row (#1927).
A row DL or SU removes stays in the view, since a token drawn whole on it was shown.
A terminal shifts no row outside scrolling margins (DECSTBM), and the piece's first row only stands for the top SU and SD scroll from.
So after DECSTBM, SU or SD, a screen that leaves the rows in place is scrubbed as well.
Without margins it reads the top as above every row, so there SU and SD move the cursor along fixed rows.
Cell widths follow `wcwidth` (`SecretRedactor.cellWidth`): a wide character takes two cells, and a zero-width one none, joining the cell before the cursor and the one last written.
A span there redacts every raw character written to its cells, so no overwritten half of a token is kept beside the marker.
That holds when the cell was drawn over from another row too, so a row repainted from further down also redacts what lies between: it fails closed, losing log lines around a secret rather than half of one.
Erasing is not modelled, since a cell the screen shows blank never holds a token's character.
Rows count from the start of each piece.
The cursor stops at a piece's first row, as at the top of a terminal the piece starts at.
On a terminal where the piece starts further down it goes higher, and from there SU or DL can bring a row onto the cursor's (#1957).
So once a guest sends the cursor above that row, a screen that lets it go a terminal's height higher, into blank rows of its own, is scrubbed as well.
There SU and SD shift the rows from the highest of those.
An absolute row (CUP, VPA) counts from there too, as if the piece started at the top of a screen it cleared.
Output that has scrolled addresses rows from a top long gone, so a second screen counts them from where the first absolute row is, and both are scrubbed.
The alternate screen (xterm's 47, 1047 and 1049) is a region of rows of its own, with its own saved cursor, so what a program draws there neither overwrites the main screen nor is lost.
Only written cells are kept, so the screen grows with what a guest printed, never with how far it moved the cursor, and a guest cannot make it allocate without bound.
Each row is a treap of its cells and of runs of blank ones, so a shift splits and joins the row instead of moving every cell past the cursor.
Each screen's rows are a treap of the same kind, rows and runs of rows nothing was written to, so a row shift costs as little.
What a shift drops is reused for the next cells, so the screen holds what its rows show, never every shift's leftovers.
Tab stops are searched 64 columns at a time, so neither a guest's stops nor a long CHT costs a line's worth of work per sequence.
REP draws at most one cell for each raw character, and the screen is built only when a piece can move the cursor back or repeat (`Screen.mayMoveBack`), so most coloured output pays nothing for it.
No order of two passes does the same: a raw pass first leaves a marker that cuts a split token short, and a shown pass first misses what showing hid.
So the scrub gets the raw text: the error line quotes stderr raw, and `appendDiag` keeps the LXC log and dmesg raw.
`Container.failed`, `StepProgress` and npm's skip reasons scrub their output while it is still raw.
A definition's own command is scrubbed the same way from the text the template wrote, in the build log's header and as `StepProgress.command` (`RepoCloner.commandText`, #1886).
Its carriage returns, form feeds and vertical tabs go to that scrub as another control, which shows as a space but, unlike one, does not end a credential's shape, since a shell keeps each inside the word it splits.
`StepProgress` keeps one form for showing and saving, so its detail and log are redacted on the terminal too.
A detail that ends in the log's last line takes that line from the scrubbed log, since a key cut short leaves its last body line with no BEGIN header of its own.
A line picked out of guest output to quote, such as the last one or Git's first `fatal:`, comes from the whole output scrubbed this way (`BuildProgress.lastNonEmptyLine`, `RepoCloner.firstGitError`), so `update-all`'s warnings and a clone's failure message keep no secret the log redacts.
The log is written as each command ends, the failure report names it, and `isx build --verbose` prints it through `BuildOutput.frame` once the template's build is over, before its failure, so a live batch's lines are never interleaved with it.
`Container.runQuiet`, `runAsUserQuiet` and `NpmGlobalInstall` show a failed command's output through it too, since a tool's `run:` step or an npm package's install script decides that output.
They report it as one warning, its lines as details (`BuildOutput.guestLines`), to the container's `Output`: the caller's when it made the `Container` with one, else the bound output (#1703).
A long output shows its last 40 lines (`BuildOutput.lastOf`), after one saying how many came before.
The exception thrown carries all of it (`Container.failed`, `withStepOutput`), so the build's failure report keeps what the warning leaves out.
`VmAgentFailure` quotes a VM's console lines through it too, since whatever runs in the guest can write its console.
The console is decided by stdin and stdout, never stderr, and Java cannot ask whether stderr alone is a terminal without native code.
So the contract is that escapes follow stdout: redirecting stdout or setting `NO_COLOR` clears both streams, while `2>log` alone from a terminal keeps colour in the log.
`docs/CLI.md` ("Scripting isx") says so rather than promising more.
`warn`, `stepWarn` and `note` go to stderr: a step line is what the command did, a warning or note is a diagnostic about it.
The blank line that closes a warning goes to stdout, because it separates the warning from stdout's own flow.
A following header then neither repeats it on a terminal nor loses it in `>out.log`.
So a line that is the command's result, such as `clean --dry-run`'s "Would delete ...", is a step, not a note.
A status command's report is its result whatever it says.
`isx proxy status` prints it on stdout in every state and tells them apart by exit code (1 not running, 2 stale DNS overrides, 3 stale bridge address), and `isx vm status` exits 1, the reason on stderr, when Incus is unreachable.
Every other styled line (`isx init`, the proxy banners, build failure reports) goes through the same `BuildOutput.styled()` (#1082); only what is drawn on a terminal alone (live steps, `TerminalProgress` formatters) writes escapes directly.
Every window title, the shell's `isx:<name>` and its reset as well as the TUI shell's auth-error one, goes through `Output.windowTitle`, which `TerminalOutput` writes only on a terminal (not a colour, so `NO_COLOR` keeps it), as one line.
`AnsiEscapeGateTest` fails on any ESC literal unless its line carries a `// raw ANSI: <why>` comment (on that line, so a marker never covers another).
It matches every literal spelling (`\033`, `\33`, `\u001b`, `(char) 27` with 27 in any literal form, `Character.toString(27)`, `Character.toChars(27)`) whatever follows, so splitting `"\033" + "["` does not hide one.
A regular expression that matches escapes carries the marker too (`// raw ANSI: matches escapes, writes none`), so no line is exempt for what else it holds, and only a `//` comment holds a marker, never a string, a text block or a block comment (the file is blanked whole to tell them apart).
A bare number (`(byte) 0x1B`, `new byte[]{0x1B}`), as the shell status bar writes ESC and `EscapeSequenceParser` reads it, is one the gate cannot tell from any other.
Only the three gates (`BuildOutput`, `TerminalOutput`, `TerminalLink`), `TerminalProgress` and the shell status bar are exempt as whole files, by repository path, so a new escape on an ordinary path in `BuildCommand` or in a namesake of a gate still fails.
`RawOutputGateTest` does the same for the raw streams (#1703): `System.out`, `System.err`, `System.setOut`/`setErr`, `System.console()`, `FileDescriptor.out`/`err` and `printStackTrace()`, and a print literal indented by hand.
It also refuses what writes to the same streams by another road: `Thread.dumpStack()`, a logger (Quarkus, JBoss, `java.util.logging`, `System.Logger`, SLF4J, Log4j, Commons Logging, Logback) and reflection on `System.class`.
A child process that inherits the terminal (`inheritIO()`, `Redirect.INHERIT`) and a string or text block that names it (`/dev/tty`, `/dev/stdout`, `/proc/self/fd/1` or `/proc/thread-self/fd/1`, with `//` or `/./` separators, a shell's `> /dev/tty` included) are allowed only in the files it names, each with why, and a file may name only its own device.
It decodes Unicode escapes first, as the compiler does, so `System.\u006fut` or a `\u000a` that ends a comment cannot spell a stream past it.
What no source gate can see is an addition to its own lists, a stream or ESC reached through a computed value, or a path spelled with octal escapes (`"/dev/\164ty"`); review catches those.
The rule against printing from code the TUI can reach was prose for five rounds of fixes, and nothing failed when the next site was added.
It began as a ratchet over the 1,300 uses that predated it, a baseline counted per file that each migration lowered; the migration emptied it, so a raw use outside a gate class now fails wherever it is.
A static import of a stream is refused outright, since it would hide every bare `out.println` after it, and an exemption whose file no longer writes fails as well.
Code that must reach the terminal itself, to hand it to an interactive guest command, draw on it whole or speak another program's protocol on it (`isx git-remote-helper`, `isx ssh-proxy`), takes the streams from the gate `TerminalStreams` rather than being exempted file by file, so a stray print beside it is still caught; only the files the gate test names may call it, a method reference included, and a named file that no longer calls it fails.
Its `terminal()` holds the one copy of the JDK 22 to 24 check that stdin and stdout are a terminal, which `Prompts.terminal()` reuses.
It is a source scan rather than forbiddenapis, which reads Java 25 class files as of 3.9 but cannot see an indentation literal.
A styled string is never a `static final`: it would be decided when the native image is built.
`BuildOutputTest` and `StatusCommandOutputTest` pin these.

**Not yet covered**: the other query commands (`templates`, `tools`, `account`, `proxy status`, `doctor`, `vm status`, `update-base --list`) have no `--format` yet.
Each adopts the same helper and contract when it gains one.

## Shell completion asks the running binary

`isx completion <shell>` prints a script that answers every TAB by running `isx --aesh-complete -- <words>`, for bash, zsh and fish (#1842) as for PowerShell (#1744).
That request walks the command tree this binary registered, so a platform's commands are offered only on that platform: on Linux there is no `vm` to strip, because the Linux top command has none.
The scripts list no command, option or value of their own, so they cannot drift from the commands.
The hand-written ones they replace did: zsh listed `init`, `update-all` and `mcp` but had no case for them, so their options completed nothing, and three regex edits (`stripVmCommand`) kept `vm` out of the Linux scripts.
The values come from aesh completers on the commands' own arguments and options (`Completers`): instances (`isx list -q`'s source, one Incus request at `recursion=1`), `--from` sources with the built templates, template and tool definitions, `<namespace>=<account>` selections, credentials and fixed values such as `--format`.
They follow the rules the scripts' old sources did: names only, never a credential, nothing that starts a VM, and nothing at all when the source fails, since an error would be drawn over the line being typed.
`Completers.answer` replaces aesh's own `--aesh-complete` handler, which in aesh 3.18 gave completers no working directory (a `Path` option threw) and asked the shell for files after every command without a positional argument.
It answers each candidate as the whole word it replaces (`--format=json`, not `json`), with a description after a TAB: a command's or option's, or the one its completer gave the value, such as `default` beside the default account.
aesh's completer values carry no description, so a completer hands it to `answer` through the request's context (`Names.describe`).
Words and candidates are plain values, so the request stays the same for every shell and each script quotes for its own.

The scripts are isx's, not aesh's generated ones.
aesh's bash script runs `compgen -W "$values"` over what isx prints and inserts candidates unquoted, which reopens the hole #1778 closed: a `$(...)` in a name ran on TAB or on Enter.
Its PowerShell script sent a quoted word with its quotes and inserted a path with a space as two arguments.
Each of isx's sends every word's value, its quoting removed by something that expands nothing, and inserts each candidate quoted by its shell's own rules:
- bash reads the words from `COMP_LINE` with its own loop (`_isx_words`), since bash-completion's dequoting calls `eval` and its words split at every word break, `@` included; it never uses `compgen` or `eval`.
  Readline replaces only what follows the word's last unquoted `COMP_WORDBREAKS` character or its open quote, so that much of the typed word is dropped from each candidate before quoting.
  A closing quote is no break, so a second TAB into a directory completed inside a quote replaces the whole word.
  Readline closes a lone candidate's quote only when the line does not already end in that character, so a lone candidate whose quoting ends in it gets the quote closed by the script.
  Nor does readline double an opening quote: inserted text starting with that quote's character replaces it, so a candidate whose quoting starts with it gets one more in front, or Enter ran what followed a leading `!`.
  A redirection's target is left to readline's file names, as bash-completion's `_init_completion` did.
  It quotes each candidate for the quote the word left open, and uses `printf %q` only outside one.
  It keeps to bash 3.2, macOS's stock shell: there history expansion also reads a `!` inside `$'...'`, so a `!` is written outside every quote but `'`.
  Inside `"`, the `$'...'` form `%q` writes for a character the locale cannot print leaves a `$(...)` live, and Enter ran it (found in review: a directory named `x` U+2028 `$(...)`, completed after `--inbox "`).
- zsh removes quoting with `(Q)` and inserts through `_describe`, whose `compadd` quotes; `-U` takes each candidate as isx matched it.
- fish reads the words with `commandline -opc` and `string unescape`, and gets back its own format, a candidate then a TAB and a description, which it quotes and never evaluates.
- PowerShell single-quotes a candidate it would otherwise split, expand or glob, and as it reads a bare `a,b` as a list and replaces only its last item, inserts only that item.

A directory or a bare `<namespace>=` gets no trailing space, so the next TAB continues it.
Paths come from `Completers.HostPath` rather than aesh's file completer, whose escaping of a lone candidate (`my\ dir/`) only aesh's readline reads back, and which cannot be undone exactly.
A typed `~/` is answered with the home directory, which reads the same quoted or not.
Where a shell matches against the word as typed, the answer gets its `~/` back: in fish always, and in bash where the `~/` lies before a word break readline keeps (`~/a=b`).
A candidate holding a control character is dropped, since a newline or TAB would split its line.
It never offers `git-remote-helper`, `ssh-proxy` or `completion` (`IncusSpawn.NOT_COMPLETED`), which stay runnable.
`isx account list --completion` and `isx account add --completion` stay for scripts saved by earlier releases, which call them (docs/dev/MIGRATIONS.md); no script isx prints now does.
A TAB costs one process start, about 3.5 ms on the native binary, plus one Incus request where instance names are offered.
On a JVM install that start is a JVM's, about 200 ms, which the hand-written scripts paid only for values.

## Keeping a long-lived TUI current

The TUI is routinely left open for days, while instances are created, stopped and deleted from other terminals, scripts, or `incus` itself.
Three things used to go stale: ages ("today 21:20" still showing at 08:00), instance state (listed as running after being stopped elsewhere, deleted instances lingering, new ones missing), and -- worst -- actions against instances that no longer existed, which failed with raw Incus errors.

**Event-driven, not polled.**
Incus publishes every instance state change on `/1.0/events?type=lifecycle`, so the TUI subscribes once per session (`InstanceEventWatcher`) and refreshes when something relevant happens: changes appear within about a second, and an idle TUI costs nothing.
Polling was rejected as the primary mechanism because an interval short enough to feel live would re-list every instance continuously across a days-long session -- over vsock on macOS -- mostly to learn nothing changed.
Polling survives only as the fallback while the subscription is down (every 60s), so an older daemon or a flaky appliance still converges.
Lost events are the subscription's inherent gap, so every successful (re)connect triggers one full re-read rather than trusting the stream to be complete.

**Live updates yield to the Incus channel.**
The subscription is a convenience layered on the connection every other isx operation depends on, so when the two conflict, live updates lose.
The watcher gives up for the session after three failures in a row -- a connect that fails, or a subscription that drops within a minute -- and the TUI falls back to 60s polling with a one-line notice.
The reason is the macOS tunnel.
Every attempt is a new vsock stream through the appliance's forwarder, where closed streams can linger until reaped (see design/macos-and-vms.md "macOS vsock robustness"), so an unbounded reconnect loop over a days-long session is exactly the leak pattern that degraded every connection there.
A subscription that lived past a minute resets the budget, so a daemon restart is survived.
`tui-live-refresh: false` in `config.yaml` turns the whole mechanism off -- no subscription, no polling -- for anyone who would rather refresh by hand.
The action allowlist excludes `instance-exec`/`-console`/`-file-*`: the TUI's own shells and the proxy's probes produce those constantly and they change no row.
A start also schedules two follow-up reads, because the instance's address appears seconds after the start event and no event announces it.

**Keepalive and liveness.**
An event subscription can be silent for hours, which is exactly what the macOS forwarder's inactivity reaper (`socat -T`) and a half-open vsock stream after sleep/resume (see design/macos-and-vms.md "macOS vsock robustness") punish.
Incus heartbeats its event listeners with a ping about every 10s and drops listeners that never answer, so the event stream's reads (`readMessage()`) answer pings with pongs.
The exec/shell read path (`readPayload()`) deliberately still skips them without replying.
Incus doesn't ping exec sockets, and a pong written from the reader thread would have to wait for the lock a stdin write holds -- a stall risk on the channel that matters most, for no benefit.
`IncusApi.openEvents()` also pings every 15s and closes the stream after 50s with nothing received (five missed heartbeats), turning a half-open connection into an end-of-stream the watcher reconnects from, instead of a read blocked forever while the TUI believes it is live.
Event messages are read with `readMessage()`, which reassembles fragmented frames: the exec path could ignore frame boundaries because its payloads are byte streams, but an event is one JSON document per message.

**A cheap refresh path.**
`reloadData()` does far more than list instances -- it re-reads every YAML definition, measures pool usage, reads btrfs accounting status (an agent round trip on macOS), may run a privileged rfer probe, and checks proxy health -- all on the UI thread.
Running that per event would stall the UI during bursts (`isx clean`, a template rebuild's create/rename/delete).
So events drive a *light* refresh: the instance listing and pool usage are fetched on a background thread and merged on the UI thread with the definitions already in memory, reusing the last full reload's disk-accounting results.
Definitions cannot change because an instance did, and the accounting reads are deliberately on their own cadence ("the privileged read is rare, not per-refresh").
Full reloads remain on `r`, on TUI re-entry and after the TUI's own background tasks, and a generation counter discards a light result that a full reload overtook.

**Races are closed at the point of action.**
No refresh latency makes "the list is current" true at the instant a key is pressed, so actions re-check existence immediately before running and report "no longer exists" in the TUI instead of an Incus error.
While the subscription is up and every received event has been applied, the listing itself answers -- asking Incus on each keypress would let a wedged daemon freeze the UI -- and only otherwise does the check cost one `GET /1.0/instances/<name>`.
The one gap, an event still in transit, is covered by the action's own error handling and by the shell path re-checking before it connects.
Dialogs whose target disappears are closed with the same notice on the next refresh.
The detail and actions dialogs render the current table selection, so otherwise a refresh that moved the selection off a deleted row would silently repoint them at a different instance.
A failing existence check does not block the action: an Incus hiccup should surface as the action's own error, not freeze the UI.

**Ages as elapsed time.**
A wall-clock age ("today 21:20") is wrong the moment the date changes unless re-rendered, and misleading across midnight even when it is.
Ages are now elapsed-time wording ("3h ago") computed on whole minutes, so the text is a pure function of the current minute and the TUI re-renders rows exactly when the minute changes -- no Incus call involved.
The absolute timestamp is still in the F3 detail view.

## Warnings while the TUI owns the terminal

A warning printed while the TUI renders is drawn over and scrambles the screen, so shared code reports through a sink each caller routes (CLAUDE.md).
Two things were still wrong after that rule was written (#872).

**Some code has no caller to take a sink from.**
`ToolDefLoader` printed tool-definition problems to stderr, and loaders are built far from any command: `ToolProxyResolver.proxyToolSetups(config)` builds one for account validation, which the TUI reaches through `AccountSelection`.
Threading a `Consumer<String>` through every such path would change a dozen signatures for a warning none of them care about.
`Warnings` (`common`) is the entry point for this case: `warn(message)` goes to the bound output like every other warning (see "One output seam" below).
The TUI binds its log around its reloads and while its runner draws; while it has released the terminal to a build or a shell, which must print their own errors, it binds a terminal that also copies warnings into the log.
A TUI-launched build that refuses an unparsable file says "see the error above", so the error has to be above it.
Taking a sink from the caller stays the first choice; `Warnings` is for code where there is none.
The loaders also keep what they found (`ToolDefLoader.warnings()`, like `conflicts()` and `parseFailures()`), so tests and callers that want to act on it need not listen.
The default sinks of `ImageDef.loadAll()`/`loadAllWithConflicts()` go through it too, since `BranchFlow` -- which the TUI branches through -- calls `loadAll()`.

**A branch made from the TUI keeps its warnings in the log** (#1646).
The TUI branches on the released terminal, so `BranchFlow` prints there as `isx branch` does.
But the shell that follows draws over it, and the TUI redraws over that, so a warning printed then was gone before anyone read it.
The TUI binds `Output.copying` around every action it lends the terminal to, the branch included, which copies every warning of the branch into the `WarningLog`, as for a build.
So code on the branch path warns through `BuildOutput.warn` or a sink the caller gives it, which `RuntimeSetup.setupRuntime` takes for an incomplete setup and a failed SSH host entry.
A bare `System.err` line reaches the terminal only.
The sites on the branch path that printed one (GUI and KVM passthrough's explanations, the CA mismatch note, #1690) now warn through `BuildOutput`.
GUI and KVM passthrough return why they could not, the cause and then what to do about it, and the branch makes it one warning with what it does instead (`Unavailable.warn`), since the log used to keep only "Continuing without GUI passthrough." with no cause.
The pre-upgrade CA note is a warning rather than a note, so the TUI's copying binding keeps it.
`common/vm` and `common/proxy` report through `BuildOutput` too: the VM's start before the TUI opens is the same header, start and wait as `isx vm start`, and a proxy refusal is one banner the TUI's log keeps (`ProxyHealthCheck.refusal`).
`ProxyService` reports its install, stop and restart the same way: what was done as steps, why it failed as an error with the journal's lines or launchd's last run under it.
A command's answer has a kind of its own, `Output.results()`: a stream rather than a method per line, since the printers (`OutputFormat`, the tables) already take a `PrintStream`.
A terminal's is its stdout, as it is at that moment, so a result written during a live step freezes the step's line first; an output that is not a terminal has none, so a result is never drawn over the TUI.
A status report lays out its fields itself (`ProxyCommand.Status.field`): a result is content, and the fence on indented print literals is for progress and diagnostics, which groups indent.
A template's validation findings are not a result: `isx templates edit` reports each one as an error or a warning, and a clean file as an `ok`, so they take the terminal's colours and the safe lines every diagnostic gets.
A restart reached from a start (`tryAutoRestart`) reports to the `Output` its caller hands it, and so does `LaunchdJob`, whose refusals are warnings the TUI's log keeps.
The proxy daemon runs some of that code (the bridge DNS, certificates, the API traffic log), so it binds `Output.logTo`, which keeps progress as info lines instead of dropping them, as a log-only output would.
Why a command failed is an `error`, a kind of its own (red `Error:` on stderr, no closing blank line), since a warning would say it went on; off a terminal it is a diagnostic, and the TUI's copying binding keeps it.
Notes go to their own sink, which the proxy prints on stderr alone (`ProxyLog.trace`): the API traffic log writes one per request, which the lifecycle log of starts and reloads should not keep.
The daemon's own code says what it says through `ProxyLog` too, the proxy module's one gate to the raw streams.
Its help, version and start banner are `ProxyLog.stdout`, laid out as given; a reload, a start failure and a config watcher's failure are levels, which the lifecycle log keeps, advice lines included; a request's cache line or upstream error is a `trace`, with its stack trace where there is one.
The console tee that copies both streams into `proxy.log` lives there as well (`teeConsoleTo`), so the proxy's main replaces no stream itself.
A proxy refusal's lines are plain text, since the TUI's log keeps them as given; `tryAutoRestart` and `waitForDns` report their progress as notes and why they failed as warnings, so the log says why a restart did not help.
Progress stays on the terminal, where it is read while the branch runs.
A start's warning is a typed `warn` on the `Output` its caller hands it, its advice as details, so no message in `lifecycle/` marks itself `Warning:` for a sink that would print it as it comes.
Neither does code outside it: `AutoRemoteService` takes the caller's `Output` too, a remote added or removed a step, one it could not add or remove a warning.
`BuildOutput.warningText` still drops a leading marker from any warning's text, such as a guest command's own `Warning:` line.

`Warnings` reports a distinct message once per output.
The same files are loaded many times in one command (every fresh loader re-reads them), and before this `isx branch` could print one broken tool file's warning several times.
The memory is per output, not per process.
The TUI's drawing output lives for the whole TUI session, so returning from a shell does not re-announce the same definition problems, while the terminal is another destination, so a build still prints a warning the TUI already logged.
`forgetReported()` clears an output's memory for the one case that wants repeats: the user asking for a reload.

**One output seam** (#1703).
Before it, three mechanisms reported with no boundary between them: `BuildOutput`'s static printer, `Warnings` with its own redirect, and `Consumer<String>` sinks.
The TUI had to redirect one and copy the other, and the copy caught `BuildOutput.warn` alone, so a `stepWarn` or a banner on the branch path was drawn over and lost.
Progress printed while the TUI drew scrambled its screen, since only `Warnings` were redirected.
Now `BuildOutput`'s static methods hand every message to one bound `Output`, and only the owner of the terminal binds it: the CLI keeps the `TerminalOutput` default, the TUI binds its log while it draws and a copying terminal while it lends it out, `PreTuiOutput` a terminal that holds what `Warnings` reports, `isx mcp` a terminal on stderr, the proxy its log.
A failed step is printed, not copied: whoever called it reports the failure, as the TUI's status line does.
An output that is not a terminal ignores progress and notes by default, while every diagnostic defaults to `warn`, so a new binding cannot lose a warning by forgetting a method.
The TUI binds its copying terminal around every action it lends the terminal to, not per action, so the next action added cannot forget it.
`Warnings` remembers what it reported per destination (`Output.destinations()`), so a copy of the TUI's log does not add a warning that log already holds.
It reports through `Output.warnAside`, which a terminal prints on stderr alone: a command's own warning ends with a blank line on stdout, which would add an empty record to a query's results.
Bindings nest; one closed out of order throws, since it would put back an output already gone.
Every call site kept compiling, and the binding is one volatile read per message, so the branch/start/shell path costs nothing more.
**Alternative:** a tee of `System.err` into the TUI's log.
**Why not:** it splits multi-line messages, captures notes as warnings and hides the raw prints the fence (`RawOutputGateTest`) exists to expose.
**`lifecycle/` takes an `Output`, not `Consumer<String>` sinks.**
Its sinks lost the kind of each message: a progress sink also carried warnings, marked `Warning:` and indented by hand so a sink that printed as it came would show them, and a multi-line explanation had nowhere typed to go.
Some callers had no sink to pass at all: the TUI's restart started an airgapped instance with `msg -> {}`, so a dropped inbox went unsaid.
Now every reporting method there takes one `Output`, where a step is a `step` and a warning a `warn` with its details, and `StaticIpAllocator.Output` is gone for the same type.
The CLI passes `BuildOutput.bound()`, which forwards each message to whatever is bound when it is reported, and the TUI its drawing log, from its background threads too.
**Alternative:** let `lifecycle/` call `BuildOutput`'s static methods and rely on the binding.
**Why not:** the binding is process-wide, and the TUI runs lifecycle code on background threads while it draws (a restart, an account switch, a destroy); an explicit output keeps those messages with the TUI's log whatever is bound meanwhile.
Paths only the CLI or a lent terminal reaches (a branch, a build's starts) take `BuildOutput.bound()` from their caller, or default to it.

**Tests record, they do not capture** (#1703).
Tests used to swap `System.out` and `System.err` and search the bytes, which asserted on how a terminal draws a message rather than on what was said or of which kind.
A binding is process-wide too, so recording does not make tests safe to run in parallel; it makes them assert on meaning.
`RecordingOutput` (common test-jar) is the recording binding: every method of `Output` records a typed event (kind, message, details), and `OutputBindingTest` holds it to every method, so no kind falls through unrecorded.
A test binds it, or hands it to code that takes an `Output`; how an event is drawn is `BuildOutputTest`'s alone.
Checks whose only point was that nothing reached stderr, or that a copy reached a listener, were dropped, since the raw-output gate and the binding tests prove there is no other path; tests of which warnings a flow raises stay, reading the recorder or the log binding.
Nothing outside the gate classes prints raw any more, so only tests of a gate's own stream swap one: the terminal printer's rendering, the bindings, the pre-TUI hold and the proxy's `ProxyLog` lines.
`StreamSwapGateTest` lists them, each with why, and an entry that no longer swaps fails.
A class that calls another's swapping helper counts as swapping; it lists classes, not swaps, so a listed class's own helper can take a new caller, which review has to catch until the class is dropped.

**One status line cannot hold several warnings.**
The TUI used to put each warning on its status line, which shows one message until the next key.
Two operations warning close together -- a reload that finds a broken tool file, then a start that drops a missing inbox -- overwrote each other.
Each site had grown its own "first warning (+N more)" squeeze with no way to read the rest.
They now all go to one `WarningLog`, whichever thread raised them.
The status line only *announces* what arrived since the last frame (the latest, led by how many arrived, since a long line is cut off at the terminal width), and only when it is empty or holds an older announcement, so an action's result ("Build failed") is never replaced -- the warnings wait for the next key; the header keeps "⚠ N warnings (w)" up while any are unread, so an announcement cleared by a keypress is not lost; and `w` opens a dialog listing every warning, newest first, with its lines intact -- several warnings carry a command or a YAML fix to copy, which a one-line status bar cannot show.
A warning raised again moves to the end with the new time instead of appearing twice, and the log is capped at 100 entries.
Warnings still unread when the TUI exits are printed once the terminal is released, so quitting does not discard them.
A refusal too long for the line, such as a branch's missing credentials with their remedy and a tool's note (#1759), goes to the log too: the line keeps its first sentence and points at `w`, and the log entry counts as announced.
AI Help's refusal when no configured account can answer (#1765) does the same: its first sentence is kept short enough for the line, and its advice, up to the API credits URL, is in the log.

The log is not cleared by a reload, because it records warnings that happened, not the definitions' current state: a fixed tool file's warning stays, with its time, until cleared (`c` in the dialog).
Clearing also calls the TUI channel's `forgetReported()`, so a warning that is still true comes back on the next reload rather than staying hidden for the rest of the session.

**What isx says before the TUI opens is drawn over too** (#1154).
Bare `isx` gets ready first -- on macOS it starts the VM, which also checks the running appliance against the installed one; a fresh host runs `isx init` -- and anything printed then sits on the screen only until the TUI takes it over, to be read after quitting.
Fixing each message would leave the next one to be found the same way, so `IncusSpawn.launchTui()` opens a window (`PreTuiOutput`) that the TUI closes before its first reload (so time spent at the pause leaves no stale listing).
Inside it, `Warnings` go to a held list that is handed to the TUI's `warningChannel` (so it does not announce the same one again) and from there to the `WarningLog`, which announces them as it does any other warning.
The stale-appliance notice reaches it this way (`VmManager.warnOfSkew`), at no cost to the user.
The header's "Appliance X — restart VM for Y" stays where the skew remains visible, and keeps skipping the first load so opening the TUI asks the VM nothing extra.
Everything else still reaches the terminal when it is written (a VM start or init that hangs must show its progress).
But `System.out`/`System.err` are wrapped to note that something was printed, and if anything was, isx asks for Enter before opening the TUI.
The wrapping is `StreamWatch`, a gate class of `RawOutputGateTest` (#1703): it watches the raw streams rather than the bound output, so a print that bypasses the bound output still counts.
The held warnings are then printed above that prompt as well, since a Ctrl-C there would otherwise lose them (the TUI that would have shown them never opens).
An Enter typed while isx got ready is discarded so it cannot end the pause unseen.
The window wraps `isx init` too: init's own text is exactly what the pause must not let the TUI cover.
The cost is that a `Warnings` warning raised during init is shown at the pause rather than among init's prompts.
The two remedies the issue named are both used, each where it fits.
Deferring a warning keeps the common case free of a keypress, while progress and prompts are not warnings, and replaying them into the log would bury the warnings that are.
A TUI that never opens (init declined) prints the held warnings as it closes, so none is lost.
Without a terminal there is nothing to wait on and no TUI to cover anything, so no pause.

## Terminals the TUI cannot assume

Two things that looked fine in Ghostty broke in Termius (#1681).

**The screen is cleared before the first frame.**
tamboui sends only the cells that differ from the previous frame, and its first frame is compared with a blank one, so a cell the TUI leaves blank is never sent.
A terminal that keeps its old contents on entering the alternate screen showed them through those cells, such as the cursor gutter left of each table.
`Tui.openRunner` clears the screen once the runner is open.
Ctrl+L clears it again in any mode, for output something else wrote over the TUI, after its reload so the screen is not blank meanwhile.
tamboui clears on resize only, so this belongs upstream too; until then isx does it.

**The theme uses no RGB colours.**
tamboui writes an RGB colour as a 24-bit escape whatever the terminal supports, and Termius drew every one of them in the same green.
The `isx` badge, the disabled function keys and the scrollbar track became green, and RGB backgrounds became black.
`TuiTheme` takes a 256-colour palette entry instead, the nearest one unless that would merge two colours that sit together, such as a modal and its inactive input.
The dark theme's dialogs lose their slight blue tint and become grey.
It is used everywhere rather than when `COLORTERM` is unset, because SSH does not pass `COLORTERM` on, so most remote sessions would lose it anyway.
`TuiThemeTest` fails on an RGB colour in either theme.

**Keys are read in their legacy encoding.**
Pi and Claude Code turn on the kitty keyboard protocol, and Pi also xterm's `modifyOtherKeys`.
One that does not exit cleanly, or a multiplexer between, leaves the terminal sending Esc as `CSI 27 u` and Ctrl+C as `CSI 99;5 u`.
tamboui reads neither, so Esc and Ctrl+C did nothing, and a dialog that closes only on them could not be left.
Only terminals that speak the protocol do this, which is why Esc worked in one terminal and not another.
`LegacyKeyboardBackend` pushes kitty flags of zero on the TUI's alternate screen and pops them before it leaves, never touching the main screen's stack.
What still arrives encoded, from a terminal that ignores the push or a multiplexer that keeps its own mode, `KeyboardProtocol` turns back into legacy bytes before tamboui parses them.
It decodes `CSI code;mods u` and `CSI 27;mods;code ~`, drops key releases and keys with no legacy byte, and hands every other sequence on byte for byte.
Shift with a key that reports its shifted form (kitty's alternate keys, which Pi turns on) becomes that form, so Alt+Shift+1 is Alt+`!` as on a legacy terminal, never Alt+1 (Shift+F1).
A dropped key reads as a timeout rather than a second wait, since a release follows every press under Pi's flags and would hold back the runner's tick.
`ShiftTabBindings` no longer takes an unknown key for Shift+Tab: tamboui reads `CSI Z` itself now, and the old fallback turned an undecoded Esc into focus moving back.
`KeyboardProtocolTest` runs each encoding through tamboui's own parser.

**Keyboards without F-keys** (#1824).
Many keyboards have no F row, or give it to media keys, as MacBooks and 60% boards do.
So a digit is its F-key, 0 being F10, and Alt with a digit is Shift with that F-key.
The key bar keeps its F-key labels and draws each one's digit bold, and the rule is stated only in the F1 dialog, as what to press (`1–9, 0 stand in for F1–F9, F10`): on the context line it was constant noise that confused more than it helped.
The F1 dialog lists both forms of every key, and on macOS how to make Option send Alt.
The rule is the same on every keyboard layout.
A terminal sends a digit as the digit however the layout types it, and Alt as an Esc before it, which tamboui reports as `ALT`.
It never reports Shift on a digit, only the layout's own symbol above it (`@` on US, `"` on UK and German), and the TUI reads keys in their legacy encoding, without the Kitty keyboard protocol or `modifyOtherKeys`, the only ways a terminal says more.
Shift+digit as the US symbols `!@#$%^&*()` was rejected as confusing everywhere else (Sanne): on AZERTY `&` is the unshifted 1 key, and would restart a row at once.
`Tui.handleEvent` translates the key once, before the mode dispatch (`DigitKeys`), so every F-key check in every dialog takes it and none changes.
A mode that reads digits itself says so on its `Mode` constant, which every constant must: the text fields (branch, rename, new template, AI Help) and the build menu, whose options are numbered.
The search bar keeps them too.
Mnemonic letters lost because the free letters do not match the labels (`r`, `a`, `c` and `n` are taken) and the bar has no room for a second key per item.
Esc then a digit, as Midnight Commander offers, lost because tamboui folds Esc and a key into Alt only within 50 ms, and Esc already clears the marks.
Ctrl+digit lost because terminals send most of them as control characters such as NUL and Esc.
