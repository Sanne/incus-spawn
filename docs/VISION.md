# isx: Vision

Where the project is going and why.
The project's character, meaning its mission and design philosophy, describes what isx is.
This page describes what isx is becoming.
Nothing here is a committed roadmap: the concrete work is tracked in issues, which this page links.

## The problem, restated for the next few years

Today a developer runs one AI coding agent and supervises it in a terminal.
That is already changing: developers increasingly run several agents in parallel, and the human's job shifts from typing alongside one agent to dispatching tasks and reviewing results.
Every part of that future workflow raises the same questions:

- Where does each agent run, and what can it damage?
- Whose identity is it using, and can its actions be traced back to the agent rather than to you?
- How do you see, at a glance, which of your agents needs attention?
- How do you review and accept work you did not watch being written?

isx's answer: onboard agents the way you would onboard a new teammate.
Each agent gets its own disposable, full-fidelity Linux workstation.
You can give it its own API keys and GitHub account, and those credentials stay on your host.
Its own audit trail is still to come.

You fetch each agent's work as commits and review them the way you would review any teammate's pull request, before you merge any of it.

## Three theses

### 1. Agents are principals, not processes

An agent should not act as you.
It should have its own identity: its own scoped API token, its own GitHub identity, its own commit-signing key.

These credentials are held outside the instance the agent works in, so even a fully compromised agent cannot exfiltrate them.
An identity of its own is what makes each action traceable to its agent.

The "held outside" part is delivered: isx never puts a key, token or credential into an instance, in any form.
They stay on your host, and the proxy there adds them to requests as the requests leave an instance.

The other part, an identity per instance, is partly delivered.
Credential accounts map each instance to its own tokens, such as its own Claude API key and GitHub identity.
You choose the accounts when you branch.
The proxy applies that choice on the host, where the agent cannot change it.

Still to come is [#271](https://github.com/Sanne/incus-spawn/issues/271): SSH-agent-filtered commit signing, where the instance can request a signature for exactly one designated key.
Each request is checked to be for git signing only, and is audited.
The private key never leaves the host, or the hardware enclave when one holds it.

The per-instance account mechanism is deliberately general: per-agent API keys and GitHub identities are its first consumers, and signing keys and budgets are meant to use the same mechanism.

### 2. The proxy is the control plane

The credential proxy began as a security mechanism, but its position gives it a second use: every request an agent makes with a credential isx holds, model calls included, passes through the proxy.
Because the proxy sees each of these requests, its position is enough to provide the following, without any instrumentation inside the instances:

- **Activity**: an agent that is streaming API calls is working; one that stopped mid-session is waiting for input; one that went quiet is done or stuck.
- **Spend**: live token usage per agent, attributable per instance.
- **Audit**: a complete record of what each agent did with the identity it was issued.
- **Policy**: per-agent domain allowlists, rate and budget caps, enforced by the proxy, outside the instance, where the agent cannot alter them.

Alternatives need SDK cooperation or in-sandbox monitors to approximate any of this.
isx needs nothing inside the instance for any of the four, because the proxy observes from outside, whichever agent runs there: Claude Code, Pi, or whatever comes next.

The counts behind activity and spend exist today: the proxy counts each instance's requests, model calls and tokens, and `isx proxy activity` shows them.
The audit record and the policy are still to come.

### 3. The TUI grows into mission control

isx's primitives map one-to-one onto the dispatch-and-review workflow:

| Primitive | Mission-control meaning |
|---|---|
| Template | The standardized workstation issued to every agent |
| Branch | One agent, one task, one disposable workstation |
| Credential accounts | The agent's identity: its token, its signing key, its budget |
| `isx://` remote | The deliverable: an immutable commit you review before merging |
| Destroy | Offboarding: workstation, credentials, and remotes cleaned up atomically |

The goal is for the TUI to grow from an instance manager into the place where you dispatch tasks, watch agent status (derived from proxy traffic), inspect diffs, and accept or reject work.
The concrete shape of that TUI is brainstormed in [#322](https://github.com/Sanne/incus-spawn/issues/322).

The review model, always an immutable commit and never a live shared directory, is already the project's position: see the [FAQ](FAQ.md#why-cant-i-mount-a-host-directory-read-write-to-follow-agent-work-in-my-ide) on read-write mounts.

## Local-first, by conviction

The major agent platforms are converging on cloud execution.
isx deliberately does the opposite: it runs the agents on your hardware, in your network, with your private repos, and your repositories and credentials stay on infrastructure you own.
An agent's model calls still send the code it works on to the model provider.

This is not nostalgia.
Local execution is the requirement of sovereignty-sensitive organizations, which cloud agent platforms structurally cannot serve: regulated industries, the public sector, security-conscious engineering orgs.
It is also what individual developers want when they simply want their code and credentials to stay theirs.

Local-first does not mean local-only.
The same control plane (templates, identities, proxy, review flow) should eventually be able to dispatch agents to remote hardware you control, for example a team's shared server running Incus.

The architecture already allows that: the client already speaks to Incus over a transport abstraction, and the proxy design is host-agnostic.
But the trust model never changes: the credentials and the control plane stay on infrastructure you own.

## Sequencing

isx is evolving from an instance manager into mission control for parallel coding agents.
These are the priorities, in order: each stage unblocks the next.

1. **Stability and supportability**: done.
   Full-stack `isx doctor` and a redacted support bundle ([#321](https://github.com/Sanne/incus-spawn/issues/321)).
   A tool that promises safety must be dependable, and a project seeking adoption must be supportable without screen-sharing.
2. **Identity foundation**: in progress.
   Per-instance identities have begun with [credential accounts](CONFIGURATION.md#credential-accounts), which have shipped and resolve [#281](https://github.com/Sanne/incus-spawn/issues/281): each instance can use its own Claude and GitHub accounts, covering API keys and GitHub identities.
   Filtered commit signing remains to be done ([#271](https://github.com/Sanne/incus-spawn/issues/271)): audited, without exposing private keys.
3. **Observability.**
   Proxy-derived monitoring of agent status, activity and spend, shown in the TUI: the first visible part of mission control ([#322](https://github.com/Sanne/incus-spawn/issues/322)).
4. **Dispatch and review.**
   Task dispatch onto fresh branches, and review inside the TUI: you read the diff, then accept or reject the work ([#322](https://github.com/Sanne/incus-spawn/issues/322)).
   Agents dispatch work themselves too, through the `isx mcp` server ([#859](https://github.com/Sanne/incus-spawn/issues/859)).
   - A coordinating agent on your host creates instances from templates you approved, runs commands, builds and tests in them, delegates whole tasks to the agents inside, and reads back their diffs.
   - A coordinating agent can already read proxy-derived activity per instance, and what it further needs is tracked in [#898](https://github.com/Sanne/incus-spawn/issues/898).
5. **Policy.**
   Per-agent domain sets, network modes, and budget caps as settings you choose when you dispatch a task.

Throughout, local-first stays the core conviction: your hardware, your network, your repos.
And the project's character holds: strong opinions on the core (security, fidelity, speed), flexibility at the edges (tools, templates, integrations).
