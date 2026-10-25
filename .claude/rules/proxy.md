---
paths:
  - "proxy/**"
  - "common/src/main/java/dev/incusspawn/proxy/**"
  - "common/src/main/java/dev/incusspawn/DerEncoder.java"
  - "common/src/main/java/dev/incusspawn/util/HttpValidator.java"
  - "common/src/main/java/dev/incusspawn/util/SystemTrust.java"
  - "common/src/main/java/dev/incusspawn/util/MacKeychainTrust.java"
  - "common/src/main/java/dev/incusspawn/util/DeniedChainTrustManager.java"
  - "cli/src/main/java/dev/incusspawn/command/Proxy*Command.java"
---

# MITM TLS Proxy

`MitmProxy` (in `proxy/src/main/java/dev/incusspawn/proxy/`; the supporting `ProxyConfig`, `ProxyCredentials`, `ProxyHealthCheck`, `ProxyService` and `CertificateAuthority` live in `common/src/main/java/dev/incusspawn/proxy/`) is a TLS-terminating proxy that intercepts HTTPS to specific domains and injects real auth credentials, so containers only hold placeholder values.
Key design:
- Listens on gateway IP:18443 (iptables redirects 443->18443 on the bridge)
- `authError` is set by credential injection, which only runs on **real container traffic** -- so on its own it makes the status wrong in both directions (a fixed failure still shown, a broken credential not yet noticed).
  `/health` therefore verifies the Vertex token itself via `claimAuthRevalidation()`, running `getVertexAccessToken()` through `executeBlocking` (it forks `gcloud`; never call it on the event loop from here).
  The gate declines in four cases and each one matters: **not `useVertex()`** (first line, before any lock or dispatch -- non-Vertex users must pay nothing); **a fresh cached token with no error** (`hasFreshVertexToken()`, read *outside* `authLock` -- taking `authLock` then the instance monitor while the traffic path takes them in the other order would risk deadlock); **a standing non-`VERTEX_AUTH_HINT` error** (OAuth needs `isx init`); and **the 10s throttle / in-flight flag** (a failing credential caches nothing, so every poll would otherwise fork `gcloud`).
  A probe failure calls `recordProbeAuthError`, **not** `setAuthError` -- the latter notifies, and the check runs on every poll.
  When adding a new credential source, give it a hint constant and decide explicitly whether it is machine-verifiable.
- `getVertexAccessToken()` is bounded at `GCLOUD_TIMEOUT_SECONDS` and rejects an empty token instead of caching it.
  Both matter because `/health` reaches this with no traffic to reveal a wedged `gcloud` or a blank credential.
- The proxy authenticates to Vertex with `gcloud auth print-access-token`, which reads the **gcloud user credential** -- so the remediation is `gcloud auth login`, not `gcloud auth application-default login`.
  `CredentialSetup.verifyVertexConfig()` uses the same command and must give the same hint.
- Per-domain certs signed by a custom CA (installed in templates during build).
  The CA lives at `~/.config/incus-spawn/ca.{crt,key}`; leaf certs are persisted by `CertStore` under `~/.config/incus-spawn/certs/` (`<domain>.crt`/`.key`, wildcards as `_wildcard.<domain>`) and reused across proxy restarts, re-minting only on miss/CA-rotation/near-expiry.
  Persisting is what keeps each leaf's `notBefore` stable.
  The proxy is relaunched frequently (macOS launchd `KeepAlive`), and re-minting on a host whose clock has jumped ahead of a lagging container clock (e.g. an Incus VM after macOS resume) produced certs the container rejected as "not yet valid".
  Certs are keyed by domain, never by container (a leaf is a function of `(domain, CA)`), so this composes with future per-container interception, which is a routing/DNS concern.
  `CertificateAuthority.BACKDATE_MS` backdates `notBefore` as a skew margin for the rare fresh-mint moments.
