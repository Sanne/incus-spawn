# Validating the agent install path

`scripts/agent-install/validate.sh` checks the agent-driven install of plan #2072 on a clean Linux host (#2081).
It gives a coding agent only the prompt that [Install with your agent](../AGENT-INSTALL.md) publishes, as a fresh user who cannot run sudo, and plays the person's part by script.
It reads the prompt from that page's "The prompt" section, so a change to the page is what the next run tests; `--print-prompt` shows it, and `AgentInstallPromptTest` keeps the two joined.
Run it again for every release that changes `isx init`, `isx mcp` or the install docs.

## What it checks

The agent must reach a first branch while stopping at exactly three handovers: the root script, a credential and the MCP approvals.
The run passes, and `validate.sh` exits 0, only when the branch's template installs the agent's own isx tool and `isx` is in the agent's own MCP settings as well (#2178).
It also needs `list_templates` answered over that registration after the approval, without an error, so a registration that cannot reach Incus fails the run.
When the agent made no such call, the harness asks it for one in a last turn, which starts it afresh with the same groups as its other turns.
The harness finds the tool by following the branch's template chain through each definition's file, and the registration in the agent's settings file, without starting the server.
The harness answers each one as the person would:

- When `isx init --no-sudo` wrote a new `~/.local/state/incus-spawn/init-privileged.sh`, it runs that script with sudo.
- When the agent hands over a script it wrote itself, as `sudo sh <path>` or `sudo bash <path>` in the home or `/tmp`, it runs that one too, with the shell named.
  The report names it as the agent's when the person owns the file.
