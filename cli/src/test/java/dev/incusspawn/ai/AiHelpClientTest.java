package dev.incusspawn.ai;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiHelpClientTest {

    private static SpawnConfig config() {
        return new SpawnConfig();
    }

    @Test
    void oauthTokenAloneIsNotAUsableProvider() {
        // A Pro/Max token is only valid for Claude Code itself; the Messages API answers
        // anything else with an opaque HTTP 429, so it must never be selected here.
        var config = config();
        config.getClaude().setOauthToken(SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN);
        assertTrue(config.getClaude().isOauthMode());
        assertNull(AiHelpClient.detectProvider(config));
    }

    @Test
    void oauthTokenDoesNotShadowAnotherProvider() {
        var config = config();
        config.getClaude().setOauthToken(SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN);
        config.getOpenai().setApiKey("sk-openai-test");
        assertEquals(AiHelpClient.Provider.OPENAI, AiHelpClient.detectProvider(config));
    }

    @Test
    void apiKeySelectsAnthropic() {
        var config = config();
        config.getClaude().setApiKey("sk-ant-api03-test");
        assertEquals(AiHelpClient.Provider.ANTHROPIC, AiHelpClient.detectProvider(config));
    }

    @Test
    void vertexSelectsVertex() {
        var config = config();
        config.getClaude().setUseVertex(true);
        // Region and project are what make the account complete(), and an incomplete one is
        // deliberately never selected -- see incompleteVertexSelectsNothing below.
        config.getClaude().setCloudMlRegion("europe-west1");
        config.getClaude().setVertexProjectId("acme");
        assertEquals(AiHelpClient.Provider.VERTEX, AiHelpClient.detectProvider(config));
    }

    @Test
    void incompleteVertexSelectsNothing() {
        // 'useVertex: true' with no region or project cannot serve a call: the request URI
        // would be built with empty path segments. It stays configured so ProxyMain can
        // report the misconfiguration, but it must not be picked as a provider here.
        var config = config();
        config.getClaude().setUseVertex(true);
        assertTrue(config.getClaude().isUseVertex());
        assertNull(AiHelpClient.detectProvider(config));
    }

    @Test
    void noCredentialsSelectsNothing() {
        assertNull(AiHelpClient.detectProvider(config()));
    }

    @Test
    void oauthOnlyConfigIsNotReportedAsMissingCredentials() {
        var config = config();
        config.getClaude().setOauthToken(SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN);
        var message = AiHelpClient.noProviderMessage(config);
        assertTrue(message.contains("Claude Code"), message);
        assertFalse(message.startsWith("No AI credentials"), message);
    }

    @Test
    void emptyConfigIsReportedAsMissingCredentials() {
        assertTrue(AiHelpClient.noProviderMessage(config()).startsWith("No AI credentials"));
    }
}
