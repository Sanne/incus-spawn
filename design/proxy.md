# The MITM TLS proxy

How the host-side proxy keeps credentials out of instances, what it intercepts, caches and injects, and why it holds no configuration of its own.

## Credential isolation via MITM TLS proxy
A TLS-terminating MITM proxy intercepts HTTPS connections to specific domains (Anthropic API, GitHub, IBM Bob), injects authentication headers server-side, and forwards to the real upstream.
Containers resolve these domains to the gateway IP via bridge-level dnsmasq overrides (configured when `isx proxy` starts) and trust the proxy's certificates via a custom CA installed in the template image.
This approach was chosen over simpler alternatives (reverse proxy with `ANTHROPIC_BASE_URL`, credential helpers, shell wrappers) because those approaches still expose credentials to code running inside the container — either as environment variables, in process memory via `curl` calls, or through accessible endpoints.
The MITM proxy provides complete isolation: there is no API, endpoint, environment variable, or file that container code can access to obtain credentials.

## Auth & Security: MITM TLS Proxy

**API keys and tokens never enter containers.**
A host-side MITM TLS proxy (`isx proxy`) provides transparent authentication.
Placeholder values satisfy tools' local auth checks (e.g. `GH_TOKEN`, `ANTHROPIC_API_KEY`), but the proxy replaces them with real credentials before requests reach upstream servers.
Every start fills them with proof tokens derived from the instance's stable credential seed (`gho_isx_<digest>`; see design/accounts.md "Proof tokens").

