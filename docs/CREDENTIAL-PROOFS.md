# Credential proof enforcement

This completes the proxy enforcement work in [#1107](https://github.com/Sanne/incus-spawn/issues/1107), following proof delivery, tool login refresh, doctor diagnostics and explicit account inheritance in [#1100](https://github.com/Sanne/incus-spawn/issues/1100).
An instance's address selects its accounts; its current namespace proof authorizes their use.
A nested container sharing that address receives no credential unless its parent deliberately shares a proof.
An independent inner credential passes through with its destination, path, query and body unchanged, including through an outer Claude account using a different authentication mode.

## Upgrade and recovery

Update both `isx` and `isx-proxy`, then restart the proxy with `isx proxy stop` and `isx proxy start`.
Update nested isx installations too when using `inherit`, so both layers use the separate credential seed.
The proxy cannot gain enforcement from a CLI update while an older proxy process keeps running.
From a terminal on the host, run `isx shell <instance>` for each existing instance that needs credentials.
This detects instances with the earlier proof delivery version and installs fresh proofs and verifier metadata together.
Open a new login shell and restart long-running tools that inherited the previous environment.
Run `isx doctor` inside the instance to check its proofs and managed tool auth files.

Claude API-key images built before the login approval script was introduced need a template rebuild and a new branch.
Doctor reports this explicitly; opening another login in that old image cannot fix it.
Static placeholders never authorize injection, including during an upgrade.
Requests without valid proofs are relayed normally and receive the upstream's own authentication response.
Unregistered host-originated requests must supply their own credentials.

For a nested isx that should use its parent's account, select `<namespace>=inherit` as described in the README.
The inner proxy verifies its child's proof before presenting the parent's proof upstream.
For Docker or Podman, sharing a current namespace token explicitly grants that namespace's account to the child.
The token remains valid across parent stops, starts and reboots.
Restart that child with a new token only after an explicit proof reset or when moving it to a different isx instance.
Network restrictions and CA trust are separate from credential authorization.

## Lifecycle and builds

Credential proofs use a stable per-instance seed, separate from the per-start MCP and host-service secret.
A protected host key at `~/.config/incus-spawn/credential-proofs/host.key` and a public random instance generation derive this seed.
Back up this key with the instance state; ordinary starts refuse to replace a missing or unusable key silently.
Incus metadata contains the public generation, the MCP secret hash and hashes of namespace digests; metadata readable through `/dev/incus` cannot produce a usable proof.
Branches replace inherited proof generations and verifiers in the copy request.
Ordinary starts and recovery preserve the generation; migration and changed tool declarations update verifiers in the secret write after one additional metadata read under the proof lock.
The guest delivery version is now `3`, and the container boot marker includes `proofs-v3:<generation>`.
The credential seed is redelivered to `/run/isx/credential-secret`; it survives reboots through host-side derivation, not through the guest filesystem.
Normal branch/start/shell request-budget tests retain their existing expectations.
Registry listings share a one-second refresh floor.
An unrecognized proof waits asynchronously for the next permitted shared refresh, then verification is retried.
Waiting is bounded to six seconds, including the refresh floor, and a stalled listing does not accumulate more worker jobs.
Independent credentials pass through immediately.
To revoke shared proofs, run `isx account reset-proofs <instance>` on the host.
It writes a fresh generation and waits up to five seconds for the proxy to acknowledge both the generation and verifier fingerprint.
An unconfirmed reset exits with an error; check or upgrade the proxy and retry the reset.
A successful reset invalidates earlier proofs for subsequent authorization checks; requests already authorized and established WebSockets continue.
Open a new login shell and restart tools and nested services with their new tokens.
A stopped instance receives its new tokens on its next start.
Account changes and deletion still use the registry cache and its ten-second snapshot lifetime.

Every build now registers an address, including builds using configured defaults.
Address assignment, account pins and proof verifiers share one configuration write, and proof delivery rides in the readiness exec.
This adds address allocation and proxy registration to the former unregistered default-build path; explicitly pinned builds already paid for that registration.
Tool environments are written before install hooks that authenticate, such as GitHub identity derivation.
Publication adds verifier revocation and two guest execs to reset managed Claude/Codex caches and remove temporary proof files, before stopping and releasing the address.
Retained failed builds revoke their grant before stopping and receive fresh proofs on their next isx preparation.

## Validation

The stable-proof revision adds lifecycle tests for restarts, fresh branches, migration, changed declarations, lost host keys and concurrent reset/start snapshots.
Reset tests cover proxy acknowledgement, failed guest delivery and preserving pending MCP recovery after a raw reboot.
HTTP and WebSocket tests cover first-call refresh; a stalled-listing test checks bounded waiting and independent-credential passthrough.
The native integration workflow's `test-stable-proofs.sh` keeps a Podman child's configured token through a managed start and raw reboot recovery, then checks explicit reset revocation, new-token delivery, fresh branches and reused names.

The following 2026-10-14 record describes the original per-start implementation, before the stable-proof design revision.

On 2026-10-14, the JVM build was exercised against a real Incus daemon with a Fedora parent, a plain Alpine child on an inner Incus NAT bridge, and two production proxy processes.
Both proxies used a local TLS identity upstream with synthetic `outer-A` and `inner-B` accounts; no real GitHub credential was involved.
Requests used `curl -k` to reproduce the original TLS-verification-disabled case.
The same guest and upstream were checked against base commit `169885e3`.

| Request | Base | Patched |
| --- | --- | --- |
| Parent login with its current proof | `outer-A` | `outer-A` |
| Plain nested child, no proof | `outer-A` | Unauthenticated |
| Nested child with independent `inner-B` credential | `outer-A` | `inner-B` |
| Parent proof deliberately passed into child | `outer-A` | `outer-A` |
| Inner patched isx configured with its own account | Not tested | `inner-B` |
| Inner patched isx configured with `github=inherit` | Not tested | `outer-A` |
| Forged child proof through inner inheritance | Not tested | Unauthenticated |
| Parent's previous proof after a real restart and refresh (original per-start implementation) | Not tested | Unauthenticated |

The patched production build/lifecycle APIs were also run against a real Fedora container: configured defaults authenticated as `outer-A`, an explicit account authenticated as `inner-B`, publication removed verifier metadata and cached Claude/Codex proofs, and a retained failure acquired fresh proofs while its previous proof stayed invalid.
These were API-level probes; a complete template build and VM boot were not run locally.
The native integration workflow exercises full template builds, publication cleanup, containers, VMs, account switching, and nested Podman requests.

`CredentialProofProxyTest` covers HTTP and WebSocket requests with missing, static, malformed, wrong-instance, wrong-namespace, old and current proofs, independent credentials, unknown addresses and unavailable outer accounts.
The proxy tests also cover all Claude carriers, OAuth/API-key injection, Vertex passthrough and authorized translation, inherited proof replacement, and bounded registry refresh.
The integration fixtures use `proof-curl.sh` to send the proofs delivered to the guest; `test-podman.sh` checks a plain nested child, an independent credential and deliberate sharing with TLS verification disabled.
`test-build-proofs.sh` checks that published templates retain neither a grant nor a cached Codex build proof.

Refused injections are counted in `/activity` as `refused_injections`.
Custom tools must declare `proxy.placeholders` and export the credential variable in `env`, as shown in [Custom Tools](../README.md#proxy-credentials).
The proxy logs missing declarations when loading configuration, and tool validation warns about them.
Single-tool validation checks its own namespace; borrowed namespaces are checked against all loaded tools when the proxy loads configuration.
A top-level credential without a namespace needs `config-namespace` and relative config paths before placeholders can be declared.
Without a declaration, the namespace blocks credential injection for every tool sharing its domains.
Request warnings are deduplicated and limited to 256 caller/namespace combinations in a five-minute window, so a burst cannot permanently silence later diagnostics.
They are emitted only for requests claiming an isx proof or placeholder; an ordinary independent credential does not produce a warning.
Account refusals retain their known reason.
Unexpected credential-refusal and WebSocket setup failures log only the exception class to avoid exposing authentication values from exception messages.
