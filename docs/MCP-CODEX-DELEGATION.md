# MCP delegation to Codex workers

Status: implemented in this checkout.
Investigated on 2026-10-11 against the current checkout and local `codex-cli 0.162.1`.

A Claude Code coordinator should be able to give a task to an instance that contains only Codex, wait for its report, inspect its changes, and continue its conversation.
The coordinator's choice of client should be independent of the worker's agent.
Implement this through the existing MCP tools, with a small adapter for each supported worker agent.

## Starting point

Before this change, managed delegation required Claude Code.
The previous `InstanceBackend.TemplateInfo.supportsDelegate()` checked `tools.contains("claude")`, and `McpTools.requireDelegate()` rejected a Codex-only template.
`TaskScripts.agentRun()` previously always started `claude -p` and resumed with Claude's `session_id`.
`StreamJsonEvents`, `ModelCheck`, `AskScript`, and `Presence` also assumed Claude.
The template approval step in `InitCommand` offered only templates that installed Claude.

The transport is already independent of the worker's agent.
A Claude coordinator inside an instance continues to use `McpBridge` and its existing `--mcp-client` grant.
A worker does not need an MCP client registration or a grant to coordinate other instances.

An approved Codex template can already be branched with `create_instance`, and `exec(background=true)` can launch `codex exec` inside it.
That is a command task: it has command output and cancellation, but no managed agent report, conversation continuation, or agent-task diff baseline.
It is a temporary manual workaround, not the capability proposed here.

Codex installation and credential isolation already exist in `common/.../tool/CodexSetup.java`.
The template's `openai` account is injected by the proxy for `api.openai.com`.
The login script refreshes `~/.codex/auth.json` from the instance's current proof token.
The initial implementation supports that existing API-key route.
ChatGPT subscription authentication and automatic registration of a Codex coordinator are separate work.

## MCP contract and agent selection

Keep `delegate`, `send_message`, `task_status`, `task_result`, `wait_any`, `cancel_task`, and `get_diff`.
Add an optional `agent` argument to `delegate`, with the closed values `claude` and `codex`.
Resolve it before creating an instance or reserving a task:

| Template's supported agents | Omitted `agent` | Explicit `agent` |
| --- | --- | --- |
| Claude only | Claude | Accept Claude; reject Codex |
| Codex only | Codex | Accept Codex; reject Claude |
| Both | Claude, preserving existing behavior | Use the requested installed agent |
| Neither | Reject delegation | Reject delegation |

Installed tools come from trusted template inheritance, as they do today.
Do not choose an agent from the coordinator's identity, `default-action`, a model-name prefix, or whichever executable happens to be on the guest's PATH.
Probe the selected executable in the guest before launch to catch an old or modified image.
A missing executable or incompatible CLI produces `refused` with rebuild or upgrade guidance.
There is no fallback to another agent.

`send_message` always uses the task's recorded agent.
It accepts model overrides as today, but cannot switch a conversation between Claude and Codex.
One agent task per instance remains the rule, across both agent types.

Add the following discovery fields to `list_templates` and the corresponding instance descriptions:

- `delegate_agents`: descriptors with `agent`, optional template `model`, `supports_max_turns`, and `supports_model_preflight`.
  Each descriptor also reports its effective permission settings and any configuration refusal that would prevent launch.
- `default_delegate_agent`: the selection from the table, omitted when neither agent is available.
- `supports_ask`: whether this instance can provide the current tool-free summarization service.
  `summary_agent: "claude"` makes its separate credential use explicit when available.

Keep `supports_delegate`, meaning at least one supported worker agent is installed.
It remains distinct from `built`, `stale`, and configuration eligibility.
Keep `delegate_model` as the default agent's configured model, omitting it when the agent chooses its own default.
The nearest template layer declaring a given tool supplies that tool's parameters, matching current build behavior.

