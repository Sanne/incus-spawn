# isx: Vision

Where the project is going and why.
[CHARACTER.md](dev/CHARACTER.md) describes what isx is: its mission and design philosophy.
This page describes what it is becoming.
Nothing here is a committed roadmap: the concrete work is tracked in issues, linked throughout.

## The problem, restated for the next few years

Today a developer runs one AI coding agent and babysits it in a terminal.
That is already changing: developers increasingly run several agents in parallel, and the human's job shifts from typing alongside one agent to dispatching tasks and reviewing results.
Every part of that future workflow raises the same questions:

- Where does each agent run, and what can it damage?
- Whose identity is it using, and can its actions be traced back to the agent rather than to you?
- How do you see, at a glance, which of your agents needs attention?
- How do you review and accept work you did not watch being written?

isx's answer: onboard agents the way you would onboard a new teammate.
Each agent gets its own disposable, full-fidelity Linux workstation, its own credentials and its own audit trail.
You review its work as commits, the same way you would review any teammate's pull request, before anything touches your host or your repos.

## Three theses

### 1. Agents are principals, not processes

An agent should not act as you.
It should have its own identity: its own scoped API token, its own GitHub identity, its own commit-signing key.
These are held outside the environment it works in, so even a fully compromised agent cannot exfiltrate them, and every action is attributable to the specific agent that performed it.

The MITM credential-isolation proxy already guarantees the "held outside" half: no key, token or credential ever enters an instance in any form.

The per-instance identity half is partly delivered.
Credential accounts map each instance to its own tokens, such as its own Claude API key and GitHub identity, chosen at branch time and resolved by the proxy from the caller's address.

Still to come is [#271](https://github.com/Sanne/incus-spawn/issues/271): SSH-agent-filtered commit signing, where the instance can request a signature for exactly one designated key.
Each request is namespace-checked to git signing only and audited, and the private key never leaves the host, or the hardware enclave.

The per-instance account mechanism is deliberately general: per-agent API keys and GitHub identities are its first consumers, and signing keys and budgets ride on the same foundation.

### 2. The proxy is the control plane

The credential proxy began as a security mechanism, but its position makes it something more: every API call every agent makes passes through it.
That single vantage point yields, with zero instrumentation inside instances:

- **Activity**: an agent that is streaming API calls is working; one that stopped mid-session is waiting for input; one that went quiet is done or stuck.
- **Spend**: live token usage per agent, attributable per instance.
- **Audit**: a complete record of what each agent did with the identity it was issued.
- **Policy**: per-agent domain allowlists, rate and budget caps, enforced at a layer the agent cannot reach.

Alternatives need SDK cooperation or in-sandbox monitors to approximate any of this.
isx gets it architecturally, because the observation point sits outside the trust boundary, and for any agent: Claude Code, Pi, or whatever comes next.

### 3. The TUI grows into mission control

isx's primitives map one-to-one onto the dispatch-and-review workflow:

| Primitive | Mission-control meaning |
|---|---|
| Template | The standardized workstation issued to every agent |
| Branch | One agent, one task, one disposable workstation |
| Credential accounts | The agent's identity: its token, its signing key, its budget |
| `isx://` remote | The deliverable: an immutable commit you review before merging |
| Destroy | Offboarding: workstation, credentials, and remotes cleaned up atomically |

The TUI evolves from an instance manager into the place where you dispatch tasks, watch agent status (derived from proxy traffic), inspect diffs, and accept or reject work.
The concrete shape is brainstormed in [#322](https://github.com/Sanne/incus-spawn/issues/322).
The review model, always an immutable commit and never a live shared directory, is already the project's position: see the [FAQ](FAQ.md#why-cant-i-mount-a-host-directory-read-write-to-follow-agent-work-in-my-ide) on read-write mounts.

## Local-first, by conviction

The major agent platforms are converging on cloud execution.
isx deliberately runs the other way: your hardware, your network, your private repos, and nothing leaves the building.

This is not nostalgia.
It is the requirement of sovereignty-sensitive organizations that cloud agent farms structurally cannot serve: regulated industries, the public sector, security-conscious engineering orgs.
It is also what individual developers want when they simply want their code and credentials to stay theirs.

Local-first does not mean local-only.
The same control plane (templates, identities, proxy, review flow) should eventually be able to dispatch agents to remote hardware you control: a team's shared agent-farm box running Incus.

The architecture keeps that door open: the client already speaks to Incus over a transport abstraction, and the proxy design is host-agnostic.
But the trust model never changes: the credentials and the control plane stay on infrastructure you own.

## Sequencing

isx is evolving from an instance manager into mission control for parallel coding agents.
These are the priorities, in order: each stage unblocks the next.

1. **Stability and supportability**: done.
   Full-stack `isx doctor` and a redacted support bundle ([#321](https://github.com/Sanne/incus-spawn/issues/321)).
   A tool that promises safety must be dependable, and a project seeking adoption must be supportable without screen-sharing.
2. **Identity foundation**: in progress.
   Per-instance identities have begun with [credential accounts](CONFIGURATION.md#credential-accounts), which have shipped and resolve [#281](https://github.com/Sanne/incus-spawn/issues/281): each instance can use its own Claude and GitHub accounts, covering API keys and GitHub identities.
   Filtered commit signing remains ([#271](https://github.com/Sanne/incus-spawn/issues/271)): audited, without exposing private keys.
3. **Observability.**
   Proxy-derived monitoring of agent status, activity and spend, surfaced in the TUI: the first visible slice of mission control ([#322](https://github.com/Sanne/incus-spawn/issues/322)).
4. **Dispatch and review.**
   Task dispatch onto fresh branches, and an in-TUI review lane: diff review and accept or reject from the TUI ([#322](https://github.com/Sanne/incus-spawn/issues/322)).
   Dispatch is also coming to agents themselves, through the experimental `isx mcp` server ([#859](https://github.com/Sanne/incus-spawn/issues/859)).
   - A coordinating agent on your host creates instances from templates you approved, runs commands, builds and tests in them, delegates whole tasks to the agents inside, and reads back their diffs.
   - What a coordinator further needs, including proxy-derived activity per instance, is tracked in [#898](https://github.com/Sanne/incus-spawn/issues/898).
5. **Policy.**
   Per-agent domain sets, network modes, and budget caps as dispatch-time knobs.

Throughout, local-first stays the core conviction: your hardware, your network, your repos.
And the character defined in [CHARACTER.md](dev/CHARACTER.md) holds: strong opinions on the core (security, fidelity, speed), flexibility at the edges (tools, templates, integrations).
