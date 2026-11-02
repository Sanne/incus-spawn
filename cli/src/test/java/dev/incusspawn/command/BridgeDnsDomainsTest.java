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

    @Test
    void hostRouteNamesAreKept() throws Exception {
        var config = IsolatedHome.seed("""
                host-routes:
                  work.isx.internal:
                    target: 127.0.0.1:7070
                    templates: [tpl-a]
                  mcp.isx.internal:
                    target: 127.0.0.1:7071
                    templates: [tpl-a]
                """);
        var domains = ProxyConfig.interceptedDomains(ToolProxyResolver.resolvedDomains(config));
        // A rewrite without it would stop instances resolving the route (#936)
        assertTrue(domains.contains("work.isx.internal"), domains.toString());
        // One the proxy refuses (it is already isx mcp's) adds nothing
        var others = new java.util.HashSet<>(domains);
        others.remove("work.isx.internal");
        assertEquals(ProxyConfig.interceptedDomains(ToolProxyResolver.resolvedDomains(IsolatedHome.seed("{}\n"))), others);
    }

    @Test
    void chatGptHostsAreKeptWhileAChatGptAccountExists() throws Exception {
        // The proxy intercepts them then (#1332): a rewrite without them would send Codex's
        // placeholders straight to OpenAI
        var config = IsolatedHome.seed("""
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                """);
        var domains = ProxyConfig.interceptedDomains(ToolProxyResolver.resolvedDomains(config));
        assertTrue(domains.contains("chatgpt.com"), domains.toString());
        assertTrue(domains.contains("auth.openai.com"), domains.toString());

        var none = ProxyConfig.interceptedDomains(ToolProxyResolver.resolvedDomains(IsolatedHome.seed("{}\n")));
        assertFalse(none.contains("chatgpt.com"), none.toString());
    }
}
