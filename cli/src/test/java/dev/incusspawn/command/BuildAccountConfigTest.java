package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a build container is stamped with before it starts, so the proxy serves it its
 * template's accounts (#903).
 */
class BuildAccountConfigTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theTemplatesPinsAreStampedWithTheTemplateAsTheirOrigin() {
        var updates = BuildCommand.buildAccountConfig(JSON.createObjectNode(),
                Map.of("github", "bot"), "tpl-q");
        assertEquals("bot", updates.get(Metadata.accountKey("github")));
        assertEquals("template:tpl-q", updates.get(Metadata.accountOriginKey("github")));
    }

    @Test
    void theParentsBakedIdentitiesAreCleared() {
        // A copy of a parent built for another Claude auth mode: the proxy would refuse this
        // build the account its own template chose
        var config = JSON.createObjectNode()
                .put(Metadata.accountIdentityKey("claude"), "oauth")
                .put(Metadata.accountIdentityKey("github"), "me")
                .put("limits.memory", "8GiB");
        var updates = BuildCommand.buildAccountConfig(config, Map.of(), "tpl-q");
        assertTrue(updates.containsKey(Metadata.accountIdentityKey("claude")));
        assertNull(updates.get(Metadata.accountIdentityKey("claude")));
        assertTrue(updates.containsKey(Metadata.accountIdentityKey("github")));
        assertNull(updates.get(Metadata.accountIdentityKey("github")));
        assertFalse(updates.containsKey("limits.memory"));
    }

    @Test
    void theParentsBakedIdentitiesAreKeptForTheRebakeBeforeTheyAreCleared() {
        // refreshInheritedIdentities compares these with the build's own accounts after tool
        // setup, when the pre-start write has long removed them from the container
        var config = JSON.createObjectNode()
                .put(Metadata.accountIdentityKey("github"), "me#0123456789ab")
                .put(Metadata.accountKey("github"), "bot");
        assertEquals(Map.of("github", "me#0123456789ab"), BuildCommand.inheritedIdentities(config));
    }

    @Test
    void noSelectionLeavesCopiedPinsAlone() {
        var config = JSON.createObjectNode().put(Metadata.accountKey("github"), "bot");
        assertTrue(BuildCommand.buildAccountConfig(config, Map.of(), "tpl-q").isEmpty());
    }

    @Test
    void aSelectionReplacesThePinsItWasCopiedWith() {
        var config = JSON.createObjectNode().put(Metadata.accountKey("openai"), "old");
        var updates = BuildCommand.buildAccountConfig(config, Map.of("github", "bot"), "tpl-q");
        assertTrue(updates.containsKey(Metadata.accountKey("openai")));
        assertNull(updates.get(Metadata.accountKey("openai")));
        assertEquals("bot", updates.get(Metadata.accountKey("github")));
    }
}
