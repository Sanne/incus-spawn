package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Proxy configuration constants and bridge DNS management shared between
 * the CLI and the proxy binary.
 */
public final class ProxyConfig {

    public static final int CONTAINER_FACING_PORT = 443;
    public static final int DEFAULT_MITM_PORT = 18443;
    public static final int DEFAULT_HEALTH_PORT = 18080;

    public static final Set<String> ANTHROPIC_DOMAINS = Set.of("api.anthropic.com");
    public static final Set<String> REGISTRY_DOMAINS = Set.of(
            "registry-1.docker.io", "auth.docker.io",
            "ghcr.io", "quay.io"
    );
    public static final Set<String> MAVEN_DOMAINS = Set.of(
            "repo.maven.apache.org", "repo1.maven.org",
            "plugins.gradle.org"
    );
    public static final Set<String> GRADLE_DOMAINS = Set.of("services.gradle.org");
    public static final Set<String> NPM_DOMAINS = Set.of("registry.npmjs.org");

    private static final Set<String> BUILTIN_INTERCEPTED_DOMAINS;

    static {
        var all = new HashSet<String>();
        all.addAll(ANTHROPIC_DOMAINS);
        all.addAll(REGISTRY_DOMAINS);
        all.addAll(MAVEN_DOMAINS);
        all.addAll(GRADLE_DOMAINS);
        all.addAll(NPM_DOMAINS);
        BUILTIN_INTERCEPTED_DOMAINS = Set.copyOf(all);
    }

    private ProxyConfig() {}

    public static Set<String> builtinInterceptedDomains() {
        return BUILTIN_INTERCEPTED_DOMAINS;
    }

    /**
     * All intercepted domains: built-in + tool proxy domains.
     * Wildcard entries (*.example.com) are expanded to their base domain.
     */
    public static Set<String> interceptedDomains() {
        return interceptedDomains(Set.of());
    }

    public static Set<String> interceptedDomains(Set<String> toolProxyDomains) {
        if (toolProxyDomains.isEmpty()) return BUILTIN_INTERCEPTED_DOMAINS;
        var all = new HashSet<>(BUILTIN_INTERCEPTED_DOMAINS);
        all.addAll(toolProxyDomains);
        return Set.copyOf(all);
    }

    public static boolean isInterceptedDomain(String domain, Set<String> toolProxyDomains,
                                               List<String> wildcardSuffixes) {
        if (BUILTIN_INTERCEPTED_DOMAINS.contains(domain)) return true;
        if (toolProxyDomains.contains(domain)) return true;
        for (var suffix : wildcardSuffixes) {
            if (domain.endsWith(suffix)) return true;
        }
        return false;
    }

    public static String vertexHost(String region) {
        return switch (region) {
            case "global" -> "aiplatform.googleapis.com";
            case "us" -> "aiplatform.us.rep.googleapis.com";
            case "eu" -> "aiplatform.eu.rep.googleapis.com";
            default -> region + "-aiplatform.googleapis.com";
        };
    }

    public static String resolveGatewayIp(IncusClient incus) {
        RuntimeException error = null;
        try {
            var addr = incus.networkConfigGet("incusbr0", "ipv4.address");
            if (addr.contains("/")) {
                addr = addr.substring(0, addr.indexOf('/'));
            }
            if (!addr.isEmpty()) return addr;
        } catch (RuntimeException e) {
            error = e;
        }
        var cached = SpawnConfig.load().getIncusBridgeGateway();
        if (!cached.isEmpty()) return cached;
        if (error != null) throw error;
        throw new IncusException("Bridge incusbr0 has no ipv4.address configured");
    }

    public static String resolvConfContent(IncusClient incus) {
        return "nameserver " + resolveGatewayIp(incus) + "\n";
    }

    /**
     * @return true if resolv.conf was updated
     */
    public static boolean fixResolvConfIfNeeded(IncusClient incus, String name) {
        var expected = resolvConfContent(incus);
        var result = incus.shellExec(name, "cat", "/etc/resolv.conf");
        if (result.success() && result.stdout().contains(expected.strip())) return false;
        var write = incus.shellExec(name, "sh", "-c",
                "rm -f /etc/resolv.conf; printf '%s' '" + expected + "' > /etc/resolv.conf");
        return write.success();
    }

