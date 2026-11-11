# Credential proofs

The proxy adds an instance's credentials to a request only when the request carries that instance's proof.
A proof is a token isx gives each instance in place of a credential, one per credential namespace, such as `github` or `claude`.
This page says what the proofs reach, how to share or revoke one, and what to do after an upgrade.

## How the proxy checks a proof

A proof authorizes one instance's accounts, and the proxy checks it before any credential leaves the host.

- **Address and proof.**
  An instance's address selects its accounts.
  Its current proof for a namespace authorizes the use of that namespace's account.
- **macOS.**
  On macOS, Incus runs in a VM and every container reaches the proxy from that VM's address, so the proof alone identifies the instance.
  The proxy on macOS therefore accepts a proof copied to another instance, which the address check refuses on Linux.
- **Nested containers.**
  A nested container is one started inside an instance, and it shares that instance's address.
  It receives no credential unless its parent instance deliberately shares a proof.
- **Independent credentials.**
  An independent credential is one a program inside the instance supplies itself, in place of a proof.
  The proxy relays a request that carries one with its destination, path, query and body unchanged.
  This holds even when the instance's own Claude account uses a different authentication mode.
- **Requests without a proof.**
  A static placeholder is the fixed value a credential variable holds before isx replaces it with a proof.
  A static placeholder never authorizes the proxy to add a credential, including during an upgrade.
  The proxy relays a request without a valid proof unchanged, so the upstream service answers with its own authentication response.
- **Host requests.**
  A request that starts on the host, from an address that isx did not register, must supply its own credentials.

Network restrictions and CA trust are separate from credential authorization.

## Proof tokens

Each start replaces the credential placeholders with **proof tokens** unique to the instance and credential namespace, exported by the login profile.
`GH_TOKEN` holds a `gho_isx_…` token, for example.

The tokens stay the same across stops, starts and reboots, so a nested service explicitly configured with a token keeps working.
A new branch or replacement instance gets different tokens, even if a name or address is reused.

### What a token reaches

Only what runs in an instance's login environment receives the tokens.
The proxy injects a selected credential only when the source instance presents its current proof for the credential's namespace.
A nested container, a sandbox or anything started without that environment does not get a token, unless you pass one in deliberately, as `podman run -e GH_TOKEN …` does.

Plain Git is the one exception: for HTTPS requests to `github.com` it presents the GitHub proof through a credential helper isx installs system-wide, so a clone of a private repository works without `gh`.
The helper reads the current proof on every request, outside login shells and after a reset too, and does not store it.

### Sharing a token

