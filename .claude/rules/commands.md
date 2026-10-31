---
paths:
  - "cli/src/main/java/dev/incusspawn/IncusSpawn*.java"
  - "cli/src/main/java/dev/incusspawn/command/**"
  - "cli/src/main/java/dev/incusspawn/tui/**"
  - "common/src/main/java/dev/incusspawn/util/OutputFormat.java"
  - "integrations/**"
---

# Entry Point and Command Structure

`IncusSpawn.java` is the aesh `@CommandDefinition` top command.
With no subcommand, it launches the TUI (`Tui`).
Each subcommand in `command/` is an aesh `@CommandDefinition` with Quarkus DI.

**Platform-specific command tree**: aesh bakes `groupCommands` into the annotation at compile time, so macOS-only commands can't exist on one platform without a second top command.
`IncusSpawn` defines two variants -- `IncusSpawnCommand` (macOS, includes the `vm` group) and `IncusSpawnLinuxCommand` (identical minus `vm`) -- and picks one at runtime via `Platform.isMacOS()`.
The result: on Linux `isx vm` is an unknown command and never appears in `isx --help` (Incus runs natively there, so there is no appliance VM to manage).
The completion scripts ask the running binary, so they follow the tree with no edit of their own.
When adding a *shared* command, add it to **both** command lists -- `IncusSpawnCommandTreeTest` fails if they drift (the two lists must be identical except for `vm`).
When adding another macOS-only command, add it to `IncusSpawnCommand` only.

