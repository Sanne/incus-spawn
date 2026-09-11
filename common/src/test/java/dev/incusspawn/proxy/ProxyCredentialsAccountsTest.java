package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Instances must keep using the default account even when another account exists that isx
 * itself prefers for direct API calls -- the two selections are independent.
 */
class ProxyCredentialsAccountsTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void proxyUsesTheDefaultAccountNotTheDirectApiOne() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    personal:
                      type: oauth
                      oauthToken: "sk-ant-oat01-abc"
                    console:
                      type: api-key
                      apiKey: "sk-ant-api03-xyz"
                  default: personal
                """;
        var config = YAML.readValue(yaml, SpawnConfig.class);
        var credentials = ProxyCredentials.fromConfig(config);

        assertEquals("sk-ant-oat01-abc", credentials.oauthToken());
        assertEquals("", credentials.anthropicApiKey());
        assertFalse(credentials.useVertex());
    }

    @Test
    void proxyFollowsAVertexDefault() throws Exception {
        var yaml = """
                claude:
                  accounts:
                    console:
                      type: api-key
                      apiKey: "sk-ant-api03-xyz"
                    work:
                      type: vertex
                      cloudMlRegion: europe-west1
                      vertexProjectId: acme
                  default: work
                """;
        var credentials = ProxyCredentials.fromConfig(YAML.readValue(yaml, SpawnConfig.class));
        assertTrue(credentials.useVertex());
        assertEquals("europe-west1", credentials.vertexRegion());
        assertEquals("acme", credentials.vertexProjectId());
        assertEquals("", credentials.anthropicApiKey());
    }

    @Test
    void legacyFlatConfigStillReachesTheProxyUnchanged() throws Exception {
        var yaml = """
                claude:
                  useVertex: false
                  oauthToken: "sk-ant-oat01-abc"
                """;
        var credentials = ProxyCredentials.fromConfig(YAML.readValue(yaml, SpawnConfig.class));
        assertEquals("sk-ant-oat01-abc", credentials.oauthToken());
    }
}