- **Certs are chosen per SNI name** by `InterceptedCertOptions` (Vert.x `keyManagerFactoryMapper`, run on a worker thread), not by a fixed keystore.
  dnsmasq sends names at *any* depth under an intercepted domain, but a wildcard SAN covers one label, so `certNameFor()` gives the exact domain or a wildcard for the name's own parent, minted on demand through `CertStore`.
  Two bounds, because the container picks the names: `MAX_ON_DEMAND_CERTS` counts on-demand certs **on disk** (so reloads/restarts don't reset it), and `MAX_SERVED_NAMES` counts distinct SNI names answered, since Vert.x caches an `SslContext` per answered name forever.
  With the old `<domain>`/`*.<domain>` JKS, a deeper name matched no alias and Vert.x served an arbitrary entry: `*.api.openai.com` for a GitHub host (#783).
  Anything else (off-domain, no SNI, over the cap) is refused: the mapper returns `null` and the default key manager holds **no** cert, so the handshake fails at once.
  Never refuse by throwing from the mapper -- Netty then hangs the connection until the handshake timeout.
  SNI names become file names, so `CertStore.get()` accepts only `CertStore.isHostname` names.
  `InterceptedCertOptionsTest` covers every shipped intercepted domain automatically.
- Both CA and leaf certs carry RFC 5280 key identifiers: SKI on the CA, SKI + AKI on leaves.
  Strict validators (OpenSSL 3.5, and so Python 3.13+, which turns on `VERIFY_X509_STRICT` by default) reject a chain without them -- including the trust anchor, so leaf-only extensions are not enough.
  A CA generated before this is re-issued on load over its **existing key** (`reissueWithSki`), which keeps every leaf valid and un-re-minted; the replaced cert is kept as `ca-superseded.crt`.
  Images stamped with that superseded fingerprint carry a stale-but-not-foreign anchor: `BranchCommand` lets them branch (the new cert is pushed into the instance by `InstancePrep`/`fixContainerCaIfNeeded` on first use) instead of demanding a rebuild the way a real CA rotation does.
- **Host trust** (#1437): isx's own HTTPS trusts the host's CA store, found by `SystemTrust` (in `common/util`) as the first system PEM bundle that exists.
  The proxy's upstream clients trust that bundle alone (`Upstream.upstreamTrust()`, Vert.x `PemTrustOptions`).
  Every other TLS client starts from `SystemTrust.httpClient()` or `SystemTrust.sslContext()`, which add the bundle to the JDK store rather than replace it, so a CA a JVM user imported into `cacerts` keeps working; an unreadable bundle leaves the JDK store alone.
  A raw socket from `SystemTrust.sslContext()` checks the chain but not the host name, so it sets endpoint identification to `HTTPS` before the handshake (`DumpProxy.openUpstream`).
  Without it a native binary trusts only the JDK store baked in at build time, and fails behind TLS inspection where curl and the proxy succeed.
  On macOS `MacKeychainTrust` adds what an administrator trusts for SSL in the Keychain's admin domain and removes what one denied, from every source (#1467, DESIGN.md "Host trust on macOS: the admin domain only").
  It runs `/usr/bin/security` by absolute path; a failure adds nothing.
  When only the System keychain's certificates cannot be read (exit status, timeout or unparseable output), the export's denies still apply; a failed login-keychain read only loses the bodies stored there; nothing is denied only when the trust settings themselves cannot be read.
  An unreadable entry (not a dict, a result that is not an integer, a policy that is not data) decides nothing and stops any later entry from trusting; a later deny still denies, and so does a deny on an entry whose policy is not data, and a `<real>` result of 3.
  The user domain is read for its denies alone (`withUserDenies`); its trusts are never used, and when it cannot be read the admin result stands (#1552).
  A deny only removes anchors, so whenever the Keychain denies anything `DeniedChainTrustManager` wraps the JDK's trust manager (CLI: `SystemTrust.sslContext`; proxy: `Upstream.refusingDenied`, `TrustOptions.wrap`) and refuses a presented chain holding a denied intermediate or leaf (#1552).
  `Trust.denies` matches a certificate's SHA-1 or, for a denied certificate whose body a keychain holds (System, login, `SystemRootCertificates.keychain`), its subject and key (`keyOf`): a server's cross-signed copy of a denied root has another hash (#1552, DESIGN.md).
  It must stay an `X509ExtendedTrustManager` passing every `SSLEngine`/`Socket` overload to the JDK's, which does the host-name check; AIA fetching must stay off, or a fetched denied intermediate would go unchecked.
  `SystemTrustGateTest` (in `cli`) fails on a plain `HttpClient.newBuilder()`, a default `SSLContext`/socket factory or an `HttpsURLConnection` in any module's main sources; a plain HTTP client says so with a `// plain HTTP:` comment on that line.
  Nothing is cached: the class is initialized at image build time, and the store may change under a long-running isx.
- **Host CA propagation** (opt-in, `host-ca: { propagate: true }` in config.yaml): `CertificateAuthority.collectTrustAnchors()` builds one trust set -- the MITM CA plus host anchors from `/etc/pki/ca-trust/source/anchors/`, every certificate in each `.crt`/`.pem` file (PEM bundles and DER alike) re-encoded as PEM from its DER and keyed by its SHA-256 fingerprint (`isx-<fp>.crt`).
  Only re-encoded certificates reach a guest or the stamp: a file holding anything that does not parse as certificates (a private key beside a cert) is refused whole, with a warning.
  Content-addressable naming eliminates filename collisions and shell injection.
  One stamp (`TrustAnchors.stamp()`) goes in `Metadata.CA_FINGERPRINT`: the CA's fingerprint, then `+` and a fingerprint over the host anchors when there are any; without host anchors it is the CA's fingerprint alone, as before.
  `fixContainerCaIfNeeded` reconciles the whole set in one guest exec (`buildReconcileScript`): it first stamps `<ca>+pending`, which matches no set, so a failed install retries (an empty stamp would read as pre-versioning and never be checked again).
  It removes old `incus-spawn-mitm.crt` and the managed anchors, writes the current set via `Container.heredoc`, runs `update-ca-trust`, and re-stamps on success.
  Managed means exactly `isx-<64 lowercase hex>.crt` (`MANAGED_ANCHOR_PATTERN`, built from `anchorFile`); `isx-company.crt` is not isx's.
  The pattern is a `case` test over `isx-*.crt`, never a path: a glob matching nothing reaches `rm` as an over-long name and fails the script.
  It is serialized per instance across isx processes on this host (`HostLock` on `locks/trust/<instance>.lock`): the unlocked check only decides whether to take the lock; under it the stamp is read again from Incus (a caller's prefetched read may predate another reconcile) and the set collected afresh, so the last holder installs and stamps what it saw (#1381).
  Builds install and stamp under the same lock (`CertificateAuthority.lockTrust`).
  A lock not acquired in time changes nothing and is reported; the next use retries.
  A lock file that cannot be used at all degrades, with one warning, to the in-process lock (`HostLock.acquireOrDegrade`), so a home on NFS without lockd still gets repairs.
  `CaTrust.classify` compares only the stamp's CA part: changed host anchors are reconciled on first use and never make a template `FOREIGN` (which would block `isx branch`).
  `collectTrustAnchors()` creates the CA when there is none, as the build's install always did; `buildFromScratch` and `buildFromParent` (`BuildCommand.refreshTrustAnchors`) install the set through the same script (`installTrustAnchors`), fail the build if that fails, and stamp the build with that same set right then, never with one read again at the end; `BranchFlow` and `InstancePrep.repairBeforeUse` (the `isx shell` and TUI shell prep) reconcile through `fixContainerCaIfNeeded`.
  The config is structured (`HostCaConfig.of(raw)`, same raw-YAML pattern as `ArtifactCacheTiers` and `ReadyTimeoutsConfig`) so a mistyped section does not fail config.yaml.
- Claude credentials live in `claude.accounts`, a named map (`ClaudeAccount`, `type: api-key|oauth|vertex`) so several accounts can be configured at once; `claude.default` names the one instances use.
  `ClaudeConfig.accountFor(Predicate)` picks the default when it qualifies, else the first that does.
  Which accounts exist and which is the default are answered by `AccountResolver` against `ClaudeConfig.ACCOUNT_SHAPE`, the same rule as every namespace (a pre-accounts flat file presents as the account `default`; see `config.md`), so nothing outside `ClaudeConfig` branches on Claude.
  `ClaudeConfig`'s `isOauthMode()`/`getApiKey()`/`isUseVertex()` accessors are **derived from the resolved account**, so `ProxyCredentials`, `ClaudeSetup` and `PiSetup` read them unchanged.
  Both config classes bind **by field** (`@JqVisibility` with getters and is-getters `NONE`) because the derived getters collide with the legacy field names -- binding through them would make the split property derived, and a flat `config.yaml` would silently stop deserializing.
  Account selection is **per instance** and **generic across config namespaces** -- see the next two bullets.
- **Per-instance accounts.**
  Three layers, resolved lowest-first: the namespace's `default` in config.yaml, the template's `accounts:` map (merged per key down `ImageDef.chain()` via `resolveAccounts`), and a per-instance override (`isx branch --account <ns>=<account>`, `isx account set`; `isx account unset` removes it).
  The last two are stamped as `user.incus-spawn.account.<namespace>` (`Metadata.accountKey`).
  Selection is always namespaced -- a bare name would fan out over whichever namespaces happened to have that account, so a namespace added later would silently widen an existing command.
  **Fail closed**: a pin to an account that is gone raises `AccountResolver.UnknownAccountException` and the request 502s; never fall back to the default, which in #351's case belongs to another client.
  `isx doctor` reports dangling pins.
  `AccountUsage` is the read-side view for humans (`isx account show`): the served account per namespace, who chose it -- recorded beside every pin as `user.incus-spawn.account-origin.<ns>` (`AccountOrigin`: `template:<name>`, `explicit`, `copied:<instance>`), written only through `AccountSelection.stampUpdates` so pin and origin never diverge -- and drift from the template's current `accounts:`.
  `ToolSetup.describeAccount()` describes an account without its credential.
  `isx init` warns before removing an account pinned by instances or named by templates, and its rename re-points pins via `AccountSelection.renameInInstances`, which renames an identity stamp only through `ToolSetup.renameBakedIdentity()` (GitHub keeps the fingerprint).
  **Default changes** move every unpinned instance: `isx init` (`changeDefaultAccount`) names them (`InstanceRegistry.accountStates` includes unpinned instances) and offers switch/keep/cancel; the proxy logs them on reload (`logDefaultChanges`).
  The registry reads `account-identity` stamps (`InstanceAccounts.bakedIdentities`), and `MitmProxy.refuseIfUnservable` fails closed -- `AccountSelection.UnservableAccountException`, 502 -- when the served account (pin or default) needs another Claude auth mode than the instance was built with (`AccountSelection.servingMismatches`), scoped to that credential's domains (`namespacesForDomain`, wildcards included); cached per registry record in the reload's `ConfigState`, so a result computed from the old config lands in a cache nothing reads any more.
  `accountStates` lists branches only -- never templates, whose pins would travel to all their branches.
- **Gemini API keys (#1409)** use `GeminiSetup`'s generic header route for exactly `generativelanguage.googleapis.com`.
  `gemini.accounts.<name>.apiKey` stays on the host; the guest exports `GEMINI_API_KEY` with prefix `gemini-` and namespace `gemini`.
  `ProofCarriers` reads its proof from `x-goog-api-key` before HTTP or WebSocket account resolution and injection.
  Missing, static, wrong or reset proofs pass through unchanged, as do independent credentials; a valid proof with a missing account pin fails closed.
  No Google wildcard, OAuth flow or Vertex route is added.
- **The generic seam is one method.**
  `ProxyDef.accountConfigPath()` builds `<ns>.accounts.<account>.<config-path>` beside the existing `fullConfigPath()`.
  `ToolProxyResolver.resolveConfigValue` prefers it and falls back to the flat path when the namespace has no accounts *or the account omits that key* (a namespace may hold the token per account and other settings globally).
  That gives named accounts to every namespace at once, YAML-only tools included, with no per-tool code.
  The namespace is derived **per config entry** (`ProxyDef.namespaceOf`), not per tool: a tool may *borrow* another namespace's credential by naming it fully in `config-path` and declaring no `config-namespace` (`CopilotSetup` points at `github.token` to share the `gh` PAT).
  Either way the namespace must be an identifier (`ToolDefValidator.invalidNamespace`), or the loader refuses the tool: it is printed by `isx init` and `isx account`.
  Keying off the tool's own namespace would leave `gh` authenticating as the pinned account while copilot used the flat token -- two GitHub identities in one container.
  `AccountResolver` does the tree-based resolution; `AccountSelection` does parsing/validation/stamping.
  `GitHubConfig`/`BobConfig`/`OpenaiConfig` extend `SpawnConfig.NamespaceConfig` so an `accounts:` block survives a save -- without it the block deserialized to nothing and the next save **deleted** it.
- **Caller identification.**
  `routeRequest`/`routeWebSocket` read `req.remoteAddress()`; iptables REDIRECT preserves the container's source address, and every branch has a static IP in `Metadata.STATIC_IP`.
  `InstanceRegistry` maps address -> (instance, accounts) from one `listJsonConfig()` call.
  An address two instances claim (a copy made before #815, or by `incus copy`) belongs to the one running claimant -- Incus will not start a second NIC on the same address -- and to nobody when none or several run; never to whichever is listed last.
  `isx branch`/`isx account set` poke it with **SIGUSR1** (`ProxyService.signalAccountRefresh`), not SIGHUP: pinning is instance state, and answering a branch -- the most common operation there is -- with a full reload would re-mint certs and re-push SSL options every time.
  `lookup()` must stay non-blocking (event loop); refreshes go through `executeBlocking` with single-flight, like `resolveHost`/`acquireVertexAccessToken`.
  Normalize `::ffff:` mapped addresses or every lookup misses and credential proofs fail.
  An unknown address gets no injected credential; host traffic must supply its own authentication.
  Except on macOS (#1582), where the VM NATs every container to its own address: there `MitmProxy.proofMayIdentify` lets a non-host caller at an unknown address be the instance its proofs name (`InstanceRegistry.prove(address, digests, true)`, the snapshot's `addressByProof`, running instances only; a hash two running instances share names nobody).
  Never widen that gate to Linux, where the address check is what stops a proof replayed from another instance.
  Such a caller's traffic flow starts pending (`ApiActivity.proofFlow`) and is begun for the instance once `authorize` names it.
  Build containers are **not** unknown (#903, #1107): `BuildAccounts.start` claims them an address (`InstanceNetwork.assignBuildAddress`, filtering on, guest keeps DHCP, leased addresses skipped) and stamps the template's pins before the first start, clears every `account-identity` stamp copied from the parent (else `refuseIfUnservable` would refuse the build its own template's account, or a changed default) -- reading them first, since `settleIdentities` needs them after tool setup and `BuildAccounts.identityStamps` puts back the ones the build does not re-stamp -- and signals the proxy.
  `releaseBuildAddress` gives the address back once the template is stopped.
  Builds using configured defaults also need a registered address and fresh proofs before authenticated steps run.
  A new build path must do the same before anything in it goes through the proxy.
- **The instance secret (#934)** is a second layer on the address, never a replacement (except on macOS, below): `InstanceRegistry.identify(address, secret)` passes only when both belong to one instance (`InstanceSecret.matches`, constant time; no hash recorded means nothing passes).
  The host holds only the SHA-256 (`Metadata.INSTANCE_SECRET_SHA256`; a guest reads its own `user.*` keys via `/dev/incus`), kept in the snapshot's `secretByAddress`, not in `InstanceAccounts`, which `MitmProxy` caches by value.
  Every isx start rotates it: `BranchFlow.create` (hash on the copy request, secret to `setupRuntime`), `InstanceLifecycle.startForUse`/`restartForUse` (`rotateInstanceSecret` before the start, then the `waitForReady` probe writes `/run/isx/instance-secret`), and `VmAgentRecovery` (the hash in its boot-stamp write).
  A container start also records the boot it went to (`recordSecretBoot`: its `last_used_at`, read once the guest answers, as `Metadata.INSTANCE_SECRET_BOOT`; the read is returned for the CA check, `fixContainerCaIfNeeded(incus, name, instance)`, so the stamp's write is the only request added -- pinned in `InstanceLifecycleRequestBudgetTest`), so `ensureReady` can tell a reboot isx did not do from the instance it already holds and give the box a new secret (#1024).
  A VM is never stamped, since QEMU reboots in place, and its agent probe asks the guest instead (`InstanceSecret.GUEST_CHECK`).
  A new path that starts or restarts an instance for use goes through these or does the same; builds use `BuildAccounts` and revoke their grants before publication.
  The secret reaches the guest in the exec environment (`InstanceSecret.guestEnv` + `GUEST_SCRIPT`), never on a command line, which any guest user can read.
  The same exec reconciles the Claude Code registration of `mcp.isx.internal` with the `mcp-caller` grant every caller passes beside it (`McpClientRegistration`, #1182, see `mcp.md`).
  Rotation does not signal the proxy (cost on every start; a refusal right after a start refreshes and retries, throttled by `wantsMissRefresh()` like a lookup miss).
  On macOS (#1583) `routeRequest` passes the `proofMayIdentify` gate to `McpBridge` and `HostRouteRelay`, and a caller at an unknown address is the instance whose secret it presents (`identify(address, secret, true)`, the snapshot's `addressBySecret`, running owners only, by `owner`'s rule, cleared by a failed listing like `addressByProof`); its traffic flow starts pending and the handler begins it for the instance it names; never widen that gate to Linux either.
  See DESIGN.md "A per-start secret".
- **Guest tools read their placeholder when they run (#1108)**, because a build proof is revoked before publication and instance proofs can be explicitly reset.
  Nothing a build writes may hold one: `ClaudeSetup` and `CodexSetup` each write an `/etc/profile.d/isx-zz-*-auth.sh` login script (sorted after every `isx-*.sh`) that adapts the exported value to what the tool reads -- Claude Code's `customApiKeyResponses.approved` (last 20 characters of `ANTHROPIC_API_KEY`, else an interactive prompt defaulting to no), `ANTHROPIC_CUSTOM_HEADERS` from `ISX_VERTEX_ACCESS_TOKEN` (under `CLAUDE_CODE_SKIP_VERTEX_AUTH` Claude Code sends no Authorization header of its own), and Codex's `~/.codex/auth.json` (Codex never reads `OPENAI_API_KEY`).
  The `gcloud` stub prints `$ISX_VERTEX_ACCESS_TOKEN`; `GhSetup`'s host-driven `gh api` calls take `GH_TOKEN` from the guest's `/etc/profile` (`GhSetup.LOGIN_TOKEN`). gh, Copilot, Pi and Bob read the variable already.
  A child build refreshes these files for a tool it only inherits (`ToolSetup.refreshInherited`, from `GuestProvisioning.refreshInheritedTools`), so an older parent's files do not reach it.
  A YAML tool whose `files:` or build steps hold the value of one of its own credential-named env entries is warned about by the loader (`ToolDefValidator.embeddedTokens`).
  Per-tool evidence: DESIGN.md "A placeholder is read when the tool runs".
- **Proof tokens (#1100, #1107).**
  `CredentialProof` derives a stable seed from a protected host key and a public random generation in `Metadata.INSTANCE_PROOF_ID`.
  Namespace digests use HMAC keyed by that seed, never a `/dev/incus`-readable value.
  Starts retain the generation; every branch and explicit reset creates a new one.
  The host key lives at `~/.config/incus-spawn/credential-proofs/host.key`, checked with the same ownership, permission and atomic-creation rules as host-route secrets.
  A symlinked config directory resolves to a checked target; the key and its immediate directory still cannot be symlinks.
  A lost host key fails an ordinary start; restore it or explicitly reset the instance.
  Tool `placeholders:` declarations supply variables and prefixes; declarations exclude project-local definitions.
  `ProofVerifier` stores only SHA-256 of each namespace digest in the replaceable `Metadata.INSTANCE_PROOF_SHA256` stamp.
  Ordinary starts leave proof metadata alone; migration and changed declarations re-read under the proof lock before updating it.
  Reset marks the old generation as invalidated under the host lock directory before changing Incus, so stale prefetched listings re-read authoritative metadata.
  Delivery rechecks invalidation after boot and holds the proof lock through the guest exec and boot stamp; it cannot overwrite a completed reset with revoked proofs.
  Normal starts derive the seed once, and TUI restarts reuse their listing.
  Every copy clears inherited generations, verifiers and boot stamps before applying fresh branch overrides.
  Every prefix of a namespace normalizes to that digest, compared in constant time.
  `ProofCarriers` derives the authentication headers from tool declarations, including Basic and custom headers; Claude supports API-key, OAuth and Vertex carriers independently of the selected account mode.
  Both HTTP and WebSocket handling call `authorize` before account resolution, compatibility checks, translation, token acquisition, injection and inheritance substitution.
  Verification and account resolution use one configuration snapshot across an asynchronous refresh.
  Missing or incorrect proofs relay unchanged, including authentication and destination semantics; unknown addresses never get defaults.
  Valid proofs still obey account selection, including missing-account failures.
  A proxied namespace without declared placeholders produces a warning once per tool and namespace on configuration load; it blocks injection for its own entries.
  `authorize` verifies every proof the request carries for the domain, without requiring one per namespace there (#1423), except on Claude's domains; `RequestContext.proven` holds the namespaces that verified, and `injectHeaders`/`injectWebSocketAuth` inject the selected entry only when they cover `ResolvedToolProxy.namespaces()` (`provesOrWarn`), so a fallback to another tool's entry or another namespace's proof never spends a credential.
  The `inherit` swap covers only proven namespaces: a proof in a header no carrier reads is never replaced by the parent's.
  Single-tool validation checks its own namespace, leaving borrowed declarations to the proxy's combined check; an unscoped credential needs `config-namespace` before placeholders.
  HTTP and WebSocket account refusals log the instance and reason, including the missing pinned account.
  Unexpected credential-refusal and WebSocket setup failures log the exception class only, since exception messages can contain authentication values.
  WebSocket failures after acceptance name relay setup.
  Unrecognized proofs wait for the next permitted listing on a shared timer and worker, with a bounded wait; independent credentials pass through immediately.
  A failed listing clears credential verifiers and acknowledgement state.
  `isx account reset-proofs` writes the new generation and verifiers under the instance proof lock, signals the proxy and polls host-only `GET /credential-proofs?instance=<name>`.
  Success requires both the expected generation and verifier fingerprint; timeout is an error, and authorized requests and WebSockets continue.
  A running guest receives the new credential seed and tokens without changing its MCP secret; failed delivery leaves recovery pending.
  Refusal diagnostics are bounded and deduplicated, contain no authentication values, and do not warn for ordinary independent credentials.
  The 256-entry request-warning window expires after five minutes so later callers can still be diagnosed.
  `/activity` counts refused injections, including anonymous and independent pass-through.
  It also counts claimed proofs apart (#1586): `refused_proofs` were checked and matched nothing, `refused_address_misses` came from an address no instance has and were never checked.
  The host's own credentials claim no proof, so they are in neither.
  Refusals while the last listing failed count in neither (`registry_stale` says so, and also while a listing has hung past `InstanceRegistry.LISTING_HUNG_AFTER_MS`, `listingUnavailable`), nor do refusals with no registry, from an address a stale listing lacks, after a refresh that timed out or listed nothing new (`provenOrWarn`'s `viewBefore`), or from a stale snapshot (`refusedProof`'s `current`).
  `last_address_miss_at` is when the last miss was; `isx doctor` fails only on a recent one with no traffic placed since.
  `unplaced_running` names the running instances with no traffic attributed, and each instance's `identified_by_proof` says its proof or secret alone named it (`ApiActivity.proofFlow`).
  None of these are on `/health`.
  The existing guest exec writes proofs and the secret into `/run/isx`, root-owned and readable by group 1000.
  Login profiles replace only isx placeholders or earlier proofs in variables the build exported.
  VM recovery and uninitiated reboots retain credential proofs while replacing the boot secret.
  Normal lifecycle request budgets stay unchanged; migration or changed declarations add one metadata read under the proof lock.
  A container's `proofs-v4:<generation>` boot stamp and a VM's version-4 guest marker upgrade previous proof deliveries.
  Version 4 installs `GitHubGitCredential`, the system Git credential helper for `https://github.com` (#1420), during proof delivery and GitHub tool setup.
  It reads the current delivered proof at each credential request, so plain Git can authenticate without a login shell and after a proof reset.
  It stores no token and leaves other hosts, protocols and user configuration alone.
  The VM probe also checks the delivered generation, so a delivery racing a reset is repaired on the next preparation.
  Old shells and long-running tools need a new login or restart; doctor requests a rebuild for old API-key Claude images without #1113's approval script.
  Every build gets an address and proofs, including unpinned defaults, before tool setup, clones and primes.
  Successful builds revoke and scrub temporary proof state, including managed Claude and Codex caches, before publication; retained failures are revoked and get fresh proofs on their next isx start.
  Project create and update use temporary proofs while retaining their account pins, baked-identity checks and untrusted host-route lineage.
  Their cleanup attempts revocation, scrubbing, shutdown and address release even when an earlier step fails; failed cleanup prevents publication.
- **`<ns>=inherit` (#1110): a nested isx shares its parent instance's grant on purpose.**
  `AccountResolver.INHERIT` is a reserved account, selected by a pin or as `<ns>.default`, that resolves to itself whatever is configured.
  An `accounts:` entry of that name is never served, and `value()` gives nothing for it, not even the flat credential.
  `ToolProxyResolver.resolveConfigValue` resolves no entry for it, and `ClaudeConfig.account()`/`accountNamed` give none.
  `GhSetup.bakedAccountIdentity` gives `inherit`, so a move to or from it re-derives the git identity through the proxy.
  It cannot see the parent's account: a parent that switches GitHub accounts leaves the inner identity on the old one until a re-pin or a new branch (#281's mismatch, documented in `docs/CONFIGURATION.md`).
  `CredentialCheck` counts an inherited namespace as supplied (it skips the `credentialProblem` of a tool whose namespaces are all inherited, and `ToolProxyResolver.missingSecrets` skips inherited keys) and refuses the branch outside an instance, `default: inherit` included.
  A Claude build for `inherit` takes the API-key form (`ClaudeSetup.envEntries` with no account).
  `ProxyCredentials.inheritedNamespaces` carries the namespaces resolved to it.
  `MitmProxy.contextFor` builds an `InheritedProof.Swap` for those a request's domain spends (`namespacesForDomain`), from the parent's credential seed in `/run/isx/credential-secret`.
  The secret is read off the event loop: with every config read, every second by `refreshNesting()` on the config watcher's thread, and by `executeBlocking` when a request finds it more than a second old, so it does not depend on the watcher.
  None there is a 502 through `UnknownAccountException`, as for a missing account.
  `presentInheritedProofs` then replaces each whole `<prefix>isx_<32 hex>` of a declared prefix of that namespace with the parent's proof under the same prefix.
  It runs on every request header, HTTP and WebSocket alike, after the normal injection, with a `Basic` credential decoded first.
  The carrier stays the instance's own, so Claude's API-key, OAuth and Vertex forms all work without the inner isx knowing the parent's mode.
  A prefix two swapped namespaces declare is left alone.
  The swap is constructed only after the instance's own proof passes verification.
  A static placeholder or a caller's own token is never swapped, and the Anthropic path does not strip an inherited `x-api-key`.
  Inside an isx instance (`InheritedProof.nested()`: the secret file exists), `resolveAcrossAccounts` adds the domains of every tool declaring a placeholder, credential-free, since a pin is not known when the intercepted domains are decided.
  `ConfigWatcher` asks `MitmProxy.refreshNesting()` every second and reloads when it turns true, as at boot, when the proxy starts before the parent's isx delivers its secret.
  It stays true until a reload completes (`nestingReloadDue`), so the watcher retries every 30 s while reloads fail.
  `AccountSelection.validate` refuses `inherit` outside an instance; `isx init` refuses it as an account name.
  Tests never find the box's own secret: the parent pom points `isx.instance-secret-file` at a missing file, and a test that wants one sets that property.
  The outer proxy verifies the swapped proof as it verifies the parent's own; deeper descendants cannot obtain it with forged or unrelated proofs.
  See DESIGN.md "Proof tokens".
- **`mcp.isx.internal` is answered, not relayed (#915).**
  `routeRequest` hands `ProxyConfig.MCP_DOMAIN` to `McpBridge` before any other domain check (a WebSocket to it is refused): `isx mcp` over MCP's Streamable HTTP for an instance stamped `mcp-caller`, identified by `InstanceRegistry.identifyWithLineage()` with `mcpCaller()` (address + secret + stamp; an unstamped caller it identifies is refused but its traffic counted as its own; one throttled refresh on a refusal, as for `identify()`).
  Each session is a child `isx mcp --caller-instance <name>` (the `isx` beside `isx-proxy`, else `~/.local/bin/isx`, else PATH; tests swap the command with `useMcpCommand`), one per instance.
  The keepalive tick (30 s, under the 120 s idle timeout) also ends sessions whose instance lost the stamp, and `stop()` ends them all.
  Never block the event loop on the child: starting it runs in `executeBlocking`, its pipes on virtual threads.
  See `mcp.md`.
- **Host routes are forwarded, never relayed (#936).**
  `HostRouteRelay` answers every request whose `Host` or SNI is a `host-routes:` name or under one, checked in `routeRequest` before `mcp.isx.internal` and everything else: both must equal the route's name (421), a name under it gets 404, and a WebSocket to either is refused.
  The caller passes by `InstanceRegistry.identifyWithLineage` (address + secret, one throttled refresh on a refusal) and `HostRoutes.Route.admits` (its leaf template), and is refused before its body is read.
  Never admitted: a project-local instance (its type, `project` stamp or build source), and one that `Metadata.untrustedReason` calls untrusted, it or any ancestor in the listing.
  That is the inherited `untrusted-lineage` stamp or `type: failed-build`; the registry also refuses a `parent` ending in `-failed-build` (`Metadata.untrustedByName`, never stamped on a copy).
  A build container's copy or create request sets the stamp, only `BuildCommand.trustBuilt` removes it, after the swap and inside its lock (`TemplateLock.replace`'s after-swap step), and a branch's copy request stamps an untrusted source's reason over anything its caller passed.
  A template still carrying it is outdated (`BuildCommand.isImageOutdated`).
  The relay drops every client `X-Isx-*` header by `HostRouteRelay.headerKey` (every non-alphanumeric folded into `-`, as a CGI-style controller reads it), the hop-by-hop ones and those named in `Connection:`.
  It sets `X-Isx-Instance`/`X-Isx-Template` and the route's own secret (`HostRouteSecret`, `X-Isx-Route-Secret`), which the controller checks before trusting the rest.
  It forwards the path and query (never an absolute URI) over plain HTTP to the loopback target, stored canonical by `HostRoutes` so it is never looked up, with no credential.
  The route secret is host-only: an owner-only file under `~/.config/incus-spawn/host-routes/`, loaded or created in `useConfig`.
  A route without a usable one stays claimed and answers 503, never a relay.
  It is stripped from response headers (by name and by value) and from a reason phrase, redacted from `HostRouteRelay.Served.toString()`, and never logged; nothing may hand it to a guest.
  `ConfigWatcher` reloads on any change to a secret file, so deleting one rotates it, and on a `chmod` of one or of the directory on Linux only (macOS's polling watcher sees mtimes, not modes).
  Every check is made before the file is opened (a FIFO would block `reload()` for good): its type, and that it, `host-routes/` and the config directory belong to the proxy's uid (of a file it created, never a name lookup) with nobody else able to write them: no group write on `host-routes/`, and on the config directory only by a user-private group (its gid equals the proxy's uid; never a gid read off a file made inside it, which takes a setgid directory's group).
  A refused caller's retry is `InstanceRegistry.refreshAfterNow`: one listing started after the call answers every waiter, so a burst of wrong secrets costs one listing.
  The routes live in `ConfigState` (replaced whole on reload) and their names in the routing's intercepted domains, from `ToolProxyResolver.hostRoutes`, which `resolvedDomains` includes for the CLI's DNS writers.
  See DESIGN.md "Host routes".
- **`security.ipv4_filtering` is load-bearing**, not incidental hardening: without it a container can re-address itself as a neighbour and spend that neighbour's credentials, so the whole model would be decorative.
  Set by `InstanceLifecycle.configureBranch` (a refused setting is retried without it and warned about, never fails the branch), re-armed on every stopped start by `InstanceLifecycle.prepareForStart` (package-private, reached only through `startForUse` and VM agent recovery; both shells get there through `InstancePrep.repairBeforeUse` and `readyForUse` -- `IpFilteringRearmTest`, `InstancePrepTest`), which also restores it on instances the fallback below turned off.
  **Setting it succeeds even where it cannot be enforced**.
  The failure appears only when the instance starts and Incus installs the per-NIC rules (`ebtables -t filter` under xtables, the nft equivalent otherwise), so a host without that kernel support would accept every branch and start none of them.
  The macOS appliance kernel needs `CONFIG_NF_TABLES_BRIDGE` for this (#905); its smoke test asserts it.
  `InstanceLifecycle.startInstance` (package-private, like `prepareForStart`, so code outside `lifecycle` starts an existing instance through `startForUse`, and `isx update-all` a template through `startTemplate`, #1457; the other public start paths, `RuntimeSetup.prefetchAndStart` and `BuildAccounts.start`, are for an instance just created) is therefore the way isx starts an instance: it recognises that one failure, drops the setting, says plainly what is no longer enforced, and retries.
  Keep the match narrow -- it strips a security setting, so any other start failure must surface as itself.
- **Per-request credentials.**
  `RequestContext(domain, instanceName, creds, routing, usesDefaultCredentials)` replaces the bare `domain` parameter down `routeRequest -> handleApiRequest -> streamApiRequest` (the relay's `RelayHooks.prepare`), or `-> retainVertexRequest -> sendVertexRequest`, to `injectHeaders`.
  Only a Vertex model call is held, in a `BodySpool` under the shared `SpoolBudget` and `DiskReserve`, released once an answer other than a 401 has come and the body has all gone, or the exchange ends (#1315; DESIGN.md "A body the proxy must hold"): every other API request streams through `Upstream.relayRequest`, and its credential must be set in `prepare`, before any of the head leaves; `--debug` captures 64 KiB of each body as it passes, never more (DESIGN.md "API request bodies").
  Routing is global (which domains are intercepted never varies by account); only the credential inside the entry does, so a pinned instance gets its own `ToolProxyRouting` built by the pure `buildRouting(list, warn)`.
  Everything derived from the config -- its serialized tree (the one every selection resolves against: serialize once per read, never per request), default credentials and routing, tool setups, the per-namespace indexes, and the `credentialsBySelection`/`mismatchesByInstance` caches (the first also caching a dangling pin's `UnknownAccountException`, #891) -- is one immutable `ConfigState`, built eagerly by `useConfig()` (the single path for the `MitmProxy(..., ConfigFingerprint.Loaded)` constructor and `reload()`) and **replaced whole on every reload**.
  A late request writes only into the old state's caches, so a rotated token is never put back.
  Never add a lazy read of config or tool setups: it escapes the fingerprint and can revert a reload (#837).
  The Vertex *token* cache stays global on purpose: `gcloud auth print-access-token` returns the host's gcloud user credential regardless of project, so only region/project come off the per-request creds.
  `authError` transitions are gated on `usesDefaultCredentials()` -- a pinned instance's broken token is reported to that instance, but must not set or clear the host's own auth status.
- **What a build derives from an account is stamped and reconciled.**
  `ToolSetup.bakedAccountIdentity()` returns Claude's `effectiveType()` (two accounts of one mode leave the image identical) and GitHub's account name plus `#` and a 12-hex SHA-256 fingerprint of its token and email (`user.name`/`user.email` come from whoever the token belongs to, so a token replaced in place with another user's is a change of identity too -- #281; a stamp without a fingerprint, from an older build, is re-derived once).
  Namespaces that bake nothing return `""` and are freely interchangeable.
  Stamped at build as `user.incus-spawn.account-identity.<ns>`.
  On a re-point, a tool that `canRebakeForAccount()` is asked to fix itself up -- `GhSetup.rebakeForAccount` clears the git identity and asks the API again, which resolves through the proxy and so answers for the new account by itself.
  `AccountSelection.staleIdentities` maps each stale namespace to the *account* to re-derive for, and `InstanceLifecycle.reconcileAccountIdentities` drives that, stamping back the tool's current identity, and is called from **all three** places a pin can change or take effect: `BranchCommand` after start, `AccountCommand`'s `set` when the instance is running, and `InstancePrep.readyForUse` on the next `isx shell`/`isx run` or TUI shell.
  All three are needed.
  Only the last catches an instance branched `--no-start`, and without the first two an instance used over `incus exec`, SSH or an IDE would keep committing as the template's account for as long as nobody happened to run `isx shell`.
  A tool that cannot (Claude: the mode is in `/etc/profile.d`, which a running agent has already read) has the swap refused by `AccountSelection.incompatibilityReason`.
  A child template's build re-derives an identity inherited from its parent that no longer matches (`BuildAccounts.settleIdentities`, from `BuildCommand`, *after* tool setup: a parent without gh still carries a GitHub stamp, and an identity written first would create the `.gitconfig` whose absence is how `GhSetup` knows to write its git defaults).
  The copy carries the parent's `.gitconfig`, which gh's setup would otherwise keep while the end-of-build stamp claimed the current identity.
  `GhSetup.rebakeForAccount` is a no-op where `gh` is not installed, since every build stamps every namespace.
  **Stamps come from the guest, not config.yaml:** `settleIdentities` asks each tool in `AccountSelection.rederivableNamespaces` (owning tool in the chain, can re-derive, known to `namespaceSetups`) `lacksBakedIdentity()` (one exec for gh), re-derives a missing identity an account can supply (`BuildAccounts.toRederive`; the stale test is `AccountSelection.needsRederive`, shared with the branch reconcile), and where none can, warns (`unbakedIdentityWarning`) and stamps `Metadata.ACCOUNT_IDENTITY_NONE`.
  A token configured later is then an ordinary stale identity.
  A parent with an identity but no stamp keeps it.
  Never stamp the marker for a tool that cannot rebake: there a mismatched stamp refuses the account.
  Every build also stamps `Metadata.ACCOUNT_IDENTITY_VERIFIED`.
  An instance without it (template from an older isx) is `unverified` in `AccountSelection.identityReconcile`, checked in the guest once by `reconcileAccountIdentities` while an account is configured, repaired, and marked (and stamped with the account's identity when it has one, so a later change is still seen; any reconcile of an unmarked instance that did something marks it) -- once per instance, since the marker is written on the instance, never its template.
  The guest is asked *before* the wait for an address, which only re-deriving needs.
  An airgapped instance is never reconciled (`identityReconcile` returns nothing for it): it has no proxy to re-derive through and never gets an address, so the wait would cost every `isx shell` 30 s.
  A verified instance's reconcile is one read and no exec (`InstanceLifecycleRequestBudgetTest`).
  The plan is decided against the built-in tools that can re-derive (`AccountSelection.rederivableSetups`), and the tool definitions are read only when it finds something.
  `InstancePrep` and `BranchFlow` pass the read they already made (for `ensureReady`, and the pre-start prefetch), so on the shell and branch paths the reconcile costs no request.
  `ClaudeSetup`/`PiSetup` `envEntries(params, selection)` and `GhSetup.install(c, params, selection)` all take the **template's** account, not the global default.
- Three auth modes for Anthropic domains (priority: Vertex > OAuth > API key): OAuth mode strips `x-api-key` and injects `Authorization: Bearer <token>` for Claude Pro/Max users.
  Vertex mode does three-way routing -- passthrough for Vertex-formatted requests, standard-to-Vertex translation for `/v1/messages` (`VertexBodyRewriter`: streaming, bounded memory, its departures from the old tree translation listed in DESIGN.md), and direct forwarding for non-messages endpoints.
  A passthrough body loses only each tool's `strict` (`VertexBodyRewriter.vertexFormat`) and, for an object, insignificant whitespace; a non-empty one must be valid JSON (else 502) with well-formed top-level and tool names (else 400).
  One whose `Content-Encoding` headers name any coding but `identity` is not read, and is forwarded unchanged.
  API key mode replaces `x-api-key` with the real key.
  Anthropic auth is declared via `ClaudeSetup.proxy()` with `type: anthropic` but remains hardcoded in `MitmProxy` (too complex for the generic tool proxy system); `buildRouting()` filters `type: anthropic` entries from domain maps
- GitHub, Bob, and Codex/OpenAI credential injection is handled via tool-contributed proxy entries (see Tool System), not hardcoded.
  `ToolProxyResolver.resolve(SpawnConfig)` resolves configuration from `value`/`config-path`, uses `${configKey}` template substitution in auth fields, and produces a per-domain `ResolvedToolProxy` list.
  `MitmProxy` stores these in per-domain candidate lists (`Map<String, List<ResolvedToolProxy>>` for exact domains, grouped list for wildcard suffixes); `findToolProxy(routing, domain, requestUri)` does exact-then-wildcard lookup with path-aware selection: path-scoped entries are tried first, the longest matching prefix winning (`ResolvedToolProxy.pathMatchLength`, first loaded on a tie), then unscoped entries.
  Two tools can claim the same domain with different `paths:` scopes; an unscoped collision still warns and uses first-wins.
  A tool with a prefix that is not a literal `/`-rooted path is refused where definitions load (`ToolDefValidator.refusal`, like an invalid namespace); `ResolvedToolProxy.pathMatchLength` never matches one without a leading `/`, so an empty prefix can never widen a scope to every path.
  Nor does a scope other than `/` match a request path an upstream may resolve elsewhere (`ResolvedToolProxy.mayResolveElsewhere`, the list in docs/TOOLS.md "Path-scoped injection"; #1453).
  Matching is on the raw target, and refusing is chosen over normalizing: the forwarded request is never rewritten, and the proxy never has to guess the upstream's normalization.
  `ToolDefValidator.pathScopeProblem` refuses a prefix the same check flags, since it could never match, and a prefix with `;`.
  `isInterceptedDomain()` is an instance method (not static) to include tool proxy domains alongside built-in ones.
  It answers from `allInterceptedDomains` (every account's domains, as bridge DNS and certificates have them), never the default account's entries alone: an instance pinned to an account whose entry the default lacks would be relayed with nothing injected.
  `ProxyCredentials` only has Anthropic-specific fields (`anthropicApiKey`, `oauthToken`, Vertex settings) -- `ghToken`/`bobApiKey`/`openaiApiKey` have been removed
- WebSocket passthrough: handles Upgrade requests by establishing an upstream WebSocket connection (with credential injection via both Anthropic auth and tool proxy lookup), then relaying frames bidirectionally with keepalive pings and close-code propagation.
  `routeWebSocket` completes the client's handshake (`completeHandshake`, the deprecated `ServerWebSocket.accept()` on purpose) before injecting anything or dialling, because the handler runs before Netty validates the handshake (#972); keep any new rejection (e.g. `reject(502)`) before it.
  Used by Codex CLI for `api.openai.com`
- Caches OCI blobs by SHA256, Maven/Gradle artifacts by repository path (confirmed with upstream per `ArtifactCacheTiers`, see below), and npm tarballs from `registry.npmjs.org` with ETag-based packument verification.
  The caching lives in `ArtifactCacheHandler`, built only from `Upstream` (the upstream clients, async DNS, backoff, relay and response helpers it shares with `MitmProxy`) and given no `RequestContext`, so by its types it cannot inject a credential; keep it that way.
  npm's per-version digest lookups go through `probeClient` (`fetchNpmVersion` → `fetchSmallBody`) like Maven/Gradle checksums.
  A `5xx` is asked once more (while over half the client's budget is left), since a failed lookup relays the tarball uncached and unresumable (#925).
  The digest is `dist.integrity`'s SHA-512, else its SHA-1, else `dist.shasum`; SHA-1 only when the integrity is absent or lists only `sha1-` hashes (algorithms compared case-insensitively), never for any other integrity that gives no readable SHA-512 (`npmDigest`, #1465).
  An npm tarball's `.etag` and `.shasum` are first written by its commit (`Verification.ofNpmTarball` → `VerifiedArtifactStore.verifyAndCommit`, under the per-artifact lock), never before the download: a sidecar written then can end up beside another download's bytes (#1464).
  Every commit carries its lookup's time (`Verification.lookedUp`), stamps the stored checksum (or `.shasum`) with it, and is skipped (`Commit.SUPERSEDED`) over a copy whose stored checksum is newer, so a slow download from before a republish cannot replace the republished copy (#1538).
  npm's lookups are ordered the same way: `checkNpmTarballCache` and `reconcileNpmCopy` take the lookup's time, an answer older than the `.shasum`'s confirmation changes nothing (the foreground serves the copy), and `renewConfirmation` stamps the lookup's time, never moving it back.
  Afterwards only lookups touch them, every one under the same lock: `checkNpmTarballCache` renews `.shasum` and updates `.etag` when it confirms the copy and evicts the tarball with both on a mismatch or a gone version, and `reconcileNpmCopy` (the background confirmation) renews, expires or evicts.
  A cached tarball whose lookup gives no digest is served only when the registry is unreachable (`SidecarAnswer.unreachable()`); a `404`/`410` evicts it and anything else relays (`checkNpmTarballCache`, #787).
  An unchanged packument ETag is not enough to skip the lookup: `npm ci` fetches no packument, so a hit also needs its confirmation (the `.shasum` mtime) within `ArtifactCacheTiers`, and is confirmed in the background past `fresh` (`npmCacheState`, #1466).
  The npm registry keeps the unreachable backoff like the `Revalidation` domains (`keepsBackoff`, read by `requestWithAsyncDns`): during it a cached tarball skips its lookup and its background confirmation.
  Never wait on the network inside an ordered `executeBlocking` (the default): every MITM connection shares one context and so one ordered queue, and a raw-socket lookup there ran every cold-install lookup one at a time (#960).
  The npm disk checks stay ordered on purpose, behind the packument's ETag write.
  All three commit through `Verification` (`fetchCacheAndServe`): nothing reaches the cache unverified.
  A cache download that stalls (`downloadIdleSeconds`, 20s, not counting our own backpressure) or breaks mid-body is resumed with `Range` + `If-Range` inside the same client response (`CachingDownload`, #925), since npm silently drops an optional dependency whose download breaks.
  The head wait and every resume fit into `clientSilenceBudgetSeconds` (110s since the client's request or last byte, threaded as `started` from `routeRequest`), below the MITM server's 120s idle timeout, which otherwise drops the client silently.
  The stall watchdog re-checks whenever a pause ends (`resumeWhenWritable`) so a late head cannot outsleep it.
  No validator (a strong ETag, or with no ETag a Last-Modified a second or more before `Date`), no resume, and then no stall cut either (`neverResumable`): only the client's budget ends it.
  A `206` continues only unencoded, with the remaining `Content-Length` and the first response's own validator (`resumesAt`), whatever `If-Range` got, since some servers ignore it.
  Both rules, the validator (`HttpValidator.strong`) and the whole continuation check (`HttpValidator.continues`), live in `common`, shared with `DownloadCache`, so the two paths cannot drift apart again.
  A connect timeout cut short to fit the budget does not start the unreachable backoff (`requestWithAsyncDns`).
  Plain relays get the same budget with no resume (`RelayWatchdog` in `relayRequest`, #929): counted from the client's last byte, not while its body streams, so uploads are not cut while the client sends.
  The cache handlers' own relays (an error answer to a fetch or a redirect, and `CachingDownload`'s uncached fallback after a cache write error) go through `relayResponse` (`relayBody` for the fallback, under the download's own head), a `RelayWatchdog` counting from `started` or the client's last byte, not from relay entry (#1192).
  `relayRequest` has no default clock: every caller passes `since`, `routeRequest`'s `started`, so the wait for `authorize` on the API path and a cache handler's own lookups before it falls back to a relay are counted too (#1059).
  A Vertex model call gets one once its body is in (`Upstream.watch`, #1059), through the token fetch, a 401's retry and the answer; it passes its `ended` handler (spool and exchange) as `onEnd`, since the watchdog takes the response's end and close handlers and a later handler replaces an earlier one.
  Only `HostRouteRelay` pipes with no watchdog.
  `relayRequest` pauses the request body on entry and `pipeRequest` streams it once the upstream request exists (#1175); never aggregate a relayed body (`clientReq.body()`), which holds an upload whole in the shared proxy's heap, and never leave one unpaused across an async step, since Vert.x drops a body nobody is reading and the relay hangs (#1164).
  So a request with a body is relayed from `routeRequest` before anything waits: to a caching domain (`cacheHandler`, the one list of them) it never reaches the cache handlers, which wait.
  The relay's budget runs while the body is paused (connect included) and while upstream holds it back; a response that ends before the body was read closes the client connection once the rest is read and dropped, bounded by the shared `LingeringClose` (`RelayWatchdog`, #1623), and a client gone before the upstream request exists resets it there, since no handler was set to tell.
  A client that leaves before its answer ends (while the head is awaited, or mid-stream) resets the upstream request from the watchdog's close handler, as a cut does, on every relay the watchdog guards, Vertex included (#1448).
  Otherwise upstream generates, and bills for, an answer nobody reads.
  `Upstream.copyRequestHeaders`, the head of `relayRequest`, of credentialed requests and of a cache fetch, drops the hop-by-hop headers and those the client names in `Connection:`, except `Content-Length` and `Expect`, which the relay reads to send the body (#1360); it runs before `prepare` injects anything, so a client cannot name an injected header away.
  `HostRouteRelay` and the WebSocket path build their own heads.
  Don't lower the upstream client's read-idle timeout instead: slow uploads get no bytes back
- **Maven/Gradle cache integrity** (#555, DESIGN.md "Maven/Gradle cache integrity"): serve a cached artifact only when a direct fetch would have returned the same bytes at its last confirmation with upstream, and that confirmation is recent enough.
  **`ArtifactCacheTiers`** (`artifact-cache:` `fresh`, default 2h; `max-stale`, default 7d) decides from the stored checksum's mtime, which `VerifiedArtifactStore` sets on commit (to the commit's lookup time) and renews (best-effort, to the probe's time, never backwards) only on a match from an answer that could have evicted (`mayEvict`): fresh is served with no request, stale is served and confirmed in the background (single-flight, at most 16 in flight, the rest queued; a change evicts for the next request, an inconclusive answer expires the confirmation), older is **confirmed before serving**.
  `reconcile` takes the probe's time: an answer from a probe older than the stored checksum's mtime changes nothing (`Outcome.SUPERSEDED`, #1542); the foreground then serves the copy without the answer's checksum header, a sidecar request gets the copy's stored sidecar, else is asked upstream once more (`keptSidecar`; a 502 if that answer is superseded too), and a background confirmation does not expire it.
  `settleWithHeader` applies a sidecar answer only to the copy it was reconciled against: `reconcileCopy` returns the `FileState` it judged under the lock, and `storeSidecar`/`dropSidecar`/`isCopy` refuse any other, making the outcome `SUPERSEDED` (#1555).
  Overlapping lookups are joined by `SingleFlight` (#1567): a sidecar settle (`sidecarSettles`, by domain and path: GET, reconcile, header, store and `keptSidecar` as one unit returning a `SidecarReply`), a confirmation (`confirmations`, by artifact, foreground and background alike), a `~/.m2` probe and import (`hostImports`) and an npm version lookup (`npmLookups`: each ask is joined, but the 5xx retry is each caller's own, on its own `started`).
  The shared work never touches a client response, and the key is released before any caller hears of it; downloads are not joined.
  `Revalidation.mayWithdraw` domains (Plugin Portal) never get the tiers.
  `artifact-cache:` stays a raw value in `SpawnConfig`: a typed binding would let a mistyped section fail the whole config.yaml.
  Hits are counted in `ArtifactCacheStats` and logged as one summary at most every 5 s, only after activity; don't add a per-hit log line back.
  Older is confirmed as `Revalidation` says per domain: `HEAD_CHECKSUM` (Central: a `HEAD`'s `X-Checksum-SHA1`, falling back to `.sha1`) or `SIDECAR` (portal, Gradle distributions).
  A domain without an entry is never cached.
  A miss is only the GET, committed if it matches the same response's `X-Checksum-SHA1` or the sidecar fetched afterwards; no usable checksum means served but not cached.
  Past the tiers, only an unreachable upstream (`SidecarAnswer.unreachable()`: connect failure or 5xx; 30s per-domain backoff) serves unconfirmed.
  `X-Checksum-SHA1` is what makes Maven skip its `.sha1` request.
  For a copy served on a trusted confirmation (`serveTrusted`) it is the stored checksum, and sidecar requests get the stored copy too, or Maven would pay the round trip the tiers save.
  For any other copy it must be upstream's **current** value, never synthesized from the store, which would turn the client's check against upstream into a check against our own cache.
  `Revalidation` is also the caching allowlist, and its javadoc states why a domain must be vetted even though hits are confirmed: offline serves are unconfirmed, and serves within the tiers not confirmed now (so they rely on the repository not changing or withdrawing releases), the confirmation must be sound for that repository (`SIDECAR` only where artifact and sidecar are published together and never replaced), and the cache is shared across instances without per-client authorization (public repositories only).
  Keep those reasons, not "immutability", in comments.
  Send every `probeClient` request through `Upstream.sendProbe()`, with `probeReadIdleSeconds` below `probeOptions()`' request timeout: it closes a connection with nothing read since the request, the only thing that evicts a silently dead h2 connection (a request timeout resets just its stream).
  Don't give `probeClient` a read-idle or all-idle timeout instead: Netty ticks the first from the last read, cutting a request just sent on a long-pooled connection, and requests' writes restart the second (#1007).
  `Upstream.resolveHost` keeps the expired entry's address while the refreshed answer still lists it, since connections pool per address; `requestWithAsyncDns` drops it on a failed connect so the stickiness never pins a dead address (#862).
  Keep `PoolOptions.setHttp2MaxSize` above 1, or bursts to HTTP/1.1-only or unreachable hosts serialize.
  `~/.m2` imports copy/reflink, never hardlink.
  Commit/reconcile/evict go through `VerifiedArtifactStore`'s striped lock; don't touch the cache files around it
- **Proxy lifecycle locking**: `ProxyService` guards mutating operations (`restart`, `stop`, `install`, `uninstall`, `startService`, `reinstallIfChanged`, `upgradeIfNeeded`) with a `HostLock` (`proxy.lock` in `configDir()`).
  Public methods acquire the lock then delegate to private `*Locked()` variants, because `HostLock` is not reentrant.
  When adding new operations that modify proxy service state, follow the same pattern.
- **The service execs `isx-proxy`, never `isx proxy start`**: the CLI dropped Vert.x when the proxy became its own module, so it cannot serve the proxy in-process.
  `install()` resolves `resolveProxyBinaryPath()` first and refuses to write the unit/plist without one (`printMissingProxyBinary()` names the JBang aliases, since JBang installs each separately -- issue #701).
  A unit exec'ing `isx proxy start` restarts itself forever: the command sees the service installed and unhealthy and restarts the unit it is running under, with `reset-failed` clearing systemd's rate limiter each pass.
  Units already on disk are covered three ways: `ProxyStartCommand` resolves the proxy binary *before* any service management and exits `EXIT_CONFIG` when it is missing (restarting cannot fix an absent binary); `ProxyService.isSupervisedInvocation()` matches systemd's `INVOCATION_ID` against the proxy unit's own invocation (Linux) or this process's own pid against what `launchctl print` reports for the job (macOS, #977 -- restarting the job from inside it can end this very process via `bootout` before it reloads) so a legacy unit/plist's `isx proxy start` launches the proxy instead of restarting itself; and on macOS, where launchd has no `RestartPreventExitStatus` (a `KeepAlive` job exiting 78 is simply restarted) and `RunAtLoad` would revive a booted-out job at the next login, `haltUnusableMacOsService()` boots the job out **and** deletes the plist, declining whenever the proxy still answers on 127.0.0.1.
  `upgradeIfNeeded()`/`reinstallIfChanged()` call it before `needsMacOsPlistUpdate()`, which cannot describe a plist whose binary is gone.
  `startScriptIsStale()` compares the exec line, which no legacy `isx proxy start` script can match, so upgrades rewrite them.
  The supervised branch never calls `restartLocked()` (it runs in the foreground instead), so it never rewrites its own stale plist that way either.
  `ProxyService.migrateMacOsPlistIfSupervised()` rewrites it directly, under the proxy lock, with no bootout or bootstrap, so the next login execs `isx-proxy`.
  `install()` and `isx proxy install` (before its running-service path) refuse with "run `isx init` first" until `Environment.hasBeenInitialized()` (`ProxyService.initComplete()`, #968): the proxy exits `EXIT_CONFIG` before init, so a service installed or restarted then could only report "not responding".
  So does every restart and start (#1048): `restartLocked()` (under the lock, after `restartIfUnhealthy`'s health recheck), `startService()`, and `reinstallIfChanged()`/`upgradeIfNeeded()` before they rewrite the service files (after the macOS halt).
  After an `INIT_VERSION` bump the proxy still running is a working one; refusing keeps it.
  Paths the TUI reaches pass a sink (`initComplete(log)`).
  `isx init`'s firewall step defers its restart when the marker is stale; `completeWithProxyService` restarts a running service it found with a stale marker once the marker is written (read from the marker, so an aborted run's restart is not lost).
  The TUI names `isx init` on its status line when the auto-restart was refused.
  `ProxyService.replaceCommandRunner` swaps `runQuiet` for a recorder so `ProxyServiceInitGateTest` fails, rather than restarts a real service, if a guard regresses.
- **The proxy that answers may not be the service's (#1570)**: `/health` passes for any proxy on the fixed ports, another user's included.
  `ProxyHolder.notTheService` compares the pid `/health` reports with `ProxyService.servicePid()` (`MainPID`, or `launchctl print`'s `pid`) and its descendants; a service with no process owns nothing.
  `servicePid()` counts a unit in auto-restart (`activating`) as meaning to run: a unit that cannot bind waits there, where `isActive()` is false.
  With the service stopped or failed (`servicePid()` -1), `ProxyHolder.forService` reports only another user's holder.
  Every path that reports the service running asks `ProxyService.answeringIsTheService` after its health wait: `install()` on both platforms, `isx proxy install`, `isx proxy start` and `isx proxy restart`.
  `isx proxy status` adds `pid`, `pid_owner` and `foreign_holder`, exiting 4 for another user's holder; `isx doctor` has a "Proxy ownership" finding.
  The proxy's bind wait (`bindWaitingForOldProxy`) names the holder and throws at once for another user's, never waiting for it.
  An owner `ProcessHandle` cannot give is never another user's, so it never turns the wait into a failure.
  Never on the shell path: it costs a service-manager fork.
- **launchd: `bootout` is asynchronous, so restarts use `kickstart -k`** (`LaunchdJob`, #916).
  `launchctl bootout` returns as soon as the job was told to go.
  A process that ignores SIGTERM (a hung proxy) is killed only about 5 s later, and until then a `bootstrap` fails with `5: Input/output error` while `launchctl print` still finds the job.
  The old `bootout` + `bootstrap` restart therefore reported success and left nothing loaded.
  `LaunchdJob.restart` restarts a loaded job in place (`kickstart -k`, no unload, nothing to race); only a changed plist is reloaded, and then it waits (`UNLOAD_POLLS`, 10 s) until `print` stops finding the job before `bootstrap` + `kickstart`.
  `restart`'s `kickstart -k` refuses with exit 37 for a job that is already being unloaded (after `isx proxy stop` on a hung proxy); that one waits for it to be gone and loads it afresh, same as a changed plist.
  `start` never reaches `kickstart` at all -- it is only `load` (`bootstrap` + `kickstart`), reached when `startService()` finds nothing loaded.
  Any other `kickstart` failure leaves the job loaded.
  Success is `launchctl` returning 0 **and** the job still `state = running` under the same pid half a second later (launchd shows a proxy that is about to exit as running), never "`print` succeeded" -- `isActive()` still means only "loaded".
  Both `start` and `restart` return a `LaunchdJob.Outcome` (`RUNNING`/`STILL_UNLOADING`/`FAILED`/`EXITED`), not a bare boolean.
  `isActive()` alone cannot tell a job still being unloaded apart from one that loaded fine and is merely unhealthy, and `installMacOs` needs that distinction to report the right cause.
  `FAILED` is `launchctl` refusing; `EXITED` is a job that was loaded and started but did not stay up, which launchd retries after `ThrottleInterval`.
  Only `RUNNING` is `up()`, but `startService()` answers with `launched()` (`RUNNING` or `EXITED`), so `isx proxy start` waits for launchd's retry through `awaitStarted` instead of printing "failed to start" about a proxy that comes up ten seconds later (#1098).
  `restart`'s callers keep `up()`.
  `restartLocked`'s `forceReload` parameter, not a plist comparison inside it, decides whether to reload.
  `reinstallIfChanged` and `upgradeIfNeeded` pass it whenever *they* already know a reload is needed -- including health-assessed drift `needsMacOsPlistUpdate()` itself cannot see -- so callers must not separately rewrite the plist and expect `restartLocked` to notice on its own.
  `bootstrap` also answers 5 for a job that is already loaded, so never bootstrap without checking `isLoaded()`.
  `kickstart -k` honours `ThrottleInterval`: on a job started under 10 s ago it blocks for the remainder.
  The same interval bounds every wait for health after the job is started.
  On macOS `ProxyService.awaitStarted` stretches them past `LAUNCHD_THROTTLE_SECONDS` (which the plist is generated from), so a failed first start is not reported before launchd's retry, and a wait that still times out prints `LaunchdJob.lastRun()` (`state`, `runs`, and `last exit code` or, for a run a signal ended, `last terminating signal`, #969).
  The price is that a proxy which never comes up is reported after 15 s on macOS, not 5 to 10.
  `tryAutoRestart` (`ProxyHealthCheck`) re-checks `/health` under `proxy.lock` before restarting (`restartIfUnhealthy`), so a second `isx` command arriving while the first is still restarting a proxy that is now healthy does not `kickstart` it again and cut every connection.
  Tests replace `LaunchdJob.Launchctl` with a fake launchd (`LaunchdJobTest`); `restartIfUnhealthy`'s restart action is itself replaceable so a test proving the health recheck skips a restart never touches a real service (`ProxyServiceTest`).
  `installMacOs` reloads the proxy job through it too, and writes the plist only right before that, because `restartLocked` takes a plist that is current on disk for the one the job runs.
  Still calling `launchctl` directly: `stopLocked`, `uninstallMacOs` and `haltUnusableMacOsService`, which only unload, and the VM agent in `installMacOs`: a one-shot `RunAtLoad` job that is never kickstarted or expected to stay running, so it does `bootout`, waits until `print` stops finding the job (`waitForJobUnloaded`, up to 20 s), then `bootstrap`, and warns when that `bootstrap` fails (#977: a reinstall while `isx vm start` was still running under the old job otherwise left no VM agent loaded).
- **The foreground `isx proxy start` stops its child on shutdown**: `ProxyStartCommand.ForegroundProxy` registers a hook, before it starts the child, that sends the `isx-proxy` child SIGTERM and SIGKILLs it after 15 s (and skips the abnormal-exit diagnosis, since that stop was asked for), so killing the CLI alone does not orphan a proxy that keeps the ports (#882).
  `ProxyStartForegroundTest` drives it in a separate JVM.
  For a CLI that runs no hook (SIGKILL, OOM), `foregroundCommand()` passes `--exit-with-pid <cli pid>`, an internal argument (not in `--help`), and the proxy exits once that pid is no longer among its ancestors (`ExitWith`, #923).
  Ancestry, never liveness: `onExit()` counts an unreaped zombie CLI as alive.
  The service units must never pass it.
- **Bridge DNS is `address=/<d>/<gateway>` + `local=/<d>/`** per intercepted domain, inside a `# BEGIN`/`# END incus-spawn intercepted domains` block in `raw.dnsmasq` (`BridgeDns`; `ProxyConfig.writeBridgeDns()`/`clearBridgeDns()` replace or remove only that block).
  The `local=` line makes AAAA/HTTPS answer NODATA instead of being forwarded upstream, where real IPv6 addresses would bypass the proxy.
  Never go back to `address=/<d>/::`: `::` reads as loopback, so a nested isx's `DownloadCache` refused every intercepted domain (#814).
  Never infer ownership from line shapes: an orphaned `local=/<d>/` answers NXDOMAIN for the whole domain.
  The pre-block layout is detected by its `::` lines and migrated.
  Anything that rewrites the block takes its domains from `ToolProxyResolver.resolvedDomains()` (all accounts, as the proxy does), never the default account alone.
  Writing `raw.dnsmasq` restarts the bridge's dnsmasq; Incus refuses and reverts the update when the new one does not stay up, so `isx init`'s DNS step tries three times, on both platforms, and on Linux the proxy retries in the background (on macOS it tries once and keeps the install-time block) (#1376).
  A CLI writer gets them, built-ins included, from `ProxyConfig.bridgeDnsDomains(config)` or `configureBridgeDns(incus, config)`.
  Never add a writer that takes no domains: `isx init` once rewrote the block with the built-in ones alone after the proxy's reload, and builds got 401 from GitHub until the proxy restarted (#1354).
  `writeBridgeDns` refuses an empty set; `clearBridgeDns()` is the only way to remove the block.
  On Linux the proxy points the block at the gateway it already resolved and listens on (`ProxyMain.Addresses`, whose writers startup uses and `MitmProxy` is handed for reloads; `--gateway-ip` sets it, and must be a unicast IPv4 address in canonical form or the proxy exits `EXIT_CONFIG`; DESIGN.md says why), never a second bridge lookup, which ignored the override and failed on a bridge with no address (#892).
  The proxy serves only where the CLI looks for it (`ProxyConfig.resolveGatewayIp()`), so a Linux `--gateway-ip` must equal the bridge's current gateway (`BridgeAddress.read()`, never the cached one) when Incus reports it, else `EXIT_CONFIG` (#933).
  With no bridge address to read it stands in for one (#892): it must equal the gateway `isx init` cached, if any (else `EXIT_CONFIG`), and be private (RFC 1918) or loopback, else exit 1 (#1022).
  Never add a separate proxy address the CLI must learn; DESIGN.md names the rejected `proxy-gateway-ip` key and when it would be the path.
  On macOS it looks up the VM's own bridge, and `--gateway-ip`, the credential listener's bind address there, must equal the host's VM-bridge address (`VmNetwork.discoverHostBridgeIp()`) or the proxy exits `EXIT_CONFIG`.
  A wildcard or LAN address would serve the default accounts to other hosts (#937).
  A failed health probe with the block pointing at another address that the host no longer has but that still has a listener on the health port (`/proc/net/tcp{,6}`, since that address no longer answers) is `STALE_GATEWAY`, not `STALE_DNS`.
  The proxy is up and needs a restart (#919).
  The auto-restart (`requireProxy`, `checkOrWarn`, the TUI) restarts the service for it only when the service is active (`serviceRestartCanHelp`): with a foreground proxy it would start a second one beside it.
  On Linux the proxy also notices by itself, `--gateway-ip` or not (#966).
  `Addresses.movedTo()` re-reads the bridge every `GATEWAY_CHECK_MILLIS` (60 s) and before a reload rewrites the block, and on a different address it skips the write (`GatewayMoved`), stops, and exits 1 so `Restart=on-failure` starts it on the new address.
  An unreadable bridge or one with no address is not a move.
  Exit-and-restart, not rebinding in place: the startup path already moves the listener, health endpoint and block together.
- **Redirect rule cleanup**: `ProxyService.uninstall(IncusClient)` removes the service **and** unconditionally cleans up PREROUTING redirect rules (`ProxyConfig.clearRedirectRules()`) and bridge DNS overrides (`ProxyConfig.clearBridgeDns()`).
  `clearRedirectRules()` attempts both firewalld and UFW independently (not else-if) so a system with firewalld installed but inactive still reaches UFW cleanup.
  Firewalld cleanup falls back to editing the persistent `/etc/firewalld/direct.xml` when the daemon is not running.
  `runQuiet()` returns `boolean` success; cleanup callers check and warn on failure.
  `clearRedirectRules()` returns `boolean` so callers like `ResetCommand` can propagate cleanup failures.
  The no-arg `uninstall()` is private — callers must pass an `IncusClient` so DNS cleanup can reach the bridge.
- **`/activity` (#898)** serves `ApiActivity`: per instance, Messages API creates only (`ApiActivity.isModelCall`), counted in `streamApiRequest` and `retainVertexRequest` (`beginModelCall`) and ended once on the client response's end or close, with tokens read from 2xx responses' `usage` by `UsageTap` as `pipeResponse` relays them (cumulative stream counts: keep the max per field).
  Counted calls go upstream with `Accept-Encoding: identity`; nothing else's headers change.
  Each instance's counters carry their own creation time (`counting_since`), never the proxy's start, and a read creates them for every instance the registry knows, so a known instance always has one.
  A read drops those of instances the registry no longer lists, but only idle ones and through `computeIfPresent` against `begin`'s `compute`, and counters made again must show a new stamp or a client's before/after subtraction goes wrong.
  Counters belong to one incarnation of a name (`InstanceRegistry.incarnations()`, the listing's `created_at`): a call or read naming another starts them afresh, so a hand-named instance branched again never inherits the old one's counts (#1063).
  A call's incarnation comes from the lookup that named it (`InstanceRegistry.resolve`, in `RequestContext`), never from a second snapshot read.
  Counters never go back to an earlier registry view (`InstanceRegistry.Incarnations.view`; a late call of the destroyed instance is counted nowhere), or a call and a read from different snapshots would reset each other.
  Order by view, never by `created_at`: a rename hands a name an older one.
  The prune obeys views too: a read never drops counters a later listing made.
- **All traffic on `/activity` (#935)**: every request and WebSocket of the MITM listener is an `ApiActivity.Flow` (`countTraffic` in `routeRequest`, `trafficFlow` in `routeWebSocket`), on the same per-incarnation counters, per domain (`ApiActivity.trafficDomain`: an intercepted domain, a subdomain under its closest intercepted parent, else `(other)` -- never a key per Host header), and `unattributed` for callers that are no known instance.
  A request ends on the response's `bodyEndHandler` or the connection's `closeHandler`: the response's end and close handlers are single slots the relay, the cache and `beginModelCall` already set, so never use them for this.
  A model call ends with that flow, on one clock reading (`RequestContext.flow` → `Flow.carries`, #1281), so its `last_response_at` is never after its domain's `last_activity_at`.
  A WebSocket ends on its close (an early close handler until the relay installs its own, and `flow.end` on every refusal before the handshake).
  Bytes and last activity move at start and end only.
  A flow in flight keeps an unlisted instance's counters from the prune.
  Fields are only added.
- **The health server only reads** (`MitmProxy.healthRoute`): `GET` and `HEAD` only (405 otherwise), `/activity` and `/credential-proofs` a 404 for non-host callers whatever the method.
  It is the listener every container reaches: never add a write endpoint to it.
- **The proxy holds no configuration of its own** (DESIGN.md, #935): routing is derived from host config and `user.incus-spawn.*` stamps, so restart and reload rebuild it.
  A new capability is data in one of those stores with a no-inherit copy rule, written by `isx`, then a signal; any future proxy-side write is a stamp `PATCH` from an authenticated host-only surface, and every grant expires.
  Host callers only; a container gets a 404.
  `ProxyActivity` (`common`) is the CLI's reader of the same JSON -- `ApiActivityTest` pins the round trip.
- `/health` includes the proxy's `pid` only for host callers (`MitmProxy.isHostCaller`); keep any other host-only detail behind the same check, since containers reach this endpoint.
  `ProxyService.signalAccountRefresh()` reads it at signalling time and falls back to `fuser` for older proxies, as `isx proxy stop` does for a proxy started by hand (`stopManualProxy`, #155: `fuser` finds nothing on macOS or without psmisc).
  Tests must never send it: `TempHome` (common, `lifecycle`) and `IsolatedHome` (cli, `command`) swap the signaller for a counter (`ProxyService.replaceAccountRefreshSignal`, `proxySignals()`) for the test classes that `@ExtendWith` them.
  Protection is opt-in, so a test reaching any caller -- branch, destroy, clean, an account change -- must run under one of them, never add a seam of its own (#871).