**Dynamic completion** (#1744, #1842): `isx --aesh-complete -- <words>` answers a TAB from the running binary's command tree, which `IncusSpawn.run` hands to `Completers.answer` before aesh sees it.
Every script `isx completion` prints (`CompletionCommand.script`) asks it and lists no command, option or value of its own, so a new command or option needs no script change.
The request's words and candidates are plain values: each script sends a word's value, its quoting removed by something that expands nothing, and quotes what it inserts by its shell's rules.
`answer` drops a candidate holding a control character, and path options complete through `Completers.HostPath`, never aesh's file completer, which escapes a lone candidate for its own readline.
A value comes from a completer on the argument or option itself (`completer = Completers.Instance.class` and its siblings), so a new instance, template, tool or account argument names one.
A completer offers names only, never starts a VM, costs at most one Incus request, and offers nothing when its source fails.
`answer` writes each candidate as the whole word (`--format=json`) and, after a TAB, a command's or option's description or the one its completer gave with `Names.describe` (`default` beside the default account); `CompletersTest` pins both.
`answer` drops the commands in `IncusSpawn.NOT_COMPLETED` (the hidden plumbing and `completion`), which stay runnable.

Account values: `Completers.Account` and `InstanceThenAccounts` offer `AccountCommand.completionNames`: every namespace for `account unset`, and for `--account` and `account set` `ns=` / `ns=account` for the usable accounts, the bare `ns=` dropped once a namespace is typed.
`account add`'s positional completes from `AccountCommand.addCompletionLines` instead (`CredentialCheck.acceptedNames`): each credential step's namespace and its tool's name, as `credentialTool` resolves them for `addCredential`, so `gh` is offered beside `github`.
It offers only names `ToolDefValidator.isNamespace` accepts, though the loader already refuses a tool whose name is not one (#1778).
`isx account list --completion` and `isx account add --completion` print the same names for scripts saved by releases before #1842; no script isx prints calls them (docs/dev/MIGRATIONS.md).

Never hand what isx prints to anything that expands it: bash's `compgen -W` expanded its word list, so a cloned repository's tool named `$(...)` ran on TAB after `isx tools show`, and aesh's generated bash script still does that.
The bash script reads the words from `COMP_LINE` with its own `_isx_words` (bash-completion's dequoting calls `eval`, and its `COMP_WORDS` split at `@` and the other word breaks), and never uses `compgen` or `eval` (`CompletionCommandTest.bashNeverExpandsWhatIsxPrints`).
Readline inserts a candidate as is, after the quote the word left open, so `_isx_quote_for` quotes it for that quote: `printf %q` outside one, `'\''` for a `'` inside `'`, a backslash before `$`, backtick, `"` and `\` inside `"`.
Never `%q` inside a quote: it writes a name the locale cannot print (U+2028, any non-ASCII under `LC_ALL=C`) as `$'...'`, which inside `"` leaves a `$(...)` live; `aCandidateIsQuotedForTheQuoteLeftOpen` presses Enter after each quote.
The script runs on macOS's stock bash 3.2, which `unit-tests-macos` runs it under: no negative subscripts, `compopt` (bash 4) only behind `2>/dev/null`, and a `!` is written outside `$'...'` too, since bash 3.2 expands history there.
Readline replaces only what follows the word's last unquoted `COMP_WORDBREAKS` character or its open quote, so the script drops the part before (`_isx_kept`) from each whole-word candidate before quoting; a candidate that does not start with it is not offered.
A closing quote is no break (bash's `char_is_quoted` covers it), so `_isx_kept` returns to what it was before that quote opened; otherwise a second TAB into `"./a b/"` replaced the word with `sub/` (`aSecondTabIntoAQuotedDirectoryKeepsThePath`).
Readline adds no closing quote to a line that already ends in that character, so a quoted candidate ending in one (a trailing `'` inside `'`, `\"` inside `"`, or a `!` written outside the quote) gets its quote closed by `_isx` when it is the lone match, as readline closes only a lone match's quote (`aCandidateEndingInItsQuoteLeavesItClosed`, `onlyALoneMatchHasItsQuoteClosed`).
A redirection's target (`> ou`, `2>ou`) asks isx nothing: the script leaves `COMPREPLY` empty under `compopt -o default`, so readline completes file names (`aRedirectionTargetFallsBackToFileNames`); bash 3.2 has no `compopt`, so there the script is registered with `-o default`, and any empty answer falls back to file names.
zsh removes quoting with `(Q)` and inserts through `_describe -U`, whose `compadd` quotes; fish reads `commandline -opc` and `string unescape`, and quotes and never evaluates what `__isx_complete` prints.
A directory or a bare `ns=` gets no trailing space.
isx answers a typed `~/` (or `--opt=~/`) with the home directory; fish, which matches candidates against the word, gets it back as typed, and so does bash where the `~/` is in `_isx_kept`.
The hostile-name tests (`noCandidateIsExpanded*` in the three shells' completion tests) feed a command substitution to every kind of place that offers a value.
`BashAccountCompletionTest`, `FishAccountCompletionTest` and `ZshCompletionTest` run the script in a real shell against a `FakeIsx`, which records each request's words and answers with `Completers.answer` for them, or with a given answer; each skips without its shell.
`ZshCompletionTest` types each line into a real interactive zsh through `zpty` and pins the buffer TAB leaves behind.
Its driver (`zsh-completion-driver.zsh`) waits on markers only the sourced setup or the widget can print, with blocking `zpty -r -m` reads that drain the pty, never on timed polling: on macOS the timed loop hung (#1738).
It runs `compinit -D`, so no test writes the developer's `~/.zcompdump`.

`isx account add [<ns>|<tool>]` is `InitCommand.addCredential`: init's step for that one credential, or with no argument its Credential Setup menu, under the same `[n/N]` headers (#1469).
Init's credential and host steps read their answers from `InitCommand.prompts()`, which tests override.
Every message naming a missing credential's remedy takes it from `CredentialCheck.Missing.command()`, so it names `isx account add <ns>` only when a step owns that namespace.
A generic tool step's own "configure later" line uses `CredentialCheck.stepCommand`, the same rule seen from the tool.
Claude's and GitHub's steps name their own namespace (`SpawnConfig.ClaudeConfig.NAMESPACE`, `GhSetup.NAMESPACE`) directly: those built-in tools always own it, so the rule would only load every tool definition to print the same command.

**`isx mcp`** is a normal (visible) command, but it is started by an agent, not typed: it serves MCP on stdin/stdout until end of input.
See `.claude/rules/mcp.md`.

**Hidden (internal) commands**: Some commands are plumbing not meant for direct user invocation: `git-remote-helper` (invoked by git), `ssh-proxy` (SSH ProxyCommand).
These remain in `groupCommands` (so they're still executable) but are hidden from `isx --help` and shell tab-completion.
`IncusSpawn.HIDDEN_COMMANDS` is the single source of truth for the help filter; `IncusSpawn.filterHiddenCommands()` intercepts `isx --help`/`-h` before aesh and strips matching lines from the rendered help via `LazyHelp.render()`.
Completion drops them through `IncusSpawn.NOT_COMPLETED`, which every script reaches through `Completers.answer`.
`IncusSpawnCommandTreeTest.hiddenCommandsAreStillRegistered` asserts they stay in `groupCommands`; `CompletersTest.neverOffersTheHiddenCommandsOrCompletionItself` asserts that completion offers neither them nor `completion`.
`completion` is listed in `isx --help`, the one place a user finds it before completion is installed, but the completion scripts do not offer it: nobody tab-completes the command that sets up tab completion (#1802).
When hiding a new command: add it to `HIDDEN_COMMANDS` (which `NOT_COMPLETED` is built from) and run both tests.

**`isx start`, `isx stop` and owners are for programs that drive isx (#1720).**
`isx start` is `isx shell`'s preparation without the shell (`InstanceStart`, shared with `isx ssh-proxy --start`): `InstancePrep.prepare` under the `starting` lock, waiting up to two minutes for another holder, never a bare Incus start.
`isx stop` stops under the lock and the `stopping` mark (`InstanceDestroyer.stopMarkedIf`, shared with `isx mcp`'s held stop), and a stopped instance is success.
`isx ssh-proxy --start` uses a running container no isx process holds as it is only when its boot holds its secret (`InstanceLifecycle.readyForThisBoot`, one read per connection), and prepares any other.
`isx branch --owner <name> [--owner-id <id>]` stamps `Metadata.OWNER`/`OWNER_ID`, ownership keys no copy inherits (`Metadata.isOwnershipKey`, used where copies drop `mcp-*`); `BranchCommand.ownerProblem` validates both before anything is made.
Only the ProxyCommand `isx branch --format=json` hands out has `--start` (`SshKeyManager.proxyCommand`); the Host block never does.
`isx ssh-proxy --start` installs `StdioGuard` and relays the guard's stdin and stdout, since a start prints progress and stdout is the SSH stream; without `--start` it relays `TerminalStreams` as before.
`isx branch --format=plain` stays the name alone; its new fields are in `json` only (`BranchCommand.record(name, owner, ownerId, reach)`).
`isx destroy --owner` (`DestroyCommand.destroyOwned`) fails closed on `drivers:` (`Drivers`, config.yaml only), then deletes through `deleteHeldIf` on the owner stamps; a missing instance is success.
A plain destroy of an owned instance goes through `confirmDestructive`.
`isx list`'s `OWNER` column (`ListCommand.ownerCell`) folds in `isx mcp`'s standing; `owner`/`owner_id` are appended to its formats.
The TUI's instance table shows the same column only when some instance is owned and the panel is at least `MainScreen.OWNER_COLUMN_MIN_WIDTH` wide (`TuiOwnerColumnTest`); the instance details always show the owner (`InstanceDetailView.ownerDetailRows`).
`integrations/orca/isx-orca-recipe` is Orca's recipe contract over these commands, in bash and `jq`; `OrcaRecipeScriptTest` runs it against a stand-in `isx` and pins the exact `isx` command lines it runs and the result it prints from a recorded `isx branch --format=json` output.
The stand-in does not check that isx accepts those flags or still prints those fields, so a change to either must update the script and the recorded output together.

**Output for scripts goes through `OutputFormat`.**
A query command offers `--format=table|plain|json` (#1036) by parsing `--format` with `OutputFormat.parse` (`OutputFormat.resolve` where there is also a `--plain`), building each result once as an ordered `Map<String, Object>` (a `LinkedHashMap`; maps need no native reflection registration) in a package-private `record(s)` method, and handing it to `format.print`/`printOne`.
`table` stays the command's own human output.
`plain`/`json` are a contract: fields are added at the end, never renamed, removed or reordered, so a field change needs a golden test update that says so (`ListCommandOutputTest`).
They carry no header, padding, colour, glyph or prose, no results is empty output (or `[]`), and diagnostics go to stderr.
Times are ISO-8601 with offset (`Metadata.createdIso` for a `created` stamp: a legacy date-only stamp stays a date, an unreadable one is `null`), an unreadable Incus listing is an `IncusException` (exit 1), never an empty list, a command line aesh cannot parse exits 2 (the exit-code table in `docs/CLI.md`, "Scripting isx"; `ExitCodeTest`), sizes bytes, absent values `null`/`-`.
No value reaches the terminal with a control character raw (#1118): `plain` turns each into a space, bidi embeddings, overrides and isolates too (`OutputFormat.oneLine`, lossy), `json` escapes the same set, `OutputFormat.isControl` (exact: `\t` `\n` `\r` `\b` `\f` for tab, line feed, carriage return, backspace and form feed, `\uXXXX` for every other one), and the `isx list` table and TUI show an instance stamp through the same `oneLine`, as the `isx templates` and `isx tools` tables and `isx templates edit`'s validation findings and `isx init`'s credential prompts (#1222) and `HostResourceSetup`'s build and start notes (#1235) show definition text (#1133: project-local definitions are untrusted).
`isx list` outside the TUI (`ListCommand.printListing`) reads the instance listing once (`-q` at `recursion=1`, since completion runs it on every TAB) and never loads definitions.
Templates are excluded by the `base` type, never by the `tpl-` prefix (which is why `BranchFlow` stamps `clone` in the copy request: a half-made branch must not pass for a template).
Its request count is pinned.
Its fields are `name`, `status`, `ipv4`, `parent`, `runtime`, `created`, `mcp_state`, `mcp_purpose` (#1053: `McpStanding`, `null` for an instance no `isx mcp` session made), `base_image` (#1657: `BaseImageAge.tag`, the `image.incus-spawn.tag` Incus copied from the image); `docs/CLI.md`'s "Scripting isx" lists them.
Every query command has it, plus `isx branch` (the new name alone).
`isx templates` adds build state and staleness from `TemplateStaleness`, the same judgement as the TUI's `! △ ↑` marks and `isx build --out-of-sync` (`Staleness.outOfSync()` says which count), so a change to what counts as stale goes there, never into one caller.
`--out-of-sync` calls `assess` on the same one listing; a named build's parents go through `BuildCommand.isImageOutdated`, which calls its `versionOutdated`, `definitionChanged` and `parentRebuilt`.
`QueryCommandFormatTest` pins each JSON shape and `ExitCodeTest` the shared rejection.
Work that prints progress runs under `BaseCommand.withStdoutOnStderr` (its swap is the gate class `StdoutOnStderr`), the machine formats never prompt, and the exit code is the table's.
See DESIGN.md "Output for scripts".

**Confirmations that delete data fail closed.**
`BaseCommand.confirm()` proceeds when there is no terminal (and never reads stdin), so `echo n | isx ...` answers yes.
`confirm` and `confirmDestructive` ask through `Prompts.terminal()`, null unless stdin and stdout are a terminal (#1703).
On JDK 25 `System.console()` is already null when either is redirected; `terminal()` also guards against a console that exists with stdin redirected (JDK 22 to 24, or `-Djdk.console=jdk.internal.le`).
Anything that deletes data -- `destroy --all-*`, `clean`, `reset`, `vm reset` -- uses `confirmDestructive(prompt, skip, skipFlag)` instead.
Without a terminal (`System.console()` null or `!isTerminal()`) it throws `NoTerminalException`, which `BaseCommand.execute` reports as an error with exit status 1, naming the flag that skips the question.
Declining at the prompt is still a successful exit.
A clean that also deletes the DNF cache volume lists it with what it deletes and deletes it only on that same answer (`CleanCommand.DnfCacheVolume`, #1627).
`isx clean dnf`, whose whole job is that volume, exits 1 when it could not ask Incus whether the volume exists or could not delete it, rather than reporting nothing to clean or success.
`confirm()` stays for disruptive but recoverable actions (`vm restart`, `vm resize`); `BuildCommand` keeps its own proceed-without-a-terminal confirm, which CI relies on.

**Tidying state never takes the instances (#1155).**
On macOS `Environment.vmDataImage()` sits in the state directory but is the whole Incus pool and database.
`isx clean state`/`all` and `uninstall.sh` delete the rest of the directory and keep it unless `--delete-instances` is given (`CleanCommand.cleanDirs`), and say so.
A new command that deletes `vmStateDir()` does the same; `isx reset` and `isx vm reset` are the commands that wipe it on purpose.
A kept disk is only as intact as the VM's stop.
`VmManager.stop()` returns a `StopResult`, and `isx vm stop --require-clean` exits 4 when the VM was `SIGNALLED` rather than shut down (every stop of an appliance from before #881), which `uninstall.sh` turns into its warning.
A caller about to delete the disks stops with `stopToDelete()`, which does not warn and does not ask the guest.
`stopLocked` asks the guest first, through the agent's `shutdown` verb (`VmAgentClient.shutdown()`), and waits up to 30 s for the hypervisor to exit by itself: vfkit's own stop request does nothing in this guest, so without the verb every stop is a hard power-off that loses up to five minutes of btrfs changes (`commit=300`; appliance/DESIGN.md "Stop sequence").
The exit status is opt-in so `isx vm stop && ...` keeps working.

**Remediations act through isx, never name an `incus` command (#986).**
Doctor and build say what is done and do it through `IncusClient` (REST, so the same on Linux and over the macOS vsock tunnel, whose host has no `incus` CLI).
A destructive step on a trust gate still asks, at a terminal, via `Prompts`.
`NoIncusCliHintTest` fails on any `"... incus storage|profile|move|image|network|remote|console|config ..."` literal in `cli`, `common` or `proxy` outside its allowlist (Linux-only `isx init` setup hints, the Linux arms of `unknownRemoteMsg` and `gatewayUnavailableHint`, and the proxy's Linux `--gateway-ip` check, #1139).
See DESIGN.md "Doctor and build remediations act through isx".

**A shell without a terminal is skipped or refused, never retried.**
`isx branch` with no terminal (`IncusClient.hasTerminal()` false: stdin or stdout redirected) reports the branch, says how to connect, and exits 0 without a shell.
`isx shell` refuses with `NoTerminalForShellException` before starting the instance.
`isx run`'s shell paths get the same exception from `interactiveShell()`.
Either way exit status 1 comes from `BaseCommand.execute` (#1027).

# Init and Versioned Completion

`InitCommand` runs first-time setup (dependencies, Incus, firewall, CA, proxy service, etc.).
On Linux it also installs a tightly-scoped NOPASSWD sudoers rule (`configureBtrfsUsageAccess` -> `/etc/sudoers.d/incus-spawn-btrfs`) permitting only read-only `btrfs qgroup show`/`subvolume list` plus `btrfs quota rescan` (rebuilds accounting counters, never touches data — the auto-repair for inconsistent qgroups, see `BtrfsUsage`) against the CoW pool mount, so the non-root TUI/build can read referenced sizes for per-template disk accounting and repair them when btrfs flags them stale (validated with `visudo -cf` before install).
Commands that need a working environment call `InitCommand.requireInit()`, which auto-launches init if it hasn't completed.
Completion is tracked by a sentinel file `~/.config/incus-spawn/.init-complete` containing `INIT_VERSION` -- a version integer defined in `Environment` (in `common`, so `isx-proxy` reads it too).
Every init step is idempotent, so re-running is safe.
**The sentinel is written before the last step installs, upgrades or restarts the proxy service** (`completeWithMacOsServices` / `completeWithProxyService`: ask, mark, then act), never after it.
`isx-proxy` exits `EXIT_CONFIG` without the sentinel, so a service installed first fails its first start, and init reports a proxy that is not responding (#938; launchd retries only after its 10 s `ThrottleInterval`, systemd not at all).
A step added to init goes above that one.
`InitServiceStepTest` pins the order.

**Interactive prompts read from `Prompts`, never a `Console`, and show their questions through it.**
`java.io.Console` is final, so a method that takes one is reachable only by a human at a keyboard -- which is how the credential flows shipped bugs no test could catch (#740, #772).
Each interactive step is split in two: a thin entry (`setupGitHubAuth()`, `setupClaudeAuth()`, `setupGenericToolCredentials(name, tool)`, `selectCredentials(tools)`, `setupPathList(...)`) that prints the step header, loads the config and gets `Prompts.console()`, and a package-private overload on `CredentialSetup` taking `(SpawnConfig, Prompts)` that holds all the logic.
Effects outside the process are package-private overridable seams on `CredentialSetup` -- `verifyGitHubToken`, `verifyAnthropicApiKey`/`verifyOauthToken`/`verifyVertexConfig`, `ghCliLoggedIn`/`readGhAuthToken`, `openUrl`, `env`, `hostHasCommand`, `runClaudeSetupToken` -- and on `InitCommand` -- `hostHasCommand`, `proxyServiceActive`, `macOsServicesInstalled`, `upgradeProxyService`, `installProxyService` -- which the flow tests override.
Flow tests: `GitHubAuthFlowTest` (incl. the commit-email choice, `chooseEmail`), `ClaudeAuthFlowTest` (incl. credentials detected in the environment), `CredentialPromptsTest`, `PathListPromptsTest`, `AccountMenuTest`, `InitServiceStepTest`.
Tests drive flows with `ScriptedPrompts`, which fails if a value scripted as a secret is read through an echoing `readLine()` (or vice versa) and answers `null` (EOF) once exhausted, like `isx init </dev/null`.
`@ExtendWith(IsolatedHome.class)` is required for anything that can reach `config.save()`, which otherwise writes the developer's real `~/.config/incus-spawn/config.yaml`.
Secrets are read with `askSecret(prompts)`, never a bare `readPassword()`.
Nothing is echoed, so it prints a dimmed `✓ Received N characters (<maskSecret>)` line confirming a paste landed -- length and masked form only, which `GitHubAuthFlowTest` checks by asserting the raw token never reaches stdout.
New prompts in init follow the same shape: take `Prompts`, route any network or host call through an overridable method, and add a flow test asserting what reaches the file on disk.
The host-setup confirmations (Incus install, subid, CoW pool) ask through the same `Prompts` (`askYesNo`).

**Init output** (#906).
Init keeps its own frame -- the boxed, numbered step headers and the `┃` hint bar -- but the lines under a header follow these rules.
Which kind each line is (live step, note, step, step detail, success) and that its label is bare are the rules of `.claude/rules/terminal-output.md`, "Five kinds, and bare labels".
*The step counter is derived, never written down*: `runSteps(setup, finish)` counts its lists, Credential Setup and the credentials chosen there, so `[n/N]` adds up on every path (credentials or none, Linux or macOS) and the last step reads `[N/N]`.
Each `Step` shows exactly one header, so a new step goes into a list, never a bare `startStep` call between them.
*Report outcomes, not attempts*: a success line ("Configured X") prints only once every command of that step succeeded.
When a file is in place but applying it failed, say it applies after a reboot, as the sysctl, NetworkManager and KSM steps do.
A step that runs several commands runs each through `ran()`, which names the one that failed, and combines the results (`ok &= ran(...)`, never short-circuiting, so the remaining rules are still applied) before choosing between its success line and a warning (#1102: the firewall and PREROUTING steps).
*Host commands are captured*: `runHostQuiet` shows a command's output only when it fails (as `Container.runQuiet()` does in build), so firewall-cmd's `NOT_ENABLED`/`ALREADY_ENABLED` chatter on success never reaches the step.
*Each host change is a live step* (`BuildOutput.liveStep`, #1791): a ✓ once its commands succeeded, and a ✗ when one failed, below the output and warning that command's failure printed.
`runHost` (inherited output) and a `sudo` through `runHostQuiet` release the terminal first, since a password prompt on the tty bypasses the stream guard.
`InitStepOutputTest` pins all three; `InitFirewallOutputTest` pins the firewall steps.

**The group init adds** (#1436).
A process gets its groups at login, so the `incus-admin` membership Incus Installation adds is not this run's.
When the socket refuses this process and the user is in the group, `continueInGroup()` runs the same command line under `sg incus-admin` (an inner `sg <primary>` keeps the user's primary group), and this process exits with its code.
The probe (`incusConnectivity`, `IncusClient.diagnoseSocket`) connects afresh, since `IncusClient` keeps a failed connect and step 3 must probe for itself.
The continuation sees `ISX_INIT_CONTINUED`, skips the banner, and `runLinuxSteps` starts it after Incus Installation; it closes with `GROUP_PENDING`.
`InitGroupContinuationTest` pins it, through `runLinuxSteps` with a fake `host()`, and with `hasSg`, `incusConnectivity`, `ownCommandLine`, `runContinuation` and `exit` as its seams.

**When to bump `INIT_VERSION`**: increment it when adding a new infrastructure step to init that existing installations need (new dependency, firewall rule, systemd service, config field).
A sentinel *older* than `INIT_VERSION` causes `hasBeenInitialized()` to return false, triggering a re-run on the next command.
Do NOT bump it for changes that don't affect host configuration (new template features, TUI changes, proxy logic changes).

The comparison is `>=`, not equality: the sentinel is a monotonic floor, so a binary that finds a *newer* sentinel treats itself as initialized rather than concluding init never ran.
This matters when two isx builds are installed at once (e.g. `/usr/bin/isx` and `~/.local/bin/isx`) -- under equality the older one both hard-failed the proxy service and re-ran init to write the sentinel back down, ping-ponging with the newer binary.
Never renumber `INIT_VERSION` downward.
