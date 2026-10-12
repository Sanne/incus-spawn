package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.proxy.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx init}'s DNS Configuration step. The proxy's reload has just written the whole block
 * when it runs, and nothing writes it again until the proxy restarts, so the step must never
 * shrink it to the built-in domains: builds would reach GitHub with the placeholder token (#1354).
 */
@ExtendWith(IsolatedHome.class)
class InitBridgeDnsStepTest {

    private static final String GATEWAY = "10.1.2.1";
    private static final String GITHUB_TOKEN = """
            github:
              accounts:
                default:
                  token: "ghp_test"
              default: default
            """;

    @Test
    void theStepKeepsTheToolDomainsOfAConfiguredGitHubToken() throws Exception {
        var config = IsolatedHome.seed(GITHUB_TOKEN);
        var all = ProxyConfig.bridgeDnsDomains(config);
        assertTrue(all.contains("github.com"), all.toString());
        var daemon = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"));
        var init = new InitCommand();
        init.incus = daemon.client();
        // What the proxy's reload wrote just before
        ProxyConfig.writeBridgeDns(init.incus, all, GATEWAY);

        init.setupBridgeDns();

        var status = ProxyConfig.bridgeDnsStatus(init.incus, all);
        assertTrue(status.complete(), status.describe());
    }

    @Test
    void theStepWritesTheToolDomainsOnABridgeWithNoOverridesYet() throws Exception {
        IsolatedHome.seed(GITHUB_TOKEN);
        var daemon = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"));
        var init = new InitCommand();
        init.incus = daemon.client();

        init.setupBridgeDns();

        var status = ProxyConfig.bridgeDnsStatus(init.incus, Set.of("github.com", "githubusercontent.com"));
        assertTrue(status.complete(), status.describe());
    }
}
