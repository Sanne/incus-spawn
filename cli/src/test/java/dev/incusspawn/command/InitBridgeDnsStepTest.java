package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.proxy.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static dev.incusspawn.incus.FakeIncusDaemon.DNSMASQ_EXITED;
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
        ProxyConfig.writeBridgeDns(init.incus, all, GATEWAY, null);

        init.setupBridgeDns();

        var status = ProxyConfig.bridgeDnsStatus(init.incus, all);
        assertTrue(status.complete(), status.describe());
    }

    /**
     * {@code isx init --plan} agrees with what the step writes: with a port exposed, a block that
     * lacks host.isx.internal is still a change to make, not done.
     * <p>
     * Linux only: on macOS the line's address is the Mac's end of the VM link, which a runner
     * without the VM does not have, so neither the plan nor the step wants the line there.
     * {@code BridgeDnsTest#aCliWritersExposedHostAddressIsTheGatewayOnLinuxAndTheVmLinkOnMacOs}
     * covers the macOS address.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void thePlanWantsTheExposedHostNameTheStepWrites() throws Exception {
        var config = IsolatedHome.seed(GITHUB_TOKEN + "exposed-host-ports: [11434]\n");
        var daemon = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"));
        var init = new InitCommand() {
            @Override
            boolean incusReachable() {
                return true;
            }
        };
        init.incus = daemon.client();
        ProxyConfig.writeBridgeDns(init.incus, ProxyConfig.bridgeDnsDomains(config), GATEWAY, null);
        assertNotEquals(InitPlan.State.DONE, init.planBridgeDns().state(), "the host.isx.internal line is missing");

        init.setupBridgeDns();
        assertEquals(InitPlan.State.DONE, init.planBridgeDns().state());
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

    @Test
    void theStepWritesAgainWhenIncusRevertsTheUpdate() throws Exception {
        IsolatedHome.seed(GITHUB_TOKEN);
        var daemon = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .refuseNetworkWrites(DNSMASQ_EXITED);
        var init = new CountingPauses();
        init.incus = daemon.client();

        init.setupBridgeDns();

        var status = ProxyConfig.bridgeDnsStatus(init.incus, Set.of("github.com", "githubusercontent.com"));
        assertTrue(status.complete(), status.describe());
        // Writing straight away would land in the same dnsmasq restart that refused the first one
        assertEquals(1, init.pauses);
    }

    @Test
    void theStepFailsWithIncussReasonWhenEveryWriteIsRefused() throws Exception {
        IsolatedHome.seed(GITHUB_TOKEN);
        var refusals = Collections.nCopies(InitCommand.BRIDGE_DNS_ATTEMPTS, DNSMASQ_EXITED);
        var daemon = new FakeIncusDaemon().network("incusbr0", Map.of("ipv4.address", GATEWAY + "/24"))
                .refuseNetworkWrites(refusals.toArray(String[]::new));
        var init = new CountingPauses();
        init.incus = daemon.client();

        var e = assertThrows(IncusException.class, init::setupBridgeDns);

        assertTrue(e.getMessage().contains(DNSMASQ_EXITED), e.getMessage());
        assertEquals("", init.incus.networkConfigGet("incusbr0", "raw.dnsmasq"));
        assertEquals(InitCommand.BRIDGE_DNS_ATTEMPTS - 1, init.pauses, "a pause between tries, none after the last");
    }

    /** Counts the pauses between tries instead of waiting them out. */
    static class CountingPauses extends InitCommand {
        int pauses;

        @Override
        void pauseBeforeBridgeDnsRetry() {
            pauses++;
        }
    }
}
