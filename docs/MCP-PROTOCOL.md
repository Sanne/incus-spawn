# Driving `isx mcp` from a program

A controller of your own can drive `isx mcp` from code rather than from a model: a scheduler that farms tasks out to instances, or a coordinator that recovers from its own crashes.
This page is the reference for what such a program relies on: structured results, task states, notifications, safe retries and the activity counters.

[Delegating from an agent on your host](MCP.md) covers setup, approving templates and what an agent gets; everything there applies here too.

## Structured results and refusals

Every tool declares an `outputSchema` and returns its facts as matching `structuredContent`, so a program need not parse the text.
The text stays for the model.

A refusal carries no `structuredContent`.
It says why in `_meta["dev.incusspawn/error"].code`.

`list_templates` reports the limits of `mcp.yaml` as `max_instances`, `max_concurrent_tasks`, `orphan_grace_hours` and, when set, `delegate_max_turns`.

## Task states

A task's `state` is the same word in every tool.

| State | Meaning |
|-------|---------|
| `running` | The task is in progress. |
| `finished` | It ended; `exit_code` says how. |
| `attached` | It finished, and a person is in its conversation with `isx shell --task`. |
| `lost` | The instance no longer has a record of the task. |
| `unknown` | Nothing has read how the task ended yet, as after `cancel_task`. |

`list_instances` gives the state as the session last saw it, so `unknown` appears there.
`task_status` reads it from the instance.
`task_status.join_command` holds the exact `isx shell --task` command for a person to join the conversation.

## Delegate descriptors

`list_templates` reports, for each template, `default_delegate_agent`, `supports_ask` with `summary_agent`, and under `delegate_agents` a descriptor per installed agent.

| Field | What it holds |
|-------|---------------|
| `agent` | `claude` or `codex`. |
| `model` | The template's configured model. |
| `supports_max_turns` | Whether `delegate` accepts `max_turns`. Claude only. |
| `supports_model_preflight` | Whether a chosen `model` is checked against the account before launch. Claude only; Codex reports model-access errors in the task result. |
| `permission_mode`, `approval_policy` | Claude's permission mode, or Codex's sandbox mode and approval policy, as `mcp.yaml` gives this template. |
| `refusal` | Why current host policy prevents launching this agent, when it does. |

A task's `model` override persists across `send_message` and adoption.
`max_turns` on `delegate` to Codex is refused, and a `delegate-max-turns` in `mcp.yaml` refuses Codex delegation and continuation.

## Being told when a task changes

A client can ask to be told of task changes instead of polling.
List `isx/task_changed` under `capabilities.experimental` in `initialize`.

Every change of a task's state then arrives as a `notifications/isx/task_changed`, carrying `task_id`, `instance`, `run`, `state` and, once finished, `exit_code`.
The `state` there is `running`, `finished`, `lost`, `attached` or `released`.
`released` means another session took the task's instance, with `adopt_instance`.
Clients that do not ask are not polled for it.

## Retrying safely after a crash

A controller that recovers from a crash by re-running what it has no result for can make that safe.
`create_instance`, `delegate` and `exec` with `background: true` take an optional `idempotency_key`: 1 to 64 characters of `A-Za-z0-9._:-`.

A call whose key already made something returns that instance or task, with `replayed: true`, instead of making a second one.
This holds across sessions too: an orphan made under the key is adopted.

The key lives exactly as long as what it made.
A repeat must ask for the same thing: the same template or source, instance and kind of task.
One that asks for something else is refused, never redirected, so a controller that picks the instance at run time records that choice with its intent.

A key whose instance was kept with `keep_instance` is refused; look it up instead of retrying.

## Telling working from stuck

`instance_activity` answers from the host proxy's view, without touching the instance.
A model call in flight means the agent is working.
A growing `idle_seconds` means it is stuck, or finished without reporting.
An agent with no model call in flight but a build pulling dependencies is still working, which the traffic counts show.

| Field | What it holds |
|-------|---------------|
| `input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens` | Tokens spent, as the API reports them, from `counting_since`. |
| `counting_since` | When the proxy began counting that instance. Absent while the proxy does not know the instance yet. |
| `requests`, `requests_in_flight` | Model calls made, and in flight. |
| `last_request_at`, `last_response_at` | When the last model call started and ended. |
| `idle_seconds` | How long since the last model call ended. Present only while none is in flight: a long streamed call is work, not silence. |
| `model_usage_providers` | Whose model calls the counts above cover. Anthropic only; OpenAI calls appear in `traffic_*`. |
| `traffic_*` | Every request the instance made through the proxy from the same `counting_since`, model calls included: package downloads, git, WebSockets (in flight while open). |

For what a task spent, read before and after it and subtract, but only when both reads carry the same `counting_since`.
A different or missing one means the proxy restarted in between and the counts started afresh.
Codex reports its own token usage in the task result when available.
`isx proxy activity` shows the same counts for every instance on the host.
