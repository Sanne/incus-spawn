package dev.incusspawn.proxy;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.lifecycle.TempHome;
import dev.incusspawn.tool.GeminiSetup;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A {@code gemini} account of {@code type: google} (#2443): what turns the route on, and what it is to the resolver. */
@ExtendWith(TempHome.class)
class GoogleAccountsTest {

    private static final String GOOGLE_AND_KEYED = """
            gemini:
              accounts:
                home:
                  type: google
                work:
                  apiKey: "gemini-real-key"
              default: home
            """;

    /** {@code yaml} as the config tree isx resolves against. */
    private static io.hyperfoil.tools.jjq.value.JqValue tree(String yaml) throws Exception {
        Files.createDirectories(SpawnConfig.configDir());
        Files.writeString(SpawnConfig.configDir().resolve("config.yaml"), yaml);
        return SpawnConfig.load().tree();
    }

    @Test
    void onlyAGoogleAccountTurnsItOn() throws Exception {
        assertFalse(GoogleAccounts.enabledBy(JqValues.parse("{}")));
        assertFalse(GoogleAccounts.enabledBy(tree("gemini:\n  accounts:\n    work:\n      apiKey: k\n")));
        var tree = tree(GOOGLE_AND_KEYED);
        assertTrue(GoogleAccounts.enabledBy(tree));
        assertArrayEquals(new String[]{"home"}, GoogleAccounts.googleAccounts(tree));
        assertTrue(GoogleAccounts.isGoogleAccount(tree, "home"));
        assertFalse(GoogleAccounts.isGoogleAccount(tree, "work"));
        assertFalse(GoogleAccounts.isGoogleAccount(tree, "missing"));
        assertEquals(Set.of("oauth2.googleapis.com", "www.googleapis.com", "daily-cloudcode-pa.googleapis.com",
                "cloudcode-pa.googleapis.com"), GoogleAccounts.DOMAINS);
    }

    @Test
    void theHostsAreInterceptedWhileAGoogleAccountExistsAndNoRepositoryMayTakeThem() throws Exception {
        var tree = tree(GOOGLE_AND_KEYED);
        assertTrue(ToolProxyResolver.handlerDomains(tree).containsAll(GoogleAccounts.DOMAINS));
        assertTrue(ToolProxyResolver.repositoryDomainsTaken(Set.of()).containsAll(GoogleAccounts.DOMAINS));
        assertFalse(ToolProxyResolver.handlerDomains(JqValues.parse("{}")).contains(GoogleAccounts.TOKEN));
        assertTrue(ToolProxyResolver.resolvedDomains(SpawnConfig.load()).containsAll(GoogleAccounts.DOMAINS),
                "the bridge DNS block agrees with the proxy");
    }

    /** A Google account is usable with no key, is never called incomplete for lacking one, and the key account still is. */
    @Test
    void aGoogleAccountNeedsNoKeyAndAKeyedOneStillDoes() throws Exception {
        var tree = tree(GOOGLE_AND_KEYED);
        var config = SpawnConfig.load();
        var shape = new GeminiSetup().accountShape();
        assertEquals(List.of("home", "work"), AccountResolver.usableAccountNames(tree, "gemini", shape));
        assertEquals("home", AccountResolver.effectiveAccount(tree, "gemini", shape, null));
        assertTrue(shape.credentialOutsideConfig(tree.path("gemini").path("accounts").path("home")));
        assertFalse(shape.credentialOutsideConfig(tree.path("gemini").path("accounts").path("work")));
        assertEquals("", shape.problem(JqValues.parse("{\"type\":\"google\"}"), JqValues.parse("{}")));
        assertEquals("it holds no credential", shape.problem(JqValues.parse("{}"), JqValues.parse("{}")));
        assertEquals("it holds no credential", shape.problem(JqValues.parse("{\"type\":\"api-key\"}"), JqValues.parse("{}")));
        assertTrue(shape.problem(JqValues.parse("{\"type\":\"oauth\"}"), JqValues.parse("{}")).contains("oauth"));
        assertEquals("", shape.problem(JqValues.parse("{}"), JqValues.parse("{\"apiKey\":\"flat\"}")), "a flat key is inherited");
        assertTrue(shape.hasCredential(Map.of("type", "google")));
        assertFalse(shape.hasCredential(Map.of("type", "api-key")));
        assertTrue(shape.accountKeys().contains("type"), "type is a key isx reads, never reported as unread");

        Map<String, ToolSetup> setups = new ToolDefLoader().allToolSetups();
        var missing = ToolProxyResolver.missingSecrets(tree, setups, List.of("gemini-cli"), Map.of());
        assertTrue(missing.isEmpty(), "the Google account's credential lives outside config.yaml: " + missing);
        var keyedMissing = ToolProxyResolver.missingSecrets(tree, setups, List.of("gemini-cli"), Map.of("gemini", "work"));
        assertTrue(keyedMissing.isEmpty(), keyedMissing.toString());
        var noKey = tree("gemini:\n  accounts:\n    work:\n      type: api-key\n    home:\n      type: google\n  default: work\n");
        assertThrows(AccountResolver.UnknownAccountException.class,
                () -> ToolProxyResolver.missingSecrets(noKey, setups, List.of("gemini-cli"), Map.of("gemini", "work")),
                "a pin to a keyed account without its key still fails closed");
        assertEquals(List.of("home"), AccountResolver.usableAccountNames(noKey, "gemini", shape),
                "and it is skipped for the default, which the Google account then is");
        assertTrue(ToolProxyResolver.findUnresolved(tree, setups).stream().noneMatch(u -> u.namespace().equals("gemini")),
                "the proxy's startup report does not call the Google default unresolved");
    }

    @Test
    void theAccountIsDescribedByItsSessionNeverItsTokens() {
        var store = new GoogleSessionStore(() -> SpawnConfig.configDir().resolve("gemini"));
        assertEquals("Google account, not signed in yet: run agy in an instance", GeminiSetup.describeGoogleAccount(store, "home"));
        store.create("home", new GoogleSessionStore.Tokens("ya29.secret", "1//secret", 1, "me@example.invalid"));
        assertEquals("Google account, signed in as me@example.invalid", GeminiSetup.describeGoogleAccount(store, "home"));
        store.create("home", new GoogleSessionStore.Tokens("ya29.secret", "1//secret", 1, ""));
        assertEquals("Google account, signed in", GeminiSetup.describeGoogleAccount(store, "home"));
    }
}
