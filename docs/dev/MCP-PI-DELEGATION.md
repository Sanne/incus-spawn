# MCP delegation to Pi workers

Status: implemented in this checkout.
Investigated on 2026-10-30 against the current checkout and Pi 1.4.0.

A coordinator can hand a task to an instance whose only coding agent is Pi, exactly as it does for Codex.
Pi is a third `DelegateAgent` built on the seams the [Codex worker design](MCP-CODEX-DELEGATION.md) introduced, with no new mechanism.
This page records only what differs from Codex.

## Selection

`list_templates` reports `pi` in `delegate_agents` for a template that installs it.
Without an explicit `agent`, Claude wins when installed, then Codex, then Pi.
So no template that delegated before this change changes its default.
`send_message` keeps the task's recorded agent, as for Codex.

## Sessions: chosen up front, checked on every resume

`pi --session-id <id>` opens the session with that exact ID, or creates it when the working directory has none.
So isx needs no capture step: run 1's runner generates a random UUID before Pi starts, and every run passes it.
A random ID, never one derived from the task ID, because a fork copies the source's Pi sessions while task IDs are only unique per session.

The same rule is a hazard on a resume: a session Pi cannot find is silently replaced by a new, empty one under the same ID.
That would continue the task without its instruction or history.
Pi's first JSON record is the session header, whose `timestamp` is the session's creation time, unchanged when it is resumed (verified live with Pi 1.4.0).
Run 1 publishes that timestamp as `session_started` when the header arrives.
The session's file is named by that timestamp and the ID, so isx checks it is still there before Pi is started for a resume (`TaskScripts.piSessionCheck`).
`send_message` asks first, in one exec, and refuses with `wrong_state` without reserving a run; the run script asks again before it starts the runner.
Checking only afterwards is not enough: Pi writes the new message into the replacement session before the runner can stop it, and every refused attempt would add another (seen on real Incus).
The header check stays as the backstop: a later run whose header differs is stopped at once and fails with "start a new delegated task".
Pi prints the header before the session exists on disk: it writes the file only when the first user or assistant message is appended, right after emitting that message's `message_end`.
So run 1 publishes the ID as `session_id` at the record after the first such `message_end`, when the file is certainly there.
A task cancelled before that has no `session_id`: `task_status` is not `resumable`, and `send_message` refuses with `wrong_state` without launching.
On run 1 the runner drops Pi's warning that it is creating a session under that ID, which is expected there; on a resume the warning would mean a lost session, and it stays.

## The runner

`mcp/pi-runner.cjs` runs inside the worker under Node, which `PiSetup` installs.
It shares `mcp/runner-io.cjs` with the Codex runner: bounded no-FIFO, no-symlink reads, publishing by rename from a private scratch directory, and LF-only JSONL splitting.
Both are included in native resources by the `mcp/*.cjs` pattern.

```sh
pi -p --mode json --session-id "$session" --append-system-prompt "$brief" [--model "$model"] < prompt-<run>.md
```

The prompt goes on stdin, which Pi treats as the first message, so nothing the coordinator wrote is parsed as an option or an `@file`.
The delegation brief goes to `--append-system-prompt` on every run, as Claude's does, since Pi rebuilds its system prompt each time.
The isx primer needs no delivery: Pi loads `/AGENTS.md`, the build's hardlink to `/etc/isx/agent-instructions.md`, from every working directory.
Without a terminal, Pi skips project-local resources nobody trusted (its `defaultProjectTrust`), so isx passes neither `--approve` nor `--no-approve` and a person's saved trust decisions still apply.

The event stream is large: the system `message_start` carries the whole system prompt and `agent_end` repeats the whole conversation.
So a status tail cannot be trusted to hold the final message, and the runner keeps it instead.
Records above 8 MiB are dropped unparsed; when the dropped one was a `message_end`, the report counts as lost rather than falling back to an earlier message.

