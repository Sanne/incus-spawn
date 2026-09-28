package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The one path by which 'isx account set/unset' and the TUI change an instance's accounts. */
class ChangeAccountsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final String CONFIG = """
            claude:
              accounts:
                personal:
                  type: oauth
                  oauthToken: "sk-ant-oat01-x"
                other-sub:
                  type: oauth
                  oauthToken: "sk-ant-oat01-y"
                acme:
                  type: api-key
                  apiKey: "sk-ant-api-x"
              default: personal
            github:
              accounts:
                me:
                  token: "ghp_me"
                bot:
                  token: "ghp_bot"
              default: me
            """;

    private static final String NAME = "review-1";

    private static FakeIncusDaemon daemon(Map<String, String> config) {
        return new FakeIncusDaemon().container(NAME, config);
    }

    private static String get(FakeIncusDaemon daemon, String key) {
        var value = daemon.instance(NAME).path("config").path(key);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Map<String, String> change(FakeIncusDaemon daemon, Map<String, String> changes) throws Exception {
        var config = YAML.readValue(CONFIG, SpawnConfig.class);
        return InstanceLifecycle.changeAccounts(daemon.client(), NAME, config,
                dev.incusspawn.config.AccountSelection.namespaceSetups(config), changes, msg -> { }, msg -> { });
    }

    @Test
    void pinningMergesWithWhatIsAlreadyPinned() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountKey("github"), "bot"));
        var after = change(daemon, Map.of("claude", "other-sub"));
        assertEquals(Map.of("github", "bot", "claude", "other-sub"), after);
        assertEquals("bot", get(daemon, Metadata.accountKey("github")), "naming one namespace must not unpin another");
        assertEquals("other-sub", get(daemon, Metadata.accountKey("claude")));
    }

    @Test
    void aNullAccountRemovesThePinRatherThanBlankingIt() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountKey("github"), "bot",
                Metadata.accountKey("claude"), "personal"));
        var changes = new HashMap<String, String>();
        changes.put("github", null);
        var after = change(daemon, changes);
        assertEquals(Map.of("claude", "personal"), after);
        assertNull(get(daemon, Metadata.accountKey("github")));
    }

    @Test
    void anUnknownAccountIsRefusedBeforeAnythingIsWritten() throws Exception {
        var daemon = daemon(Map.of());
        daemon.clearRequests();
        assertThrows(AccountResolver.UnknownAccountException.class,
                () -> change(daemon, Map.of("github", "ghost")));
        assertTrue(daemon.requests().stream().noneMatch(r -> r.startsWith("PATCH") || r.startsWith("PUT")),
                daemon.requests().toString());
    }

    /** Built for a Pro/Max token: an API-key account would need a different environment. */
    @Test
    void aClaudeModeTheBuildDidNotBakeIsRefused() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountIdentityKey("claude"), "oauth"));
        var e = assertThrows(AccountSelection.InvalidSelectionException.class,
                () -> change(daemon, Map.of("claude", "acme")));
        assertTrue(e.getMessage().contains("'api-key'"), e.getMessage());
        assertNull(get(daemon, Metadata.accountKey("claude")));
    }

    /** Unpinning is refused on the same grounds: the default may be the other mode. */
    @Test
    void unpinningToADefaultOfAnotherModeIsRefused() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountIdentityKey("claude"), "api-key",
                Metadata.accountKey("claude"), "acme"));
        var changes = new LinkedHashMap<String, String>();
        changes.put("claude", null);
        var e = assertThrows(AccountSelection.InvalidSelectionException.class, () -> change(daemon, changes));
        assertTrue(e.getMessage().contains("the default account"), e.getMessage());
        assertEquals("acme", get(daemon, Metadata.accountKey("claude")));
    }

    /** Explicitly choosing the account a template pinned makes it the user's choice. */
    @Test
    void reChoosingATemplatesAccountRecordsItAsExplicit() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountKey("github"), "bot",
                Metadata.accountOriginKey("github"), "template:tpl-acme"));
        change(daemon, Map.of("github", "bot"));
        assertEquals("bot", get(daemon, Metadata.accountKey("github")));
        assertEquals("explicit", get(daemon, Metadata.accountOriginKey("github")));
    }

    @Test
    void noChangeWritesNothing() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountKey("github"), "bot",
                Metadata.accountOriginKey("github"), "explicit"));
        daemon.clearRequests();
        change(daemon, Map.of("github", "bot"));
        assertTrue(daemon.requests().stream().noneMatch(r -> r.startsWith("PATCH") || r.startsWith("PUT")),
                daemon.requests().toString());
    }

    @Test
    void aChangeIsRecordedAsExplicitAndLeavesOtherOriginsAlone() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountKey("github"), "bot",
                Metadata.accountOriginKey("github"), "template:tpl-acme"));
        change(daemon, Map.of("claude", "other-sub"));
        assertEquals("explicit", get(daemon, Metadata.accountOriginKey("claude")));
        assertEquals("template:tpl-acme", get(daemon, Metadata.accountOriginKey("github")),
                "a pin nobody touched keeps who chose it");
    }

    @Test
    void unpinningRemovesTheOriginWithThePin() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountKey("github"), "bot",
                Metadata.accountOriginKey("github"), "explicit"));
        var changes = new HashMap<String, String>();
        changes.put("github", null);
        change(daemon, changes);
        assertNull(get(daemon, Metadata.accountKey("github")));
        assertNull(get(daemon, Metadata.accountOriginKey("github")));
    }

    /** A pin nobody is changing does not block changing another, even when it is broken. */
    @Test
    void aBrokenPinElsewhereDoesNotBlockAChange() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountKey("github"), "removed-since",
                Metadata.accountIdentityKey("github"), "removed-since",
                Metadata.accountIdentityKey("claude"), "oauth"));
        var after = change(daemon, Map.of("claude", "other-sub"));
        assertEquals("other-sub", after.get("claude"));
        assertEquals("removed-since", after.get("github"), "left as it was, for the user to repair");
    }

    /** ...and the change that repairs it goes through too. */
    @Test
    void aBrokenPinCanBeRepaired() throws Exception {
        var daemon = daemon(Map.of(Metadata.accountKey("github"), "removed-since"));
        var changes = new HashMap<String, String>();
        changes.put("github", null);
        change(daemon, changes);
        assertNull(get(daemon, Metadata.accountKey("github")));
    }
}