Nested instances with their own accounts keep their own authentication and destination.
A nested instance without a proof does not get a credential of the outer instance.
Deliberate sharing uses [the reserved `inherit` account](CONFIGURATION.md#inheriting-the-outer-instances-account), or explicitly passing the parent's token.

- **A nested isx.**
  To use the parent's account, select `<namespace>=inherit`, as [Inheriting the outer instance's account](CONFIGURATION.md#inheriting-the-outer-instances-account) describes.
  The nested isx's proxy verifies its child's proof before it presents the parent's proof to the outer isx's proxy.
- **Docker or Podman.**
  When you pass a namespace's current token to a child container, the child can use that namespace's account.
  The token remains valid across parent stops, starts and reboots.
- **When to restart a child.**
  Restart the child container with a new token only after an explicit proof reset, or when you move the child to a different isx instance.

### Revoking tokens

To revoke previously shared tokens, run `isx account reset-proofs <instance>` on the host.
The command writes a fresh generation, the public random value the instance's proofs derive from.

The command then waits up to five seconds for the proxy to acknowledge both the generation and the fingerprint of the verifiers, the hashes the proxy checks proofs against.
An unconfirmed reset exits with an error; check or upgrade the proxy and retry the reset.

A successful reset invalidates earlier proofs for subsequent authorization checks; requests already authorized and established WebSockets continue.
Open a new login shell and restart tools and nested services with their new tokens.
A stopped instance receives its new tokens on its next start.

### Checking tokens

A shell opened before an explicit proof reset or an upgrade keeps its previous tokens.
`isx doctor` compares the shell's tokens with the credential seed isx delivered, the per-instance value the tokens derive from, and says how to refresh the tokens.

## Upgrade and recovery

After an isx update, these steps give the proxy and every instance proofs they agree on.

1. Update both `isx` and `isx-proxy`, then restart the proxy with `isx proxy stop` and `isx proxy start`.
   A CLI update alone does not make the proxy enforce proofs, because the older proxy process keeps running until you restart it.
2. Update nested isx installations too when using `inherit`, so that the outer isx and the nested isx both use the separate credential seed.
3. From a terminal on the host, run `isx shell <instance>` for each existing instance that needs credentials.
   `isx shell` detects an instance with the earlier proof delivery version and installs fresh proofs and verifier metadata together.
4. Open a new login shell and restart long-running tools that inherited the previous environment.
5. Run `isx doctor` inside the instance to check its proofs and managed tool auth files.

Programs that run on the host supply their own credentials.
A Docker or Podman job that relied on the parent's accounts without a token needs you to pass a token explicitly.

Claude API-key images built before the login approval script was introduced need a template rebuild and a new branch.
That script approves the instance's current proof in Claude Code at each login.
`isx doctor` reports this need explicitly; opening another login in that old image cannot fix the image.

## Lifecycle and builds

This section says where a proof comes from, what a copy, a start or a reset does to it, and what builds and projects do with theirs.

### The credential seed

Credential proofs use a stable per-instance seed, separate from the instance's secret, which changes on every start and serves MCP and host services.
A protected host key at `~/.config/incus-spawn/credential-proofs/host.key` and a public random instance generation derive this seed.

Back up this key with the instance state, because an ordinary start does not replace a missing or unusable key and fails with an error instead.

The config directory may be a symlink; its resolved target must pass the ownership and permission checks.
The credential key and its containing `credential-proofs` directory cannot be symlinks.

Incus metadata contains the public generation, the hash of the instance's secret and hashes of namespace digests.
A program in the instance can read metadata through `/dev/incus`, but that metadata cannot produce a usable proof.

isx delivers the credential seed to `/run/isx/credential-secret`.
The seed survives reboots because the host derives it again, not because the guest filesystem keeps it.
The guest delivery version is `4`, and the container boot marker includes `proofs-v4:<generation>`.

### Copies, starts and resets

- **Copies.**
  Every copy clears inherited proof generations, verifiers and boot-delivery stamps in the copy request; branches supply fresh grants in that same request.
- **Starts.**
  Ordinary starts and recovery preserve the generation.
  Migration and changed tool declarations update verifiers in the write of the instance's secret, after one additional metadata read under the proof lock.
- **Seed derivation.**
  The ordinary start derives its seed once, and a restart from the TUI reuses the TUI's listing of instances without another pre-restart metadata read.
- **Resets.**
  Before a reset changes the instance's metadata in Incus, the reset marks the old generation as invalidated in the host lock directory.
  An old prefetched listing then requires a fresh metadata read; the marker never authorizes credentials.
- **A reset during a start.**
  Guest proof delivery and its boot stamp hold the same lock as reset, and recheck invalidation after booting.
  This prevents a start from delivering revoked proofs after a concurrent reset completes.
- **Account changes and deletion** use the registry cache and its ten-second snapshot lifetime.

### The proxy's registry

The proxy checks a proof against its listing of instances, which it refreshes at a bounded rate.

- The proxy refreshes its listing at most once per second, and all requests share that refresh.
- A request with an unrecognized proof waits asynchronously for the next permitted shared refresh, then the proxy retries verification.
- Waiting is bounded to six seconds, including that one-second interval, and a stalled listing does not accumulate more worker jobs.
- The proxy relays a request that carries an independent credential immediately.

### Builds

Every build registers an address, including builds using configured defaults.
Address assignment, account pins and proof verifiers share one configuration write.
isx delivers the proofs in the readiness exec, the command it already runs in the guest to check that the guest is ready.
Tool environments are written before install hooks that authenticate, such as GitHub identity derivation.

Publication revokes the verifier and runs two guest execs to reset managed Claude/Codex caches and remove temporary proof files, before isx stops the build instance and releases its address.
isx revokes the grant of a failed build before it stops and retains that build, and the build receives fresh proofs the next time isx starts it.

### Project create and update

`isx project create` and `isx project update` receive temporary proofs for private repository access and authenticated build commands.
They also register an address and deliver those proofs before cloning repositories, fetching updates or running pre-build commands.
They retain inherited account pins and baked-identity compatibility checks, and remain untrusted for host routes.

isx revokes those grants when the operation finishes or fails.
On success or failure, cleanup attempts revocation, managed-cache scrubbing, shutdown and address release even if an earlier cleanup step fails.
A cleanup failure prevents publication; an update keeps the project available for a later retry.

## Diagnostics and declarations

The proxy counts what it refuses, and warns about tools whose credentials it cannot inject.

### Refusal counters

The proxy counts refused injections in its `/activity` report, which `isx proxy activity` prints.

| Counter | What it counts |
| --- | --- |
| `refused_injections` | Every refused injection. |
| `refused_proofs` | Refused requests that carried a proof: the proxy checked it and it did not match any proof isx gave an instance. |
| `refused_address_misses` | Refused requests that carried a proof from an address that belongs to no instance; the proxy never checked the proof. |

The last two count a refusal only when the proxy's listing of instances is current.
When the proxy's last listing failed or hung, `registry_stale` is true and the proxy counts such a refusal in neither of the two.

### Placeholder declarations

Custom tools must declare `proxy.placeholders` and export the credential variable in `env`, as shown in [Custom Tools](TOOLS.md#proxy-credentials).
The proxy logs missing declarations when loading configuration, and tool validation warns about them.
Validating a single tool checks that tool's own namespace.
The proxy checks a borrowed namespace, one that another tool declares, against all loaded tools when the proxy loads configuration.

A top-level credential without a namespace needs `config-namespace` and relative config paths before placeholders can be declared.
Without a declaration, the proxy never injects the namespace's own entries.
The proxy still injects the entry of another tool that shares the namespace's domains, for requests that select that tool's entry.

### Request warnings

- Request warnings are deduplicated and limited to 256 caller/namespace combinations in a five-minute window, so a burst cannot permanently silence later diagnostics.
- They are emitted only for requests claiming an isx proof or placeholder; an ordinary independent credential does not produce a warning.
- Account refusals retain their known reason.
- Unexpected credential-refusal and WebSocket setup failures log only the exception class, to avoid exposing authentication values from exception messages.

## Validation

Unit tests in the proxy and the native integration workflow cover the proofs.
The workflow exercises full template builds, publication cleanup, containers, VMs, account switching and nested Podman requests.

- **Lifecycle tests** cover restarts, fresh branches, migration, changed declarations, lost host keys and concurrent reset/start snapshots.
- **Reset tests** cover proxy acknowledgement, failed guest delivery and preserving pending MCP recovery after a raw reboot.
- **Proxy tests** cover HTTP and WebSocket requests with missing, static, malformed, wrong-instance, wrong-namespace, old and current proofs, independent credentials, unknown addresses and unavailable outer accounts.
  They also cover first-call refresh, all Claude carriers, OAuth/API-key injection, Vertex passthrough and authorized translation, inherited proof replacement and bounded registry refresh.
  A stalled-listing test checks bounded waiting and independent-credential passthrough.
- **`test-stable-proofs.sh`** keeps a Podman child's configured token through a managed start and raw reboot recovery, then checks explicit reset revocation, new-token delivery, fresh branches and reused names.
- **`test-project-proofs.sh`** clones an authenticated Git fixture during project creation, checks authenticated pre-build and update requests, and checks grant removal after successful and failed operations.
- **`test-podman.sh`** checks, through `test-nested-proofs.sh`, a plain nested child, an independent credential and deliberate sharing with TLS verification disabled.
  The integration fixtures use `proof-curl.sh` to send the proofs delivered to the guest.
- **`test-build-proofs.sh`** checks that published templates retain neither a grant nor a cached Codex build proof.

## History

An upgrade from a release with an older proof design takes the steps in [Upgrade and recovery](#upgrade-and-recovery), whether that release delivered proofs without enforcing them or enforced a proof that changed on every start.

The two cases that need more than those steps are listed there: a Claude API-key image from before the login approval script, and a Docker or Podman job that relied on the parent's accounts without a token.
