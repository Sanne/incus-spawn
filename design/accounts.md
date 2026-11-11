# Credential accounts and proofs

How a host holds several accounts per credential namespace, how an instance is matched to one, and how the proxy tells callers apart.
This continues design/proxy.md "Auth & Security: MITM TLS Proxy".

## Credential accounts

**Claude accounts:** `claude.accounts` is a named map of credentials, so one host can hold several Claude identities at once (a personal Pro/Max subscription and a work Vertex project, say) instead of swapping whole `config.yaml` files by hand.
Each entry declares what it *is* — `type: api-key`, `oauth` or `vertex` — never what it is used for, because usage is expected to grow.
`claude.default` names the account used when nothing narrower applies.
Accounts are named in `ClaudeAccount`/`ClaudeAccountType` (nested in `SpawnConfig`); `type` may be omitted when the fields make it unambiguous (`effectiveType()`), and an entry missing its credential is ignored rather than half-applied (`isComplete()`).

Selection is by capability, via `ClaudeConfig.accountFor(Predicate)`: the default account when it can do the job, otherwise the first account in file order that can.
`account()` (the unfiltered form) is what instances get today.
`ClaudeConfig`'s familiar accessors (`isOauthMode()`, `getApiKey()`, `isUseVertex()`, …) are **derived from the resolved account**, not from the flat fields, so `ClaudeSetup`, `PiSetup`, `ProxyCredentials` and `DoctorCommand` needed no changes.
Both `ClaudeConfig` and `ClaudeAccount` bind **by field** (`@JqVisibility` with getters and is-getters `NONE`).
The derived getters share names with the legacy fields.
Binding through those accessors would make the split property derived, and ignoring one of them would drop the whole property — which is how a flat `config.yaml` stops deserializing.
Claude stays typed because the type drives real behaviour — which variables a build bakes, whether an account can answer `isx ask` — but only as a *reader*: which accounts exist, which one is the default and whether a pin is valid are answered by `AccountResolver`, the same as for every other namespace (see "One accounts model" below).

**Per-instance selection.**
Which account an instance uses is decided in three layers, each explicit, resolved lowest-first: the namespace's `default` in `config.yaml`, an `accounts:` map on the template (merged *per key* down the inheritance chain, so a child re-points one namespace and keeps the parent's choice for the rest), and a per-instance override from `isx branch --account <ns>=<account>` or `isx account set`.
Only the last two are written onto the instance, as `user.incus-spawn.account.<namespace>`, so an instance that carries neither stamp keeps following the global default as it changes.

Selection is always written `<namespace>=<account>`.
A bare account name is deliberately not accepted even though it would read better.
It would have to fan out over whichever namespaces happened to hold an account of that name, so adding a credential namespace later would silently widen the meaning of a command someone had already written down.

**Accounts are generic, not Claude-specific.**
`ToolDef.ProxyDef.fullConfigPath()` already built `<config-namespace>.<config-path>` (`github.token`); `accountConfigPath()` builds `<ns>.accounts.<account>.<path>` beside it, and `ToolProxyResolver` prefers it, falling back to the flat path when the account omits that key.
That one preference is the whole mechanism: every namespace gains named accounts at once, including a tool defined purely in YAML that isx has never heard of, with no per-tool code and no per-tool flag.
`AccountResolver` does the namespace-agnostic resolution off the serialized config tree.

