package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegateAgentProbeTest {

    /** opencode's yargs prints {@code run --help} on stderr, so a probe reading stdout never matches (live-VM finding). */
    @Test
    void opencodeProbeReadsHelpFromStderr() {
        assertTrue(DelegateAgent.OPENCODE.probe().contains("opencode run --help 2>&1 | grep -q -- --auto"));
    }
}
