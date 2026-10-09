package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a build container is stamped with before it starts, so the proxy serves it its
 * template's accounts (#903), and what the finished template keeps.
 */
@ExtendWith(TempHome.class)
class BuildAccountsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theTemplatesPinsAreStampedWithTheTemplateAsTheirOrigin() {
        var updates = BuildAccounts.startConfig(JSON.createObjectNode(),
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
        var updates = BuildAccounts.startConfig(config, Map.of(), "tpl-q");
        assertTrue(updates.containsKey(Metadata.accountIdentityKey("claude")));
        assertNull(updates.get(Metadata.accountIdentityKey("claude")));
        assertTrue(updates.containsKey(Metadata.accountIdentityKey("github")));
        assertNull(updates.get(Metadata.accountIdentityKey("github")));
        assertFalse(updates.containsKey("limits.memory"));
    }

    @Test
    void theParentsBakedIdentitiesAreKeptForTheRebakeBeforeTheyAreCleared() {
        // settleIdentities compares these with the build's own accounts after tool
        // setup, when the pre-start write has long removed them from the container
        var config = JSON.createObjectNode()
                .put(Metadata.accountIdentityKey("github"), "me#0123456789ab")
                .put(Metadata.accountKey("github"), "bot");
        assertEquals(Map.of("github", "me#0123456789ab"), BuildAccounts.inheritedIdentities(config));
    }

    @Test
    void noSelectionLeavesCopiedPinsAlone() {
        var config = JSON.createObjectNode().put(Metadata.accountKey("github"), "bot");
        assertTrue(BuildAccounts.startConfig(config, Map.of(), "tpl-q").isEmpty());
    }

    @Test
    void aSelectionReplacesThePinsItWasCopiedWith() {
        var config = JSON.createObjectNode().put(Metadata.accountKey("openai"), "old");
        var updates = BuildAccounts.startConfig(config, Map.of("github", "bot"), "tpl-q");
        assertTrue(updates.containsKey(Metadata.accountKey("openai")));
        assertNull(updates.get(Metadata.accountKey("openai")));
        assertEquals("bot", updates.get(Metadata.accountKey("github")));
    }

    @Test
    void aPinnedBuildIsKnownToTheProxyBeforeItStarts() {
        var daemon = new FakeIncusDaemon().container("tpl-q-rebuilding",
                Map.of(Metadata.accountIdentityKey("claude"), "oauth"));
        var incus = daemon.client();
        var seenByTheProxy = new ArrayList<String>();

        var started = BuildAccounts.start(incus, "tpl-q-rebuilding", Map.of("github", "bot"), "tpl-q", () -> {
            var instance = daemon.instance("tpl-q-rebuilding");
            seenByTheProxy.add(instance.path("status").asText() + " "
                    + instance.path("config").path(Metadata.STATIC_IP).asText() + " "
                    + instance.path("config").path(Metadata.accountKey("github")).asText());
        });

        assertEquals(List.of("Stopped " + started.address() + " bot"), seenByTheProxy,
                "the proxy is told the address and pins before the build starts");
        assertEquals(List.of("tpl-q-rebuilding start"), daemon.stateActions());
        var config = daemon.instance("tpl-q-rebuilding").path("config");
        assertEquals(started.address(), config.path(Metadata.STATIC_IP).asText());
        assertFalse(config.has(Metadata.accountIdentityKey("claude")),
                "a parent's stamp would have the proxy refuse this build its own account");
        assertEquals(Map.of("claude", "oauth"), started.inheritedIdentities());
    }

    @Test
    void aBuildPinningNothingKeepsDhcpAndItsStamps() {
        var daemon = new FakeIncusDaemon().container("tpl-q-rebuilding",
                Map.of(Metadata.accountIdentityKey("claude"), "oauth"));
        var refreshes = new ArrayList<String>();

        var started = BuildAccounts.start(daemon.client(), "tpl-q-rebuilding", Map.of(), "tpl-q",
                () -> refreshes.add("refresh"));

        assertNull(started.address());
        assertEquals(List.of(), refreshes);
        var instance = daemon.instance("tpl-q-rebuilding");
        assertFalse(instance.path("config").has(Metadata.STATIC_IP));
        assertEquals("oauth", instance.path("config").path(Metadata.accountIdentityKey("claude")).asText());
        assertEquals(List.of("GET /1.0/instances/tpl-q-rebuilding", "PUT /1.0/instances/tpl-q-rebuilding/state"),
                daemon.requests().stream().filter(r -> !r.startsWith("GET /1.0/operations/")).toList(),
                "served the defaults either way, it pays for none of the address's round trips");
    }

    @Test
    void pinsCopiedFromTheParentAreServedToo() {
        var daemon = new FakeIncusDaemon().container("tpl-q-rebuilding",
                Map.of(Metadata.accountKey("github"), "bot"));

        var started = BuildAccounts.start(daemon.client(), "tpl-q-rebuilding", Map.of(), "tpl-q", () -> {});

        assertNotNull(started.address());
    }

    @Test
    void aFinishedTemplateKeepsTheStampsItDidNotReplace() {
        // gh's credential is gone from config.yaml, so the build derived nothing for it, but the
        // parent's .gitconfig is still in the rootfs
        var stamps = BuildAccounts.identityStamps(Map.of("claude", "api-key"),
                Map.of("claude", "oauth", "github", "me#0123456789ab"), java.util.Set.of());
        assertEquals(Map.of(Metadata.accountIdentityKey("claude"), "api-key",
                Metadata.accountIdentityKey("github"), "me#0123456789ab"), stamps);
    }
}