    public static void configureBridgeDns(IncusClient incus) {
        configureBridgeDns(incus, Set.of());
    }

    public static void configureBridgeDns(IncusClient incus, Set<String> allDomains) {
        var effective = allDomains.isEmpty() ? BUILTIN_INTERCEPTED_DOMAINS : allDomains;
        writeBridgeDns(incus, effective);
        System.out.println("  DNS overrides: " + effective.size() +
                " domains -> " + resolveGatewayIp(incus) + " (via bridge dnsmasq)");
    }

    public static void writeBridgeDns(IncusClient incus) {
        writeBridgeDns(incus, BUILTIN_INTERCEPTED_DOMAINS);
    }

    public static void writeBridgeDns(IncusClient incus, Set<String> allDomains) {
        var gatewayIp = resolveGatewayIp(incus);
        var overrides = allDomains.stream()
                .sorted()
                .flatMap(d -> Stream.of(
                        "address=/" + d + "/" + gatewayIp,
                        "address=/" + d + "/::"))
                .collect(Collectors.joining("\n"));

        var existing = incus.networkConfigGet("incusbr0", "raw.dnsmasq");
        var preserved = existing.lines()
                .filter(l -> !l.startsWith("address="))
                .collect(Collectors.joining("\n"));
        var dnsmasqConfig = preserved.isEmpty() ? overrides : preserved + "\n" + overrides;

        if (dnsmasqConfig.equals(existing)) {
            return;
        }
        incus.networkConfigSet("incusbr0", "raw.dnsmasq", dnsmasqConfig);
    }

    public static void configureBridgeDnsWithRetry(IncusClient incus, Set<String> allDomains,
                                                    Runnable onDnsConfigured) {
        try {
            configureBridgeDns(incus, allDomains);
            ProxyLog.info("DNS overrides configured");
            if (onDnsConfigured != null) onDnsConfigured.run();
            return;
        } catch (Exception e) {
            ProxyLog.warn("DNS override failed, will retry in background: " + e.getMessage());
        }

        final Set<String> domains = allDomains;
        var thread = new Thread(() -> {
            long delaySec = 2;
            long maxDelaySec = 60;
            int attempt = 1;
            while (true) {
                try {
                    Thread.sleep(delaySec * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                attempt++;
                try {
                    configureBridgeDns(incus, domains);
                    ProxyLog.info("DNS overrides configured (attempt " + attempt + ")");
                    if (onDnsConfigured != null) onDnsConfigured.run();
                    return;
                } catch (Exception e) {
                    ProxyLog.warn("DNS retry " + attempt + " failed (next in "
                            + Math.min(delaySec * 2, maxDelaySec) + "s): " + e.getMessage());
                    delaySec = Math.min(delaySec * 2, maxDelaySec);
                }
            }
        }, "dns-override-retry");
        thread.setDaemon(true);
        thread.start();
    }

    public static void clearBridgeDns(IncusClient incus) {
        try {
            var existing = incus.networkConfigGet("incusbr0", "raw.dnsmasq");
            var servers = existing.lines()
                    .filter(l -> l.startsWith("server="))
                    .collect(Collectors.joining("\n"));
            incus.networkConfigSet("incusbr0", "raw.dnsmasq", servers);
        } catch (Exception e) {
            System.err.println("Warning: could not clear bridge DNS overrides: " + e.getMessage());
        }
    }

    public static String getDnsOverrides(IncusClient incus) {
        try {
            return incus.networkConfigGet("incusbr0", "raw.dnsmasq");
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isBridgeDnsComplete(IncusClient incus) {
        return isBridgeDnsComplete(incus, Set.of());
    }

    public static boolean isBridgeDnsComplete(IncusClient incus, Set<String> allDomains) {
        var overrides = getDnsOverrides(incus);
        if (overrides.isEmpty()) return true;
        var domains = allDomains.isEmpty() ? BUILTIN_INTERCEPTED_DOMAINS : allDomains;
        return domains.stream()
                .allMatch(d -> overrides.contains("address=/" + d + "/"));
    }
}
