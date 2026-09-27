package dev.incusspawn.command;

import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ToolProxyResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TUI's DNS auto-heal and {@code isx doctor}'s remediation rewrite the bridge's whole block
 * of overrides from {@link ToolProxyResolver#resolvedDomains}, so that set must be the proxy's own.
 */
@ExtendWith(IsolatedHome.class)
class BridgeDnsDomainsTest {

    @Test
    void domainsOnlyANamedAccountCanServeAreKept() throws Exception {
        var config = IsolatedHome.seed("""
                github:
                  accounts:
                    personal:
                      email: "me@example.com"
                    acme:
                      token: "ghp_acme"
                  default: personal
                """);
        var domains = ProxyConfig.interceptedDomains(ToolProxyResolver.resolvedDomains(config));
        // The default account has no token; a rewrite from its set alone would stop intercepting
        // GitHub, and instances pinned to acme would reach it with placeholder credentials.
        assertTrue(domains.contains("github.com"), domains.toString());
        assertTrue(domains.contains("githubusercontent.com"), domains.toString());
    }
}