Task records in tool results and `list_instances` gain `agent` for agent tasks only.
Keep the required `permission_mode` string in launch results: Claude returns its existing mode, and Codex returns its selected sandbox mode alongside `agent: "codex"` and `approval_policy: "never"`.
Update its schema description to name both meanings, without renaming the field or inventing a Claude mode for Codex.
Status also exposes the persisted `session_id` when known and `resumable` when it can be determined.
For Codex, `session_id` contains its thread ID.
Keep the existing task-state vocabulary and notification envelope.
Programmatic clients can discover the agent from the task they already received.

Example calls:

```json
{"template":"tpl-codex-dev","instruction":"Fix issue #123, run the relevant tests, and report the changes."}
{"template":"tpl-mixed-dev","agent":"codex","instruction":"Review this repository for the reported race."}
{"task_id":"<returned-task-id>","message":"Address the review findings and rerun the tests."}
```

## Adapter boundary

The closed `DelegateAgent` enum selects the worker policy, with `TaskScripts` supplying agent-specific invocations and `CodexEvents` interpreting Codex output.
Construct it only on the MCP path, with no new Quarkus extension or application startup work.
An adapter owns these differences:

- Tool name, credential namespace, configured model lookup, and capability description.
- Validation of agent-specific profile and permission settings.
- Initial and resumed invocation, including the instruction or skill brief.
- Event interpretation, final report, and session identification.
- Model preflight where supported, and process identification for human takeover.

`Tasks` continues to own reservations, run numbers, ownership, adoption, cancellation, and notifications.
`TaskScripts` continues to own the systemd lifecycle, git baselines, durable records, and bounded guest reads.
Adapters supply invocation details rather than duplicating those lifecycles.
`StreamJsonEvents.Summary` is the shared internal event summary; `CodexEvents` normalizes its outcome and recent activity, while the final Codex report is read from its separate durable file.
Claude's adapter must retain its existing rendered fields and behavior.
An unavailable metric is omitted, never reported as zero.

## Codex execution

