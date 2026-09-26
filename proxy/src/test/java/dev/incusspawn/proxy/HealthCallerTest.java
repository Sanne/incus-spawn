package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who gets the proxy's PID in {@code /health}: the host, never a container. */
class HealthCallerTest {

    @Test
    void theHostOverTheBridgeAddressIsTheHost() {
        // The CLI on Linux connects to the bridge gateway, so the source is that same address.
        assertTrue(MitmProxy.isHostCaller("10.166.11.1", "10.166.11.1"));
    }

    @Test
    void loopbackIsTheHost() {
        // macOS: the CLI reaches the proxy on 127.0.0.1.
        assertTrue(MitmProxy.isHostCaller("127.0.0.1", "127.0.0.1"));
        assertTrue(MitmProxy.isHostCaller("::1", "::1"));
    }

    @Test
    void aContainerIsNot() {
        assertFalse(MitmProxy.isHostCaller("10.166.11.5", "10.166.11.1"));
    }

    @Test
    void unknownIsNot() {
        assertFalse(MitmProxy.isHostCaller(null, "10.166.11.1"));
        assertFalse(MitmProxy.isHostCaller("", "10.166.11.1"));
    }
}
