# MCP and external orchestrators

How agents and orchestrators drive isx: the MCP server, and the ordinary commands an orchestrator calls.

## MCP server: delegating work to isx instances

`isx mcp` lets an agent on the host -- the user's own Claude Code -- use isx the way the user does: create disposable instances from approved templates, run commands in them, and hand whole tasks to Claude Code, Codex or Pi inside one.
The host agent plans and reviews; instances do the work, with the same isolation and credential injection as any branch.

Managed delegation selects the installed worker agent independently of the MCP client.
The [Codex worker design](../docs/dev/MCP-CODEX-DELEGATION.md) describes agent discovery, durable continuation, adoption, and explicit capability differences.
Existing Claude-only calls retain their defaults; mixed templates default to Claude, then Codex, and Pi-only templates default to Pi.
The [Pi worker design](../docs/dev/MCP-PI-DELEGATION.md) records what differs for Pi.
Pi resumes by an exact session ID isx chooses before launch, so nothing has to be captured; but `--session-id` silently creates a missing session, so a resume checks that the session header's creation time is run 1's.
Pi has no sandbox or permission modes, so it always runs with every tool.

**Workers run with full access, bounded by the instance (#1857).**
Claude runs in `bypassPermissions`, Codex in `danger-full-access` with `approval_policy=never`, Pi with every tool, and none has a turn limit (`DelegateAgent.permissionMode`).
`mcp.yaml` once had `delegate-permission-mode(s)`, `delegate-max-turns` and `codex.sandbox-mode(s)`, and a delegate's `max_turns`.
They added policy surface and refusals that coupled the agents: Codex and Pi were refused while a Claude mode was restrictive or a turn ceiling set, since neither could honour it.
The isolation boundary is the instance itself, its proxy account bounding what credentials reach, so a narrower mode inside it protected nothing the instance does not.
The keys stay readable (`McpConfig.RETIRED`) because an unknown key makes the whole policy `Invalid`, which would have broken every MCP call for anyone who set one; they have no effect, `isx mcp` logs once at start and `isx init` warns, and a save drops them.
A `max_turns` argument is refused as `invalid_argument` rather than ignored, so a caller that relied on a budget learns it has none; the output schemas lost `supports_max_turns`, `refusal`, `delegate_max_turns` and `max_turns`, a deliberate removal in a contract that otherwise only grows.

**Hand-rolled protocol, measured.**
The Quarkus MCP extension was tried first, initialized only on demand (`quarkus.mcp.server.stdio.initialization-enabled=false`, started explicitly by the command).
It still cost every isx command.
Native `isx --help` went from 3.2 to 4.9 ms (+55%; Vert.x alone was +0.7 ms), the binary grew 11% and RSS 5 MB, because the extension's config mappings and metadata are built at runtime init whether or not MCP is served.
What isx needs of the protocol is small -- `initialize`, `ping`, `tools/list`, `tools/call`, cancellation, progress -- so `cli/.../mcp/` implements it over jjq `JqValue` trees (no reflection registration), and startup is unchanged.
`McpTransport` is the seam for a later HTTP transport.

**Stdout is the protocol.**
Much of isx prints progress to `System.out`.
Rather than teach every helper about MCP, `StdioGuard` hands the raw fd 0/1 to the transport, points `System.out` at stderr (which the client logs) and empties `System.in`.
The `Headless` flag makes `TerminalProgress` stop animating and `Prompts.console()` return nothing, so no prompt can consume protocol bytes.
The MCP path never calls `requireInit()` or anything that `System.exit`s: each becomes a tool error telling the agent what to ask the user.
What the session logs itself goes through `McpLog`, its one gate to stderr as `ProxyLog` is the proxy's (#1703).
It writes a message and its details as one block with one `println`, its first line prefixed `isx mcp: `, so another `System.err` write cannot land inside a stack trace.

**Who is calling.**
Over stdio there is no endpoint: the client spawned `isx mcp` and holds both pipes, so authentication is the OS user -- anyone who could reach the server could run `isx` anyway.
A session is the `isx mcp` process, identified as `<pid>-<start>`; the start makes the id safe to test for liveness after the pid is reused.
On Linux it is the kernel's `starttime` in clock ticks since boot, not the JDK's start instant: that is derived from `/proc/stat`'s `btime`, which moves when NTP steps the clock, so a later process could compute a different start for a live holder and treat its instances as orphans -- adoptable without `force`, then destroyed. macOS records the start instant once, at fork, so it uses the JDK's.
The client's self-reported name, its pid and working directory are stamped too, for display only.

**Owned by the user, held by a session (#898).**
The first version tied an instance to the process that created it: destroyed when that process ended, unreachable through MCP once kept, reaped on sight by the next session.
That fits one agent using isx from its shell, and fails the consumer it was built for -- a coordinating agent that dispatches issues to workers and restarts (its context fills, it crashes, the machine reboots) while those workers wait on CI or a person's reply.
Every restart stranded or destroyed them.
Keeping everything was no way out: kept instances were unreachable and nothing cleaned them up.
So an instance now *belongs* to the host user (`mcp-owner`) and carries a free-text `mcp-purpose`; `mcp-session` says which session *holds* it, and that session's liveness is all it is read for.
`adopt_instance` lets any session of the same user take over an instance whose holder is dead (or, with `force`, a stuck live one).
It re-stamps the holder in one write and reads it back, so of two sessions adopting at once only the last writer passes, and the loser's next `requireOwned` fails.
The task state was always in the instance (`~/.isx-mcp/tasks/<id>/`, now with each task's physical `cwd`), so adoption rebuilds the task registry from it and the ids keep working.
Task ids carry a random per-session tag rather than the pid, so they cannot collide with the adopting session's.
`max-instances` counts per user across sessions -- one listing per create -- including orphans, which are still using the machine; kept instances are the user's and never count.
`max-concurrent-tasks` counts per user the same way (#1015), and for the same reason.
The machine runs every task whichever process holds it, and a controller is one session running ten tasks, which a per-session cap sized for an editor stopped at once.
The truth about a task is in its instance (its files and systemd unit).
So a reservation asks each running instance of the user's that another session holds or that is orphaned, with one exec in parallel (`TaskScripts.busy()`), and makes no exec when there is none.
A kept instance counts while a live session still holds it (that session can go on starting tasks there) and stops counting with it, as for `max-instances`.
The count is taken again after a delegate's instance is branched, since other sessions may have started tasks meanwhile.
A host-side ledger lost: each session's own belief goes stale-high as soon as its tasks finish unobserved, and would block the others for nothing.
An instance that cannot be asked adds no tasks to the count, so it cannot block every later task; `max-instances` still bounds it.
Nor can one that never answers hold up every task start or pile up processes.
The probe runs isx's script as agentuser's uid without a login shell (`IncusClient.execProbe`), so a profile that hangs `su -` -- which an agent in the instance can arrange -- never runs; and it is a bounded exec (`execStreamWithin`, killed at ten seconds and given up on shortly after), so even a guest whose own binaries hang releases the host.
There is never more than one probe per instance.
One that did not answer in time, or answered but took more than half of it (a FIFO a loop feeds just in time would otherwise cost every task start nearly ten seconds), is not asked for ten minutes and adds no tasks to the count meanwhile.
Counting its last answer instead was tried and lost.
A count frozen for ten minutes goes stale-high and blocks starts for nothing, which is what asking the guest exists to avoid.
The undercount is the same one the timeout path accepts, and `max-instances` bounds it.
Every script the host waits on reads only regular task files (`TaskScripts.ifFile`), so a FIFO in place of a task file holds neither these probes nor an `adopt_instance`, `task_status` or `task_result` (#1035).
Checking the path before opening it is not enough, as a FIFO can be swapped in between the check and the open (#1080).
The file is opened read-write, which never waits on a FIFO, and the type of what was opened is checked (`[ -f /dev/fd/3 ]`).
The path is still checked first, since a read-write open creates a missing file.
Writes never open the file they write: `launch`, `cancel`, every exit record and `exec`'s pid file write into a file `mktemp` creates in the same directory and rename it into place, which replaces a FIFO without opening it (#1074).
Bounding these execs instead was the alternative, and it would turn a stalled read into a slow one rather than none.
`task_diff` (and `ask` on a diff) is the exception that gets a bound as well (#1105).
Its own reads go through the same helper, `base.txt` and the copy of the index included, but git then opens the repository's files, and anyone in the instance can replace one of those with a FIFO.
So the diff runs under the guest's `timeout` (five minutes, which a diff against a copied index never needs), and one that does not finish fails as `unavailable` rather than holding the call.
The orphan sweep asks whether an orphan is in use the same way, and takes no answer as in use.
What the guest reports is counted once per task id, and the refusal names the instances the count came from, so a controller can see which one to look at.
Between one session's count (up to ten seconds old by its check) and another's launch, both can take the last slot, as with `max-instances`.
Both limits are a net against runaway creation, not a budget, and a host lock held across a guest exec is not worth closing that.
The defaults (8 and 8) are sized for that net, not for one editor's task or two; the only cost of a high number is host memory.

**Ownership is stamped by the copy.**
`BranchFlow.Request.extraConfig` rides the `POST /1.0/instances` copy request (Incus lays the request's `config` over the source's) and `configureBranch`'s one write, so an agent's instance never exists without its owner and costs no extra request.
Every tool that names an instance checks the session's registry *and* the stamp read back from Incus, which refuses a user's instance recreated under a name the session once used, and one another session has since adopted.
`configureBranch` drops copied `mcp-*` keys a caller does not re-stamp, so a user's branch of an agent's instance is never mistaken for an orphan.

**A person sees what the agent sees (#1053).**
`isx list` and the TUI's instance details say which instances a session made, what for, and whether they are `held`, `orphaned` or `kept`, so a person can tell an agent's worker from their own instance before destroying or entering it.
`McpStanding` decides the state with `list_instances`' rules (`Orphans.othersOf`, kept first), and a test holds the two together.
It does not read anything beyond the listing it is given.
A process session's liveness is a local `/proc` read, and a coordinator instance's is whether that instance is in the same listing with the same grant, so `isx list` still costs one request.
The TUI shows it in the table's `OWNER` column, which appears only when some instance has an owner and the table is wide enough, and in the detail pane.
An orphan a sweep stopped as dormant (#1028) is still `orphaned` there, as in `list_instances`; the detail pane says when it was stopped and that `dormant-grace-hours` in `mcp.yaml`, not the orphan grace, comes next.
`isx list --format=plain|json` gains `mcp_state` and `mcp_purpose` at the end, and its table gains an `OWNER` column only when some instance has an owner, an `isx mcp` session or an external `--owner`.
Human output flattens control characters in these stamps: `isx mcp` refuses them in a purpose, but `incus config set` does not.

**Orphans are quarantined, not reaped.**
A name is registered before its copy starts, so a session ending mid-create still covers it.
On end of input or SIGTERM a session does not destroy any instance: it stamps `mcp-orphaned` with the time on what it holds.
After a SIGKILL nobody stamps that, so the first later session to see the dead holder stamps it then.
Either way, the grace period starts when the orphan is first known, without a per-call heartbeat write (each instance write costs Incus a backup-file rewrite).
Each new session sweeps this user's orphans and destroys those past `orphan-grace-hours` in `mcp.yaml` (default 24: long enough to outlast a night's CI wait, short enough that forgotten instances do not pile up) -- never kept ones, never ones in a pending operation, never one being adopted, never one a person is working in, and never one whose delegated agent is still running.
That check is one exec: a probe (`Presence`) reading `/proc` for a process on a pseudo-terminal (an `isx shell`, including a tmux or zmx session left detached) or a Claude Code that does not carry the `ISX_MCP_TASK` marker of a task's own processes, and `TaskScripts.unfinished()`, which asks systemd about every agent task whose current run has no exit file.
Background commands do not count: a dev server never finishes, and would keep its orphan forever.
`Presence` deliberately ignores a task's own processes, so without the second half a delegate still working when its coordinator's grace period ran out was destroyed with its unpushed work.
A coordinator that never comes back leaves such an instance until the task ends, and the next sweep after that reaps it.
A running instance the probe cannot reach, or whose systemd cannot answer, counts as in use: what cannot be looked into is left for the user.
A *stopped* one is not probed at all: nobody can be in it and no task can be running, and counting it as in use leaked every orphan a session stopped before it died -- which `stop_instance` made routine (#1013).
Its status comes from the listing the sweep already makes, so the delete re-checks under its mark that it is still stopped: one started since (an `isx shell`) was never looked into.
An instance adopted while stopped cannot have its task records read; `start_instance` reads them, so their ids work again.
"Still running" means what systemd says.
A run cut off by an instance restart left no exit file, but its unit is gone, so it reads as over -- like a finished run, whose uncommitted work the sweep does not protect either.
A coordinator that wants that work adopts the instance within the grace period.
Adoption and the sweep can meet: the sweep runs as a session starts, which is when a restarted coordinator adopts.
So each writes its own mark before reading the other's.
The sweep, holding the instance's delete lock, stamps `pending-op: deleting` and then re-reads `mcp-session` (`InstanceDestroyer.deleteHeldIf`), backing off (and taking the mark back) if it changed.
`adopt_instance` stamps `mcp-session` and then reads `pending-op`.
Incus orders the two writes, so at least one side sees the other.
A mark after the stamp does not say which way the sweep went, so adoption waits (up to 30 s) for it to go and answers from what is left: gone, taken by another session, or held -- never reported adopted while being deleted, and never left stamped as this session's without being held.
A failed re-read under the mark takes the mark back, and taking it back is strict, since a mark left behind would leave the orphan busy for good.
The `mcp-orphaned` stamp names the session it was written for (`<time> <session>`): a sweep stamping a killed holder's orphan just as another session adopts it leaves a stamp for the old holder, which never starts the new holder's grace period.

**Dormant orphans are stopped, not kept forever (#1028).**
The rules above leave one case open-ended: an orphan "in use" with nobody coming back -- a delegate hung on a dead API call, or one waiting longer than anyone will.
It cannot be destroyed (the sweep cannot tell hung from waiting, and its unpushed work is on the disk), but keeping it running holds its memory forever.
So a third outcome sits between keep and destroy.
An orphan past its grace period that the sweep would keep *only* because a delegated agent has not finished (or systemd cannot say) -- never one a person is in -- is **stopped** once nothing in it has moved for `dormant-after-hours` in `mcp.yaml` (default 24), and stamped `mcp-dormant: <time> <session>`, in the shape of `mcp-orphaned`.
"Nothing moved" needs two quiet signals, both free of new machinery: the newest mtime among delegated agents' task files (`TaskScripts.agentIdle()`, in the same probe exec, as seconds by the guest's own clock), and the CPU time in the instance's Incus state (`IncusClient.cpuUsage`, one GET, no exec) compared with the sample the previous sweep recorded on the instance (`mcp-cpu-sample: <time> <cpu-nanos> <quiet-since>`).
The CPU counts as quiet while it grows by under 1% of one CPU between two samples (`Orphans.QUIET_CPU_SHARE`), taken at least an hour apart (`MIN_SAMPLE_GAP`, or the window if shorter).
Sessions starting seconds apart would otherwise hold the probes' own CPU against an allowance of milliseconds and keep a hung delegate forever, so a sweep that close to the last sample neither compares nor records.
More starts the window again, and so does a first look, a counter that went back (a restart) or a state Incus cannot give -- so a stop always needs two sweeps at least `dormant-after-hours` apart.
A delegate polling CI writes no events but burns CPU; one hung on a dead call does neither.
The stop goes through `InstanceBackend.stopIfHeldBy`, the guard the delete uses: mark `pending-op: stopping`, re-read the holder, then stamp and stop -- stamped first, since a stopped orphan without the stamp is destroyed by the next sweep, and taken back if the stop fails and the instance still runs, so a later stop by anything else is not given the dormant clock.
A dormant orphan is then destroyed without being looked into once `dormant-grace-hours` in `mcp.yaml` (default 168) have passed since its stop.
A stopped orphan without the stamp, or with one naming an earlier holder, is destroyed past its orphan grace period as before -- the dormant clock only ever adds time.
Stop rather than freeze: a frozen instance returns no memory, the resource that matters on the appliance VM, and a frozen Claude Code loses its in-flight API call on thaw anyway, so stopping is no worse and reuses `stop_instance`'s machinery.
`adopt_instance` starts a dormant instance again (`McpSession.wakeIfDormant`, with a progress notification and an audit line) before `Tasks.adopt` reads its tasks: any instance it holds and finds stopped with an `mcp-dormant` stamp -- its own, or one a sweep wrote for the previous holder just after the adoption's stamp -- and clears the stamp only once started.
Adoption itself clears `mcp-cpu-sample` but leaves that stamp, so a repeated `adopt_instance` on an instance the session already holds still does the start.
A start that fails is `unavailable`, with the instance held, and the retry `unavailable` promises is the same call.
`start_instance` and `stop_instance` clear a stamp left that way: once the holder has started or stopped the instance itself, a later adoption must not start what an agent stopped on purpose.
The delegate's run was cut off, so it reads as over, and `send_message` resumes its conversation from the recorded `session_id`.
The sweep's stop can outlast adoption's 30 s wait for its mark (the stop alone may take Incus's 30 s graceful timeout): reported adopted then, the instance would be stopped under its new holder with nothing saying so.
So an adoption that still finds the sweep's `stopping` mark after the wait holds the instance (it is stamped as the session's) but is refused `busy`, and the repeat starts it once the stop is done.
`list_instances` adds `dormant_since` and `dormant_until` to such an orphan, which stays `state: orphaned`.
Nothing an agent can call changed: the stop is the sweep's, an instance write like its delete.

**A person in the box is a task state.**
Taking over a worker's conversation (`isx shell <instance> --task`, below) is how a person steers a design, so it must not race the coordinator's `send_message`, which runs `claude -p --resume` on the same session.
The task status script runs the same probe and reports the Claude Code that resumes the task's `session_id`, or works in its recorded `cwd` (where `--continue` or the resume picker would find the conversation), excluding processes that carry a task's `ISX_MCP_TASK` marker.
`task_status` then says `attached`, and `send_message` refuses.
The check is made when a message is sent, so a person joining in the following seconds is not seen; the alternative was every client racing its own `pgrep` before each wake.

**Joining a task is a mode of `isx shell`.**
`isx shell <instance> --task [<id>]` opens the task's conversation instead of a login shell (#1509).
It is the same path as `isx shell` (instance start, container or VM, `agentuser`), with the login shell replaced by the guard below, a `cd` to the recorded cwd, and the resume under the conversation's lock: `flock -n -o -E 213 "$D" claude --resume <session_id>`, or `codex resume <session_id>`, with no `exec`, so the shell stays to report a lock it did not get.
The agent it starts has the argv and physical `cwd` `Presence` looks for, so `attached` holds by construction while the person is in it, and `send_message` stays refused.
A test runs the built command and asserts the probe finds it, so the two cannot drift apart.
`TaskJoin` reads every task directory in one exec as agentuser (`TaskScripts.joinable()`), with no `isx mcp` session, so an orphan's task can be joined; joining changes no ownership and no TTL.
A new verb (`isx join`) was rejected: the task is a property of the instance, and `shell` is already the door a person uses.
`isx mcp join` was rejected too: `isx mcp` is the stdio server an agent runs, and mixing a person's interactive action into it blurs the two audiences.
With several resumable tasks and no id, it prints a table, each row with its ready command, and exits 1: guessing the newest would sometimes land in the wrong conversation.
A task mid-turn, or one systemd cannot say is over, is refused with the last things it did; waiting for the turn to end would race the owner's next `send_message`.
A conversation a person is already in is refused too: the same read runs the `Presence` probe.
That read is a snapshot seconds before the agent starts, so the guest command decides again in the shell that then execs the agent (`TaskScripts.joinGuard`).
It refuses, and exits rather than falling back to a login shell, while any unit of the task is active, systemd cannot say, or an agent isx did not start holds the session or the directory.
Every unit is asked about, whatever `current` says, because `current` is written only after `systemd-run` has started a run.
A refusal waits for Enter on a terminal, so the TUI does not redraw over it.
The host cannot see the shell's exit status (the PTY session ends without waiting for Incus to finalize the exec, about 8 s), so the guard leaves its reason in the task directory under a nonce the host chose (`TaskScripts.joinRefusal`), and `isx shell --task` reads it after the session and exits 1.
These checks are snapshots: on the VM, a `send_message` started 0-0.2 s after a join ran its turn beside the person's agent in 4 of 8 trials, because its presence check comes 170-250 ms before `systemd-run`.
So the conversation has a lock: every run and every joined agent starts under `flock -n -o` on the task directory (`TaskScripts.CONVERSATION_LOCK`), and the second of two that coincide does not start.
A run that finds a person there exits 213 (a value no agent gives, unlike 75) with a line saying why, which `task_status` shows as its stderr; a join that finds the lock taken is refused like any other.
A guest without flock (util-linux) holds no lock, so its runs and joins refuse with that reason rather than run unlocked.
`-o` keeps the lock with flock itself while the agent runs, not with what the agent starts, which could outlive it and hold the conversation after it.
The lock is on the directory, which cannot be swapped for a FIFO that would hold the open (#1080).
A person who resumes by hand (`claude --resume` in an `isx shell`) takes no lock; for them the `Presence` check at `send_message` is all there is, as before.
The read is bounded (10 s, `IncusClient.execProbe`), so a guest that never answers cannot freeze the TUI at the keypress.
The joined command runs outside the instance's tmux or zmx session (`ShellPrep.withCommandOnly`), since attaching to one that already exists would enter it instead.
Plain `isx shell` on an instance with the `isx mcp` stamp prints one hint line with the command, at the cost of one guest exec; other instances pay nothing.
`task_status.join_command` holds the same host command, so a coordinator relays it verbatim.
The TUI's Shift+F2 does the same, reading the tasks on the keypress rather than while rendering, with a picker for several.
Guest text (the instruction's first line, an agent's recent activity) is printed through `OutputFormat.oneLine`.

**Results stay small.**
The coordinator's context is the scarce resource.
`task_result` returns the report capped (`max_bytes`, default 16 KB) and the event tail only on request.
`get_diff(stat)` is `git diff --numstat` plus `--shortstat` per repository, small and exact, which is what a coordinator needs to keep new dispatches from overlapping work in flight.
`ask` on `exec`, `task_result` and `get_diff` goes further.
The text is produced in the instance and piped, there, into `claude -p --model <summary-model> --tools '' --no-session-persistence` in an empty directory with the question, and only the answer (plus what was read: lines and bytes) comes back.
A fresh one-shot rather than the worker's session, which would spend the worker's context and inherit its framing; no tools, because the text is untrusted and may carry instructions; the default model is the alias `haiku`, so a template whose Claude Code maps models elsewhere (Vertex, Bedrock) is followed.
The answer is as untrusted as any output and lossy besides, so the tool descriptions say never to gate a merge on it.

**Fewer calls per tick.**
`wait_any` blocks until any of several tasks finishes, probing each instance once per two seconds, instead of the coordinator polling N tasks in turn.
`delegate(skill, args)` runs `/<skill> <args>` so that the brief lives in the instance, versioned with the template, and the coordinator sends a name.
The delegate's permission mode is always passed explicitly (`--permission-mode bypassPermissions`, as isx's managed settings already set).
A headless agent that meets a permission prompt has nobody to answer it.
The refusals it does meet are surfaced from the result event's `permission_denials` in `task_status` and `task_result` instead of failing silently.

**Activity from the proxy (#898).**
"Working", "stuck" and "finished but never reported" look alike from outside: a task is `running` in all three.
Asking the instance costs an exec and reads what the agent says about itself.
The proxy already sees every model call and knows which instance made it, so `instance_activity` asks the proxy instead -- VISION.md's "the proxy is the control plane" applied to its first consumer.
`MitmProxy` counts, per instance, the Messages API creates (`/v1/messages`, and Vertex `:rawPredict`/`:streamRawPredict`; not `count_tokens`, batches, settings or telemetry, which an idle Claude Code keeps sending): requests made and in flight, when the last one started and ended, and the tokens their 2xx responses report in `usage`.
The usage is read off the relayed stream (`ApiActivity.UsageTap`, one SSE line held at a time, a plain JSON body up to 4 MB).
A stream reports it in `message_start` and, cumulatively, in `message_delta`, so each field keeps its largest value rather than summing.
Those calls are sent upstream with `Accept-Encoding: identity`, since a compressed answer would hide its usage; a response compressed anyway is relayed and not counted.
A call ends once, on the client response's end or close, so a failed or abandoned call never stays in flight.
The proxy serves the counts as `/activity` on the health port, to host callers only (`isHostCaller`, as `/health`'s pid), a container getting a 404: what its neighbours do is not its business.
Counts live in memory, so every way they can start over must be visible to a client subtracting two reads.
`counting_since` is therefore per instance -- when the proxy created that instance's counters, at the first read or call after it learned of the instance -- not the proxy's start.
A read drops the counters of an instance the registry no longer lists, so those of destroyed instances do not pile up, and the registry can also miss a live one for a moment (an address two instances claim, a listing entry that failed to parse).
A proxy-wide `counting_since` would let a dropped instance's counts start again from zero under an unchanged stamp, and a before/after subtraction would read a wrong or negative spend (found in review of #1052).
With it per instance, counters made again carry a later stamp.
Since only idle counters are dropped -- atomically per key with `begin`, so a call never lands on counters the map no longer holds -- `requests_in_flight` is never undercounted.
A task's spend is the difference of two reads with the same `counting_since`, and nothing else: a read creates counters for every instance the registry knows, so a known instance always carries one.
An earlier rule that also let a first read without one count as zero was unsafe -- counters created after it could be dropped and made again before the second read, and the subtraction would undercount without a sign.
Dropping on a read is also not enough on its own: a hand-named instance destroyed and branched again before anything reads `/activity` would carry on from the old one's counts under its stamp (#1063).
So counters also belong to one incarnation of a name, the instance's Incus `created_at`, which the registry already reads in its listing and keeps beside the address (not in `InstanceAccounts`, which the proxy caches by value).
A call or read naming another incarnation starts fresh counters (and `requests_in_flight` counts that incarnation's calls only).
A call's incarnation is read in the same registry lookup that names its instance (`InstanceRegistry.resolve`, carried in `RequestContext`), so a call is never paired with another instance's `created_at`.
A call and an `/activity` read can still see different snapshots, so each listing the registry parses gets a higher *view*, and counters never go back to an earlier view.
A call identified before the name changed hands is counted nowhere, and a read from an older listing neither replaces nor prunes counters a newer one made, or the two would reset them in turn.
Views, not `created_at`, order them: a rename keeps the instance's own `created_at`, so a name can pass to an instance created before the one that had it.
Per-task baselines kept by the session were rejected: they would not survive the adoption that #898 exists for, and the subtraction is the client's to do.
The contract can grow additively (a `task_id`, other domains) without changing what is there.

**All traffic, by domain (#935).**
Model calls say whether an agent is thinking; a build pulling dependencies, a clone or an open WebSocket is work too, and a coordinator telling working from stuck needs to see it.
So `/activity` also counts every request and WebSocket through the MITM listener, model calls included, on the same per-incarnation counters (and so under the same `counting_since`, pruning and views): requests, in flight, request and response body bytes, and when one last started or ended, in all and per domain.
What comes from no known instance -- the host itself, or an address the registry has not listed -- is counted as `unattributed`, from the proxy's start.
The domain is the intercepted one a request is relayed for: a subdomain reached through dnsmasq's wildcard counts under its closest intercepted parent, and any other name under `(other)`, since the Host header is the guest's to write and a key per made-up name would grow without bound.
A request ends on its response's `bodyEndHandler` or its connection's `closeHandler`, the two handlers nothing else on the path sets (the relay, the cache and the model-call count each set the response's single end and close handlers); an HTTP/1.1 connection answers one request at a time, so its close ends the one in progress.
A model call ends with its request's flow, on the same reading of the clock (`Flow.carries`, #1281): Vert.x runs the body-end and end handlers one after the other, and two readings could put the call's `last_response_at` after the `last_activity_at` of its own domain.
Its own end and close handlers stay as the backstop.
A WebSocket ends on its close and adds its frames' bytes as they pass.
An open stream is in flight for as long as it is open, whatever passes on it -- a WebSocket, or the MCP stream an `--mcp-client` instance holds to `mcp.isx.internal` -- so a reader telling working from idle by `in_flight` looks at the domains, which is why they are kept apart.
Bytes and the last activity move when a request starts and ends, not per chunk: an open stream shows as in flight, which is what stuck detection reads, and the event loop takes one short lock per request rather than one per chunk.
The fields are added, never renamed: `isx mcp`'s `instance_activity` gains `traffic_*` fields beside the model-call ones, and `isx proxy activity` prints both, one record per instance and the unattributed one last (`--by-domain` for one per instance and domain).
The health server answers only `GET` and `HEAD` (405 for anything else) and `/activity` only to the host, whatever the method: it is the one listener every container reaches, so it never gets a write endpoint (next section).

**Structured results for programs (#1010).**
A daemon driving `isx mcp` is a client that is not a model, and it should not have to parse a sentence to find a task id or a state: the wording of those sentences changes in nearly every follow-up.
So every tool declares an `outputSchema` in `tools/list` and returns a matching `structuredContent` object (MCP 2025-06-18), while the text the model reads stays as it was.
The five tools that already answered in JSON print the structure itself, followed by guidance that is not part of it.
Field names are shared across tools (`instance`, `template`, `task_id`, `state`, `exit_code`, `run`, matching the `isx/task_changed` notification).
One word means one thing everywhere: a task's `state` is always from `OutputSchemas.TASK_STATES` (the notification adds `released` and never says `unknown`; `McpOutputSchemaTest` pins both), including a task entry in `list_instances`, which gives the state as the session last saw it, after asking each instance with a task believed running which have finished (one exec per instance, #1278) -- `unknown` when nothing read how it ended, as after a `cancel` (a boolean `running` there would have been frozen at release: once the session knew more, saying it would take a second field for the same fact), a no-op lifecycle call says `already: true` (`stop`, `start`, `destroy`), and a field isx cannot fill truthfully is left out rather than given a placeholder.
Every schema object is closed, and the schemas live in `OutputSchemas`, built only when `isx mcp` lists its tools.
They are a contract, so `McpOutputSchemaTest` keeps a golden copy (a change is a deliberate diff), compiles each one against the 2020-12 metaschema with networknt's validator -- the one the official MCP Java SDK uses -- and refuses a `$schema` naming another dialect, since clients dispatch on it.
`StructuredResults` validates every result the tool tests produce, and one test drives every tool.
A refusal carries **no** `structuredContent` at all.
Its `{code, message, instance?, task_id?}` goes in the result's `_meta` under `dev.incusspawn/error`, with `code` from a small `ToolError.Code` set (`invalid_argument`, `not_approved`, `not_found`, `not_held`, `wrong_state`, `busy`, `limit`, `unavailable`, `task_failed`, `refused`, `internal`), which every throw site names -- there is no default, so a new refusal cannot go unclassified -- and an `IncusException` escaping a tool is `unavailable`, not `internal`.
`busy` also answers a call repeating one with the same `idempotency_key` that is still under way, `not_held` one whose instance another live session holds or is still making, and `invalid_argument` a key repeated for something other than what it made (#1011).
`unavailable` also answers an `adopt_instance` that took a dormant instance but could not start it again, and `busy` one that took an instance the orphan sweep is still stopping (#1028).
The instance is held then, either way, and the retry is the same call, which starts it.
`unavailable` and `busy` are the codes that may succeed on a retry as is (not every `unavailable` does: an instance that cannot do what was asked fails the same way again).
A `create_instance` refused by `BranchFlow.preflight` (a missing credential, a template built with a different CA, the name in use, the proxy down) is `refused`, since the user has to act first, as is an `instance_activity` asked of a proxy older than `/activity` (`ProxyActivity.Outdated`), which answers the same until restarted.
Do not move it into `structuredContent`.
The 1.x TypeScript SDKs validate `structuredContent` against the tool's output schema even on an error result (2.x and the Python SDK do not), and Claude Code ships both generations, so an error object there would turn every refusal into a client-side failure.
`get_diff` reads `git diff --numstat -z --no-renames`, so no file name can forge a record and a rename reports both paths it touched.
Its text is rendered from the parsed structure, with a path holding a control character C-quoted as git quotes it (the structure keeps it verbatim), and `ask` hands the in-guest model git's own quoted, line-based `--numstat`, so no name forges a line in either.
`task_status` adds when the run started and last wrote output, from the mtimes of its `current` and output files in the same exec.

**Told, for a client that asks (#1014).**
A daemon driving `isx mcp` would rather be told than hold a `wait_any`: `notifications/isx/task_changed` (`{task_id, instance, run, state, exit_code?}`, state `running`, `finished`, `lost`, `attached` or `released`) is sent on every transition.
Server and client both list `isx/task_changed` under `capabilities.experimental` to switch it on; it starts only once the `initialize` response is out.
A client that does not ask -- Claude Code -- gets no notification and costs nothing.
The server only learns task states when asked, so notifying means polling, and an always-on poller would spend an exec per instance every two seconds on messages that are dropped.
`TaskWatcher` keeps the last state it reported per task and the paths that learn one anyway feed it (launch, `task_status`, forgetting an instance), so a transition is reported once whichever path saw it first.
The poller fills in the rest, including a task never reported (adopted) or last reported running (cancelled since): an extra exec in adoption would only say it two seconds sooner.
A cancel in progress must not read as a loss.
It stops the unit, sweeps the task's processes for two seconds and only then records exit 143, and a unit that is inactive with no exit file is otherwise `lost`.
So `cancel` stamps `cancelling-<n>` before the stop.
Every state read calls the run `running` while that stamp is under five minutes old -- still true, its processes are being killed, and the stop alone may wait systemd's 90 s -- so a poll landing in the sweep does not report a change and the client hears one `finished 143`.
A stamp older than that (or from the future) is a cancel that died midway, and the run is lost after all -- once the exit file has been looked at again, since a cancel records its exit before removing its stamp and may finish between a reader's two checks.
Every state read means `status()` too: it now prints the guest's own `RUN_STATE` rather than the raw unit state for Java to interpret, so the rules are written once.
A cancel is also the one path that reports at once, and costs nothing: the watch script rides the cancel's own exec, because `stop_instance(force)` stops the instance straight after and a stopped instance cannot be asked.
An instance the poller cannot exec into (stopped behind the session's back, unreachable) is left until the next slow round rather than costing a failed exec every two seconds with `TaskScripts.watch()` (one exec per instance, re-reading the exit file after asking systemd so a run that just ended is never called lost, and one `Presence` scan only when a finished agent is among the tasks): tasks last reported running every two seconds, as `wait_any` does; every fifth round, finished agents (a person may join or leave) and whether the session still holds each instance, so a set of idle tasks does not become a steady stream of execs and Incus reads.
`unknown` is never reported, as it never changes a recorded state.
A task whose instance is gone is `lost` if it was running (a finished task lost nothing).
One whose instance another session adopted is `released`, whatever its state, because its id no longer works here and calling it lost would claim the run died.
Which of the two is not asked of Incus when the session has already let go.
Every place that drops an instance knows why (`destroy_instance` and a 404 mean gone, another session's stamp means adopted) and the session remembers it, since by the time a poll round asks, an instance destroyed a moment earlier would read as merely not held.
Either is the last word for the task: the watcher tombstones its id, so a `task_status` or poll that read the task just before and reports just after cannot tell the client the session still has it.

**The agent gets `isx branch`, not options.**
Instances are created through `BranchFlow` with `Request.defaults()`: the template's network mode, account pins, KVM and resource defaults, no GUI, no inbox.
Template approval lives in `~/.config/incus-spawn/mcp.yaml`, read on every call; a template must be listed, not built from a project-local definition (`BuildSource.usedProjectLocal()`), and built.
Definitions come from `ImageDef.loadTrusted()`, which leaves out the working directory's `.incus-spawn/`.
`isx mcp` runs in whatever repository the agent was started in, and a definition there must not reach an agent's instance even by overriding a parent of an approved template (which would decide, among other things, which credential accounts it gets).
Nothing in the package can write config or definitions; `McpNoWritePathTest` pins that at the source level, so widening it means deleting a line of that test.

**The policy has a file of its own (#1331).**
Every MCP setting lives at the top level of `mcp.yaml` in the host user's configuration directory, with the names, meanings and defaults the `mcp:` section of `config.yaml` had.
That section is no longer read at all: it stays in `config.yaml` as any unknown key does, so a save does not delete what the user wrote, and `isx mcp` and `isx init` say to move it.
Its presence alone, with no `mcp.yaml` yet, makes the policy `Invalid`: the defaults would replace what the user wrote there (a longer orphan grace, fewer instances), so the first session after an upgrade would sweep orphans early and release a coordinator's instances, and `isx init` saving approvals would make the wider policy permanent.
Nothing is taken from the section; it only stops isx guessing.
An empty section (`mcp:` or `mcp: {}`) set nothing the defaults would widen, so it does not count.
A `config.yaml` that does not parse counts the same, since `SpawnConfig.load()` would read it as empty and hide the section.
A coordinator session whose resume meets an invalid policy does not hold any instance until it reconnects, and says so on stderr.
MCP was experimental, so this is a clean break: no fallback, no merging and no migration, because a policy assembled from two files is one nobody can review in one place.
`McpConfig.load()` reads the file on every call, as before.
A missing file or an empty document does not approve any template.
One that cannot be read, does not parse, or holds a key or value isx does not know throws `McpConfig.Invalid`, naming the file; `isx mcp` turns that into a `refused` error for the call that needed the policy, never an earlier policy and never defaults.
Unknown keys are refused rather than ignored because ignoring them drops a restriction in silence: a mistyped `max-instance: 2`, or the old section pasted with its `mcp:` line, would otherwise leave the defaults in force.
Retired keys (`McpConfig.RETIRED`) are the one exception: they restricted nothing that still exists, so they load and are named instead.
Values that were only checked on use (the summary model) are checked at load for the same reason, so a bad one stops every call rather than only the one that reads it.
`isx init`'s step is the only writer, run by `isx mcp setup` too: it writes the file only when an approval changes, owner-only into a temporary file renamed over it (over a symlink's target, so a dotfiles link stays a link) (a session reading meanwhile sees one policy or the other, never a truncated file), and leaves an invalid one alone.
`isx mcp setup` runs that step alone (#2077), so an agent-driven install (#2072) can end by handing the person this one step instead of all of `isx init`.
It needs stdin and stdout to be a terminal (`Prompts.terminal()`), stricter than init's console, so an agent cannot pipe its own answers in, and it takes no option naming a template.
An agent with a shell can still do what its user can; the command only means the install path never asks an agent to approve anything.
It is a subcommand of `isx mcp` because it sets up exactly what that server serves; bare `isx mcp`, which agents run, is unchanged.
The approvals do not depend on Claude Code (#2078): a person approves templates whichever client they use, and only Claude Code is registered by isx, through its own CLI.
Any other client is set up from `isx mcp config`, which prints the stdio entry as the `mcpServers` JSON most clients take, with the resolved `isx` path.
It prints rather than writes because another program's settings file is its own: isx does not know its format's other keys, and an entry it wrote would be one it must also find and undo.
Codex and Gemini CLI have their own `mcp add` commands, which the docs name; isx does not run them, so registering stays a step the person does or confirms.
`host-routes:` stays in `config.yaml`: it is proxy routing that a controller without MCP may use, and its template list answers who may reach a service, not which templates an agent may create instances from, so neither list is derived from the other.
The split organizes the configuration; it is not a new security boundary, since both files belong to the same host user.

**Forks: prepare once, try N things (#1013).**
A review that builds a PR once and then runs several specialist reviewers wants each in an identical, warm environment -- the built checkout, the primed `~/.m2`, the compiled `target/` -- without rebuilding per specialist or sharing one `target/` and one failure domain.
`create_instance(from_instance)` is `isx branch --from <instance>` through the same `BranchFlow`, which already resolves a branch of a branch: the leaf template from `PROFILE`, the source's account pins on top of the template's, credentials checked against the source's recorded build.
So the trust boundary does not move: the source must be held by this session, its lineage must still be approved and trusted (`TemplatePolicy.requireLineage`, which, like adoption, does not need the template built: a fork copies the instance, not the image), and network mode and accounts are still never arguments.
The source must be **stopped**.
A running instance can be copied, but only crash-consistently (a half-written `target/`, a live lock in `.m2`).
So `stop_instance` and `start_instance` exist, and `stop_instance` is refused while a task runs unless `force`, which cancels the tasks first so none stays recorded as running in an instance nobody can ask.
Having the fork stop and restart the source by itself would pay a restart per fork and hide a state change from the client.
A fork is stamped, counted against `max-instances`, orphaned and reaped like any instance; `configureBranch` drops the `mcp-*` keys it copied, so a fork of a kept instance is not kept.
Two things a copy carries had to be undone: the guest's `~/.isx-mcp/tasks/`, which would let adopting the fork claim the source's task ids, is cleared with one exec after the start (a fork whose tasks cannot be cleared is removed); and the template of an MCP instance is read from `PROFILE`, not `PARENT`, which for a fork names the source instance and made forks impossible to adopt.

**Idempotency keys: repeating a call is safe by construction (#1011).**
A controller recovers from a crash by re-running every intent with no recorded result.
So it needs "did my `create_instance` happen before I died?" answered by the thing it drives, not by a lookup by `purpose` whose race and convention it owns.
`create_instance`, `delegate` and `exec(background)` take an optional `idempotency_key`.
A call whose key already made something gets it back -- the original result shape, rebuilt from what Incus and the task's files hold, plus `replayed: true` -- instead of a second one.
- *The key lives exactly as long as what it made.*
  On the instance it is `user.incus-spawn.mcp-idempotency-key`, stamped by the copy request with the other ownership stamps: host-side config the guest cannot change, and no request of its own.
  On a task it is `~/.isx-mcp/tasks/<id>/key`, which `TaskScripts.list()` reads back, so it survives a server restart, a session replacement and an adoption.
  A host-side ledger would also remember keys past a destroy.
  But the only thing it could add is "that key made an instance that is now gone", to which a controller's right answer is to create again -- which a forgotten key does.
  It would be host state that `McpNoWritePathTest` forbids and that would need reconciling with Incus.
  Every destroy is deliberate (the controller, a person, or the orphan sweep, which already defines an abandoned instance as gone with all it held).
- *Scope.*
  An instance key is matched among the host user's instances (`mcp-owner`).
  A task key is matched only among the session's tasks, and a guest-written key only answers for a call naming its own instance.
  The call that starts a task names the instance anyway, and a user-wide match would let one instance's agent plant a key and capture a keyed `delegate` meant for another.
  `delegate(template)` gets user-wide scope through the instance stamp.
  A replay is decided before the call is compared with what the key made (`McpSession.replayable`): one still being made -- this session's create under way (`busy`), or a live session's (`not_held`, pointing at `adopt_instance`) -- cannot be compared yet, because a copy carries its source's config, its parent included, until it is configured, and a mismatch's advice to use a new key would make a second instance.
  One this session holds is returned as it is.
  An orphan, or one its holder released, is adopted with all of `adopt()`'s checks, tasks included (a running instance whose tasks cannot be read is let go of again and the call refused as `unavailable`: a repeated `delegate` would not find its task and would start a second agent).
  A kept one is refused.
- *The same key asking for something else is refused, not redirected:* another template or source, another instance, a command where an agent was started.
  `name_hint` and `purpose` only describe, so a retry that rewords them still replays -- the reason not to hash every parameter, Stripe-style.
- *Two calls in one session* cannot both make one: the key is registered with the reservation, under its lock, and a second call while the first is under way is `busy` ("call again"), never a duplicate.
- *Two sessions* can: a crashed session's copy that Incus finishes after a new session looked, or two live ones racing.
  A keyed create therefore looks again once its copy exists, and of two instances under one key the one Incus recorded first stays; the other destroys its copy and replays it.
  The order is Incus's `created_at`, then the name, the same comparison everywhere one is chosen (`McpSession.FIRST_MADE`).
  Measured against Incus 6.23: a copy is listed, with the config its request stamped, from the moment its record exists -- while its files are still being copied -- and `created_at` is set then and never changes.
  So if only one of two racing creates sees the other, it was made after the other looked, it is the later of the two, and it gives way: exactly one of them destroys its copy.
  `user.incus-spawn.created` cannot serve: a copy carries its source's until `configureBranch` writes its own, and that value is taken before the write that a reader would see.
  A copy whose session died before `configureBranch` (no `static-ip`, which a copy never carries from its source -- the copy request unsets it -- and which only `configureBranch` gives; never started) is not what a create promises.
  So it never answers for its key, and the orphan sweep removes it.
- *Partial intent.*
  `delegate(template)` whose keyed instance exists without its task (the call was cut off between the two) starts the task there, finishing the intent instead of making a second box.
- *Forks.*
  A copy brings its source's `user.*` config, the key included, and `configureBranch` drops the `mcp-*` keys it did not stamp only once the copy is already listed.
  So `BranchFlow.create` applies the same rule in the copy request itself: every `mcp-*` key of the source the caller does not stamp gets an empty value, which Incus takes as removing it.
  That covers every ownership key, not just this one, at no extra request.

**Exec has no server-imposed time limit.**
How long a build may run is the controlling agent's call: `timeout_seconds` is optional with no default.
What matters is that a command nobody waits for does not keep running: `exec` runs the command in its own session (`setsid`) and records the session id, and a cancelled call kills that session's process tree from a second exec.
Output is bounded to the tail (`TailBuffer`), which is about the size of a tool result, not about how long a command runs.

**Tasks run as systemd units in the guest.**
`exec(background)` and `delegate` start a transient unit (`sudo -n systemd-run`, each run `isx-task-<id>-<n>`) writing into `~/.isx-mcp/tasks/<id>/`.
They outlive the call, the connection and the session's process; only destroying the instance ends them, and a person can inspect one with `isx shell`.
The files and the unit are the task's state: nothing about a task is kept on the host beyond which session owns it.
Instructions and prompts arrive on stdin and run scripts as base64, so nothing an agent sends is interpolated into a shell.
The unit's cgroup is not where the run ends up: `su -` goes through PAM, which moves it into a user session scope, so stopping the unit left a delegated `claude` running (seen on a real instance).
Every run therefore exports `ISX_MCP_TASK=<id>`, inherited by all it starts, and `cancel_task` signals every process carrying it after stopping the unit.
Unit state is read through `sudo` too: an unprivileged login session in a container cannot reach systemd's system bus, and read directly every running task looked lost.

**Delegation.**
The inner agent is `claude -p --output-format stream-json`, resumed with `--resume <session_id>` for each `send_message`.
The ID is saved only from the `init` event, and only once Claude Code has written that session's transcript (#1866).
A cancelled run used to leave none, since the cancel kills the run script before it reads the events, and the next run then started `claude --resume ""`.
Claude Code fails that at once, with exit 1, no turns and a made-up session ID in its result, which was saved, so every later message failed the same way.
Now `cancel` saves the session itself, a refused resume never replaces the saved ID, and a task without one is not `resumable`: `send_message` refuses it rather than launch a run that can only fail.
A saved session can still go later: Claude Code deletes transcripts older than `cleanupPeriodDays` (30 days by default), and resuming one fails the same way (#1902).
So the status script, the run script and the join guard look for the transcript too, as Pi's resume looks for its session file.
The status script's check is one `ls` in an exec `task_status` and `send_message` make anyway, so refusing costs no extra request.
The appended brief frames the role -- a delegate reporting to a coordinator -- and does not forbid anything: what a delegate can reach is bounded by the credentials the template's proxy account carries, what it does by the instruction.
That is what makes "fix it, don't push" -> review `get_diff` -> "push and open the PR" a controlled flow rather than a prompt-level honour system.
`get_diff` compares against the commit each repository was at when the task started, using a throwaway index so untracked files are included and the instance's own index is untouched.
One agent per instance, because two would clash in one working tree; `delegate(template=...)` gives parallelism instead.

**Per-task profiles (#1012).**
A coordinator spends tokens per kind of work -- a small model for a rebase, a large one for a design -- so `delegate` and `send_message` take `model`, passed as `--model`.
Without it nothing changes: no `--model`, so the template's `settings.json` model applies (`list_templates` shows it as `delegate_model`: the `model` parameter of the nearest layer listing the `claude` tool, which the build reconfigures with that layer's parameters alone).
The alternative, a template per model, made every profile a build.
A turn budget (`max_turns`, capped by `delegate-max-turns`) was part of it until #1857 removed it with the other delegate policies.
Two bounds:
- *The model is the account's.*
  A model the template's credential cannot use must fail the call, not the task, which would cost a branch and a poll to discover.
  The check (`ModelCheck`) is a one-turn, tool-less `claude -p --model <m>` in the instance, through its proxy account: the same path the task takes, so it answers alike for a model id, an alias (`haiku`, `opus[1m]`) and a Vertex account's naming.
  A host-side list or the Models API would cover neither aliases nor Vertex.
  It costs one tiny request per (template, account, model) per session -- the account being the one the instance's pin, or the configured default, resolves to at the call, so a default changed meanwhile is checked again; a success is remembered, a refusal is not, since the user may fix the account -- and nothing when no model is chosen.
  It runs once the task's slot is reserved (a refusal gives the slot back), so a call refused anyway -- a busy task, a full session -- spends no request, and under `timeout`, since Claude Code retries an overloaded API for minutes while the call waits.
  A fresh instance's pin is the one its branch stamped (`CreatedInstance.accounts`), not read back from Incus.
  On `delegate(template=...)` the fresh instance is destroyed with the refusal, as for any failed start.
  The cache is per template, not per instance.
  Instances of one template share its Claude Code, except one branched before the template was rebuilt with another version, which could resolve an alias differently.
  A pass on the newer one then spares the older one its check, and a model it cannot use fails in its task.
  Keying on the instance would re-check every fresh `delegate(template=...)` instead, the case the cache exists for.
  A chosen model stays chosen for the task's later turns: Claude Code has no flag meaning "the template's own", so going back to it is naming it or a new task.
- *The permission mode is not part of it* (#858): what a delegate may do stays the template's.

The model passes `McpConfig.isModelName()` (also what `summary-model` must match; it starts with a letter or digit, so it cannot read as an option) and is shell-quoted.
A task's profile is recorded with the task, in `~/.isx-mcp/tasks/<id>/model`, where all its state lives, and `TaskScripts.list()` reports it, so an adopting session keeps it.
Read back from the instance, it is taken only if it is still a model name.
Once adopted, the recorded profile is advisory: the delegate could have rewritten those files, so a later turn that names no model may run on one the coordinator did not choose, shown by `task_status` as the task's.
A turn that names one is always checked, even when it names the recorded one (the account may have changed too); the cache makes the repeat free.
That is no escalation: the guest can run `claude --model` itself through the same account.

**A coordinator in a box (#915).**
The coordinating agent runs untrusted text all day -- reports and triage lines come out of the boxes it drives -- and on the host it acts with the user's shell and the user's `gh` login.
So it may run in an isx instance instead, holding only that instance's credentials (a bot's), with the MCP tool list as its only reach into the host.
The pieces, and why each is the way it is:

- *Who may call: a stamp the user sets.*
  `isx branch --mcp-client` stamps `user.incus-spawn.mcp-caller` on the branch it makes (`mcp-client` was the name proposed, but that key already holds the client's display name).
  The TUI's branch dialog has the same grant as its MCP control box (#1811), not offered on an Airgapped network: a person at the TUI is the one who would type the flag, so it needs no gate of its own.
  Whether a branch coordinates is a property of its kind, so a trusted template can make it the default (`coordinator: {control-over: [...]}`, #1812), beside `gui:` and under the same rules: never from a project-local definition or a source built with one (#765), never baked into the image (it is not fingerprinted, and builds clear the grant), and an airgapped branch goes without it with a warning rather than a refusal.
  `--no-mcp-client`, or unticking the box, which starts from that default, opts out.
  Unlike GUI, the dialog always sends the box's value rather than leaving an untouched box to the default: a capability grant is what the person saw, not what the source says at Enter, which a rebuild meanwhile may have changed.
  So the dialog gives the project-local note in the box's row, and `create` gives it only for a choice left to the default: an explicit no, `--no-mcp-client` or `isx mcp`'s own request, needs no reason.
  Which tool launches a branch does not change it (Sanne on #1869): `isx branch --owner orca`, Orca's recipe, gets the template's default like any branch, and only the person's explicit choice overrides it, which for Orca is the template the recipe names.
  The block is named for the kind, `coordinator:`, not the protocol, and Sanne chose its shape on #1869: `isx-control-capability` is the switch and defaults to true exactly when `control-over` lists a template, so a template that says what its coordinators control needs one key, and an explicit `false` beside a list is warned of at load as the likely mistake it is.
  The nearest layer whose `coordinator:` says either decides alone, so a child never pairs its parent's list with its own switch.
  The default is read only for a built template (`type` base or project) whose lineage is trusted, never for an instance that merely shares a definition's name.
  `BranchFlow.Request` carries the choice, null for the default, and `preflight` mints a fresh grant per branch into the request's `extraConfig`, replacing or stripping any a caller put there, so both front ends get the stamp and the check below from one place and cannot drift.
  `Request.defaults`, the MCP path, says no, so a template's default never lets a coordinator make another.
  `BranchFlow.preflight` refuses the stamp on an airgapped branch (`mcpClientUnreachable`) before any Incus request, so no caller can make a coordinator that cannot reach its endpoint.
  It is an `mcp-` key, so `configureBranch` drops it from every copy: `isx branch --from coord` makes an ordinary instance, and so does an agent's fork.
  The MCP create path refuses it outright (`IncusInstanceBackend.branch`), so a coordinator cannot make another one.
  Like account pins it is the user's act, never an agent's: a guest can read its own `user.*` keys, never write them.
- *How a caller is known: address and secret.*
  The proxy already identifies instances by source address (`security.ipv4_filtering` makes that trustworthy), and #934 added the per-start secret for exactly this kind of host-side service.
  The proxy (`InstanceRegistry.identifyWithLineage()` and its `mcpCaller()`) admits a caller only when the address, the secret in `X-Isx-Instance-Secret` and the stamp all name one instance, read from the listing the proxy makes anyway.
  A refusal is decided before the body is read, and a refusal right after a start is worth one throttled refresh, as for `identify()`.
  A bearer token issued to the box (#859's sketch) was rejected: it is a secret the box would hold for good, where the start secret is useless from any other address and dies with the start.
- *Where it is served: by the proxy, as a name.*
  `mcp.isx.internal` is a built-in intercepted domain, so bridge DNS sends it to the gateway, the 443 redirect to the proxy, and it works in proxy-only mode, whose rules allow only the gateway.
  No new port, and no new service to install on either platform.
  `.internal` is reserved for private use, so no real host answers to it; the proxy answers it itself and never relays it.
- *What serves it: a host `isx mcp` per instance.*
  The MCP server lives in the CLI, which the proxy (a separate binary, the only one with Vert.x) cannot run in process; moving it would move most of `cli` into `common`.
  So `McpBridge` speaks MCP's Streamable HTTP and runs `isx mcp --caller-instance <name>` per session, bridging each POSTed message to a line on its stdin and each line back to the POST that asked, by JSON-RPC id.
  Anything else (notifications) goes to the GET stream.
  The `isx` is the one beside `isx-proxy`, else `~/.local/bin/isx`, else PATH.
  The child re-checks the stamp before serving.
  A request is answered as an event stream with a comment every 30 s, below the MITM server's 120 s idle timeout, because `exec` has no time limit.
- *One process per instance.*
  A new `initialize` from an instance ends its previous process first (SIGTERM, then SIGKILL after 10 s) and only then starts the next.
  Two processes serving one instance session would each count only the instances in their own registry against `max-instances`: a coordinator could multiply its cap by opening connections.
- *The session is the instance -- this one, not its name.*
  `mcp-caller` holds a random grant id (`Metadata.newMcpCallerGrant()`, 128 bits), and `SessionId.ofInstance()` stamps `instance:<name>:<grant>` as the holder.
  The name alone would let a coordinator destroyed and recreated under the same name take over its predecessor's workers: hold them again, stop their orphan clocks and count them against its cap.
  Incus's own `volatile.uuid` would do as well but is not among the `user.*` keys every read here already carries; the grant costs nothing.
  It is not a secret (the guest can read it), since who calls is settled by address and per-start secret.
  The session is alive while the instance exists and carries that grant (`CallerLiveness`: one read per instance, remembered 5 s; a failed read counts as alive, so it never orphans anything) -- running or stopped, because a coordinator box being restarted has not let go of its workers.
  So nothing is released when a connection ends (`release()` does nothing for it), and a new connection takes back what the instance holds (`McpSession.resume()`, one listing before the first tool call so the cap counts them, then the running ones' tasks in the background) -- only what `adopt` would take.
  A take-back that fails (`mcp.yaml` refused, Incus unreachable, `isx init` not yet run) is tried again before every tool call until one succeeds (`McpMain.takeBack`, #1514), never left to a reconnect, and the call fails with its error meanwhile: the stamp says the session holds them, so no other session lists them as orphans, and a call run without them would neither list them nor count them against the cap.
  An instance whose template is no longer approved is released instead, its `mcp-orphaned` stamp naming the session (`Orphans.releasedByHolder`), which every session counts as an orphan though the holder lives on.
  So withdrawing approval reaches a coordinator as it does a host session that ends.
  Once the coordinator is destroyed or its stamp removed, its workers are orphans like a dead process's, and the proxy ends its session at the next keepalive tick.
  Older isx versions read an `instance:` stamp as unparseable, which they treat as held.
- *What it may control: two consents (#1812).*
  A coordinator may use a template only when both `mcp.yaml`'s `templates:` and its own template's `coordinator.control-over` list it (`McpScope`).
  The two files answer different questions: the definition says what this coordinator is for, and `mcp.yaml` which templates any agent may ever touch.
  Isx instances are not only for agents, so a template holding secrets the person never lists in `mcp.yaml` stays out of every agent's reach, whatever a fetched or edited definition says; and a new coordinator template for templates already approved needs no second edit.
  Neither list can widen the other.
  Deny by default, with no fallback to the global list: no `coordinator.control-over`, a missing or project-local definition, or an untrusted lineage gives an empty scope, so a forgotten list is a warned refusal rather than silent access.
  The scope is keyed on the leaf template, judged by the same `InstanceRegistry.lineageOf` host routes use (#936), read once per session; the definition is read live from the trusted layers on every call, never stamped, so an edit reaches a running coordinator at once and nothing goes stale.
  The nearest layer whose `coordinator:` sets either key decides alone, as it decides the default: its list replaces its parent's, so a child never silently widens a parent's scope, and the coordinators of a child that sets only `isx-control-capability` cannot control any template until it lists some.
  Everything that asks which templates a session may use asks the scope: `list_templates`, `create_instance`, `from_instance`, `adopt_instance` and the take-back's release rule, so narrowing either list releases the workers it no longer covers.
  Each mismatch is said by one function, naming the list, file and key to change, wherever it surfaces: `isx branch` and the TUI branch, a refusal and `list_templates`' `scope_note`, the session log, `isx doctor`, and the TUI's instance details; `isx list` shows only `mcp_client`, since it loads no definitions.
  `coordinator:` is an object so per-caller limits can join it later as keys that only narrow `mcp.yaml`'s global values, the same intersection.
  It is deliberately apart from #1720's `drivers:`: that names host programs already running as the user with the full CLI, a guard rail on `isx destroy`; this restricts guests the proxy identified, a security boundary, and merging them would give `isx mcp` a reason to read `config.yaml`.
- *How its Claude Code or Pi finds the server: registered from the stamp on every start (#1182).*
  Branching with `--mcp-client` is the only per-instance act.
  The exec that delivers each start's secret (`InstanceSecret.GUEST_SCRIPT`, root in the guest) also gets `ISX_MCP_CLIENT` (`InstanceSecret.guestEnv(secret, mcpCaller)`), which every start path reads from the instance it already holds, so it costs no request.
  `McpClientRegistration` compares it with a root-owned marker in the rootfs (`/var/lib/isx/mcp-client-registered`, holding a digest of the entry it registered, `ENTRY_VERSION`, so an isx that changes the entry re-registers existing coordinators on their next start), and checks that Claude Code's own config still holds the entry (`CONFIG_HAS_ENTRY`, so an entry the agent removed comes back; root reads that agent-owned file through `timeout -k 1 2 head -c 16MiB`, since the agent may swap in a FIFO or a vast sparse file and the readiness exec has no time limit of its own -- a read cut short answers "no entry", which costs a re-registration, never a hang).
  A copy gets one removal attempt, whatever it answers: an entry it cannot remove cannot reach the MCP server without the stamp, and retrying it would cost every later start; only when either says otherwise, runs Claude Code's own `claude mcp add-json --scope user isx` or `claude mcp remove --scope user isx` and updates the marker.
  So the registration is the standard one (`claude mcp list` and `/mcp` show it, and every way of launching Claude Code finds it), a steady-state start runs no Claude Code at all, and a reboot isx did not do does not change the registration.
  Nothing is inherited for good: a copy of a coordinator carries the registration until its first isx start, which a branch always is, removes it.
  Only `1` switches it on; a missing or unknown value removes, so a start path that forgot to say would fail closed.
  `claude` lives in the agent's home, where the agent can replace it, so the script runs it as the agent and never as root; the marker is root's, so only the script decides what isx believes it registered.
  The registration is not the capability -- the proxy still checks address, secret and stamp -- so one left behind or added by hand cannot reach the MCP server.
  The secret rotates every start while the registration is written once, so the entry carries a `headersHelper` that reads `/run/isx/instance-secret` by its absolute path: a `claude -p` under a systemd unit never read the login profile that names it.
  Pi gets the same treatment, beside Claude Code and independent of it, so a coordinator may run either.
  The script runs `pi mcp add isx` with the URL and an `X-Isx-Instance-Secret` header whose value is `!cat /run/isx/instance-secret`, which Pi runs on each connection, so it too reads the current secret.
  It has its own marker (`/var/lib/isx/mcp-client-registered-pi`, `PI_ENTRY_VERSION`) and config test (`~/.pi/agent/mcp.json`), so a template with only one of the two agents leaves the other's marker absent.
  Whether an agent is there at all is root's `[ -x ]` along the PATH `isx_as_agent` runs it with (`onAgentPath`), never a process: a coordinator with one agent asks about the other on every start, and a `runuser` for that costs a few milliseconds each time.
  The test only stats the path, so nothing the agent puts there can block root; a binary root sees as executable and the agent cannot run just fails its command, leaving the marker as any failure does.
  `pi mcp add` replaces an entry by that name, so unlike `add-json` it needs no removal first.
  Pi's entry is `--exposure direct`: Pi's default, `codemode`, declares no tool to the model and reaches them only from scripts, while driving isx is a coordinator's main job and the set is small.
- *The playbook is served, not installed.*
  `isx mcp` offers the coordination workflow (create, delegate, `wait_any`, `get_diff`, review, destroy; limits; orphans) as an MCP prompt, `coordinate` (`McpPrompt`), which Claude Code shows as a slash command; `INSTRUCTIONS` stays the tool contract every client reads.
  A skill file in the rootfs was rejected: copies would inherit it, and it would drift from the server it describes, where a prompt exists only where the server is reachable and changes with it.
  `McpPromptTest` fails if it names a tool the server does not have.
- *The branch checks the result.*
  After the start, `isx branch --mcp-client` runs one `initialize` and one `tools/list` from inside the guest against `mcp.isx.internal` (`McpClientCheck`: curl, with the secret in a root-only header file, never on a command line), ends that session, and prints the server's version and tool count or why it was not reached -- and whether Claude Code's own user config (`~/.claude.json`) or Pi's (`~/.pi/agent/mcp.json`) holds an entry for the endpoint, by the same tests the reconcile makes (`ANY_CONFIG_HAS_ENTRY`), read as a file because `claude mcp get` connects to the server and can hang.
  A reachable endpoint with no registration is the very failure #1182 was filed for.
  What the guest returns (curl's errors, the server's version) passes through `OutputFormat.oneLine` before it is printed.
  It is a warning, never a failure: the branch is made either way, and the user sees a coordinator that cannot work while still there to fix it.
  One exec, only for `--mcp-client` branches, the dialog's included.
- *A first coordinator is a ready definition, not a feature (#2079).*
  From a fresh install the shortest path is four commands (docs/MCP.md "From a fresh install"): `isx templates new tpl-coordinator --from -` with a definition the page gives, a build, `isx mcp setup` and a branch.
  The definition is a child of the Quick Start's `tpl-agent` that lists it under `control-over`, so the coordinator inherits Claude Code and its default action, and every start registers the server.
  It is a page's block rather than a built-in template: built-in templates are being retired (#1195), and a fixed one could only name a worker isx guessed at.
  It is not an option that fills in `control-over` either (#2076 dropped single-field options), and the two consents stay exactly as they were: the definition's list, and the person's approval in `mcp.yaml`.
  `CoordinatorFromFreshInstallDocsTest` runs the page's own definition through `isx templates new --from` and checks the coordinator it makes controls `tpl-agent` once approved and nothing before.
  The one hint isx adds is where a coordinator cannot control any template because its template lists none (`isx branch --mcp-client` on a plain template, say): that warning names the page.

The tool contract is the stdio one, unchanged: the same tools, approved templates, `mcp.yaml` limits and audit log, the session's host-side stamps (`mcp-cwd`, `mcp-client-pid`) simply absent.
Out of scope: gating outward actions such as pushes at the proxy (the complement to this), and reaching Incus on another host.

## Agents driving isx over MCP

`isx mcp` exposes a deliberately narrow surface: approved, trusted, built templates only; each instance usable only by the host user's session holding it (another session of that user must adopt it first, never another user's); no host command execution and no host file access; results returned as text.
It runs as the user, so that boundary holds for the MCP tools, not for an agent that may also run `isx` or edit `~/.config/incus-spawn` through its shell.
`docs/MCP.md` gives the Claude Code permission rules that close that path.
Text coming back from an instance (command output, a delegate's report, a diff) is produced inside the sandbox and may try to instruct the host agent; tool descriptions say to treat it as data.
The same holds for `ask` answers, which a model wrote after reading that text: the summarising Claude Code gets no tools, so injected instructions cannot make it act, only mislead, and its answers are never a basis for merging.
A delegated agent can do anything its template's credentials allow, which is why those are the user's choice per template and not the agent's.
A coordinator in an instance (`isx branch --mcp-client`, #915) closes the shell path altogether.
Its only reach into the host is the tool list, granted by a stamp the user sets and no copy inherits, and checked by address, per-start secret and stamp together.

Idempotency keys (#1011) add no capability: a replay goes through `requireOwned` or `adopt()`, never around them.
What a controller must know: a replay naming another instance (or template, or kind of task) than the call that used the key is refused, not redirected, so a controller that picks the instance at run time must record that choice as part of its intent; and a key whose instance was kept is refused, the one outcome to handle by looking the instance up rather than retrying.
Task keys are written inside the guest and so are untrusted: they are read only if they match the key charset, exactly, and answer only for a call naming the instance that holds them.
An agent there could plant one, which misdirects a keyed call on an instance it already controls; and, while this session holds that instance, it could make a keyed call naming another instance be refused as a mismatch -- a refusal, never a capture.

## External orchestrators: owners, start, stop and destroy

Tools that orchestrate coding agents (Orca, herdr, Coder Mux and others) want a disposable machine per task (#1720).
They fall in two kinds, decided by what the tool can do, never by the user.
One has lifecycle hooks: it runs a command to create a machine and another to destroy it, and isx must be drivable from outside.
The other only keeps a list of SSH machines, and isx must register its instances there; that half is a tool's host integration, a later change.

The first kind needs four operations and an SSH target, and isx already had every piece except the commands.
`isx branch --owner <name> [--owner-id <id>]` stamps `user.incus-spawn.owner` and `owner-id` on the copy request.
They are ownership keys (`Metadata.isOwnershipKey`), as `isx mcp`'s are: the copy request unsets them and `configureBranch` drops them, so a user's branch of an orchestrator's instance is the user's from its copy on.
The name is a short lowercase word (`mcp` is reserved for `isx mcp`'s own), the id an opaque handle of a fixed charset, so neither carries anything a shell or a terminal reads.
`isx branch --format=json` then says how to reach the instance: the managed key, `agentuser`, and the ProxyCommand with `--start`, read from the source preflight already read, so it costs no request.

`isx start` and `isx stop` existed only as side effects (`isx shell`) and as MCP tools.
A start is `isx shell`'s preparation (`InstancePrep.prepare`), never a bare Incus start: that would leave the guest without this start's secret and credential proofs, and an agent in it with empty placeholders.
It runs under the instance's `starting` lock, as `start_instance` does, and waits up to two minutes for another holder rather than failing: an orchestrator resuming a workspace while a connection starts it should not see an error.
It then prepares the instance itself instead of taking `Running` for ready, since an instance is running before its start has delivered the proofs.
A stop is `stop_instance`'s, through the one helper they share (`InstanceDestroyer.stopMarkedIf`): under the lock and the `stopping` mark, and a stopped instance is success, so a repeated suspend is harmless.

`isx ssh-proxy --start` starts a stopped instance the same way before connecting.
Every connection of an orchestrator runs it, `git` and `scp` included, so a running container that no isx process holds, and whose boot is stamped as holding its secret (`InstanceLifecycle.readyForThisBoot`), is used as it is, for one read.
One that Incus restored running after a host reboot has an empty `/run/isx`, so it is prepared like a stopped one, as is a running VM, whose secret only its guest can answer for.
Only a stopped one, or one another isx process is starting or stopping, pays for the preparation.
Without `--start`, the Host block a plain `ssh` reads, nothing is started, but a running box whose boot lacks its secret is still given it (`InstanceLifecycle.giveSecretIfMissing`, design/accounts.md, #1975).
A connection that waited out a stop does not undo it: an instance that was up when it came and is down once it may act is refused, never started again.
An orchestrator records a workspace as awake until it suspends it, and reconnects on its own after a host restart.
Only the ProxyCommand handed to a program carries `--start`; the Host block a plain `ssh <name>` reads never does, so typing `ssh` never starts anything.
With `--start`, `ssh-proxy` installs `StdioGuard`, as `isx mcp` does, and relays the guard's stdin and stdout, because they are the SSH connection and a start prints progress.

**Who may destroy.**
`isx mcp` never destroys what it does not control, and an external tool may destroy only what the user let it.
`isx destroy <name> --owner <tool>` deletes only for a tool listed under `drivers:` in `config.yaml` with `destroy: true`, and only while the instance still carries that owner (and `--owner-id`'s handle), checked under the deleting mark (`InstanceDestroyer.deleteHeldIf`).
The grant lives in the user's file, as `isx mcp`'s approval lives in `mcp.yaml`, so no process grants itself the right to delete; a flag the tool passes would have been exactly that.
A missing instance is success, since an orchestrator repeats a destroy that failed after the delete.
A plain `isx destroy` of an owned instance asks, and refuses without a terminal unless `--skip-confirmation`: deleting it under the tool breaks the tool's view of it.
`drivers:` is a raw section like `host-routes:`, and an entry isx cannot read does not grant any permission.

**Who owns what is one column.**
`isx list` has one `OWNER` column, into which `isx mcp`'s standing folds (`mcp held: <purpose>`) beside a tool's name, and its formats append `owner` and `owner_id`.
The TUI's instance table shows the same `OWNER` column from a 100-column terminal (`MainScreen.OWNER_COLUMN_MIN_WIDTH`) and leaves it out when narrower: the bare fit is 80, but there the column takes the other columns' slack and clips them; the instance details show the owner either way (Sanne's decision).
Each row carries the owner cell, and the table draws as many cells as it has columns, so the choice is made per frame, on a resize too, with no rebuild.
Whether any instance is owned is read from every instance, not the search's matches, so the column does not come and go while typing a search.

**Orchestrators with no hooks: a tool's host half.**
herdr keeps a list of SSH machines and has no lifecycle hooks, so nothing in it can call isx.
isx tells it instead: the `herdr` tool installs herdr in the template, and its host half (`HerdrIntegration`, a `HostIntegration`) runs `herdr machine add <name>` on the instance's first start and `herdr machine remove` when it is destroyed, when the host has `herdr`.
Installed in the template, herdr's first connection finds it and starts its server; without it herdr offers to install itself over SSH, which is the step Sanne did not want.
`herdr machine add` runs without a terminal (checked against herdr 0.9.5: about a second, the remote server started and the machine saved).
The target is the instance's start alias, `<name>.start.isx`, which a Host block of its own in isx's managed SSH config resolves (#2038).
That block is the instance's with `isx ssh-proxy --start`, so herdr's connection starts a stopped instance, as an orchestrator's ProxyCommand from `isx branch --format=json` does.
herdr keeps its machines across a stop and cannot start one, and the instance's own block never starts it (#1720), so the machine sat dead in herdr's sidebar.
Its label is the instance's name, so `herdr --machine <name>` still finds it.
Unregistering on stop and adding again on start was the alternative; Sanne chose to keep the machine and start on connect.
herdr's client reconnects to a lost machine by itself, every 0.5 s to 2 minutes, so while one is open it starts a stopped instance again; `herdr machine disable` keeps it stopped.
herdr's own add runs from a start that is preparing the instance, so every command a host integration runs has `ISX_SSH_PROXY_NO_START=1` in its environment (`SshKeyManager.NO_START_ENV`), which ssh hands its ProxyCommand: that connection does not start an instance.
A nested start would wait for the start's lock, or, on a path holding none, prepare the instance again, a VM always, and wait for the herdr records lock its parent holds.
A connection marked that way can only do less than one without the mark, so any host process may set it.
A lost or stale config loses the alias with the instance's block, or the alias alone, so every start writes the alias again when it is not current and herdr's record targets it (`HostIntegration.usesStartAlias`).
herdr is asked only once that holds: the managed config has the instance's block for its address, `~/.ssh/config` includes it, and the instance's host key is pinned (`HerdrIntegration.SshAlias`).
herdr connects with `StrictHostKeyChecking=yes` on its command line, so without the pin it saves a machine it can never reach again (#1863, design/instances.md "Host keys pinned by the instance's name").
Its `machine add` probe does not force strict checking, which is how that went unnoticed.
Otherwise herdr would resolve the name as any host, a real machine of that name included, and install and start its server there; nothing is recorded, so a later start adds it.
What isx did is recorded on the host, one file per instance name: the id of the machine it added and its target, or `-` when it added none.
A record with an id and no target is a machine an older isx added for the name, which never starts the instance.
herdr cannot change a machine's target, so the next start removes that machine and adds the instance again, once; one it cannot remove is kept, warned about once and recorded as reaching the name.
So later starts check one file and run no command, a copy or a renamed instance gets a record of its own, and an untrusted build is not retried or reported again on every start.
A failed add (a timeout, a refused connection) is tried once more, on the next start, since the guest's sshd may not have been up; a second failure warns with the command to run and is recorded as not added (Sanne's decision on #1823).
A destroy removes only the recorded machine: herdr may hold a machine the user added for a real host that shares the instance's name, which a match on the SSH target alone would have removed.
The TUI's instance details name the tools that registered the instance, from the same records, read when they open rather than on every frame.
A stamp on the instance was the first version: a copy carries it, it is gone once the instance is, and writing it cost a request on the start path.
A host half is built-in Java, never a definition's: a cloned repository's tool must not make isx run host commands, and an instance built from a project-local definition gets none, since such a tool may share a built-in's name.
zmx's host-side socket sharing predates the seam and could move onto it.

**Orca: a recipe over the commands, as a first version.**
Orca's environment recipes are four host commands and a strict JSON contract: `create` prints one object naming an SSH target and a project root, and the others read a lifecycle payload on stdin.
`integrations/orca/isx-orca-recipe` speaks that contract over `isx branch --owner orca --format=json`, `isx stop`, `isx start` and `isx destroy --owner orca`, translating with `jq`, so isx has no Orca code.
It names the instance after Orca's runtime id (`orca-<12 hex of its sha256>`), and acts on a payload only when its instance is that name for its runtime.
Orca destroys only what a result named, so a create takes back, through `isx destroy --owner`, both the instance an earlier attempt for the runtime left and its own on any failure or interrupt once it exists.
It covers Orca's worktree mode only, takes the template from the recipe line, and needs the repository cloned as the template's workdir: setup Sanne accepted for a first version, ahead of a smoother one (the `orca` tool's host half writing an Orca plugin, which needs a decision on where the adapter lives).
The `orca` tool installs what Orca's server needs over SSH (`tar`, `gawk`, with `sshd`), from the contributor's branch.
`docs/INTEGRATIONS.md` is the landing page for both kinds of tool.

**Alternatives.**
A dedicated `isx sandbox create|suspend|resume|destroy` group with its own JSON contract would be a second lifecycle surface to keep in step with `branch` and `destroy`.
A built-in `isx orca` (the contributor's branch) ties the surface to one tool's contract and a Java package per orchestrator; its generic pieces are what this takes.
MCP as the integration API would leave out every user and orchestrator that does not want MCP.
A plugin system loaded at run time is not trivial in a native image, and nothing here needs code outside isx.

## Considered and deferred

### MCP `ask` answered from the host

`ask` runs its producer and a tool-less `claude -p` inside the instance, so a template without Claude Code cannot answer it, and every answer spends Claude Code quota.
A host-side answerer was considered: the bulky output would come back to the host `isx mcp` process, which would ask the Messages API through the accounts AI Help uses.
It is deferred because it reverses the rule that `ask` runs in the instance, never on the host.
Up to 400 KB of untrusted guest output would pass through a host process, and the call would bypass the proxy, so `instance_activity` would not count it.
It is worth revisiting when Codex-only coordinator targets lose real work to the missing `ask`, or when an answerer without Claude Code can run in the instance through its own proxied connection.
The proposal is in #1676.