Use the CLI's non-interactive interface inside the guest.
OpenAI documents JSONL events and resuming a specific session in [Non-interactive mode](https://developers.openai.com/codex/noninteractive/).
The local CLI help confirms the following invocation shapes, including stdin on resume:

```sh
# Run in the task's resolved cwd, through the existing agentuser login environment.
codex exec --json --skip-git-repo-check \
  -c 'approval_policy="never"' -c 'sandbox_mode="danger-full-access"' \
  --output-last-message "$result_tmp" - < "$prompt_file"

codex exec resume --json --skip-git-repo-check \
  -c 'approval_policy="never"' -c 'sandbox_mode="danger-full-access"' \
  --output-last-message "$result_tmp" "$session_id" - < "$prompt_file"
```

These illustrate the default policy; the adapter substitutes the user's effective policy and appends a validated `--model` only when requested.
The runner changes directory before either invocation because resume does not expose all the initial command's flags.
The git-check override supports existing isx workdirs such as the home directory containing several repositories.
Never use `--last`, which could select a person's conversation, or `--ephemeral`, which would discard resumability.
Keep prompts on stdin, execute argv without shell interpolation where possible, and use `ExecScript.quote()` wherever shell values are necessary.

Retain the transient systemd unit, login shell, `ISX_MCP_TASK` marker, per-run stderr, and existing cancellation sweep.
Codex and its command children must inherit the marker so cancellation still reaches processes moved by PAM outside the unit's cgroup.

The first Codex input includes the shared delegation brief and the existing isx environment primer before the coordinator's instruction.
Deliver those as task input rather than overwriting user or project `AGENTS.md` files or Codex's configured developer instructions.
Keep the brief in the conversation; subsequent runs send the follow-up message.
The primer can be read from the existing build-generated file with bounded regular-file reads, with the fixed isx preamble as the fallback for older images.
This avoids requiring Claude to be installed merely to explain the environment.

Preserve Claude's `/<skill> <args>` translation.
For Codex, render an instruction to use the named installed skill, including its resolved `~/.agents/skills/<name>/SKILL.md` path and the supplied arguments.
Validate the name and installed path before launch, and refuse a missing skill rather than silently treating it as plain prose.
Claude-specific command namespaces are not automatically Codex skill names.

## Durable sessions and results

Codex emits `thread.started` with `thread_id`; this must survive a coordinator disconnect and a worker interruption.
Capture it when emitted, rather than only after Codex exits or from a bounded tail that may omit the first event.
Use a small guest-side Node runner delivered with the task script; `CodexSetup` already installs Node.
It launches Codex, forwards stdin, streams stdout to the raw event log, and parses complete JSONL records without retaining the log in memory.
It inherits the task marker and propagates child exit or signal failure.
The preflight probe checks for Node and compatible Codex exec/resume flags.

The runner records `session_id` on the first valid `thread.started` and a bounded per-run outcome record on terminal events.
It publishes the final message from `--output-last-message` into `result-<run>.txt` after process completion.
These writes use private temporary files and rename-over-target, matching `writeAside`'s no-FIFO and no-symlink-following behavior.
Apply explicit limits to JSON records, identifiers, reports, and diagnostics.
Oversized or unknown events cannot block draining stdout or make memory grow without bound.
A parsing failure leaves diagnostic evidence and cannot fabricate successful completion.

Use Codex's event stream as follows:

| Event | Interpretation |
| --- | --- |
| `thread.started` | Persist the exact thread ID |
| `item.started`, `item.updated`, `item.completed` | Bounded activity snippets; completed agent messages contribute report text |
| `turn.completed` | Successful turn evidence and optional token usage |
| `turn.failed` | Failed turn with its diagnostic |
| `error` | Diagnostic; process exit and terminal turn outcome decide success |

The event types are documented in [Non-interactive mode](https://developers.openai.com/codex/noninteractive/).
Persist the final report independently of the event tail because a long final message may be outside that tail.
The existing `exit-<run>` remains the lifecycle completion marker and is published after the outcome and report records.
`task_result` claims success only with exit zero and a recognized successful terminal event for that run.
Exit zero with no terminal evidence is an incomplete result, with diagnostics, rather than success.
A nonzero exit or failed turn reports an error even if an earlier assistant message sounds successful.
Cancellation and loss retain their existing lifecycle treatment and never reuse a previous run's report as the new result.

On an interrupted run, a captured session may still be resumable.
If the durable ID is absent, recover it through a bounded parse of the beginning of the first run's log.
If neither source yields a valid ID, `send_message` refuses with `wrong_state` and explains that a new task is needed.
It never creates a replacement conversation silently.
If Codex rejects an otherwise valid saved ID on resume, record the failed run and expose its diagnostic.

## Persistence, replay, and human takeover

Extend `Tasks.Task` with an immutable agent identity separate from `kind=agent` and the mutable profile.
Version new task metadata, and record its agent before launching the first run.
The `list`, `status`, and `watch` guest formats must carry enough identity to reconstruct the right adapter after adoption.
Read all new metadata and report files through the existing bounded regular-file mechanism.
Versionless legacy agent tasks mean Claude; missing or unknown agent identity in versioned metadata is a refusal, never a guess.
Unknown-format tasks must conservatively count as busy when they might still run and protect an orphan from deletion.
Their records must remain inspectable even when they cannot be resumed.

Re-evaluate host policy on every continuation, including adopted tasks.
Guest metadata selects only a known adapter and validated values; it cannot supply executable paths, raw flags, accounts, or permissions.
Template installation checks and current policy still apply to the recorded agent.

Idempotency matching must include the selected agent.
When a keyed task already exists, an omitted `agent` means that task's recorded agent even if the template has since changed.
An explicit conflicting agent returns `invalid_argument`.
For `delegate(template)` interrupted between instance creation and task creation, stamp the selected agent with the keyed instance's creation metadata.
Finish that original selection on replay; do not resolve a new default.
Clear this creation intent with the other MCP metadata on forks, and clear task records as today.
An unkeyed `delegate` on the fork may choose either supported agent for its new conversation.

Generalize `Presence.Claude` to an agent process record.
Recognize native Codex and its Node launcher, explicit `codex resume <id>`, and `codex exec resume <id>` without the managed-task marker.
Match an explicit session by agent and ID, and conservatively block a managed writer when an unmanaged coding agent occupies the same working directory.
The cwd fallback also covers a resume picker or `resume --last`.
All unmanaged Codex processes count as attendance for orphan protection, including those without a TTY.
Keep the existing single `/proc` scan per instance and its documented race after the check.

Upgrade the host binary and restart existing MCP server connections before enabling Codex workers.
New servers adopt old Claude tasks, but old servers cannot safely interpret new Codex tasks.
Downgrade or simultaneous servers from different releases is not a supported compatibility promise for this feature.
Keep any outstanding Codex instances before a downgrade so an old orphan sweep cannot manage them.

## Permissions, models, and feature differences

Permission policy belongs to host configuration, never to the coordinator's tool arguments.
Retain the existing Claude permission keys and add Codex-specific settings:

```yaml
mcp:
  templates: [tpl-claude-dev, tpl-codex-dev, tpl-codex-review]
  codex:
    sandbox-mode: danger-full-access
    sandbox-modes:
      tpl-codex-review: read-only
```

Allow only `read-only`, `workspace-write`, and `danger-full-access`, with `approval_policy=never` on every Codex invocation.
These are Codex settings, documented in the [CLI reference](https://developers.openai.com/codex/cli/reference/) and [configuration reference](https://developers.openai.com/codex/config-reference/).
The default matches `CodexSetup` and relies on the existing isx instance boundary.
If the effective legacy Claude permission setting is restrictive and no explicit Codex setting covers the template, refuse Codex delegation until the user chooses its policy.
A restriction set for one template in `delegate-permission-modes` is covered only by that template's `sandbox-modes` entry, never by the section-wide `sandbox-mode`.
Do not silently turn a configured Claude `plan` worker into a fully writable Codex worker.
Codex sandboxing is not a promise that every remote tool is read-only; the template's credentials remain the external authority boundary.

`model` defaults to the Codex template's configuration, with no model slug baked into MCP.
Template reasoning effort remains in effect; a separate per-task effort override can be added later.
Generalize credential selection from the hard-coded `ModelCheck.NAMESPACE` to the selected adapter: `claude` or `openai`.
Any preflight cache includes agent, template, effective account, and model.

The first release makes three capability differences explicit:

| Feature | Claude | Codex in the first release |
| --- | --- | --- |
| `max_turns` | Existing native enforcement | Unsupported; reject an explicit request |
| Model access preflight | Existing tool-free CLI check | Syntactic validation only; access errors become failed task results |
| `ask` on a Codex-only instance | Requires Claude's summary service | Unsupported; refuse before executing the producer |

Neither local `codex exec --help` nor the fetched CLI reference establishes an equivalent to Claude's maximum agent-turn count.
Do not confuse Codex's terminal `turn.completed` with Claude's internal agent-loop turns, and do not reinterpret turns as tokens or wall time.
If `mcp.delegate-max-turns` is configured, Codex delegation and continuation are refused because the server cannot enforce that ceiling.
Discovery reports this refusal before a coordinator tries to branch a worker.
The user would have to revise that policy explicitly to use Codex in the first release.
A future provider-independent time budget needs a guest-side watchdog that survives host disconnects and kills marked descendants; it must be designed separately from turn accounting.

Do not run a normal tool-enabled Codex invocation as a supposedly harmless model preflight or summarizer.
Changing its filesystem sandbox to read-only would still leave tool and network behavior unlike `AskScript.oneShot()`'s tool-free contract.
For Codex, remove the model-preflight promise from the tool description and advertise `supports_model_preflight=false`.
An account or model failure is visible in `task_result`, and does not trigger an automatic switch or paid retry.
Later support can use a verified tool-free Codex interface or a bounded guest-side Responses request through the existing proxy.

`ask` remains available where the template supplies Claude, using the existing configured summary model and advertised `summary_agent`.
On a Codex-only instance, reject `ask` as `invalid_argument` before `exec` runs its command or any summary work begins.
Raw `task_result`, capped output, and `get_diff(stat)` remain available without Claude.

The proxy currently counts Anthropic model calls, while its domain traffic counters already cover OpenAI requests.
The first release must label that coverage in `instance_activity` so zero model tokens cannot be mistaken for an idle or free Codex task.
Add `model_usage_providers: ["anthropic"]` to the activity result and explain that it describes measured coverage, not installed agents.
Use Codex's task events for reported task token usage and existing per-domain traffic for network activity.
Responses usage accounting in the proxy is follow-up work; the adapter must omit dollar cost when the CLI does not report it.

## Implementation and release acceptance

The implementation covers three areas:

1. Separate agent selection and output interpretation without changing Claude behavior.
   Add selection and discovery contracts, versioned metadata readers, and legacy-task tests.
2. Add the Codex runner, policy checks, session and report persistence, continuation, presence detection, and replay handling.
   Complete cancellation, orphan, fork, and adoption coverage before enabling discovery.
3. Offer Codex templates in `InitCommand`, update MCP instructions and the coordination prompt, and document capability differences in README and the website.
   Update `OutputSchemas`, the golden schemas, activity coverage, audit fields, DESIGN.md, and contributor rules in the same release.

The approval UI still registers the existing Claude coordinator and asks the user which templates it may use.
It must offer Codex-only worker templates without requiring Claude inside them or changing any approvals automatically.
No changes to `McpBridge`, credential exposure, ownership checks, trusted definitions, or the MCP no-host-writes rule are needed.

Validation must cover:

- Claude-only, Codex-only, both-agent, inherited-agent, and no-agent templates; explicit selection; stale binaries; model defaults; and restrictive or unsupported policy.
- Stub executables under real bash and Node for initial run, stdin follow-up, quoting, non-ASCII prompts, empty and malformed events, oversized records, missing thread IDs, and terminal failures.
- A final report larger than the status tail, a thread event emitted long before interruption, independent stdout and stderr, atomic-write failures, and hostile FIFO or symlink task files.
- Coordinator exit and adoption while Codex is running, restart before first output, cancellation of grandchildren, dormant stop and resume, human takeover, and legacy Claude task adoption.
- Keyed replay with conflicting or omitted agent, template-default changes, an interruption between branch and launch, and forks with cleared task records.
- Mixed Claude and Codex capacity limits, notifications, all successful structured results against their schemas, and refusals without `structuredContent`.
- `ask` refusal before an `exec` side effect, unchanged Claude summaries and model preflight, and activity responses that disclose incomplete provider coverage.

Before release, use the repository's real-Incus validation workflow with a template containing Codex and no Claude binary or Anthropic credentials.
Exercise a Claude coordinator on the host and one in a `--mcp-client` instance.
Have each create a worker, change a file, run a test, inspect the report and diff, send a follow-up, recover after disconnect, cancel work, and hand over the saved conversation with `codex resume <id>`.
Verify the `openai` account route and login-token refresh without printing credentials.
Pin the minimum supported Codex version from these checks; `0.162.1` is the locally inspected candidate, not a compatibility guarantee for untested older versions.
Run the relevant MCP, config, init, schema, and guest-script tests, and retain native startup and Incus request-budget checks.

The implementation exposes both worker agents through the existing MCP tools, with the stated feature differences discoverable before use.
The validation checklist above distinguishes deterministic lifecycle coverage from a live model and credential smoke test.

### Validation in this checkout

The MCP, task lifecycle, config, init, output-schema, guest-script, request-budget, fork, and Codex setup tests passed.
The guest-script tests exercise real bash and Node with deterministic Codex executables, including exact-session continuation, interrupted ID recovery, large reports, malformed and oversized events, hostile task files, and cancellation.
Real Incus 6.23 validation used a disposable Fedora 44 guest with Node 22 and a deterministic Codex stand-in, without Claude installed.
Initial execution, file changes and diff capture, follow-up execution, persisted identity, continuation after an instance restart, and cancellation of a child process across the systemd/PAM boundary passed.
The nested host cannot build ordinary templates without changing unrelated device and disk settings, so this check launched the generated task scripts in a plain guest.
Live model calls, OpenAI credential routing and refresh, and a native startup benchmark were not exercised here.
The live coordinator and credential smoke test above remains a release check.
