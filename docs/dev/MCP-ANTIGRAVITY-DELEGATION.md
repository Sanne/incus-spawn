# MCP delegation to Antigravity CLI workers

Status: implemented in this checkout.
Investigated on 2026-11-20 against Antigravity CLI (`agy`) 1.5.12, the pinned build of the `antigravity` tool, on a real Gemini API key through the isx proxy.

A coordinator can hand a task to an instance whose coding agent is Google's Antigravity CLI, as it does for Codex, Pi and opencode.
Antigravity CLI is a fifth `DelegateAgent` on the seams the [opencode worker design](MCP-OPENCODE-DELEGATION.md) uses, with no new mechanism.
This page records only what differs.

## Selection

`list_templates` reports `antigravity`, the tool's name, in `delegate_agents` for a template that installs it.
Without an explicit `agent`, Claude wins when installed, then Codex, then Pi, then opencode, then Antigravity CLI.
So no template that delegated before this change changes its default.
`send_message` keeps the task's recorded agent.

## Conversations: named by agy, checked before a resume

agy names its own conversations with a UUID, and its print mode has no option to choose one.
Its first stream-json event, `init`, carries the `conversation_id`, so run 1 saves it, as the opencode runner saves its session.
A kill between that event and the publish is covered as for opencode: `send_message` on a task with no `session_id` asks the runner to `recover` it from `events-1.jsonl` (`DelegateAgent.recoversSession`).

`agy --conversation <id>` with an ID it cannot find does not fail.
It prints a warning, opens a new conversation and works on the message there (verified with 1.5.12).
agy also prints `init` only once it has read the message, so the runner cannot stop it before it starts working.
So a resume first checks that the conversation's file, `~/.gemini/antigravity-cli/conversations/<id>.db`, is still there, and fails the run without starting agy when it is not.
A resume whose `init` names another conversation is still stopped at once, and the task keeps its saved one.
The join guard of `isx shell --task` checks the same file (`TaskScripts.antigravityConversationExists`).
agy keeps conversations under the home whatever `ANTIGRAVITY_GEMINI_DIR` says (verified), so the check reads `$HOME`.

## The runner

Before a launch, isx checks the CLI in the worker (`DelegateAgent.probe`): `agy --help` must list `--input-format`.

`mcp/antigravity-runner.cjs` runs inside the worker under Node, and shares `mcp/runner-io.cjs` with the other runners.
agy itself is one Go binary, so the `antigravity` tool installs `nodejs` for the runner alone.

```sh
agy -p= --input-format stream-json --output-format stream-json --dangerously-skip-permissions \
    [--conversation "$id"] [--model "$model"] [--effort "$effort"] < message
```

The prompt goes on stdin as one stream-json message, `{"event":"user","message":{"role":"user","content":"..."}}`.
`-p` takes its prompt as its value, and an argument is capped at 128 KiB, while a task's input may reach 1 MiB.
`-p=` gives print mode an empty value, and `--input-format stream-json` makes it read the message from stdin; `-p -` does not read stdin, and sends `-` as the prompt.
agy has no option to add to the system prompt, so the delegation brief opens run 1's prompt, as for Codex.
The isx primer needs no delivery: the build writes it as an always-on rule, `~/.gemini/config/rules/isx.md`.
`--dangerously-skip-permissions` answers every permission agy would ask about, since nobody is there to; the build's settings already let every tool call proceed.
A folder agy was never told to trust does not stop print mode (verified).

| Event | Interpretation |
| --- | --- |
| `init` | Its `conversation_id` is saved on run 1, and must be the saved one on a resume |
| `step_update`, `agent_response`, `DONE` | One model call: counted as `turns`, and its `usage` summed |
| `step_update`, `tool` | Activity snippets for `task_result(events)`, one per step index |
| `result` | Its `response` is the report, and its `status` must be `SUCCESS`; otherwise its `error` is the task's |

The result's own `usage` and `num_turns` count the whole conversation, not the run (verified: a resume repeated run 1's), so the runner sums the run's own steps instead.
`input_tokens` there excludes what was read from the cache, which is `cache_read_tokens`, reported as `cached_input_tokens`.
agy reports no cost and no cache writes.
A record above 8 MiB is dropped unparsed, as for Pi and opencode; when the dropped one was the `result`, the report counts as lost and the run fails.
A run succeeds with exit zero, a `result` whose status is `SUCCESS`, and no malformed record.
agy reports `ERROR` and exits 1 when a model call timed out and its own retry then answered, the retry's answer being the `response` (seen on a real instance).
The runner counts that run as finished when the `response` is not empty and a model answer (an `agent_response` step) came after the last `error_message` step.
The task then succeeds with its report, and the timeout stays in agy's stderr and the result's text.
A `result` with an empty `response`, or with no answer after the last failure, still fails, as the default model's 429 does.
A failed model call shows in `recent` as "a model call failed", since its `error_message` step carries no text; agy may retry it, or give up, as it does on a 429.

