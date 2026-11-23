package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code proxy.configuration} list in {@code config.yaml} ({@code for-each}, #1419) is a
 * credential like any secret key: an account holding an item is configured, moving a flat list
 * into the accounts layout keeps every item, and each item's secret is redacted from a bundle.
 */
class ForEachListConfigTest {

    /** A secret field under a name the key-name backstop does not catch, so only the declaration redacts it. */
    private static final String TOOL = """
            name: servers
            proxy:
              config-namespace: srv
              configuration:
                servers:
                  config-path: servers
                  description: "servers"
                  for-each:
                    host: { description: "host" }
                    code: { description: "code", secret: true }
              auth:
                - for-each: servers
                  domains: ["${servers.host}"]
                  type: bearer
                  token: "${servers.code}"
            """;

    private static ToolSetup tool() throws Exception {
        return new YamlToolSetup(ToolDef.loadFromStream(new ByteArrayInputStream(TOOL.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aListWithAnItemIsTheAccountsCredential() throws Exception {
        var shape = AccountShape.declaredBy(tool().proxy());
        assertTrue(shape.hasCredential(Map.of("servers.one.host", "a.example.test", "servers.one.code", "c1")));
        assertFalse(shape.hasCredential(Map.of()));
        var config = new SpawnConfig();
        config.setConfigByPath("srv.servers.one.host", "a.example.test");
        assertTrue(shape.hasFlatCredential(config.tree().getField("srv")));
    }

    @Test
    void movingAFlatListIntoAccountsKeepsEveryItem() throws Exception {
        var shape = AccountShape.declaredBy(tool().proxy());
        var config = new SpawnConfig();
        config.setConfigByPath("srv.servers.one.host", "a.example.test");
        config.setConfigByPath("srv.servers.one.code", "c1");
        config.setConfigByPath("srv.servers.two.host", "b.example.test");
        config.setConfigByPath("srv.servers.two.code", "c2");
        NamespaceAccounts.materialize(config, "srv", shape);
        var tree = config.tree();
        assertEquals("c1", AccountResolver.navigate(tree, "srv.accounts.default.servers.one.code"));
        assertEquals("b.example.test", AccountResolver.navigate(tree, "srv.accounts.default.servers.two.host"));
        assertEquals("", AccountResolver.navigate(tree, "srv.servers.one.code"));
    }

    @Test
    void everyItemsSecretIsRedactedFromABundle() throws Exception {
        var config = new SpawnConfig();
        config.setConfigByPath("srv.servers.one.host", "a.example.test");
        config.setConfigByPath("srv.servers.one.code", "zz-first-secret");
        config.setConfigByPath("srv.accounts.work.servers.two.code", "zz-second-secret");
        var locations = SecretRegistry.locations(Map.of("servers", tool()));
        assertEquals(List.of("srv.servers.code"), locations.stream().map(SecretRegistry.SecretLocation::path).toList());
        var yaml = SecretRedactor.redactConfig(config, locations).yaml();
        assertFalse(yaml.contains("zz-first-secret"), yaml);
        assertFalse(yaml.contains("zz-second-secret"), yaml);
        assertTrue(yaml.contains("a.example.test"), "a host is not a secret: " + yaml);
    }

    @Test
    void aListsItemsAreReadKeys() throws Exception {
        var config = new SpawnConfig();
        config.setConfigByPath("srv.servers.one.host", "a.example.test");
        config.setConfigByPath("srv.accounts.work.servers.two.code", "c2");
        assertEquals(List.of(), UnreadConfigKeys.in(config.tree(), "srv", Map.of("servers", tool())));
    }
}
