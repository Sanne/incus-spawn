package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A renamed account is followed onto the instances that pin it. */
class AccountRenameInInstancesTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The config as it reads after the rename: 'bot' is now 'acme-bot'. */
    private static final String RENAMED = """
            claude:
              accounts:
                oauth:
                  type: api-key
                  apiKey: "sk-ant-api-x"
                vertex:
                  type: api-key
                  apiKey: "sk-ant-api-y"
              default: oauth
            github:
              accounts:
                me:
                  token: "ghp_me"
                acme-bot:
                  token: "ghp_bot"
              default: me
            """;

    private static String config(FakeIncusDaemon daemon, String instance, String key) {
        return daemon.instance(instance).path("config").path(key).asText("");
    }

    @Test
    void pinsAndTheGitIdentityFollowTheRename() throws Exception {
        var daemon = new FakeIncusDaemon()
                .container("pinned", Map.of(
                        Metadata.accountKey("github"), "bot",
                        Metadata.accountIdentityKey("github"), "bot"))
                .container("other", Map.of(Metadata.accountKey("github"), "me"))
                // Follows the default, but was built while 'bot' was the default.
                .container("built-as-bot", Map.of(Metadata.accountIdentityKey("github"), "bot"))
                .container("fingerprinted", Map.of(Metadata.accountIdentityKey("github"), "bot#0123456789ab"));
        var config = YAML.readValue(RENAMED, SpawnConfig.class);

        var repointed = AccountSelection.renameInInstances(daemon.client(), config, "github", "bot", "acme-bot");

        assertEquals(List.of("pinned"), repointed);
        assertEquals("acme-bot", config(daemon, "pinned", Metadata.accountKey("github")));
        assertEquals("acme-bot", config(daemon, "pinned", Metadata.accountIdentityKey("github")),
                "GitHub's baked identity names the account; left alone, the rename would read as a"
                        + " change of identity and re-derive for nothing");
        assertEquals("me", config(daemon, "other", Metadata.accountKey("github")));
        assertEquals("acme-bot", config(daemon, "built-as-bot", Metadata.accountIdentityKey("github")));
        assertEquals("", config(daemon, "built-as-bot", Metadata.accountKey("github")),
                "an instance that followed the default must not gain a pin");
        assertEquals("acme-bot#0123456789ab", config(daemon, "fingerprinted", Metadata.accountIdentityKey("github")),
                "the fingerprint describes the same token, so it carries over; a fresh one would hide"
                        + " a token that was replaced before the rename");
    }

    /**
     * Claude bakes an auth mode, not the account; an account whose name happens to be a mode
     * must never have its rename written over a mode stamp.
     */
    @Test
    void claudeModeStampsAreNeverTreatedAsAccountNames() throws Exception {
        var daemon = new FakeIncusDaemon()
                .container("built-oauth", Map.of(Metadata.accountIdentityKey("claude"), "oauth"));
        var config = YAML.readValue(RENAMED.replace("    oauth:\n", "    renamed:\n")
                .replace("default: oauth", "default: renamed"), SpawnConfig.class);

        AccountSelection.renameInInstances(daemon.client(), config, "claude", "oauth", "vertex");

        assertEquals("oauth", config(daemon, "built-oauth", Metadata.accountIdentityKey("claude")));
    }

    /**
     * An incomplete account -- its token not set yet -- is still renamed on every instance
     * pinned to it; otherwise they would name an account that no longer exists.
     */
    @Test
    void anIncompleteAccountsPinsFollowTheRename() throws Exception {
        var daemon = new FakeIncusDaemon().container("pinned", Map.of(
                Metadata.accountKey("github"), "bot", Metadata.accountIdentityKey("github"), "bot"));
        var config = YAML.readValue("""
                github:
                  accounts:
                    me:
                      token: "ghp_me"
                    acme-bot:
                      email: "bot@acme.example"
                  default: me
                """, SpawnConfig.class);

        var repointed = AccountSelection.renameInInstances(daemon.client(), config, "github", "bot", "acme-bot");

        assertEquals(List.of("pinned"), repointed);
        assertEquals("acme-bot", config(daemon, "pinned", Metadata.accountKey("github")));
    }
}
