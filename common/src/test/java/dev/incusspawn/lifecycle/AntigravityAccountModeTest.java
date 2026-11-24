package dev.incusspawn.lifecycle;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.GeminiSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    private static final String CONFIG = """
            gemini:
              accounts:
                key:
                  apiKey: "gemini-k1"
                home:
                  type: google
              default: key
            """;

    private static SpawnConfig config() throws Exception {
        return Jjq.spawnConfig(CONFIG);
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

    /** A template that pins no gemini account used the configured default, and stamps the mode that gives. */
    @Test
    void aBuildThatPinsNoAccountStampsTheDefaultsMode() throws Exception {
        var config = Jjq.spawnConfig("""
                gemini:
                  accounts:
                    default:
                      apiKey: "gemini-k1"
                    agy-key:
                      type: google
                  default: default
                """);
        var unpinned = new java.util.HashMap<String, String>();
        unpinned.put("gemini", null);
        assertEquals(Map.of("gemini", "api-key"), AccountSelection.bakedIdentities(config, unpinned));
        var setups = AccountSelection.namespaceSetups(config);
        assertEquals(Map.of("gemini", "api-key"),
                AccountSelection.bakedIdentities(config, AccountSelection.effectiveSelection(Map.of(), setups), setups));
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

    /** A template built before the stamp was kept (or by the unpinned-build bug) has nothing to compare a move against. */
    @Test
    void anExplicitPinOnAnUnstampedTemplateIsRederivedOnce() throws Exception {
        var config = config();
        var pinned = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.ACCOUNT_IDENTITY_VERIFIED, "true",
                Metadata.accountKey("gemini"), "home"));
        assertEquals(Map.of("gemini", "home"), AccountSelection.staleIdentities(config, pinned.client(), NAME));
        // Once stamped, the same pin is nothing to do
        var stamped = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.ACCOUNT_IDENTITY_VERIFIED, "true",
                Metadata.accountIdentityKey("gemini"), "google",
                Metadata.accountKey("gemini"), "home"));
        assertEquals(Map.of(), AccountSelection.staleIdentities(config, stamped.client(), NAME));
    }

    /** The branch path must stay free: no pin and no stamp is what every other template's branch is. */
    @Test
    void anUnpinnedUnstampedInstanceCostsNothing() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(Metadata.ACCOUNT_IDENTITY_VERIFIED, "true"));
        assertEquals(Map.of(), AccountSelection.staleIdentities(config(), daemon.client(), NAME));
    }

    /** A tool that cannot say what its old templates hold is never re-derived on a guess: gh's stamp may be absent and right. */
    @Test
    void onlyAToolThatOptsInIsRederivedWithoutAStamp() {
        assertTrue(new GeminiSetup().rederivesWhenUnstamped());
        assertFalse(new dev.incusspawn.tool.GhSetup().rederivesWhenUnstamped());
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

    /**
     * {@code isx account set} promised nothing inside needed restarting, but an agy already
     * running keeps the mode it started in: the re-derive says so, and only where agy is.
     */
    @Test
    void aRunningAgyIsToldToRestartAndAnImageWithoutItIsNot() throws Exception {
        var configDir = Path.of(System.getProperty("user.home"), ".config/incus-spawn");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), CONFIG);

        var withAgy = stubIncus(true);
        var restart = new GeminiSetup().rebakeForAccount(new Container(withAgy, NAME), "home");
        assertNotNull(restart, "an instance with agy is told what to restart");
        assertTrue(restart.contains("a Google account") && restart.contains("isx shell " + NAME), restart);
        verify(withAgy).shellExec(eq(NAME), eq("sh"), eq("-c"), contains("isx-zz-antigravity.sh"));

        var withoutAgy = stubIncus(false);
        assertNull(new GeminiSetup().rebakeForAccount(new Container(withoutAgy, NAME), "home"),
                "Gemini CLI alone reads its key at launch: nothing to restart");
        verify(withoutAgy, never()).shellExec(eq(NAME), eq("sh"), eq("-c"), contains("isx-zz-antigravity.sh"));
    }

    private static IncusClient stubIncus(boolean agyInstalled) {
        var incus = mock(IncusClient.class);
        var ok = new IncusClient.ExecResult(0, "", "");
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(ok);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(ok);
        when(incus.shellExec(eq(NAME), eq("sh"), eq("-c"), startsWith("[ -e ")))
                .thenReturn(agyInstalled ? ok : new IncusClient.ExecResult(1, "", ""));
        return incus;
    }
}
