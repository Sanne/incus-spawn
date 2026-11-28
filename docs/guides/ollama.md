---
description: Run the Pi coding agent in an isx sandbox on a local model from Ollama on your host, with no cloud account and no model server inside the machine.
---

# Use a local model with Ollama in an isx sandbox

This guide takes you from an installed isx and an Ollama on your host to an agent in an isx machine answering from your local model.
The agent is Pi, which can use any model server that speaks OpenAI's API.
The model runs on your host, and the machine needs no account.

## Before you start

You need isx installed and set up with `isx init`: the [Quick Start](../../README.md#quick-start) covers both.
You also need [Ollama](https://ollama.com/download) running on your host, with a model pulled.
Pi works through tool calls, so pull a model that makes them, such as Qwen3:

```shell
ollama pull qwen3:4b
```

Ollama answers on port 11434 of your host's loopback, and `ollama list` names the models you have.

Give the model a context of at least 32768 tokens, by setting `OLLAMA_CONTEXT_LENGTH` where Ollama runs.
Without much video memory, Ollama's default is too small for an agent, and Ollama drops the start of a longer conversation without telling Pi.
Ollama's [context length](https://docs.ollama.com/context-length) page says where to set it for each way of running Ollama.

## Expose Ollama's port

Add Ollama's port to `~/.config/incus-spawn/config.yaml`:

```yaml
exposed-host-ports: [11434]
```

The proxy picks the change up by itself.
Every machine with network then reaches Ollama at `host.isx.internal:11434`.
Ollama's API has no authentication, so read [What exposing a port gives away](../CONFIGURATION.md#what-exposing-a-port-gives-away) before you keep it.

## Tell Pi where Ollama is

Pi learns about a model server from its `models.json` file, and a small tool of your own writes it into the template.
Create the tool:

```shell
isx tools new ollama-models
```

Your editor opens a short file.
Replace it with this:

```yaml
# yaml-language-server: $schema=https://isx.run/schema/tool.json
name: ollama-models
description: Ollama on the host, as a provider in Pi's models.json
files:
  - path: /home/agentuser/.pi/agent/models.json
    merge: json
    owner: agentuser:agentuser
    content: |
      {"providers": {"ollama": {
        "baseUrl": "http://host.isx.internal:11434/v1",
        "api": "openai-completions",
        "apiKey": "ollama",
        "models": [{"id": "qwen3:4b", "contextWindow": 32768}]
      }}}
```

Put each model you pulled in `models`, by the name `ollama list` shows.
Its `contextWindow` is the context you gave Ollama, so Pi compacts a long conversation before Ollama would cut it.
Ollama ignores the `apiKey`, but Pi offers no model without one.
[File entry fields](../TOOLS.md#file-entry-fields) says what `merge: json` keeps of the file, and Pi's [models documentation](https://docs.pi.dev/latest/models) lists the other fields.

When you close the editor, isx checks the tool and names any field it cannot use.

## Make the template

Create a template for it:

```shell
isx templates new tpl-ollama
```

Your editor opens a short file.
Replace it with this:

```yaml
name: tpl-ollama
description: Pi on a local model from Ollama on the host
parent: tpl-minimal
tools:
  - pi:
      provider: ollama
      model: qwen3:4b
  - ollama-models
default-action: pi
```

`provider` names the provider your tool added, and `model` the model Pi starts with.
[Pi Coding Agent](../AGENTS.md#pi-coding-agent) on the Agents page lists its other parameters.
[Write an isx template for your project](project-template.md) adds your project's code and packages to a template like this one.

Build it:

```shell
isx build tpl-ollama
```

## Branch a machine and ask the model

Make a machine from the template:

```shell
isx branch local-model --from tpl-ollama
```

Pi opens inside it, on your local model.
Ask it something, such as "Which files are in this directory?".
Pi runs `ls` in the machine and answers from what it lists.
On a host without a GPU, an answer can take a minute.

## Check that it works

Pi's footer names the provider and the model, here `ollama` and `qwen3:4b`.
Run this in a shell in the machine, from `isx shell local-model`, to ask Ollama directly:

```shell
curl http://host.isx.internal:11434/api/tags
```

It lists the models your host's Ollama has.
On your host, Ollama's log shows a `POST "/v1/chat/completions"` line for each answer Pi asked for.
Where that log is depends on how you run Ollama: on Linux, `sudo journalctl -u ollama` shows it.

## Clean up

Destroy the machine when you are done with it:

```shell
isx destroy local-model
```

To close the port again, remove `exposed-host-ports` from `config.yaml`.

## Next steps

- Give the template your project: [Write an isx template for your project](project-template.md).
- Take the agent's commits back to your host: [Review and take an agent's commits](agent-commits.md).
- Run several machines at once: each `isx branch` is a machine of its own, and all of them use the one Ollama on your host.
- A [network mode](../../README.md#network-modes) that limits a machine to the proxy, or cuts it off, also keeps it from Ollama: [A service on the host](../CONFIGURATION.md#a-service-on-the-host) has the table.
