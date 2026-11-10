# Testing

How isx is tested without and with a real Incus.

**Unit tests** (`mvn test`, no Incus needed):
- `ToolDefTest` — YAML tool parsing, fingerprinting, composite fingerprints with transitive dependencies
- `ToolDefLoaderTest` — resolution order (builtins, user overrides, unknown tools)
- `YamlToolSetupTest` — execution order with mocked Container
- `ImageDefTest` — image definition loading, parent chain, descriptions, fingerprinting
- `BuildCommandTest` — `.claude.json` trust configuration, skill deduplication across inheritance chains, agent context collection (notes root-first, ancestor repos), shell quoting, GitHub URL parsing
- `AgentContextGeneratorTest` — generated agent instructions content: preamble, empty-section omission, note ordering, heredoc-marker neutralization
- `GitRemoteUtilsTest` — URL normalization (SSH/HTTPS/case), protocol-lenient matching, reference device naming (hash-based, truncation, collision resistance), host repo matching across multiple remotes
- `IncusApiTest` — REST API request/response parsing, exec body format, default exec environment, LOGIN_PATH_PREFIX
- `InstanceLifecycleRequestBudgetTest` — exact Incus round-trip counts for the start/shell/branch flows (see "Request budgets" below)
- `ActionResolverRequestBudgetTest` — the same for building an action context, paid before every `isx run` action
- `HelpChatModalTest` — the TUI's AI Help dialog, rendered headlessly and compared against golden text snapshots (see below), plus its key handling

