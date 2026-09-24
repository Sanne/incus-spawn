package dev.incusspawn.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.config.SpawnConfig;

import dev.incusspawn.proxy.ProxyConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class AiHelpClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile HttpClient httpClient;

    private static HttpClient client() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
        }
        return httpClient;
    }

    public enum Provider {
        ANTHROPIC("Anthropic API key"),
        OPENAI("OpenAI API key"),
        VERTEX("Vertex AI");

        private final String credentialKind;

        Provider(String credentialKind) {
            this.credentialKind = credentialKind;
        }

        /** The kind of credential an account for this provider holds. */
        public String credentialKind() { return credentialKind; }
    }

    public record AiResponse(String content, Usage usage) {}

    /**
     * Input tokens split by how they were billed, as the provider reported them: the uncached
     * remainder, those read from the prompt cache (~0.1x), and those written to it (~1.25x).
     * OpenAI caches on its own and reports no writes.
     */
    public record Usage(long inputTokens, long cacheReadTokens, long cacheWriteTokens, long outputTokens) {
        public static final Usage NONE = new Usage(0, 0, 0, 0);

        public long totalInputTokens() {
            return inputTokens + cacheReadTokens + cacheWriteTokens;
        }
    }

    public static final String ANTHROPIC_MODEL = "claude-sonnet-5";
    public static final String VERTEX_MODEL = "claude-sonnet-5";
    public static final String OPENAI_MODEL = "gpt-4o-mini";

    /**
     * A configured account a question can be sent to. {@code accountName} is the Claude
     * account's name in {@code config.yaml}, or empty for OpenAI.
     */
    public record Target(Provider provider, String accountName, String model) {

        /**
         * Compact description for choosing between targets: the account's name as the user gave
         * it, with its kind -- {@code redhat (Vertex AI)}. The model is left out: it is fixed per
         * provider, so it never tells two accounts apart.
         */
        public String label() {
            // The legacy flat config has no user-chosen name, so the kind alone names it.
            var named = !accountName.isEmpty()
                    && !accountName.equals(SpawnConfig.ClaudeConfig.LEGACY_ACCOUNT_NAME);
            return named ? accountName + " (" + provider.credentialKind() + ")" : provider.credentialKind();
        }
    }

    /**
     * Every configured account that can answer, the one to use by default first: Claude
     * accounts that serve the API directly (in file order, the default account leading), then
     * OpenAI. Empty when nothing can answer -- see {@link #noProviderMessage}.
     *
     * <p>Selection is by what an account <em>is</em>, not by any designation: a Claude Pro/Max
     * OAuth account cannot serve this, because such a token is only valid for Claude Code
     * itself and the Messages API answers anything else with an opaque HTTP 429. So a Pro/Max
     * account keeps serving instances while an API-key or Vertex account answers here, without
     * either being set aside for the purpose.
     */
    public static List<Target> targets(SpawnConfig config) {
        var result = new ArrayList<Target>();
        var claude = config.getClaude();
        var accounts = claude.effectiveAccounts();
        var preferred = claude.accountNameFor(SpawnConfig.ClaudeAccount::servesDirectApi);
        if (!preferred.isEmpty()) result.add(claudeTarget(preferred, accounts.get(preferred)));
        accounts.forEach((name, account) -> {
            if (!name.equals(preferred) && account.servesDirectApi()) {
                result.add(claudeTarget(name, account));
            }
        });
        if (config.getOpenai().hasAuth()) result.add(new Target(Provider.OPENAI, "", OPENAI_MODEL));
        return result;
    }

    /**
     * Claude accounts that are configured but can never answer here: Pro/Max subscriptions,
     * whose token is only valid for Claude Code itself (see {@link #targets}).
     */
    public static List<String> subscriptionAccounts(SpawnConfig config) {
        var names = new ArrayList<String>();
        config.getClaude().effectiveAccounts().forEach((name, account) -> {
            if (account.effectiveType() == SpawnConfig.ClaudeAccountType.OAUTH) names.add(name);
        });
        return names;
    }

    private static Target claudeTarget(String name, SpawnConfig.ClaudeAccount account) {
        return account.effectiveType() == SpawnConfig.ClaudeAccountType.VERTEX
                ? new Target(Provider.VERTEX, name, VERTEX_MODEL)
                : new Target(Provider.ANTHROPIC, name, ANTHROPIC_MODEL);
    }

    /**
     * Explains why {@link #targets} found nothing usable. A Claude Pro/Max OAuth token
     * counts as configured credentials to the rest of isx, so "no credentials" would be a
     * confusing thing to tell that user -- name the real reason instead.
     */
    public static String noProviderMessage(SpawnConfig config) {
        var configured = config.getClaude().effectiveAccounts();
        if (configured.isEmpty()) {
            return "No AI credentials configured. "
                    + "Run 'isx init' to set up Anthropic, Vertex AI, or OpenAI credentials.";
        }
        // Describe what is actually configured rather than inferring it from the absence of a
        // provider -- a confidently wrong explanation is worse than a vague one. Both branches
        // are reachable: an account can be present but unusable (a flat 'useVertex: true'
        // missing its project is kept so ProxyMain can report it, but is not complete()).
        var allOauth = configured.values().stream()
                .allMatch(a -> a.effectiveType() == SpawnConfig.ClaudeAccountType.OAUTH);
        if (allOauth) {
            return "The configured Claude account"
                    + (configured.size() > 1 ? "s are all" : " is")
                    + " a Claude Pro/Max subscription, whose token is only valid for Claude Code"
                    + " itself and cannot answer here.\n"
                    + "Run 'isx init' to add an Anthropic API key or Vertex AI account alongside it.";
        }
        return "No configured Claude account can answer this. "
                + "Run 'isx init' to add an Anthropic API key or Vertex AI account.";
    }

    /**
     * Asks {@code target}, which must be one of {@link #targets}. {@code systemBlocks} are sent
     * as separately cacheable blocks where the provider supports it (see
     * {@link HelpContext#systemBlocks}).
     */
    public static AiResponse ask(String question, List<String> systemBlocks, SpawnConfig config,
                                 Target target) throws IOException {
        var sb = new StringBuilder();
        var usage = askStreaming(question, systemBlocks, config, target, sb::append);
        return new AiResponse(sb.toString(), usage);
    }

    /** Like {@link #ask}, handing the answer to {@code onChunk} as it arrives. */
    public static Usage askStreaming(String question, List<String> systemBlocks, SpawnConfig config,
                                     Target target, Consumer<String> onChunk) throws IOException {
        return switch (target.provider()) {
            case ANTHROPIC -> streamAnthropic(question, systemBlocks, target.model(),
                    claudeAccount(config, target), onChunk);
            case VERTEX -> streamVertex(question, systemBlocks, target.model(),
                    claudeAccount(config, target), onChunk);
            case OPENAI -> streamOpenAI(question, String.join("", systemBlocks), target.model(),
                    config.getOpenai(), onChunk);
        };
    }

    /**
     * The Messages API {@code system} field as text blocks, each ending in a cache breakpoint.
     * The documentation is the same for every question, so after the first one it is read from
     * the cache at a tenth of the price. The default 5-minute lifetime fits help traffic: a
     * cache read renews it, so follow-ups keep it warm, while the 1-hour lifetime doubles the
     * write cost for questions too sparse to repay it.
     */
    static com.fasterxml.jackson.databind.node.ArrayNode cachedSystem(List<String> systemBlocks) {
        var system = JSON.createArrayNode();
        for (var text : systemBlocks) {
            var block = system.addObject();
            block.put("type", "text");
            block.put("text", text);
            block.putObject("cache_control").put("type", "ephemeral");
        }
        return system;
    }

    private static SpawnConfig.ClaudeAccount claudeAccount(SpawnConfig config, Target target) throws IOException {
        var account = config.getClaude().effectiveAccounts().get(target.accountName());
        if (account == null || !account.servesDirectApi()) {
            throw new IOException("Claude account '" + target.accountName() + "' can no longer answer;"
                    + " check it with 'isx init'.");
        }
        return account;
    }

    private static Usage streamAnthropic(String question, List<String> systemBlocks, String model,
                                         SpawnConfig.ClaudeAccount account,
                                         Consumer<String> onChunk) throws IOException {
        var body = JSON.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 4096);
        body.put("stream", true);
        body.set("system", cachedSystem(systemBlocks));
        var messages = body.putArray("messages");
        var msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", question);

        var builder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));

        builder.header("x-api-key", account.getApiKey());

        return processStream(sendStream(builder.build()), onChunk);
    }

    private static Usage streamVertex(String question, List<String> systemBlocks, String model,
                                      SpawnConfig.ClaudeAccount account,
                                      Consumer<String> onChunk) throws IOException {
        var gcpToken = getGcloudAccessToken();

        var body = JSON.createObjectNode();
        body.put("anthropic_version", "vertex-2023-10-16");
        body.put("max_tokens", 4096);
        body.put("stream", true);
        body.set("system", cachedSystem(systemBlocks));
        var messages = body.putArray("messages");
        var msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", question);

        var region = account.getCloudMlRegion();
        var project = account.getVertexProjectId();
        var host = ProxyConfig.vertexHost(region);
        var uri = "https://" + host + "/v1/projects/" + project
                + "/locations/" + region + "/publishers/anthropic/models/"
                + model + ":streamRawPredict";

        var request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + gcpToken)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

        return processStream(sendStream(request), onChunk);
    }

    private static Usage streamOpenAI(String question, String systemPrompt, String model,
                                      SpawnConfig.OpenaiConfig openai,
                                      Consumer<String> onChunk) throws IOException {
        var body = JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        // Streamed responses only report token usage when asked to, in a final chunk.
        body.putObject("stream_options").put("include_usage", true);
        var messages = body.putArray("messages");
        var sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        var userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", question);

        var request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openai.getApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

        return processOpenAiStream(sendStream(request), onChunk);
    }

    static Usage processOpenAiStream(InputStream is, Consumer<String> onChunk) throws IOException {
        var usage = Usage.NONE;
        try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                var data = line.substring(6).strip();
                if ("[DONE]".equals(data)) break;
                var node = JSON.readTree(data);
                var text = node.path("choices").path(0).path("delta").path("content").asText(null);
                if (text != null) onChunk.accept(text);
                var reported = node.path("usage");
                if (reported.isObject()) {
                    long cached = reported.path("prompt_tokens_details").path("cached_tokens").asLong(0);
                    usage = new Usage(reported.path("prompt_tokens").asLong(0) - cached, cached, 0,
                            reported.path("completion_tokens").asLong(0));
                }
            }
        }
        return usage;
    }

    static Usage processStream(InputStream is, Consumer<String> onChunk) throws IOException {
        boolean inTextBlock = false;
        var usage = Usage.NONE;
        try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                var data = line.substring(6).strip();
                if ("[DONE]".equals(data)) break;

                var node = JSON.readTree(data);
                var type = node.path("type").asText("");

                switch (type) {
                    case "message_start":
                        usage = withUsage(usage, node.path("message").path("usage"));
                        break;
                    case "message_delta":
                        usage = withUsage(usage, node.path("usage"));
                        break;
                    case "content_block_start":
                        inTextBlock = "text".equals(
                                node.path("content_block").path("type").asText());
                        break;
                    case "content_block_delta":
                        if (inTextBlock) {
                            var text = node.path("delta").path("text").asText(null);
                            if (text != null) onChunk.accept(text);
                        }
                        break;
                    case "content_block_stop":
                        inTextBlock = false;
                        break;
                    case "error":
                        var errMsg = node.path("error").path("message").asText("Unknown error");
                        throw new IOException("API stream error: " + errMsg);
                }
            }
        }
        return usage;
    }

    /**
     * Folds a Messages API {@code usage} object into {@code usage}. {@code message_start}
     * carries the input counts and {@code message_delta} the final output count (and may
     * repeat the input counts), so each field keeps the latest value it was given.
     */
    static Usage withUsage(Usage usage, com.fasterxml.jackson.databind.JsonNode reported) {
        if (!reported.isObject()) return usage;
        return new Usage(
                reported.path("input_tokens").asLong(usage.inputTokens()),
                reported.path("cache_read_input_tokens").asLong(usage.cacheReadTokens()),
                reported.path("cache_creation_input_tokens").asLong(usage.cacheWriteTokens()),
                reported.path("output_tokens").asLong(usage.outputTokens()));
    }

    private static String getGcloudAccessToken() throws IOException {
        try {
            var pb = new ProcessBuilder("gcloud", "auth", "print-access-token");
            var process = pb.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("gcloud auth print-access-token timed out");
            }
            var stdout = new String(process.getInputStream().readAllBytes()).strip();
            var exitCode = process.exitValue();
            if (exitCode != 0) {
                var stderr = new String(process.getErrorStream().readAllBytes()).strip();
                throw new IOException("gcloud auth print-access-token failed: " + stderr);
            }
            if (stdout.isBlank()) {
                throw new IOException("gcloud returned an empty token. Run 'gcloud auth login'.");
            }
            return stdout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while getting GCP token", e);
        }
    }

    /**
     * Renders an error response as a diagnosable one-liner. Providers sometimes answer with a
     * deliberately unhelpful {@code message} (an OAuth token used outside Claude Code returns a
     * bare "Error"), so the error type and request id carry the signal and must survive.
     */
    private static String describeError(int status, String body) {
        var detail = new StringBuilder("API error (").append(status);
        String message = null;
        try {
            var tree = JSON.readTree(body);
            message = tree.path("error").path("message").asText(null);
            var type = tree.path("error").path("type").asText(null);
            if (type != null) detail.append(", ").append(type);
            var requestId = tree.path("request_id").asText(null);
            if (requestId != null) detail.append(", ").append(requestId);
        } catch (IOException e) {
            // Not JSON -- fall through and report the raw body below.
        }
        detail.append("): ");
        if (message != null && !message.isBlank()) {
            detail.append(message);
        } else if (!body.isBlank()) {
            detail.append(body.length() > 200 ? body.substring(0, 200) + "..." : body);
        } else {
            detail.append("no response body");
        }
        return detail.toString();
    }

    private static InputStream sendStream(HttpRequest request) throws IOException {
        try {
            var response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                try (var errStream = response.body()) {
                    var errBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                    throw new IOException(describeError(response.statusCode(), errBody));
                }
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }
}
