package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code isx proxy status} and the proxy suggest when the bridge gateway cannot be read. */
class GatewayUnavailableHintTest {

    @Test
    void onMacOsTheHintPointsAtTheVmNotAnIncusCommand() {
        // #939: Incus runs inside the VM on macOS, and the host has no 'incus' CLI to run.
        var hint = ProxyConfig.gatewayUnavailableHint(true);
        assertTrue(hint.contains("isx vm status"), hint);
        assertFalse(hint.contains("'incus "), hint);
    }

    @Test
    void onLinuxTheHintPointsAtIncus() {
        var hint = ProxyConfig.gatewayUnavailableHint(false);
        assertTrue(hint.contains("incus network list"), hint);
    }
}
