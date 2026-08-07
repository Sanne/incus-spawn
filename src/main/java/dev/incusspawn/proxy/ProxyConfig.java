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

    private static final Set<String> ANTHROPIC_DOMAINS = Set.of("api.anthropic.com");
    private static final Set<String> GITHUB_DOMAINS = Set.of(
            "github.com", "api.github.com",
            "raw.githubusercontent.com", "objects.githubusercontent.com",
            "codeload.github.com", "uploads.github.com"
    );
    private static final Set<String> REGISTRY_DOMAINS = Set.of(
            "registry-1.docker.io", "auth.docker.io",
            "ghcr.io", "quay.io"
    );
    private static final Set<String> MAVEN_DOMAINS = Set.of(
            "repo.maven.apache.org", "repo1.maven.org",
            "plugins.gradle.org"
    );
    private static final Set<String> GRADLE_DOMAINS = Set.of("services.gradle.org");
    private static final String BOB_BASE_DOMAIN = "bob.ibm.com";
    private static final List<String> BOB_REGIONAL_DOMAINS = List.of(
            "us-east.bob.ibm.com",
            "eu-de.bob.ibm.com",
            "jp-tok.bob.ibm.com"
    );

    private static final List<String> WILDCARD_DOMAIN_SUFFIXES;
    private static final Set<String> INTERCEPTED_DOMAIN_SET;

    static {
        var all = new HashSet<String>();
        all.addAll(ANTHROPIC_DOMAINS);
        all.addAll(GITHUB_DOMAINS);
        all.addAll(REGISTRY_DOMAINS);
        all.addAll(MAVEN_DOMAINS);
        all.addAll(GRADLE_DOMAINS);
        all.add(BOB_BASE_DOMAIN);
        all.addAll(BOB_REGIONAL_DOMAINS);
        INTERCEPTED_DOMAIN_SET = Set.copyOf(all);

        WILDCARD_DOMAIN_SUFFIXES = List.of("." + BOB_BASE_DOMAIN);
    }

    private ProxyConfig() {}

    public static Set<String> interceptedDomains() {
        return INTERCEPTED_DOMAIN_SET;
    }

    public static boolean isInterceptedDomain(String domain) {
        if (INTERCEPTED_DOMAIN_SET.contains(domain)) return true;
        for (var suffix : WILDCARD_DOMAIN_SUFFIXES) {
            if (domain.endsWith(suffix)) return true;
        }
        return false;
    }

    public static boolean isBobDomain(String domain) {
        return domain.equals(BOB_BASE_DOMAIN) || domain.endsWith("." + BOB_BASE_DOMAIN);
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

    public static void configureBridgeDns(IncusClient incus) {
        writeBridgeDns(incus);
        System.out.println("  DNS overrides: " + interceptedDomains().size() +
                " domains -> " + resolveGatewayIp(incus) + " (via bridge dnsmasq)");
    }

    public static void writeBridgeDns(IncusClient incus) {
        var gatewayIp = resolveGatewayIp(incus);
        var overrides = interceptedDomains().stream()
                .sorted()
                .flatMap(d -> Stream.of(
                        "address=/" + d + "/" + gatewayIp,
                        "address=/" + d + "/::"))
                .collect(Collectors.joining("\n"));

        var existing = incus.networkConfigGet("incusbr0", "raw.dnsmasq");
        var servers = existing.lines()
                .filter(l -> l.startsWith("server="))
                .collect(Collectors.joining("\n"));
        var dnsmasqConfig = servers.isEmpty() ? overrides : servers + "\n" + overrides;

        if (dnsmasqConfig.equals(existing)) {
            return;
        }
        incus.networkConfigSet("incusbr0", "raw.dnsmasq", dnsmasqConfig);
    }

    public static void configureBridgeDnsWithRetry(IncusClient incus, Runnable onDnsConfigured) {
        try {
            configureBridgeDns(incus);
            ProxyLog.info("DNS overrides configured");
            if (onDnsConfigured != null) onDnsConfigured.run();
            return;
        } catch (Exception e) {
            ProxyLog.warn("DNS override failed, will retry in background: " + e.getMessage());
        }

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
                    configureBridgeDns(incus);
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
        var overrides = getDnsOverrides(incus);
        if (overrides.isEmpty()) return true;
        return interceptedDomains().stream()
                .allMatch(d -> overrides.contains("address=/" + d + "/"));
    }
}