| Event | Interpretation |
| --- | --- |
| `session` (first record) | Must name the chosen ID; run 1 records its `timestamp`, later runs must match it |
| `message_end` with `role: assistant` | The latest report text, its `stopReason`, `errorMessage`, token usage and `cost.total` |
| `turn_end` | Counted as `turns`, Pi's equivalent of Claude's agent turns |
| `auto_retry_end` with `success: false` | Diagnostic from `finalError` |
| `agent_settled` | Terminal evidence; `aborted: true` is a failure |
| `tool_execution_start` | Activity snippets for `task_result(events)` and the TUI |

A run succeeds only with exit zero, a matching header, `agent_settled` with `aborted: false`, and a last assistant `stopReason` other than `error` or `aborted`.
The runner publishes `result-<run>.txt` and `outcome-<run>.json` (`type: isx.pi.result`) before the exit record.
`task_result` reports `turns`, `cost_usd`, `input_tokens`, `output_tokens`, `cached_input_tokens` (Pi's `cacheRead`) and `cache_creation_input_tokens` (its `cacheWrite`) when Pi reported them.

## Policy and capability differences

| Feature | Pi |
| --- | --- |
| Permission policy | No sandbox or permission modes: always every tool, reported as `permission_mode: "full-access"` |
| Turn limit | None, as for every agent |
| Model preflight | None; a model is checked for shape, and access errors become failed task results |
| `ask` | Needs Claude in the template, as for Codex |
| Credentials | The proxy injects the account the template's `provider` spends (`claude`, `openai` or `gemini`); the adapter reads none |

Every worker runs with full access inside its instance, which is the boundary (#1857), so Pi needs no mapping of a Claude policy.
The first Pi release refused a template with a restrictive Claude permission mode, and refused Pi while `delegate-max-turns` was set; #1857 removed both policies.

## Human takeover

Pi replaces its command line with its title, `pi`, so `/proc/<pid>/cmdline` never shows the session it opened.
`Presence` therefore recognizes a person's Pi by name (or its `node .../pi-coding-agent/...` launcher before the rename) and its working directory alone.
That suffices: Pi's sessions belong to the directory they started in, so `--session-id` from elsewhere opens another session and `--session` forks.
`isx shell --task` runs `pi --session-id <id>` in the task's recorded directory, under the conversation lock.
Interactive Pi creates a missing session just as `-p` does, so the join guard first looks for the session's file and refuses when it is gone.
The file's name is the header timestamp (with `:` and `.` as `-`) and the ID, so a replacement Pi created since does not pass for it.
The guard looks only where Pi itself looks: in `PI_CODING_AGENT_SESSION_DIR` when set, else in the task directory's own folder under Pi's `sessions` (`--<cwd, / \ and : as ->--`, as Pi 1.4.0's `getDefaultSessionDirPath` derives it, under `PI_CODING_AGENT_DIR` or `~/.pi/agent`).
Either variable's leading `~` (alone, or followed by `/`) is the home, as Pi's `normalizePath` expands it; `~user` is not.
A `sessionDir` setting elsewhere is not followed, so such a join is refused rather than opened empty.

## Upgrades

Restart existing MCP server connections after upgrading isx to enable Pi workers.
An older isx reads a Pi task's `1 pi` identity as unknown, so it neither continues nor guesses; keep outstanding Pi instances before downgrading.

## Validation in this checkout

`TaskScriptsTest` runs the real runner under bash and Node with a Pi stand-in that keeps Pi's session rule.
It covers initial and resumed runs, prompt quoting, brief delivery, a lost or re-created session, failure cases, a large report beside multi-megabyte records, cancellation before and after the session file exists, presence, and the session check before a join and a resume.
`DelegationToolsTest` covers discovery, selection, policy refusals before branching, results, continuation and the missing-session refusal.
The runner was also run by hand against the real Pi 1.4.0 here: two runs continued one conversation, and a deleted session was refused before Pi answered.
A live coordinator smoke test on real Incus passed with `tpl-isx-pi` (#1856's review): same-session continuation, joining, the lost-session refusal on joins, results, cancel, adoption, an unknown model and the policy refusals.
It found that `send_message` on a lost session let Pi write a replacement file, which the pre-launch check above now prevents.
