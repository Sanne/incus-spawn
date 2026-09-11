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
        assertEquals(AiHelpClient.Provider.VERTEX, AiHelpClient.detectProvider(config));
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