**Trust bindings (#713).** isx owns the binding of upstream runtimes to the system store, because a runtime that ships its own root bundle ignores the store that holds the isx CA.
Two kinds, one table (`GuestProvisioning.TRUST_ENV` for the first, `isx-trust` for the second):
environment variables (`SSL_CERT_FILE`, `REQUESTS_CA_BUNDLE`, `NODE_EXTRA_CA_CERTS`, ...) are exported `setIfUnset` from `isx-env.sh` and written to `environment.d`, read at process start so they cover a runtime installed at any time anywhere, and none prints a "Picked up" line.
file bindings, for runtimes that read no variable, replace a file in the runtime by a symlink to the system store, applied by the guest helper `/usr/local/bin/isx-trust [DIR...]` (Java: `lib/security/cacerts` to `/etc/pki/ca-trust/extracted/java/cacerts`; certifi: `certifi/cacert.pem` under `site-packages`, never a source checkout, to `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`).
Both name what `update-ca-trust` extracts, never a compat link such as `/etc/pki/java/cacerts` (ca-certificates dropped `/etc/pki/tls/certs/ca-bundle.crt`, and curl fails on a variable naming a missing file rather than falling back), and the build fails if the base image lacks either store; the helper never links to a missing store.
Java has no variable on purpose: `JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS` print "Picked up" on stderr, which OpenJDK's configure rejects in a boot JDK (#464).
Each build binds twice: `bindInstalledRuntimes` before each layer's prime commands (a vendor JDK the layer installed must trust the isx CA when a prime uses it, and a prime may run `isx-trust` itself), and `bindTrustStores` after them (a prime may download a runtime, and the parent's units do not settle before the stop).
A prime that downloads a runtime and uses it in the same command (`mise x -- ./gradlew dependencies`) runs it before a path unit can bind it, so its first HTTPS call fails (#1746).
A watch cannot close that race: the unit fires when the landing directory changes and settles over seconds, while the command uses the runtime as soon as it is unpacked.
So a prime command whose output shows one of the symptoms the agent context names (`GuestProvisioning.UNTRUSTED_CERTIFICATE`) gets `runTrustHelper` and one more run, of that command alone when the prime is a list (the helper is installed before the primes, and rewriting it could cut short another prime running it); a second failure with a symptom names the causes left: a runtime outside the landing directories (`isx-trust <dir>`), a JVM daemon the first run started with its old store, or a server certificate no root signs.
`isx update-all --prime` primes through the same `RepoCloner.runPrime`: a running template's path units race the same way.
It also runs `runTrustHelper` after its primes, before the template stops, as a build's `bindTrustStores` does: a runtime a prime downloaded would otherwise keep its own roots in every later branch.
`JAVA_TOOL_OPTIONS` for primes would close the race for Java alone, at the cost #464 rules out, and nothing is relaxed by the retry: the runtime verifies against the system store.
The build installs the helper and runs it on the landing directories in `GuestProvisioning.TRUST_SYSTEM_DIRS` (as root) and `TRUST_HOME_DIRS` (as `agentuser`, who owns them).
A runtime that lands later is found by systemd path units, not a daemon (#515): `isx-trust-system.path` and `isx-trust-home.path` hold one non-recursive `PathChanged=` watch per landing directory and start a oneshot `isx-trust --settle` over their group, which repeats its pass for about a minute because the new entry appears before the runtime has finished unpacking, and runs further rounds, at most five, while the landing directories keep gaining entries (systemd absorbs a trigger that arrives while the service runs).
A bare `isx-trust` covers the same directories as the build, the home ones as `agentuser`'s whoever runs it.
The cost is one inotify instance per watched directory (systemd opens one per path), counted in `IncusDiagnostics.INOTIFY_INSTANCES_PER_CONTAINER`.
What lands outside those directories, or whose bundled file appears only after the last pass, is bound by running `isx-trust <dir>`, and the agent context pairs the TLS symptoms with that command so an agent repairs the binding rather than turning verification off.
A JDK built from source, such as an OpenJDK build tree, is one of those: isx does not bind the JDK that `JAVA_HOME` or `PATH` names, so a build tree's `cacerts` keeps the roots OpenJDK bundles until `isx-trust` runs on it (#1968).
A hook on the shell's `JAVA_HOME` or `PATH` would not see a `JAVA_HOME` set inline for one command, and covers interactive shells only, which is why #713 dropped the prompt hook.
What would see every use stays ruled out: `JAVA_TOOL_OPTIONS` by #464, a preload shim by #713.
OpenJDK's `--with-cacerts-file` is only an opt-in, for a build that never leaves the instance.
It copies the instance's store, isx CA and propagated host CAs included, into the built JDK, which then trusts them wherever it is taken, with nothing in the image saying so.
The link `isx-trust` makes does not copy a CA, so on a machine without its target a JDK carrying it fails visibly.
The link's first cost is a repeated step: OpenJDK's `make/Images.gmk` deletes `images/jdk` before the jlink step writes it again, so a rebuild that re-creates the image puts a fresh `cacerts` in place of the link.
On a jdk-26 build a `java.base` change re-created the image, and a `make images` with nothing to rebuild kept the link.
The user then runs `isx-trust <dir>` again.
The second is that the link changes the JDK under test.
OpenJDK's `VerifyCACerts` (tier2) checks the count and checksum of the image's `cacerts`, so it fails on a linked image until the build's own file is put back, and on a `--with-cacerts-file` build too.
The `openjdk` template's primer names both the step and the flag, the flag only for a build that never leaves the instance, and how to put the build's own `cacerts` back before that test.
If the repeated step proves too costly, the next mechanism would be extra watched directories a template declares; it is not planned.
Such a directory would have to be the one whose entries change, such as `images`, since a path unit's watch is not recursive and a rebuild under `~/jdk/build` changes no entry of that directory itself.
`docs/FEDORA.md`, "What differs from a stock Fedora install", lists these deviations for users.

**How it works:**

1. The proxy configures bridge-level DNS overrides (via `raw.dnsmasq` on `incusbr0`) so all containers resolve intercepted domains to the gateway IP
2. Template images include a custom CA certificate (generated during `isx init`) so containers trust the proxy's TLS certificates (system anchors, plus the trust bindings below for runtimes with their own bundle)
3. The proxy listens on port 18443 on the gateway IP.
   An iptables PREROUTING redirect rule (installed by `isx init` via `firewall-cmd --permanent --direct`) transparently redirects traffic arriving on `incusbr0` destined for port 443 to port 18443, avoiding conflicts with the Incus daemon on port 443.
   The proxy terminates TLS using per-domain certificates signed by the custom CA
4. Based on the target domain, the proxy injects authentication headers:
   - `api.anthropic.com` — `x-api-key: <anthropic-api-key>` (direct API key mode), `Authorization: Bearer <oauth-token>` (OAuth mode, for Claude Pro/Max subscriptions), or Vertex AI passthrough/translation with GCP Bearer token (Vertex mode, see below).
     Anthropic auth is hardcoded in `MitmProxy` (three auth modes with complex routing)
   - Tool-contributed domains (GitHub, OpenAI, Bob, and user-defined tools) — credential injection is declared in YAML tool definitions via `proxy:` entries (see "Tool-contributed proxy definitions" below)
   - Container registry, Maven, and npm domains — relayed transparently with caching (no auth injection)
5. The proxy re-encrypts and forwards to the real upstream over TLS, trusting the host's system CA bundle (`SystemTrust.findBundle()`) alone, or the JDK store when there is none; the CLI's own TLS clients add that bundle to the JDK store instead (`SystemTrust.httpClient()`), so both work behind TLS inspection whose CA is in the system store, and the CLI run on a JVM also keeps a CA imported into that JVM's `cacerts` (a native binary's JDK store is fixed at build time).
   On macOS the Keychain's admin trust joins the bundle for both (`MacKeychainTrust`, #1467); see "Host trust on macOS: the admin domain only" below.

**Host trust on macOS: the admin domain only.**
On a Mac, a TLS-inspection CA is installed into the Keychain by MDM or `sudo security add-trusted-cert -d`, never into `/etc/ssl/cert.pem`, so without the Keychain the proxy and the native CLI fail with PKIX where Safari succeeds (#1467).
`MacKeychainTrust` runs `/usr/bin/security` (by absolute path) for the admin domain's trust settings (`trust-settings-export -d`) and the certificates of the System and login keychains (`find-certificate -a -p`), and pairs them by SHA-1.
The runs start together and share one 5 s deadline, so a hung `security` delays a proxy start or a download by 5 s at most; each took about 10 ms on a GitHub runner.
The login keychain is read by a run of its own, so one a user-level process broke cannot fail the System keychain's read: whatever its failure, a timeout or output that does not parse included, it only loses the bodies stored there.
The login keychain supplies certificate bodies only, since `add-trusted-cert -d` without `-k` stores the certificate there; what is trusted is decided by the admin domain alone.
A body is bound to its admin record by SHA-1 and by the issuer and serial number the record carries, so a certificate planted in a keychain the user can write must match all three.
It evaluates each certificate's settings as macOS does for a TLS server certificate: the first entry for SSL or for every policy decides, an empty or absent list or an entry with no result trusts as root, and Unspecified passes to the next entry.
A result of an unknown value or type, a policy that is not data, and an entry that is not a dictionary, decide nothing and pass to the next entry.
The one exception is a deny on an entry whose policy is not data: it still denies, since macOS may read that policy as SSL's.
An unreadable entry also taints the list: a later deny still denies, but a later trust does not trust, since macOS may have read that entry as a deny.
A `<real>` result of 3 is a deny, as macOS reads any number.
An entry restricted to a host name, an application or a key usage is skipped when it trusts and decides when it denies, so a constraint only ever narrows what isx trusts.
`SystemTrust` adds the admin-trusted certificates to the CLI's JDK store and bundle, and `Upstream.upstreamTrust()` gives the proxy the bundle's and the Keychain's as values.
A certificate an administrator denied for SSL is removed from every source, the JDK store and the bundle included, which only narrows.
Removing it covers a root only: neither an intermediate nor a server (leaf) certificate is ever an anchor, while macOS refuses a deny anywhere in the chain.
So whenever the Keychain denies anything, `DeniedChainTrustManager` wraps the JDK's PKIX trust manager, in the CLI (`SystemTrust.sslContext`) and in the proxy's three Vert.x clients (`Upstream.refusingDenied`, through `TrustOptions.wrap`), and after the JDK's validation refuses a chain holding a denied certificate (#1552).
It checks the chain the server presented, not the path PKIX built, which the JDK does not expose; that is stricter, since an unused denied certificate a server sends is refused too.
The rest of the built path is anchors, from which every denied certificate is already removed, because the JDK fetches no intermediate itself: AIA fetching (`com.sun.security.enableAIAcaIssuers`) is off by default and isx never turns it on.
The wrapper is an `X509ExtendedTrustManager` passing each `SSLEngine` and `Socket` overload to the JDK's own, so the host-name check stays the JDK's; `SystemTrustTest` and `UpstreamTrustTest` check that a wrong host is still refused.
With no denies, on Linux always, nothing is wrapped and the proxy keeps its `PemTrustOptions`.

A deny names one certificate by its SHA-1, but a CA has copies: its self-signed root and each cross-signed one, with the same subject and key and another issuer.
A server sends a cross-signed copy to anchor its chain on an older root: `api.github.com` sends Sectigo's Root E46 signed by USERTrust ECC, and `/etc/ssl/cert.pem` holds only USERTrust ECC.
So a "Never Trust" on the self-signed E46, the copy Keychain Access lists, removed no anchor and matched no certificate the server sent, and isx went on trusting the CA (#1552, found on the Mac runner).
`Trust.denies` therefore also refuses a certificate with a denied one's subject and public key (`keyOf`, SHA-256 of both), for every denied certificate whose body a keychain holds: the System and login keychains, and Apple's roots (`SystemRootCertificates.keychain`), read for this alone, since Keychain Access lists them and a user denies one there.
A deny whose body no keychain holds still matches its own certificate by hash.
Chosen over the subject alone, which any CA can put in a certificate, and over the key alone, which would also refuse a CA's renamed re-issue: a subject and key together are what a cross-sign keeps, and whoever holds the key is the CA that was denied.
This is narrower than refusing only the named certificate, and possibly than macOS, whose path building may fall back to the cross-signed path; a deny only ever narrows here, and a user who denies a CA means its key.

The user's own trust domain is read for its denies alone (`security trust-settings-export` without `-d`, a fourth run under the same deadline, #1552): macOS refuses what a user marked "Never Trust", so isx does too, a certificate or the default roots as a whole.
Its trusts are never used, since no certificate body is passed with it; a user's trust that macOS lets override an administrator's deny does not override it here, which is narrower than macOS.
The user domain is any user-level process's to write, so it can only narrow.
When none exists (`security` reports "No Trust Settings"), the admin domain's result stands, and a failed admin read keeps the user's denies.
When one exists but cannot be read (a timeout, another exit status, a plist that does not parse), its denies are unknown, so the read counts as failed: no Keychain CA is trusted, the admin domain's denies still apply, and the problem is reported (#1530).
It once let the admin result stand, which trusted a CA the user had denied unseen; `host-ca` propagation would also have installed it in every instance and kept it there under the stamp.
This holds for isx's own TLS, the CLI's and the proxy's, as well as for what propagation installs in guests: a failed read has one meaning everywhere, which keeps `isx doctor` and the client log truthful.
A flag that dropped the CAs from guests alone was rejected: the host would then trust a CA its user may have denied, while reporting the Keychain as not fully read.
The cost is availability: such a process can deny the roots isx needs and break its TLS, and denying every one stops the proxy from starting.
It could already kill the proxy or edit `~/.config/incus-spawn`, so this gives it nothing new; the proxy's error and `isx doctor` name `security dump-trust-settings`, with and without `-d`, to find the deny.

A deny of the default roots as a whole (`kSecTrustSettingsDefaultRootCertSetting`) drops the JDK store and the bundle, which stand for them, leaving only what an administrator trusts.
Without a bundle the proxy keeps the JDK store, as before, with the Keychain's certificates added to it.
Any failure, a timeout or a plist that does not parse adds no certificate, is logged to the client log, and `isx doctor` reports it.
When only the System keychain's certificates cannot be read, by an exit status, a timeout or output that does not parse alike, the export's denies still apply, since they need no certificate bodies.
When the Keychain's denies leave the proxy nothing to trust, it refuses to start rather than fall back to the JDK store, which would trust the denied roots again.
The plist is parsed with its DOCTYPE dropped and any other refused, so no DTD or entity is ever resolved.
The proxy reads the Keychain once, at start: a Keychain change takes `isx proxy restart`, as a bundle change does.
The CLI reads it for each TLS context it builds, which only network commands do (up to five `security` runs at once, tens of milliseconds), never branch, start or shell.

Why not the alternatives:
- *The user domain's trusts too* (a CA a user marked "Always Trust" in their login keychain): anything running as the user can ask for that trust, while the admin domain takes root, as the Linux system store does.
  A CA isx trusts can read every credential the proxy injects, so trusting one should take an administrator.
  Corporate inspection agents and MDM install into the System keychain; a user whose CA is only in the login keychain gets the `sudo security add-trusted-cert -d` remedy from `isx doctor` and the README.
- *Every certificate in the System keychain* (`find-certificate -a` alone): it holds certificates nobody trusted, and some an administrator distrusted.
- *The system domain* (Apple's roots, `SystemRootCertificates.keychain`): they overlap `/etc/ssl/cert.pem` and the JDK store, and an inspection CA is never among them.
- *No user-domain denies* (the admin domain alone, as before #1552): isx kept trusting a root a user refused while Safari and curl refuse it, to protect an availability a same-user process can take away anyway.
- *Its own `CertPathBuilder` with a checker rejecting denied certificates*, to refuse a denied intermediate: it would build around one as macOS does, but it re-implements what JSSE checks (algorithm constraints, revocation settings, the host name), a larger surface than one check after the JDK's.
- *The JDK's `KeychainStore`*: whether a native image can use it is unproven, and its reading of trust settings is the JDK's, not one isx can test on Linux.
- *A `config.yaml` file of extra CAs*: a manual step for every user, which the Keychain already records; it stays open if a need appears.

`KeychainTrustMacTest` checks this against the real Keychain on the `unit-tests-macos` runner, which is discarded after the job (it runs only when the job sets `ISX_TEST_MUTATE_SYSTEM_KEYCHAIN=1`).
`macos-keychain-trust.yml` builds the native CLI and proxy on macOS weekly and on a change to this code, and `release.yml` smoke-tests each released macOS CLI, through `isx doctor --format=json`.
MDM-delivered CAs cannot be reproduced on a runner, since a configuration profile cannot be installed headless.
Each check uses a CA of its own and never changes or removes a trust setting: on a runner that waited for an authorization nobody gives (#1515), as user-domain trust always does, so the user domain is left out of the checks too; `MacKeychainTrustTest` covers its denies with plists on every platform.
The case that found #1552 is covered on both sides (#1631).
On every platform, `MacKeychainTrustTest` denies Sectigo's real self-signed Root E46 in a user-domain plist and refuses its real copy cross-signed by USERTrust ECC; the certificates come from crt.sh, and `macos-trust-settings/sectigo-e46/README.md` says how they were checked.
On the runner, `KeychainTrustMacTest` denies a test root admin-wide and refuses a handshake through its copy cross-signed by another trusted test root.
`macos-keychain-smoke.sh` refuses the same shape in both native binaries, in `macos-keychain-trust.yml` (#1631).
`/etc/hosts` points `api.github.com` at a local server presenting a leaf, an intermediate and a denied root's copy cross-signed by a trusted one.
Native `isx update-base --list` reads its release list, and so does a request the native proxy relays upstream, until the self-signed root is denied admin-wide; then both are refused.
The proxy runs there without a VM: a made-up lease on loopback's network is where it looks for one, and a GitHub account in `config.yaml` makes it intercept `api.github.com`.
`release.yml` passes it the CLI alone, so a release does not run the handshake until a dispatched release run has shown it there.
None of these reads a user-domain deny from a real Keychain.
They cannot read a user-domain deny, because no hosted runner can write one (#1631, probed on macOS 15.8 and 26.7 in PR #2369).
There `security add-trusted-cert -r deny` without `-d` started a SecurityAgent dialog in the runner's own session and waited for an answer until the probe's 45-second limit ended it, and the user domain did not hold the setting afterwards.
Opening the trust rights in the authorization database did not prevent the dialog.
With `com.apple.trust-settings.user` and `com.apple.configurationprofiles.userprofile.trustcert` set to `allow`, authd granted `security` its right.
trustd then asked authd for an authorization of its own, which authd checked against hardcoded rules rather than the database: the session owner, then the owner's password, which ran `builtin:authenticate` in SecurityAgent.
authd refused to change `com.apple.trust-settings.admin` at all ("not allowed to update right"), even as root.
A certificate already in the login keychain and an unlocked keychain of the probe's own as the default did not help either, although both attempts ran while an earlier dialog was still pending.
So beyond `MacKeychainTrustTest`'s plists, only a person at a Mac checks a user-domain deny in a real Keychain.
`scripts/macos-keychain-deny-check.sh` guides that person through the manual test that found #1552 (#1631).
It asks them to mark Root E46 "Never Trust" in Keychain Access, then checks that `isx update-base --list` is refused and that `isx doctor` counts one more deny.
After `isx proxy restart`, an instance's `curl https://api.github.com` must get the proxy's 502 "Upstream connection failed", which it sends only when its connection upstream failed.
It then asks them to set E46 back to "Use System Defaults", and checks that all three recover.
It is also the only check of an instance's request against a Keychain deny, and of the reset.
The script never writes a trust setting and never uses sudo, so the only risk it leaves is a person who stops before the reset, which it tells them how to undo.
It concludes nothing when its premises fail: `api.github.com` must still send E46 cross-signed by USERTrust ECC, the instance's curl must be served by `incus-spawn MITM CA`, and `security dump-trust-settings` must show the person's change in the user domain.
Run it on a Mac before releasing a change to the Keychain trust code: `MacKeychainTrust`, `SystemTrust`, `DeniedChainTrustManager` or the proxy's `Upstream`.
The three rounds of the probe are commits of that PR, to run again when a new macOS might behave otherwise.

**Redirect rule cleanup:** `isx proxy uninstall` removes the systemd service **and** cleans up the PREROUTING redirect rule and bridge DNS overrides unconditionally — even if the service removal itself fails.
`ProxyConfig.clearRedirectRules()` attempts both firewalld and UFW independently (not else-if) so that a system with firewalld installed but inactive still reaches UFW cleanup.
On firewalld it queries `--direct --get-all-rules`, extracts the gateway IP from the matching PREROUTING rule (via `FirewalldCheck.isRedirectRule`), removes the permanent direct rule, and reloads; when the daemon is inactive, it falls back to editing the persistent `/etc/firewalld/direct.xml` directly.
On UFW it reads `before.rules`, derives the subnet from the marked NAT block, and replaces the block with a redirect-free version (preserving the MASQUERADE rule that containers need for general internet access).
If the subnet cannot be derived (malformed block), the entire NAT block is removed.
`runQuiet()` returns a boolean exit status; cleanup callers check and warn on failure.
`ProxyConfig.clearBridgeDns()` removes isx's block of overrides from `incusbr0`'s `raw.dnsmasq`, along with any overrides in the pre-block layout, and keeps every other line.
`isx reset` runs the same cleanup (and also destroys all containers, templates, VM, and host data).

**Vertex AI support:** When the host is configured for Vertex AI (`useVertex=true` in config), containers run Claude Code in **Vertex mode** with `CLAUDE_CODE_USE_VERTEX=1`, `CLAUDE_CODE_SKIP_VERTEX_AUTH=1`, and `ANTHROPIC_VERTEX_BASE_URL=https://api.anthropic.com/v1`.
This causes the Vertex SDK inside the container to send already-formatted Vertex requests (`/v1/projects/.../models/...:streamRawPredict`) to `api.anthropic.com`, which resolves to the proxy via dnsmasq.
The proxy then forwards to the real Vertex endpoint with GCP credentials.
No GCP credentials enter the container.

Running containers in Vertex mode (rather than standard mode) is required because Claude Code's model list depends on the provider: standard mode ("firstParty") shows a hardcoded subset that may omit newer models, while Vertex mode shows the full model catalogue.
Using Vertex mode in the container ensures the `/model` picker matches what's available on the host.

**Three-way routing for Anthropic traffic:**

The proxy routes requests to `api.anthropic.com` through one of three paths based on the URL:

1. **Vertex passthrough** (`/v1/projects/...`): Requests already in Vertex format (from the container's Vertex SDK).
   The proxy strips `@date` model version suffixes from the URL path (the global endpoint rejects them), removes the `anthropic-beta` header, injects a GCP Bearer token, and forwards to the real Vertex endpoint.
   The host's gcloud token reaches every project and every Vertex API its user can, so a proven request is served only as a Claude model call, moved into the account's own project and region (#1921, `ClaudeOnVertex`).
   That is `POST /v1/projects/<any>/locations/<any>/publishers/anthropic/models/<model>:{rawPredict,streamRawPredict}`, token counting (`count-tokens`) included; anything else proven is a 403 before its body is read or anything is sent.
   Moved, not refused: the guest's `ANTHROPIC_VERTEX_PROJECT_ID` and `CLOUD_ML_REGION` were fixed at build, and two Vertex accounts are interchangeable on an instance (`isx account set`, a config edit), so refusing a mismatch would break every call of a re-pointed instance.
   Before, such a call was billed to the project the guest named.
   Of a proven request's query it keeps only `alt` (letters only), as the Gemini route does; an unproven one keeps its query as sent (#1576), and its own authentication and destination.
   Every request the Vertex branch of `injectHeaders` signs, translated ones included, loses `x-goog-user-project` and `X-HTTP-Method-Override`, which would bill another quota project or change the method.
   The body is forwarded as sent, but for `strict` in each tool definition, which is dropped as on the translation path (#1568).
   An object body also loses its insignificant whitespace, between tokens and before the object with a byte order mark; the Vertex SDK already formats the rest correctly.
   `VertexBodyRewriter` does this as the body arrives, with the parser the translation uses, and the body is held like a translated one (see "A body the proxy must hold").
   A body that is not empty must be valid JSON within the depth limit, or it is refused with a 502.
   Of its names, only the top object's and a tool definition's must be well-formed, since those are the only ones it compares, and a malformed one is refused with a 400.
   The translation's other checks, a duplicate `model` or `stream` and the characters `model` may hold, do not apply: the model is in the URL the client chose.
   A body with a `Content-Encoding` other than `identity` (gzip, say) is not JSON the rewriter can read, so it is forwarded unchanged, `strict` included, for Vertex to judge.

2. **Standard-to-Vertex translation** (`/v1/messages`): Requests in standard Anthropic API format.
   The proxy translates the body to Vertex `rawPredict` format as it arrives, and holds the result until it has been sent for good (see translation details below, and "A body the proxy must hold").
   This path is used if a container happens to send standard-format requests (e.g. from curl).
   A proven request on it is signed only when it is `POST` to exactly `/v1/messages` or `/v1/messages/count_tokens` and its body is translated (#1921).
   Anything else under `/v1/messages`, or a body that is empty or no JSON object, is a 403: the guest's own path and method would otherwise reach Vertex signed with the host's token.
   The method and path are checked before the body is read; the body's shape only once it is all in, still before anything is sent.

3. **Direct forwarding** (all other paths): Non-messages endpoints (settings, bootstrap, feature flags, MCP registry) are forwarded to the real `api.anthropic.com` with credential injection.
   These endpoints don't exist on the Vertex API.

**Standard-to-Vertex translation details** (path 2):

- URL: `/v1/messages` → `/v1/projects/{projectId}/locations/{region}/publishers/anthropic/models/{model}:rawPredict` (or `:streamRawPredict` when `stream=true`)
- URL: `/v1/messages/count_tokens` → `/v1/projects/{projectId}/locations/{region}/publishers/anthropic/models/count-tokens:rawPredict`, Vertex's token count, whatever `stream` says (#1938).
  Before #1938 it went to the model's `rawPredict`, a generation: a 400 for a missing `max_tokens`, or a billed answer where the client asked for a count.
  The proxy sets that path itself, so no path the guest chose is signed.
  The body keeps `model`, written after the kept fields and before `anthropic_version` with the date suffix stripped as below, and only the fields of a prompt (`messages`, `system`, `tools`, `tool_choice`, `thinking`, `output_config`).
  The model is re-encoded as an ASCII JSON string, so a quote in the guest's model cannot add a member.
  Google's documentation and Anthropic's Vertex SDK send this shape, `anthropic_version` included; it was not measured against a live Vertex project.
- Auth: replaces `x-api-key` with `Authorization: Bearer <gcp-token>` (obtained via `gcloud auth print-access-token`, cached ~50 minutes)
- Body: extracts `model` field and moves it into the URL path, stripping date suffixes (e.g. `claude-sonnet-4-6-20251001` → `claude-sonnet-4-6`)
- Body: adds `"anthropic_version": "vertex-2023-10-16"` (required by Vertex rawPredict)
- Body: strips all top-level fields not in the Vertex allowlist (beta features like `context_management` cause "Extra inputs" rejections)
- Body: recursively strips `scope` from nested `cache_control` objects (beta feature unsupported by Vertex); a `cache_control` object's other members are copied as they are
- Body: strips `strict` from each tool definition, an object in the top-level `tools` array (#1556).
  Vertex treats a strict tool schema as the `structured_outputs` partner-model feature, which an organization policy (`constraints/vertexai.allowedPartnerModelFeatures`) can disallow, and then refuses every request with tools.
  A `strict` anywhere else, such as a property in an `input_schema`, is the client's data and is kept; the dropped member is logged once as `tools[].strict`.
  The passthrough path (1) applies the same rule to bodies already in Vertex's format (#1568)
- Body: `output_config` is forwarded unchanged on the passthrough and translation paths (1 and 2), a JSON-schema `format` included (#1569).
  Vertex treats that format as the same `structured_outputs` feature.
  Unlike `strict`, dropping it would change what the client gets back (no longer the JSON it asked for).
  The proxy therefore forwards it, and under a policy that disallows the feature the client gets Vertex's own 400.
- Header: removes `anthropic-beta` (Vertex rejects beta feature flags; features are enabled via `anthropic_version`)
- Host: rewrites to the Vertex endpoint hostname for the configured region

The body translation uses an allowlist approach: only known-good fields (`messages`, `system`, `max_tokens`, `temperature`, `top_p`, `top_k`, `stop_sequences`, `stream`, `metadata`, `tools`, `tool_choice`, `thinking`, `output_config`) are kept, and the client's own `anthropic_version` is replaced by the proxy's.

`VertexBodyRewriter` does the translation as the bytes arrive (#1315), never holding a body or a tree of it: a tree cost about six times the body in heap, so a 24 MiB Messages request exhausted the native proxy's heap and was never answered, and the tree's recursion overflowed the stack a few thousand levels down.
It validates the JSON as strictly as `JqValues.parseStrict`, which the tree translation used, and copies every value it keeps byte for byte.
Its memory is bounded whatever the body: two bits per nesting level up to 10,000 (`parseStrict`'s own limit), a field name decoded up to 32 characters (longer than any it compares), `model` up to the Vertex front end's URL ceiling, a path of 16,384 bytes, and 16 distinct dropped field names to log; strings and numbers pass through in chunks.
It parts from the tree translation where #1315 decided:
- a duplicate key is forwarded as sent, for Vertex to judge, except a duplicate top-level `model` or `stream`, escaped spellings included, which is refused with a 400 (the tree translation refused every duplicate, with a 502);
- a duplicate never carries a field past the rewrite: every occurrence of a dropped field, of the client's `anthropic_version` and of a `cache_control`'s `scope` is removed;
- a name the rewriter compares (every key outside a dropped field and outside a copied `cache_control` value) must be well-formed: valid UTF-8 without overlong forms, no lone surrogate escapes; and `model`, which goes into the URL, must also hold no control or invisible (format) character, whitespace, `/`, `\`, `?`, `#` or `%`, and fit the URL ceiling, or the request is refused with a 400, since a lenient decoder upstream could read a name or a route the proxy did not;
- values it keeps are forwarded as sent: malformed Unicode stays as it is (the tree re-encoded `\ud800` as `?`, and refuses it since jjq 0.2.4), and so does number spelling (`1.0e2`, not `100`);
- whitespace before a body that is not an object is not forwarded.
Each is a named case in `VertexBodyRewriterTest`, which also checks the rewriter against the tree translation (`LegacyVertexTranslation`, kept as the test's oracle) for a corpus and generated bodies, split into chunks every way, in both its modes.
Everything else is dropped.
This is more robust than blocklisting individual beta fields, since new Claude Code beta features are automatically stripped without proxy changes.

**Vertex AI protocol details** (learned from testing):

- **Hostname resolution by region**: The standard pattern is `{region}-aiplatform.googleapis.com` (e.g. `us-east5-aiplatform.googleapis.com`), but some meta-regions use special hostnames: `global` → `aiplatform.googleapis.com`, `us` → `aiplatform.us.rep.googleapis.com`, `eu` → `aiplatform.eu.rep.googleapis.com`
- **Model naming**: The Vertex SDK uses `@` for model version suffixes in URL paths (e.g. `claude-haiku-4-5@20251001`), while the standard API uses `-` (e.g. `claude-haiku-4-5-20251001`).
  The global Vertex endpoint only accepts short model aliases without any version suffix — both `@20251001` and `-20251001` forms are rejected.
  The proxy strips both forms.
- **Beta features**: The `anthropic-beta` header is rejected by Vertex rawPredict with "Unexpected value(s) for anthropic-beta header".
  This includes common beta flags like `claude-code-20250219`, `interleaved-thinking-2025-05-14`, `web-search-2025-03-05`, and `prompt-caching-scope-2026-01-05`.
  Features like extended thinking work without any beta flags on Vertex — they're enabled via `anthropic_version`.
  The Vertex SDK moves `anthropic-beta` header values into the body as an `anthropic_beta` array, but even that is rejected ("invalid beta flag").
  The proxy strips the header entirely without adding it to the body.
- **Auth skipping**: `CLAUDE_CODE_SKIP_VERTEX_AUTH=1` causes the Vertex SDK to skip GCP authentication and send no Authorization header of its own: the only headers it adds are `ANTHROPIC_CUSTOM_HEADERS` (checked against Claude Code 2.1.289 with a local listener).
  A login script sets that to `Authorization: Bearer $ISX_VERTEX_ACCESS_TOKEN`, so the request carries the instance's token for the proxy to check (#1108), and the proxy replaces it with a real GCP token.
  A stub `/usr/local/bin/gcloud` is installed inside Vertex containers (by `ClaudeSetup`) that prints `$ISX_VERTEX_ACCESS_TOKEN` (falling back to `ya29.placeholder-for-proxy`) for `auth print-access-token`, for anything that refreshes a token through `gcloud`.
  It was added for Claude Code's credential refresh, which current releases skip under the flag.
- **Base URL override**: `ANTHROPIC_VERTEX_BASE_URL` redirects all Vertex SDK requests to a custom endpoint.
  Setting it to `https://api.anthropic.com/v1` causes the container's Vertex SDK to send requests to `api.anthropic.com`, which resolves to the proxy via dnsmasq.
- **Response format**: Vertex `rawPredict` returns standard Anthropic response format — no response translation is needed.

**Pi coding agent support:** Pi is a provider-agnostic coding agent that always communicates via the standard Anthropic API (`/v1/messages`).
Unlike Claude Code, Pi does not have a Vertex mode — it always sends standard API requests with an `x-api-key` header.
The proxy handles both direct key injection and standard-to-Vertex translation transparently.
No Vertex-specific environment variables are needed inside the container; `ANTHROPIC_API_KEY=sk-ant-placeholder` is the only auth configuration (declared via `PiSetup.envEntries()`).

**Gemini on Vertex for Pi (#1114):** Pi's `google-vertex` provider runs Gemini models, not Claude, and never speaks the Anthropic API.
Pi has no provider named `vertex`; isx accepts it and writes `google-vertex` in Pi's settings.
Before #1114 isx exported only `GOOGLE_CLOUD_PROJECT`/`GOOGLE_CLOUD_LOCATION`, while Pi authenticates with `GOOGLE_CLOUD_API_KEY` or ADC, so the provider had no working credential at all.
Pi's `google` provider is the Gemini API (`GEMINI_API_KEY`, `generativelanguage.googleapis.com`), which isx had also mapped to the Vertex account; it now spends the `gemini` namespace through `GeminiSetup`'s existing route.
With an API key, `@google/genai` uses Vertex's express form: `POST https://aiplatform.googleapis.com/v1/publishers/google/models/<model>:streamGenerateContent` with the key in `x-goog-api-key` and no project.
`PiSetup` exports `GOOGLE_CLOUD_API_KEY=AIzaplaceholder`, a placeholder of the claude namespace, so login fills it with the instance's claude proof.
`aiplatform.googleapis.com` is a built-in intercepted domain (`ProxyConfig.GOOGLE_VERTEX_DOMAINS`), and `ProofCarriers` reads that proof from `x-goog-api-key` there.
A proven request on a Vertex account is rewritten to `/v1/projects/<project>/locations/<region>/publishers/google/models/<model>:<method>` on `vertexHost(region)` and signed with the host's gcloud token, as Claude's Vertex calls are (`GeminiOnVertex`, `MitmProxy.relayGeminiOnVertex`).
The proof never leaves: the Vertex branch of `injectHeaders` strips `x-goog-api-key`.
The gcloud user credential reaches every project and every Vertex API its user can, so the route serves only `generateContent`, `streamGenerateContent` and `countTokens` on Google's publisher in the express form, in the account's own project; anything else proven is a 403, and a proven call on an account that is not Vertex AI a 502, before anything is sent.
Of the guest's query it keeps only `alt` (letters only), and it drops `x-goog-user-project` and `X-HTTP-Method-Override`, which would otherwise bill another quota project or change the method.
Unproven requests relay unchanged, so a guest's own Google key still works there.

**The Gemini body is an allowlist (#1925).**
A Gemini body can name data in other projects: a `cachedContent`, a RAG corpus or a Vertex AI Search datastore in `tools`, a `gs://` object in a part's `fileData`.
The host's token may read all of them, so a proven guest could read them through the model.
The proxy therefore sends only the body Pi's `google-vertex` provider sends, read from pi-ai 1.5.2, and refuses every other before anything leaves (`GeminiBodyCheck`).
Its top object holds only `contents`, `systemInstruction`, `generationConfig`, `tools` and `toolConfig`, and each tool holds only `functionDeclarations`.
A part holds no `fileData`, in the parts of `contents[]`, of `systemInstruction` and of a part's `functionResponse`.
The check goes by position, since a function's arguments may hold a field named `fileData`.
A field outside the list is refused with a 403 naming it, never dropped, since a dropped `cachedContent` would answer another question than the one asked.
Names are compared as Vertex decodes them, and both spellings Vertex's JSON reading is believed to accept (`file_data` beside `fileData`) are covered where the check reads names.
The body must be strict JSON, one complete object, typed by one `Content-Type` of `application/json` in UTF-8 and without a `Content-Encoding`, and each position the check reads must have its shape or be `null`, so no other spelling or shape slips past it.
The bytes of every string must be well-formed UTF-8, and a name the check reads holds no control character, so a lenient decoder or one that honours another charset cannot end a string or a name where the check did not.
The check reads the body as it arrives, with bounded memory, and passes each chunk on unchanged to a `BodySpool`, as a Claude Vertex call's body is held (`MitmProxy.holdVertexRequest`).
So a refusal sends nothing upstream, and a 401 sends the body again with a fresh token.
What this gives up: a new Pi feature that sends another field gets a 403 until isx allows it, and a large upload is held in memory, then on disk, before it is sent.
Rejected: checking that a named resource is in the account's project, since the account buys model calls, not that project's caches, corpora and datastores; and a list of refused fields, which misses fields Google adds and spellings nobody listed.
Alternatives rejected: ADC in the guest (a credentials file Google's auth library exchanges for a real token, which would then sit in the instance); a refresh-token stub that the proxy answers on `oauth2.googleapis.com` (same: the guest would hold a working access token); intercepting every `*-aiplatform.googleapis.com` region (the express form needs only the global host).

**WebSocket passthrough:** The proxy also handles WebSocket upgrade requests.
When a client sends an HTTP Upgrade to a proxied domain, the proxy establishes a corresponding upstream WebSocket connection (injecting credentials on the initial handshake), then relays frames bidirectionally.
The client socket is paused until the upstream connection is established to prevent frame drops.
The client's handshake is completed (`ServerWebSocketHandshake.accept()`) before any of this, and a refused one goes no further.
Vert.x 4 ran the WebSocket handler before Netty validated the handshake, and only completed it once the handler returned.
So a request Netty refused (no key, no `Upgrade` token in `Connection`, ...) was answered 400 after the credential had been injected and an upstream socket opened for it (#972).
Copying Netty's checks into the handler was rejected: Vert.x's own pre-checks are looser than Netty's (a substring match on `Connection`), so a copy misses refusals and drifts with Netty.
Vert.x 5 splits the handshake from the socket: `routeWebSocket` is a `webSocketHandshakeHandler`, which resolves the caller's account and then calls `reject(status)` or `accept()`.
The relay is wired only once `accept()` has succeeded, and a handshake Netty refuses fails that future.
Every path must decide the handshake, because Vert.x 5 holds an undecided one (a handler that threw, say) open until the idle timeout.
The accepted socket is paused first thing: Vert.x 5 hands it over only once the 101 is written, and it starts flowing.
A frame pipelined with the upgrade request, which the Vert.x 4 proxy relayed, now fails `accept()` and closes the connection, so nothing is dialled for it.
RFC 6455 says a client must wait for the 101, and a test pins the refusal.
A regression test covering each refused shape pins the behaviour.
Keepalive pings are sent on both legs, and close codes are propagated.
This is used by Codex CLI, which communicates with `api.openai.com` over WebSocket.

**Intercepted domains:** Built-in: `api.anthropic.com`, `aiplatform.googleapis.com`, `registry-1.docker.io`, `auth.docker.io`, `ghcr.io`, `quay.io`, `repo.maven.apache.org`, `repo1.maven.org`, `plugins.gradle.org`, `services.gradle.org`, `registry.npmjs.org`.
Tool-contributed (via YAML `proxy:` entries): `github.com`, `api.github.com`, `raw.githubusercontent.com`, `objects.githubusercontent.com`, `codeload.github.com`, `uploads.github.com`, `api.openai.com`, `bob.ibm.com` (and all `*.bob.ibm.com` subdomains).
User-defined tools can add additional domains.

**HTTPS only:** The proxy intercepts HTTPS traffic, so Git operations must use HTTPS URLs (not SSH).
`gh` defaults to HTTPS automatically; for `git clone`, use `https://github.com/...` instead of `git@github.com:...`.

All other domains (package mirrors, PyPI, etc.) route normally via Incus bridge NAT and are unaffected by the proxy.

**Credential validation**: Branching (CLI and TUI alike, through `BranchFlow`) refuses up front when the new instance would lack a credential its tools need.
Airgapped branches skip the check, and building a template needs none, since only the proxy spends them at runtime.
A build still says what branching will refuse, before its time is spent (#1438): every build path warns once per credential that a template it may build lacks, naming those templates (`CredentialCheck.missingByCredential`, from each template's own `accounts:`), and goes ahead.
At a terminal, the build and `isx branch` first ask for each missing credential, with the step `isx account add` runs for it, and check again; a skipped one keeps the warning or the refusal (#2405).
The step is taken only from tools a project cannot define, since a cloned repository's tool would otherwise turn into a question for a secret, its wording the repository's.
No question is asked without a terminal, by a build under `--yes`, by a branch with `--format` or `--owner` (a script or an orchestrator must fail fast), from `isx mcp` or from the TUI, whose branch dialog keeps its refusal; this gives up asking in the TUI, where the dialog owns the screen.
A branch asks last in `preflight`, so no other refusal follows a secret typed for it, and a build asks only once its "Continue?" or "Rebuild?" is answered yes, so nobody types a secret for a rebuild they turn down.
A branch asks for the selection it will be stamped with, so a step that adds another account leaves a pin to one without a key refused.
`isx init`'s closing box makes the same check of every current (non-deprecated) template, naming for each missing credential only the topmost templates that need it and counting the rest, because a credential init skipped or failed to verify otherwise surfaces at the first branch.
The refusal, the warning and the box name `isx account add <namespace>` for each missing credential (#1469).
It runs the one step `isx init` runs for that credential, account menu included, and nothing else, so fixing one token costs no sudo and no other step.
It takes the namespace, as `isx account set` does, or the tool that owns it (`gh` for `github`); a namespace wins over a tool of the same name (`CredentialCheck.credentialTool`).
A missing secret names its namespace's owner, the first tool with a step that declares it.
A tool that borrows the key without a step (Copilot's `github.token`) is sent there too.
A tool with a step of its own that is not the owner is named itself, since the owner's step never asks for another tool's key.
With no step on either side, the remedy falls back to `isx init`.
An `isx init <step>` selector was the alternative.
It was not taken because init's other steps are host setup, and `isx account` already names credentials by namespace.
`CredentialCheck` answers for the accounts the branch will *actually* be stamped with -- the template chain's `accounts:`, the source instance's own pins and any `--account` or TUI account row, merged exactly as `BranchFlow` stamps them -- and against the leaf template recorded on the source.
So a branch of a branch is checked too (#793).
What a tool needs is declared, not listed.
Every tool the template chain names, plus everything they `requires:`, spends its credential namespaces (`ToolSetup.credentialNamespaces(params)`, with params resolved as the build resolves them, so pi names only the one its `provider` uses).
The tools serving those namespaces, and the tool itself (Copilot's borrowed `github.token`), are then judged as the proxy judges them, by `ToolProxyResolver.missingSecrets()`.
An auth entry is served once every key it references resolves against that selection.
A tool is refused only when none of its entries would be, naming the keys they reference.
A tool with alternative auth entries on different domains is usable with any one configured.
A key with no navigable config path is never asked for (`SecretRegistry.isNavigable`), since nobody could set it.
The chain is the one the source was *built* from, as its build source recorded it (`BranchFlow.Inherited.builtFrom`): a template edited since, but not rebuilt, does not make its existing branches ask for a tool they do not have.
Only a source with no build record falls back to the current YAML.
Tools the proxy does not serve (feature-gated off, or project-local) are not checked, as their credentials would not be injected anyway.
Readiness that is more than a key being set is the tool's to state, through `ToolSetup.credentialProblem()`.
So `CredentialCheck` names no tool: `ClaudeSetup` requires the resolved Claude account to be *complete* (a pre-accounts `useVertex: true` with no region still presents as an account, and fails every request).
`PiSetup` applies the same rule for its Anthropic provider and requires a Vertex account for `vertex`/`google-vertex`; its `google` provider is judged through the `gemini` namespace like Gemini CLI.
A template listing only pi would not otherwise say why it needs a Claude account, so pi adds a note through `ToolSetup.credentialNote()` (#1750).
The note names pi and its provider, and offers another provider; it follows the command that configures the account.
`CredentialCheck` leaves the note out when a tool without one needs the same namespace, as Claude Code beside pi does.
The credential itself is still reported, so pi's stricter Vertex need is not hidden behind Claude Code's.
A missing credential is one entry, identified by the credential and its command; the notes of the tools and templates that need it are collected on it, so a note never splits one credential into two lines.

**Auth error reporting**: When credential injection fails the proxy records an `authError` and surfaces it on `/health`, which `isx proxy status`, `isx doctor` and the TUI banner all render.
Injection only runs on real container traffic, so that latch alone makes the status wrong in both directions: it reports failures the user has already fixed, and does not report anything before the first API call of a session.
The health endpoint therefore verifies the Vertex token itself, on a worker thread, before answering — clearing a stale error, or reporting a broken credential that no request has hit yet.

The checks it declines are what keep this cheap:

- **Vertex not in use** — returns immediately, before any lock or thread dispatch.
  A non-Vertex setup never invokes `gcloud`, and pays nothing.
- **A valid cached token with no standing error** — the steady state.
  Tokens are cached for 50 minutes, so a healthy Vertex install makes roughly one `gcloud` call per token lifetime regardless of poll rate.
- **A standing OAuth error** (hint `isx init`) — only the user can resolve it; running `gcloud` would not prove anything.
- **A check that ran within 10 seconds, or one in flight** — a failing credential leaves no cached result, so without this every poll from the TUI or `isx doctor` would fork a fresh `gcloud`.

A failure found by the health check is recorded via `recordProbeAuthError`, which never sends a desktop notification: the check runs on every poll, and the notification belongs to the traffic path where a failure actually blocks the user.
The first transition is still logged.
The `gcloud` invocation is bounded at 15 seconds and an empty token is rejected rather than cached — this path is now reachable with no container traffic to reveal a hang or a blank credential.

**PID in `/health`**: the CLI signals the proxy (SIGUSR1: re-read the instance list) after every branch, destroy and `isx account set`.
Finding its PID by the listening port meant `fuser`, which scans the open files of every process on the host -- a measured 90 ms of every branch.
The proxy now reports `pid` in `/health`, and `ProxyService.signalAccountRefresh()` asks for it at signalling time (never a remembered value, so a restarted proxy cannot be signalled by a stale PID), falling back to `fuser` for proxies that predate it.
`isx proxy stop` finds a proxy started by hand the same way (`ProxyService.stopManualProxy`, #155).
`fuser` alone has no `port/tcp` form on macOS and is missing where psmisc is not installed, so it left that proxy running and said "Proxy is not running."
The signal goes to whatever proxy runs on the machine, so unit tests must never send it.
The one seam is in `signalAccountRefresh()` itself, and the test home extensions (`TempHome`, `IsolatedHome`) replace it with a counter for the classes that use them -- so one opt-in covers every caller a test reaches, rather than each caller needing a hook of its own (#871).
Containers reach `/health` too, so the PID goes only to host callers (`MitmProxy.isHostCaller`: loopback, or a source equal to the bridge address the endpoint listens on -- a container's source is its own address, which `security.ipv4_filtering` stops it spoofing).

**Incus unreachable in `/health`** (#2386): while the proxy cannot read the instance list, it injects no credentials, since a failed listing clears every proof.
So `/health` says why to host callers (`incusError`, from `InstanceRegistry.listingProblem`), and `isx proxy status` flags it under the running proxy and reports it as `incus_error`.
The reason names host paths, such as the VM's vsock socket, so a container is not told, as it is not told the PID.
The exit code stays 0: the proxy runs and serves, as with an auth error.
The state clears on the first listing that succeeds.
It was found on a Mac whose proxy launchd started before the VM's Incus answered: the proxy's one `IncusClient` kept that failed connection for life, and every proof was refused until a restart.
The client now connects again after `IncusClient.RECONNECT_INTERVAL_MS` and keeps a connection once made (`.claude/rules/incus.md`).

**Version drift detection**: The proxy health check (run before builds, branches, and shell access) compares the running proxy's version against the CLI version.
If they differ and the proxy runs as a service, the service is restarted, whether or not instances are running.
A proxy run in the foreground only gets a warning with the command to restart it.
This prevents subtle failures from CA certificate or protocol mismatches.
A restart only helps if it changes the running build, and the service starts the *installed* `isx-proxy`, not a binary matching the CLI.
A CLI built from another commit than the installed proxy (every source build, or a partial upgrade) therefore restarted the proxy on every command.
Each restart added ~2.5 s and cut every instance's connection, and the drift was still there afterwards (#798).
`DriftRestartRecord` ends that loop.
Whenever the service starts onto the binary its files were just pointed at (`reinstallIfChanged()`, `upgradeIfNeeded()`, or `install()` of a stopped service), the CLI records its own build together with that binary's path, mtime and size in `~/.local/state/incus-spawn/`.
If version drift persists while the same CLI and binary are in place, `ProxyHealthCheck.assessDrift()` reports that another restart cannot help, and the CLI warns instead of restarting.
Every drift consumer reads that one `DriftReport`.
The CLI build is part of the key even though a restart runs the same binary whatever the CLI, because install.sh's JVM mode rebuilds the jar behind a byte-identical launcher.
A new CLI build is then the only sign a restart would help.
Installing a different proxy or CLI allows one more restart.
The obvious alternative, asking the installed binary for its version, was rejected: `isx-proxy` releases before v0.3.2 have no `--version` and ignore unknown flags, so the probe started a whole second proxy.
It would also have cost a process start on every drifted command.
The record costs one `stat`, plus at most one futile restart per (CLI, proxy) pair.
Its remaining blind spot is a JBang launcher whose jar changes while the CLI does not; the warning still tells the user how to restart by hand.
Config drift is never gated, since a restart re-reads the config.
**Tool proxy config drift** is detected via file stamps.
At startup and on each reload, `ConfigFingerprint.load()` records a `ConfigFingerprint` -- the mtime and size of `config.yaml` and each tool definition `ToolDefLoader` reads from `tools/` -- just *before* reading the config, and then of each tool definition under the `tools/` of every search path that config names.
Those are stamped after the read, the one place their paths are known, but still before any tool definition is read; an edit to the search paths themselves is an edit to `config.yaml`, stamped first.
The proxy's tools are read from that same config's search paths (`new ToolDefLoader(config.getSearchPaths())`), never from a second `config.yaml` read.
Before #890 the loader read `config.yaml` again for them, so an edit could be served without showing as drift.
Tool definitions under a search path were neither fingerprinted nor watched, so the proxy kept their old domains and credential paths until restarted by hand.
`ConfigWatcher` watches the same tool directories (`tools/` and each search path's), following the config loaded last, so an edit there reloads the proxy as one to `config.yaml` does.
Each `/health` request takes a fresh one and reports drift when it differs in any entry; a file added or removed changes the set of entries.
This avoids the cost of re-parsing configuration on every health poll.
It used to compare mtimes against the wall-clock time of the load instead.
A file with a future mtime (`cp -p` from a machine whose clock ran ahead, a clock stepped back, a restored backup) then read as drifted after every restart, so every command restarted the proxy until the clock caught up (#818).
Comparing recorded values involves no clock, and also catches an edit landing in the same timestamp tick as the load.
Capturing before the read means an edit racing the load shows as drift rather than being recorded as already seen; the premise that a restart always clears config drift, which keeps it out of the `DriftRestartRecord` gate, depends on this.
Everything the proxy serves is derived from that one read: startup and `reload()` both build an immutable `ConfigState` from the loaded config (credentials, routing, tool setups and the per-account caches) and publish it whole.
Startup used to leave the per-account part to a lazy read on the first pinned request, which escaped the fingerprint and could land after a reload, putting the old config back (#837).

**CA certificate mismatch**: At branch time, `BranchCommand` compares the template's `ca-fingerprint` metadata against the current CA certificate.
If they differ (e.g. after `isx init` regenerated the CA), a warning is shown suggesting to rebuild the template.
This prevents TLS failures in branches where the container's trusted CA doesn't match the proxy's signing CA.

**Host CA propagation (opt-in).**
Corporate environments often install custom CA certificates on the host for TLS inspection or internal services.
When `host-ca: { propagate: true }` is set in `config.yaml`, `CertificateAuthority.collectTrustAnchors()` builds one trust set: the MITM proxy CA plus host CA anchors from `/etc/pki/ca-trust/source/anchors/`, every certificate in each `.crt`/`.pem` file (PEM bundles and DER alike) re-encoded as PEM from its DER and keyed by its SHA-256 fingerprint (`isx-<sha256>.crt`).
Nothing but re-encoded certificates reaches a guest or the stamp: a file holding anything that does not parse as certificates, such as a private key saved beside a cert, is refused whole with a warning, and comments, bag attributes or line endings change neither the files nor the stamp.
Content-addressable naming eliminates filename collisions and shell injection via filenames, and naturally deduplicates the MITM CA if it also sits in the host anchor directory.
One stamp covers the whole set, in `user.incus-spawn.ca-fingerprint` (the key the MITM-only fingerprint used, so there is one stamp, not two): the CA's fingerprint, then `+` and a fingerprint over the host anchors when there are any.
Without host anchors it is the CA's fingerprint alone, so images built before this stay current and nothing is reconciled on upgrade.
`fixContainerCaIfNeeded` reconciles the entire set in one guest exec (`buildReconcileScript`).
It first stamps `<ca fingerprint>+pending`, which matches no set, so a failed install is retried even if the set changes back (an empty stamp would read as "built before stamps" and never be checked again).
It then removes the old `incus-spawn-mitm.crt` and every `isx-<sha256>.crt`, writes the current set via `Container.heredoc` (safe against content escaping the heredoc), runs `update-ca-trust`, and re-stamps on success.
Only names of exactly that form are isx's: `isx-`, 64 lowercase hex digits, `.crt`, so a custom anchor such as `isx-company.crt` is kept, propagation on or off (#1382).
The script tests each `isx-*.crt` against that form as a `case` pattern rather than globbing it as a path: matching nothing, the glob would reach `rm` as itself, longer than a file name may be, and fail the script.
Two reconciles of one instance are serialized across isx processes on this host by a per-instance `HostLock` (`~/.cache/incus-spawn/locks/trust/<instance>.lock`), held from an authoritative re-read of the stamp to the final stamp, with the set collected under it (#1381).
Unserialized, two overlapping reconciles from different snapshots both removed the managed anchors before either wrote, so a CA one snapshot held and the newer one dropped survived under the newer stamp, and every later use skipped the repair.
The unlocked check, from the caller's read when it has one, only decides whether to take the lock: a stamp is written only after the guest holds its set and is marked pending before anything there changes, so a match is safe to trust without it.
Builds install and stamp under the same lock (`CertificateAuthority.lockTrust`).
A lock another process holds past `HostLock.TIMEOUT` changes nothing, is reported, and is retried on the next use; a lock file that cannot be used at all (NFS without lockd) degrades, with one warning, to the in-process lock rather than to no repair.
Only isx processes on this host take the lock; isx on another host using the same Incus does not.
`CaTrust.classify` compares only the stamp's CA part, which says which CA an image was built against: a host anchor added or removed since is reconciled on first use like a superseded CA, and does not make a template `FOREIGN` (which blocks `isx branch`).
`buildFromScratch` and `buildFromParent` (`BuildCommand.refreshTrustAnchors`) install the set through the same script (`installTrustAnchors`), fail the build if that fails, and stamp the build with that same set right then, never with one read again at the end; `BranchFlow` and `InstancePrep.repairBeforeUse` (the `isx shell` and TUI shell prep) reconcile through `fixContainerCaIfNeeded`.
A stamp read again at the end of a build could name a set the guest does not hold (the CA, a host anchor or propagation changed meanwhile) and make the template read as current.
The config follows the same raw-YAML pattern as `artifact-cache:` and `ready-timeouts:` (`HostCaConfig.of(raw)`): a mistyped section is ignored, not a fatal config error.

On macOS the set also holds what `MacKeychainTrust` yields, through `SystemTrust.keychain()` (#1530): a Mac has no anchors directory, and a TLS-inspection CA is in the Keychain.
That is the same trust isx itself takes there ("Host trust on macOS: the admin domain only"): the certificates an administrator trusts for SSL, never one only the user's domain trusts, and without any certificate the Keychain denies, from either source.
So isx adds to a guest no CA the host's isx would refuse, and nothing a user-level process could add, since admin trust takes root.
A deny, of one public root or of the default roots as a whole, does not reach the guest's own Fedora roots, which isx does not manage.
A Keychain CA is written as an OpenSSL `TRUSTED CERTIFICATE` trusted for serverAuth and clientAuth alone, the DER followed by the auxiliary trust `openssl x509 -addtrust serverAuth -addtrust clientAuth -trustout` appends.
The Keychain trusts it for SSL only, while a plain certificate in the anchors directory lands in the guest's email and code-signing bundles too; p11-kit extracts the trusted form into the TLS bundle alone.
The anchor files' certificates stay plain, as the host trusts them for every purpose, and the file name stays the SHA-256 of the certificate's DER.
A Keychain not fully read adds no CA, whatever it returned as trusted: the guests lose its CAs until a later use reads it, which narrows, and the failure goes to `Warnings`.
That costs a reconcile each way on a transient failure; keeping the guest's set instead could keep a CA the Keychain has denied since, unread.
Read twice when the stamp differs, once to decide and once under the lock: reusing the first read would bring back the race of #1381.
Elsewhere `SystemTrust.keychain()` is empty and runs no command.
The Keychain is read only with propagation on, so the cost lands only there: about 10 ms of parallel `security` runs per `collectTrustAnchors()`, which `fixContainerCaIfNeeded` calls on every `isx shell`.
It is not cached across processes, since a stale read could keep handing guests a CA the Keychain has since denied.

**Leaf certificate persistence (clock-skew safety)**: Per-domain leaf certs are not minted fresh on every proxy start.
`CertStore` persists them under `~/.config/incus-spawn/certs/` (keyed by domain — `<domain>.crt`/`.key`, wildcards as `_wildcard.<domain>`) and reuses them across restarts, re-minting only when a cert is missing, was signed by a rotated CA, or is within 30 days of expiry.

This fixes an intermittent "certificate is not yet valid" failure.
A cert's `notBefore` is stamped from the **host** clock at mint time, but it is validated against the **container** clock.
These are independent clocks: on macOS the proxy runs on the Mac host (launchd, `KeepAlive=true`) while containers run inside an Incus VM whose clock lags after the Mac sleeps.
The guest agent that a host's `guest-set-time` reaches did not listen until #2282, so whether `--timesync` sets it on resume is unmeasured (design/macos-and-vms.md P5).
`KeepAlive` relaunches the proxy whenever it exits (including right after wake, when the Mac clock has already jumped forward to real time).
Re-minting at that moment produced a `notBefore` in the lagging container's future, failing validation with "certificate is not yet valid".
No clock ever runs backward — both are monotonic — but the gap between the mint clock (host, ahead) and the validating clock (container, behind) can exceed a day.
Reusing a persisted leaf keeps its original `notBefore` (stamped while the clocks were in sync), so the container's lagging-but-monotonic clock always accepts it.
`CertificateAuthority.BACKDATE_MS` (2 days) backdates `notBefore` as a margin for the rare remaining fresh-mint moments (first install, CA rotation, near-expiry renewal).
The underlying clock drift is now corrected by `chronyd` running inside the VM appliance — it steps the guest clock to NTP time within seconds of network recovery after wake (see `appliance/DESIGN.md`, Clock Synchronization).
Cert persistence and backdating remain as defense-in-depth for the brief window before chrony syncs.

Certs are keyed by domain, never by container: a leaf is a function of `(domain, CA)` and is identical for every container that intercepts that domain.
Planned per-container interception (a different intercepted-domain set per container) is a routing/DNS concern — it decides which domains reach the proxy for a given container — and does not change cert identity, so the store stays domain-keyed.
Certs are already resolved per SNI name on demand against this same on-disk store (see below), so that feature needs no change here.

**Certificates are chosen per SNI name, at any depth (#783)**: dnsmasq's `address=/<domain>/` sends *every* name under an intercepted domain to the proxy, however deep, but a wildcard SAN matches exactly one label (RFC 6125).
The proxy used to serve one JKS holding `<domain>` and `*.<domain>` for each intercepted domain.
A deeper name such as `results-receiver.actions.githubusercontent.com` (where `gh run view --log-failed` fetches logs) matched no alias.
Vert.x then fell back to an arbitrary keystore entry.
The container was offered `*.api.openai.com` for a GitHub host, an error that points at the wrong domain entirely.
`InterceptedCertOptions` (proxy module) now implements Vert.x's `KeyCertOptions.keyManagerFactoryMapper`: `certNameFor()` maps the SNI name to the exact domain, or to a wildcard for the name's own parent (`*.actions.githubusercontent.com`), minted through `CertStore` on first use and so persisted like every other leaf.
With `useWorkerThread` set, Vert.x runs this mapping on a worker thread, so the RSA key generation never blocks the event loop.
Vert.x 4 did so regardless, while Vert.x 5 runs it wherever the SSL engine's delegated tasks run, so `MitmProxy.mitmServerOptions()` sets `useWorkerThread` on the engine.
The flag also moves the rest of every handshake's delegated tasks from the event loop to the worker pool.
A reload builds its SSL options from those same server options, because the update replaces the server's whole SSL configuration.
The `<domain>`/`*.<domain>` certs are still pre-minted at start.
Because the container picks the names, two bounds apply.
`MAX_ON_DEMAND_CERTS` limits on-demand certs *stored on the host*: it is counted from the cert directory at construction, so neither a reload nor a restart grants a fresh batch.
`CertStore` never deletes, and a name already on disk is always served.
`MAX_SERVED_NAMES` limits distinct SNI names answered per server configuration, because Vert.x caches an `SslContext` per answered name and never evicts.
Without it, `r1…rN.githubusercontent.com`, all served by one pre-minted wildcard, would grow the heap without minting anything.
A reload replaces Vert.x's cache, so that count is per instance.

Anything the proxy cannot serve correctly -- a name outside every intercepted domain, a client sending no SNI, a name past the cap -- is refused, never answered with an unrelated certificate.
The refusal is expressed by the mapper returning `null` and the **default** key manager holding no certificate, so the handshake ends at once with `handshake_failure`.
Throwing from the mapper looks equivalent but is not: Netty's SNI handler then leaves the connection hanging until the handshake timeout.
Returning `null` also keeps Vert.x from caching an SSL context per refused name.
Because SNI names become file names in the cert store, `CertStore.get()` accepts only strict hostnames (`CertStore.isHostname`), optionally as a one-label wildcard.
`InterceptedCertOptionsTest` handshakes with a client that trusts only the isx CA and verifies hostnames, at several depths, and checks every shipped intercepted domain (built-ins plus every bundled tool's `proxy:` domains) resolves to a matching cert name at depths 0-3.
So a newly added domain is covered without being listed.

**Vertex AI token refresh**: Vertex AI requests that receive a 401 response are retried once with a fresh GCP access token (the cached token is invalidated), the body replayed from the same spool ("A body the proxy must hold").
This handles token expiry during long-running sessions without user intervention.

**API request bodies (#1315)**: a request to a credential-injecting domain is streamed upstream as it arrives, as the plain relay streams an upload, unless it is a Vertex model call.
Every credential is a header, so nothing but Vertex needs the body: its URL is built from the body's `model` and `stream`, and its 401 retry sends the body again.
Held whole, a body cost about three times its size in heap, and a Vertex one about six, so a 64 MiB `git push` or a 24 MiB translated Messages request exhausted the native proxy's 256 MB heap, and a failed Vertex request was never answered.
`MitmProxy.streamApiRequest` hands the request to `Upstream.relayRequest` with `RelayHooks`: `prepare` copies the head and runs `injectHeaders` before any of the request leaves, so the credential is on the head even when the head goes out early for a client's `Expect: 100-continue`.
Everything else is the plain relay's (the pause at routing, backpressure, `RelayWatchdog`, the early answer that closes the connection, upstream's `100`), and its framing too: a chunked upload such as git's push reaches upstream chunked, where the whole-body path gave it a `Content-Length` of its own.
There is no upload size limit, for the reason the relay has none.
A Vertex model call is held instead, in bounded memory ("A body the proxy must hold").

`--debug` does not hold a body to log it: each body is captured as it passes, up to `ApiTrafficLog.MAX_BODY_CAPTURE` (64 KiB), and a body cut there is logged as `[captured N of M bytes]` and its first 4 KiB, as much as an uncut body that is not JSON shows; a translated Vertex body is logged as it was forwarded too.
`isx proxy dump` (`DumpProxy`), which holds each body whole anyway, still logs it whole (`ApiTrafficLog.Capture.whole`).
Responses are piped as they are without it; the log is written once the response ends.
Otherwise turning on diagnostics would change what it diagnoses: the upload's latency, its framing, and the proxy's memory.

**A body the proxy must hold (#1315)**: a Vertex model call is sent only once its body has ended, since its URL is built from `model` and `stream`, which SDKs put last, and a 401 sends the same body again.
`MitmProxy.retainVertexRequest` pauses it at routing (#1164), translates it as it arrives, and writes it to a `BodySpool`, kept across the 401 retry.
A Gemini call on the Vertex AI account is held the same way, through `MitmProxy.holdVertexRequest`, so its body is checked whole before any of it is sent (#1925).
It is released once an answer other than a 401 has come and the body has all gone upstream, or the exchange ends, whichever is first, so a long streamed answer does not keep it; the release runs on the spool's own event loop, and a spool gives back what it kept once, however many closes race.
Memory is bounded by one `SpoolBudget` (32 MiB, an eighth of the native heap) shared by every spool, which counts bytes kept in memory, bytes waiting for their disk write and bytes read back but not yet written to the upstream socket.
Kept bytes take at most half of it, so the other half always serves I/O in flight and a write never waits behind memory nobody gives back until their exchange ends.
Beyond its share a spool writes to a file in an owner-only `~/.local/state/incus-spawn/proxy-spool/`, not `/tmp`, which is tmpfs, so RAM, on Fedora; the file is unlinked as soon as it is open, so a crash leaves no name behind, and closing its handle frees its storage.
Backpressure holds both ways: the client is paused while its spool's disk writes queue beyond 256 KiB or the budget is spent, and the spool, its kept part as much as its file, is written upstream no faster than the socket drains.
A replay has one cursor and one drain handler, installed before its first write, as Vert.x's own pipe does: a drain that lands just as the queue is found full is not missed, and a drain nobody waited for only resumes from the cursor, so no byte goes upstream twice.
`BodySpool.close()` ends a spool on every path (the response's end or close, a refusal, a disk error, an unexpected error, which is answered with a 502), once, and a write still pending when it closes gives back its own share when it completes.
A kept chunk a replay already handed to the socket may still sit in Netty's queue for a moment after the close gives its share back, so the budget can undercount by up to its kept half for that moment.
A spool is only ever used on its own event loop: a replay asked for elsewhere (the shared token fetch completes on whichever request started it) hops there first, so it never races the spool's close, and a 401 stops the first send's replay before the retry's begins.
So the heap held for bodies is the budget plus what Netty holds for any connection, a few TLS records each, whatever the size or the number of bodies.
`DiskReserve` keeps the host's disk from filling: the spool leaves free min(15% of the filesystem, 4 GiB), journald's `SystemKeepFree` default, the rule the same host's logger keeps (3 GiB on 20 GiB, 4 GiB on 2 TiB; 5% would keep about 100 GiB on 2 TiB).
Free space is sampled off the event loop every 8 MiB written or every second, and a write is admitted only if the sample, less every write pending and every write completed since the sample began, leaves the reserve free.
A body that does not fit is answered `507 Insufficient Storage`, naming the host's free space and not the body, with nothing sent upstream.
The connection closes once the rest of the body has been read and dropped, once none has come for 5 s, or 30 s after the 507 however it keeps coming (#1617): nginx's `lingering_timeout` and `lingering_time` (`LingeringClose`, which the plain relay shares).
The cap is what stops a client trickling bytes from holding a refused connection, which every read would otherwise keep open.
Closed with some of it unread, the connection is reset, and a client still sending its body loses the 507 with it.
A client refused before it was told to continue sends no body, and its connection closes at once.
The same check runs before the proxy answers a client's `Expect: 100-continue`, but only for a body that may spill (no `Content-Length`, or more than half the budget): one that fits in memory never touches the disk, so a host short of disk still serves it.
It answers that itself (RFC 9110 §10.1.1): upstream cannot be asked before the body is in, and a client that waits for `100` would otherwise wait out its own timeout; upstream gets the body whole, with a `Content-Length` and no `Expect`.
There is no upload size limit: a body passes as long as the host has disk for it.
**Rejected:** a 413 cap (it refuses what upstream accepts, against the relay's transparency); a memory budget alone (a body larger than it would deadlock or be refused); the tree translation over a spooled body (still six times the body in heap); translating while streaming with no copy (`stream` comes last, and the retry would be lost); `/tmp` and off-heap buffers (both RAM).

**Proxy caching**: The proxy caches three types of upstream content to avoid redundant downloads:
- **OCI blobs** — keyed by SHA256 content digest, verified on store
- **OCI manifests** — by digest, verified on store, with their media type beside them; each hit is confirmed with a `HEAD` carrying the caller's token before its copy is served (see "Container image manifests" below)
- **Maven/Gradle artifacts and Gradle distributions** — keyed by repository path, verified on store, confirmed with upstream as recently as `artifact-cache:` asks (see below)
- **Downloads of `confirmed-cache:` domains** — keyed by host and path, each serve confirmed by upstream's `304` for the client's own request (see "A cache that confirms every serve" below)
- **npm tarballs** — keyed by package name and version, with ETag-based verification against the npm registry packument.
  A cached tarball is served without a lookup only while the packument ETag is the one it was verified under and its last confirmation is within the same tiers as Maven/Gradle (`artifactCacheTiers`, see "Confirmation tiers" below).
  The stored shasum's modification time is that confirmation: set when the tarball is stored, renewed by a matching lookup.
  Within `fresh` the hit makes no upstream request, within `max-stale` it is served and confirmed in the background (the same single-flight, capped queue), and older it is confirmed first.
  When the ETag changes or the confirmation is too old, the per-version digest is checked — a match updates the marker, a mismatch evicts and re-fetches.
  That digest is the version document's `dist.integrity` SHA-512, falling back to SHA-1 only for a document whose integrity is absent (or empty) or lists only SHA-1 hashes, and then to the integrity's own SHA-1, which npm checks, before `dist.shasum`; any other integrity with no readable SHA-512 (malformed, not a string, another algorithm, in any case) counts as no digest, never as a reason to settle for SHA-1 (`npmDigest`, #1465).
  The tarball, its `.etag` marker and its `.shasum` are committed together under `VerifiedArtifactStore`'s per-artifact lock, which every eviction and confirmation of it also takes (#1464): sidecars written at lookup time, before the download, let two concurrent misses whose lookups straddled a republish leave one tarball beside the other's digest, which the next matching lookup then served as confirmed.
  It is stored prefixed (`sha512:<hex>`) in the `.shasum` sidecar; a bare SHA-1 stored before #1465 still matches a version without a SHA-512, and a version with one is downloaded once more and verified on it (`storedNpmDigest`).
  As for Maven/Gradle, when upstream answers its answer wins (#787): a version document that is gone (`404`/`410`: unpublished, or taken down as malware) evicts the tarball and the client gets upstream's answer, and any other answer without a usable digest relays the tarball without serving the copy.
  Only an unreachable registry (no connection, `5xx` after its retry, `429`) serves the copy unconfirmed, and logs that it did.
  `registry.npmjs.org` has the same backoff as the Maven/Gradle domains, started by a refused connect or a `429` ("Offline" below).
  During it a cached tarball is served unconfirmed without a lookup and no background confirmation starts, so an offline or rate-limited install does not wait on, or spend, a refused request per tarball.
  The ETag alone used to vouch for a tarball, but `npm ci` with a lockfile fetches no packument, so the ETag never changed and a version removed upstream stayed served indefinitely (#1466).
  The age check bounds that: at most `fresh` with no lookup, then the background confirmation evicts it for the next request.
  The per-version shasum is looked up asynchronously through `probeClient`, the same client and verification as Maven/Gradle checksums, so a cold install's lookups run concurrently over shared HTTP/2 connections (#960).
  They used to be a raw `SSLSocket` per tarball inside an *ordered* `executeBlocking`.
  An ordered task on a request's duplicated context joins its parent context's single queue, which every MITM connection shares (measured on Vert.x 4.5, unchanged in 5.2.3's `DuplicatedContext.executeBlocking`).
  Every lookup from every instance ran one after another, so 540 packages took 195s through the proxy against 53s direct.
  So nothing that waits on the network runs in an ordered `executeBlocking`.
  The npm path's disk checks still do, on purpose: ordering keeps a tarball's ETag read behind the packument's ETag write (`relayNpmPackument`), which an unordered read could overtake and serve a tarball on a stale ETag without a shasum check

**Container image manifests** (#2306): Docker Hub counts a pull per manifest `GET`, never a `HEAD` and never a blob.
Its limit is 100 pulls per 6 hours per IPv4 address or IPv6 /64 for anonymous pulls and 200 for a personal account, and a multi-arch image counts once per platform it fetches ([Docker's documentation](https://docs.docker.com/docker-hub/usage/pulls/), read 2026-11).
A blob cache alone saves bandwidth but not one counted pull, and a throttled pull of a fully cached image still fails at its manifest.
quay.io does not limit anonymous pulls except under abuse, and ghcr.io publishes no limit, so for them the cache saves round trips and survives an outage, nothing more.
`ArtifactCacheHandler.serveManifest` takes every `GET /v2/<name>/manifests/<reference>` without a query; `ManifestCache` holds the files and the rules for reading the requests.
- A manifest is stored by its digest.
  A miss is fetched with the client's own headers, streamed to it, and committed through `VerifiedArtifactStore` only when the bytes hash to the digest, with the response's `Content-Type` beside it as `.type`, the copy's media type.
  For a tag, the digest is the answer's `Docker-Content-Digest`; an answer without one is relayed uncached.
  The media type matters: a client tells an index from a single-platform manifest by it, so a copy is never served as `application/octet-stream`.
- A tag is a name that can move, and one repository may be private.
  Every hit, by tag or by digest, is confirmed with a `HEAD` carrying the client's own headers, its `Authorization` and `Accept` included.
  A tag's copy is served only when the `HEAD`'s `Docker-Content-Digest` names a stored copy, and a digest's when the `HEAD` answers 200.
  That one uncounted request proves that the registry lets this caller pull it, and for a tag that the tag still points there.
  A digest is confirmed too, unlike a blob's: a manifest digest is often published (an image pinned in a chart, a compose file or a CI log), so knowing one is no proof of access, and the manifest would lead to every layer the blob cache holds.
  That is the rule #2312 set for private Maven repositories: a copy reaches a caller only on the registry's word for that caller.
  A `401`, `403`, `404` or any other refusal relays the client's `GET`, so the client gets the registry's own answer, its `WWW-Authenticate` challenge included.
  A tag never seen before skips the `HEAD`, which could only add a round trip before the `GET`.
- Which digest a tag last resolved to is kept per registry, repository, tag and `Accept` (`ManifestCache.tag`), since one tag gives a client that accepts other media types another manifest.
  Every `Accept` line counts, joined: Go clients such as podman's containers/image send one line per media type.
  Every file name is a hash of what names it, so no part of a request becomes a path.
- When the confirming `HEAD` cannot be answered (a refused connect, a `5xx` or a `429`), a copy is served only for a digest the anonymous check found public in that repository: the digest asked for, or the one a tag last resolved to.
  It is logged as unconfirmed and, while the registry answers `429`, counted on `/activity`'s `throttled`.
  Any other digest is not served, since nobody can tell whether the caller may pull it; the client's `GET` is relayed and gets upstream's own answer.
  Public means what an anonymous client finds for that digest in that repository: a `HEAD` with no credential, and on a Bearer challenge a pull-only token from its realm and the `HEAD` again with it (`checkPublicInBackground`).
  Only that check writes the marker, per registry, repository and digest (`ManifestCache.publicMarker`), never a caller's own fetch or confirmed hit.
  A marker per repository would let a repository made private lend its old check to a manifest pushed since, which a caller with access then pulled: a throttle would hand it to an instance the registry refuses.
  Copies are shared by digest, so the repository is in the key too: a private image's digest asked for under a public repository's name is not served.
  None of the three is a counted pull.
  The realm must be an `https` URL on a registry domain with no port, and the token request follows no redirect, so a registry's challenge cannot send the proxy anywhere else.
  The scope is the proxy's own, never the challenge's.
  The check runs after a manifest's fetch and after its confirmed hits, at most every 6 hours per digest and proxy, since a manifest that never moves is never fetched again.
  A digest new to the repository is therefore checked at its first fetch, and is not served during a throttle until that check got an answer.
  The marker is there exactly while the last check that got an answer found the digest public: a `5xx`, a `429` or a failed exchange leaves it as it was, so a flaky registry does not drop the marker exactly when it is needed.
- There are no confirmation tiers for tags: every hit is confirmed, since the `HEAD` costs no pull, so `:latest` is never served stale while the registry answers.

**What it gives up:** during a throttle or an outage, a public tag that moved upstream is served at its old digest.
A digest found public stays marked until a check of it gets an answer saying otherwise.
Checks of one digest are at least 6 hours apart, run only when this proxy fetches it or the registry confirms a pull of it, and leave the marker as it was when they get no answer.
A pull the registry refuses does not check it again, and neither does a copy served unconfirmed.
The marker has no age bound.
During an outage nothing checks the digest, so its marker does not change, and a manifest that was public once is served to a caller the registry now refuses for the whole outage.
The marker carries into later outages too.
It goes only when a fetch or a confirmed hit at least 6 hours after the digest's last check runs a check that gets an answer and finds the digest not public: an anonymous `HEAD` refused, or no anonymous token, but not a `5xx`, a `429` or a failed exchange.
A proxy restart clears that 6-hour gate, so the first such pull after a restart checks at once.
Only bytes an anonymous client could pull at some check are served that way, never a manifest pushed after the repository was made private.
A client's own `HEAD` (containerd resolves a tag that way) is relayed, not answered from the cache: Docker Hub does not count it, and during a throttle the registry is believed to keep answering it.
The registries' 429s are not waited out (`waitOutThrottle` excludes them): Docker Hub's window is 6 hours, longer than any client waits.

**Maven/Gradle cache integrity**: the rule is that a cached artifact is served only when a direct fetch would have returned the same bytes when upstream last confirmed it (#555), and that confirmation is recent enough.
How recent is configurable; with both durations at zero, every hit is confirmed before it is served.
Only an unreachable upstream serves an older one.

- *Confirmation tiers* (`ArtifactCacheTiers`, `artifact-cache:` in config.yaml).
  The stored checksum's modification time is when upstream last confirmed the artifact: set to the time of the lookup the commit was verified on (not when its download finished), and renewed by a match only from an answer that could have evicted (on Central a client's own `.sha1` request matching does not confirm the artifact, since the header outranks it).
  Renewal is best-effort: a checksum file the proxy may not re-stamp (another owner) just ages into being confirmed first.
  Every answer is ordered by its probe's time, taken before upstream was asked: a match stamps that time and never moves the confirmation back, and an answer from a probe made before the copy's last confirmation (a commit or another match after it) has no effect (`Outcome.SUPERSEDED`), so a slow, older probe neither evicts a newer copy nor makes a download looked up after it look superseded (#1542).
  The client then gets the copy, and for a sidecar request the copy's stored sidecar, else upstream's answer to one more ask made after that confirmation (a 502 if that one is superseded too), never the older answer's checksum beside the newer bytes.
  That includes a 404/410: a takedown that reached upstream before the copy's confirmation was not in force when upstream confirmed it, and one after it is seen by the next probe.
  A sidecar answer settled by Central's checksum header applies only to the copy it was reconciled against: if that copy was replaced before the header matched, the match may be about bytes committed after the sidecar was asked for, so the answer neither stores nor drops a signature beside the new copy, and the client gets the copy's own (#1555).
  Younger than `fresh` (default 2h, and 12h for Maven Central while config.yaml has no `artifact-cache:`), a hit is served from disk with no upstream request.
  Younger than `max-stale` (default 7 days), it is served at once and confirmed again in the background: a match renews the confirmation, a change or withdrawal evicts the copy so the *next* request fetches it, and an answer that settles neither (a 403, an unusable sidecar) expires the confirmation, so the next hit gets the confirm-first path's handling.
  Older, it is confirmed before it is served, as below.
  Background confirmations are single-flight per artifact, at most 16 in flight with the rest queued (dropping them would leave copies unconfirmed build after build under a parallel resolver), and confirm no copy during a domain's backoff.
  Overlapping lookups share one (`SingleFlight`, #1567).
  A request that arrives while the same sidecar settle, confirmation (foreground or background), host-copy import or npm version lookup is in flight joins it and gets its result.
  Two answers looked up at about the same time are both correct, but each was settled separately.
  On Central the answer settled last overwrote or dropped the signature the other had stored.
  For two confirmations, the later probe's renewal superseded the other answer, whose request then went out without its checksum header.
  Every lookup is made by the proxy itself, with no client header, so its result belongs to no one caller.
  What depends on a caller stays its own: an npm lookup's 5xx is retried on each caller's own silence budget, and an unreachable sidecar falls back to each caller's own stored copy.
  The key is released before anyone is answered, so a request after that asks again, and its lookup follows the first one's writes.
  Downloads are not joined: concurrent downloads of one file are already safe (#1538), and joining one in flight would mean streaming each follower from a growing spool and resuming it separately, for bandwidth alone.
  The store-side ordering rules stay, since joining covers only one proxy's lookups in flight.
  Each repository carries its own tiers (`CachedRepository`, its `Revalidation`); the Plugin Portal's are zero, since authors delete versions there often enough, so its every hit is confirmed first.
  npm tarballs get the same tiers, keyed on the stored shasum's modification time (see "Proxy caching" above).
  `artifact-cache:` is read from the raw YAML value, so a mistyped section (`artifact-cache: 0`) is warned about and ignored instead of failing config.yaml, which would leave the proxy with no credentials.
  The stored checksum goes out as `X-Checksum-SHA1` with bytes the proxy opens by path (Vert.x 5 added `sendFile(FileChannel)`, but it is marked unstable and HTTP/1-only).
  So just before `sendFile` the path is checked to still be the file the checksum was read with, and a copy replaced meanwhile is confirmed first.
  The window left is the few microseconds between that stat and the open, on the same thread.
  The hit path reads the copy without the store's lock (commits hold it across an fsync), re-reading under it when the artifact changed between the reads.
  Why tiers: a warm build that asks for 1,500 artifacts one after another paid ~20 ms per `HEAD`, ~30 s against ~3.5 s with no confirmation (Maven's default depth-first collector cannot overlap them).
  What they give up: within `fresh`, a republished or withdrawn artifact is served until the window ends, and in the background tier it is served once more before the eviction.
  Every repository can withdraw a release, Central included: it lets no publisher change or remove one, but removes releases itself on occasion (copyright, malware).
  So the tiers are not a claim that nothing is withdrawn; they are how long a withdrawal may go unnoticed, which the host's owner sets per repository.
  The Plugin Portal, where authors delete versions often enough, has tiers of zero.
  Maven Central's `fresh` is 12 hours instead of 2 while config.yaml has no `artifact-cache:` (`ArtifactCacheTiers.CENTRAL_DEFAULT`, #2305).
  What a warm cache sends Central is one `HEAD` per artifact per `fresh` window, about 1,700 at once for a Quarkus build (docs/dev/PERFORMANCE-NOTES.md "Requests a new instance's build makes, and what a warm cache sends Maven Central").
  That count does not grow with the number of instances, since a confirmation renews the shared copy, and `max-stale` only moves it between the background and the foreground, so `fresh` is the one duration that lowers it.
  It is a default, never a repository's own tiers: any `artifact-cache:`, even one key, replaces it whole (`Revalidation.tiersOr`), so it never loosens a duration the user wrote, such as a `max-stale` below 12 hours that the record's clamp would raise.
  The 404 memory the same issue proposed was not built: Maven asks repositories in order and stops at the first that has an artifact, so Central's 404s came to four of 1,721 requests in that build.
  For comparison, without isx a withdrawn release stays in each developer's `~/.m2` for good, since Maven never checks a release it already has, so any finite tier is stricter.
  A confirmation time in the future (a clock stepped back) is not trusted.
  Hits are not logged one by one: at thousands per build, a line each on the event loop cost more than a hit served on trust.
  `ArtifactCacheStats` counts them by kind, and the first hit after a quiet spell schedules one summary line 5 s later, so an idle proxy neither logs nor wakes up for it.
  Evictions, errors and newly cached artifacts are still logged individually.
- *Confirm a hit* (past the tiers).
  The repository's `Revalidation` says how (`CachedRepository`, one per repository, matched by the longest path prefix on its domain); a path under no repository is never cached (`VerifiedArtifactStoreTest` fails if a built-in Maven/Gradle domain lacks one, `MavenRepositoriesTest` holds what a declared one must say).
  Maven Central sends `X-Checksum-SHA1` with every artifact, so a hit costs one `HEAD` (`HEAD_CHECKSUM`), compared with the stored `.sha1`.
  The Gradle Plugin Portal and Gradle distributions send no checksum headers, so a hit fetches the `.sha1`/`.sha256` fresh (`SIDECAR`, following Gradle's redirect to `downloads.gradle.org`).
  A match serves from disk.
  A different checksum, or a 404/410, evicts the artifact, and the request goes upstream.
  So does any answer that confirms nothing (401/403).
  A `429` serves the copy unconfirmed after that one request, and starts the domain's backoff ("Offline" below).
  A stale or withdrawn copy is therefore never served, whichever client asks, including Gradle, which never requests sidecars.
- *Pass the checksum on.*
  A confirmed Central hit carries the `X-Checksum-SHA1` upstream just sent, and a miss carries upstream's own `X-Checksum-*` headers.
  Maven Resolver's smart checksums then check the bytes against upstream and skip their own `.sha1` request, so for Maven 3.9+/4 the `HEAD` replaces a request rather than adding one.
  A hit served on a trusted confirmation carries the stored checksum instead, and stored sidecars are served for it: within the window the proxy vouches for the checksum as for the bytes.
  Otherwise Maven would fetch the `.sha1` from upstream and pay the round trip the tiers save.
  Outside the window the header is only ever upstream's current value: one made up from the store for an unconfirmed copy would turn the client's independent check into a check against our own cache.
- *Verify on store.*
  A miss is just the `GET`, streamed to the client and a temp file at once, and committed only if it matches a checksum from upstream: the same response's `X-Checksum-SHA1` on `HEAD_CHECKSUM` domains, else the sidecar fetched once the download is done.
  The sidecar is committed with it.
  With no usable checksum the artifact is served but not cached.
  A host `~/.m2` copy is the exception: its checksum is fetched first, and a matching copy is imported instead of downloading, with `Files.copy`, never a hardlink.
  On JDK 25 `Files.copy` clones where the filesystem can (`copy_file_range`, which btrfs and XFS turn into a reflink, verified in the native image too; `clonefile` on APFS), so the copy costs no space there.
  A hardlink shares the inode, so an in-place rewrite of the `~/.m2` file (`cp` onto it, a truncating writer) would change the cache without it noticing.
- *Sidecar requests go upstream* once the artifact's confirmation is past `fresh`/`max-stale` (within them the stored copy is served, see the tiers), and the answer is reconciled with the stored copy the same way.
  This is a second line of defence; confirming the artifact is what provides correctness.
  A checksum we cannot parse does not confirm the copy and does not evict it (Maven accepts the `sha1sum` and BSD/OpenSSL formats, and so does `Sidecar.hex`).
  On `HEAD_CHECKSUM` domains a sidecar never evicts on its own.
  A separately uploaded `.sha1` can be wrong where the server-computed header is right (old Central artifacts), so a disagreement (`Outcome.DISAGREES`) is settled by a `HEAD`, and only by its header (`SidecarAnswer.fromHeader`), never by the `.sha1` a header-less `HEAD` falls back to.
  When the header confirms the artifact, the stored sidecar follows upstream: a changed `.asc` is kept, a withdrawn one dropped.
- *Resume a broken download.*
  A download to any cache (OCI blob, Maven/Gradle artifact, npm tarball) that stalls or breaks off mid-body asks for the rest with `Range`, and streams it on in the same response, so the client never sees the break (`CachingDownload`).
  npm is why: it treats a failed optional dependency as skippable, so a broken platform-package download (`@openai/codex-linux-x64`, 162 MB) left `npm install -g` exiting 0 without the binary (#808, #925).
  A stall is 20s without a byte from upstream (`downloadIdleSeconds`), not counting time it waits on our own backpressure, a client or disk that stopped reading, or on a resume being asked for.
  Vert.x's request idle timeout stops at the response head, so the proxy watches the body itself.
  Everything is fitted into the client's *silence budget*: 110s without a byte from us (`clientSilenceBudgetSeconds`), below the MITM server's 120s idle timeout, which otherwise drops the client silently while the upstream read is still pending (the upstream client's read-idle timeout, 300s, outlasts it; lowering that for every relay would cut slow uploads, which get no bytes back for as long as they send).
  The budget runs from the client's request, so waiting for the response head gets what the lookups before it (an npm shasum, a Maven confirmation) left, a head that never comes becomes a logged 502 the client can retry, each resume (connect, head, backoff after one that could not be made) gets what is left of it since the client's last byte, and the stall check looks again the moment a pause ends (the temp file opening, a drain, a resume's head), since a head or a resume that arrived late can leave less of the budget than one check.
  Upstream always gets a second after we let it go before it counts as stalled, and only a client that drains renews its budget, not a disk.
  When too little is left, the client gets the error, logged, rather than a silent drop.
  A connect timed out that early does not show that the domain is unreachable, so it does not start the domain's backoff below.
  A resume needs a validator for `If-Range`: a strong `ETag`, or `Last-Modified` when there is no `ETag` at all and it is at least a second older than the response's `Date` (servers ignore a weak ETag, RFC 9110 does not let a date stand in for an entity tag, and a date within the second it was sent could match two versions).
  Without one there is no resume, since a file changed meanwhile would be spliced onto the old one's bytes.
  Only a `206` with the first response's validator, no `Content-Encoding`, a `Content-Length` of what is left and a `Content-Range` from the byte the client has to the end of the same length continues; `If-Range` alone is not trusted, since GitHub's asset store answers `206` to a mismatched one (#900, #1405).
  Anything else (a `200` from an upstream that ignores `Range` or whose file changed) ends the response with an error.
  A response that ends before that length is another break.
  A `5xx` or `429` to the Range request is retried like a resume that could not connect: an overloaded upstream is what stalls a download in the first place.
  Three resumes in a row that get no further give up; one that got further starts the count again.
  An encoded body (any `Content-Encoding`, as in `DownloadCache`) is never resumed: compressed on the fly, the same file need not give the same bytes twice.
  A download that could never be resumed (encoded, no length, no validator) is not cut at a stall either, since that could only fail a body that may yet carry on.
  Only the client's budget ends it, with a line, where before the MITM server's 120s did so silently.
  An error sent before the first byte drops the headers set for the artifact, so a 502 never carries its `X-Checksum-SHA1`.
  Verification is unchanged: the whole file is checked before it is committed.
  A client that leaves mid-download, or while its head was still awaited, does not stop it.
  It goes on into the cache, as it always has, so a client that gave up and retries gets a hit.
  With nobody waiting the budget no longer applies.
  A cache download, its redirect and its resumes never ask upstream for `Connection: close` (#2082), so the connection is pooled again.
  An upstream response is never paused in Vert.x (`ResponseHold`, #2088).
  A paused Vert.x 5 HTTP/1.1 connection queues what still arrives, and drops that queue when upstream closes: the body's tail and its end are lost, and the response fails with "Connection was closed".
  A server closes on its own after a body when it speaks HTTP/1.0, sends `Connection: close`, reaches its request limit or times the connection out.
  So the cache download and every relay hold upstream back by turning off the socket's auto-read, and keep what Netty had already read, in order, with the end or error behind it.
  That is at most one read's worth, or the socket's receive buffer once upstream has closed, which epoll then reads to the end.
  Reading is turned back on once Vert.x has delivered the response's end or error, since the connection may then serve another request.
  HTTP/2 is still paused, since its flow control keeps what arrives.
  Vert.x can still pause an HTTP/1.1 connection itself, and so lose the tail the same way, but only for a response handled off the connection's event loop (#2176).
  There the stream's queue crosses threads, and it pauses the connection once it holds more than 16 chunks.
  On the connection's own loop it hands each chunk on as it is read, so it never fills.
  Every response is on that loop today, and by structure: the MITM listener is one server on one event loop, so every guest request and every upstream connection it opens run there.
  Two listener instances break that, and then a 20-chunk answer closed by upstream lost its end through the proxy (docs/dev/PERFORMANCE-NOTES.md "Upstream responses off their connection's event loop").
  So `ResponseHold` counts a response off its connection's loop and warns once, and `ArtifactCacheProxyTest#upstreamResponsesRunOnTheirConnectionsEventLoop` holds the count at zero.
  Serving the MITM listener from more than one event loop would need upstream clients per loop first, so that a pooled connection is only reused on the loop that opened it.
  Plain relays (`relayRequest`, including an npm tarball whose shasum could not be fetched) are held to the same budget without a resume (`RelayWatchdog`, #929): 110s since the client's last byte either way, not counting the time its request body is streaming or the client holds the response back.
  So an upload is never cut while the client sends, and nothing is ended that the 120s timeout would have let live: while upstream holds the body back the client is paused too, and its connection idle.
  An early answer can stream while the upload still does, so the watchdog keeps the two waits apart: the end of one never ends the other.
  A head that never comes is a logged 502, a body that stops a logged reset, and the upstream request is let go rather than left for its 300s read-idle timeout.
  A client that leaves before its answer ends is treated the same way: the upstream request is reset, not left streaming into a closed response, so a model call stops generating an answer nobody reads (#1448).
  A connection that closes before any of the answer came is sent again once, on a new request, when the request is a GET, HEAD or OPTIONS without a body (`Upstream.mayResend`, #1914).
  An upstream may close an idle pooled connection just as the relay sends on it, and a public clone in CI got a 502 from exactly that.
  RFC 9110 lets a client resend an idempotent request then, as curl and browsers do; the resend goes through the same `prepare`, so its proof is checked and its credential injected as for the first.
  Only safe methods are resent: a bodyless PUT or DELETE is idempotent by the RFC but changes state, and a server need not honour that promise.
  A request with a body is never resent, since its body has gone upstream and is not held.
  The artifact cache's fetch on a miss, and each redirect it follows, takes a connection from the same pool, so it is resent on the same rule (`Upstream.closedUnanswered`, #1923).
  So is a download's Range request, at once and without counting as a failed resume, since it no longer asks for `Connection: close` (#2094).
  The cache's probes resend more widely, any failure after connecting but a timeout, since each reads its whole small answer before anything reaches the guest.
  The resend takes a connection from the same pool, which may be just as stale when upstream closed several at once; it is still one resend, never more.
  The cache path's own relays get the same watchdog (`relayResponse` and `relayBody`, #1192): an error answered to a fetch or after a redirect, and a download relayed uncached after a cache write error.
  Their clock starts at the client's request, or its last byte, not when the relay begins, since the head wait already spent part of the budget.
  So does every plain relay (#1059): one a cache handler falls back to after lookups of its own, such as the npm tarball whose shasum lookup failed, and an API request that first waited for its caller's proof to be checked.
  A Vertex model call, which the proxy sends itself once the body is in, gets a watchdog from then (#1059): through the token fetch, a 401's retry and the answer, which may be a stream.
  A real stream from the API had never gone more than about a minute between chunks when this was measured, so 110s does not cut any stream that would have lived; what changes is that the cut is logged and upstream is let go.
  A relay cannot resume, so a shasum lookup answered with a `5xx` is asked once more, while over half the client's budget is left, before the tarball falls back to a relay (`fetchNpmVersion`).
  A `429` is not asked again at once, where it would be refused again: when there is no cached copy to serve meanwhile, it is asked again after the wait "Wait out a 429 on a cache miss" describes (#2302), and when that wait does not fit, the tarball is relayed.
  The npm fallback logs that it relays, so a codex install that fails without a stall or resume line in the proxy log points at it directly.
  A relayed request's body is paused as the request is routed, before the proxy waits on anything (#1164), and streamed upstream once the connection is ready (`pipeRequest`, #1175), with backpressure both ways.
  Vert.x drops a body that arrives with nothing reading it, and then refuses to read the request at all, but queues a paused one.
  So a body left unread until the upstream connection was ready was lost whenever it beat the connect -- a small POST that found no idle pooled connection, such as npm's audit request after an install, which sends its tarballs with `Connection: close` -- and the relay waited forever with nothing to cut it.
  An upload is never held whole: buffered, a registry push of a 64 MiB layer exhausted the native proxy's 256 MB heap, failing uploads from every instance, and a Vert.x buffer cannot hold 2 GiB at all.
  There is no upload size limit: the relay stays transparent, and with nothing held there is nothing to protect.
  A relay whose response ends, an error included, before the upload was all read closes the client's connection (`Connection: close` on a response not yet sent); left unread on a keep-alive connection, the upload would stall the next request.
  So upstream's early answer (a `413`, a `401`) reaches the client while it is still sending.
  The connection closes as the Vertex `507` does (#1623): once the rest has been read and dropped, after 5 s with none, or 30 s after the answer.
  Closed at once, with some of the upload unread, it was reset, and a client that writes its whole body before it reads lost the answer.
  The cap keeps the drop bounded, since an upload may be gigabytes.
  Upstream's half-sent request is reset at once, and a client still waiting for `100 Continue` sends no body, so its connection closes at once.
  A client that sends `Expect: 100-continue` (curl above 1 MB) gets upstream's `100`: the relay sends upstream the head at once instead of on the first body byte, which the client withheld until its own timeout, about a second per upload.
  A request with a body is therefore never served from a cache (all of them are keyed by path alone).
  A relay whose client left before its body was all sent resets its upstream request, also when it left while the body was paused, which gives the connection back to the pool instead of holding it for good.
- *Wait out a 429 on a cache miss* (#2302).
  A Maven, Gradle or npm miss that upstream answers `429` is asked again after upstream's `Retry-After`, whole seconds or an HTTP date, or after 2s doubled for each earlier 429 of that fetch when it sent none.
  The wait is never under a second, so a `Retry-After: 0` does not ask again every slot.
  An npm miss asks for the version document first, and a `429` there is waited out the same way when there is no cached copy to serve meanwhile; a background confirmation never waits.
  The client hears nothing meanwhile, so the wait must end 5s before the client's silence budget and 5s before 30s from its request, Gradle's default socket timeout and the shortest read timeout of the clients served (Maven's and npm's are minutes).
  A Gradle client asked to wait longer would otherwise give up on its own and see a read timeout instead of the `429`.
  A wait that does not end in time is not made: the client gets the `429` with upstream's `Retry-After`, which the relay already copies, so a client that retries waits the right time.
  An npm version document is then relayed without its digest as before, and its tarball relayed uncached.
  One schedule per domain spaces the fetches waiting on it 250ms apart, so a cold build's misses do not all ask again the moment the wait ends.
  A new miss does not consult that schedule before its first ask, so it draws its own `429`; the domain's backoff (#2301, "Offline" below) does not hold it back either, since it has no copy to serve.
  It gives up a build that would have failed in a second waiting up to 25s before failing the same way.
  OCI registries, redirects and resumes are unchanged.
- *Offline.*
  Only when upstream cannot be reached (connect/DNS/TLS failure, an exchange that broke on an established connection, or a 5xx or 429, i.e. cases where the client would otherwise get an error) are cached artifacts and stored sidecars served unconfirmed.
  A broken exchange is retried once first, since a pooled keep-alive connection may simply have died while idle; a timeout is not, which would double the wait on a black-holed network.
  An answer we cannot use (an oversized sidecar, a redirect loop, a redirect to anything but http(s)) is a different thing, `SidecarAnswer.UNUSABLE`: upstream answered, so it does not confirm the artifact and the request goes upstream rather than to the cache.
  Redirects are followed over TLS, an `http` Location included, as the proxy always has.
  A failure to connect starts the domain's backoff for 30s (not a connect timeout the proxy cut short to fit a client's budget), so a black-holed network does not cost a connect timeout per request.
  A `429` starts it too (#2301), for as long as its `Retry-After` asks in seconds, at most 60s (the bound isx's own downloads honour, `DownloadCache.retryAfter`), else for 30s.
  Without it, a warm build against a rate-limited Maven Central sent one refused request per artifact whose confirmation had aged out, each counted against a limit already exceeded.
  A `Retry-After` of 0 starts no backoff, since upstream asked to be asked again at once.
  Like any other answer, it ends one already on.
  The backoff short-circuits only requests that have a cached copy to fall back on (a request without one still asks upstream, which it might answer).
  It ends on time, or on any answer from the domain that is not a `429` asking for a wait, to a request sent after it began, and a shorter one never cuts a longer one short.
  An answer to a request sent before the backoff began does not end it, since that request was already in flight and says nothing about the domain now.
  A later failure or `429` that meets a running backoff counts as its start, so an answer to a request sent before it does not end the backoff either.
  Repeated `429`s each start it again at the same length, so a lasting rate limit costs one refused request per backoff.
  It does not grow when `Retry-After` is missing: that one request is how the proxy sees the limit lifted.
  What it gives up: during it, copies past `max-stale` are served unconfirmed without an attempt, where before they were served the same way after a refused one.
  The Plugin Portal keeps the backoff too, so its copies are then served unconfirmed without an attempt.
  A request on a pooled connection is sent without any network I/O first, so getting the connection does not prove the upstream is reachable now, and would let one request end a backoff a real connect failure started.
  It is kept where every upstream connection is made (`requestWithAsyncDns`), for every domain `Upstream.backoffDomains` names (the cached repositories' and the npm registry), so no fetch path can forget to update it.

**Why a repository is declared** (#2312).
With both tiers at zero an online serve does not depend on a repository's publishing rules, so a cached repository is not a claim of immutability.
Each one still says three things the proxy cannot find out about it by itself, built in or in config.yaml's `maven-repositories:` (`MavenRepositories`), one shape for both.
**How long a withdrawal may go unnoticed.**
Offline serves are unconfirmed, and serves within the tiers are not confirmed now, so they are only what a fetch would return while upstream has not withdrawn or replaced the release.
Every repository can withdraw one, so this is a duration, not a property: the entry's `fresh` and `max-stale`, which default to 0 for a declared repository.
A repository that replaces the bytes at a release path (`replaces-releases: true`) is never served on a past confirmation at all, offline and during a backoff included (`Revalidation.servesOnlyConfirmed`), and its sidecars are relayed rather than stored.
**The confirmation must be sound for that repository**: a server-computed header describes exactly the bytes the server would send, so `confirm: checksum-header` (`X-Checksum-SHA1`, which Central and Artifactory send and Nexus 3 does not) is sound even for changing content.
Nexus 3 sends the SHA-1 as its strong, quoted `ETag` instead, which `confirm: etag-sha1` reads the same way (`Revalidation.HEAD_ETAG`, #2336).
Nexus 3 makes that `ETag` the blob's SHA-1 in a hosted repository, but a proxy repository keeps its upstream's (Central's is an MD5), so no Nexus version or format is trusted to send it.
A copy is committed on its `ETag` only when that is the SHA-1 of its bytes, and a weak, unquoted or `{SHA1{...}}` one counts as none.
A download with no such `ETag` is handled as a `checksum-header` one without its header: confirmed by its `.sha1` where a sidecar is sound, and otherwise served but not cached.
A served copy gets that SHA-1 as `X-Checksum-SHA1`, which Maven reads, never as an `ETag` the proxy made up; a miss gets none, since its `ETag` is not yet known to describe its bytes.
A separately uploaded sidecar is not (`mvn deploy` PUTs the jar and its `.sha1` one by one, so a repository can briefly serve a new jar beside the old `.sha1`).
So `confirm: sidecar` only fits repositories that publish the two together and never replace them, and an entry declaring both a sidecar and replaced releases is refused at load; a replacing repository's header-less answer is not confirmed by its sidecar either.
**Whether it is public.**
The cache is shared by every instance and a public repository's confirmation carries no client credentials.
A request carrying its own credential (`Authorization`, `Cookie`, or a repository manager's token header such as `Private-Token` or `X-JFrog-Art-Api`) to a declared public repository is relayed and never cached, so one declared public by mistake does not spread what a credential read to every instance.
A private repository (`access: private`) is served from a cache of its own, per credential, below.
The declarations are the user's claim: a repository that replaces artifacts declared as one that does not serves stale builds from the cache, within its tiers.
The defaults make a repository described too cautiously slow, never wrong.
Only config.yaml declares one: a template, and above all a project-local definition, cannot, since it would decide what every other instance on the host is served.
An entry is dropped with a warning naming why, never failing config.yaml: a field that could loosen a protection is required, a domain isx intercepts for something else (built in, `.internal`, ChatGPT's) is refused, and so is a public entry on a domain a tool injects a credential at, whose cache would take the domain's requests from that injection.
Its domain joins the intercepted ones through `ToolProxyResolver.handlerDomains(SpawnConfig, Collection)`, the seam the proxy and the CLI's bridge DNS writers share, so a reload mints its certificate and writes its DNS override.
**Private repositories** (#2312).
A company's repository manager is where most of the demand is, so `access: private` is served too, without loosening what makes the shared cache safe.
The guest holds a proof, never the repository's credential: a private entry names the namespace whose tool injects at its domain, and the proxy drops it when none does.
Only a request that proved that namespace reaches the cache (`MitmProxy.privateRepositoryCredential`, after `authorize`); any other is relayed as it came, its own credential and all, and never cached.
The cache sees only the one header the relay would have injected (`ArtifactCacheHandler.Credential`), never the request context, so it still cannot choose a credential.
Copies live apart per credential, under `.private/<partition>/` in the Maven cache, the partition an HMAC of the namespace, header and value under an owner-only random key (`PrivatePartitions`), so two accounts never share a copy, a confirmation or a single-flight key, and a hashed weak password cannot be tested against a directory name.
Every hit is confirmed first by a `HEAD` for the artifact itself carrying that credential, and served only on a `200` whose `X-Checksum-SHA1`, or for `etag-sha1` whose `ETag`, matches the copy.
So upstream decides each serve: a revoked account is refused at once, and nothing is served while upstream cannot be asked, offline included.
That is why a private entry must confirm by `checksum-header` or `etag-sha1`: a sidecar's answer shows that the credential may read the sidecar, not the artifact.
A declared `checksum-header` or `etag-sha1` repository that answers an artifact without its header is named once in the proxy log, since its copies are then confirmed by sidecar or, where that is unsound, never cached.
The log names it by its URL, since one host often serves several.
Its sidecars, metadata and SNAPSHOTs go through the ordinary credentialed relay; a cache error answers 502 rather than relaying without the credential.
A redirect from a private repository goes to the client, whose next request is proven and credentialed again; followed by the cache it would go out with no credential.
What it gives up: a private hit always costs one round trip, and a private repository is never available offline.
A token replaced in an account starts a partition of its own, and nothing removes the old one but `isx clean cache`.
A nested isx's `inherit` namespace is not cached here, since the real credential is the parent's to send; the parent's proxy caches it.
**A mirror for a repository** (#2304).
A cold build needs every artifact once, and a repository that rate-limits the host (Central does) would fail it, however the cache confirms what it already holds.
config.yaml's `maven-mirrors:` (`MavenMirrors`) names, for a cached Maven repository, a mirror of it that the proxy asks first for an artifact the cache does not hold (`ArtifactCacheHandler.downloadMissing`).
Sanne's decision on the issue: off by default and host config only, used in place of the repository, the repository still asked when the mirror does not have the artifact, logged but not warned about, and not tied to Central's names.
So the repository is named by its URL, any cached one: Central's two names (each a repository of its own, listed apart), the Plugin Portal, or a public one `maven-repositories:` declares.
The repository is asked when the mirror answers anything but a `200`, cannot be reached or fails mid-request, so a mirror never makes a build worse than none.
Each of the mirror's two requests waits at most half of what the client has left, counted against its silence budget and Gradle's 30 s read timeout alike (`mirrorWaitMillis`), so a mirror that hangs leaves the repository at least a quarter of it.
A mirror that failed, hung or answered `429` or a `5xx` is not asked again for 30 seconds (`mirrorBackoffs`).
The mirror gets bare `GET`s with only `Host`: no header of the client's reaches a third party, a credential included, and a resume repeats those same headers.
Its answers are booked under its own name in `Upstream.throttles`, and a mirrored download is one `info` line in the proxy log.
**Its copy is committed unconfirmed.**
The mirror's `.sha1` is asked first, and a mirror without a usable one is not asked for the artifact.
That checksum goes out with the artifact as the repository's own header where the repository sends one (Central's `X-Checksum-SHA1`), so Maven does not then ask the repository for the `.sha1`, which a throttling Central would refuse.
The download is checked against it, which proves only the transfer, then committed with its confirmation at the epoch, so its first hit is confirmed with the repository itself before it is served again (`mirroredArtifact`).
The copy is keyed under the repository, not the mirror, so that confirmation and every later one go to the repository, which evicts a copy it disagrees with.
During a throttle that confirmation is answered `429` and the copy is served unconfirmed, as any copy is.
**The mirror is asked only when the repository has said nothing about the artifact**: a miss, or a `~/.m2` import whose probe was throttled or failed.
Never after the repository evicted a copy as changed or withdrawn, or the mirror's older copy would come straight back.
What it gives up: the cache-integrity rule above holds from the first hit on, not for the miss itself.
The build that misses uses bytes the mirror's operator vouched for, under the mirror's TLS, and a release the repository withdrew is served again by every new miss while the mirror still holds it (each such copy evicted at its next hit).
That is the trust the user chose by naming the mirror.
A private repository cannot take a mirror, since its credential never goes to another host and its copies are confirmed before every serve; nor does one that replaces releases, for the same second reason; nor the Gradle distributions, which are not laid out as Maven coordinates.
An entry naming no cached repository, or one of these, is dropped with a warning naming why (`CachedRepositoryRules.mirrorRefusal`, which `isx validate` runs too, so it warns about the same entry).
The mirror's host is not intercepted: only the proxy talks to it.
Uncached paths (`maven-metadata.xml`, SNAPSHOTs, a sidecar with no stored copy) still go to the repository alone, which leaves a build that resolves a version range or a plugin prefix exposed to its limit.
**A request right after a download waits for its commit** (#2491).
The client is answered when the last byte arrives, and the copy is committed after that.
A client that asks again at once, as Maven checking a jar it just took does, would find nothing cached and download the artifact a second time, which a mirror or a throttling repository then answers.
So a Maven artifact or sidecar lookup whose commit is under way waits for it (`ArtifactCacheHandler.afterCommit`), on its own connection's context.
The wait is bounded by a mirror's (`mirrorWaitMillis`, half the client's remaining time), and then the lookup goes on as if nothing were pending.
A miss with no commit pending pays nothing, and the npm tarball and container image lookups do not wait.

`maven-metadata.xml` stays relayed because confirming it costs the same round trip as fetching it.
SNAPSHOTs stay relayed: Central and the Plugin Portal reject them, and where a declared repository serves them, sidecar confirmation would be unsound and offline serves of content replaced on every deploy rarely right.

`VerifiedArtifactStore` owns the on-disk half and serializes commit, reconcile and evict per artifact with a striped lock, so a store racing an eviction cannot leave a sidecar beside an artifact it does not describe.
A commit is ordered for crashes as well: the download is flushed to disk before it is renamed into place, sidecars of a previous copy are dropped before the rename, and the new checksum is written after it, so an interrupted commit never leaves a checksum beside bytes it does not describe (an artifact without one is hashed before it is trusted).
A commit never replaces a copy upstream confirmed after the commit's own lookup: under the lock, a cached copy whose stored checksum (npm's `.shasum`) is newer than that lookup stays, and the download, already served to its client, is dropped (#1538).
Since the stored checksum carries the lookup's time, two downloads racing a republish keep the copy from the later lookup whichever finishes last; ordered by finish instead, a slow download from before a republish replaced the republished copy, which a lockfile install then served without a lookup for up to `fresh`.
npm's version lookups are ordered the same way: a renewal stamps its lookup's time and never moves the confirmation back, and an answer from a lookup older than the copy's confirmation neither evicts, renews nor expires it (the foreground serves the copy), so a slow pre-republish answer cannot evict the republished copy to make room for the old bytes.
Checksums are fetched through a Vert.x client of their own (`probeClient`: hostname verification, the proxy's truststore, async DNS, pooled keep-alive connections), npm's version lookups included, never a raw socket.
A separate pool keeps a cache hit's confirmation from queueing behind large downloads.
Since every hit waits on one, that client is tuned for round trips.
It negotiates HTTP/2 through ALPN (Central offers it; others fall back to HTTP/1.1), so a build's concurrent confirmations share a connection rather than a TLS handshake each.
Idle connections stay open for `PROBE_KEEP_ALIVE_SECONDS` (60s), across short pauses between a build's Maven invocations.
Longer buys little (one handshake per longer pause) and widens the window for a connection dropped silently while idle (NAT, suspend, a VPN coming up).
Such a connection is still found.
`Upstream.sendProbe()` closes a connection once nothing has been read on it for 15s while an exchange waits, counted from that exchange's request (#1007).
Being shorter than the exchange's own timeout (30s) it closes the dead connection first, so the exchange fails as broken and gets the one retry above, on a new connection.
It is not the client's read-idle timeout: Netty ticks that from the connection's last read, so a request on a connection pooled idle for longer could be cut by a tick just after it went out, while upstream was answering.
Nor an all-idle timeout, which a request's write restarts: confirmations going out every few seconds would then keep a dead connection open.
Without it an HTTP/2 connection would never be evicted: a timed-out exchange resets only its own stream, and every later confirmation would wait out 30s on the dead connection and be served unconfirmed.
Up to four HTTP/2 connections per host (`PoolOptions.setHttp2MaxSize`): with one, Vert.x admits a single connect at a time, so a burst to a host that falls back to HTTP/1.1 (a nested isx's own proxy, the benchmark stub) ran one request at a time, and a burst to one that does not answer failed one connect timeout after another.
Connections are pooled per resolved address, so the DNS cache (60s) keeps a host's address across a refresh for as long as the answer still lists it, whatever order it comes in (#862).
Central's and Gradle's CDNs answer with two addresses in a random order, which moved about half of all refreshes to the other one and its fresh TLS handshake.
A failed connect drops the cached address (a connect timeout cut short to fit a client's budget does not), so the next request resolves again and takes the first one listed instead of staying on one that stopped answering.
Hashing a large artifact happens outside the lock, re-checked under it against the file's identity, so it does not stall other artifacts in the same stripe.
The benchmark (`bench/run.sh --load=maven`) points Central at a loopback stub through `ISX_BENCH_UPSTREAM`, since a confirmed hit makes a `HEAD` (with the default tiers, the benchmark's warm hits are fresh and make none).
The end-to-end tests use the same hook.
The model-call loads (`--load=anthropic|vertex`) point the Messages API and Vertex at the same stub, and run the proxy on Claude accounts of their own.
`--load=websocket` points `api.openai.com` at the stub's WebSocket server, with an OpenAI account of its own.
A WebSocket dials an overridden host at the stub's address (`MitmProxy.handleWebSocketUpgrade`): Vert.x ignores `setServer` for one, so its SNI names the stub's address, which the stub's certificate carries, while the Host header keeps the real host.
`ISX_BENCH_CONFIG` names the file it reads instead of `config.yaml` (`SpawnConfig.useBenchFile`), so a benchmark never edits the user's file.
The CA, the credential proofs and the rest of the configuration directory stay the user's, so the instance the load comes from proves itself as any other does.
Nothing but `bench/run.sh` sets it, and the proxy refuses to start when it names no file.
Accepted residual risk: an artifact corrupted on disk while its sidecar is intact is served until the client's own checksum check fails.
Caches written before this (`~/.cache/incus-spawn/maven`, `gradle`) were stored unverified; the roots moved to `maven-verified`/`gradle-verified` and the proxy deletes the old ones on start.

**Buffered I/O**: The proxy uses 64KB `BufferedInputStream`/`BufferedOutputStream` on both client and upstream connections for throughput.
SSE and chunked streaming responses are flushed after each line/chunk to avoid buffering delays.

**OAuth token support:** Users with a Claude Pro/Max subscription (no API key) can authenticate via `claude setup-token`, which generates a long-lived (~1 year) OAuth token.
The proxy injects `Authorization: Bearer <token>` into requests to `api.anthropic.com` and strips the container's placeholder `x-api-key` header.
Containers are configured identically to direct API key mode (with `ANTHROPIC_API_KEY=sk-ant-placeholder`).
Unlike Vertex AI tokens, OAuth tokens cannot be refreshed automatically — when a 401 is received, the proxy logs an actionable error directing the user to re-run `isx init`.

**Tool-contributed proxy definitions:** Tools declare proxy entries via a `proxy:` block — either in YAML (`ProxyDef` with `configuration:` and `auth:`) or programmatically via `ToolSetup.proxy()` for CDI tools.
A `ProxyDef` has shared `configuration:` (a map of `ConfigEntry` entries with `config-path`, `value`, `secret`, `type`, and `description`) and a list of `auth:` entries (`AuthDef`), each with a `domains:` list, an optional `paths:` list, and an auth type (`basic`, `bearer`, `header`, or `anthropic`).
When `paths:` is set, the credential is injected only for requests whose path equals a listed prefix or starts with one followed by `/`; query strings are stripped before matching.
Auth fields use `${configKey}` template references resolved against the shared configuration map — literals like `"x-access-token"` are passed through unchanged.
Configuration resolution: `value` (hardcoded literal) > `config-path` (dot-path into `config.yaml`, e.g. `"github.token"`).
User-defined tools use arbitrary config paths (e.g. `"myTool.apiKey"`) stored via `SpawnConfig.setConfigByPath()` and round-tripped through `@JsonAnySetter`/`@JsonAnyGetter`.
Built-in proxy tools are CDI tools declaring `proxy()` directly: `ClaudeSetup` (1 auth entry: `api.anthropic.com`, `type: anthropic`), `GhSetup` (2 auth entries: `github.com` Basic with literal username, `*.github.com`+`*.githubusercontent.com` Bearer), `BobSetup` (1 auth entry for `bob.ibm.com` and regional domains, Header type), `CodexSetup` (1 auth entry: `api.openai.com` Bearer, feature-gated behind `openai`), and `CopilotSetup` (1 auth entry: `*.githubcopilot.com` plus three exact per-tier domains — `api.individual.githubcopilot.com`, `api.business.githubcopilot.com`, `api.enterprise.githubcopilot.com` — declared exactly because each has two labels ahead of `githubcopilot.com`, which a one-label wildcard cert cannot cover; since #783 the proxy mints a cert for any depth on demand, so the exact entries now only make those certs ready at start; its `token` config entry points at `github.token`, reusing the `gh` tool's PAT instead of prompting separately, so it works even when `gh` isn't installed).
`type: anthropic` means the domain is handled by MitmProxy's hardcoded auth logic (three-way routing: Vertex, OAuth, API key) — the generic tool proxy injection path does not apply.
Anthropic entries use relaxed configuration resolution (at least one non-blank credential, matching `ClaudeConfig.hasAuth()` semantics) and are excluded from MitmProxy's domain maps in `buildRouting()`; a change to their credentials is still config drift, since it changes `config.yaml`.
`ToolProxyResolver` (in `common`) handles configuration resolution; `findUnresolved()` returns configuration entries that could not be resolved (excluding `type: anthropic` entries), and `ProxyMain` warns at startup when unresolved entries are found.
`MitmProxy` stores resolved generic entries in per-domain candidate lists for exact domains and wildcard suffixes.
`findToolProxy(routing, domain, requestUri)` selects among candidates: path-scoped entries are tried first, the longest matching prefix winning, then unscoped entries, so two tools can claim the same domain with different paths.
A prefix that is not a literal `/`-rooted path is refused where definitions load, and one without a leading `/` never matches, so an empty prefix cannot widen a scope to every path.
A scope other than `/` never matches a request path an upstream may serve as another path (a dot segment, for one; the full list is in docs/TOOLS.md, #1453).
The proxy refuses rather than normalizes: a normalized match would also have to forward the rewritten target and guess how each upstream decodes.
A refused match falls back to an entry that serves every path (unscoped, or scoped to `/`), which its scope covers anyway, if the request proves that entry's namespace; otherwise it does not inject a credential.
Unscoped collisions between tools are warned at startup (exact-domain conflicts and wildcard-suffix overlaps); tool domains that shadow built-in intercepted domains (registry, Maven, Gradle, npm) are warned during validation since built-in routing takes precedence.
A `proxy:` entry also declares `placeholders:` -- each an `env` variable the tool reads its credential from and the `prefix` its value must start with -- which every start fills with the instance's proof token for the entry's namespace (see design/accounts.md "Proof tokens").
A YAML tool that exports a static placeholder through `env:` declares the same variable here (`typesafe.yaml` does).
A proxied namespace with no usable placeholder declaration warns once per tool and namespace on proxy configuration load and during tool validation.
Its own entries fail closed, so the warning names the affected domains and explains how to declare and export the proof variable.

**Dynamic credential setup:** `isx init` builds its credential menu dynamically from tool setups via `ToolDefLoader.allToolSetups()`.
Tools with proxy configuration are discovered, filtered by `ToolSetup.hasOwnCredentials()` (a tool whose `proxy()` configuration borrows another tool's credential, like `CopilotSetup` reusing `github.token`, overrides this to `false` so it isn't offered as a separately "configurable" entry), and sorted (known tools first: claude, gh, bob, codex; then alphabetically).
A tool behind a `feature:` gate is offered only once `config.yaml` enables its feature, since until then the proxy does not use its credential.
Each entry shows `ToolSetup.description()` and a `[configured]` tag.
Known tools dispatch to specialized setup methods with validation (e.g., `setupClaudeAuth` with env-var detection and API verification).
Unknown tools use a generic prompt (`setupGenericToolCredentials`) that iterates `proxy.getConfiguration()` directly, respects `ConfigEntry.isSecret()` and `ConfigEntry.isConfirm()` (for y/n prompts like license acceptance), and saves via `SpawnConfig.setConfigByPath()` using the entry's `config-path`.
User-defined tool YAMLs with proxy entries appear in the menu automatically.

**Configuration**: `~/.config/incus-spawn/config.yaml` (owner-only permissions, `chmod 600`).
CA key and certificate at `~/.config/incus-spawn/ca.key` and `~/.config/incus-spawn/ca.crt`.
Vertex AI users must have `gcloud` installed on the host and `gcloud auth login` completed — the proxy and `isx init` both shell out to `gcloud auth print-access-token`, which reads the gcloud user credential, not application-default credentials.

### Codex ChatGPT sessions stay on the host (#1332, experimental)

A user who cannot create an OpenAI API key, for example under organization SSO, signs Codex in with ChatGPT.
That session must stay on the host like every other credential, so an `openai` account of `type: chatgpt` holds it there and the proxy turns the guest's look-alikes into it.
This is an **experimental compatibility mechanism**.
It is built from OpenAI's documentation and the open-source Codex client (codex-rs, `rust-v0.162.1`), and verified against a local stub of OpenAI's token endpoint and the Codex backend, with Codex 0.162.1 when it was written and Codex 0.166.0 since.
No Codex version is pinned: CI's `tpl-test-codex` installs whatever `@openai/codex` npm serves when it is built (`CodexSetup`), and the test logs `codex --version`.
So a Codex release that changes what this relies on fails CI's next run rather than waiting for a deliberate upgrade, and a user's own template may run a Codex no run has tried.
The browser sign-in is verified against a real ChatGPT Enterprise workspace's SSO; the device-code path is not yet tested against the real service, since that workspace disables device authentication.
Passing these tests does not establish that OpenAI supports the integration; feedback is welcome on #1332.

**Sign-in:** `isx init`'s OpenAI step (and `isx account add openai`) offers signing in with ChatGPT beside pasting an API key, for a user who cannot mint one.
isx drives the two flows Codex's own login drives (`ChatGptSignIn`, built from codex-rs `login/src`): the browser flow, which the step offers first, and the device-code flow for a host whose browser is elsewhere.
isx runs the sign-in itself, which replaces the #1332 design's plan to run the host's `codex login` into a private `CODEX_HOME`.
What that keeps: no Codex install on the host, no dependency on how Codex stores its session, and a sign-in the tests drive against a stub, in plain Java.
What it costs: isx speaks to OpenAI's auth server under a client id the published pages document for Codex, not for a third program, so a change to the scopes, the callback ports or the endpoints breaks sign-in until an isx release, and OpenAI has not said how it regards this.
It is accepted because the account type is experimental and opt-in, the sign-in runs once per account from the host, and the proxy already depends on Codex's internals for the refresh request and the backend paths.
Delivery and the proxy do not depend on how the session was obtained, so the sign-in can later be replaced -- by `codex login`, or by a documented flow -- without touching the rest.
The browser flow is what an organisation's SSO needs: the sign-in happens in the person's browser, at their IdP, and isx only listens on loopback for the redirect -- so that browser must be on the host -- on Codex's own callback ports (1455, 1457), which OpenAI's auth server allow-lists, with the PKCE pair and the parameters Codex's authorize URL carries.
The device-code flow is the one-time code at `auth.openai.com/codex/device`, entered on any device (the code carries the sign-in, so the browser need not be the host's) and polled until the person entered it; an organisation that disables device authentication refuses it, which is why it is not the default, and it is not yet tested against the real service.
Both end at the same `/oauth/token` the proxy later refreshes at, and every request names Codex's OAuth client id, so the session is one the proxy can answer refreshes for.
The tokens go to the session store alone, never a file in an instance, and `config.yaml` records only `type: chatgpt` for the account.
A sign-in again replaces the session, and its new `session_id` invalidates every proof made for the previous one.
Removing an account leaves its session file behind, owner-only on the host; only an account re-created under the same name would serve it again.
Switching an account to an API key ends its session: the `type` goes with the session file, or the account would read as a ChatGPT one and ignore the pasted key, and the guest's API-key login rewrites the dead proofs out of `auth.json`.
A "replace all" save ends the replaced ChatGPT accounts' sessions the same way, whatever replaces them, and the API-key login touches a delivered session's file only while its own profile script is gone.

**Delivery:** every start that mints an instance secret also decides its ChatGPT session (`ChatGptStart`, called from the secret rotation's locked write).
The instance's pinned `openai` account resolves exactly as the proxy resolves it, and a session is served only when that account is a ChatGPT one and still has its session: anything else -- an API-key account, an `inherit` pin, a pin that no longer resolves, a session gone -- leaves the instance with no binding and clears stale stamps in the same write.
The proofs are keyed with the rotating per-start secret, which is the decision this milestone owed the paragraph above: `InstanceRegistry`'s binding check requires it (`chatgpt-proof-secret-sha256` must be the instance's current `instance-secret-sha256`), a copy gets a secret of its own and so never inherits its source's ChatGPT access, and the stable credential seed's machinery stays untouched.
The same write records the verifiers, and the delivery exec carries what the guest's Codex reads: `/run/isx/chatgpt-auth` holds the placeholders, and a login profile (`isx-zz-codex-chatgpt.sh`, written by the delivery, so old images get it too) writes `~/.codex/auth.json` from it -- Codex's own file shape, with this instance's placeholder tokens, the real account id, and the key field null as Codex's own login leaves it.
The profile runs after the API-key one, so a ChatGPT session's file wins when both could apply, and replaces only a file that is absent, holds a placeholder, or holds an earlier delivery's proofs: a user's own `codex login` is never overwritten.
It also removes the API-key login forcing the build's config.toml carries (`forced_login_method = "api"`, `CodexSetup.configToml`): Codex enforces that restriction by logging the session out and deleting auth.json, so a ChatGPT-serving login takes the line out of isx's own config -- only the line, and only a config holding isx's permissions profile, never a user's own.
A start that serves no session clears both, so the guest's Codex does not keep a session the host ended.
A branch is served its session from the copy request on: the same write that mints its secret derives its verifiers against the selection the branch was asked for (`ChatGptStart.stampBranchInto`), so a copy never carries its source's stamps, and one served no session clears what its source had.
A build does not stamp a ChatGPT session: builds keep their temporary-proof grant, so Codex in a credentialed build stays signed out unless the account is an API-key one.

On Linux the route identifies its caller by source address, then checks the proof against that instance's binding.
On macOS, where every container arrives from the VM's address (#1582), the address names no instance, so the proof is the caller (#2497).
`InstanceRegistry.chatGptCaller` finds the running instance whose current binding holds the verifier of the presented access placeholder on a backend call or WebSocket, of the refresh placeholder on a refresh, and of the token a logout names.
It looks up only verifiers of bindings made with the instance's current secret.
So a previous start's proof, a copy's, another session's or account's, a verifier presented as a token and a proof keyed with the metadata hash each name nobody.
The flow is counted as the proven instance's, once the proof names it, so `instance_activity` shows its Codex as busy.
The route then applies the same kind check as on Linux, so a refresh proof does not pass as an access one.
A logout is answered locally whichever kind it names, as on Linux, where it needs no proof at all, and on macOS it still needs one that names an instance.
A caller at a known instance's address is still held to that instance's own binding, and the Linux proxy never takes this path.
What this gives up is the address check, as for every proof on macOS (#1582): a proof replayed from another instance on the same Mac serves the instance that owns it.
A ChatGPT proof reaches the user's conversations, so it matters more than most, and it is held to the same bar.
It is keyed with a secret the guest cannot read, rotated on every start and checked against a verifier no guest can forge, so an instance holds another's proof only if something hands it over.

**The session:** `~/.config/incus-spawn/openai/<account>/session.json` (owner-only) holds the tokens, a `session_id` minted at sign-in, a `generation`, and `refreshing` while a refresh is out (`ChatGptSessionStore`, on the mechanics `SessionStore` shares with Google sign-ins, #2443).
`config.yaml` only names the account and its type.
OpenAI rotates the refresh token on every use and refuses a reused one, so exactly one party may refresh: the proxy, relaying the guest Codex's own refresh request.

**Proofs (#1107):** Every proof is an HMAC keyed with the raw per-start secret, never with its SHA-256, which the guest can read through `/dev/incus` (`ChatGptProof`).
The refresh proof binds the account and the session id; the access proof is derived from the refresh proof, so a refresh can be answered with the caller's own access proof without the proxy holding the secret.
The host records only SHA-256 verifiers of both, with `<account>:<session id>` and the hash of the secret they were made with, in instance metadata.
The verifiers count only while that is still the instance's secret, so a copy, which gets its own, never inherits its source's ChatGPT access along with its files and metadata; delivery re-derives them on every start ("Delivery" below).
The guest's access token is an unsigned JWT with the real token's expiry and account claims and the access proof as its signature; its refresh token is `rt.isx_<refresh proof>` (`ChatGptPlaceholders`).
The real id token never reaches a guest: it is signed, and can be exchanged for an API key.

**Which requests:** Only a request that claims to be isx's (an isx access placeholder as its bearer, or an isx refresh placeholder in its body) is handled; anything else to `chatgpt.com` or `auth.openai.com` is relayed (`ChatGptRoute`).
Interception is decided per host, not per instance, because bridge DNS is: while any `openai` account is `type: chatgpt`, both hosts are intercepted for every instance, and an instance with no ChatGPT binding only ever gets the relay.
`ChatGptAccounts` decides it for everything that has to agree: the proxy, and the DNS block the CLI rewrites (`ToolProxyResolver.resolvedDomains`).
A host route cannot take these hosts: its name must lie under `.internal`.
The relay is not quite untouched on the two token paths: `POST /oauth/token` and `/oauth/revoke` bodies are read (64 KiB at most, a longer chunked one gets 413) to look for a placeholder, and one that contains `isx_` counts as claimed.
A claimed request is refused here, never relayed, unless each of these holds, all judged on its head: a backend request's body is not read until they do, and then it streams through the same relay as every other credential-injecting domain (#1315), never held whole.
A refusal pauses the request instead of draining its body, and the connection is not reused.
1. Its shape: one `Authorization`, `ChatGPT-Account-Id`, `Host`, `Content-Length` and `Transfer-Encoding` at most, a TLS name that is the `Host` (none at all is refused too), and a target with no percent-encoding, dot or empty segments (400).
   A token request's body is read to find its claim, 64 KiB at most (413 past it); a backend request's is never held.
   Anything a server could read as another path is refused rather than normalised.
2. The allowlist: method and exact path, below; nothing is pre-authorized (403, logged once per operation).
3. The caller: the instance at the source address, served the account its binding names, presenting the proof its verifier matches (401).
   A refusal right after a start may come from a registry listing that predates it, so it gets `CallerCheck`'s one throttled refresh and one more try, as `isx mcp` and host routes do; a WebSocket is judged on the listing at hand.
4. The session: still the one the binding was made for, so a sign-in again invalidates every proof made before it (401).

| Host | Method | Path | What the proxy does |
|---|---|---|---|
| chatgpt.com | POST | `/backend-api/codex/responses` | injects the real bearer and `ChatGPT-Account-Id` |
| chatgpt.com | GET, WebSocket upgrade | `/backend-api/codex/responses` | the same, on the upgrade |
| chatgpt.com | GET | `/backend-api/codex/models`, query `client_version` only | injects |
| chatgpt.com | GET | `/backend-api/wham/accounts/check`, no query | injects; the answer is relayed as it came |
| chatgpt.com | GET | `/backend-api/wham/config/bundle`, no query | injects; the workspace's managed policies, relayed as they came |
| auth.openai.com | POST | `/oauth/token`, Codex's refresh request only | relays with the real refresh token (below) |
| auth.openai.com | POST | `/oauth/revoke` with a placeholder | answers 200 itself: a guest's `codex logout` never ends the host session |

Workspace discovery is on the list by a decision on #1332: before its first turn, Codex 0.162.1 asks `GET /backend-api/wham/accounts/check` where the workspace's requests must go, and without an answer it fails with "workspace routing discovery failed".
Its answer, relayed as it comes, describes every ChatGPT account and workspace the signed-in user belongs to, not only the session's.
For each one it gives the id, plan type, name, profile picture URL, structure (personal or workspace) and routing constraints; it also gives their order and the default (codex-rs `AccountsCheckResponse`).
A 401 to it marks the token refused, as one to any allowed call does: it is Codex's first call, so a token the backend stops accepting before it expires would otherwise never be refreshed.
The guest controls no more of this request than of the others, and the 30-second limit spaces the refreshes a refusing backend causes on any of them.
Those are identifiers, not credentials, and the guest already holds the session's own ones in its id token; but a user in several workspaces shows them all to every instance that holds a proof.
Widening the list further is a deliberate decision on #1332, not a fix.
The config bundle is on the list because an Enterprise plan's Codex will not start without it (codex-rs cloud-config, `plan_requires_managed_config`): the failure is fatal, where its other off-list calls only fail without stopping a turn.
Codex's other calls (plugins, plugin MCP, analytics, user settings, usage) stay refused, and fail without stopping a turn.

**Refresh:** Codex refreshes when its token is within 5 minutes of expiry, or after a 401.
The proxy accepts only the body the tested Codex sends (`client_id`, `grant_type=refresh_token`, `refresh_token`, with Codex's client id), checks the refresh proof, and relays it with the real refresh token only when the real token needs it: within the same 5 minutes, or after the backend refused it.
Codex 0.166 adds an optional `refresh_reason`, a diagnostic from a fixed set of four values (`proactive_expiry`, `scheduled`, `unauthorized_recovery`, `forced`); the proxy accepts those and relays the value as it came, and refuses any other (`ChatGptRoute.isCodexRefresh`).
Otherwise it answers with the current session's placeholders.
Concurrent refreshes of one session share one upstream call, and at most one goes upstream per 30 seconds: with tokens that stayed inside the window, real Codex otherwise refreshed nearly 6000 times in 5 minutes.
A caller whose token no longer works waits for a refresh already upstream for it, whatever the throttle says; one whose token still works is answered with it, and never shares a failed refresh.
Within that interval a token that has expired, or that the backend has just refused, is answered 503 with a `Retry-After` of the rest of the interval, not handed back as if refreshed, which would only loop Codex between refreshes and 401s with a bearer known to be bad.
Such a caller is handed a generation committed since it read the session only when that generation's token works too, and is answered 401 once the account's session is another one or gone.
A refusal is a 401 to a backend request or to a WebSocket handshake, and is kept per token (the session at one generation), so a late 401 for a token a refresh has already replaced does not mark the current one; a refresh that succeeds clears the session's earlier ones.
A rate limit or server error from OpenAI reaches the guest as one too (429, 502), so Codex retries rather than treating its session as dead.
The 429 keeps OpenAI's `Retry-After` and rate-limit headers ("A throttled answer reaches the client as upstream sent it").
The answer is committed only if the file still holds the session and generation the refresh started from.
A refresh overtaken by another write of the same session is answered from that write.
After a sign-in again it is discarded, its new refresh token revoked, and the request fails with no new-session proof; after removal it is discarded too, and the proxy never creates a session file.

**The window atomic replacement cannot close:** Between OpenAI's answer and the commit's `fsync` the new tokens exist only in that answer.
`refreshing` is written before the request goes out and cleared only when OpenAI answered, or the request provably never left; upstream Codex has the same window.
So a proxy that died between the request and the commit, or an answer lost before it reached the proxy, leaves the record behind, and when the next refresh is then refused the host is told to sign in again because a refresh was interrupted.

**Testing:** `ChatGptRouteTest` covers the proof matrix (missing, static, another instance's, a previous session's, the wrong kind, one keyed with the metadata hash, the verifier itself) on both placeholders and both transports, the allowlist and refused shapes on both transports, the body bound and that no body is read before the proof, redirects (handed back, never followed), the refresh rules, and the sign-in-again and removal races.
`ChatGptSignInTest` drives both sign-in flows against a stub of the auth server, `ChatGptStartTest` the stamps and the guest auth content, and `InstanceSecretRotationTest` the rotation that writes both; `ChatGptAuthFlowTest` drives init's step end to end.
`.github/scripts/test-chatgpt-session.sh` runs real Codex (CI: the npm release current when `tpl-test-codex` is built) in an instance through the real `isx-proxy` against `.github/scripts/chatgpt-stub.py`.
It passes only when Codex completes a turn over WebSocket and one over HTTP (the stub refusing the upgrade), with Codex's own refresh relayed once and committed, and the guest holding no real token.
Nothing in it may leave the machine, and that is checked, not assumed.
The host side runs with a group an nftables table lets reach only the stub and the bridge, and a negative control proves the drop first.
A systemd `IPAddressDeny=` was the first plan; in a nested instance it was accepted and did not block any address.
The guest's own `--proxy-only` rules can be removed by guest root (#1137), so a host table drops everything the bridge would route off the host, whatever address guest root takes, proven with the guest's rules flushed as root.
Every token is stub-minted and searched for afterwards in the guest's files, journal and environment and in the proxy's log.
It leaves the developer's own isx state alone: its proxy runs in a mount namespace with throwaway config and state directories bind-mounted over the real ones (a copy of `config.yaml` and the CA, plus the ChatGPT account and session).
Neither `$HOME` nor `-Duser.home` would do: Java reads its home from the password database, and a native image ignores the property.
The real directories are checked unchanged, and a proxy that was running is started again on exit, an interrupted run included.

### Antigravity CLI's Google sign-in stays on the host (#2443)

Antigravity CLI's personal plans (AI Pro, AI Ultra) pay only for a Google sign-in, and its API key mode is slower and billed to the key (#2443).
The sign-in is an OAuth session, which must stay on the host like every credential, so a `gemini` account of `type: google` holds it there (`GoogleSessionStore`, `~/.config/incus-spawn/gemini/<account>/session.json`) and the proxy turns the guest's look-alikes into it (`GoogleAuthRoute`).
This follows the ChatGPT design above, with three differences, each because of how `agy` and Google behave.
It was built from a probe of agy 1.5.12 against a stub of Google's endpoints, not against Google: the probe recorded the sign-in's code exchange, the refresh, every backend call of a session and where agy keeps its token.

**The sign-in happens in the instance, and isx holds no client credentials.**
agy signs in with a PKCE flow whose browser step ends at `https://antigravity.google/oauth-callback`; without a browser it prints the URL and takes the code the page shows.
The user opens the URL on the host, signs in, pastes the code into agy, and agy exchanges it at `oauth2.googleapis.com/token` with its own client id and client secret.
The proxy relays that exchange as sent for an instance whose `gemini` account is a Google one, keeps Google's answer as the account's session (a sign-in again replaces it), and answers the guest with look-alikes.
An exchange of agy's client id from such an instance in a form isx does not recognise is refused, never relayed: relayed, Google's real tokens would land in the guest, which the account exists to prevent.
For the same reason a token request over 64 KiB is answered 413, never relayed unread: a token request is never that large, and relaying it would be the one request a Google-account instance could pad past every check.
So isx never holds Google's client secret, and the official binary is the OAuth client.
**Alternatives:** a host-side sign-in in `isx init` with agy's client id and secret embedded in isx, as third-party tools do: the cleanest flow, but Google's credentials in isx's source and a token exchange isx makes in agy's name.
Reading agy's token from the host's keyring after a sign-in on the host: a keyring format isx would have to follow, and a host that may have no agy.
**What this gives up:** the one-time authorization code passes through the guest.
It is spent by the proxy's exchange within seconds, and a `--proxy-only` instance can send it nowhere else; in full network mode a hostile process that caught the user's paste could exchange it directly, so sign in from a `--proxy-only` instance when that matters.
On macOS the address names no instance, so the exchange carries nothing that says which instance sent it.
There the proxy keeps a sign-in only for the one instance that proved itself and was told to sign in within the last 15 minutes: agy's first request on the seeded token file is a refresh carrying the instance's proof, refused with "no Google session yet" when the account has none, and that refusal is what opens the window.
An exchange no such instance asked for, or that two asked for at once, is refused, never relayed: relayed, the real tokens would land in whichever instance sent it, and on macOS an API-key instance's own sign-in and a Google-account instance's look the same.
So on macOS an instance on an API key account cannot sign agy in to Google through the proxy while a Google account is configured, and a Google-account instance that signed out in agy signs in again from a new login shell, whose script seeds the file that makes agy ask first.
**Accepted, and documented rather than closed (Sanne, #2448):** while that instance's window is open, the proxy cannot tell its exchange from another instance's on the Mac, so a sign-in another instance completes in those minutes is kept as the account's session.
It takes the user's own browser sign-in to complete, so what can happen is that the session becomes the identity the user signed in as from another box, never that a guest obtains a token; the user pages say to sign in from one instance at a time.

**The look-alikes carry the instance's `gemini` proof, not a session-bound proof.**
The access placeholder is `ya29.isx_<digest>` and the refresh placeholder `1//isx_<digest>`, with the digest the instance's `GEMINI_API_KEY` already carries (`GooglePlaceholders`, `ProofToken.derive`).
So the session belongs to the account as an API key does: a copy or a reset gets new look-alikes by the mechanism every proof uses, and a sign-in again does not cut earlier proofs off, since the host decides what the account's session is.
agy keeps its token in the OS keyring, and without one, as no instance has, in `~/.gemini/antigravity-cli/antigravity-oauth-token`; the login script `/etc/profile.d/isx-zz-antigravity.sh` derives that file from the variable (the #1108 pattern).
A missing file is seeded with the look-alikes and an expiry in the past, in a home agy is configured in (its directory exists; agy's `/logout` removes the file alone), so agy's first request is a refresh: answered from the session, or refused with a message when the account has not signed in, which makes agy show its sign-in.
The proxy holds no credential seed, so at the exchange it cannot mint the instance's proof: it mints a digest for that run, honoured from memory for a day and bound to the instance that signed in (at an address the registry names, that instance on that account; on macOS, that instance still running), and the next login's script replaces it with the proof.
A proxy restart forgets it; agy's refresh is then refused and the next login shell repairs the file.

**Refreshes ride the guest's own request.**
Google's token endpoint wants the client secret on a refresh too, so the proxy sends agy's request with the real refresh token in place of the placeholder, when the real token is within five minutes of expiry or the backend refused it, else it answers from the session.
Google does not rotate the refresh token, so a lost answer costs nothing; the single-flight and 30 s throttle are kept as for ChatGPT.
The look-alikes' `expires_in` is the real token's, so agy refreshes when the host needs to.
The id token the guest gets is unsigned and holds the email alone, which agy shows in its header and checks no signature on.
A guest's `/logout` deletes its file and sends nothing; a revocation naming a placeholder is answered locally, so the host session never ends from a guest.

**The host is the boundary at the backend, not a method list.**
A proven request to `daily-cloudcode-pa.googleapis.com` or `cloudcode-pa.googleapis.com` is relayed with the session's token when its path is `/v1internal:<method>` or `/v1internal/<resource>`, with one `Authorization`, a `Host` that is the TLS name and a target no server decodes into another path.
Only a 401 from those two hosts marks the session's token refused; userinfo judges nothing.
agy calls dozens of `/v1internal` methods in one session (`loadCodeAssist`, `fetchAvailableModels`, `retrieveUserQuotaSummary`, `listExperiments`, `cascadeNuxes`, `streamGenerateContent` and more) and a release adds some, and every one reaches the user's own Antigravity data.
The session's token has the `cloud-platform` scope, which reaches far more than Code Assist, so what matters is that the proxy sends it to these hosts and `GET /oauth2/v2/userinfo` on `www.googleapis.com` and nowhere else.
**Alternative rejected:** ChatGPT's exact method list, which would break agy at each release for no gain here.
Everything else to the four hosts is relayed untouched; interception is per host while any Google account exists (`GoogleAccounts.enabledBy`, shared with the CLI's DNS writers through `ToolProxyResolver.handlerDomains`).

**The build derives the mode from the account.**
For a Google account the build leaves `modelProvider` out of agy's settings, since agy exits on `modelProvider: gemini` with no key, and installs the login script; for an API key account it writes `modelProvider: gemini` and removes both.
That is a baked identity (`google` or `api-key`, `AntigravitySetup.accountMode`), re-derived on a built instance when its account moves across modes, since agy reads both files when it starts.
`GeminiSetup` declares it (`bakedAccountIdentity`, `rebakeForAccount`), because account selection asks a namespace's owner and agy, which declares no proxy entry, is never asked.
Its re-derivation does nothing in an image without agy, so a Gemini CLI image stamped with the mode costs one check on a move and nothing else.
Declared on `AntigravitySetup` itself at first, it was never asked: a build stamped nothing and `isx account set` left agy in its old mode (`AntigravityAccountModeTest`, whose first test fails for any tool that can re-derive but is not what its namespace finds).
**What this gives up:** templates and instances built before that have no `gemini` stamp, so they keep the mode they were built with until they are rebuilt (docs/dev/MIGRATIONS.md).
A `type: google` account holds no key, so `GeminiSetup.ACCOUNT_SHAPE` judges it usable without one and `AccountShape.credentialOutsideConfig` keeps `CredentialCheck` and the proxy's startup report from calling it incomplete.
`isx init` offers it beside the key (`ToolSetup.hostedSignIn`), and `isx account list` describes it by its session's email, never its tokens.
Gemini CLI, Pi and opencode read the API key alone, so an instance on a Google account gives them nothing to spend.

**A key request on a Google account is answered, never sent.**
A proven request to `generativelanguage.googleapis.com` from an instance whose account is a Google one has no key to carry, so without this it would leave with the guest's proof and come back from Google as an invalid key.
The proxy answers it with a 403 in the model APIs' error shape (`error.message`, Google's `code` and `status`), whose text says which account the instance uses and what fixes it (`AccountSelection.refuseSignInAccount`, worded by `ToolSetup.hostedSignInRefusal`).
It is decided by the account, never by the tool that sent it, so it holds for Gemini CLI, Pi and opencode as for agy still set up for a key.
A quota refusal on a real key, such as an instance pinned to the wrong key account, is Google's own answer and is relayed as it is: nothing in it says the account was the wrong one.

**Testing:** `GoogleAuthRouteTest` runs the real proxy against a stub of the four hosts with the requests the probe recorded: the exchange that becomes a session and the placeholders it answers, a key account's and a stranger's exchange relayed untouched, an oversized one refused, the macOS window and its refusals, the proof matrix on the backend, refused paths and shapes, refreshes answered and relayed, Google's refusals, the local revocation, and a minted digest forgotten by a restart.
`AntigravitySetupTest` runs the login script in a shell against each state of the token file.
`AntigravityAccountModeTest` goes through the lookups a build, a branch and `isx account set` use, and `CredentialProofProxyTest` holds the refusal of a key request on a Google account.
Nothing here was tried against Google or a real Antigravity plan, and the integration is experimental until it is.

## Credential Isolation

Real API keys and tokens never enter containers, regardless of network mode.
Containers hold only placeholder values that satisfy tools' local auth checks; the proxy replaces them with real credentials before requests reach upstream servers.
A started instance's placeholders are proof tokens (`gho_isx_<digest>`, see design/accounts.md "Proof tokens" under the per-start secret): the table shows the static value a build bakes, which a start replaces.

| Credential | Container has | How it works |
|-----------|--------------|--------------|
| Claude API key (direct mode) | Placeholder `sk-ant-placeholder` | Proxy replaces `x-api-key` header with real key |
| Claude OAuth token (Pro/Max) | Placeholder `sk-ant-placeholder` | Proxy strips `x-api-key` and injects `Authorization: Bearer <oauth-token>`. Container configuration is identical to direct API key mode |
| GCP credentials (Vertex mode) | Placeholder `ya29.placeholder-for-proxy` in `ISX_VERTEX_ACCESS_TOKEN` | Container runs Claude Code in Vertex mode with `CLAUDE_CODE_SKIP_VERTEX_AUTH=1`, which sends only `ANTHROPIC_CUSTOM_HEADERS`; a login script puts `Authorization: Bearer $ISX_VERTEX_ACCESS_TOKEN` there. Proxy injects GCP Bearer token from `gcloud` on the host. No GCP credentials, service accounts, or access tokens enter the container |
| Pi Anthropic key | Placeholder `sk-ant-placeholder` in `ANTHROPIC_API_KEY` | Same as Claude direct/OAuth mode. Pi always uses standard API format; the proxy handles key injection, OAuth Bearer injection, or Vertex translation transparently |
| opencode Anthropic key | Placeholder `sk-ant-placeholder` in `OPENCODE_ANTHROPIC_API_KEY` (claude namespace), named by `provider.anthropic.options.apiKey` in `~/.config/opencode/opencode.json` | Same as Pi's Anthropic key. Its own variable, so Claude Code's `ANTHROPIC_API_KEY` auth is left alone |
| Pi Gemini on Vertex | Placeholder `AIzaplaceholder` in `GOOGLE_CLOUD_API_KEY` (claude namespace) | Pi's `google-vertex` provider sends Vertex's express form to `aiplatform.googleapis.com`; the proxy moves it into the Vertex account's project and region and injects the host's gcloud Bearer token. Only Gemini model calls are served, with only the body fields Pi sends |
| OpenAI API key | Placeholder `sk-placeholder` in `OPENAI_API_KEY` (and, for `codex`, copied into `~/.codex/auth.json` at login) | Proxy replaces `Authorization: Bearer` header with real key for `api.openai.com`. Used by `codex` and by `pi` with `provider: openai` |
| GitHub token | Placeholder `gho_placeholder` in `GH_TOKEN` | Proxy replaces `Authorization` header with real token for GitHub domains (Basic auth for `github.com` git HTTP, Bearer for API) |
| Google sign-in (Antigravity CLI) | `ya29.isx_<digest>` and `1//isx_<digest>` in `~/.gemini/antigravity-cli/antigravity-oauth-token`, written by a login script from `GEMINI_API_KEY` | The proxy relays a proven request to the Code Assist hosts and userinfo with the session's access token, and a refresh with its refresh token; the session comes from a sign-in agy made through the proxy (#2443) |

**A placeholder is read when the tool runs, never fixed at build time (#1108).**
The placeholders become stable per-instance proofs derived from the credential seed, which the proxy checks before it injects anything (#1107, #1100).
A value a build copied into a file would be the wrong one after the next start, and lose the credential.
So every consumer takes the variable its login exports, and where a tool insists on reading a file, a login script rewrites that file from the variable.
What each tool reads, checked against its source or binary:

| Tool | Reads | What isx does |
|------|-------|---------------|
| `gh`, and git through `gh auth git-credential` | `GH_TOKEN` (before `hosts.yml`; the helper asks on every call, and gh writes no env token to disk) | Nothing more. `GhSetup`'s identity lookups, run from the host, take `GH_TOKEN` as the instance's `/etc/profile` exports it (`GhSetup.LOGIN_TOKEN`), with the build's placeholder only as a fallback |
| Claude Code, OAuth | `CLAUDE_CODE_OAUTH_TOKEN` | Nothing more |
| Claude Code, API key | `ANTHROPIC_API_KEY`, but an interactive session first asks whether to use a key whose last 20 characters are not in `customApiKeyResponses.approved` of `~/.claude.json`, defaulting to no | `/etc/profile.d/isx-zz-claude-auth.sh` approves the key of this start at login |
| Claude Code, Vertex | Under `CLAUDE_CODE_SKIP_VERTEX_AUTH` no Authorization header of its own and no `gcloud` call, only `ANTHROPIC_CUSTOM_HEADERS` | The same login script adds an Authorization line from `ISX_VERTEX_ACCESS_TOKEN` to `ANTHROPIC_CUSTOM_HEADERS`, after any headers already there and in place of an Authorization line already there; the `gcloud` stub prints that variable when called |
| Codex | `~/.codex/auth.json` only: `OPENAI_API_KEY` is never sent, `CODEX_API_KEY` is `codex exec`-only, and the built-in `openai` provider cannot be pointed at a variable | `/etc/profile.d/isx-zz-codex-auth.sh` rewrites `auth.json` from `OPENAI_API_KEY` when it still holds a key isx put there |
| Copilot CLI | `COPILOT_GITHUB_TOKEN` (before any stored login, of which there is none) | Nothing more |
| Pi | `ANTHROPIC_OAUTH_TOKEN` / `ANTHROPIC_API_KEY` / `OPENAI_API_KEY`, after a stored `~/.pi/agent/auth.json` that isx never writes | Nothing more |
| opencode | `OPENCODE_ANTHROPIC_API_KEY` through its settings' `{env:...}` reference, or `OPENAI_API_KEY` / `GEMINI_API_KEY` for its `openai` and `google` providers | Nothing more |
| Bob | `BOBSHELL_API_KEY`, copied into `BOB_API_KEY`; nothing stored | Nothing more |
| Antigravity CLI, Google sign-in | `~/.gemini/antigravity-cli/antigravity-oauth-token`, its file store when there is no keyring (probed: no instance has one) | `/etc/profile.d/isx-zz-antigravity.sh` seeds the file with look-alikes carrying the proof in `GEMINI_API_KEY`, and brings look-alikes of another digest to it; a real token is left alone |
| YAML tools with a `proxy:` entry | Whatever the author wrote | The loader warns when a tool's `files:` or build steps hold the value of one of its own variables named as a credential, if that value is no path, URL or phrase (`ToolDefValidator.embeddedTokens`): read the variable when the tool runs |

The two login scripts are named `isx-zz-*` so they sort after every other `isx-*.sh` in `/etc/profile.d`, and see the values a start exports rather than the build's.
Both are POSIX sh, silent, and do nothing but a grep when nothing changed.
Neither may break the file it edits.
Claude Code's approval is added by replacing the last line of a file shaped as Claude Code writes it (`{` first, `}` last) with the field and the closing brace -- `JSON.parse` keeps the last of a repeated key and Claude Code rewrites the file with one -- and anything else is left alone, which costs a prompt, never a config.
isx's own readers of the file, the reconfigure merge and the repo pre-trust at build, drop that last line before their strict parse (`ClaudeSetup.parseClaudeJson`).
Both run only at build, where that line can only approve the build's own temporary proof, revoked when the build ends; a branch gets proofs from a new seed, which its first login approves.
Codex's file is replaced only while it still holds `sk-placeholder` or an `isx_` proof in `apikey` mode, so a `codex login` of the user's own survives.
A child build rewrites both scripts and syncs the `gcloud` stub for a Claude or Codex it inherits without setting up again (`ToolSetup.refreshInherited`), so a parent built before #1108 does not pass on files that hold or lack the wrong thing.
Instances branched from such a parent get them only once it is rebuilt.
A tool in an instance reads what its *login* environment holds: a process started some other way gets the build's placeholder, which the proxy will refuse once it checks.

The MITM TLS proxy provides credential isolation:
1. Bridge-level dnsmasq overrides (configured by `isx proxy`) route intercepted domains to the gateway IP
2. A custom CA certificate (installed in template images) lets containers trust the proxy's TLS certs
3. The proxy terminates TLS, replaces placeholder auth with real credentials, and forwards to real upstream over TLS
4. Placeholder values cannot authenticate against any service — they only bypass local tool checks
5. In proxy-only mode, an Incus network ACL on the instance's NIC, enforced on the host, blocks all egress except the proxy (443), its health check and DNS on the gateway

## The proxy holds no configuration of its own
Every input the proxy acts on must survive the proxy (#935).
Its routing and credentials are always derived, never held: the truth is the two stores it already reads, host configuration (`config.yaml`, tool definitions, the CA) for what applies to every instance and for every secret value, and the `user.incus-spawn.*` stamps of each instance for what applies to that one (its address, account pins, the `mcp.caller` grant).
Host configuration is re-read on the watcher or `SIGHUP` into one immutable `ConfigState`, and the stamps on `SIGUSR1`, on a TTL and on an address miss into the `InstanceRegistry` snapshot, which reads them from the one listing it already makes.
So a restart -- which launchd's `KeepAlive` does often on macOS, and `isx` on drift -- or a reload rebuilds the same routing from the same truth, and a guest never loses a route because a process restarted; a reload that fails keeps the old state and fingerprint, so `/health` reports drift.
The activity counters are the only state a restart resets, because they are observations, and each says since when it counts.
The event log (below) records the same observations in a file, so they outlive a restart.

Rejected: routes set by admin operations and held in memory (lost on every restart, and a reload's result would be ambiguous), or persisted by the proxy to a state file of its own (a third store duplicating instance lifetimes: an instance destroyed or copied while the proxy is down leaves it wrong).
Secrets never go in stamps: a guest reads its own `user.*` keys through `/dev/incus`, so a stamp names a declared config entry, never a value (as `InstanceSecret` keeps only a hash).

The rules for what comes next, agreed with the decision on #935:
- A new capability (per-instance routes, header grants, leases) is data in one of those two stores, with its copy rule stated: a stamp is no-inherit, stripped by `configureBranch` on every copy in the same change, as `mcp.caller` is.
- An operation that changes them is done by `isx` (the CLI and `isx mcp` tools): validate, write the store, signal the proxy, as `isx account set` does.
  The proxy may itself write to Incus later, but only `PATCH`ing `user.incus-spawn.*` stamps of instances the registry lists, never devices, profiles or lifecycle, through a narrow wrapper around `IncusClient`, and only from a host-side admin surface that no bridge-facing handler (MITM routing, the WebSocket relay, `McpBridge`) can reach, pinned by a static test as `McpNoWritePathTest` pins the MCP package.
- That surface is authenticated, not address-gated: the source address (`isHostCaller`) is enough for reads such as `/activity`, but a write needs a bearer secret in an owner-only file in the state directory, on a loopback listener or a JDK domain socket (Vert.x needs its native transport for domain-socket listeners, which the proxy has on Linux only).
- Every grant expires: the proxy enforces the expiry on read, revoking is the fast path and expiry the safety net for a coordinator that dies, and a grant without one is refused.
Until a resource to lease exists, only the read side ships; the health server stays read-only (`MitmProxy.healthRoute`).

## Upstream rate limits are counted per domain (#2303)
A registry that answers 429 to the host's address makes a build inside an instance slow or failing, behind isx's proxy, so the user reasonably suspects isx.
`Throttles` (`Upstream.throttles`) counts, per domain, the 429s upstream answered to any request the proxy made, the cached copies served unconfirmed because of one (after the 429 itself, or without asking while the domain still refuses, as in the backoff a 429 starts), when the first and last came, and whether the domain's latest answer was one.
It sees upstream's answers to a relayed response (a buffered one and a Vertex call included), a refused WebSocket upgrade, a cache miss's fetch and each redirect it follows, a probe (`sendProbe`, which covers sidecars, HEADs and the npm version lookup) and a resume.
The proxy's own requests are counted too, such as ChatGPT's token refresh and revoke.
A redirect target's answer is booked under the target's name, and the redirect itself is the redirecting domain's answer, so a CDN's limit is not misnamed as the registry's.
This includes GitHub, the model APIs and the container registries, since a count costs nothing and passing their 429s on unchanged is #2309's.
A domain that has not answered 429 is not listed, and at most 128 domains are held, so a redirect to many hosts cannot grow the map.
The proxy logs a transition, not a request: one warning when a domain starts refusing and one line when it answers again.
A 5xx does not end a refusal, since it says nothing of the limit.

The counters are the host's, since a limit is on the host's address, and they are lost on a restart, like the other activity counters (above), with no state of the proxy's own.
`/activity` serves them as `throttled`.
`isx proxy activity` prints a line per domain under its table, with how long ago its last 429 came, and `upstream_refusals`, `served_unconfirmed` and `throttled_domains` on the unattributed record.
`isx doctor` warns for 30 minutes after a domain's last 429, whether or not it has answered since, since a domain nothing asked again still reads as refusing.
Its warning names the limit as upstream's, on this host's address for a registry and possibly the account's for a model API or GitHub, and the cached artifacts as still served.
`isx doctor --explain` reads the same finding.
The relayed 429 itself is not changed: a client that wants to know why still sees only upstream's answer.
A response header naming upstream as the source is left to a later change, since a header added to a relayed answer touches what a client may rely on.

## The event log (#2241)
The proxy appends one JSON line to `~/.local/state/incus-spawn/events/events.jsonl` for each model call that ends, so what an instance did outlives a restart and the in-memory counts of `ApiActivity`.
A line holds the time, the instance's name and incarnation, the domain, the model the response names, the HTTP status, the duration and the four token counts.
It holds no request or response content.
A GitHub operation an instance makes is a line too: a push (`op: push`, the repository, the branches its commands update in `refs` and delete in `deleted_refs`) or a pull request opened (`op: pull_request`, the repository and the number).
`isx overview` and the TUI's overview (`o`) read it back through `EventLogReader` in `common` (design/terminal.md "The overview of what each instance did").
The log is an observation and never read back by the proxy to decide routing, so "The proxy holds no configuration of its own" stands.
Writing costs the request path one `offer` onto a bounded queue; one platform thread does the file work, so a slow disk never delays a response.
When the queue is full or the write fails a line is lost, and the next line written says how many were (`kind: dropped`).
The log gives up completeness under load for a request path that never waits on it.
Retention is by size alone: the file rolls over at 16 MiB and four older files are kept (`events.jsonl.1` to `.4`), about 80 MiB at most, with no timer and no state beyond the file.
Before the oldest file is deleted it is folded into `events.jsonl.sums` (`EventLogFold`, #2429), on the writer thread, so lines queued during a fold wait for it.
A `model` line becomes a sum by UTC day, instance, incarnation, task run, model and exact status (so a day's 429s and 529s stay countable in the file, though the overview reads only calls and tokens), holding the calls, the four token counts, the duration and the first and last time.
A `dropped` line becomes a count per day, and every other line, such as a push, a pull request or a later isx's instance and task lines, is kept as written, since they are few and tie an instance to its work.
The sums hold ids and never labels, so purposes and names are joined at read time.
The fold writes a temporary file and renames it over the sums before the old file is deleted, and one it cannot write leaves the old file for the next rollover.
A `folded` marker holds each folded file's SHA-256, so a file that survived a crash between rename and delete is not summed twice.
`EventLogReader` reads the sums first, so a reader started in that window counts the file twice until the next rollover.
Rows older than 400 days are dropped from the sums, which otherwise grow by days times instances.
Both the directory and the files are owner-only.
The first call of each instance incarnation is preceded by one `kind: instance` line (#2427): the time, `instance`, `incarnation`, `template`, `purpose` (the person's, else `isx mcp`'s) and `session` (the `isx mcp` session holding it), each empty when the instance has none.
The proxy writes it from the registry's listing, so an instance made by `isx branch` has one too, and only when the listing's view of the name is the incarnation that called, so a name branched again never takes the old one's description.
A purpose changed later (`isx purpose`) writes another line for the same incarnation at the next call, and the reader keeps the last (`EventLogReader.Log.described`).
The line gives the log, kept after the instance is destroyed, what each incarnation was for.
It is not an event of the instance, so it moves neither its first nor its last time.
Labels on `create_instance` and `delegate` (#2427's optional part) are not written yet.
`isx mcp` appends a `kind: task` line to the same file when a task's run starts and when it sees one end (`op: start` or `end`, #2428): `instance`, `incarnation`, `task_id`, `run`, `task_kind`, the client's `activity`, and on an end the `state` and, when known, `exit_code`.
It appends in one write to the file opened for append, so its line and the proxy's are never interleaved, and it never asks the proxy, whose health server only reads.
`EventLogReader` books each model call to the run that encloses it (`Log.runs`); design/mcp.md "Task runs in the event log, with an activity" has the rules.
A push is `POST github.com/<owner>/<repo>[.git]/git-receive-pack`, and its branches are the commands at the head of the body, before the pack: the proxy keeps at most the first 8 KiB as the body streams past and never holds or pauses it.
A pull request is `POST api.github.com/repos/<owner>/<repo>/pulls` or the `createPullRequest` mutation that `gh pr create` sends to `/graphql`, and its number is read from the first 32 KiB of the answer.
Reading an answer needs it uncompressed, so those two requests go upstream with `Accept-Encoding: identity`, as a model call does.
A GitHub line is written only when GitHub answered with success, so a refused or failed call costs the log nothing.
It gives up two things.
A push's success is the HTTP answer's: a ref GitHub rejected comes back inside a 200's report-status, which is not read, so the line says a push was accepted, not that every branch moved.
A body with a `Content-Encoding` is not read, so such a push or GraphQL call has no line.
The guest writes these requests itself, so it can add successful lines at the rate it can push, and a flood of them rolls older model calls out of the 80 MiB.
Repository and branch names are the guest's text, cut at 255 characters and JSON-escaped.
The incarnation is the listing's `created_at`, as for the counters (#1063), so a name branched again is a different instance in the log.
Its cost on the request path is one queue offer and a JSON build at the call's end, which runs on the event loop and costs microseconds beside a model call that lasts seconds; it is not measured on a real Incus.
A GitHub call costs more: each chunk of a push's body or a tapped answer copies up to 8 or 32 KiB on the event loop, every `/graphql` POST allocates about 40 KB of buffers whether or not it opens a pull request, and the end runs a regex over the head of the answer.
This too is not measured on a real Incus.

## The socket transport: epoll on Linux
On Linux the proxy runs Vert.x on Netty's native epoll transport, not Java NIO (#475).
On the cached Maven path it serves 3 to 15% more requests per second, at a cost of 2.5% in binary size (docs/dev/PERFORMANCE-NOTES.md "Native socket transport (issue #475)").
An OS- and arch-activated profile in `proxy/pom.xml` adds `netty-transport-native-epoll` for the build's architecture and sets `quarkus.vertx.native-transport=if-available`.
A build without one, such as macOS's, keeps the native transport disabled, so Quarkus does not warn at every start that no native transport was found.
Its JAR carries the JNI library for one architecture only.
The uber-jar that JBang installs is built once, on Linux x86_64, and runs on every platform, so its build (`-Prelease`) keeps the native transport disabled and runs on NIO.
With it on, every start on macOS or Linux aarch64 would warn that epoll did not load.
At startup Netty unpacks the library into `java.io.tmpdir` and loads it.
Where that fails, on a `/tmp` mounted without exec rights for example, the proxy runs on NIO instead of refusing to start.
The proxy log's startup line names the transport in use, or the reason the native one did not load.

What differs on epoll is the exceptions a socket error raises.
A port still held fails a bind with Netty's `NativeIoException` carrying `EADDRINUSE`, not a `BindException`, so `MitmProxy.isBindException` matches both.
Without that, a restart whose old proxy still held its ports would fail at once instead of waiting for it.
The bind tests in `MitmTlsTest` and `ForeignHolderBindTest` run on the native transport (`ProxyTransport`), and the other proxy tests run on NIO.
The native integration job fails when its proxy is not on epoll, so its whole suite runs over the transport users have.
The release smoke test fails a Linux proxy binary that does not contain the epoll transport.

Rejected, for now:
- **io_uring**: it measured 46 to 61% more throughput on the cached Maven path, with p99 a third lower.
  It also exposes the host kernel's io_uring code to the process that handles guest traffic.
  Where io_uring is disabled, Quarkus falls back to NIO, not to epoll.
  The current direction is epoll for now, with io_uring not pursued (#475, 2026-11-13).
- **kqueue on macOS**: measured on Apple Silicon at 0.6 to 1.3% more throughput on the cached Maven path, consistent across runs, and 0.1 to 0.3% on `saturate` (#2215, 2026-11-13; docs/dev/PERFORMANCE-NOTES.md "kqueue on macOS (issue #2215)").
  In a native image it needs its JNI library listed in the image's resources and JNI metadata of its own.
  Without the metadata, or with metadata a Netty upgrade made stale, startup exits instead of falling back to NIO.
  About 1% does not pay for that risk, so the proxy stays on NIO there.
- **`required` instead of `if-available`**: a host that cannot load the library would then lose its proxy, not just the speedup.

## Host routes
An agent in a box needs to reach whatever coordinates it (ask for a review, file a report) without going through GitHub, and without leaving proxy-only mode (#936).
`host-routes:` in `config.yaml` maps a name an instance reaches on 443 to a controller on this host, `hostname:port` on loopback:

```yaml
host-routes:
  work.isx.internal:
    target: 127.0.0.1:7070
    templates: [tpl-isx-agent]   # required
```

With no entries there are no routes, and an entry with no templates is no route either: its owner names who may call it.
Each name joins the intercepted set, so it gets bridge DNS and a leaf certificate the way a credential domain does, through the one function both the proxy and the CLI writers of the DNS block use (`ToolProxyResolver.hostRoutes`, which `resolvedDomains` includes); otherwise an `isx` command rewriting the block would drop them.
A request to the name passes only when the caller's address and its per-start secret belong to one instance (`InstanceRegistry.identifyWithLineage`, one throttled refresh on a refusal, then 403; on macOS the secret alone, #1583) and the route admits the instance's leaf template; a project-local instance never passes.
Nor does a build that has not succeeded, or anything copied from one: a build container is a copy of its parent and keeps the parent's `profile` and `build-source` stamps, which only a successful build replaces.
So the request that copies or creates a build container already stamps `untrusted-lineage` (`Metadata.UNTRUSTED_LINEAGE`).
Only `BuildCommand.trustBuilt` removes it, after the swap of a build that succeeded and inside the swap's lock, so a branch or `isx mcp` delegate waiting on the rebuild copies the template only once it is trusted.
A build killed, lost with its host, failing after `tagTemplateMetadata`, or promoted half-way stays untrusted, and every copy keeps the stamp.
A template still carrying it counts as outdated, so the next `isx build` of it, or of a template built on it, rebuilds it.
`isx project` builds keep it for good.
One rule says whether a config is untrusted (`Metadata.untrustedReason`): the stamp, or `type: failed-build`.
A branch's copy request stamps its answer for the source, over anything the caller passed, so a branch of an older failed build is covered from now on.
The registry asks it of the instance and of each ancestor its `parent` names that the listing still has, so a branch of a branch, or of a renamed failed build, made before the stamp existed is caught too, and the refusal is logged with the reason.
It also refuses an instance whose `parent`, or an ancestor's, ends in `-failed-build` (`Metadata.untrustedByName`), for a branch whose failed build has since been deleted.
That name rule is never stamped on a copy by name alone, so a template a user named so on purpose costs the routes of everything built or branched from it only while it is named so, and the refusal says to rename it.
A branch of a source with such a parent is stamped only when that parent is a failed build or is gone, which the copy request then asks (the one extra request, made only for such a name).
Both walks go by name, never by the incarnation a copy came from, so a name reused since decides.
That can refuse a trusted branch, or, for a branch made before the stamp existed whose failed ancestor is gone and whose name now belongs to a trusted instance, admit it; a branch made since from an untrusted source carries the stamp itself and is never admitted that way.
The proxy then forwards it over plain HTTP to the target, with every client-sent `X-Isx-*` header removed, the secret included, and `X-Isx-Instance` and `X-Isx-Template` set, and streams the response back.
So every identity header that reaches the controller through the route was written by the proxy after that check, and the controller never holds the instance's secret.
The loopback hop is plain HTTP, though, which any process on the host can also send, so the proxy authenticates itself to the controller with a second, per-route secret (`HostRouteSecret`, `X-Isx-Route-Secret`), and the controller trusts the identity headers only once it matches in constant time.
It lives in `~/.config/incus-spawn/host-routes/<name>.secret`, which the controller reads too.
The proxy creates it the first time it serves the route: owner-only from the start, written to a temporary file and hard-linked into place, which never replaces one another proxy just made.
It is read without following a link, from a directory nobody else can write to, so it cannot be swapped for another file.
It never reaches a guest:
- no instance is given it, and a client copy is stripped with every `X-Isx-*` header;
- it is removed from the response's headers, by name and wherever its value appears, and from a reason phrase that quotes it, for a controller that echoes its request;
- it is never logged, and `Served.toString()` redacts it.

A route whose file is not usable is still claimed, and every request to it gets 503.
Unusable means: a link or anything but a regular file (judged before it is opened, since opening a FIFO would block startup or a reload for good), a file or directory not owned by the proxy's uid, a file readable by anyone else, `host-routes/` writable by anyone else (group included: the proxy makes it 0700 itself), the config directory writable by others or by a shared group (group write is accepted there only when its gid equals the proxy's uid, a user-private group as `useradd` makes it, since a umask of 002 gives a user's directories that; never by comparing with a file made inside it, which takes a setgid directory's group, and every file on macOS), a config directory that is a link (only the real directory is checked), or content not 32 to 256 header-safe characters.
The uid is that of a file the proxy has just created, once per process: never its user name, which may not resolve, and no directory-service lookup on the reload path.
isx makes the config directory itself, and every directory it makes in it or on the way to it, without group or other write, whatever the umask (#2136).
`OwnDirectory.create` does it, called from `isx init` before any step writes there and from every writer that may make the directory first.
So a user whose primary group's gid is not their uid, as on Ubuntu's cloud images, is not refused for a umask of 002.
An existing directory is left as it is: group write it already has may have been used, so it is checked, never repaired.
`isx init` warns when the check would refuse it, judged by the uid it runs as, as the build and the proxy judge it, and the refusal says `chmod go-w`.
Outside these checks, and so able to weaken them: the directories above `~/.config/incus-spawn`, which are not checked, and POSIX ACLs on any of them.
An ACL entry granting another user write shows only as the group bits of the mode (its mask), so a config directory accepted for its user-private group can carry one.
Java cannot read POSIX ACLs; refusing every group write on the config directory (`chmod g-w`, one command for a umask of 002) would close this at the cost of that step.
That is failing closed without dropping the name: the CLI's bridge DNS still lists it, and a name the proxy no longer claimed would be relayed out as ordinary traffic.
The watcher reloads on any change to a secret file, so deleting one rotates it at once, and rewriting a refused one serves the route again, on every platform.
A `chmod` or `chown` of a secret or of `host-routes/` reloads at once on Linux, where inotify reports attribute changes; macOS's polling watcher compares modification times only, so there it takes effect at the next reload (`isx proxy restart`).
The controller must still listen on loopback alone.
One bound to `0.0.0.0` is reached directly at the bridge gateway by any instance that is not proxy-only; the route secret keeps such a request from passing as an instance, but the controller would be answering it at all.

What the route secret cannot cover, and the controller must:
- the response body is relayed as it is, so a controller must never echo the secret in one;
- the hop is plain TCP, so whatever listens on the target port while the controller is down receives the secret, and should be treated as having it: rotate it by deleting the file.

A client header is stripped by the name a controller may read it as: every character but a letter or digit is folded into `-`, as CGI-style servers do (`X_Isx_Instance` and `X.Isx.Instance` are `HTTP_X_ISX_INSTANCE`).
So are the hop-by-hop headers and those the client names in `Connection:`, and the target is sent in origin form, never a client's absolute URI.
No credential is injected on a route.

Each entry is checked separately and dropped with a warning naming it, failing closed:
- the name is a hostname under `.internal` that no other intercepted name equals, contains or lies under, nor another route;
- the target is a loopback literal or `localhost`, taken as 127.0.0.1, never a name to look up, with a port from 1 to 65535;
- `templates:` is required and a non-empty list (decided on #936: there is no route for every instance), and an unknown key drops the entry, so a misspelt filter never widens it; so does a name written twice (in two cases, say).

Three details keep a route to its own name:
- the request is routed only when its SNI and its `Host` are both the route's name, else 421, so a connection opened for a credential domain cannot carry a request for a route, nor the other way round, and this is checked before anything else, `mcp.isx.internal` included;
- dnsmasq resolves names under a route too, and those get 404 instead of a relay;
- a WebSocket is refused in this version: a controller can long-poll or send server-sent events, which stream through the request path.

The entry is keyed by name with the target and the filter as fields, so #935's per-instance grants can later sit beside `templates:` on the same entry rather than in a second route table.

Rejected:
- Templates declaring their routes: a project-local template is untrusted (#765), and a route reaches host state outside the project; the host owner opts in from `config.yaml`, as with the `templates` of `mcp.yaml`.
- Any target address: it would make the proxy a pivot from proxy-only boxes onto the LAN.
- A unix-socket target: Vert.x needs its native transport for one on Linux and has no portable path on macOS; loopback TCP covers the need, and a socket can be added later behind the same entry.
- Forwarding the instance's secret for the controller to check: the controller would then hold credentials, while the proxy already checks address and secret together.
  The route secret is not that: it identifies the proxy to the controller, and no guest ever holds it.
- Leaving the loopback hop unauthenticated, trusting loopback alone: any process on the host could forge an instance's identity (decided on #936).
- A unix socket or mutual TLS for the hop instead of a shared secret: the first has no portable path on macOS, the second is heavy for a controller; a header compare is a few lines in any language.
- A route with no `templates:` admitting every instance that is not project-local: a typo or an omission would open the controller to every box.
- The address alone: #934's secret exists so that a host-side service sees both, and this is that service.

## A cache that confirms every serve (#2518)

A host on a slow connection should download a large file once, whichever instance asks for it next.
The caches above do that only for repositories whose rules isx knows (`Revalidation`, "Why a repository is declared").
GitHub Actions caches are the case that needs more: a self-hosted runner downloads each one from Azure blob storage (`*.blob.core.windows.net`) on every job, about 100 MB for a Maven cache and up to 580 MB for the cloud images.
Those blobs are private, reached by a signed link, and overwritten under the same name.
Sanne's decision, in the design conversation of #2298: a general mode, not one for Azure alone, off by default, opted into per domain in host config, and tested against GitHub's cache service.

**The mode.**
config.yaml's `confirmed-cache:` (`ConfirmedCache`) lists domains, exact names or `*.` wildcards, and a `max-size` (20 GiB by default).
`ConfirmedCacheHandler` takes every bodyless request to them, through the same `cacheHandler` seam as the other caches.
A GET goes upstream as the client sent it, its signed link and any credential included, with `If-None-Match` naming the stored copy's `ETag` and no `Accept-Encoding`.
A `304` serves the stored bytes, whole or the one range asked for (`Range`, or Azure's `x-ms-range`), with the headers stored beside the copy, less `Content-MD5` and `x-ms-content-crc64` for a range, which they do not describe.
Upstream checked the caller before it answered, so a caller without access gets upstream's own `403`, never the copy.
Anything else is relayed as it came, and a whole `200` that may be stored becomes the new copy as it passes (`Upstream.relayResponse` with a tap).
Nothing is ever served while upstream cannot be asked: a failed exchange is a `502`, and these domains keep no backoff.
The handler is built from `Upstream` alone, like `ArtifactCacheHandler`, so it cannot send a credential the client did not send.
What it gives up: every download still costs one round trip, and nothing is served offline.

**What may be stored.**
A `200` with a strong, quoted `ETag`, a `Content-Length` within `max-size`, no `Content-Encoding` and no `Content-Range`, no `Cache-Control: no-store`, and no `Vary` beyond `Accept-Encoding`.
A copy is committed only when every byte arrived.
Each fill's length is reserved before its file exists, on two counts kept across every fill under way: together they stay within `max-size`, and the host keeps the spool's free-space reserve (one `DiskReserve` shared by the fills, `admit` before and `written` or `abandoned` after).
A check of free space alone saw only one fill, since those under way had written nothing yet, so a guest asking one byte of many large blobs could have started a fill for each and written past both limits.
A commit evicts down to `max-size` less what the other fills under way will add.
A fill's file write is paced like the client: while its queue is full, upstream's response is held back (`ResponseHold`), on the relayed download as on a background fill, so a slow disk never queues an object in the heap.
A weak `ETag`, or none, is never cached: a `304` against it would not promise the bytes.
A request that asks a question of its own (`If-None-Match`, `If-Match`, `If-Range` and the like, Azure's `x-ms-if-tags` or a per-range checksum) is relayed untouched, as is a request for several ranges.

**The key.**
The host is lower-cased first, since a `Host` header's case is the client's.
A signed link changes on every request while the object does not, so the key is the host, the path and the query less the parameters that sign the link (`ConfirmedCacheStore.unsigned`), while the check still sends the full signed request.
Those are an Azure shared access signature's (`sv`, `se`, `sig` and the rest) and the `X-Amz-` and `X-Goog-` ones of S3's and Cloud Storage's signed URLs.
Every other parameter stays in the key, sorted: Azure answers `?comp=metadata` on a blob's path with the blob's own `ETag` and another body, so a key without the query would serve one as the other on a `304`.
A domain that signs links with other parameter names only misses, it never serves the wrong bytes.
Whether the key helps depends on the service keeping a blob's path stable across links.
GitHub's toolkit fetches the blob from the signed URL the cache service hands out (`@actions/cache`, `downloadUtils.ts`, read 2026-11), and whether that path repeats from one job to the next is checked by the real run that #2519 makes possible.
Correctness never depends on it: a path that changes only misses.

**Ranges.**
GitHub's toolkit reads a blob with a `HEAD`, then 4 MiB `Range` requests, eight at a time, each with a 30 s timeout.
A ranged request with a copy to check against is confirmed and served from that copy, so a warm restore asks upstream only for `304`s.
A ranged miss is relayed as it is, and one download of the whole object is started beside it, once per object (`fillInBackground`), with that request's own headers and link but no range.
At most four background fills run at once (`MAX_BACKGROUND_FILLS`); a ranged miss beyond them is only relayed, and a later one starts the fill.
What it gives up: the first restore of a blob costs its bytes twice.
The alternative, holding each range until a single fill reached it, would leave later ranges waiting past the toolkit's 30 s timeout on a slow link.
A background fill is given up once it has run two minutes longer than its bytes so far take at 256 KiB/s (`fillDeadlineMillis`, checked every second), so fills that trickle cannot hold those slots and their reservations for hours, however large their objects.
What it gives up: on a link slower than that, a large object is never filled in the background, and its downloads are relayed.
A `HEAD` is relayed.

**Versions never splice.**
Each object's directory holds one immutable file per `ETag`, named by a hash of it, and a `current` file naming the newest.
A serve confirmed for one tag reads that tag's file, so a refill landing between a `304` and its read cannot hand a range of the new version to a download of the old one.
A superseded copy is marked when it is first superseded (`.superseded`), and the next commit of that object removes it once the mark is ten minutes old.
So a copy a `304` just confirmed keeps its window whenever it was last served, though eviction may take it sooner.
A serve whose copy was removed between its `304` and its read asks for the object again, without a condition.
Commits, evictions and new fills of one object take that object's lock (one of 64, striped by its key), so two commits never mark each other superseded.
An object eviction leaves with no copy and no fill under way loses its directory, `current` and marks included, so the directories stay as few as the copies.

**Private content on the host's disk.**
The copies may be private, though each is served only behind upstream's check.
They live under `~/.cache/incus-spawn/confirmed/`, removed least recently served first once they pass `max-size`, and by `isx clean cache`; `isx doctor` names the domains, the size and the place.
Opting a domain in makes isx terminate TLS for every instance's traffic to it, so a tool that pins that domain's certificate stops working there; that is why the mode is off by default and host config only.
A domain isx serves otherwise is refused (built in, a handler's, a `maven-repositories:` one, `.internal`), and so is one overlapping a domain a tool injects a credential at, since the cache takes requests before injection would (`ToolProxyResolver.servedConfirmedCacheDomains`).
The trust taken is that the domain answers a conditional request only for a caller it lets in, which Azure does: a bad signature is a `403` before any condition is read.

**Measurement.**
Once a domain is opted in, `/activity` counts its traffic under its name.
Before that, its requests never reach the proxy, so `(other)` cannot show which domain is worth opting in.

## Exposed host ports

`exposed-host-ports:` in `config.yaml` lists TCP ports of the host's loopback that every instance with network reaches at `host.isx.internal:<port>` (#2117).
The motivating case is Ollama on the host: before, it took binding the service to the bridge and opening the firewall (Linux only), a `host-routes:` entry (whose clients must send `X-Isx-Instance-Secret`, which a plain Ollama client cannot), or an Incus `proxy` device per instance.
On macOS none of them is simple, since the service is on the Mac's loopback and instances sit behind the VM.

`HostPortRelay` opens one listener per listed port on the address the proxy already listens on and pipes each connection to the same port on `127.0.0.1`, else `::1`.
It shares no code path with interception, host routes or credentials: raw TCP, no TLS, no header changes, nothing injected, and the service sees a loopback caller.
It uses blocking sockets on virtual threads, two per connection, because a socket shut down for writing is what passes one side's end of stream on while the other direction still flows; Vert.x's `NetSocket` closes on the first end.
A blocking write is the backpressure, and there is no idle timeout, since a model may think a long time between two tokens; keepalive drops a peer that is gone.
Nothing listening on loopback closes the guest's connection with a trace line, not a warning.
A reload binds added ports and closes removed ones, connections included, and leaves unchanged ports and their connections alone.
Closing a listener waits for its accepting virtual thread to exit: a socket closed under a blocked accept is released only when that thread wakes, and until then the kernel still completes connections to an unlisted port (seen on CI, `HostPortRelayTest#anUnlistedPortRefusesConnectionsAsSoonAsListenReturns`).
A port that cannot be bound is warned about and skipped, never stopping the proxy or the other ports, and is tried again on every reload; `/health` names it to host callers (`unboundHostPorts`), and `isx doctor` warns.

**No caller identity, on purpose.**
On macOS every container reaches the proxy from the VM's one address, so a raw TCP relay cannot tell callers apart, and a template filter enforced only on Linux would make the platforms differ.
So the feature is for every instance with network, project-local and untrusted-lineage ones included, and the docs state the risk: many development services trust loopback callers completely, and Ollama's API has no authentication.
Only the host's `config.yaml` can list a port; no template, tool or project-local definition can.

**Instances only, never a peer beside the host.**
The relay listens on the bridge's gateway, or the Mac's end of the VM link, and a host with no input firewall routes a LAN peer's packet for that address to it (Linux's weak host model); review of #2117 reproduced it with a network-namespace peer routing the bridge subnet through the host.
Every other gateway listener already refuses such a caller (the MITM listener by its unknown address, the health endpoint by its host-only routes), so `RelayCallers` closes a connection before anything is connected to loopback unless its source is in the bridge's subnet (Linux: the subnet of the interface holding the bind address) or is the VM's address (macOS: `VmNetwork.discoverVmIp()`).
An address that cannot be placed admits nobody, and the rule is looked up again once it is 5 s old (`REDISCOVER_MILLIS`), at the next connection, since the bridge's subnet or the VM's lease can change under a running proxy and an address the old rule held must stop being admitted.
This is a filter on where a caller is, not who: within the bridge, or behind the VM's NAT, every instance is still admitted alike.
CI's `macos-role` job checks the macOS rule against the appliance: an instance is admitted, the host and a peer routed through it are refused (design/testing.md "The macOS roles, tested on Linux").

**Closed by default.**
No key, an empty list, or a value that is not a plain list of integers does not expose any port (`ExposedHostPorts`); within a list each port is checked alone, a duplicate is kept once, and isx's own ports (443, 18443, 18080, 53) are refused.
Those are exactly the gateway ports the proxy-only ACL admits (`ProxyOnlyAcl.egressRules`), so a `--proxy-only` instance never reaches an exposed port and the ACL is not widened; an airgapped one has no network.
Unlisted loopback ports stay unreachable; a service already listening on every address or on the bridge was reachable before, firewall permitting, and still is.

**Name resolution.**
`host.isx.internal` joins isx's bridge DNS block (`address=` + `local=`, as a domain) only while a port is listed, and is not an intercepted domain: no leaf certificate, and 443 on it is not served.
`BridgeDns` takes it apart from the domain set, at an address of its own, and `overrideAddresses` leaves it out of the stale-gateway check.
Every writer names that address or null, so none drops the line by omission: the proxy passes the address it listens on (`ProxyMain.Addresses.hostAddress`), and a CLI writer `ProxyConfig.bridgeDnsHostAddress`.
`HostRoutes` refuses a route named `host.isx.internal` or under it.

**macOS: DNS to the Mac's end of the VM link (option a).**
The name resolves to `VmNetwork.discoverHostBridgeIp()`, where the proxy already listens, and containers reach it through the VM's NAT; the appliance needs no change.
A CLI writer on macOS finds that address the way the proxy does, and only while a port is listed; it is derived, never a key in `config.yaml`, so it does not reintroduce the rejected `proxy-gateway-ip` (design/network.md).
Rejected: a DNAT in the appliance (option b), which needs the appliance to learn the list at run time, an appliance release, and care in the proxy-only ACL, whose bridge-family chains see the gateway port before the DNAT.
Verified on a real Mac: containers resolve the name to that address and reach a listed port on it through the VM's NAT, while an unlisted loopback port refuses.
macOS lets the proxy bind a port a service holds on every address, and the proxy's more specific listener takes the VM-facing address: a guest's connection goes through the relay to the service on loopback, so there is no bind failure for `isx doctor` to warn about.
Such a service is reachable at that address with or without the key, as on Linux; one bound to loopback only is not.

Rejected, on both platforms: firewall rules.
On Linux a DNAT to `127.0.0.1` needs `route_localnet` on the bridge, which removes a kernel protection for loopback; on macOS it needs an appliance rule and a `pf` redirect, which isx does not touch.
Both need root for every change to the list, leave state outside isx to restore at boot, and silently override what already listens on a port, where a bind fails loudly.
The proxy needs no root, already watches `config.yaml`, and holds no state of its own.

## A throttled answer reaches the client as upstream sent it (#2309)
When a registry or an API rate-limits a request and the proxy has nothing better to offer, the client gets upstream's own answer.
That is the `429`, its `Retry-After` and rate-limit headers, and its body.
Build tools, `git`, `gh` and the agents decide how long to wait from those, so an answer the proxy rewrote would make them retry too early or give up.
The proxy absorbs a `429` only by serving a cached copy in its place, or on a cache miss by waiting and asking again while the client's budget allows ("Wait out a 429 on a cache miss", #2302).

A `404` that carries a positive `Retry-After` is a rate limit too, and the caches read it as a `429` (`Upstream.status`, #2422).
Maven Central's edge sends a throttled host such a 404 for artifacts it has, as CI saw for `junit-4.13.2.pom` with `Retry-After: 37500`, while its 404 for a missing artifact has no `Retry-After`.
Read as a 404, it told Maven `Could not find artifact`, which Maven then remembers in the guest's `~/.m2`, and a confirmation answered with it evicted a good copy as withdrawn.
Read as a `429`, it starts the domain's backoff, serves cached copies unconfirmed, is waited out on a miss, and reaches the client as a `429` with upstream's headers and body.
What it gives up: a repository that sent a real 404 with a positive `Retry-After` would have each missing artifact handled as a throttle.
Its domain would go into backoff, a miss would be waited out for up to about 25 s, and the client would get a `429` where a 404 let it move on to its next repository.
A cache's domain reads it this way on every request, also those it relays without caching, such as `maven-metadata.xml` and SNAPSHOTs, for which Maven would record a 404 as missing until its update policy asks again (`Upstream.relayedStatus`).
A private repository's domain is read this way too, though it keeps no backoff (`Upstream.cacheDomains`).
The container registries' manifest cache reads it the same way, on its confirming `HEAD` and on a miss, whose answer `Upstream.relayResponse` sends as a `429`.
Any other 404 passes on as it came, a registry's on a request it relays without caching included.
A `Retry-After` of 0, or one that cannot be read, asks for no wait, so that 404 keeps its meaning.

Every relayed domain passes the answer on whole: `Upstream.copyResponseHead` copies the status and every header but `Connection` and `Transfer-Encoding`.
So does a Maven or Gradle cache miss that upstream does not answer with a `200` or a redirect, through `Upstream.relayResponse`, a `429` once its wait no longer fits.
The Vertex translations relay Vertex's status and headers the same way, in Vertex's form, which may lack the headers the client's SDK reads for Anthropic or Gemini.

Two answers are written by the proxy, and both keep upstream's retry headers (`Upstream.retryHeaders`: `Retry-After`, `RateLimit*` and `X-RateLimit-*`).
A checksum or signature request on a Maven or Gradle domain that has no stored copy gets upstream's status and body, up to the sidecar size bound, with its `Content-Type` and those retry headers (`SidecarAnswer.headers`).
This holds for a `5xx` too, since nothing differs but the status.
A ChatGPT token refresh refused with a `429` keeps the proxy's own JSON body, which Codex classifies by its `code`.
A refresh refused with a `5xx` becomes the proxy's `502`, without upstream's headers.
OpenAI's body is not passed on, since it answered a request that carried the host's refresh token.

The proxy's own throttle of ChatGPT refreshes answers `503` with a `Retry-After` of the rest of its 30 s window, in whole seconds rounded up (`ChatGptRoute.newerOrLater`).
Without it, Codex obeying a shorter `Retry-After` from OpenAI would come back inside the window and get a `503` that says nothing about when to try again.

## Vertex AI: container in Vertex mode vs standard mode
We initially ran containers in standard (non-Vertex) mode with proxy-side API translation — the container sent `/v1/messages` and the proxy rewrote to Vertex `rawPredict`.
This had a critical flaw: Claude Code's model list is provider-dependent.
In standard "firstParty" mode the model picker is a hardcoded subset that omits newer models (e.g. Opus 4.6 was missing).
In Vertex mode the full catalogue is shown.

The solution: containers now run in Vertex mode with `CLAUDE_CODE_USE_VERTEX=1`, `CLAUDE_CODE_SKIP_VERTEX_AUTH=1` (skips GCP auth — the SDK uses stub credentials that produce empty auth headers), and `ANTHROPIC_VERTEX_BASE_URL=https://api.anthropic.com/v1` (redirects the Vertex SDK to the proxy).
The Vertex SDK formats requests in Vertex URL format (`/v1/projects/.../models/...:streamRawPredict`), sends them to the proxy, and the proxy injects real GCP credentials before forwarding to the actual Vertex endpoint.
The proxy also retains the standard-to-Vertex translation path for `/v1/messages` requests — this is the primary path for tools like Pi that use the standard Anthropic API format, and also serves manual `curl` calls inside the container.

**Fragility and mitigation:** The standard-to-Vertex translation path uses an allowlist (`ALLOWED_FIELDS` in `VertexBodyRewriter.java`) that may drift as Anthropic adds new standard fields.
However, the primary traffic flow (Vertex passthrough) doesn't use the allowlist — the Vertex SDK already formats the body correctly.
The allowlist only affects the fallback translation path.
The `anthropic_version: "vertex-2023-10-16"` value is hardcoded in the translation path — this matches the Anthropic Vertex SDK and has been stable since Vertex support launched.
The Vertex passthrough path doesn't set this value; the SDK does it itself.
