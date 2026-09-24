package dev.incusspawn.ai;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiHelpClientTest {

    /** The provider of the account asked by default, or null when none can answer. */
    private static AiHelpClient.Provider provider(SpawnConfig config) {
        var targets = AiHelpClient.targets(config);
        return targets.isEmpty() ? null : targets.getFirst().provider();
    }

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
        assertNull(provider(config));
    }

    @Test
    void oauthTokenDoesNotShadowAnotherProvider() {
        var config = config();
        config.getClaude().setOauthToken(SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN);
        config.getOpenai().setApiKey("sk-openai-test");
        assertEquals(AiHelpClient.Provider.OPENAI, provider(config));
    }

    @Test
    void apiKeySelectsAnthropic() {
        var config = config();
        config.getClaude().setApiKey("sk-ant-api03-test");
        assertEquals(AiHelpClient.Provider.ANTHROPIC, provider(config));
    }

    @Test
    void vertexSelectsVertex() {
        var config = config();
        config.getClaude().setUseVertex(true);
        // Region and project are what make the account complete(), and an incomplete one is
        // deliberately never selected -- see incompleteVertexSelectsNothing below.
        config.getClaude().setCloudMlRegion("europe-west1");
        config.getClaude().setVertexProjectId("acme");
        assertEquals(AiHelpClient.Provider.VERTEX, provider(config));
    }

    @Test
    void incompleteVertexSelectsNothing() {
        // 'useVertex: true' with no region or project cannot serve a call: the request URI
        // would be built with empty path segments. It stays configured so ProxyMain can
        // report the misconfiguration, but it must not be picked as a provider here.
        var config = config();
        config.getClaude().setUseVertex(true);
        assertTrue(config.getClaude().isUseVertex());
        assertNull(provider(config));
    }

    @Test
    void noCredentialsSelectsNothing() {
        assertNull(provider(config()));
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

    @Test
    void targetsListEveryUsableAccountWithTheDefaultFirst() {
        var config = config();
        var claude = config.getClaude();
        claude.putAccount("personal", SpawnConfig.ClaudeAccount.ofOauth("sk-ant-oat01-abc"));
        claude.putAccount("console", SpawnConfig.ClaudeAccount.ofApiKey("sk-ant-api03-xyz"));
        claude.putAccount("gcp", SpawnConfig.ClaudeAccount.ofVertex("europe-west1", "acme"));
        claude.setDefaultAccount("gcp");
        config.getOpenai().setApiKey("sk-openai-test");

        var targets = AiHelpClient.targets(config);

        assertEquals(List.of(
                new AiHelpClient.Target(AiHelpClient.Provider.VERTEX, "gcp", AiHelpClient.VERTEX_MODEL),
                new AiHelpClient.Target(AiHelpClient.Provider.ANTHROPIC, "console", AiHelpClient.ANTHROPIC_MODEL),
                new AiHelpClient.Target(AiHelpClient.Provider.OPENAI, "", AiHelpClient.OPENAI_MODEL)),
                targets);
        assertEquals(AiHelpClient.Provider.VERTEX, provider(config));
        assertEquals(List.of("personal"), AiHelpClient.subscriptionAccounts(config));
    }

    @Test
    void labelsNameTheAccountUnlessItIsTheLegacyOne() {
        var named = new AiHelpClient.Target(AiHelpClient.Provider.ANTHROPIC, "work", "claude-sonnet-5");
        assertEquals("Anthropic API key · \"work\" · claude-sonnet-5", named.label());

        var config = config();
        config.getClaude().setApiKey("sk-ant-api03-test");
        assertEquals("Anthropic API key · " + AiHelpClient.ANTHROPIC_MODEL,
                AiHelpClient.targets(config).getFirst().label());
    }

    @Test
    void everySystemBlockEndsInACacheBreakpoint() {
        var system = AiHelpClient.cachedSystem(List.of("docs", "definitions"));
        assertEquals(2, system.size());
        for (int i = 0; i < system.size(); i++) {
            assertEquals("text", system.get(i).path("type").asText());
            assertEquals("ephemeral", system.get(i).path("cache_control").path("type").asText());
            // No explicit TTL: the 5-minute default suits help traffic (see cachedSystem).
            assertTrue(system.get(i).path("cache_control").path("ttl").isMissingNode());
        }
        assertEquals("docs", system.get(0).path("text").asText());
    }

    @Test
    void messagesStreamReportsTextAndCacheUsage() throws Exception {
        var stream = sse("""
                {"type":"message_start","message":{"usage":{"input_tokens":12,"cache_read_input_tokens":48000,"cache_creation_input_tokens":3100,"output_tokens":1}}}
                {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}
                {"type":"content_block_stop","index":0}
                {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":312}}
                {"type":"message_stop"}
                """);
        var text = new StringBuilder();
        var usage = AiHelpClient.processStream(stream, text::append);
        assertEquals("Hello there", text.toString());
        assertEquals(new AiHelpClient.Usage(12, 48000, 3100, 312), usage);
        assertEquals(51112, usage.totalInputTokens());
    }

    @Test
    void openAiStreamReportsCachedTokens() throws Exception {
        var stream = sse("""
                {"choices":[{"delta":{"content":"Hi"}}],"usage":null}
                {"choices":[],"usage":{"prompt_tokens":50000,"completion_tokens":200,"prompt_tokens_details":{"cached_tokens":49000}}}
                """);
        var text = new StringBuilder();
        var usage = AiHelpClient.processOpenAiStream(stream, text::append);
        assertEquals("Hi", text.toString());
        assertEquals(new AiHelpClient.Usage(1000, 49000, 0, 200), usage);
    }

    private static java.io.InputStream sse(String jsonLines) {
        var sb = new StringBuilder();
        jsonLines.lines().forEach(l -> sb.append("data: ").append(l).append("\n\n"));
        sb.append("data: [DONE]\n\n");
        return new java.io.ByteArrayInputStream(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
