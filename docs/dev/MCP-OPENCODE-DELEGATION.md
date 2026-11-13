# MCP delegation to opencode workers

Status: implemented in this checkout.
Investigated on 2026-11-13 against opencode 1.19.5, its `run` command's source and a local model server that speaks Anthropic's API.

A coordinator can hand a task to an instance whose coding agent is opencode, as it does for Codex and Pi.
opencode is a fourth `DelegateAgent` on the seams the [Codex worker design](MCP-CODEX-DELEGATION.md) introduced, with no new mechanism.
This page records only what differs.

## Selection

`list_templates` reports `opencode` in `delegate_agents` for a template that installs it.
Without an explicit `agent`, Claude wins when installed, then Codex, then Pi, then opencode.
So no template that delegated before this change changes its default.
`send_message` keeps the task's recorded agent.

## Sessions: named by opencode, refused when gone

opencode names its own sessions (`ses_...`), and `run` has no option to choose one.
Every event of `run --format json` carries the session's `sessionID`, so run 1 saves it from the first event, as the Codex runner saves its thread ID.
A kill between that event and the publish is covered as for Codex: `send_message` on a task with no `session_id` asks the runner to `recover` it from `events-1.jsonl` (`DelegateAgent.recoversSession`).
A later run whose events name another session is stopped at once.

`opencode run --session <id>` refuses a session it cannot find: it prints "Session not found" and exits 1 without opening one (verified with 1.19.5, the TUI's `--session` too).
So opencode needs none of Pi's session-file checks: a lost session fails the run with its stderr, and the coordinator starts a new task.
A session is tied to the directory it ran in: a `--session` run from another directory did not finish, so every run starts in the task's recorded directory, as all runs do.

## The runner

Before a launch, isx checks the CLI in the worker (`DelegateAgent.probe`): `opencode run --help` must list `--auto`.
opencode prints its help on stderr, so every agent's probe reads both streams; reading stdout alone refused every delegate on a real VM.

`mcp/opencode-runner.cjs` runs inside the worker under Node, which `OpencodeSetup` installs, and shares `mcp/runner-io.cjs` with the other runners.

```sh
opencode run --format json --auto [--session "$session"] [--model "$provider/$model"] < prompt
```

The prompt goes on stdin, which opencode reads when no message is on the command line, so nothing the coordinator wrote is parsed as an option.
opencode has no option to add to the system prompt, so the delegation brief opens run 1's prompt, as for Codex.
The isx primer needs no delivery: the build's `opencode.json` names `/AGENTS.md` in `instructions`, which opencode adds to every request (verified against the local model server).
`--auto` answers every permission opencode would ask about, since nobody is there to; what `run` itself denies (questions, entering and leaving plan mode) stays denied.

opencode takes a model as `provider/model`.
A bare model ID, the form the tool's `model` parameter and the other agents use, gets the provider of the `model` in `opencode.json`, which the template's `provider` decided at build.
A template with `provider: openai` or `google` and no `model` writes none there, so a bare ID fails the run, asking for `provider/model`.

| Event | Interpretation |
| --- | --- |
| first event | Its `sessionID` is saved on run 1, and must be the saved one on a resume |
| `text` | The report is the text of the last assistant message, which may come in several parts |
| `step_finish` | Counted as `turns`; its `tokens` and `cost` are summed, and `reason: error` fails the run |
| `error` | A failure, its message the task's error |
| `tool_use` | Activity snippets for `task_result(events)` and the TUI |

A tool's output can be large, so records above 8 MiB are dropped unparsed, as for Pi; when the dropped one was a `text`, the report counts as lost.
A run succeeds only with exit zero, at least one step, no `error` event and no step that ended in error.
`task_result` reports `turns`, `cost_usd`, `input_tokens`, `output_tokens`, `cached_input_tokens` (`cache.read`) and `cache_creation_input_tokens` (`cache.write`).

## Policy and capability differences

| Feature | opencode |
| --- | --- |
| Permission policy | `--auto`, reported as `permission_mode: "full-access"` |
| Turn limit | None, as for every agent |
| Model preflight | None; access errors become failed task results |
| `ask` | Needs Claude in the template |
| Credentials | The proxy injects the account the template's `provider` spends (`claude`, `openai` or `gemini`) |

## Human takeover

opencode keeps its command line, so `Presence` finds a person's opencode by the `--session` or `-s` it resumes, as well as by its directory.
`isx shell --task` runs `opencode --session <id>` in the task's recorded directory, under the conversation lock.

## A coordinator's opencode

An instance branched with `--mcp-client` gets the server in `~/.config/opencode/opencode.json` under `mcp.isx` on each start (`McpClientRegistration`).
opencode has no command that removes a server, and `opencode mcp add` cannot set `oauth: false`, so Node run as the agent edits the file.
The secret header is `{file:/run/isx/instance-secret}`, which opencode reads once per process; design/mcp.md says why that is enough.

## Validation in this checkout

`TaskScriptsTest` runs the real runner under bash and Node with an opencode stand-in that keeps opencode's session rule.
It covers initial and resumed runs, prompt quoting, brief delivery, the model's provider, a lost session, failure cases, recovery of a session ID and presence.
`DelegationToolsTest` covers discovery, selection, results and continuation, and `McpClientRegistrationTest` the coordinator's entry.
The runner was also run by hand against the real opencode 1.19.5 here, with a local server answering as Anthropic's API: two runs continued one conversation, the brief and `/AGENTS.md` reached the model, and a missing session was refused.
No delegation has yet run against a real model in a real instance: the box's Claude account refuses third-party apps.
