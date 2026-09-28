package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A pinned instance's credentials come from the config the proxy loaded, and only a reload
 * replaces them (#837). Startup used to leave the per-account state to a lazy read on the
 * first pinned request, which escaped the config fingerprint and could land after a reload,
 * putting the old config back.
 */
class PinnedAccountStateTest {

    private static final String PINNED_IP = "10.0.0.5";
    private static final String ANTHROPIC = "api.anthropic.com";

    @TempDir
    Path home;
    private String originalHome;
    private Path configYaml;

    @BeforeEach
    void isolateHome() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        configYaml = Files.createDirectories(home.resolve(".config/incus-spawn")).resolve("config.yaml");
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    private void writeConfig(String workKey) throws Exception {
        Files.writeString(configYaml, """
                claude:
                  accounts:
                    personal:
                      type: api-key
                      apiKey: "sk-ant-api03-personal"
                    work:
                      type: api-key
                      apiKey: "%s"
                  default: personal
                """.formatted(workKey));
    }

    private static MitmProxy proxyWithWorkPinnedInstance() {
        var proxy = new MitmProxy(null, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        var registry = new InstanceRegistry(new IncusClient() {
            @Override
            public String listJsonConfig() {
                return """
                        [{"name":"work-box","config":{
                           "user.incus-spawn.static-ip":"%s",
                           "user.incus-spawn.account.claude":"work"}}]
                        """.formatted(PINNED_IP);
            }
        });
        registry.refresh();
        proxy.useInstanceRegistry(registry);
        return proxy;
    }

    private static String servedKey(MitmProxy proxy) {
        return proxy.contextFor(ANTHROPIC, PINNED_IP).creds().anthropicApiKey();
    }

    @Test
    void theFirstPinnedRequestIsServedFromTheLoadedConfigNotALaterRead() throws Exception {
        writeConfig("sk-ant-api03-work-v1");
        var proxy = proxyWithWorkPinnedInstance();

        // Edited after the load, before any pinned request: a lazy read would pick this up.
        writeConfig("sk-ant-api03-work-v2");

        assertEquals("sk-ant-api03-work-v1", servedKey(proxy));
        assertEquals("sk-ant-api03-personal",
                proxy.contextFor(ANTHROPIC, "10.0.0.99").creds().anthropicApiKey());
    }

    @Test
    void aReloadReplacesCachedPinnedCredentials() throws Exception {
        writeConfig("sk-ant-api03-work-v1");
        var proxy = proxyWithWorkPinnedInstance();
        assertEquals("sk-ant-api03-work-v1", servedKey(proxy));

        writeConfig("sk-ant-api03-work-v2");
        proxy.reload();

        assertEquals("sk-ant-api03-work-v2", servedKey(proxy));
    }
}
