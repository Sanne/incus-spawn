package dev.incusspawn.lifecycle;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Antigravity CLI's mode follows the instance's {@code gemini} account after it is built: a move
 * from an API key account to a Google one is re-derived in place, rather than leaving agy sending
 * API key requests the proxy has no key for. agy borrows Gemini CLI's credential and declares no
 * proxy entry, so account selection only ever asks the namespace's owner; these tests go through
 * the same lookups a build, a branch and {@code isx account set} use, not the tool in isolation.
 */
@ExtendWith(TempHome.class)
class AntigravityAccountModeTest {

    private static final String NAME = "agy-1";

    private static SpawnConfig config() throws Exception {
        return Jjq.spawnConfig("""
                gemini:
                  accounts:
                    key:
                      apiKey: "gemini-k1"
                    home:
                      type: google
                  default: key
                """);
    }

    /**
     * The gate that would have caught it: a tool that can re-derive what it bakes is only ever
     * asked when it is what account selection finds for its namespace.
     */
    @Test
    void everyToolThatCanRederiveIsTheOneItsNamespaceFinds() {
        var rederivable = AccountSelection.rederivableSetups();
        for (var tool : RuntimeConstants.CDI_TOOLS) {
            if (!tool.canRebakeForAccount()) continue;
            assertNotNull(tool.proxy(), tool.name() + " re-derives an account's identity but declares no"
                    + " proxy entry, so no namespace lookup ever finds it");
            var namespace = tool.proxy().getConfigNamespace();
            assertSame(tool, rederivable.get(namespace), tool.name() + " re-derives for '" + namespace
                    + "', but the namespace finds " + rederivable.get(namespace));
        }
        assertTrue(rederivable.containsKey("gemini"), "Antigravity CLI's mode is re-derived through gemini");
    }

    @Test
    void theBuildStampsTheModeItsAccountGives() throws Exception {
        var config = config();
        assertEquals(Map.of("gemini", "google"), AccountSelection.bakedIdentities(config, Map.of("gemini", "home")));
        assertEquals(Map.of("gemini", "api-key"), AccountSelection.bakedIdentities(config, Map.of("gemini", "key")));
    }

    /** The reported case: built on an API key account, then {@code isx account set <name> gemini=home}. */
    @Test
    void aMoveToAGoogleAccountIsRederivedNotRefused() throws Exception {
        var config = config();
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.ACCOUNT_IDENTITY_VERIFIED, "true",
                Metadata.accountIdentityKey("gemini"), "api-key",
                Metadata.accountKey("gemini"), "home"));
        assertEquals(Map.of("gemini", "home"), AccountSelection.staleIdentities(config, daemon.client(), NAME));
        assertEquals("", AccountSelection.incompatibilityReason(config, daemon.client(), NAME, Map.of("gemini", "home")));
    }

    @Test
    void twoAccountsOfOneModeNeedNothing() throws Exception {
        var config = Jjq.spawnConfig("""
                gemini:
                  accounts:
                    key:
                      apiKey: "gemini-k1"
                    other:
                      apiKey: "gemini-k2"
                  default: key
                """);
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.ACCOUNT_IDENTITY_VERIFIED, "true",
                Metadata.accountIdentityKey("gemini"), "api-key",
                Metadata.accountKey("gemini"), "other"));
        assertEquals(Map.of(), AccountSelection.staleIdentities(config, daemon.client(), NAME));
    }
}