## Policy and capability differences

| Feature | Antigravity CLI |
| --- | --- |
| Permission policy | `--dangerously-skip-permissions`, reported as `permission_mode: "full-access"` |
| Turn limit | None, as for every agent |
| Model preflight | None; access errors become failed task results |
| `ask` | Needs Claude in the template |
| Credentials | The proxy injects the `gemini` account's key, as for Gemini CLI |

A free-tier Gemini key has no quota for agy's default model, so a task given no `model` and no template `model` fails with Google's 429 in its result.
`agy models` lists the IDs `model` takes.
The tool's `model` and `effort` parameters reach the runner as `ISX_AGY_MODEL` and `ISX_AGY_EFFORT` in the login environment (#2474).
A task's own `model` and `effort` win, and the task's recorded profile stays what the task chose.

## Human takeover

agy keeps its command line, so `Presence` finds a person's agy by the `--conversation` it resumes, with one dash or two and the value apart or after `=`, as Go's flags take it, as well as by its directory.
`isx shell --task` runs `agy --conversation <id>` in the task's recorded directory, under the conversation lock.

## MCP servers: a stdio bridge (#2476)

agy expands no variable, file or header helper in `mcp_config.json`, so a credential cannot be named there the way Claude Code, Pi and opencode name it.
isx gives agy a stdio server instead, `/usr/local/lib/isx/mcp-bridge.cjs`, which Node runs and which forwards each message to the Streamable HTTP server.
The bridge keeps no credential and none is written into `mcp_config.json`.
A file (`--header` and `--header-file`) is read when each request is sent, so a rotation reaches the next request.
A variable (`--bearer-env`) is the bridge's environment as agy started it, so a changed proof, after `isx account reset-proofs`, reaches it only when agy starts the bridge again.
The script is root's file in the guest, written by the build (`McpStdioBridge.install`, from `AntigravitySetup` and from `McpClients.ANTIGRAVITY` when it registers a server that needs it), so the agent cannot rewrite it.

Two servers use it:
- A tool's remote server with `bearer-token-env` is registered as `node <bridge> --url <url> --bearer-env <VAR>`.
  agy hands its own environment to the stdio server it starts (verified with 1.5.14: a variable set for agy reached the server, as did `NODE_EXTRA_CA_CERTS`), so the variable is read there.
  A server with no bearer token stays a plain `serverUrl`.
- A coordinator's `isx` server is registered from the stamp on every start like Pi's and opencode's (`McpClientRegistration`), as `node <bridge> --url https://mcp.isx.internal/mcp --header X-Isx-Instance-Secret --header-file /run/isx/instance-secret`.
  An image built before the bridge existed has no bridge file, so its start registers nothing and tries again after a rebuild.

The proxy ends an instance's session when another client initializes, and drops all of them on a restart, and agy never sees the 404.
So on a 404 the bridge initializes again with the `initialize` agy first sent, and retries the request once.
The bridge answers a request it cannot forward with a JSON-RPC error, never a response of agy's, and ends the session with a `DELETE` when agy closes its stdin.
It follows no redirect, so the secret header cannot reach another origin.
It opens no stream for server-initiated messages, which `isx mcp` does not send.

## Validation in this checkout

`TaskScriptsTest` runs the real runner under bash and Node with an agy stand-in that keeps agy's conversation rule.
It covers initial and resumed runs, prompt quoting, brief delivery, a lost conversation, another conversation opened in its place, failure cases, an oversized result, recovery of a conversation ID, presence and the join guard.
`DelegationToolsTest` covers discovery, selection, results and continuation, and `DelegateAgentProbeTest` the probe.
The runner was also run by hand against the real agy 1.5.12 with a free-tier key: two runs continued one conversation, a missing conversation was refused before agy started, and the default model's 429 became the task's error.

On 2026-11-20 it ran end to end on a real Incus in a VM, through `isx mcp` over stdio, with `gemini=inherit` and a Flash model:
- `list_templates` offered `antigravity` for an agy-only template, and `delegate` ran agy, which wrote a file that `get_diff` showed;
- `send_message` resumed the same conversation, and agy answered from it without a tool, with that run's own usage;
- a task on the default model failed with Google's 429 as its error;
- `isx shell --task` started `agy --conversation <id>` in the task's directory, `task_status` said `attached` and `send_message` was refused, and `reclaim_task` ended it with SIGHUP.
