package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class VmAgentFailureTest {

    @Test
    void matchesTheSelinuxDenialAndTheFailedUnitFrom842() {
        var log = """
                [    3.912345] systemd[1]: Starting incus-agent.service - Incus - agent...
                [    4.105712] audit: type=1400 audit(1758000000.123:4): avc:  denied  { listen } for  pid=1288 comm="incus-agent" scontext=system_u:system_r:init_t:s0 tcontext=system_u:system_r:init_t:s0 tclass=vsock_socket permissive=0
                [    4.110000] systemd[1]: Failed to start incus-agent.service - Incus - agent.
                [  OK  ] Reached target multi-user.target
                """;

        var lines = VmAgentFailure.matchingLines(log);

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("tclass=vsock_socket"));
        assertTrue(lines.get(1).endsWith("Failed to start incus-agent.service - Incus - agent."));
    }

    @Test
    void stripsConsoleColourAndCarriageReturns() {
        var log = "\u001B[0;1;31mFAILED\u001B[0m] Failed to start \u001B[0;1;39mincus-agent.service\u001B[0m - Incus - agent.\r\n";

        assertEquals(List.of("FAILED] Failed to start incus-agent.service - Incus - agent."),
                VmAgentFailure.matchingLines(log));
    }

    @Test
    void ignoresOtherUnitsAndOtherDenials() {
        var log = """
                Failed to start sshd.service - OpenSSH server daemon.
                avc:  denied  { read } for  pid=900 comm="chronyd" tclass=file permissive=0
                incus-agent: Connected to host
                """;

        assertEquals(List.of(), VmAgentFailure.matchingLines(log));
    }

    @Test
    void reportsEachLineOnceAndBoundsTheCount() {
        var log = "Failed to start incus-agent.service - Incus - agent.\n".repeat(5)
                + IntStream.range(0, 20)
                        .mapToObj(i -> "avc: denied { listen } comm=\"incus-agent\" pid=" + i + "\n")
                        .collect(Collectors.joining());

        var lines = VmAgentFailure.matchingLines(log);

        assertEquals(VmAgentFailure.MAX_LINES, lines.size());
        assertEquals(1, lines.stream().filter(l -> l.startsWith("Failed to start")).count());
    }

    @Test
    void emptyOrMissingLogMatchesNothing() {
        assertEquals(List.of(), VmAgentFailure.matchingLines(""));
        assertEquals(List.of(), VmAgentFailure.matchingLines(null));
    }
}
