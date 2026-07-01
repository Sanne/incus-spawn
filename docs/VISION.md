# isx — Vision

Where the project is going and why. [CHARACTER.md](CHARACTER.md) describes what isx *is* — its mission and design philosophy; this document describes what it is *becoming*. Nothing here is a committed roadmap; concrete work is tracked in issues (referenced throughout).

## The problem, restated for the next few years

Today a developer runs one AI coding agent and babysits it in a terminal. That is already changing: developers increasingly run several agents in parallel, and the human's job shifts from *typing alongside one agent* to **dispatching tasks and reviewing results**. Every part of that future workflow raises the same questions:

- Where does each agent run, and what can it damage?
- Whose identity is it using, and can its actions be traced back to *it* rather than to you?
- How do you see, at a glance, which of your agents needs attention?
- How do you review and accept work you didn't watch being written?

isx's answer: **onboard agents the way you'd onboard a new teammate.** Each agent gets its own disposable, full-fidelity Linux workstation, its own credentials, and its own audit trail — and you review its work as commits, the same way you'd review any teammate's pull request, before anything touches your machines or your repos.

## Three theses

### 1. Agents are principals, not processes

An agent should not act *as you*. It should have its own identity: its own scoped API token, its own GitHub identity, its own commit-signing key — held outside the environment it works in, so even a fully compromised agent cannot exfiltrate them, and every action is attributable to the specific agent that performed it.

The MITM credential-isolation proxy already guarantees the "held outside" half: no key, token, or credential ever enters a container in any form. The per-container identity half is designed in [#271](https://github.com/Sanne/incus-spawn/issues/271): per-container proxy profiles (each instance mapped to its own tokens and keys), and SSH-agent-filtered commit signing where the container can request a signature for exactly one designated key — namespace-checked to git signing only, audited per request — without the private key ever leaving the host (or the hardware enclave).

The per-container profile mechanism (#271 Phase 1) is deliberately general: signing keys are the first consumer, but per-agent API keys, GitHub identities, and budgets all ride on the same foundation.

### 2. The proxy is the control plane

The credential proxy began as a security mechanism, but its position makes it something more: **every API call every agent makes passes through it.** That single vantage point yields, with zero instrumentation inside containers:

- **Activity**: an agent that is streaming API calls is working; one that stopped mid-session is waiting for input; one that went quiet is done or stuck.
- **Spend**: live token usage per agent, attributable per container.
- **Audit**: a complete record of what each agent did with the identity it was issued.
- **Policy**: per-agent domain allowlists, rate and budget caps — enforced at a layer the agent cannot reach.

Alternatives need SDK cooperation or in-sandbox monitors to approximate any of this. isx gets it architecturally, for any agent — Claude Code, Pi, or whatever comes next — because the observation point sits outside the trust boundary.

### 3. The TUI grows into mission control

isx's primitives map one-to-one onto the dispatch-and-review workflow:

| Primitive | Mission-control meaning |
|---|---|
| Template | The standardized workstation issued to every agent |
| Branch | One agent, one task, one disposable machine |
| Per-container profile | The agent's identity: its token, its signing key, its budget |
| `isx://` remote | The deliverable — an immutable commit you review before merging |
| Destroy | Offboarding: workstation, credentials, and remotes cleaned up atomically |

The TUI evolves from a container manager into the place where you dispatch tasks, watch agent status (derived from proxy traffic), inspect diffs, and accept or reject work. Brainstormed concretely in [#322](https://github.com/Sanne/incus-spawn/issues/322); the review model — always an immutable commit, never a live shared directory — is already the project's position (see the README FAQ on read-write mounts).

## Local-first, by conviction

The major agent platforms are converging on cloud execution. isx deliberately runs the other way: **your hardware, your network, your private repos — nothing leaves the building.** This is not nostalgia; it is the requirement of sovereignty-sensitive organizations (regulated industries, public sector, security-conscious engineering orgs) that cloud agent farms structurally cannot serve, and of individual developers who simply want their code and credentials to stay theirs.

Local-first does not mean local-only. The same control plane — templates, identities, proxy, review flow — should eventually be able to dispatch agents to *remote hardware you control*: a team's shared agent-farm box running Incus. The architecture keeps that door open (the client already speaks to Incus over a transport abstraction; the proxy design is host-agnostic), but the trust model never changes: the credentials and the control plane stay on infrastructure you own.

## Sequencing

Priorities, in order — each stage unblocks the next:

1. **Stability and supportability.** Full-stack `isx doctor` and a redacted support bundle ([#321](https://github.com/Sanne/incus-spawn/issues/321)). A tool that promises safety must be dependable, and a project seeking adoption must be supportable without screen-sharing.
2. **Identity foundation.** Per-container proxy profiles and filtered commit signing ([#271](https://github.com/Sanne/incus-spawn/issues/271)); also resolves per-container GitHub identities ([#281](https://github.com/Sanne/incus-spawn/issues/281)).
3. **Observability.** Proxy-derived status, activity, and spend surfaced in the TUI — the first visible slice of mission control ([#322](https://github.com/Sanne/incus-spawn/issues/322)).
4. **Dispatch and review.** Task dispatch onto fresh branches; diff review and accept/reject from the TUI.
5. **Policy.** Per-agent domain sets, network modes, and budget caps as dispatch-time knobs.

Throughout: the character defined in [CHARACTER.md](CHARACTER.md) holds — strong opinions on the core (security, fidelity, speed), flexibility at the edges (tools, templates, integrations).
