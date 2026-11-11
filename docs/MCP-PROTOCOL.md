# Driving `isx mcp` from a program

A program of your own can drive `isx mcp` from code rather than from a model: a scheduler that hands tasks to instances, or a program that recovers from its own crashes.
This page is the reference for what such a program relies on: structured results, task states, notifications, safe retries and the activity counters.

[Delegating from an agent on your host](MCP.md) covers setup, approving templates and what an agent gets; everything there applies here too.

## Structured results and refusals

Every tool declares an `outputSchema` and returns its facts as matching `structuredContent`, so a program need not parse the result's text.
The result's text stays for the model.

A refusal carries no `structuredContent`.
It says why in `_meta["dev.incusspawn/error"].code`.

`list_templates` reports the limits of `mcp.yaml` as `max_instances`, `max_concurrent_tasks` and `orphan_grace_hours`.

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
| `agent` | `claude`, `codex` or `pi`. |
| `model` | The template's configured model. |
| `supports_model_preflight` | Whether a chosen `model` is checked against the account before launch. Claude only; Codex and Pi report model-access errors in the task result. |
| `permission_mode`, `approval_policy` | Always full access inside the instance: `bypassPermissions` for Claude, `danger-full-access` with approval policy `never` for Codex, `full-access` for Pi. |

A task's `model` override persists across `send_message` and adoption.
A Pi task has a `session_id` only once Pi has saved its session; before that, or once that session is gone, `send_message` is refused with `wrong_state`.

No agent has a turn limit.
Older releases took `max_turns` on `delegate` and `send_message` and reported `supports_max_turns`, `refusal`, `delegate_max_turns` and `max_turns`; these no longer exist, and a call passing `max_turns` is refused with `invalid_argument`.

## Being told when a task changes

A client can ask to be told of task changes instead of polling.
List `isx/task_changed` under `capabilities.experimental` in `initialize`.

Every change of a task's state then arrives as a `notifications/isx/task_changed`, carrying `task_id`, `instance`, `run`, `state` and, once finished, `exit_code`.
The `state` in a notification is `running`, `finished`, `lost`, `attached` or `released`.
`released` means another session took the task's instance, with `adopt_instance`.
For a client that does not ask, isx does not poll the tasks.

## Retrying safely after a crash

A program that recovers from a crash by repeating the calls it has no result for can make each repeat safe.
`create_instance`, `delegate` and `exec` with `background: true` take an optional `idempotency_key`: 1 to 64 characters of `A-Za-z0-9._:-`.

A call whose key already made something returns that instance or task, with `replayed: true`, instead of making a second one.
This holds across sessions too.
An orphan is an instance whose session ended, and the call adopts an orphan made under the key.

isx keeps the key exactly as long as the instance or task it made exists.

A repeat must ask for the same thing: the same template or source, instance and kind of task.
A repeat that asks for something else is refused, never redirected.
So a program that picks the instance at run time must save that choice with its record of the call it intends to make.

A key whose instance was handed over to a person with `keep_instance` is refused; look the instance up instead of retrying.

## Telling working from stuck

`instance_activity` reports what the proxy on the host counted of an instance's traffic, and does not run anything in the instance.

A model call in flight means the agent is working.
A growing `idle_seconds` means the agent is stuck, or finished without reporting.
An agent with no model call in flight but a build pulling dependencies is still working, which the `traffic_*` counts show.

| Field | What it holds |
|-------|---------------|
| `input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens` | Tokens spent, as the API reports them, from `counting_since`. |
| `counting_since` | When the proxy began counting that instance. Absent while the proxy does not know the instance yet. |
| `requests`, `requests_in_flight` | Model calls made, and in flight. |
| `last_request_at`, `last_response_at` | When the last model call started and ended. |
| `idle_seconds` | How long since the last model call ended. Present only while no model call is in flight: a long streamed call is work, not idleness. |
| `model_usage_providers` | Whose model calls the counts above cover. Anthropic only; OpenAI calls appear in `traffic_*`. |
| `traffic_*` | Every request the instance made through the proxy from the same `counting_since`, model calls included: package downloads, git, WebSockets (in flight while open). |

For what a task spent, read the counts before and after the task and subtract, but only when both reads carry the same `counting_since`.
A different or missing `counting_since` means the proxy restarted between the two reads and the counts started again from zero.
`isx proxy activity` shows the same counts for every instance on the host.

Codex reports its own token usage in the task result when available, and Pi its turns, tokens and `cost_usd`.
