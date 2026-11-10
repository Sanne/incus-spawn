package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
import dev.incusspawn.vm.VmNetwork;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vertx.core.Vertx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@QuarkusMain
public class ProxyMain implements QuarkusApplication {

    @Override
    public int run(String... args) {
        int port = ProxyConfig.DEFAULT_MITM_PORT;
        int healthPort = ProxyConfig.DEFAULT_HEALTH_PORT;
        String gatewayIpOption = null;
        boolean debug = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> {
                    ProxyLog.stdout("Usage: isx-proxy [OPTIONS]");
                    ProxyLog.stdout("");
                    ProxyLog.stdout("MITM authentication proxy for incus-spawn containers.");
                    ProxyLog.stdout("");
                    ProxyLog.stdout("Options:");
                    ProxyLog.option("--port <port>", "MITM listen port (default: " + ProxyConfig.DEFAULT_MITM_PORT + ")");
                    ProxyLog.option("--health-port <port>", "Health check port (default: " + ProxyConfig.DEFAULT_HEALTH_PORT + ")");
                    ProxyLog.option("--gateway-ip <ip>", "Bridge gateway IP, for when Incus cannot report it (Linux: the bridge address; macOS: the VM bridge)");
                    ProxyLog.option("--debug", "Enable API traffic debug logging");
                    ProxyLog.option("--version, -V", "Display version info");
                    ProxyLog.option("--help, -h", "Show this help");
                    return 0;
                }
                case "--version", "-V" -> {
                    var info = BuildInfo.instance();
                    ProxyLog.stdout("isx-proxy " + info.displayVersion() + " (" + info.gitSha() + ")");
                    ProxyLog.stdout(info.runtime());
                    return 0;
                }
                case "--port" -> { if (i + 1 < args.length) port = Integer.parseInt(args[++i]); }
                case "--health-port" -> { if (i + 1 < args.length) healthPort = Integer.parseInt(args[++i]); }
                case "--gateway-ip" -> { if (i + 1 < args.length) gatewayIpOption = args[++i]; }
                case "--debug" -> debug = true;
                case ExitWith.ARG -> i++; // internal, read by ExitWith.watchIfAsked
            }
        }

        ProxyLog.teeConsoleTo(Environment.proxyLogFile());
        // The daemon's messages are its log lines: what shared code reports, the proxy logs,
        // progress as info, notes (a request's debug line) on stderr alone and warnings as
        // warnings, each line its own timestamped line.
        BuildOutput.bind(Output.logTo(ProxyLog::info, ProxyLog::trace, ProxyLog::warn));

        ExitWith.watchIfAsked(args);

        var incus = new IncusClient();
        if (!ProxyService.initComplete()) return ProxyService.EXIT_CONFIG;

        var badOverride = checkGatewayOverride(gatewayIpOption, incus);
        if (badOverride != 0) return badOverride;

        if (!applyBenchConfig()) return ProxyService.EXIT_CONFIG;

        var loaded = ConfigFingerprint.load();
        var config = loaded.config();
        var claude = config.getClaude();

        if (claude.isUseVertex()) {
            if (claude.getCloudMlRegion().isBlank() || claude.getVertexProjectId().isBlank()) {
                ProxyLog.error("Vertex AI enabled but region or project ID not configured. Run 'isx init' first.");
                return ProxyService.EXIT_CONFIG;
            }
        }

        var addresses = resolveAddresses(gatewayIpOption, incus);
        if (addresses == null) return 1;
        var gatewayIp = addresses.gateway();
        var healthBindAddress = addresses.healthBind();

        // Everything the proxy serves is derived from this one read, the same way reload()
        // derives it -- so no later read escapes the fingerprint taken ahead of it (#837).
        var vertx = Arc.container().instance(Vertx.class).get();
        var proxy = new MitmProxy(vertx, gatewayIp, port, healthPort, healthBindAddress, loaded);
        var creds = proxy.credentials();

        var build = BuildInfo.instance();
        ProxyLog.info("Starting proxy " + build.displayVersion() + " (" + build.gitSha() + ") " + build.runtime());
        ProxyLog.stdout("Starting MITM authentication proxy...");
        ProxyLog.field("Version", build.displayVersion() + " (" + build.gitSha() + ")");
        ProxyLog.field("Runtime", build.runtime());
        if (!Platform.isMacOS()) {
            ProxyLog.field("Incus", build.incusClient() + " (client) / " + build.incusServer() + " (server)");
        }
        ProxyLog.field("Gateway IP", gatewayIp);
        ProxyLog.field("MITM port", port);
        ProxyLog.field("Health port", healthPort);
        if (creds.useVertex()) {
            ProxyLog.field("Vertex AI", creds.vertexRegion() +
                    " (project: " + creds.vertexProjectId() + ")");
        } else if (!creds.oauthToken().isBlank()) {
            ProxyLog.field("OAuth token", "configured");
        } else if (!creds.anthropicApiKey().isBlank()) {
            ProxyLog.field("API key", "configured");
        } else {
            ProxyLog.field("Claude", "(not configured)");
        }
        var toolProxyNames = creds.toolProxyNames();
        if (!toolProxyNames.isEmpty()) {
            ProxyLog.field("Tool proxies", String.join(", ", toolProxyNames));
        }
        var unresolved = ToolProxyResolver.findUnresolved(proxy.configTree(), proxy.toolSetups());
        if (!unresolved.isEmpty()) {
            var unresolvedNames = unresolved.stream()
                    .map(ToolProxyResolver.UnresolvedToolProxy::toolName)
                    .distinct().sorted().toList();
            ProxyLog.field("Skipped", String.join(", ", unresolvedNames)
                    + " (no credentials: run 'isx init', or add entries to config.yaml)");
        }
        ProxyLog.field("Log file", Environment.proxyLogFile());
        ProxyLog.stdout("");

        proxy.setIncusClient(incus);
        var exitCode = new AtomicInteger();
        proxy.setBridgeDnsWriter(reloadDnsWriter(addresses, incus, proxy, exitCode));
        if (addresses.followsBridge()) {
            // Unordered: an Incus read must never queue behind, or ahead of, MITM work.
            vertx.setPeriodic(GATEWAY_CHECK_MILLIS, id -> {
                if (exitCode.get() != 0) {
                    vertx.cancelTimer(id);
                    return;
                }
                vertx.executeBlocking(() -> {
                    addresses.movedTo(incus).ifPresent(moved -> exitForRestart(moved, proxy, exitCode));
                    return null;
                }, false);
            });
        }
        if (!applyBenchUpstream(proxy)) return ProxyService.EXIT_CONFIG;

        if (debug) {
            try {
                var debugLog = new ApiTrafficLog(Environment.apiDebugDir().resolve("proxy"));
                proxy.setDebugLog(debugLog);
                ProxyLog.field("Debug logs", debugLog.logDir());
            } catch (IOException e) {
                ProxyLog.warn("Could not create debug log directory: " + e.getMessage());
            }
        }

        var configWatcher = new ConfigWatcher(
                SpawnConfig.configDir(), proxy::toolDirs, proxy::reload, proxy::refreshNesting);
        configWatcher.start();
        ProxyLog.info("Config watcher started");

        try {
            sun.misc.Signal.handle(new sun.misc.Signal("HUP"), signal -> {
                // reload() logs that it reloads
                proxy.reload();
            });
        } catch (Exception e) {
            ProxyLog.info("SIGHUP handler not available: " + e.getMessage());
        }

        try {
            // Which accounts an instance uses is instance state, not config state, and it
            // changes on every branch -- this tool's most common operation. Answering that with
            // a full reload would re-mint certs and re-push SSL options each time, so it gets a
            // signal of its own that only re-reads the instance list.
            sun.misc.Signal.handle(new sun.misc.Signal("USR1"), signal -> {
                ProxyLog.info("Received SIGUSR1, refreshing instance account registry");
                proxy.refreshInstanceRegistry();
            });
        } catch (Exception e) {
            ProxyLog.info("SIGUSR1 handler not available: " + e.getMessage());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ProxyLog.stdout("", "Stopping proxy...");
            configWatcher.stop();
            var forceExit = new Thread(() -> {
                // Past stop()'s own budget, which says what it could not close
                try { Thread.sleep(ProxyService.STOP_FORCE_EXIT_SECONDS * 1000L); } catch (InterruptedException e) { return; }
                ProxyLog.warn("Proxy shutdown exceeded " + ProxyService.STOP_FORCE_EXIT_SECONDS + " seconds, forcing exit.");
                Runtime.getRuntime().halt(0);
            }, "force-exit");
            forceExit.setDaemon(true);
            forceExit.start();
            proxy.stop();
        }));

        // The domains are read when the callback runs: a reload while start() waits on a port
        // may change them.
        Runnable dnsCallback;
        if (Platform.isMacOS()) {
            dnsCallback = () -> {
                try {
                    addresses.configureBridgeDns(incus, proxy.allInterceptedDomains(), proxy.exposedHostPorts());
                    ProxyLog.info("DNS overrides configured");
                } catch (Exception e) {
                    ProxyLog.info("Using install-time DNS configuration (VM API not reachable from launchd)");
                }
                proxy.setDnsConfigured(true);
            };
        } else {
            dnsCallback = () -> ProxyConfig.configureBridgeDnsWithRetry(
                    () -> addresses.configureBridgeDns(incus, proxy.allInterceptedDomains(), proxy.exposedHostPorts()), () -> proxy.setDnsConfigured(true));
        }
        try {
            proxy.start(dnsCallback);
        } catch (Exception e) {
            ProxyLog.error("Failed to start: " + e.getMessage(),
                    "Is another proxy already running? Check ports " + port + " and " + healthPort + ".",
                    "If the iptables redirect rule is missing, re-run 'isx init'.");
            return 1;
        }
        return exitCode.get();
    }

    /** How often a proxy that follows the bridge checks it still has the address it listens on. */
    static final long GATEWAY_CHECK_MILLIS = 60_000;

    /** What a reload rewrites bridge DNS with: nothing once the bridge has moved, which stops the proxy. */
    static java.util.function.Consumer<java.util.Set<String>> reloadDnsWriter(Addresses addresses,
            IncusClient incus, MitmProxy proxy, AtomicInteger exitCode) {
        return domains -> {
            try {
                addresses.writeBridgeDns(incus, domains, proxy.exposedHostPorts());
            } catch (GatewayMoved moved) {
                exitForRestart(moved, proxy, exitCode);
            }
        };
    }

    /**
     * The bridge's address changed under a running proxy (#966): stop and exit 1, so the
     * service manager ({@code Restart=on-failure}) starts it again on the new address, which
     * the startup path resolves and writes into bridge DNS. Rebinding in place would mean
     * moving the listener, the health endpoint and the DNS block together; the restart already
     * does exactly that.
     */
    private static void exitForRestart(GatewayMoved moved, MitmProxy proxy, AtomicInteger exitCode) {
        if (!exitCode.compareAndSet(0, 1)) return;
        ProxyLog.warn(moved.getMessage() + "; stopping (exit 1) to start again on the new address."
                + " The proxy service restarts it; a proxy run in the foreground must be started again by hand.");
        proxy.stop();
    }

    /** The bridge now has another address than the one the proxy listens on. */
    static final class GatewayMoved extends RuntimeException {
        GatewayMoved(String from, String to) {
            super("Incus bridge address changed from " + from + " to " + to);
        }
    }

    /**
     * Where the MITM listener serves instances, where the health endpoint listens, and where
     * bridge DNS sends intercepted domains -- null on macOS, where DNS inside the VM points at
     * the VM's own bridge gateway, not at the host address the proxy listens on.
     */
    record Addresses(String gateway, String healthBind, String bridgeDns) {
        /**
         * Whether the gateway is the Incus bridge's, and so moves when the bridge's address
         * does (#966): always on Linux, {@code --gateway-ip} included (#933), never on macOS,
         * where it is the VM link.
         */
        boolean followsBridge() {
            return bridgeDns != null;
        }

        /** Write the bridge DNS block at startup, and say where it points. */
        void configureBridgeDns(IncusClient incus, java.util.Set<String> domains, java.util.List<Integer> exposedPorts) {
            ProxyConfig.configureBridgeDns(incus, domains, bridgeDnsTarget(incus), hostAddress(exposedPorts));
        }

        /**
         * Where bridge DNS sends {@link dev.incusspawn.config.ExposedHostPorts#HOST_NAME}: the
         * address the proxy listens on, and so relays exposed ports on, on Linux and macOS alike;
         * null, which leaves it out, while no port is exposed (#2117).
         */
        String hostAddress(java.util.List<Integer> exposedPorts) {
            return exposedPorts.isEmpty() ? null : gateway;
        }

        /**
         * Rewrite the bridge DNS block on reload; throws {@link GatewayMoved} instead of pointing
         * every intercepted domain back at an address the bridge has given up.
         */
        void writeBridgeDns(IncusClient incus, java.util.Set<String> domains, java.util.List<Integer> exposedPorts) {
            var moved = movedTo(incus);
            if (moved.isPresent()) throw moved.get();
            ProxyConfig.writeBridgeDns(incus, domains, bridgeDnsTarget(incus), hostAddress(exposedPorts));
        }

        /**
         * Whether the bridge now has another address than {@link #gateway()}. Never without
         * {@link #followsBridge()}, nor for a bridge that cannot be read or has no address: a
         * restart would find nothing better, and the cached gateway is not the bridge's word.
         */
        java.util.Optional<GatewayMoved> movedTo(IncusClient incus) {
            if (!followsBridge()) return java.util.Optional.empty();
            String current;
            try {
                current = BridgeAddress.read(incus).map(BridgeAddress::gateway).orElse(null);
            } catch (RuntimeException unreadable) {
                return java.util.Optional.empty();
            }
            return current == null || current.equals(gateway) ? java.util.Optional.empty()
                    : java.util.Optional.of(new GatewayMoved(gateway, current));
        }

        private String bridgeDnsTarget(IncusClient incus) {
            return bridgeDns != null ? bridgeDns : ProxyConfig.resolveGatewayIp(incus);
        }
    }

    /**
     * The gateway comes from {@code --gateway-ip} when given, else from the bridge; the health
     * endpoint and, on Linux, bridge DNS then use that same address. Deriving them from a second
     * bridge lookup would ignore the override, and fail when the override is how the user worked
     * around a bridge with no address to read (#892).
     * Returns null after printing why when the gateway cannot be determined.
     */
    static Addresses resolveAddresses(String gatewayIpOption, IncusClient incus) {
        String gatewayIp;
        if (gatewayIpOption != null && !gatewayIpOption.isBlank()) {
            gatewayIp = gatewayIpOption;
        } else if (Platform.isMacOS()) {
            gatewayIp = VmNetwork.discoverHostBridgeIp();
            if (gatewayIp == null) {
                reportNoVmBridge();
                return null;
            }
        } else {
            try {
                gatewayIp = ProxyConfig.resolveGatewayIp(incus);
            } catch (Exception e) {
                ProxyLog.error("Could not determine Incus bridge gateway IP.", ProxyConfig.gatewayUnavailableHint(false));
                return null;
            }
        }
        return new Addresses(gatewayIp, ProxyHealthCheck.healthAddress(gatewayIp),
                Platform.isMacOS() ? null : gatewayIp);
    }

    /**
     * {@code --gateway-ip} as the exit code to stop with, after saying why, or 0 to go on. On
     * Linux the override is written verbatim into dnsmasq as every intercepted domain's A record,
     * and the unauthenticated health endpoint binds to it, so it must be a unicast IPv4 address
     * in the canonical form: {@code 10.1} or {@code ::ffff:10.99.0.1} parse as the same address
     * but are not what dnsmasq would read, and a wildcard would expose /health everywhere.
     * <p>
     * The CLI looks for the proxy at the bridge's gateway, or at the one {@code isx init} cached
     * when the bridge cannot be read, so the override must be that address: anywhere else every
     * {@code isx} command would find no proxy and point bridge DNS away from it (#933). On macOS
     * it must be exactly the address {@link VmNetwork#discoverHostBridgeIp()} finds (#937). On
     * Linux, with the bridge read from Incus and found, it must equal its current gateway. With
     * no bridge address to read it stands in for one (#892): it must then be private (RFC 1918)
     * or loopback, since the credential-injecting listener binds to it and serves the default
     * accounts to a caller it cannot place (#1022), and equal the cached gateway if there is one.
     * When the bridge address is needed and cannot be found, the override cannot be checked and
     * the proxy stops as it would without one.
     */
    static int checkGatewayOverride(String gatewayIpOption, IncusClient incus) {
        var macOS = Platform.isMacOS();
        return checkGatewayOverride(gatewayIpOption, macOS,
                macOS ? VmNetwork::discoverHostBridgeIp : () -> readBridgeGatewayOrNull(incus),
                ProxyConfig::cachedGateway);
    }

    /**
     * The bridge's current gateway, never the one {@code isx init} cached: an address the bridge
     * has since given up may now be one other hosts can route to.
     */
    private static String readBridgeGatewayOrNull(IncusClient incus) {
        try {
            return BridgeAddress.read(incus).map(BridgeAddress::gateway).orElse(null);
        } catch (RuntimeException unreadable) {
            ProxyLog.warn("Could not read the Incus bridge address: " + unreadable.getMessage());
            return null;
        }
    }

    /**
     * {@code bridgeIp} is null if it cannot be read; {@code cachedGateway} is empty when
     * {@code isx init} recorded none. Each is read only when the decision depends on it.
     */
    static int checkGatewayOverride(String gatewayIpOption, boolean macOS,
            java.util.function.Supplier<String> bridgeIp, java.util.function.Supplier<String> cachedGateway) {
        if (gatewayIpOption == null || gatewayIpOption.isBlank()) return 0;
        if (macOS) {
            var vmBridge = bridgeIp.get();
            if (vmBridge != null) return checkAgainstBridge(gatewayIpOption, true, vmBridge);
            // Exit 1, as without an override: a VM not up yet is transient, not a config error.
            reportNoVmBridge();
            return 1;
        }
        var literal = bridgeGatewayLiteral(gatewayIpOption);
        if (literal == null) {
            ProxyLog.error("--gateway-ip " + gatewayIpOption + " is not a bridge gateway address.",
                    "Pass the Incus bridge's own IPv4 address, without the prefix length that "
                            + "'incus network get incusbr0 ipv4.address' shows (10.166.11.1, not 10.166.11.1/24).");
            return ProxyService.EXIT_CONFIG;
        }
        var bridge = bridgeIp.get();
        if (bridge != null) return checkAgainstBridge(gatewayIpOption, false, bridge);
        var cached = cachedGateway.get();
        if (!cached.isEmpty() && !cached.equals(gatewayIpOption)) {
            ProxyLog.error("--gateway-ip " + gatewayIpOption + " is not the bridge gateway 'isx init' recorded ("
                    + cached + "), where isx looks for the proxy while the bridge address cannot be read.",
                    "Omit --gateway-ip to use " + cached + ".");
            return ProxyService.EXIT_CONFIG;
        }
        if (literal.isSiteLocalAddress() || literal.isLoopbackAddress()) return 0;
        // Exit 1, as without an override: Incus not up yet is transient, not a config error.
        ProxyLog.error("--gateway-ip " + gatewayIpOption + " is not a private address, and the Incus bridge"
                + " address to compare it with could not be read.", ProxyConfig.gatewayUnavailableHint(false));
        return 1;
    }

    private static int checkAgainstBridge(String gatewayIpOption, boolean macOS, String bridgeIp) {
        if (bridgeIp.equals(gatewayIpOption)) return 0;
        if (macOS) {
            ProxyLog.error("--gateway-ip " + gatewayIpOption + " is not the VM-facing bridge address (" + bridgeIp + ").",
                    "The proxy injects credentials, so on macOS it listens only where the VM reaches it.",
                    "Omit --gateway-ip to use " + bridgeIp + ".");
        } else {
            ProxyLog.error("--gateway-ip " + gatewayIpOption + " is not the Incus bridge address (" + bridgeIp + ").",
                    "isx looks for the proxy at the bridge's address, so the proxy serves only there.",
                    "Omit --gateway-ip to use " + bridgeIp + ".");
        }
        return ProxyService.EXIT_CONFIG;
    }

    private static void reportNoVmBridge() {
        ProxyLog.error("Could not discover VM-facing bridge interface.", ProxyConfig.gatewayUnavailableHint(true));
    }

    /** {@code address} as a canonical unicast IPv4 literal dnsmasq can serve, or null. */
    private static java.net.Inet4Address bridgeGatewayLiteral(String address) {
        try {
            var parsed = java.net.InetAddress.ofLiteral(address);
            if (parsed instanceof java.net.Inet4Address v4 && v4.getHostAddress().equals(address)
                    && !v4.isAnyLocalAddress() && !v4.isLinkLocalAddress() && !v4.isMulticastAddress()
                    && !"255.255.255.255".equals(address)) {
                return v4;
            }
            return null;
        } catch (IllegalArgumentException notALiteral) {
            return null;
        }
    }

    /**
     * bench/run.sh --load=maven points a repository host at a local stub, so a
     * benchmark measures the cache path without sending its load to the real
     * repository. {@code ISX_BENCH_UPSTREAM} is {@code host=ip:port[,...]};
     * {@code ISX_BENCH_UPSTREAM_CERT} is the stub's certificate (PEM) to trust.
     * Nothing else sets these.
     */
    static boolean applyBenchUpstream(MitmProxy proxy) {
        return applyBenchUpstream(proxy, Environment.strippedEnv("ISX_BENCH_UPSTREAM"),
                Environment.strippedEnv("ISX_BENCH_UPSTREAM_CERT"));
    }

    static boolean applyBenchUpstream(MitmProxy proxy, String spec, String cert) {
        if (spec.isEmpty()) return true;
        var entries = new ArrayList<Matcher>();
        for (var entry : spec.split(",")) {
            var m = BENCH_UPSTREAM_ENTRY.matcher(entry.strip());
            if (!m.matches()) {
                ProxyLog.error("ISX_BENCH_UPSTREAM entry '" + entry + "' is not host=ip:port");
                return false;
            }
            entries.add(m);
        }
        for (var m : entries) {
            proxy.overrideUpstream(m.group(1), m.group(2), Integer.parseInt(m.group(3)));
            ProxyLog.field("BENCHMARK", "upstream " + m.group(1) + " -> " + m.group(2) + ":" + m.group(3));
        }
        if (!cert.isEmpty()) {
            proxy.trustUpstreamCertificate(cert);
            ProxyLog.field("BENCHMARK", "trusting upstream certificate " + cert);
        }
        ProxyLog.warn("Benchmark upstream override active (ISX_BENCH_UPSTREAM=" + spec + ")");
        return true;
    }

    /**
     * bench/run.sh's model loads give the proxy Claude accounts of their own: {@code ISX_BENCH_CONFIG}
     * names the config.yaml to read instead of the user's, which is never touched. The CA, the
     * credential proofs and every other file stay the configuration directory's. Nothing else sets it.
     */
    static boolean applyBenchConfig() {
        return applyBenchConfig(Environment.strippedEnv("ISX_BENCH_CONFIG"));
    }

    static boolean applyBenchConfig(String file) {
        if (file.isEmpty()) return true;
        var path = Path.of(file).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            ProxyLog.error("ISX_BENCH_CONFIG names no file: " + path);
            return false;
        }
        SpawnConfig.useBenchFile(path);
        ProxyLog.field("BENCHMARK", "reading " + path + " instead of config.yaml");
        ProxyLog.warn("Benchmark config override active (ISX_BENCH_CONFIG=" + path + ")");
        return true;
    }

    private static final Pattern BENCH_UPSTREAM_ENTRY =
            Pattern.compile("([^=\\s]+)=([^:\\s]+):(\\d{1,5})");
}
