# Network

How instances reach the network, and how their names resolve.

## Network Modes

Branches run in one of three network modes, selectable via CLI flags or the TUI branch modal:

**Full internet** (default): Container stays on the `incusbr0` bridge with NAT masquerading and a static IP assigned at branch time.
Unrestricted outbound access to the internet.
Traffic to intercepted domains (Anthropic, GitHub) is transparently authenticated by the host MITM proxy — credentials never enter the container in any form.

**Proxy only** (`--proxy-only`): The instance stays on the bridge, and its egress can reach only the gateway's MITM proxy (port 443), the proxy's health check (18080), DNS and a ping of the gateway, so it reaches the intercepted domains through the proxy and nothing else.
The restriction must hold against the code it restricts, which has root in the guest, so it lives on the host (#1137).
It is an Incus network ACL, `isx-proxy-only`, on the instance's bridge NIC (`ProxyOnlyAcl`):

```
security.acls=isx-proxy-only
security.acls.default.egress.action=drop     # anything the ACL does not allow, IPv6 included
security.acls.default.ingress.action=allow   # the host reaches the instance over SSH
```

The ACL's egress rules allow, to the gateway's address only: tcp 443, 18443 and 18080, udp and tcp 53, and ICMP echo (the connectivity watchdog pings the gateway).
DNS over TCP is wider than the in-guest rules, which allowed udp 53 only, but no NIC ACL can take it away: Incus puts its own `udp dport 53` and `tcp dport 53 accept` to the gateway in the same chains ahead of the ACL's rules, so the ACL lists both to say what is in force.
That is accepted: DNS reaches only the gateway's resolver either way, and blocking TCP would take a host nftables table isx owns, the state the ACL exists to avoid.
A proxy-only instance has one network device, the bridge NIC that carries the ACL: any other NIC, InfiniBand device or `proxy` device bound in the instance (`IncusClient.isNetworkDevice`), its own or from a profile, would reach the network directly.
A `proxy` with `bind: instance` listens in the guest and connects from the host, where no NIC ACL sees it (#1518); one bound on the host, the default, only reaches into the instance and stays, as does one that connects to a host Unix socket (an X11 or audio forward), which reaches that socket only.
A branch whose source has one (an airgapped source's, as unmasking would bring it back) is refused before anything is created, and isx refuses to start or use an instance that has gained one.
`pci` and `usb` passthrough devices are refused the same way, by both proxy-only and airgap (`InstanceNetwork.passthroughRefusal`, #1518): either can be a network card, and isx cannot tell one from a GPU or a security key.
The refusal names the device and says to pass a GPU as a `gpu` device, which Incus accepts only for a GPU and which isx leaves alone, as it does its own GUI passthrough's.
Until #1518 a `pci` device was accepted on proxy-only because isx never adds one, but templates and profiles, which a branch copies, can.
Masking them, as airgap masks NICs, was the alternative: it would take away hardware the user attached on purpose without saying so, and the loss would show only later, as a GPU that is not there.
Warning only would leave the mode named while a way out stays open.
A start outside isx (`incus start`, autostart) cannot be refused and starts it with that NIC unrestricted.
Incus installs it as per-NIC chains in the host's nftables `bridge` family, the table that also enforces `security.ipv4_filtering`, at every start of the instance, whoever starts it: `isx`, `incus restart`, autostart after a host reboot, `isx doctor`'s subnet move.
A start whose ACL cannot be installed fails.
Those chains match in the bridge family, which on most hosts is before the host's PREROUTING redirect, so they see port 443 rather than the proxy's 18443.
On a host with bridge netfilter on (`net.bridge.bridge-nf-call-iptables=1`, which Docker and Kubernetes set), iptables runs during the bridge's prerouting, so the redirect comes first and the chains see 18443 (#1503).
The ACL allows both ports, which makes nothing new reachable, since every connection to 443 already lands on that socket; isx neither refuses such a host nor asks for the sysctl off, which Docker's own bridge filtering needs.
The appliance still turns it off: its DNAT goes to the host's proxy, not the gateway's 18443 (`appliance/DESIGN.md`).
A rule set there also stops traffic to other instances on the bridge, which a rule on the host's INPUT chain would not see.

*Where it is set.*
`BranchFlow.preflight` writes the ACL for the current gateway before anything is created (one PUT, a POST the first time).
The copy request itself carries the three NIC keys and the `network-mode` stamp (#2009).
So a branch killed before its configuration, which runs no cleanup, leaves a copy that starts restricted, whether isx starts it or Incus does.
`configureBranch` writes the keys again in the branch's one write, before its first start, so a `--no-start` branch is restricted from the moment it exists (#1311).
A branch with network of a proxy-only instance drops them in that write.
A subnet move rewrites the ACL when it moves a proxy-only instance's gateway (`InstanceNetwork.fixStaticIp`).
Until then the ACL allows only the old gateway, so the instance reaches nothing.
Every start for use (`prepareForStart`), `isx shell` into a running instance (`ensureReady`) and the TUI's restart put the keys on a proxy-only instance that lacks them: one branched before #1137 is restricted from the first time isx sees it.
An instance that has them costs no request.
That repair writes the ACL for the bridge's current gateway, never the one the instance recorded: the ACL is every proxy-only instance's, and a record from before a subnet move would take the proxy away from all of them.
A proxy-only instance whose NIC is on another network is not started or used, since nothing restricts it there; one with no NIC at all has no network, which is within the mode.
A branch whose source's only network is off the bridge is refused the same way before its copy (#2016), since a copy left by an interrupted branch would have direct network on a plain `incus start`.
Two isx processes creating the ACL at once both succeed: a POST answered "already exists" is followed by the PUT.
`isx reset` deletes the ACL, which Incus refuses while an instance that was kept still uses it.

*Fail closed.*
Incus accepts NIC ACLs only from its nftables firewall driver, and rejects them under xtables.
It accepts them on a bridged NIC only with the `network_bridge_acl_devices` API extension, which Incus 6.9 and 6.0.4 added and Ubuntu 24.04's own 6.0.0 lacks (#1979).
Preflight reads both from one `GET /1.0` (`IncusClient.networkFiltering`).
On such a host `isx branch --proxy-only` is refused before anything is created, saying why and what to change, and an existing proxy-only instance is not started or shelled into.
Incus itself refuses every write to and start of an instance whose NIC names an ACL once it runs xtables (the host gained legacy rules after the branch); isx asks for the driver only after such a refusal (`ProxyOnlyAcl.explaining`), so an instance already restricted costs its start no request, and reports the same reason instead of a failed write.
A proxy-only branch that fails after its copy, for any reason, deletes the copy (`BranchFlow.removeFailedCopy`): it is restricted, but may not be fully configured, under the name chosen for a working one.
An Incus that refuses the NIC keys after preflight refuses the copy request, so no copy exists.
A copy that cannot be deleted is named in a warning.
Branches in the other modes keep a failed copy, listed as an instance (#1036).
There is no fallback to rules in the guest: they would make the mode look in force while root in the guest can remove it, and they are why images needed `iptables` (#1312).
The IP spoofing fallback (`startInstance`, below) never strips a NIC that carries the ACL keys.
A failure of the ACL means the instance cannot start, so the fallback could only turn a refused start into an unrestricted one.

*History.*
Until #1137 the rules were iptables OUTPUT rules applied in the guest by an exec after the first start.
The instance's root could flush them, and nothing applied them again after a restart (#1311).
The exec also needed `iptables` in the image, which `tpl-minimal`'s base image lacks.
Since #1136 their failure removed the branch.
The alternatives to the ACL were re-applying the guest rules on every start, and an isx-owned host nftables table keyed on each branch's address.
Root in the guest could still flush the first, and an `incus restart` or autostart is a start isx never sees.
The second needs root on the host for every branch and a boot-time restore ordered before Incus, and there is no host nftables on macOS outside the appliance.
Incus already does all of that for its own per-NIC rules.

**Airgapped** (`--airgap`): Every network device, each NIC and InfiniBand device (which reaches a network without any NIC, #1507), is masked with a `type: none` device of the same name, and the instance is stamped `network-mode=AIRGAP` and `airgap-masked=<the masked devices>`.
No network device, and no way out that isx can see in the instance's config; what it cannot see is listed below.
Masking is the only way: Incus cannot remove a profile device from one instance, and removing an instance device that overrides one only drops the override, so the profile's device applies again.
That is how airgap branches kept full network access until #813.
A branch with network from an airgapped instance writes the profiles' network devices back over the masks named in `airgap-masked`, and no other: a `type: none` the template set itself over a profile device is its own choice and stays.
An instance airgapped before that stamp existed masked NICs only, so without the stamp only masks over a profile NIC are lifted.
Airgapping an airgapped instance keeps its source's names in the stamp, since the copy carries those masks.
A `proxy` device with `bind: instance` is a network device here too, masked and put back like a NIC (#1518).
An airgapped branch of a source with a `pci` or `usb` device is refused, as on proxy-only above.
The copy request itself carries the masks and both stamps (#2014), as a proxy-only copy carries its ACL.
So a branch killed before its configuration, which runs no cleanup, leaves a copy with no network device, whether isx starts it or Incus does.
`configureBranch` masks the copy again in the branch's one write (`InstanceNetwork.masks`), which then masks only what it gained since preflight.
Masks are written at branch time, and a profile that gains a network device later attaches it at the next start, so every start for use (`prepareForStart`), `isx shell` into a running instance and the TUI's restart mask what the instance gained, add it to the stamp and say so (`AirgapGuard.enforce`).
This also masks the InfiniBand devices of an instance airgapped before #1507.
The TUI restarts an airgapped instance as a stop and a start for use, reading it after the stop, so the mask is written while it is down; `isx shell` into a running instance masks it live, and a device Incus will not take from a running guest refuses that shell, with the advice to stop it.
An instance that gained passthrough hardware is refused instead, and one that gained nothing costs no request.
As with proxy-only, a start outside isx is not checked.
Other ways out are not covered by either mode: `raw.qemu` or `raw.lxc` (a QEMU `-netdev user`), `unix-char` and `unix-hotplug` devices such as a serial modem, and a `disk` device sharing a host socket that can open connections.

## DNS: static resolv.conf + bridge dnsmasq
systemd-resolved (127.0.0.53) doesn't work reliably inside Incus containers because it expects to manage the network configuration.
We disable it, point `/etc/resolv.conf` directly at the Incus bridge gateway (which runs dnsmasq), and make the file immutable with `chattr +i`.
This is less flexible than systemd-resolved (no per-link DNS, no DNSSEC validation) but works reliably across container restarts and network changes.
Domain interception for the MITM proxy is configured at the bridge level via `raw.dnsmasq` (dnsmasq `address=` directives), not via per-container `/etc/hosts`.
This avoids a class of bugs where Incus overwrites `/etc/hosts` on container start.
Each intercepted domain gets two lines: `address=/<domain>/<gateway>` for A, and `local=/<domain>/`, which makes dnsmasq answer every other record type for it locally with no data.
Without the `local=` line dnsmasq forwards AAAA (and HTTPS/SVCB, which can carry address hints) upstream, and the domain's real IPv6 addresses bypass the proxy.
Released versions closed that hole with `address=/<domain>/::`, but `::` is the wildcard address, which clients treat as loopback.
A nested isx refused every intercepted domain as host-local (`DownloadCache` rejects a host if any resolved address is loopback, wildcard or link-local), and an IPv6-preferring client would connect to itself (#814).
The `address=` + `local=` pair gives NODATA for AAAA on every dnsmasq from 2.80 to 2.92.
The lines sit between `# BEGIN incus-spawn intercepted domains` and `# END incus-spawn intercepted domains` markers (`BridgeDns`), like the UFW blocks, so a rewrite replaces exactly what isx wrote and keeps every user line.
Inferring ownership from line shapes is not safe with this layout: a `local=/<domain>/` left behind without its `address=` partner answers NXDOMAIN for the whole domain.
The pre-block layout is recognised by its `address=/<domain>/::` lines, which every released version wrote, and migrated.
A downgrade to such a release keeps the block's `local=` lines as user lines, so a domain that release stops intercepting stays NXDOMAIN until isx is upgraded again, and the upgrade then rewrites everything cleanly.
`isBridgeDnsComplete()` and `isx doctor` report domains missing from the block, domains whose `address=` line points at another address than the bridge's current gateway, and domains still answered with `::` separately.
An override for a gateway the bridge has since given up counts as wrong, not as configured: until the next proxy start rewrites the block, it sends the domain where the proxy does not listen (#840).
Overrides that all point there fail doctor like having none; both values come from one read of the bridge (`ProxyConfig.bridgeDnsStatus()`).
Having no overrides at all is not complete: instances then resolve intercepted domains to their real addresses and bypass the proxy, so while the proxy is up `isx doctor` fails that case (#839).
A failed read of `raw.dnsmasq` is reported as "could not check", never as complete (`readDnsOverrides()` throws; only `getDnsOverrides()`, for callers looking for overrides to act on, turns it into `""`).
Incus applies a new `raw.dnsmasq` by restarting the bridge's dnsmasq, and a dnsmasq that does not stay up makes it refuse the update and revert the network; `isx init`'s DNS step writes again up to three times, 2s apart, before failing, and a refused read or write of a network's config carries Incus's reason (#1376).
Doctor offers to write the overrides only while the proxy is listening.
With it down, they would send every intercepted domain to a gateway where nothing answers (`STALE_DNS`), which is worse than bypassing it, so doctor leaves the "Proxy running" finding to carry the problem.
A proxy that outlived a change of the bridge's address is still running, bound to the old gateway, and its block still points there.
Probing the new gateway finds nothing and probing the old one cannot help either: once the bridge drops the address nothing answers on it, though the socket bound to it lives on.
So when the probe fails and the block points elsewhere, `ProxyHealthCheck` looks for a listener on the health port at that address in `/proc/net/tcp` and `tcp6`, and, when the host no longer has that address, reports `STALE_GATEWAY` (restart the proxy) instead of `STALE_DNS` (start it) (#919).
An address the host still has is a proxy started there with `--gateway-ip` while the bridge had no address to read, which a restart would not move (#892).
The automatic restart before a command, and the TUI's, restart the service for `STALE_GATEWAY` only when the service is active.
When the stale proxy runs in the foreground, starting the service would put a second proxy beside it.
The user is shown the restart advice instead.
It reads two local files, never the network, and only after the probe has already failed, so a healthy proxy pays nothing.
Doctor does not rewrite the block in that state either: the current gateway is where nothing listens.
The proxy also heals itself when the bridge moves (Linux) (#966).
Every 60 s, and before a reload rewrites the block, it reads the bridge's address again (`ProxyMain.Addresses.movedTo()`).
When the bridge now has another address, it does not write the block, which would point every intercepted domain back at an address nothing reaches.
Instead it stops and exits 1, and the unit's `Restart=on-failure` starts it again, and startup binds to the new address and writes it into the block.
Rebinding in place was the alternative, rejected because it would need the listener, the health endpoint and the block moved together, which is exactly what startup already does.
A foreground proxy just exits with the reason in its log.
A bridge that cannot be read, or has no address, is not a move: a restart would find nothing better, and the gateway `isx init` cached is not the bridge's word.
A `--gateway-ip` is the bridge's address too (see below), so it is compared the same way.
On macOS the gateway is the VM link, which the Incus bridge does not move.
One Incus request a minute is the cost; it is not on any branch, start or shell path.
On Linux the proxy points the block at the gateway it resolved at startup and listens on, which `--gateway-ip` can set, passing it in rather than reading the bridge again.
A second read ignored the override and failed outright on a bridge with no address, leaving DNS unconfigured (#892).
The override is written into dnsmasq verbatim, so it must be a unicast IPv4 address in canonical dotted-quad form, and anything else is a configuration error (`EXIT_CONFIG`).
A hostname, IPv6, link-local, multicast, broadcast or wildcard address would become every intercepted domain's answer, and most of them break the bridge's dnsmasq outright.
A shorthand such as `10.1`, or an IPv4-mapped `::ffff:10.99.0.1`, parses as the right address in Java, but dnsmasq would not read it the same way: the mapped form becomes an AAAA answer, leaving intercepted domains with no A record.
The override is also the bind address of the proxy listener; credential injection additionally requires a registered source address and its current namespace proof.
The proxy serves the bridge gateway and nothing else, because that is where every `isx` command looks for it: health checks, auto-restart, `isx list`, doctor and the CLI's bridge DNS writers all resolve the address with `ProxyConfig.resolveGatewayIp()`, the bridge's gateway or, when the bridge cannot be read, the one `isx init` cached.
A proxy anywhere else was reported down, restarted beside itself, and had its DNS block pointed back at the bridge (#933).
So on Linux, when Incus reports the bridge's address, the override must equal it (`BridgeAddress.read()`, never the cached one), and anything else is `EXIT_CONFIG`.
The override's one use is the bridge's address when Incus cannot report it (#892).
Then it must equal the gateway `isx init` cached, if there is one, else `EXIT_CONFIG`.
As the credential listener's bind address it must also be private (RFC 1918) or loopback, since nothing vouches for it: a cached gateway could be an address the bridge has since given up (#1022).
A routable override that passes the cache comparison stops the proxy with exit 1, as it does without an override when the bridge cannot be read.
Loopback is accepted though, as an A record, it sends instances to themselves: it exposes nothing.
Recording a differing override where the CLI could read it (a `proxy-gateway-ip` key in `config.yaml`, used by the health address, the DNS writers, init's PREROUTING redirect and the `PROXY_GATEWAY` stamp) was the alternative, rejected because nobody needs a proxy away from the bridge and no such configuration ever worked on Linux: the redirect and the proxy-only ACL both target the bridge gateway.
It is the path to take if a second bridge or a dedicated proxy interface ever becomes a real request.
Saving `isx proxy start --gateway-ip` into `config.yaml` was rejected too: a command must not rewrite the user's secrets-bearing file to remember an option.
On macOS the address the proxy listens on is the host's end of the VM link, so the block, which dnsmasq serves inside the VM, still points at the VM's own bridge gateway.
There `--gateway-ip` is not written anywhere but it is the bind address of the credential-injecting listener, and a caller the proxy cannot place by address is identified by its credential proof alone (#1582), or for `isx mcp` and host routes by its per-start secret alone (#1583).
So an override of `0.0.0.0` or a LAN address would let any host that obtained an instance's proof spend that instance's credentials, or with its secret act as that instance.
On macOS the override must therefore be exactly the host's end of the VM link that `VmNetwork.discoverHostBridgeIp()` finds, and anything else is `EXIT_CONFIG`.
When no VM bridge is found the override cannot be checked and the proxy stops as it would without one (#937).
Refusing only wildcard and public addresses was the alternative, rejected because a private LAN address is just as reachable from other hosts.
The TUI's auto-heal and doctor's remediation rewrite the block from `ToolProxyResolver.resolvedDomains()`, which resolves across all accounts exactly as the proxy does: the default account's narrower set would drop a domain that only a named account can serve.
Every CLI writer of the block (`isx init`'s DNS step, the macOS service install, the TUI's auto-heal and doctor's remediation) takes its set from `ProxyConfig.bridgeDnsDomains()`, the built-in domains plus that resolution.
There is no writer that takes no domains, and an empty set is refused rather than read as the built-in ones (#1354).
Init's DNS step once wrote the built-in domains alone, just after the proxy's reload had written them all.
Nothing wrote the tool domains back until the proxy restarted, so builds reached GitHub with the placeholder token and got 401.
