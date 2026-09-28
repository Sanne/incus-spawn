package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.GhSetup;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A GitHub token replaced in place -- same account, different user -- is a change of git
 * identity, just as switching to another account is (#281).
 */
class TokenRotationIdentityTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String NAME = "work-1";

    private static SpawnConfig config(String meToken, String meEmail) throws Exception {
        return YAML.readValue("""
                github:
                  accounts:
                    me:
                      token: "%s"
                      email: "%s"
                    bot:
                      token: "ghp_bot"
                  default: me
                """.formatted(meToken, meEmail), SpawnConfig.class);
    }

    private static SpawnConfig config(String meToken) throws Exception {
        return config(meToken, "me@example.com");
    }

    /** What a build stamps while {@code config} is in effect. */
    private static String builtWith(SpawnConfig config, String account) {
        return new GhSetup().bakedAccountIdentity(config, account);
    }

    private static Map<String, String> stale(FakeIncusDaemon daemon, SpawnConfig config) {
        return AccountSelection.staleIdentities(config, daemon.client(), NAME);
    }

    @Test
    void switchingAccountMarksTheIdentityStale() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), builtWith(config("ghp_userA"), "me"),
                Metadata.accountKey("github"), "bot"));
        assertEquals(Map.of("github", "bot"), stale(daemon, config("ghp_userA")));
    }

    /** The issue's steps: built while 'me' held user A's token, then 'me' was given user B's. */
    @Test
    void rotatingTheTokenInPlaceMarksTheIdentityStale() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), builtWith(config("ghp_userA"), "me")));
        assertEquals(Map.of("github", "me"), stale(daemon, config("ghp_userB")),
                "with user B's token in the same account the instance would keep committing as A");
    }

    @Test
    void changingTheConfiguredEmailMarksTheIdentityStale() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), builtWith(config("ghp_userA"), "me")));
        assertEquals(Map.of("github", "me"), stale(daemon, config("ghp_userA", "other@example.com")));
    }

    @Test
    void anUnchangedAccountIsNotStale() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), builtWith(config("ghp_userA"), "me")));
        assertEquals(Map.of(), stale(daemon, config("ghp_userA")));
    }

    /**
     * Stamped by a build that recorded only the account name: whether its token has been
     * replaced since cannot be told, so the identity is re-derived once.
     */
    @Test
    void aStampWithoutAFingerprintIsReDerivedOnce() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), "me"));
        assertEquals(Map.of("github", "me"), stale(daemon, config("ghp_userA")));
    }

    @Test
    void theFingerprintNeverContainsTheToken() throws Exception {
        var identity = builtWith(config("ghp_userA"), "me");
        assertTrue(identity.matches("me#[0-9a-f]{12}"), identity);
        assertFalse(identity.contains("userA"), identity);
    }

    @Test
    void aRenameKeepsTheFingerprint() {
        var gh = new GhSetup();
        assertEquals("acme-bot#0123456789ab", gh.renameBakedIdentity("bot#0123456789ab", "bot", "acme-bot"));
        assertEquals("acme-bot", gh.renameBakedIdentity("bot", "bot", "acme-bot"));
        assertNull(gh.renameBakedIdentity("bots#0123456789ab", "bot", "acme-bot"), "another account");
        assertNull(gh.renameBakedIdentity("bot#extra#0123456789ab", "bot", "acme-bot"), "another account");
    }
}