**One accounts model (#773).**
Claude got accounts first and grew its own rules: it presented a pre-accounts flat file as an account named `default`, and always wrote the accounts layout.
Every other namespace kept a flat credential flat until a second account was added, and did not present it as an account at all.
With identical-looking files, `isx account list` showed Claude and nothing else, and `--account claude=default` was accepted while `--account github=default` was refused.
The difference had leaked into generic code as `if (namespace is claude)` branches in `AccountSelection` and a namespace removal in `ProxyCredentials`.
Claude's rules won, for every namespace:

- *A flat credential is the account `default`.*
  `AccountResolver` presents a namespace with no `accounts:` block but with a credential as one account named `default`, with no fields of its own.
  `value()` already falls back from an account's key to the flat one, so that account reads the flat credential.
  Nothing is synthesized into the file, and reading a file written by any earlier isx is unaffected.
  Listing, pin validation and the proxy all ask the same resolver, so a pin the CLI accepts is one the proxy serves.
- *Writing always produces the accounts layout.*
  A single credential is saved as `accounts.default` with `default: default`; `NamespaceAccounts.materialize` moves a flat one there on the first write that has something to save, and never before, so an abandoned `isx init` leaves the file as it found it.
  `isx init` therefore has one vocabulary, `AccountTarget(name, replaceOthers)`, for every namespace, where GitHub used to pass `""` to mean "the flat layout".
  Downgrading is not a goal: an older isx reads a namespace written this way as unconfigured.

What the resolver needs to know about a namespace is an `AccountShape`, declared rather than coded.
`ToolSetup.accountShape()` derives it from the tool's proxy definition: its `secret: true` entries are the credential (so a leftover `github.email`, or the `apiKey: ""` older releases wrote for every namespace, is not an account), every entry the user supplies is held per account, and `confirm` entries — Bob's licence consent — stay at the namespace level, where every account inherits them.
Two built-in tools override it.
`GhSetup` names `email` as per-account, because the commit email belongs to an identity but is not something the proxy injects.
`ClaudeConfig.ACCOUNT_SHAPE` recognises the flat Vertex setup (`useVertex: true` is not a secret) and judges each account by its type.
That judgement is the shape's one hook, `problem(account, namespace)`.
An unusable account is skipped when picking a default, and pinning it is refused as "configured but incomplete: <why>" rather than "not configured", because an incomplete account is easy to overlook in `isx init` (#742).
The flat credential is never judged — the shape already decided it was one, and a flat `useVertex: true` without a region must still reach `ProxyMain` as a Vertex account so it can report exactly that misconfiguration.
The tree-taking resolver methods take the shape as a parameter, so the proxy resolves against the tool setups it already loaded and never scans for tool YAMLs on the event loop.
The `SpawnConfig`-taking conveniences look it up among the built-in Java tools first and scan only for a YAML tool's namespace.

The typed classes follow.
`BobConfig.hasAuth()` and `OpenaiConfig.hasAuth()` used to read the flat field, which is empty once a key lives under `accounts:`.
They now resolve through `NamespaceConfig.defaultValue()`.
The branch-time credential check asks for Bob and OpenAI the way it already asked for GitHub, against the instance's account (see design/proxy.md "Credential validation").
Bob and OpenAI got named accounts in the same change, through the generic `isx init` flow rather than code of their own.

The credential namespaces that *do* have a Java class (`GitHubConfig`, `BobConfig`, `OpenaiConfig`) extend `SpawnConfig.NamespaceConfig`, which preserves keys the class does not declare.
Without that an `accounts:` block would deserialize to nothing and then be **deleted** by the next save — the same credential-destroying shape as the `putAccount` bug found in review.
Namespaces with no Java class at all were already safe: they land in `SpawnConfig.getExtras()`.

**How the proxy tells callers apart.**
One shared proxy on `DEFAULT_MITM_PORT` still serves every instance, but it now identifies the caller by source address.
Every branch is given a static IP (`InstanceLifecycle.configureBranch`, recorded as `user.incus-spawn.static-ip`) and the iptables REDIRECT that sends `:443` to the proxy preserves the source address.
So one `/1.0/instances?recursion=1` call maps every address to an instance and its pinned accounts at once.
`InstanceRegistry` holds that snapshot; `lookup()` never blocks, and refreshes run through `executeBlocking` following the single-flight pattern already used for DNS and Vertex tokens.
`isx branch` and `isx account set` signal the proxy (SIGHUP) so a change lands immediately rather than at the next poll.
`RequestContext` carries domain, caller, credentials and routing down the request path in place of the bare domain, and per-selection credentials are cached keyed by the selection itself and cleared on every config reload.

An address that is not a known instance gets no injected credential; host-side traffic supplies its own authentication.
A known instance with no pin receives the configured default only after proving its namespace.

That premise does not hold on macOS (#1582).
There the VM's `PROXY_MODE=remote` DNATs `:443` to the host and the VM bridge's `ipv4.nat` masquerades it, so every container reaches the proxy from the VM's one address, which is no instance.
Before #1107 such a caller got the defaults; after it, nothing, so 0.4.0 served no credential at all on macOS.
So on macOS a caller whose address is no known instance is identified by its proof instead (`MitmProxy.proofMayIdentify`).
The registry indexes each verifier hash to the running instance stamped with it, per namespace, and `InstanceRegistry.prove` takes the instance every presented proof names, refusing when they name different ones.
A hash two instances share comes only from an `incus copy` made outside isx, and is decided as a shared address is: the one running, else nobody.
Unlike an address, a proof never names a stopped instance, even its only holder: a stopped instance sends no requests, and a listing taken before its start costs only the refresh `authorize` makes on a miss.
The host's own traffic (loopback, or from the listener's address) is never identified this way, and a caller at a known instance's address is always held to that instance's proofs.
Linux keeps the strict address check, which is what stops a proof replayed from another instance; that protection never existed on macOS, where the address does not identify an instance.
`integration-tests` boots the appliance in `remote` mode (`appliance/test-proxy-remote.sh`, #1585) on a tap network shaped like vfkit's NAT.
Two containers' requests to the gateway's `:443` must reach a host listener on the MITM port, and both must arrive from the VM's leased address.
The listener stands in for `isx-proxy`.
The real `isx-proxy` and `isx` run in their macOS roles in CI's `macos-role` job, from a test-only build that is never released (#1800, design/testing.md "The macOS roles, tested on Linux").
Matching the VM's DHCP lease to accept only the VM's address was rejected: it adds nothing an address that identifies nothing could give, and an unreadable leases file would reproduce this failure.
The proof's instance also gets the request's traffic in `/activity`: a flow from such a caller is counted once `authorize` has named it, not in flight while its proof is checked.
A PROXY-protocol relay in the appliance restoring per-container addresses was considered and deferred (#1584, design/macos-and-vms.md "Address-based caller identity on macOS").

**Builds are served their template's accounts (#903).**
A build container used to get a DHCP address, so the proxy could not tell it from host traffic and served every namespace's default, whatever the template's `accounts:` chose.
`repos:` were cloned and `prime` ran with the user's default token -- for a project-local template, handing that identity to code the cloned repository controls -- and `GhSetup` baked the default account's name next to the template account's email, stamped as the template account so nothing ever repaired it.
Before its first start, a build container now gets what a branch gets: an address from `StaticIpAllocator` with `security.ipv4_filtering`, and the template's pins (origin `template:<name>`), in one write (`InstanceNetwork.assignBuildAddress`), then a SIGUSR1 so the proxy knows it before anything inside asks (`lifecycle/BuildAccounts.start`).
Builds using defaults also claim an address and receive fresh proofs before authenticated steps run.
The `account-identity` stamps a copy carries from its parent are cleared in the same write, all of them and not only the pinned namespaces'.
They describe the parent's build.
The proxy refuses an account -- pinned *or* default -- whose auth mode does not match one, so it would refuse this build the account its own template chose, or a default changed since the parent was built.
They are read first: after tool setup, `settleIdentities` compares them with this build's accounts to re-derive a parent's identity that no longer matches (#281), and reading them back from the container then would find nothing.
When the build is done it stamps what it baked and puts the parent's back for every other namespace (`BuildAccounts.identityStamps`).
A namespace whose credential is no longer configured does not derive an identity, yet the parent's `.gitconfig` or Claude environment is still in the rootfs.
No `.network` file is pushed: the guest keeps DHCP, and Incus's DHCP server hands out the NIC's `ipv4.address`.
It will not while another MAC holds a lease on that address, and one usually does when a chain is built.
The allocator hands out the lowest free address, so a child's build is offered the one its parent's build has just given back, whose lease the parent's MAC keeps.
Incus clears only IPv6 leases when a NIC's `ipv4.address` changes, and a force-stopped guest never releases its own.
dnsmasq would then give the child a dynamic address that filtering drops, and the build would fail minutes later as a DNS error.
So a build's claim also skips every address in the bridge's leases (`IncusClient.networkLeaseAddresses`, read before the allocation lock).
Once the guest is up `InstanceNetwork.requireBuildAddress` fails the build, saying why, if it holds another address on the bridge's subnet anyway -- only that subnet, since a parent's docker0 or podman bridge also shows up in the instance's state.
Incus lists leases per project, so an instance of another Incus project on the same bridge stays invisible to the allocator, as its static reservations always were; the check turns that collision into a clear failure.
The build prints the accounts it is served.
Once stopped, the template gives the address back (`releaseBuildAddress`), returning the bridge NIC it was claimed on -- and no other -- to the profile's, since a template makes no requests and its copies never keep an address.
A release that fails only warns, since the template is built and an address it keeps is freed when it is rebuilt or removed.
A failed build keeps both address and pins as `<template>-failed-build`, so starting it for inspection serves it its template's accounts rather than the defaults.
Deleting it frees the address, as the template's next failed build, `isx clean` or a destroy does.
The allocator reads claimed addresses from the instance listing each time, so a deleted instance's address is free with nothing to undo.
The proxy is another matter: until it re-reads, it would serve the deleted build's accounts to the next holder of the address.
So `isx clean`, `isx clean pool` and `isx doctor`'s remediation signal it after deleting failed builds (`CleanCommand.deleteFailedBuilds`/`deleteFailedBuild`), as `isx destroy` does.
A build or branch signals it before its first start anyway, which leaves only a manual `incus delete` to the proxy's refresh backstop.

**An update builds a new template (#1760).**
`isx update-all` and `isx project update` used to change a template in place: an update that failed or was interrupted part way left a half-updated template, and a branch made meanwhile copied whatever it had written so far.
They now run in a copy, as a build does (`TemplateUpdate`).
The copy takes the build's temporary name (`<template>-rebuilding`, which listings and `update-all` skip), under the template's `TemplateLock.reading` hold, and carries `untrusted-lineage: building` from its copy request on; a template already untrusted (a project template) keeps its own reason.
Every step runs in the copy, and only when all succeeded and the copy is stopped again does `TemplateLock.replaceIf` put it in place, with `trustBuilt` inside the lock for a template that was trusted.
One failed step discards the whole update, the steps that succeeded included, so a template is always one consistent state; a re-run retries all of it.
The swap first checks, under the lock, that the instance named for the template is still the one copied (its `created_at`), so a rebuild that landed meanwhile is never replaced by an update of what it replaced.
As a backstop, for a lock file that cannot be used or an isx from before the lock, the copy is known by its `created_at` too: the swap also requires that the copy is still the one this update made, and the update deletes only that one.
Otherwise a copy another isx deleted would leave the swap deleting the template for a rename that cannot follow, and one it re-made would be swapped in un-updated.
A refusal before anything is copied (stranded storage, a running template, another build or update holding the template) is reported as one, not as a failure a re-run would retry.
A step that throws, a copy that cannot be stopped and a refused swap all delete the copy.
Builds and updates share that name, and each deletes what it finds there first, so `isx build`, `isx project create` and both updates hold `TemplateLock.building` for the template while they work under it.
It never waits, since the holder may take minutes: a second build or update of the same template is refused, and is not offered a re-run.
Holding it, an update deletes whatever is under the name, as a build does, since what an interrupted build or update left is nobody's work any more.
A project template is never trusted by its update, one made before the stamp included.
The copy drops the template's `disk-referenced` stamp, and the update measures it again before the swap, as a build does (`probeReferencedSize`).
The template is a new Incus instance after every update: its `created_at` and subvolume change, but `user.incus-spawn.created`, which `TemplateStaleness` compares, and every other stamp carry over, so its children are not made outdated.

**A template's update is started as a build is (#1743).**
An update runs a template's `prime` commands, npm updates and git fetches, as a build does.
It used to run them on a DHCP address with no filtering, so code in the template could re-address `eth0` as a stopped branch's `STATIC_IP`.
The proxy identifies callers by address, so it would attribute that traffic to the branch.
`InstanceLifecycle.startTemplate` now claims an address as a build does (`InstanceNetwork.assignBuildAddress`), with `security.ipv4_filtering`, and signals the proxy as a build does.
The same write grants the template temporary proofs and a per-start secret (`BuildAccounts.templateUpdate`, through the `grantInto` a build and `isx project update` use), and clears any `mcp-caller` stamp.
So its steps are served the template's own accounts: the pins its build stamped on it, or the configured defaults, as for a build.
The readiness exec delivers them, and the update checks the guest took its address (`requireBuildAddress`).
However the update ends, it revokes the proofs (`BuildAccounts.revoke`) and signals the proxy before the stop, clears the guest's caches of them from the rootfs when the guest answered (`clearGuestProofs`), then gives the address back once the template has stopped.
A revocation or cleanup that fails fails the update, saying so.
A template that is running is refused before anything is written, and one whose guest did not take its address fails its update, naming `update-all` as what to retry; either fails that template alone, and `update-all` goes on to the next.
On a host that cannot enforce filtering the start drops it and warns, as for a build or a branch.

A build container is refused host routes by its `untrusted-lineage` stamp, but a stamp on a template would refuse every branch of it too, since the registry asks each caller's ancestors.
So the registry never trusts a base template as a caller itself (`InstanceRegistry.lineageOf`, at the caller only): a template runs only to be built or updated, and its branches keep their routes while it updates.
A project template is refused already, as every project-local caller is.
The accounts are the ones the template's build stamped, not its definition's current `accounts:`: its baked identities match those, and a definition that changed since makes it outdated for `isx build`.

**This only works because the identity cannot be forged.**
`security.ipv4_filtering` is set on the NIC alongside the pinned `ipv4.address`.
Without it, root inside a container could re-address its interface as a neighbour and spend that neighbour's subscription — per-instance credentials would *look* like isolation while providing none, which is worse than not having them.
Every stopped start of an existing instance turns it back on where it is off (`InstanceLifecycle.prepareForStart`, reached only through `startForUse` and VM agent recovery, so the `isx shell`/`run` prep and the TUI, which share it in `InstancePrep`, cannot start around it), both for instances branched before this and for ones the fallback below left without it.
So an instance regains protection once its host can enforce it, rather than staying open until it is re-branched.
The check reads the instance the other pre-start repairs already read, so it costs no request when filtering is already on.
A host whose firewall backend cannot apply it warns rather than failing the branch, and the warning says plainly what is not enforced.
That warning has to come from the *start* path, not the config path.
Incus accepts `security.ipv4_filtering` on a host that cannot enforce it and only fails when the instance starts, so `InstanceLifecycle.startInstance` recognises that specific failure, drops the setting and retries.
Otherwise the hardening would take a working host and leave it unable to start anything it branched.
It never does this for a NIC carrying the proxy-only ACL (design/network.md "Network Modes"): there, the failed start stands.
The macOS appliance was such a host until its kernel gained the nft `bridge` family (#905): Incus's nftables driver installs the per-NIC rules there, so every Mac branch fell back to no filtering.
`CONFIG_NF_TABLES_BRIDGE` is now built in, and the appliance smoke test fails CI if it is dropped (`appliance/DESIGN.md`).

**A per-start secret, checked together with the address (#934).**
A host-side service that authorises control actions -- grants, leases, a controller reached through the proxy -- needs more than the address, which is only as good as the filter on the day.
So every isx start gives the instance a new random secret (`InstanceSecret`: 256 bits, hex), and `InstanceRegistry.identify()` accepts a caller only when its source address and the secret it presents (`X-Isx-Instance-Secret`) belong to the same instance; neither alone passes.
On Linux it is an extra layer, never an alternative (macOS is below): since the secret is only valid from its bound address, a leaked one is useless, so it needs no rotation protocol beyond "every start makes a new one".
Client certificates per instance were the alternative -- a key in the box and a CA to run, for nothing over address plus secret given the filter.

*On macOS, the secret alone (#1583).*
The address that would bind the secret does not identify an instance there: every container reaches the proxy from the VM's address (#1582, "How the proxy tells callers apart"), so until 0.4.1 `identify()` refused every caller on a Mac, and with it `isx branch --mcp-client` and every host route.
So, behind the same gate as a proof (`MitmProxy.proofMayIdentify`: macOS, never the host's own traffic), a caller whose address is no known instance is the instance whose secret it presents (`identify(address, secret, true)`).
The registry indexes each running address owner's secret hash to its address; a hash two instances share comes only from an `incus copy` made outside isx before isx started it, and is decided as a shared proof is: the one running, else nobody, and never a stopped instance.
A failed listing clears that index with the proofs' (the address check keeps its last good listing), so an Incus outage does not keep a stopped instance's secret valid.
The secret is 256 random bits rotated on every start, so it cannot be guessed; what is lost is the address check that makes a secret leaked to another instance useless, a protection the Mac never had.
A caller at a known instance's address is still held to that instance's secret, and Linux keeps the address check.
As with a proof, the secret's instance gets the request's traffic in `/activity`, counted once `McpBridge` or `HostRouteRelay` has named it.
Restoring per-address identity in the appliance (#1584) would make this unnecessary again.

*Where it lives.*
The host keeps only its SHA-256, as `user.incus-spawn.instance-secret-sha256`, which the registry reads from the listing it already makes, so the proxy pays nothing.
Never the secret itself: a guest can read its own `user.*` keys through `/dev/incus`.
Keeping the hash in instance config also means a destroyed instance forgets it with nothing to clean up.
The hash sits beside the accounts in the registry's snapshot rather than inside `InstanceAccounts`, which `MitmProxy` caches by value: a key that changed on every restart would grow those caches.
In the guest it is `/run/isx/instance-secret`, root-owned and readable by the instance user's group (0440), and the login profile exports `ISX_INSTANCE_SECRET_FILE` naming it.
The path is exported, not the value, so the secret is not in the environment of every process, nor in an agent's `env` dump.
`/run` is a tmpfs.
Any reboot isx did not perform (`incus restart`, a reboot inside the guest, autostart after a host reboot) leaves the box without a secret, so it fails closed rather than keeping the previous start's -- until the next `isx shell` or `isx run` notices and gives it one (#1024, below).

*Where it is made, and what it costs.*
The start path is latency-sensitive, so delivery adds no round trip of its own.
A branch's secret is generated in `BranchFlow.create`.
Its hash rides on the copy request (no extra write, and no moment in which a branch of a branch carries its source's hash).
The secret goes into the post-start setup script beside the SSH keys.
A stopped start goes through `InstanceLifecycle.startForUse` -- `isx shell`/`isx run`, the TUI's shell, and the CA repairs before them -- and the TUI's restart through `restartForUse`.
`rotateInstanceSecret` records the new hash before the start or restart, while no boot contends for the Incus API (one PATCH, the only new request, pinned in `InstanceLifecycleRequestBudgetTest`).
`waitForReady`'s probe writes the secret as it checks the guest answers.
`VmAgentRecovery` records the hash in the write that already stamps the boot it restarted into.
The proxy is not signalled: on Linux that costs every start a bridge read, a `/health` call and a fork, and has the proxy list every instance while this one boots.
Nothing needs it sooner -- the previous secret went with the guest's `/run` -- and `identify()` documents that a refusal right after a start is worth one refresh and a retry, throttled by `wantsMissRefresh()` as a lookup miss is.
A guest can present wrong secrets at will, and each refresh lists every instance.
A refusal inside the throttle's interval waits for it to pass and is asked again, not refused from that snapshot (`CallerCheck.identify`, #1800).
A branch signals the proxy just before it starts the instance, so the instance's first call meets a snapshot that young, from before its secret: every `--mcp-client` branch on a fast host warned it could not reach `isx mcp`.
Template builds get none, so no image carries one.
The write is best-effort: a box left without its secret is refused by whatever checks it, which is the safe way for this to fail, and must not fail the start it rides on.
It waits (up to 3 s) for the tmpfs on `/run`, and skips the write without one.
A container answers exec as soon as its init runs.
A secret written before systemd mounts `/run` would land on the rootfs, hidden under the mount and carried into copies.
Every `IncusClient.copy` clears the hash beside the address, so no copy holds its source's; a branch sets its own in that same request.
The secret travels in the exec's environment (`InstanceSecret.guestEnv`), never its arguments.
Any user in the guest can read a process's `/proc/<pid>/cmdline` for as long as it runs -- and the branch setup script can run for many seconds -- while `/proc/<pid>/environ` is readable only by its own uid.
The script moves it into an unexported variable at once, so nothing it starts inherits it.
Nor does it sit in `RuntimeConfig`, which `BranchFlow.create` hands back to its callers: `setupRuntime` takes it as an argument.

*A reboot isx did not do (#1024).*
`InstanceLifecycle.ensureReady`, the step before every `isx shell` and `isx run` (and the TUI's shell), gives a running instance that lost its secret a new one: the hash recorded, then `GUEST_SCRIPT` run in one exec (`giveSecretToThisBoot`, best-effort and warning through `say`, so the shell still opens; a delivery that fails unsets the boot stamp it was recorded with, so the next shell tries again; since `GUEST_SCRIPT` never fails, the same exec runs `GUEST_CHECK` after it, and a secret the guest did not keep counts as a failed delivery and is reported, rather than silently given anew on every shell).
Telling that it is needed costs no request of its own, but how differs by machine type, because the host sees a reboot differently.
Every container reboot -- `incus restart`, `reboot` in the guest, autostart -- is a start.
Incus sets the instance's `last_used_at` on every start.
The instance `ensureReady` already holds carries it, so it is compared with `user.incus-spawn.instance-secret-boot`, the boot the secret was made for (with the mark that it carried proof tokens, #1106; see "Proof tokens").
Since a boot's `last_used_at` exists only once it starts, isx's own starts (`startForUse`, `restartForUse`, a branch's first start) record it after the guest answers, after the boot and not during it, where `setupRuntime` documents that API calls contend.
That takes a read of the instance, which the starts followed by a CA check (`isx shell` and `isx run`, the TUI's shell, a branch that is not airgapped) were about to make anyway, so `startForUse` and `recordSecretBoot` return it and `CertificateAuthority.fixContainerCaIfNeeded(incus, name, instance)` uses it: there the stamp's write is the one request added (an airgapped start and the TUI's restart have no CA check, and pay the read too) (start plus CA check 5 -> 6, branch 18 -> 19 against `FakeIncusDaemon`, pinned in `InstanceLifecycleRequestBudgetTest`).
Two isx processes acting on one instance in the same boot -- two shells, a shell and the TUI, MCP's `start_instance` -- each rotate and then deliver, and interleaved they left the guest holding one secret and the host recording the other, with the boot stamped as holding it (#1065).
So rotating and delivering are serialized per instance by a `HostLock` (`InstanceLifecycle.secretLock`, `instance-secret/<name>.lock` under `Environment.lockDir()`), held over `startForUse`, `restartForUse`, `giveSecretToThisBoot` (which `giveSecretIfMissing` takes) and `VmAgentRecovery.restartForAgent`.
One that waited asks again whether the work is still needed (`HostLock.waited()`): a start that finds the instance running only gives it a secret if its boot still lacks one, as a delivery that waited does (a container's boot stamp, a VM's guest).
A listing read before another start that has already let go is not seen by the lock, so a start that then fails on an instance that is running all the same goes on as if it had started it: the readiness probe delivers the secret it just recorded, and the boot is recorded.
The same covers a start Incus made whose answer was lost.
That matters most for a VM, whose probe only asks whether a secret is there and would never see that its hash was replaced.
So a second `isx shell` on a box another is starting waits and connects, where it used to fail on Incus's "already running" or "busy" refusals more often than not.
Uncontended it costs no Incus request, and the shell into a running, stamped container never takes it.
The wait is bounded by the instance's ready timeout, the 30 s Incus is given to stop or restart and the 30 s to start, and `HostLock.TIMEOUT` (for a VM also the graceful stop), since the holder may be sitting out a whole restart.
A wait behind another thread of the same process says so too once it passes half a second (`Another isx operation is ...`), as the TUI or `isx mcp` can wait as long as another process does.
The lock is outside `CredentialProof.lock`, which each step still takes, and nothing takes the two the other way round.
The lock files are never deleted, one empty file per instance name, as deleting a lock file reopens the inode race `FlockInstanceLockManager` documents.
Without the record the next shell would take that boot for a stranger's and replace its secret.
A VM is never stamped: QEMU reboots a guest in place when it can, with no new start, no new `last_used_at` and no new QEMU pid (`AGENT_RESTART_BOOT`'s identity), so nothing the host reads changes.
Only the guest can tell, and `ensureReady` already asks a running VM's agent whether it answers on every shell, so that probe now also runs `InstanceSecret.GUEST_CHECK`, which prints a marker when `/run` is a tmpfs with no secret in it.
Asking the guest the same way for containers was the alternative: one exec on every shell into a running container, where the comparison costs nothing.
Guessing from the pre-start state was rejected: a stamp written before a start cannot tell the boot it was made for from a later one.

*A running box that `isx mcp` uses (#1975).*
`ensureReady` runs only where isx prepares a box for use, and a coordinator's `exec`, `delegate` and task tools run in a box that is already running.
So a box Incus restored after a host reboot kept its empty `/run/isx`, and every model call in it was refused with a 401 until someone ran `isx shell` or `isx start` on it.
`McpSession.requireRunning`, the gate of every tool that runs something in a box, and `start_instance` on a running one now call `InstanceBackend.giveSecretIfMissing` (`InstanceLifecycle.giveSecretIfMissing`) before anything runs.
For a container the judgment comes from the read `requireRunning` already made: `metadata()` carries `last_used_at` and the type beside the config, so a boot holding its secret costs no request.
A missed boot costs one read, then the delivery under the `secretLock`.
A VM's guest is probed once per boot `isx mcp` sees (its `last_used_at`), so a restart by Incus is caught without a probe on every exec; a reboot in place waits for the next `isx shell`, as before.
A delivery that fails is logged and the command still runs: without its proofs the proxy relays the guest's placeholder unchanged, which fails closed.
A plain `ssh <name>` takes the same step: its ProxyCommand, `isx ssh-proxy` without `--start`, already read the instance to check it runs, so it calls `InstanceLifecycle.giveSecretIfMissing` from that read and never starts anything.
What it says goes to stderr only (`TerminalOutput.stderrOnly()`), since stdout is the connection's stream.
For a VM, whose readiness only its guest can answer, that is one probe per connection, as `--start` already prepares a running VM on every connection.
The probe runs before the `secretLock`, so a VM that holds its secret never waits on another isx.
A VM it finds without its secret is given it under the lock without a second probe, unless another isx held the lock meanwhile.
An instance without isx's parent stamp, which `isx shell` refuses, is never given a secret, so isx does not write anything into an instance it did not make.
A plain `incus exec` bypasses isx entirely, and stays without its proofs until isx next prepares the box.

**Proof tokens: address and proof authorize credential injection (#1100, #1107).**
A nested machine shares its parent's apparent source address after NAT, including user-mode networking.
Every credential request therefore needs the current proof for that address and credential namespace before the proxy can inject the selected account.
`ProofToken.derive(seed, namespace)` is HMAC-SHA256 keyed with a stable credential seed, truncated to 32 hexadecimal characters.
`CredentialProof` derives the seed with HMAC-SHA256 from a protected host key and a fresh random generation recorded in `user.incus-spawn.instance-proof-id`.
The host key lives in `~/.config/incus-spawn/credential-proofs/host.key`, with the ownership, permissions and atomic-creation checks used for host-route secrets.
The config directory may be a symlink to a target passing those checks; the key and its immediate directory must not be symlinks.
It must be backed up with the instance state; losing it requires restoring it or explicitly resetting affected instances.
The generation is public and cannot derive a usable proof without the host key.
Starts and reboots retain it; branches, replacement instances and explicit reset get fresh generations.
The separate MCP and host-service secret still rotates on every start.
Tools declare their token prefixes and environment variables in `proxy.placeholders`; a token is the prefix followed by `isx_` and the digest.
The host records SHA-256 of each namespace digest in one replaceable `user.incus-spawn.instance-proof-sha256` stamp (`ProofVerifier`).
The stamp contains neither usable tokens nor the raw secret.
Deriving proofs from a public metadata value alone is unsafe because guests can read `user.*` through `/dev/incus`.
All declared prefixes of one namespace normalize to the same digest, and verifier comparisons use constant-time hash comparison.

`ProofCarriers` reads authentication carriers from trusted tool declarations, including custom headers and Basic credentials.
Missing placeholder diagnostics on proxy load consider all trusted tools together.
Single-tool validation checks only the tool's own namespace, since an absolute config path may borrow another tool's declarations.
An unscoped credential is diagnosed with instructions to set `config-namespace` before declaring placeholders.
Claude accepts its API-key, OAuth and Vertex prefixes through either `Authorization` or `x-api-key`, independently of the outer account's mode.
HTTP and WebSocket requests share `MitmProxy.authorize`, before account resolution, compatibility checks, Vertex translation, token acquisition, injection or inheritance substitution.
The entry a request selects is injected only when the request proved every namespace that entry spends (`ResolvedToolProxy.namespaces`), checked at injection against the per-account routing that chose it (#1423).
Every proof the request carries for the destination must verify, but tools sharing a domain under different path scopes each need only their own: requiring every namespace on the domain blocked a request to one tool's path for lack of another tool's proof.
Selecting the entry before authorization was rejected, because the entry depends on the account: a pinned account that does not resolve a scoped entry falls back to another tool's, which the check at injection refuses without that tool's proof.
Claude's domains still require every namespace their carriers declare, and the `inherit` swap covers only proven namespaces.
The authorization and account resolution use the same configuration snapshot even if a reload finishes during a registry refresh.
A missing, static, malformed, stale, cross-instance or cross-namespace proof preserves incoming authentication, host, path, query and body semantics.
An unknown address receives no injected defaults, including host-originated traffic; host tools must use their own credentials.
On macOS an unknown address other than the host's may be identified by its proof alone (#1582, "How the proxy tells callers apart").
A refusal's warning says which case it is: a proof from an address that is no known instance was never checked, which nothing inside an instance fixes, while one that was checked and does not match points to renewing it.
A valid proof authorizes account selection but creates no credential grant.
A valid proof for a missing or incompatible selected account still fails with the existing account diagnostic.

`InstanceRegistry` reads verifier stamps with its existing listing and binds them to addresses alongside account selection.
An unrecognized proof waits asynchronously for the next permitted refresh before verification is retried.
The shared timer observes the one-second listing floor and bounds the wait even when other registry readers keep refreshing.
Independent credentials do not wait for proof refresh.
Concurrent credential checks share the refresh.
A failed registry refresh clears credential verifiers while retaining the account snapshot, so unavailable Incus cannot keep authorizing stale proofs indefinitely.
Warnings about isx placeholders are deduplicated and bounded, never print authentication values, and point to recovery instructions.
Account refusals retain the known account reason.
Unexpected credential-refusal and WebSocket setup failures log only the exception class because exception messages can contain authentication headers.
WebSocket setup failures after acceptance are logged as relay failures.
Ordinary independent credentials pass without warnings.
`/activity` adds `refused_injections`, including anonymous and independently authenticated pass-through requests.
It also says whether the proxy can tell its callers apart (#1586), because #1582 went unseen for a year while every check passed.
A refused proof that was checked against an instance's and did not match any of them is `refused_proofs`: the instance's to renew.
One from an address no instance has was never checked: `refused_address_misses`, the proxy's problem.
Both count only requests whose credential looks like a proof, so the host's own traffic is in neither.
`registry_stale` says the last listing failed, or the listing under way has run past a caller's refresh wait (a hung Incus does not throw an error), so refusals meanwhile count in neither: until one succeeds, a failure refuses every proof, and a hang any new or renewed one.
Nor do refusals from a snapshot that may lack the caller's proofs: with no registry yet, after a refresh that timed out or listed nothing new (one coalesced onto a stalled listing gives up at its deadline), or from a stale snapshot, since they are no instance's to renew.
`last_address_miss_at` says when the last address miss was.
`unplaced_running` names the running instances no request has been attributed to; stopped instances and templates own addresses too, so the registry says which are running.
Each instance's `identified_by_proof` says a request of it was named by its proof or secret alone, which only macOS allows.
These stay off `/health`, which containers reach: what the proxy knows of their neighbours is not their business.
`isx doctor` reads them on the host and does not report them while they are zero.
Address misses are reported only while `unplaced_running` names an instance, and the finding names those: a miss cannot say whose it was, and one while every running instance is placed was transient.
They fail only when the last was within ten minutes and no instance has had traffic attributed since, #1582's signature of a proxy that places nobody; otherwise they warn, since the counter never goes down and an idle instance is unplaced too.
Refused proofs and a failed listing warn.
`identified_by_proof` is no finding: on macOS it is how instances are named, and Linux never sets it.
`isx proxy activity` prints the counters under its table, and all of them on its unattributed record.

An ordinary start keeps credential verifiers while replacing the MCP secret.
Migration and changed tool declarations re-read under the instance proof lock before updating verifiers in the secret write.
Before reset writes Incus, it invalidates the old generation with a marker under the host lock directory.
This marker only forces an old prefetched listing to re-read authoritative metadata; it does not grant any access and remains after a failed reset.
Delivery rechecks that marker after boot and holds the proof lock through the guest exec and boot stamp, so reset cannot finish before an older delivery overwrites its replacement.
The normal start derives its seed once and keeps its request budget; the TUI restart passes its listing through instead of reading again.
Every copy clears the generation, complete verifier stamp and boot-delivery stamp in the copy request; branches override them with fresh grants.
`isx account reset-proofs <instance>` creates a new generation and verifier stamp under the same lock and signals the proxy.
It polls the host-only, read-only `/credential-proofs` endpoint for both that generation and the expected verifier fingerprint.
An unavailable or old proxy, a failed listing, or a timeout cannot acknowledge completed revocation.
A running guest then receives its new proofs without changing its MCP secret; failed guest delivery leaves preparation pending.
Stopped instances receive their new proofs on their next start.
Existing authorized requests and WebSockets continue.
Branch, start, restart, VM recovery and recovery after an uninitiated reboot use the same declarations as guest delivery.
Declarations exclude project-local tools (`ToolDefLoader.withoutProjectTools`).
The existing readiness exec delivers the MCP secret, credential seed and proofs into `/run/isx`, owned by root and readable by group 1000.
The credential seed is `/run/isx/credential-secret`, also used by nested inheritance and doctor.
The login profile replaces only declared variables holding an isx placeholder or an earlier proof; user-supplied credentials remain untouched.
A container's boot stamp now ends in `proofs-v4:<generation>`, and the VM probe checks version 4 and `/run/isx/proof-generation`.
An instance from before verifier enforcement therefore receives fresh metadata and proofs on its next preparation through `isx shell` or `isx run`.
Old shells and long-running processes retain stale environment values and need a new login or restart.
`isx doctor` diagnoses missing delivery and drift, and explicitly requests rebuilding an API-key Claude image that lacks the dynamic approval script from #1113.
Static placeholders never authorize injection.

Git does not read `GH_TOKEN`, so guest setup installs a system credential helper scoped to `https://github.com` (`GitHubGitCredential`, #1420).
It answers Git's HTTPS authentication challenge with `x-access-token` and the current proof from `/run/isx/proof-tokens`, including in non-login processes and after an explicit proof reset.
The proxy still requires the matching address and namespace proof before substituting the selected account's real token.
The helper stores no token, ignores store/erase operations, and declines other hosts and protocols.
An explicit `GH_TOKEN` remains intact, and a user's Git configuration can reset the default helper list.
Proof delivery installs the helper during builds and starts; the version-4 delivery marker upgrades running instances on their next `isx shell` or `isx run`.
GitHub tool installation also installs it after packages are available.
Normal lifecycle Incus request budgets are unchanged; tool declarations must be ready before the existing secret write can include verifiers.

Every build receives a registered address, fresh verifiers and proof delivery, including builds using configured defaults without explicit account pins.
Tool environment setup precedes install hooks, Git identity lookup, clones and primes, including tools added by later layers.
A build restart retains its credential proofs while redelivering them.
Successful builds revoke verifiers, reset the managed Claude and Codex credential caches, remove temporary proof files, stop, and release their address.
Failed builds are revoked before being retained for inspection; a later start through isx gives them fresh proofs.
These build paths deliberately pay for source registration and cleanup rather than exempting builds from enforcement.
Project create and update use temporary proofs too, before their repository clones, fetches and pre-build commands.
They preserve inherited account pins and baked-identity checks, and keep their untrusted host-route lineage.
Their cleanup attempts revocation, managed-cache scrubbing, shutdown and address release on both success and failure, continuing after individual cleanup errors and refusing publication on failure.

**Nested isx: nothing is inherited unless its isx says `inherit` (#1110).**
An isx running inside an isx instance has its own proxy, and its instances present their own proofs, derived from secrets the inner isx gave them.
The outer proxy cannot verify those, so a nested machine gets none of the outer credentials.
That is the rule: a nested machine gets no credential unless its isx says `inherit`, or the user passes a token in by hand (`podman run -e GH_TOKEN …`).
`inherit` is a reserved account in every namespace of the inner `config.yaml`, chosen like any other: `isx branch --account github=inherit`, a template's `accounts:`, or `github.default: inherit`.
For an instance resolved to it, the inner proxy does not inject a credential of its own for that namespace.
It replaces each proof the request carries for the namespace with the parent instance's: `ProofToken.derive` over the secret the outer isx gave the parent, under the same prefix and in the same header (`InheritedProof`).
The outer proxy then sees the parent's proof from the parent's address and serves the parent's account, for that namespace only.
Nothing changes on the outer side: no registration of nested instances, no special mode.
Keeping the instance's own carrier, rather than writing the credential the way a configured account would, is what lets Claude work in all three auth modes.
The inner isx does not know which mode the parent uses, and the outer proxy accepts a proof in any of Claude's carriers.
Only a whole proof of a declared prefix is swapped.
A static placeholder is not a proof, and a token the caller supplied stays theirs, so `inherit` never fabricates a credential the instance did not present.
The inner proxy verifies the calling instance's own proof before constructing the swap.
A forged proof-shaped token or a deeper descendant's proof cannot obtain the parent's proof.
The credential seed is read from `/run/isx/credential-secret` off the event loop: with every config read, every second by the config watcher, and in the background by a request that finds it more than a second old.
The parent's seed survives restarts and changes on explicit reset; the inner proxy can retain its previous seed for up to one second after that reset.
Without that file the inner isx is not running nested, and the request fails closed with a 502, as a pin to a missing account does.
`isx branch` refuses `inherit` there up front, a pin or `default: inherit` alike (`CredentialCheck`), and so does `isx account set`.
Nested, an inherited namespace needs no credential in the inner config.
A Claude build for `inherit` takes the API-key form, which the outer proxy turns into whichever account it serves.
The git identity is re-derived through the proxy when a pin moves to or from `inherit`, but nothing here sees the parent switch GitHub accounts: the inner identity then stays the old one until a re-pin or a new branch.
Never falling back matters here as everywhere: the inner default might be anyone's.
The proxy intercepts a domain only if it was told to when the config was read, while a pin to `inherit` can name a namespace the inner config never mentions.
So inside an instance the inner proxy intercepts the domains of every tool declaring a placeholder, with nothing to inject for them.
An instance that does not inherit sends its own token through it unchanged.
Whether the machine is an instance can change while the proxy runs: one started at boot, before the parent's isx delivered its secret, has no secret to read.
The config watcher therefore asks every second whether that changed and reloads when it did.
The alternatives were rejected.
Intercepting those domains everywhere would MITM traffic on every host for tools nobody configured.
Intercepting them only when `default: inherit` names them would leave a per-instance pin silently uncovered.

**Fail closed.**
An instance pinned to an account that has since been renamed or deleted gets an error naming it — never a quiet fall back to the default.
In the case this feature exists for (#351, a subcontractor whose subscription depends on the client), the default belongs to someone else, so falling back is the specific harm to avoid.
`isx doctor` reports instances in that state, without an auto-fix: only the user knows whether the account was renamed or the instance should follow the default.

**Seeing and undoing a selection.**
An instance records only its pins, so "which account is this instance spending?" cannot be read off it: an unpinned namespace follows whatever the default is *now*.
`isx account show` answers it through `AccountUsage`, which resolves every namespace with the same `AccountResolver` the proxy serves from and says in a sentence where each account comes from -- never a bare "pinned" or "default", which leave the reader to guess what would change it.

**Who chose a pin is recorded, not inferred.**
A pin always lives on the instance, whoever chose it: `isx branch` copies the template's `accounts:` onto the branch exactly as it writes an `--account`.
Inferring the chooser from whether the pin matches the template was tried first and is wrong in both directions.
An explicit choice of the template's account reads as the template's, and a template edited since makes its own pin read as an override.
So every pin is written with `user.incus-spawn.account-origin.<ns>` (`AccountOrigin`): `template:<name>` (the build stamps it; branches copy it unchanged), `explicit` (`--account`, `isx account set`, the TUI), or `copied:<instance>` for an explicit choice made on the instance a branch was copied from -- the instance where it was chosen, not every hop since.
`AccountSelection.stampUpdates` writes and clears pin and origin together, so no path can set one without the other.
Pins written before origins existed read as unknown and are reported as such.
The one inference made is at branch time, where an unrecorded pin on the source that equals its template's choice is recorded as the template's, since that is how every earlier template build stamped it.
The template is also compared as it reads *now*, which answers what no record can: a template whose `accounts:` was edited after the instance was branched.
A branch keeps what it was branched with, deliberately -- silently re-pointing running work because a YAML file changed is the wrong default for credentials -- so `show` reports the difference and prints the `isx account set` that would follow it, never suggesting an account that no longer exists.
For a template instance the advice is a rebuild instead, since its pins are what its build stamped.
Explaining a pin needs the description of the account it names, which is `ToolSetup.describeAccount()`: Claude's type, GitHub's commit email, nothing for a tool that declares nothing, never the credential.
`isx account list` shows the same descriptions with the instances pinned to each account, and pins naming an account that is gone.

Pinning the account that happens to be the default is not the same as following the default, and looks identical until the default changes.
`isx account set` says so when it happens, and `isx account unset <instance> <ns>` removes the pin (the key is unset, not blanked) so the instance follows the default again.
`unset` runs the same `incompatibilityReason` check as `set`, with a null account meaning "the default", because the default may be a Claude auth mode the instance was not built for.

**In the TUI.**
`a` on an instance -- in the list or its details -- opens the same choice as a form: per namespace, "default (<account>)" first, then each account.
The refusal check runs while the dialog is open, so a Claude cross-mode swap is explained where it was attempted.
The change itself runs in the background through `InstanceLifecycle.changeAccounts`, the path `isx account set/unset` also take.
So the two front ends cannot drift the way the TUI's branch flow did (#800).
The F3 instance details carry the same "Credential accounts" section `isx account show` prints.

**Choosing accounts when branching in the TUI.**
The branch dialog offers a dropdown per credential the template's tools use (`ToolSetup.credentialNamespaces()`, which derives them from proxy entries -- borrowed ones included, as Copilot's `github.token` -- and which `PiSetup` overrides, since Pi spends Claude's or OpenAI's credential without declaring one) and per credential the inheritance already mentions, but only where there is a choice: two or more usable accounts, or an inherited pin that no longer resolves.
A credential with a single account gets no row.
The first entry is always *inherit*: exactly what `isx branch` without `--account` does, computed by the same `BranchFlow.inheritedAccounts`, and labelled with where it comes from ("as tpl-acme chooses", "global default, follows it").
Everything below it is an explicit pin, recorded as `explicit` like `--account` -- the inherited account included, because pinning the account that happens to be the default is a different choice from following the default.
The pin stays when the default moves, and the entry says so.
Offering it twice under two different meanings is what keeps "let it be" and "pin this" distinguishable without a second control; the closed row shows the account with its source in grey when inherited, and highlighted "pinned" when not.
An account the source was not built for (a different Claude auth mode, `AccountSelection.requiredRebuild`) is listed, greyed, with the reason, rather than hidden, so nobody wonders where their account went -- and cannot be chosen.
Tamboui has no dropdown, only the inline `< >` select, so `BranchAccountChoices` draws its own overlay; Space opens it, since Space toggles every other field and Enter confirms the branch.

**Changing the global default.**
An unpinned instance follows the default as it changes -- deliberately, since switching accounts later is what makes per-instance accounts useful, and pinning everything at branch time would take that away.
The cost is that a one-line edit moves every unpinned instance's principal at once, so the change is made visible and made safe rather than prevented.
*Visible*: `isx init`'s "change the default" (`CredentialSetup.changeDefaultAccount`) lists the instances that follow it (`InstanceRegistry.accountStates`, which, unlike `accountsByInstance`, includes unpinned instances) and offers switch -- the default, since that is what following means -- keep, or cancel.
Keep pins them to the account they use now as an explicit choice, before the default moves, so none is ever served the new one; end of input cancels, never switches.
Removing the default account names its followers the same way.
For a hand edit nothing can ask, so the proxy's `reload()` logs which instances a changed default moved (`logDefaultChanges`), and `isx account list` shows who follows each default.
*Safe*: the two things a default change could break are handled as a pin change is.
GitHub's baked git identity is re-derived for running followers straight away (`refreshIdentities`, the same reconcile `isx account set` runs; stopped ones catch up in `InstancePrep.readyForUse` on their next shell).
A Claude auth mode cannot be re-derived, and `isx account set` refuses such a move while the user chooses.
But a default change moves instances without asking, so the proxy checks instead.
`AccountSelection.servingMismatches` compares the account an instance would be served (its pin, or the default it follows) with its `account-identity` stamp, which the registry now reads, and for a tool that cannot rebake it fails the request closed with a message naming an account the instance was built for -- the same rule as a pin to an account that is gone, never a mismatched credential.
The refusal is scoped to that credential's domains (`namespacesByDomain`/`namespacesForDomain`, from the tools' own proxy declarations, `*.suffix` patterns included), so the instance's GitHub traffic keeps working while Claude is refused.
Results are cached per registry record inside the proxy's `ConfigState`, which each reload replaces whole.
So a request that read the old config and finishes after the reload can only write its stale answer into a cache nothing reads any more.
Only branches count as followers (`accountStates` skips templates and failed builds): a template makes no requests, and pinning one on "keep" would pin every future branch of it.
`isx doctor` and `isx account show` report the same state.
If #866 makes Claude's auth modes interchangeable, `bakedAccountIdentity` returns "" for Claude and all of this goes quiet by itself.

**Accounts that instances still use.**
Removing or renaming an account breaks every instance pinned to it -- fail closed means their next request errors -- and every template whose `accounts:` names it.
`isx init` therefore names those instances and templates before a removal (including "replace all", which removes every account but one) and asks, defaulting to no.
Rename is offered for the same reason: the alternative, remove and re-add, orphans every pin.
`NamespaceAccounts.rename` keeps the account's position in the file (with no usable default, the first usable account in file order serves) via `SpawnConfig.renameConfigKey`.
`AccountSelection.renameInInstances` re-points the pins in one list request plus one PATCH per affected instance -- and, for a tool whose baked identity names the account (GitHub; `ToolSetup.renameBakedIdentity()`), the identity stamp too, so a rename is not mistaken for a change of identity.
GitHub's fingerprint carries over unchanged: it describes the same token, and recomputing it would hide a token replaced before the rename.
Template YAML is the user's file and is reported, never rewritten.
The instance and template lookups are overridable seams on `CredentialSetup`, so `AccountMenuTest` covers these flows without Incus.

**What a build derives from an account, and what happens when it changes.**
Credentials never enter a container, so re-pointing an instance between two accounts that the image cannot tell apart takes effect on its next request with nothing inside restarting.
Two tools *can* tell them apart, and each does so differently.
`ClaudeSetup.envEntries()` writes a different set of variables for each auth mode into `/etc/profile.d/isx-env.sh`, so what it bakes is the **mode** — two accounts of one mode remain interchangeable.
`GhSetup` derives `user.name` and `user.email` from whoever the token belongs to, so what it bakes is the **account** — any change is a change of identity.

`ToolSetup.bakedAccountIdentity()` returns whichever of those applies, or `""` for a namespace whose credential is pure header substitution.
The build stamps it as `user.incus-spawn.account-identity.<namespace>`.
When a re-point makes it stale, the tool is asked to bring the instance in line.
`GhSetup.canRebakeForAccount()` is true, and `rebakeForAccount` clears the git identity and asks the API again — which resolves *through the proxy*, so it answers for the new account without this code knowing which one that is.
`InstanceLifecycle.reconcileAccountIdentities` does this, and only stamps back on success.

GitHub's stamp is the account name plus a fingerprint of its token and configured email (`<account>#<12 hex of SHA-256>`), not the name alone.
The name alone missed the most ordinary change of all (#281).
Replacing an account's token with another user's keeps the name, so nothing was stale.
Every instance -- and every child template, which inherits its parent's `.gitconfig` and whose gh setup skips an identity already present -- went on committing as the previous user while pushing as the new one.
The fingerprint is truncated and one-way, so the stamp never carries the credential.
A stamp from a build that recorded only the name cannot say whether its token has changed since, so it is re-derived once.
Child templates are covered by `BuildAccounts.settleIdentities` (from `BuildCommand`), which re-derives an inherited identity that no longer matches after tool setup (before it, a child adding gh to a parent without it would get its identity written first, and gh's setup, finding a `.gitconfig`, would skip its git defaults).
Otherwise the stamp written at the end of the build would claim the current identity over the parent's `.gitconfig` and hide it from the branch-time reconcile too.
Since every build stamps every namespace, instances without gh carry a GitHub stamp as well; `GhSetup.rebakeForAccount` does nothing where gh is not installed.
It runs from every point a pin can change or first take effect: `isx branch --account` after the instance starts, `isx account set` while it is running, and `InstancePrep.readyForUse` on the next shell, from `isx shell`, `isx run` or the TUI, after the other `fix*` reconcilers.
The TUI's shell skipped it until #907, so an account changed on a stopped instance and opened from the TUI pushed as the new account and committed as the old one.
Reconciling only in `InstancePrep` was not enough.
The token swaps on the next request, but the identity is baked, so an instance driven over `incus exec`, SSH or an IDE would push as one account and commit as another until someone happened to open a shell.
A tool that cannot re-derive — Claude, whose variables a running agent has already read — has the swap refused at selection time instead, while the user is still choosing.

**An identity that was never baked is reconciled too.** gh's setup derives the git identity only when an account can supply one.
A template built while no GitHub token was configured -- an `isx init` whose GitHub step was skipped -- gets gh's `.gitconfig` defaults and no `[user]`, so every commit in every branch was unattributed.
It used to happen silently and nothing repaired it: with no account, `bakedAccountIdentity` is `""`, no stamp was written, and an absent stamp reads as "nothing baked, nothing to reconcile".
Worse, stamps were computed from config.yaml, not from the guest: a child built once a token existed, on such a parent, was stamped with an identity its `.gitconfig` never got, and the reconcile trusted it.

So the stamp is now decided by the guest.
After tool setup, `BuildAccounts.settleIdentities` (called from `BuildCommand`) asks each tool in the template's chain that owns a namespace and can re-derive (`AccountSelection.rederivableNamespaces`: gh itself, not Copilot, which borrows the GitHub credential but does not derive an identity; never Claude, where a stamp the account does not match would refuse the account; only namespaces `namespaceSetups` knows, so a gated-out one is never marked) whether its identity is missing (`ToolSetup.lacksBakedIdentity`, one exec for gh).
Missing, with an account to derive from, it is re-derived (`BuildAccounts.toRederive`, which shares `AccountSelection.needsRederive` with the branch-time reconcile, so the two cannot drift) -- whether the parent was stamped `<none>`, unstamped by an older isx, or stamped with an identity it never got.
A failure fails the build, as for a fresh template.
Missing with no account, the build warns (`ToolSetup.unbakedIdentityWarning`, whichever layer installed gh; it names the account when the template uses one that has no token, since "no token is configured" would be untrue while the default has one) and stamps `Metadata.ACCOUNT_IDENTITY_NONE` (`<none>`) over whatever the parent claimed.
A parent that has an identity but no stamp -- built before stamps existed -- keeps it, so a GitHub outage cannot fail a child build that never needed the API.
A token-less account bakes `""`, not its name, so it is never stale against the marker.

The marker differs from every account's identity, so once a token is configured it is an ordinary stale identity.
The branch-time reconcile re-derives it and stamps the real one.
While there is still no token the namespace does not bake an identity and is left alone rather than failing on every start.
A real value rather than an absent key, because absence is what an image without gh looks like, and telling the two apart at branch time would cost a guest exec on every branch.

Every template this isx builds is also stamped `Metadata.ACCOUNT_IDENTITY_VERIFIED`, which every branch inherits: its stamps came from the guest and are trusted, and its reconcile costs one instance read and no exec (`InstanceLifecycleRequestBudgetTest`).
An instance without it comes from a template an older isx built, so its stamps -- or their absence -- may lie.
`AccountSelection.identityReconcile` lists its re-derivable namespaces with a configured account as `unverified`.
`InstanceLifecycle.reconcileAccountIdentities` asks the guest once, re-derives what is missing and sets the marker: one exec and one write, once per such instance, and nothing at all while no account is configured.
One that has its identity is stamped with the account's, so a later change of account or token is still reconciled; marked without a stamp, it would be skipped for good.
The marker is written by any reconcile of an unmarked instance that had something to do, so one whose stamp was merely stale is marked once re-derived.
Only a built-in tool can re-derive (it takes Java code a tool YAML cannot supply).
So the plan is decided against those (`AccountSelection.rederivableSetups`) and the tool definitions are read from disk only when it finds something, to drop a namespace the proxy does not serve.
An instance with nothing to do -- verified and current, `<none>` while there is still no token, or an older one while no account is configured, which is never marked and so would pay on every use -- does not read anything from disk.
That repairs templates already built without an identity, and their branches, without a rebuild.

The guest is asked before waiting for the instance's address, which only re-deriving needs, so an older instance that has its identity is marked without that wait.
The marker goes on the instance, not its template, so until an older template is rebuilt every new branch of it pays the exec and the write on its first use.
Marking the template from a branch's answer was rejected.
The template is stopped, so it cannot be asked.
A branch's `.gitconfig` may have changed since the copy, so a marker written from it would be trusted by every later branch.
An airgapped instance is never reconciled: it has no proxy to re-derive through, and it never gets the address the re-derive waits for, so every `isx shell` in one would wait 30 s and warn.
`BranchFlow` already skipped it; `AccountSelection.identityReconcile` now returns nothing for it, which covers `InstancePrep`, `isx account set` and init's refresh alike.
`InstancePrep` and `BranchFlow` hand over the read they already made (for `ensureReady`, and the prefetch before the start), so on the shell and branch paths the reconcile itself costs no request.

What the guest check does not catch: it asks whether an identity is *missing*, never whose it is.
A parent built before stamps existed, holding account A's identity and no stamp, yields a child built for account B that is stamped B and verified while its `.gitconfig` still holds A's.
The build finds an identity, and with no stamp to compare there is nothing stale.
Before the marker that stamp was equally trusted -- an unstamped parent was never re-derived -- but the marker makes it permanent.
The same holds for an older instance with an identity and no stamp, which the reconcile stamps with its account's identity.
Rebuilding the parent fixes it.

That asymmetry is the point.
Refusing is the fallback for what cannot be fixed, not the general rule: GitHub accounts are freely swappable on a built instance precisely because the thing it bakes can be recomputed.
Moving git identity out of template-build time is also what closes the older complaint that every branch of a template shares one GitHub identity (#281).

The deeper fix would be to stop telling the container which mode it is in at all.
`handleApiRequest` already branches on the request *path*, not on how the container was built, and `VertexBodyRewriter` already converts a standard `/v1/messages` call into a Vertex `rawPredict`.
So dropping `CLAUDE_CODE_USE_VERTEX` and always baking the standard placeholder would make all three Claude types environmentally identical and every swap live.
The open risks are Vertex model-ID mapping and the non-messages endpoints that currently fall through to `api.anthropic.com`; both want measuring against a real Vertex project rather than arguing about.

## The help assistant (`isx ask`)

**AI help credentials (`isx ask` / `?` in the TUI):** `AiHelpClient.targets()` lists the accounts satisfying `ClaudeAccount::servesDirectApi` (the default account first), then `openai`; the first is the one used by default.
An `oauth` account never satisfies it.
A Claude Pro/Max token is only valid for Claude Code itself.
The Messages API rejects it unless `system` is a block array whose *first* block is Claude Code's own identity string — rejecting it with an opaque HTTP 429 `rate_limit_error` whose message is the literal text `Error`, so the failure reads as a generic API error rather than an auth hint.
Satisfying that check would mean isx presenting itself as Claude Code to spend the user's subscription, so a Pro/Max account simply keeps serving instances while an `api-key` or `vertex` account answers here.
Max and Team plans include monthly API credits for a key, so that second account need not cost a subscriber extra (#1674).
So when `isx init` saves an `oauth` account and nothing can answer here, `CredentialSetup.offerApiKeyForAiHelp` names the credits and offers to add an `api-key` account beside it, leaving the subscription the default.
This is the one place isx calls a model API on its own behalf; everywhere else (`ProxyCredentials`, `ClaudeSetup`, `PiSetup`) credentials are only forwarded into instances.
Because providers answer errors with varying usefulness, `describeError()` reports the HTTP status, the error `type` and the `request_id` alongside the message.
A stream that ends in a refusal, at `max_tokens` or at the context window reads like a finished answer, so `processStream()` returns a note saying which (`StreamEnd`).
The note is isx's word, not the model's, so it never joins the answer: `isx ask` warns with it on stderr, keeping stdout to what the model wrote, and the TUI shows it under the answer in the warning colour.
Every answer also carries `AiHelpClient.DISCLAIMER`, that it is AI-generated and to check it against the docs, by the same rule: `isx ask` prints it as a note on stderr, and the TUI shows it in the footer below the answer, outside its scrolling.
Measured on #1675, both Sonnet 5.5 and Haiku 5.5 sometimes state commands or behaviour the docs do not support, so the prompt also asks every answer to end with a `Sources:` list of the sections it relies on.
A reader can follow those to check it, and DESIGN.md and its `design/` pages, which have no page on the site, are cited by file and heading.
It may cite only headings that appear verbatim in the bundled files, and writes no `Sources:` line when none supports the answer, since a guessed source is the failure it is meant to expose.
The prompt states the site's anchor rule as `site/render.js` applies it, so `#### Fedora / RHEL` links to `#fedora-rhel`, and the `--with-templates` definitions, which have no page, are never cited.
`isx ask` always uses that first one.
But the TUI's AI Help dialog makes the account explicit, offering every target as a selector -- a fixed line when there is only one -- because which credentials are billed should be visible rather than implied.
`subscriptionAccounts()` names the Pro/Max accounts so the dialog can show them as unavailable with the reason, instead of leaving a subscriber to wonder why their account is missing.
The optional template and tool definitions are presented as an *attachment* with their measured size.
The word itself says they leave the machine with the question, which matters because they include the user's own YAML files.
User and search-path tool YAML comes from `ToolDefLoader.userAndSearchPathToolDirs()`, the loader's own list of its layers, so the attachment lists the same directories the loader reads (#1173).
That list leaves out a project's `.incus-spawn/tools/` because the flag is named `--with-templates`; it may later be widened to project tools.
A project's templates' names, descriptions and tool lists do go in, through `ImageDef.loadAll`: the flag asks for templates, all of them (#1454).
Both lines follow from what the flag names, not from how far a layer is trusted: the help assistant has no tools, so a project's text in its prompt cannot act; it can shape an answer, commands and links included, which the user still reads before acting on it.
`HelpContext.definitions()` is built once per opening of the dialog, so the size shown is exactly what gets sent.
**Prompt caching:** every question carries the whole README, the `docs/` pages, DESIGN.md and its `design/` pages (~990 KB, the bulk of the cost; kept because answers are markedly less complete without the design rationale).
So `HelpContext.systemBlocks()` returns the prompt as two blocks -- the fixed documentation, then the attached definitions -- and `AiHelpClient.cachedSystem()` puts a 5-minute `cache_control` breakpoint on each for Anthropic and Vertex.
A follow-up question within five minutes then reads the documentation from the cache at 0.05x the input price (the first question pays 1.25x to write it; OpenAI caches the same stable prefix without being asked).
Both Claude targets pin `claude-sonnet-5-5`, whose cache reads cost half Sonnet 5's at the same input and output prices (#1675).
Both also ask at `output_config.effort: low` (`AiHelpClient.EFFORT`): at its default, `high`, Sonnet 5.5 thinks before every answer.
Measured on #1675, `low` reached the first text in 2.6 s at the median, against 7.4 s at the default and 2.8 s for Haiku 5.5.
Its answers stayed about as accurate as at the default, while Haiku invented a claim in most of its answers.
The Claude API and Vertex name it alike, but each has its own constant because the two have diverged before.
Caches are scoped per model, so a change of pin pays one fresh write.
The attachment has its own breakpoint so ticking it still reuses the cached documentation, and its content is sorted so it is byte-identical between questions.
Anything that varies per request must never enter the first block: a single changed byte turns every question back into a full-price write, and nothing fails -- the bill just rises.
The AI Help answer view shows the provider-reported token split (`51.2k tokens in (51.2k from cache) · 312 out`) as the visible check that caching still works.
The answer is wrapped by the terminal cells it takes (`SegmentWrap`, #1412): a CJK ideograph or an emoji takes two, and a break never splits a grapheme cluster, so wide text is neither clipped nor split into lone surrogates.
It lives in `cli` rather than beside `TerminalLink` in `common`, since only the TUI wraps and only the CLI depends on TamboUI's `CharWidth`.
