package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.vm.VmNetwork;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vertx.core.Vertx;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
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
                    System.out.println("Usage: isx-proxy [OPTIONS]");
                    System.out.println();
                    System.out.println("MITM authentication proxy for incus-spawn containers.");
                    System.out.println();
                    System.out.println("Options:");
                    System.out.println("  --port <port>         MITM listen port (default: " + ProxyConfig.DEFAULT_MITM_PORT + ")");
                    System.out.println("  --health-port <port>  Health check port (default: " + ProxyConfig.DEFAULT_HEALTH_PORT + ")");
                    System.out.println("  --gateway-ip <ip>     Gateway IP to listen on (Linux: private, loopback or the bridge; macOS: the VM bridge)");
                    System.out.println("  --debug               Enable API traffic debug logging");
                    System.out.println("  --version, -V         Display version info");
                    System.out.println("  --help, -h            Show this help");
                    return 0;
                }
                case "--version", "-V" -> {
                    var info = BuildInfo.instance();
                    System.out.println("isx-proxy " + info.version() + " (" + info.gitSha() + ")");
                    System.out.println(info.runtime());
                    return 0;
                }
                case "--port" -> { if (i + 1 < args.length) port = Integer.parseInt(args[++i]); }
                case "--health-port" -> { if (i + 1 < args.length) healthPort = Integer.parseInt(args[++i]); }
                case "--gateway-ip" -> { if (i + 1 < args.length) gatewayIpOption = args[++i]; }
                case "--debug" -> debug = true;
            }
        }

        installLogTee();

        var incus = new IncusClient();
        if (!ProxyService.initComplete()) return ProxyService.EXIT_CONFIG;

        var badOverride = checkGatewayOverride(gatewayIpOption, incus);
        if (badOverride != 0) return badOverride;

        var loaded = ConfigFingerprint.load();
        var config = loaded.config();
        var claude = config.getClaude();

        if (claude.isUseVertex()) {
            if (claude.getCloudMlRegion().isBlank() || claude.getVertexProjectId().isBlank()) {
                System.err.println("Error: Vertex AI enabled but region or project ID not configured. Run 'isx init' first.");
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
        ProxyLog.info("Starting proxy " + build.version() + " (" + build.gitSha() + ") " + build.runtime());
        System.out.println("Starting MITM authentication proxy...");
        System.out.println("  Version:       " + build.version() + " (" + build.gitSha() + ")");
        System.out.println("  Runtime:       " + build.runtime());
        if (!Platform.isMacOS()) {
            System.out.println("  Incus:         " + build.incusClient() + " (client) / " + build.incusServer() + " (server)");
        }
        System.out.println("  Gateway IP:    " + gatewayIp);
        System.out.println("  MITM port:     " + port);
        System.out.println("  Health port:   " + healthPort);
        if (creds.useVertex()) {
            System.out.println("  Vertex AI:     " + creds.vertexRegion() +
                    " (project: " + creds.vertexProjectId() + ")");
        } else if (!creds.oauthToken().isBlank()) {
            System.out.println("  OAuth token:   configured");
        } else if (!creds.anthropicApiKey().isBlank()) {
            System.out.println("  API key:       configured");
        } else {
            System.out.println("  Claude:        (not configured)");
        }
        var toolProxyNames = creds.toolProxyNames();
        if (!toolProxyNames.isEmpty()) {
            System.out.println("  Tool proxies:  " + String.join(", ", toolProxyNames));
        }
        var unresolved = ToolProxyResolver.findUnresolved(proxy.configTree(), proxy.toolSetups());
        if (!unresolved.isEmpty()) {
            var unresolvedNames = unresolved.stream()
                    .map(ToolProxyResolver.UnresolvedToolProxy::toolName)
                    .distinct().sorted().toList();
            System.out.println("  Skipped (no credentials): " + String.join(", ", unresolvedNames));
            System.out.println("  Run 'isx init' to configure, or add entries to config.yaml.");
        }
        System.out.println("  Log file:      " + Environment.proxyLogFile());
        System.out.println();

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
                System.out.println("  Debug logs:    " + debugLog.logDir());
            } catch (IOException e) {
                System.err.println("Warning: could not create debug log directory: " + e.getMessage());
            }
        }

        var configWatcher = new ConfigWatcher(
                SpawnConfig.configDir(), proxy::toolDirs, proxy::reload);
        configWatcher.start();
        ProxyLog.info("Config watcher started");

        try {
            sun.misc.Signal.handle(new sun.misc.Signal("HUP"), signal -> {
                System.out.println("Received SIGHUP, reloading configuration...");
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
            System.out.println("\nStopping proxy...");
            configWatcher.stop();
            var forceExit = new Thread(() -> {
                try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                System.err.println("Proxy shutdown exceeded 10 seconds, forcing exit.");
                Runtime.getRuntime().halt(0);
            }, "force-exit");
            forceExit.setDaemon(true);
            forceExit.start();
            proxy.stop();
        }));

        var allDomains = proxy.allInterceptedDomains();
        Runnable dnsCallback;
        if (Platform.isMacOS()) {
            dnsCallback = () -> {
                try {
                    addresses.configureBridgeDns(incus, allDomains);
                    ProxyLog.info("DNS overrides configured");
                } catch (Exception e) {
                    ProxyLog.info("Using install-time DNS configuration (VM API not reachable from launchd)");
                }
                proxy.setDnsConfigured(true);
            };
        } else {
            dnsCallback = () -> ProxyConfig.configureBridgeDnsWithRetry(
                    () -> addresses.configureBridgeDns(incus, allDomains), () -> proxy.setDnsConfigured(true));
        }
        try {
            proxy.start(dnsCallback);
        } catch (Exception e) {
            ProxyLog.error("Failed to start: " + e.getMessage());
            System.err.println("Is another proxy already running? Check port " + port + ".");
            System.err.println("If the iptables redirect rule is missing, re-run 'isx init'.");
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
                addresses.writeBridgeDns(incus, domains);
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
     * {@code followsBridge} when the gateway came from the Incus bridge rather than
     * {@code --gateway-ip} or the VM link, and so moves when the bridge's address does (#966).
     */
    record Addresses(String gateway, String healthBind, String bridgeDns, boolean followsBridge) {
        /** Write the bridge DNS block at startup, and say where it points. */
        void configureBridgeDns(IncusClient incus, java.util.Set<String> domains) {
            ProxyConfig.configureBridgeDns(incus, domains, bridgeDnsTarget(incus));
        }

        /**
         * Rewrite the bridge DNS block on reload; throws {@link GatewayMoved} instead of pointing
         * every intercepted domain back at an address the bridge has given up.
         */
        void writeBridgeDns(IncusClient incus, java.util.Set<String> domains) {
            var moved = movedTo(incus);
            if (moved.isPresent()) throw moved.get();
            ProxyConfig.writeBridgeDns(incus, domains, bridgeDnsTarget(incus));
        }

        /**
         * Whether the bridge now has another address than {@link #gateway()}. Never for an
         * explicit {@code --gateway-ip} (#933), which is not read from the bridge at all, nor
         * for a bridge that cannot be read or has no address: a restart would find nothing
         * better, and the cached gateway is not the bridge's word.
         */
        java.util.Optional<GatewayMoved> movedTo(IncusClient incus) {
            if (!followsBridge) return java.util.Optional.empty();
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
        var followsBridge = false;
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
                followsBridge = true;
            } catch (Exception e) {
                System.err.println("Error: could not determine Incus bridge gateway IP.");
                System.err.println(ProxyConfig.gatewayUnavailableHint(false));
                return null;
            }
        }
        return new Addresses(gatewayIp, ProxyHealthCheck.healthAddress(gatewayIp),
                Platform.isMacOS() ? null : gatewayIp, followsBridge);
    }

    /**
     * {@code --gateway-ip} as the exit code to stop with, after saying why, or 0 to go on. On
     * Linux the override is written verbatim into dnsmasq as every intercepted domain's A record,
     * and the unauthenticated health endpoint binds to it, so it must be a unicast IPv4 address
     * in the canonical form: {@code 10.1} or {@code ::ffff:10.99.0.1} parse as the same address
     * but are not what dnsmasq would read, and a wildcard would expose /health everywhere.
     * <p>
     * On both platforms the override is also the bind address of the credential-injecting
     * listener, and a caller the proxy cannot place is served the default accounts, so it must
     * not be somewhere other hosts can route to. On macOS it must be exactly the address
     * {@link VmNetwork#discoverHostBridgeIp()} finds (#937). On Linux it may be a private
     * (RFC 1918) or loopback address, which needs no bridge read and so still works around a
     * bridge with no address to read (#892), or else the bridge's current gateway itself, read from Incus without the cached fallback (#1022). When the
     * bridge address is needed and cannot be read, the override cannot be checked and the proxy
     * stops as it would without one.
     */
    static int checkGatewayOverride(String gatewayIpOption, IncusClient incus) {
        var macOS = Platform.isMacOS();
        return checkGatewayOverride(gatewayIpOption, macOS,
                macOS ? VmNetwork::discoverHostBridgeIp : () -> readBridgeGatewayOrNull(incus));
    }

    /**
     * The bridge's current gateway, never the one {@code isx init} cached: an address the bridge
     * has since given up may now be one other hosts can route to.
     */
    private static String readBridgeGatewayOrNull(IncusClient incus) {
        try {
            return BridgeAddress.read(incus).map(BridgeAddress::gateway).orElse(null);
        } catch (RuntimeException unreadable) {
            System.err.println("Could not read the Incus bridge address: " + unreadable.getMessage());
            return null;
        }
    }

    /** {@code bridgeIp} is read only when the decision depends on it, and is null if it cannot be. */
    static int checkGatewayOverride(String gatewayIpOption, boolean macOS,
            java.util.function.Supplier<String> bridgeIp) {
        if (gatewayIpOption == null || gatewayIpOption.isBlank()) return 0;
        if (!macOS) {
            var literal = bridgeGatewayLiteral(gatewayIpOption);
            if (literal == null) {
                System.err.println("Error: --gateway-ip " + gatewayIpOption + " is not a bridge gateway address.");
                System.err.println("Pass the Incus bridge's own IPv4 address, without the prefix length that");
                System.err.println("'incus network get incusbr0 ipv4.address' shows (10.166.11.1, not 10.166.11.1/24).");
                return ProxyService.EXIT_CONFIG;
            }
            if (literal.isSiteLocalAddress() || literal.isLoopbackAddress()) return 0;
        }
        return checkAgainstBridge(gatewayIpOption, macOS, bridgeIp.get());
    }

    /** Exit 1 when the bridge cannot be read, as without an override: that is transient, not config. */
    private static int checkAgainstBridge(String gatewayIpOption, boolean macOS, String bridgeIp) {
        if (bridgeIp == null) {
            if (macOS) {
                reportNoVmBridge();
            } else {
                System.err.println("Error: --gateway-ip " + gatewayIpOption + " is not a private address, and the");
                System.err.println("Incus bridge address to compare it with could not be read.");
                System.err.println(ProxyConfig.gatewayUnavailableHint(false));
            }
            return 1;
        }
        if (bridgeIp.equals(gatewayIpOption)) return 0;
        if (macOS) {
            System.err.println("Error: --gateway-ip " + gatewayIpOption + " is not the VM-facing bridge address ("
                    + bridgeIp + ").");
            System.err.println("The proxy injects credentials, so on macOS it listens only where the VM reaches it.");
        } else {
            System.err.println("Error: --gateway-ip " + gatewayIpOption + " is neither a private address nor the Incus");
            System.err.println("bridge address (" + bridgeIp + "). The proxy injects credentials, so it does not");
            System.err.println("listen on addresses other networks can route to.");
        }
        System.err.println("Omit --gateway-ip to use " + bridgeIp + ".");
        return ProxyService.EXIT_CONFIG;
    }

    private static void reportNoVmBridge() {
        System.err.println("Error: could not discover VM-facing bridge interface.");
        System.err.println(ProxyConfig.gatewayUnavailableHint(true));
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

    private static void installLogTee() {
        var logFile = Environment.proxyLogFile();
        try {
            Files.createDirectories(logFile.getParent());
            var fileOut = new FileOutputStream(logFile.toFile(), true);
            System.setOut(new PrintStream(new TeeOutputStream(System.out, fileOut), true));
            System.setErr(new PrintStream(new TeeOutputStream(System.err, fileOut), true));
        } catch (IOException e) {
            System.err.println("Warning: could not open log file " + logFile + ": " + e.getMessage());
        }
    }

    static class TeeOutputStream extends OutputStream {
        private final OutputStream console;
        private final OutputStream file;

        TeeOutputStream(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int b) throws IOException {
            console.write(b);
            file.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            console.write(b, off, len);
            file.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            file.flush();
        }

        @Override
        public void close() throws IOException {
            file.close();
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
                System.err.println("Error: ISX_BENCH_UPSTREAM entry '" + entry + "' is not host=ip:port");
                return false;
            }
            entries.add(m);
        }
        for (var m : entries) {
            proxy.overrideUpstream(m.group(1), m.group(2), Integer.parseInt(m.group(3)));
            System.out.println("  BENCHMARK:     upstream " + m.group(1) + " -> " + m.group(2) + ":" + m.group(3));
        }
        if (!cert.isEmpty()) {
            proxy.trustUpstreamCertificate(cert);
            System.out.println("  BENCHMARK:     trusting upstream certificate " + cert);
        }
        ProxyLog.warn("Benchmark upstream override active (ISX_BENCH_UPSTREAM=" + spec + ")");
        return true;
    }

    private static final Pattern BENCH_UPSTREAM_ENTRY =
            Pattern.compile("([^=\\s]+)=([^:\\s]+):(\\d{1,5})");
}
