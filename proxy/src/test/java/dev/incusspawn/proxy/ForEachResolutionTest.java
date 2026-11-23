package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool that repeats its auth entry over a list of servers in {@code config.yaml}
 * ({@code for-each}, #1419): each server's key goes to its own host alone, every host passes the
 * checks a configured domain does, and what an {@code mcp:} entry is registered for is what the
 * proxy serves.
 */
class ForEachResolutionTest {

    /** Yoann's Develocity tool, in the form this change gives it. */
    static final String DEVELOCITY = """
            name: develocity
            env:
              - name: DEVELOCITY_TOKEN
                value: develocity-placeholder
            proxy:
              config-namespace: develocity
              placeholders:
                - env: DEVELOCITY_TOKEN
                  prefix: develocity-
              configuration:
                servers:
                  config-path: servers
                  description: "Develocity servers"
                  for-each:
                    host:
                      description: "Server host"
                    access-key:
                      config-path: accessKey
                      description: "Access key"
                      secret: true
              auth:
                - for-each: servers
                  domains: ["${servers.host}"]
                  paths: [/mcp]
                  type: bearer
                  token: "${servers.access-key}"
            mcp:
              - for-each: servers
                name: "develocity-${servers.label}"
                url: "https://${servers.host}/mcp"
                bearer-token-env: DEVELOCITY_TOKEN
            """;

    static ToolSetup load(String yaml) throws Exception {
        return new YamlToolSetup(ToolDef.loadFromStream(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    }

    private static SpawnConfig twoServers() {
        var config = new SpawnConfig();
        config.setConfigByPath("develocity.servers.quarkus.host", "ge.quarkus.io");
        config.setConfigByPath("develocity.servers.quarkus.accessKey", "key-quarkus");
        config.setConfigByPath("develocity.servers.commonhaus.host", "Develocity.Commonhaus.dev");
        config.setConfigByPath("develocity.servers.commonhaus.accessKey", "key-commonhaus");
        return config;
    }

    private static Map<String, String> headersByDomain(List<ResolvedToolProxy> resolved) {
        return resolved.stream().collect(Collectors.toMap(ResolvedToolProxy::domain, ResolvedToolProxy::computeHeaderValue));
    }

    @Test
    void eachServersKeyGoesToItsOwnHostAlone() throws Exception {
        var resolved = ToolProxyResolver.resolve(twoServers(), Map.of("develocity", load(DEVELOCITY)));
        assertEquals(Map.of("ge.quarkus.io", "Bearer key-quarkus",
                        "develocity.commonhaus.dev", "Bearer key-commonhaus"),
                headersByDomain(resolved));
        resolved.forEach(r -> assertEquals(java.util.Set.of("develocity"), r.namespaces()));
        resolved.forEach(r -> assertEquals(List.of("/mcp"), r.auth().getPaths()));
    }

    @Test
    void aHostAnotherServerTookIsDropped() throws Exception {
        var config = twoServers();
        config.setConfigByPath("develocity.servers.commonhaus.host", "ge.quarkus.io");
        var resolved = ToolProxyResolver.resolve(config, Map.of("develocity", load(DEVELOCITY)));
        assertEquals(Map.of("ge.quarkus.io", "Bearer key-quarkus"), headersByDomain(resolved));
    }

    @Test
    void aHostIsxOrAnotherToolServesIsDropped() throws Exception {
        var tools = new LinkedHashMap<String, ToolSetup>();
        tools.put("other", load("""
                name: other
                proxy:
                  config-namespace: other
                  configuration:
                    token:
                      config-path: token
                      secret: true
                  auth:
                    - domains: [api.other.example]
                      type: bearer
                      token: "${token}"
                """));
        tools.put("develocity", load(DEVELOCITY));
        var config = twoServers();
        config.setConfigByPath("develocity.servers.quarkus.host", "api.other.example");
        config.setConfigByPath("develocity.servers.commonhaus.host", "registry.npmjs.org");
        config.setConfigByPath("other.token", "other-real");
        var resolved = ToolProxyResolver.resolve(config, tools);
        assertEquals(Map.of("api.other.example", "Bearer other-real"), headersByDomain(resolved));
    }

    @Test
    void aServerWithoutItsKeyOrWithABadLabelIsSkipped() throws Exception {
        var config = twoServers();
        config.removeConfigPath("develocity.servers.commonhaus.accessKey");
        config.setConfigByPath("develocity.servers.Bad_Label.host", "bad.example.test");
        config.setConfigByPath("develocity.servers.Bad_Label.accessKey", "key-bad");
        var resolved = ToolProxyResolver.resolve(config, Map.of("develocity", load(DEVELOCITY)));
        assertEquals(Map.of("ge.quarkus.io", "Bearer key-quarkus"), headersByDomain(resolved));
    }

    @Test
    void aServerIsNeverAWildcardNorAnAddress() throws Exception {
        var config = twoServers();
        config.setConfigByPath("develocity.servers.quarkus.host", "*.quarkus.io");
        config.setConfigByPath("develocity.servers.commonhaus.host", "10.0.0.1");
        assertEquals(List.of(), ToolProxyResolver.resolve(config, Map.of("develocity", load(DEVELOCITY))));
    }

    @Test
    void anAccountsListIsTheOneItsSelectionServes() throws Exception {
        var config = new SpawnConfig();
        config.setConfigByPath("develocity.accounts.work.servers.quarkus.host", "ge.quarkus.io");
        config.setConfigByPath("develocity.accounts.work.servers.quarkus.accessKey", "key-work");
        config.setConfigByPath("develocity.accounts.home.servers.mine.host", "ge.example.test");
        config.setConfigByPath("develocity.accounts.home.servers.mine.accessKey", "key-home");
        config.setConfigByPath("develocity.default", "work");
        var tools = Map.of("develocity", load(DEVELOCITY));
        assertEquals(Map.of("ge.quarkus.io", "Bearer key-work"),
                headersByDomain(ToolProxyResolver.resolve(config, tools)));
        assertEquals(Map.of("ge.example.test", "Bearer key-home"),
                headersByDomain(ToolProxyResolver.resolve(config, tools, Map.of("develocity", "home"))));
        // Every account's hosts are intercepted, as the proxy decides them
        assertEquals(java.util.Set.of("ge.quarkus.io", "ge.example.test"),
                ToolProxyResolver.resolveAcrossAccounts(config, tools).stream()
                        .map(ResolvedToolProxy::domain).collect(Collectors.toSet()));
    }

    @Test
    void anEmptyListIsReportedMissingAndAFullOneIsNot() throws Exception {
        var tools = Map.of("develocity", load(DEVELOCITY));
        var empty = new SpawnConfig();
        var missing = ToolProxyResolver.missingSecrets(empty.tree(), tools, List.of("develocity"), Map.of());
        assertEquals(List.of("develocity.servers"), missing.stream().map(ToolProxyResolver.MissingSecret::path).toList());
        assertEquals(List.of("servers"), ToolProxyResolver.findUnresolved(empty.tree(), tools).stream()
                .map(ToolProxyResolver.UnresolvedToolProxy::configKey).toList());

        var full = twoServers();
        assertEquals(List.of(), ToolProxyResolver.missingSecrets(full.tree(), tools, List.of("develocity"), Map.of()));
        assertEquals(List.of(), ToolProxyResolver.findUnresolved(full.tree(), tools));
    }

    @Test
    void whatAnMcpEntryIsRegisteredForIsWhatTheProxyServesWithoutTheKey() throws Exception {
        var tool = load(DEVELOCITY);
        var config = twoServers();
        config.setConfigByPath("develocity.servers.taken.host", "registry.npmjs.org");
        config.setConfigByPath("develocity.servers.taken.accessKey", "key-taken");
        var resolved = ToolProxyResolver.resolve(config, Map.of("develocity", tool));
        var items = ToolProxyResolver.servedItems(tool.proxy(), "develocity", "servers", "servers.host", resolved);
        assertEquals(List.of(
                        Map.of("servers.label", "quarkus", "servers.host", "ge.quarkus.io"),
                        Map.of("servers.label", "commonhaus", "servers.host", "develocity.commonhaus.dev")),
                items.stream().map(ToolProxyResolver.ListItem::values).toList());
    }

    @Test
    void aNestedIsxNeverInterceptsAListsHosts() throws Exception {
        // Inside an instance on inherit: the parent's host holds the list, this one none
        var config = new SpawnConfig();
        config.setConfigByPath("develocity.default", "inherit");
        var tools = Map.of("develocity", load(DEVELOCITY));
        var resolved = ToolProxyResolver.resolveAcrossAccounts(config.tree(), tools,
                ToolProxyResolver.resolve(config.tree(), tools, Map.of("develocity", "inherit")), true);
        // inheritable() adds no repeated entry: the hosts live in this host's config.yaml
        assertTrue(resolved.stream().noneMatch(r -> r.auth().getForEach() != null), resolved.toString());
        assertEquals(List.of(), ToolProxyResolver.resolve(config.tree(), tools, Map.of("develocity", "inherit")));
    }

    @Test
    void aServerNamesOneHostNeverSeveral() throws Exception {
        var config = twoServers();
        config.setConfigByPath("develocity.servers.commonhaus.host", "a.example.test,b.example.test");
        assertEquals(Map.of("ge.quarkus.io", "Bearer key-quarkus"),
                headersByDomain(ToolProxyResolver.resolve(config, Map.of("develocity", load(DEVELOCITY)))));
    }

    @Test
    void aListWhoseOnlyHostIsDroppedIsReportedMissing() throws Exception {
        var config = new SpawnConfig();
        config.setConfigByPath("develocity.servers.taken.host", "registry.npmjs.org");
        config.setConfigByPath("develocity.servers.taken.accessKey", "key-taken");
        var missing = ToolProxyResolver.missingSecrets(config.tree(), Map.of("develocity", load(DEVELOCITY)),
                List.of("develocity"), Map.of());
        assertEquals(List.of("develocity.servers"), missing.stream().map(ToolProxyResolver.MissingSecret::path).toList());
    }
}
