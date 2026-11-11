# Validating the agent install path

`scripts/agent-install/validate.sh` checks the agent-driven install of plan #2072 on a clean Linux host (#2081).
It gives a coding agent only an install prompt, as a fresh user who cannot run sudo, and plays the person's part by script.
Run it again for every release that changes `isx init`, `isx mcp` or the install docs.

## What it checks

The agent must reach a first branch while stopping at exactly three handovers: the root script, a credential and the MCP approvals.
The harness answers each one as the person would:

- When `isx init --no-sudo` wrote a new `~/.local/state/incus-spawn/init-privileged.sh`, it runs that script with sudo.
- When the agent names `isx account add <namespace>`, it adds that credential.
- When the agent asks for approvals, it writes `mcp.yaml` with the built templates the agent named.

Anything else gets "nothing to do", which the report counts as a turn where the agent was stuck or guessing.
`report.md` lists the turns and their times, the person's actions, every sudo the agent tried (from its commands and the journal), replies that may ask for a secret, every write of `mcp.yaml`, and `isx init --plan` at the end.
The heuristics are a first pass: read `reply-<turn>.txt` and `commands.tsv` before reporting a finding.

## Running it

```shell
scripts/agent-install/validate.sh --agent claude            # the agent installs isx from isx.run
scripts/agent-install/validate.sh --agent codex --isx-from /opt/isx-rc --out ~/run-codex
```

`--isx-from` is for a build that no install channel serves yet.
The directory holds `bin/isx`, `bin/isx-proxy` and `docs/` (the repository's `docs/` without `docs/dev/`, `README.md` and `site/llms.txt`), and the prompt then tells the agent to read those docs instead of isx.run.
The harness refuses a `docs/dev/` there: this page would tell the agent how the person answers.
A JVM build serves: launchers in `bin/` that run `java -jar` on copies of `cli/target/quarkus-app` and `proxy/target/quarkus-app`.

The host must not have Incus, since the first run is the one that matters.
A second agent therefore needs a second clean host.

## Inside an isx instance

The person's account joins the group that reads `/run/isx`, so the instance's proxy serves the agent.
A credential the agent asks for is answered with the reserved account `inherit`, which `isx account list` does not show.
`/etc/claude-code/CLAUDE.md`, `/etc/claude-code/managed-settings.json`, `/etc/isx/agent-instructions.md`, `/AGENTS.md` and `/etc/codex/requirements.toml` are masked for the agent, since a new user's host has none.

A clean VM nested in an instance that already has Incus works too.
Give it a bridge of its own without isx's redirect (`incus network create`, then add it to firewalld's `trusted` zone).
Launch `images:fedora/45` with `--vm`, and grow its root partition and filesystem, which the image does not do: a 4 GB root fills on the first template build.
Push the instance's `/etc/pki/ca-trust/source/anchors/isx-*.crt` (then `update-ca-trust`), its `/run/isx/` files (group-readable by one group) and `/etc/profile.d/isx-instance-secret.sh`.
Its traffic leaves through the instance's address, so the outer proxy serves it.
Push the files again after the VM restarts, since `/run` is empty then.
