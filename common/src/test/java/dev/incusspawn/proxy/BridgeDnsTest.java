package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.incusspawn.proxy.BridgeDns.BEGIN;
import static dev.incusspawn.proxy.BridgeDns.END;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BridgeDnsTest {

    private static final String GITHUB_BLOCK = BEGIN + """

            address=/github.com/10.0.0.1
            local=/github.com/
            """ + END;

    @Test
    void answersOtherRecordTypesLocallyInsteadOfWithTheWildcardAddress() {
        // An AAAA answer of :: reads as loopback to clients, and made a nested isx refuse every
        // intercepted domain as host-local (#814).
        assertEquals(BEGIN + """

                address=/api.anthropic.com/10.0.0.1
                local=/api.anthropic.com/
                address=/github.com/10.0.0.1
                local=/github.com/
                """ + END,
                BridgeDns.render("", Set.of("github.com", "api.anthropic.com"), "10.0.0.1"));
    }

    @Test
    void migratesTheReleasedLayoutAndKeepsEveryUserLine() {
        var released = """
                server=/corp/10.1.1.1
                no-hosts
                address=/lan/192.168.1.5
                local=/lan/
                address=/github.com/10.0.0.1
                address=/github.com/::
                address=/old.example/10.0.0.1
                address=/old.example/::""";
        assertEquals("""
                server=/corp/10.1.1.1
                no-hosts
                address=/lan/192.168.1.5
                local=/lan/
                """ + GITHUB_BLOCK,
                BridgeDns.render(released, Set.of("github.com"), "10.0.0.1"));
    }

    /**
     * A {@code local=} line without its {@code address=} partner answers NXDOMAIN for the whole
     * domain, so a rewrite must never leave one behind for a domain it stopped intercepting.
     */
    @Test
    void aDroppedDomainTakesBothOfItsLinesWithIt() {
        var existing = "no-hosts\n" + BEGIN + """

                address=/github.com/10.0.0.1
                local=/github.com/
                address=/quay.io/10.0.0.1
                local=/quay.io/
                """ + END;
        assertEquals("no-hosts\n" + GITHUB_BLOCK, BridgeDns.render(existing, Set.of("github.com"), "10.0.0.1"));
    }

    /**
     * What a downgrade to a release before the block leaves: it drops every {@code address=}
     * line, keeps the rest of the block as user lines, and appends its own layout after it.
     * Upgrading again must converge on one clean block.
     */
    @Test
    void recoversFromADowngradeRoundTrip() {
        var downgraded = BEGIN + """

                local=/github.com/
                local=/quay.io/
                """ + END + """

                address=/github.com/10.0.0.1
                address=/github.com/::""";
        var rewritten = BridgeDns.render(downgraded, Set.of("github.com"), "10.0.0.1");
        assertEquals(GITHUB_BLOCK, rewritten);
        assertEquals(rewritten, BridgeDns.render(rewritten, Set.of("github.com"), "10.0.0.1"),
                "a second rewrite must be a no-op, or every heal restarts dnsmasq");
    }

    @Test
    void anUnterminatedBlockRunsToTheEnd() {
        var existing = "no-hosts\n" + BEGIN + "\naddress=/quay.io/10.0.0.1\nlocal=/quay.io/";
        assertEquals("no-hosts", BridgeDns.withoutOverrides(existing));
    }

    @Test
    void clearingKeepsUserLinesAndDropsBothLayouts() {
        var existing = "no-hosts\nlocal=/lan/\naddress=/quay.io/10.0.0.1\naddress=/quay.io/::\n" + GITHUB_BLOCK;
        assertEquals("no-hosts\nlocal=/lan/", BridgeDns.withoutOverrides(existing));
    }

    @Test
    void statusNamesMissingDomainsAndTheOldLayoutSeparately() {
        var config = BEGIN + """

                address=/api.anthropic.com/10.0.0.1
                local=/api.anthropic.com/
                address=/ghcr.io/10.0.0.1
                """ + END + """

                address=/github.com/10.0.0.1
                address=/github.com/::""";
        var status = BridgeDns.status(config, Set.of("api.anthropic.com", "ghcr.io", "github.com"));
        assertFalse(status.complete());
        assertEquals(List.of("ghcr.io"), status.missing());
        assertEquals(List.of("github.com"), status.legacy());
        assertEquals("missing: ghcr.io; AAAA answered with :: (the pre-#814 layout) for: github.com",
                status.describe());
        assertTrue(BridgeDns.status(GITHUB_BLOCK, Set.of("github.com")).complete());
    }

    @Test
    void writeBridgeDnsSkipsAnUnchangedConfig() {
        var incus = mock(IncusClient.class);
        when(incus.networkConfigGet("incusbr0", "ipv4.address")).thenReturn("10.0.0.1/24");
        when(incus.networkConfigGet("incusbr0", "raw.dnsmasq")).thenReturn(GITHUB_BLOCK);
        ProxyConfig.writeBridgeDns(incus, Set.of("github.com"));
        verify(incus, never()).networkConfigSet(any(), any(), any());
    }
}