CI runs them on Linux (`unit-tests`) and on an arm64 macOS runner (`unit-tests-macos`) on every PR.
A test that leans on GNU tools or Linux kernel behaviour fails on a Mac, and with only release builds on macOS, eight such tests failed on every Mac without CI noticing (#970).
A test that genuinely needs Linux skips with `assumeTrue(Platform.isLinux())` and a reason.
Intel Macs are not covered: arm64 is what Mac users run, and macOS runners are scarce and, where billed, cost several times Linux ones (#974).

**The Git tests are paced by process starts on macOS.**
`GitPreparationTest` ran some 4,500 Git processes, which came to about 27 ms each on a macOS runner, 83 ms on a slow one and 8 ms on Linux.
On the slow runner it took 373 s instead of 122 s, and `unit-tests-macos` overran its 20-minute timeout with every test passed (#1839).
So `GitTestUtils.releasesFixture` builds its repository once per JVM and copies it, which saves fifteen Git processes per test.
`GitPreparationTest` also runs its tests concurrently (`@Execution(CONCURRENT)`): each owns its directories, cache root and Git server, and the process-wide lease and lock maps it reaches are keyed by path.
The methods carry the annotation, not the class: a concurrent class runs beside every other class in the module.
Its Git processes then inherit stub scripts other tests are still writing, and a stub's exec fails with ETXTBSY (#1846).
Under dash that failure is silent, since the shell runs the host's command of the same name instead.
`ConcurrentTestClassesTest` refuses a class-level `@Execution(CONCURRENT)` in `common`.
`common`'s surefire configuration enables JUnit's parallel execution for that, with every other class still running one test at a time.
`GitTestServer` holds its listening socket and serves each connection with `git daemon --inetd`, since a port released for a daemon to bind could be taken by a concurrent test first.
Four run at a time, and on three CPUs the class takes 10 s instead of 36 s, so the timeout stays at 20 minutes.
A class added to the concurrent set must share no state between its tests: no bound `Output`, system property or fixed port.

**A docs-only change runs the docs gates** (#1960).
`test-integration.yml` ignores a change to Markdown, `docs/`, `site/` or `.claude/` alone, which keeps its Incus jobs off such a change.
So the tests guarding those files would not run on such a change, and one breaking them would merge green.
The next code PR would then fail on a test it did not touch.
`docs-gates.yml` runs on exactly those paths and runs only the tests tagged `docs`, in `common` and `cli`, in well under a minute.
A test that reads the repository's own docs carries `@Tag("docs")`, on its class or on the methods that do.
`DocsGatesWorkflowTest` holds both halves: the paths equal `test-integration.yml`'s `paths-ignore`, and a test source naming a repository doc is tagged.

**An artifact upload that other jobs need is retried once.**
A hosted runner now and then times out reaching GitHub's artifact service (`CreateArtifact: ETIMEDOUT`), failing a job whose build had passed, and every job downloading its artifact with it (#1672).
`actions/upload-artifact` has no retry input, and its client does not retry a network error such as `ETIMEDOUT`.
So deliverable uploads go through the local composite action `.github/actions/upload-artifact-retry`.
Its first attempt continues on error, and a second one with `overwrite: true` runs only when it failed, 15 seconds later, replacing any partial artifact under that name.
The second does not continue on error, so two failures still fail the job, and one timeout costs a retry rather than a re-run.
A local action keeps the pair in one place with no third-party dependency in the release path.
Diagnostic uploads keep a single attempt, since nothing downloads them.
`WorkflowArtifactUploadRetryTest` checks both the action and that no deliverable upload bypasses it.

**A runner installs packages within a time bound.**
A hosted Ubuntu runner reads its archive from a mirror list, Azure's mirror first.
One afternoon that mirror crawled at about 90 kB/s, and every apt step took 4 to 19 minutes with no output; `macos-role` ran into its 20-minute timeout with no test run (#1993).
apt's own `Acquire::http::Timeout` cannot help, since a slow mirror still sends data.
So workflows install through `.github/scripts/apt-install.sh`, which bounds the index update and the package download with `timeout`, two minutes each.
A phase that stalls or fails drops the mirror apt tries first (the lowest `priority:`) from `/etc/apt/apt-mirrors.txt`, while another remains, and runs again, three attempts in all.
Killing either phase leaves the system as it was; dpkg runs once, after everything is downloaded, with `--no-download`.
A bound with no fallback would only turn a lost job into a failed one, since the same mirror stays slow for the next attempt.
`WorkflowAptInstallTest` fails on a workflow that runs apt directly, and drives the script against a stand-in `apt-get` that stalls on a named mirror.

**A github.com outage is ridden out, never skipped.**
For most of an hour GitHub answered release downloads with 503s and 504s and let connections time out, failing the gitleaks install, isx's base-image download and host-distro's HTTPS check on `main` and on unrelated PRs (#2042).
isx's own `DownloadCache` retries a transient failure for about four minutes, which suits a user but not an outage that long.
So `allowlist-test` downloads gitleaks with `curl --retry 8 --retry-all-errors`, about four minutes of backoff, and `sha256sum -c` still decides.
Every CI build of a template from its base image runs through `.github/scripts/net-retry.sh`'s `retry_download_outage`, which runs it again after 60, 120 and 240 seconds only when its output holds `DownloadCache`'s message for retries that ran out (`Download failed:` and `(after N attempts)`).
Any other failure, a checksum mismatch included, is returned at once, and a rerun verifies its download as the first run did.
host-distro's check that an HTTPS reply comes back through the host's firewall asks github.com, Cloudflare and Fedora's mirror in three rounds (`https_reply_from_any`).
Any status from any of them passes, since a reply from any host proves the firewall passes it, and the log names which one answered when github.com did not.
`NetRetryScriptTest` drives both against stand-in commands, and checks the workflows and scripts call them.

**An exec Incus could not attach is tried again.**
On an Arch host, incusd answered an exec into a container that had just started, 150 ms after another exec had run, with `Failed to retrieve PID of executing child process` and exit 255, and host-distro's proxy-only phase failed with no change to blame (#2084).
That message means its forkexec helper could not attach to the guest (liblxc's attach failed), so the command never ran, and running it again is safe whatever it does.
So `test-proxy-only.sh` and `test-host-distro.sh` exec only through `.github/scripts/guest-exec.sh`'s `guest_exec`, which tries such an exec again after 1, 2 and 4 seconds and prints the instance's `forkexec.log` before each new try.
Any other result is returned at once, since the no-egress checks rely on a command's own failure.
It did not reproduce on a Fedora host, idle or loaded, in 27 branches of 30 execs each, so the cause in liblxc is not known.
`GuestExecScriptTest` drives the helper against a stand-in `incus` and checks both scripts exec through it.

**The test JVM keeps `AESCrypt.makeSessionKey` out of the JIT.**
CI tests on GraalVM 25, whose JIT now and then gives one AES-256 cipher a wrong key schedule while it deoptimizes the compiled and OSR versions of `com.sun.crypto.provider.AESCrypt.makeSessionKey` during warm-up.
TLS 1.3 prefers `TLS_AES_256_GCM_SHA384`, so that connection fails with `bad_record_mac` (or `Tag mismatch`, followed by a "record" of zeros, which is the JDK wiping the plaintext it refused).
The proxy tests run every TLS hop in one JVM.
So this showed up as a flake on whichever hop and test happened to be running: client to MITM, MITM to mock upstream, in either direction, sometimes twice within milliseconds as the method was recompiled (#940).
A standalone loop of `Cipher.init` + `doFinal` with fresh AES-256 keys, run in fresh JVMs, gave a wrong ciphertext in 24 of 960 GraalVM JVMs.
It gave none in 480 with `-XX:-UseOnStackReplacement`, none in 480 with the method excluded from compilation, none in 96 with C2 in the same JDK (`-XX:-UseJVMCICompiler`), and none on OpenJDK's C2.
AES-128 never failed.
The root pom's `argLine` property excludes the method, which runs once per cipher and costs nothing interpreted.
It is a property, not surefire configuration, because the Quarkus plugin adds its own arguments to it.
`JitWorkaroundTest` fails if the proxy tests' JVM loses the flag, or if a JDK running the Graal JIT stops having that method.
On any other JDK the exclude is a no-op and that check is skipped: JDK 27 renamed the class to `AES_Crypt` and has no `makeSessionKey`, so requiring it there failed every build on a stock JDK 27 (#1050).
A native image is unaffected, since it never deoptimizes into a recompiled method.
A JVM install run on a GraalVM JDK is affected, though, so the launchers carry the same exclude (#1018).
`install.sh` adds it to the generated wrapper only when the JDK it pins runs the Graal JIT (`UseJVMCICompiler` is `true`; a stock JDK has no such flag).
The JBang aliases set it as `java-options` unconditionally, since a catalog cannot ask which JDK it lands on and every HotSpot JDK accepts it.
`LauncherJitWorkaroundTest` keeps both equal to the `-XX:CompileCommand` options in the pom's `argLine`.
The upstream finding that `-Djdk.graal.OptimisticAliasingAnalysis=false` also avoids the bug is not used: it disables a whole optimization rather than one method.
The bug is GraalVM-only (Temurin 25 and 27 do not reproduce it) and is reported upstream as [oracle/graal#14599](https://github.com/oracle/graal/issues/14599); once it is fixed there, the fix here is dropping the flag.

**Request budgets**: CLI latency regressions almost always arrive as extra Incus round trips -- a `configGet` per key, a GET per listed instance -- and each one is paid in sequence before the user gets a prompt, at well under a millisecond each, over a local Unix socket and over the macOS vsock tunnel alike (see docs/dev/PERFORMANCE-NOTES.md "CLI latency baseline" and docs/dev/PERFORMANCE-NOTES.md "CLI latency on macOS").
A settings write costs about 10 ms: 6-10 ms in a branch on macOS, 11-13 ms on Linux btrfs.
Wall-clock benchmarks cannot gate PRs on shared CI runners, but a round-trip count is deterministic.
`FakeIncusDaemon` (test scope, `common/src/test/.../incus/`) is an in-memory `IncusTransport` behind a real `IncusClient` (via its package-private `IncusClient(IncusApi)` constructor) that records every request; budget tests pin the exact count for a flow.
`common` publishes its test classes as a test-jar that `cli` depends on in test scope, so a command's own wiring is pinned against the same fake (`BuildCommandAccountsWiringTest`, #932).
Exact rather than at-most, so they ratchet: an improvement fails the test until the budget is lowered in the same change.
The failure lists every request made, which is usually enough to spot the duplicate.
A flow on the start/shell/branch path gets a budget; a known waste is pinned at its current count with a comment saying what the fixed count will be.
Wall-clock measurement belongs in `bench/`, not in the shipped CLI: `bench/cli.sh` times real `isx` commands, JVM against native, on a throwaway instance.

**TUI snapshot tests**: TUI screens are only reviewable if they can be rendered without a terminal or an Incus daemon.
`TuiSnapshot` (test scope, `cli/src/test/.../tui/`) renders into an in-memory Tamboui `Buffer` via `Frame.forTesting` and compares the plain text against golden files in `cli/src/test/resources/tui-snapshots/`.
Every run also writes the actual rendering to `cli/target/tui-snapshots/` as `.txt` and `.ansi` (colours; view with `less -R`), which is how a reviewer -- human or agent -- sees a UI change.
A missing golden fails rather than being created silently.
Accept a changed rendering with `-Dtui.snapshots.update=true` and review the golden diff.
This requires the screen to be decoupled from `Tui`: a modal gets its own class holding its state, key handling and rendering, with side effects (the AI call, the executor) injected -- `HelpChatModal` is the first.
Snapshot at several terminal sizes (80×24 is the floor), since truncation and wrapping bugs only show at some widths.

**Interactive init flows**: `isx init`'s credential steps are where a token gets written to the wrong place, silently cleared, or an operator's personal `gh` identity reused when a dedicated one was intended -- failures that announce nothing.
CI cannot reach them (it runs `isx init </dev/null`, so every prompt hits EOF), and `java.io.Console` is final, so code that takes one cannot be driven by a test at all; that is how #740 shipped.
So interactive code reads from `Prompts` (line / secret, `null` at EOF) instead: production wraps the terminal, tests pass `ScriptedPrompts`.
Each step's logic lives in a `(SpawnConfig, Prompts)` overload, and every effect outside the process -- token verification, the host's `gh` login, the browser, environment variables -- is a package-private method a test overrides.
`GitHubAuthFlowTest`, `ClaudeAuthFlowTest`, `CredentialPromptsTest`, `PathListPromptsTest` and `AccountMenuTest` drive the flows end to end under `IsolatedHome` and assert on the config.yaml actually written.
`IsolatedHome` also swaps the Incus the account flows ask for an empty `FakeIncusDaemon`, the way it swaps the proxy signal.
One opt-in covers every flow, where an override per test class was missed once and made four tests fail on any host whose Incus had instances (#1965).
`ScriptedPrompts` also fails a test when a value scripted as a secret is read through an echoing prompt, so a token prompt cannot quietly start printing the token.
The question is shown by the same `Prompts` (`show`, `ask`, `say` for its options or a re-ask), so what a flow asks and what it reads live in one place, and `ScriptedPrompts.conversation()` lets a test assert on both (#1703).
Prompts show at the output's step indent, so a question asked inside a group sits in it, where a literal indent in the question used to place it by hand.
What a flow reports between questions is not the conversation and goes through `BuildOutput`; init's frames, which it lays out itself, are an `Output.frame`: not a result, which a script reads, and nothing off a terminal.
A question is an `Output.prompt`, so the terminal shows it at the step indent and a live step is frozen first, as for any line.
`ScriptedPrompts` shows its questions and said lines through the bound output too, as the terminal's prompts do, so a test that records the output reads the whole conversation in order, and the old `askConfirmation` overloads, which printed the question as each caller wrote it, are gone.
Every question in `cli/command` is asked this way, `isx update-base`'s choice between tracking and pinning and `isx run`'s wait after an action included.
Init's narration sits at the same step indent as every command's, for consistency, so the step boxes alone keep init's own look.

**The macOS tunnel, without a Mac**: the compensations described in design/macos-and-vms.md "macOS vsock robustness" exist because of how vfkit's tunnel *behaves*, and Java never touches vsock -- vfkit hands the host an ordinary Unix socket.
So the behaviour is faked instead of the hypervisor.
`LossyIncusServer` (test scope, `common/src/test/.../incus/`) binds a real Unix socket, speaks just enough of the REST API for exec (`POST .../exec`, the four fd WebSockets, operation `/wait`), and can withhold close frames and EOF, keep sending output after the process exits, accept and never answer, stall after reading a request, drop an idle keep-alive connection, or truncate a body.
`IncusApiLossyTunnelTest` asserts exec completes from `/wait` with no close frame, `/wait` is re-polled while `Running`, the adaptive drain both extends for late output and stops at its ceiling, every exec fd is pinged, and `tryConnect` fails at the probe timeout.
`UnixSocketTransportTest`, `ConnectionPoolTest`, `KeepAliveConnectionTest` and `HttpResponseReaderTest` cover the watchdogs (the WebSocket handshake's included), responses and handshakes cut off at every stage, the once-only stale retry (and that a truncated, possibly-executed request is never replayed), pool expiry, and that the connection gauge returns to its baseline after every failure.
Each of these was checked by breaking the compensation and watching its test fail; the cut-off cases were found this way, as real bugs, and fixed with tests that failed first.
Timing seams are package-private constructors (`IncusApi(transport, pingIntervalMs)`, `ConnectionPool(idleTtlNanos)`) and `IncusApi.tryConnect(List)`, rather than waiting out production intervals.
Output a test needs inside the drain's window is timed by the drain itself: `IncusApi(transport, pingIntervalMs, DrainClock)` replaces the time the drain reads and its pause between polls, and the test sends each chunk from the pause that reaches it.
A fake that slept between chunks on its own thread assumed the thread woke on time; on a loaded macOS runner it woke late, the gap outgrew the window, and the drain, correctly, read the output as finished (#1208).
The guest half is covered too, in `integration-tests`.
The appliance boots under QEMU with a `vhost-vsock-pci` device, host `socat` bridges its vsock ports to Unix sockets on the paths isx uses (the shape vfkit gives it), and `appliance/test-tunnel.sh` checks the forwarder, every `isx-agent` verb, concurrent-connection accounting, that silent and partial clients stall no request, the `-T` backstop against a test-only silent endpoint, and no-reboot recovery.
The native `isx` then connects through the same socket.
A second boot, in `remote` proxy mode on a tap network, checks the macOS data path: the guest's DNAT of the gateway's `:443` to the host and the masquerade that makes every container arrive from the VM's address (`appliance/test-proxy-remote.sh`, #1585).
Both arches run it: x86_64 under KVM, aarch64 (the arch Apple Silicon runs) under TCG, since GitHub's arm64 runners expose no `/dev/kvm`.

**The macOS roles, tested on Linux** (#1800).
Both binaries take their platform role from the OS they run on, and the role decides what the proxy trusts: on macOS a credential proof identifies a caller whose address names no instance, on Linux the address check stays.
A runtime switch that selected macOS's role on Linux would be a switch that turns that check off, so released binaries have none, and CI runs a test-only build instead.
`scripts/macos-role-build.sh` copies the `-Prelease` uber-jars and replaces their `Platform.class` with one compiled from `Platform.java` with its one `OS_NAME` line pinned to macOS, marked in the class and the manifest.
`appliance/test-macos-role.sh` boots the appliance as isx boots it on macOS and runs those jars against it, with stand-ins for what only macOS has.
QEMU stands in for vfkit: `vm.pid` names it and socat bridges its vsock ports, and a tap device with DHCP, DNS and NAT stands in for vfkit's network.
The VM's lease is written to `/var/db/dhcpd_leases` for `VmNetwork`, and a `/usr/bin/security` answers the Keychain's admin trust settings with the one CA the GitHub stand-in's certificate chains to.
It checks what #1582 broke and the Linux jobs cannot reach: a template build that runs `gh` is served the template's account, a pinned branch its pin, no known instance's proof is refused, an `--mcp-client` branch reaches `isx mcp`, and a forged proof or the host's own request gets no credential.
Breaking `proofMayIdentify`'s macOS branch fails its template build with #1582's error, and the job also found that every `--mcp-client` branch was refused its first call (`CallerCheck`).
It runs in about a minute, in parallel with the other jobs, from `uber-jar-smoke`'s jars.

Three guards keep the test build out of a release.
`release.yml` runs `scripts/check-platform-role.sh` on the uber-jars it uploads, as `uber-jar-smoke` does on every PR: each `Platform.class` must be byte for byte the one the build compiled, unmarked, and `-Dos.name` must not give the CLI macOS's `vm` command.
`PlatformRoleFeature` fails a native build whose role is not its target OS's, so no native binary can carry a pinned `Platform` or a builder's `-Dos.name`.
`PlatformRoleBuildGuardTest` fails if anything but the `macos-role` job makes the test build, if that job uploads more than its logs, or if `release.yml` stops checking.
Making this sound found a switch the released uber-jars already had: a JVM reads `os.name` from its command line, so `-Dos.name` or `JAVA_TOOL_OPTIONS` put them in macOS's role.
`Platform` now checks an `os.name` that claims macOS against the host: a Linux kernel (`/proc/sys/kernel/ostype`) makes it Linux, and without macOS's `/System/Library/CoreServices/SystemVersion.plist` isx refuses to start rather than run half as Linux.
The test build runs the JVM, not the native macOS binaries, and none of vfkit, the macOS network stack or the real Keychain.
A runtime seam in the shipped code, defaulted off, was rejected for the reason above; an in-process harness over `MitmProxy` alone was rejected because it would run neither the CLI's macOS branch nor the real network path.

What remains uncovered is vfkit itself.
It cannot run on GitHub-hosted macOS runners at all -- they are macOS VMs, and Virtualization.framework nests only Linux guests -- so it needs a bare-metal Mac, as a self-hosted release gate or the manual pre-release run of `test-boot.sh` / `test-with-isx.sh` (#774).

**Hosts as users install them** (#1972).
`isx init` sets up the host itself: it installs Incus with the distribution's package manager and writes the host's firewall rules.
`isx-integration-tests-native` runs on the runner's Ubuntu, where ufw is never active and init installs firewalld, so the ufw path shipped on unit tests alone.
That is how #1932 passed: init wrote its forward block into a comment of ufw's `before.rules`, ufw failed to load, and an Arch host lost loopback and reply traffic.
So three `host-distro` jobs boot a distribution's own cloud image as a KVM guest on the hosted runner and run what a user runs there, from `isx init` to `isx destroy`.
The shapes are stock Arch (init installs firewalld through pacman), Arch with ufw enabled, and Ubuntu 24.04 with ufw enabled and its own Incus 6.0.
Arch ships the newest Incus, ufw and iptables-nft and has dropped `sg` (#700), so it breaks first; Ubuntu is where ufw is the firewall users enable.
A KVM guest, not the runner itself, because a broken host firewall would cut the runner off from GitHub mid-job, while in a guest the failure stays reportable.
Not Incus VMs from images.linuxcontainers.org, whose outages already moved `fresh-daemon-init` off them (#1558), and whose images come pre-wired unlike a host a user installs.
Not containers with nested Incus either: their kernel, nftables and AppArmor are the runner's, and the firewall is what is under test.
Not a self-hosted runner: on a public repository it would run fork PRs' code, so it could not gate a PR.
They run on every PR, beside the native job, since host-setup code is spread across packages no path filter could name.
Reverting #1936's line-anchored `COMMIT` lookup fails both `-ufw` legs at "ufw still loads after isx init", and `arch` not at all, which is why it is a separate leg.
The Arch leg found at once that isx init needed `which`, which Arch's base system does not ship; host commands are now found on the `PATH` in-process (`HostPath.find`).

**Live tests** (`sg incus-admin -c "mvn test"`, requires Incus daemon + cached test image):
- `IncusApiLiveTest` — REST protocol against real Incus: all endpoints, exec capture/stream, device ops, copy, launch, logs
- `IncusClientSmokeTest` — high-level API: pollUntilReady, shellExec, execBidirectional, execPty, runAsUser, copy, filePush, filePushRecursive (directory placement + permission preservation), login PATH
- `BuildPipelineSmokeTest` — full buildFromScratch + buildFromParent operation sequence

**Integration tests** (`mvn verify -DskipITs=false`, requires Incus):
- `TemplateBuildIT` — builds actual images, verifies metadata, agentuser, and that the generated agent context file lands without touching the user-layer CLAUDE.md