- When the agent names `isx account add <namespace>`, it adds that credential.
- When the agent asks for approvals, it writes `mcp.yaml` with the templates the agent named, or all of them when it names none, built or the person's own.
  It never registers `isx mcp` in the agent's client, although `isx mcp setup` does that for Claude Code: registering is the agent's step, and a Claude Code run passes only when the agent took it (#2178).
- When the agent asks which agent to put in the template, it names the isx tool given with `--template-agent`.
  An agent isx has a tool for should not need to ask, so for those the question is a finding.

The agent keeps the groups it had when the run started, as a running agent does, so the `incus-admin` group the root script adds reaches it only through `sg` (`--fresh-groups` gives it a new login each turn instead).
Anything else gets "nothing to do", which the report counts as a turn where the agent was stuck or guessing.
`report.md` lists the turns and their times, the person's actions, every sudo the agent tried (from its commands and the journal), replies that may ask for a secret, every write of `mcp.yaml`, and `isx init --plan` at the end.
The heuristics are a first pass: read `reply-<turn>.txt` and `commands.tsv` before reporting a finding.

## The agents it drives

`--agent` takes `claude`, `codex`, `pi`, `gemini`, `opencode` and `copilot`, each run headless with every tool call allowed.
`opencode` has no isx tool, so it is the run that exercises the question: give it `--template-agent` (for example `codex`), and the run then expects that tool in the template.
Pi runs on the OpenAI account, its default provider in `~/.pi/agent/settings.json` (Pi 1.5 refuses `--provider` without `--model`), since upstream refuses Pi on a Claude subscription.
`--agent-model` picks the model for `pi`, `gemini`, `opencode` and `copilot`, where their default is not available.
Bob Shell is not driven: its headless `bob run` has no flag that approves tool calls, and no approval setting was confirmed to apply to it.
The event shapes the harness reads for Pi and opencode were checked against recorded runs (#2178).
Those for Gemini CLI and GitHub Copilot CLI come from their sources and docs alone, so check `commands.tsv` against `turn-<n>.jsonl` after a first run of each.

## Agents that passed

The install page names only the agents in this table (#2178), and `AgentInstallPromptTest` holds the two together.
An agent is added once a clean-VM run with the page's prompt, unedited, reached a first branch, its own agent's tool in that branch's template, `isx` in its own MCP client, and `list_templates` answered there, with the three handovers and nothing else.
Record the run here, then add the agent's name to the page's "tested on a clean host" sentence.

| Agent | Version | isx | Date | Run |
|---|---|---|---|---|
| Claude Code | 2.1.322 | 0.5.1 | 2026-11-12 | https://github.com/Sanne/incus-spawn/pull/2192 |
| Codex CLI | 0.167.2 | 0.5.1 | 2026-11-12 | https://github.com/Sanne/incus-spawn/issues/2178#issuecomment-6835241625 |
| Pi | 1.5.2 | 0.5.1 | 2026-11-12 | https://github.com/Sanne/incus-spawn/issues/2178#issuecomment-6835241625 |

## How clear the instructions were

Reaching a first branch is not enough: an agent that got there by guessing shows the instructions are unclear.
So the report has two more sections, kept apart, and #2081 wants a clarity report per agent built from them.

- **The transcript**: commands that failed (`commands.tsv` has each one's outcome), `--help` lookups, web searches and downloads, commands run more than once, and which pages the agent named in a command and which it never opened.
  This is the evidence.
- **The debrief**: once the run is over, the harness asks the agent a fixed set of questions (in `validate.sh`, `DEBRIEF_PROMPT`): gaps it filled, extrapolations, wrong guidance it backed off from, ambiguities, what it read, and what it would change.
  It is asked only afterwards, since asking for notes during the run would change how the agent reads the instructions.
  Its answer is `debrief.txt`, and where it disagrees with the transcript the transcript wins.

Write each finding with its fix: the install page, `llms.txt`, or a command's own output, which is preferred because it reaches an agent that never read the docs.
Then run again, until a run fills no gap that mattered.
`--no-debrief` leaves the debrief out.

## Running it

```shell
scripts/agent-install/validate.sh --agent claude            # the agent installs isx from isx.run
scripts/agent-install/validate.sh --agent codex --isx-from /opt/isx-rc --out ~/run-codex
scripts/agent-install/validate.sh --agent claude --docs /opt/isx-docs   # a released isx, docs not yet on isx.run
```

`--isx-from` is for a build that no install channel serves yet.
`--docs <dir>` is for a release that a channel serves before isx.run publishes its docs: the agent installs isx itself and reads the docs in `<dir>`, laid out as the `docs/` of `--isx-from` below.
The person's account must be able to read either directory.
The directory holds `bin/isx`, `bin/isx-proxy` and `docs/` (the repository's `docs/` without `docs/dev/`, `README.md` and `site/llms.txt`), and the prompt then tells the agent to read those docs instead of isx.run.
The harness refuses a `docs/dev/` there: this page would tell the agent how the person answers.
A JVM build serves: launchers in `bin/` that run `java -jar` on copies of `cli/target/quarkus-app` and `proxy/target/quarkus-app`.

The host must not have Incus, since the first run is the one that matters.
A second agent therefore needs a second clean host.

## Inside an isx instance

`scripts/agent-install/run-in-vm.sh` does the setup below for you: it launches a fresh nested VM, prepares it, runs `validate.sh` in it with the options after `--`, copies the results out and deletes the VM.

```shell
scripts/agent-install/run-in-vm.sh --agent codex --out ~/runs/codex -- --docs /tmp/isx-docs
scripts/agent-install/run-in-vm.sh --agent opencode --out ~/runs/opencode -- --template-agent codex --agent-model <provider/model>
```

The person's account joins the group that reads `/run/isx`, so the instance's proxy serves the agent.
A credential the agent asks for is answered with the reserved account `inherit`, which `isx account list` does not show.
`/etc/claude-code/CLAUDE.md`, `/etc/claude-code/managed-settings.json`, `/etc/isx/agent-instructions.md`, `/AGENTS.md` and `/etc/codex/requirements.toml` are masked for the agent, since a new user's host has none.

A clean VM nested in an instance that already has Incus works too.
Give it a bridge of its own without isx's redirect (`incus network create`, then add it to firewalld's `trusted` zone).
Launch `images:fedora/45` with `--vm`, and grow its root partition and filesystem, which the image does not do: a 4 GB root fills on the first template build.
Push the instance's `/etc/pki/ca-trust/source/anchors/isx-*.crt` (then `update-ca-trust`), its `/run/isx/` files (group-readable by one group) and `/etc/profile.d/isx-instance-secret.sh`.
Its traffic leaves through the instance's address, so the outer proxy serves it.
Push the files again after the VM restarts, since `/run` is empty then.
