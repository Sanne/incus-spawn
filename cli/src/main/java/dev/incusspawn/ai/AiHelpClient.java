package dev.incusspawn.ai;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.config.SpawnConfig;

import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.util.ByteLines;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.SystemTrust;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class AiHelpClient {

    private static volatile HttpClient httpClient;

    private static HttpClient client() {
        if (httpClient == null) {
            httpClient = SystemTrust.httpClient()
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

    /** An answer, what it cost, and {@link StreamEnd#note() why it ended early}, if it did. */
    public record AiResponse(String content, Usage usage, String note) {
        public AiResponse(String content, Usage usage) {
            this(content, usage, null);
        }
    }

    /**
     * How a streamed answer ended: what it cost, and isx's note when the answer is not whole
     * ({@link #REFUSAL_NOTE}, {@link #TRUNCATED_NOTE}), else null. The note is isx's own
     * word, so it is reported beside the answer and never streamed as part of it.
     */
    public record StreamEnd(Usage usage, String note) {
        StreamEnd(Usage usage) {
            this(usage, null);
        }
    }

    /**
     * Input tokens split by how they were billed, as the provider reported them: the uncached
     * remainder, those read from the prompt cache (0.05x on Sonnet 5.5), and those written to it (~1.25x).
     * OpenAI caches on its own and reports no writes.
     */
    public record Usage(long inputTokens, long cacheReadTokens, long cacheWriteTokens, long outputTokens) {
        public static final Usage NONE = new Usage(0, 0, 0, 0);

        public long totalInputTokens() {
            return inputTokens + cacheReadTokens + cacheWriteTokens;
        }
    }

    public static final String ANTHROPIC_MODEL = "claude-sonnet-5-5";
    public static final String VERTEX_MODEL = "claude-sonnet-5-5";
    public static final String OPENAI_MODEL = "gpt-4o-mini";
    /** How hard Claude thinks before answering, on the Claude API and Vertex alike (see {@link #messagesBody}). */
    static final String EFFORT = "low";
    /** The Messages API version a Vertex request names in its body. */
    static final String VERTEX_VERSION = "vertex-2023-10-16";
    /**
     * Shown with every answer, apart from it: the answer is a model's, and can be wrong or name
     * commands and flags isx does not have (#1675 measured both).
     */
    public static final String DISCLAIMER =
            "AI-generated and may be wrong: check it against https://isx.run/docs";
    /** Why an answer is empty or stops short: the model declined to give it. */
    static final String REFUSAL_NOTE = "The model declined to answer this question.";
    /** Why an answer stops short: {@code max_tokens} or the context window cut it off. */
    static final String TRUNCATED_NOTE = "The answer was cut off at its length limit.";
    /** Where Anthropic explains the API credits Max and Team plans include, which can pay for this (#1674). */
    public static final String API_CREDITS_URL =
            "https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers";

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
     * OpenAI. Empty when nothing can answer -- see {@link #noProvider}.
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

    /** Why nothing can answer, and what to do about it, one line each. */
    public record NoProvider(String cause, List<String> advice) {

        /** The cause and its advice as one message: one line when a single advice follows it. */
        public String message() {
            return cause + (advice.size() > 1 ? "\n" + String.join("\n", advice)
                    : " " + advice.getFirst());
        }
    }

    /**
     * Why {@link #targets} found nothing usable, and what to do about it. A Claude Pro/Max
     * OAuth token counts as configured credentials to the rest of isx, so "no credentials"
     * would be a confusing thing to tell that user -- name the real reason instead.
     */
    public static NoProvider noProvider(SpawnConfig config) {
        var configured = config.getClaude().effectiveAccounts();
        if (configured.isEmpty()) {
            return new NoProvider("No AI credentials configured.",
                    List.of("Run 'isx init' to set up Anthropic, Vertex AI, or OpenAI credentials."));
        }
        // Describe what is actually configured rather than inferring it from the absence of a
        // provider -- a confidently wrong explanation is worse than a vague one. Both branches
        // are reachable: an account can be present but unusable (a flat 'useVertex: true'
        // missing its project is kept so ProxyMain can report it, but is not complete()).
        var allOauth = configured.values().stream()
                .allMatch(a -> a.effectiveType() == SpawnConfig.ClaudeAccountType.OAUTH);
        if (allOauth) {
            // Two sentences, so the first fits on the TUI's status bar (#1765)
            return new NoProvider(configured.size() > 1
                    ? "Your Claude Pro/Max subscriptions cannot answer here."
                            + " Their tokens are only valid for Claude Code itself."
                    : "Your Claude Pro/Max subscription cannot answer here."
                            + " Its token is only valid for Claude Code itself.", List.of(
                    "Run 'isx init' to add an Anthropic API key or Vertex AI account alongside it.",
                    "Max and Team plans include monthly API credits for a key: " + API_CREDITS_URL));
        }
        return new NoProvider("No configured Claude account can answer this.",
                List.of("Run 'isx init' to add an Anthropic API key or Vertex AI account."));
    }

    /**
     * Asks {@code target}, which must be one of {@link #targets}. {@code systemBlocks} are sent
     * as separately cacheable blocks where the provider supports it (see
     * {@link HelpContext#systemBlocks}).
     */
    public static AiResponse ask(String question, List<String> systemBlocks, SpawnConfig config,
                                 Target target) throws IOException {
        var sb = new StringBuilder();
        var end = askStreaming(question, systemBlocks, config, target, sb::append);
        return new AiResponse(sb.toString(), end.usage(), end.note());
    }

    /** Like {@link #ask}, handing the answer to {@code onChunk} as it arrives. */
    public static StreamEnd askStreaming(String question, List<String> systemBlocks, SpawnConfig config,
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
     * the cache at a twentieth of the input price on Sonnet 5.5. The default 5-minute lifetime
     * fits help traffic: a cache read renews it, so follow-ups keep it warm, while the 1-hour
     * lifetime doubles the write cost for questions too sparse to repay it.
     */
    static JqArray cachedSystem(List<String> systemBlocks) {
        var system = JqArray.arrayBuilder();
        for (var text : systemBlocks) {
            system.add(JqObject.builder(3)
                    .put("type", "text")
                    .put("text", text)
                    .put("cache_control", JqObject.builder(1).put("type", "ephemeral").build())
                    .build());
        }
        return system.build();
    }

    /**
     * A Messages API request for one question, on the Claude API ({@code model}) or Vertex
     * ({@code anthropic_version}, the model being in the URL), at {@link #EFFORT}: at its default
     * Sonnet 5.5 thinks before every answer (design/accounts.md "AI help credentials" has the measurement).
     */
    static JqObject messagesBody(String versionKey, String version, String question, List<String> systemBlocks) {
        return JqObject.builder(6)
                .put(versionKey, version)
                .put("max_tokens", 4096L)
                .put("stream", true)
                .put("output_config", JqObject.builder(1).put("effort", EFFORT).build())
                .put("system", cachedSystem(systemBlocks))
                .put("messages", JqArray.arrayBuilder()
                        .add(JqObject.builder(2).put("role", "user").put("content", question).build())
                        .build())
                .build();
    }

    private static SpawnConfig.ClaudeAccount claudeAccount(SpawnConfig config, Target target) throws IOException {
        var account = config.getClaude().effectiveAccounts().get(target.accountName());
        if (account == null || !account.servesDirectApi()) {
            throw new IOException("Claude account '" + target.accountName() + "' can no longer answer;"
                    + " check it with 'isx init'.");
        }
        return account;
    }

    private static StreamEnd streamAnthropic(String question, List<String> systemBlocks, String model,
                                         SpawnConfig.ClaudeAccount account,
                                         Consumer<String> onChunk) throws IOException {
        var body = messagesBody("model", model, question, systemBlocks);

        var builder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body.toJsonString()));

        builder.header("x-api-key", account.getApiKey());

        return processStream(sendStream(builder.build()), onChunk);
    }

    private static StreamEnd streamVertex(String question, List<String> systemBlocks, String model,
                                      SpawnConfig.ClaudeAccount account,
                                      Consumer<String> onChunk) throws IOException {
        var gcpToken = getGcloudAccessToken();

        var body = messagesBody("anthropic_version", VERTEX_VERSION, question, systemBlocks);

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
                .POST(HttpRequest.BodyPublishers.ofString(body.toJsonString()))
                .build();

        return processStream(sendStream(request), onChunk);
    }

    private static StreamEnd streamOpenAI(String question, String systemPrompt, String model,
                                      SpawnConfig.OpenaiConfig openai,
                                      Consumer<String> onChunk) throws IOException {
        var body = JqObject.builder(4)
                .put("model", model)
                .put("stream", true)
                // Streamed responses only report token usage when asked to, in a final chunk.
                .put("stream_options", JqObject.builder(1).put("include_usage", true).build())
                .put("messages", JqArray.arrayBuilder()
                        .add(JqObject.builder(2).put("role", "system").put("content", systemPrompt).build())
                        .add(JqObject.builder(2).put("role", "user").put("content", question).build())
                        .build())
                .build();

        var request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openai.effectiveApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body.toJsonString()))
                .build();

        return processOpenAiStream(sendStream(request), onChunk);
    }

    private static final byte[] SSE_DATA = "data: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SSE_DONE = "[DONE]".getBytes(StandardCharsets.US_ASCII);

    /**
     * The payload of an SSE {@code data:} line, trimmed, as the bytes it came in, so
     * {@link Jjq#parseForeign(byte[], String)} refuses invalid UTF-8; null for any other line.
     */
    private static byte[] sseData(byte[] line) {
        if (!Arrays.equals(line, 0, Math.min(line.length, SSE_DATA.length), SSE_DATA, 0, SSE_DATA.length)) {
            return null;
        }
        int from = SSE_DATA.length;
        int to = line.length;
        // Bytes are signed: a UTF-8 byte (0x80 and up) is negative, and is not whitespace.
        while (from < to && line[from] >= 0 && line[from] <= ' ') from++;
        while (to > from && line[to - 1] >= 0 && line[to - 1] <= ' ') to--;
        return Arrays.copyOfRange(line, from, to);
    }

    static StreamEnd processOpenAiStream(InputStream is, Consumer<String> onChunk) throws IOException {
        var usage = Usage.NONE;
        try (var lines = new ByteLines(is)) {
            byte[] line;
            while ((line = lines.next()) != null) {
                var data = sseData(line);
                if (data == null) continue;
                if (Arrays.equals(data, SSE_DONE)) break;
                var node = Jjq.parseForeign(data, "the AI provider's stream event");
                var text = node.getField("choices").getElement(0).getField("delta").pathText("content", null);
                if (text != null) onChunk.accept(text);
                var reported = node.getField("usage");
                if (reported.isObject()) {
                    long cached = Jjq.longOr(reported.getField("prompt_tokens_details").getField("cached_tokens"), 0);
                    usage = new Usage(Jjq.longOr(reported.getField("prompt_tokens"), 0) - cached, cached, 0,
                            Jjq.longOr(reported.getField("completion_tokens"), 0));
                }
            }
        }
        return new StreamEnd(usage);
    }

    static StreamEnd processStream(InputStream is, Consumer<String> onChunk) throws IOException {
        boolean inTextBlock = false;
        String stopReason = "";
        var usage = Usage.NONE;
        try (var lines = new ByteLines(is)) {
            byte[] line;
            while ((line = lines.next()) != null) {
                var data = sseData(line);
                if (data == null) continue;
                if (Arrays.equals(data, SSE_DONE)) break;

                var node = Jjq.parseForeign(data, "the AI provider's stream event");
                var type = node.pathText("type", "");

                switch (type) {
                    case "message_start":
                        usage = withUsage(usage, node.getField("message").getField("usage"));
                        break;
                    case "message_delta":
                        usage = withUsage(usage, node.getField("usage"));
                        stopReason = node.getField("delta").pathText("stop_reason", stopReason);
                        break;
                    case "content_block_start":
                        inTextBlock = "text".equals(
                                node.getField("content_block").pathText("type", ""));
                        break;
                    case "content_block_delta":
                        if (inTextBlock) {
                            var text = node.getField("delta").pathText("text", null);
                            if (text != null) onChunk.accept(text);
                        }
                        break;
                    case "content_block_stop":
                        inTextBlock = false;
                        break;
                    case "error":
                        var errMsg = node.getField("error").pathText("message", "Unknown error");
                        throw new IOException("API stream error: " + OutputFormat.oneLine(errMsg));
                }
            }
        }
        return new StreamEnd(usage, switch (stopReason) {
            case "refusal" -> REFUSAL_NOTE;
            case "max_tokens", "model_context_window_exceeded" -> TRUNCATED_NOTE;
            default -> null;
        });
    }

    /**
     * Folds a Messages API {@code usage} object into {@code usage}. {@code message_start}
     * carries the input counts and {@code message_delta} the final output count (and may
     * repeat the input counts), so each field keeps the latest value it was given.
     */
    static Usage withUsage(Usage usage, JqValue reported) {
        if (!reported.isObject()) return usage;
        return new Usage(
                Jjq.longOr(reported.getField("input_tokens"), usage.inputTokens()),
                Jjq.longOr(reported.getField("cache_read_input_tokens"), usage.cacheReadTokens()),
                Jjq.longOr(reported.getField("cache_creation_input_tokens"), usage.cacheWriteTokens()),
                Jjq.longOr(reported.getField("output_tokens"), usage.outputTokens()));
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
     * bare "Error"), so the error type and request id carry the signal and must survive. The
     * provider wrote all of it, so it goes through {@link OutputFormat#oneLine}: it cannot drive
     * the terminal it is shown on.
     */
    static String describeError(int status, byte[] bytes) {
        var detail = new StringBuilder("API error (").append(status);
        String message = null;
        try {
            var tree = Jjq.parseForeign(bytes, "the AI provider's error");
            message = tree.getField("error").pathText("message", null);
            var type = tree.getField("error").pathText("type", null);
            if (type != null) detail.append(", ").append(type);
            var requestId = tree.pathText("request_id", null);
            if (requestId != null) detail.append(", ").append(requestId);
        } catch (RuntimeException e) {
            // Not JSON -- fall through and report the raw body below.
        }
        detail.append("): ");
        var body = new String(bytes, StandardCharsets.UTF_8);
        if (message != null && !message.isBlank()) {
            detail.append(message);
        } else if (!body.isBlank()) {
            detail.append(body.codePointCount(0, body.length()) > 200
                    ? body.substring(0, body.offsetByCodePoints(0, 200)) + "..." : body);
        } else {
            detail.append("no response body");
        }
        return OutputFormat.oneLine(detail.toString());
    }

    private static InputStream sendStream(HttpRequest request) throws IOException {
        try {
            var response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                try (var errStream = response.body()) {
                    throw new IOException(describeError(response.statusCode(), errStream.readAllBytes()));
                }
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }
}
