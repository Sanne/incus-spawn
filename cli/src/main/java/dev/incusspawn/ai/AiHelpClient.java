package dev.incusspawn.ai;

import dev.incusspawn.Platform;
import dev.incusspawn.config.ModelIds;
import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.proxy.GoogleAccounts;

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
        VERTEX("Vertex AI"),
        GEMINI("Gemini API key");

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

        /**
         * What the answer cost in tokens, and whether the prompt cache was used -- the only visible
         * sign that caching works, since a broken cache fails silently as a higher bill. Null when
         * the provider reported nothing.
         */
        public String summary() {
            if (totalInputTokens() == 0) return null;
            var cache = new ArrayList<String>();
            if (cacheReadTokens > 0) cache.add(tokens(cacheReadTokens) + " from cache");
            if (cacheWriteTokens > 0) cache.add(tokens(cacheWriteTokens) + " cached for follow-ups within 5 min");
            return tokens(totalInputTokens()) + " tokens in"
                    + (cache.isEmpty() ? "" : " (" + String.join(", ", cache) + ")")
                    + " · " + tokens(outputTokens) + " out";
        }

        private static String tokens(long count) {
            return count < 1000 ? Long.toString(count)
                    : String.format(java.util.Locale.ROOT, "%.1fk", count / 1000.0);
        }
    }

    public static final String ANTHROPIC_MODEL = ModelIds.get("ai-help-anthropic");
    public static final String VERTEX_MODEL = ModelIds.get("ai-help-vertex");
    public static final String OPENAI_MODEL = ModelIds.get("ai-help-openai");
    public static final String GEMINI_MODEL = ModelIds.get("ai-help-gemini");
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
    /** The Claude Console's page for API keys, Settings > API keys (platform.claude.com/docs/en/get-api-key). */
    public static final String API_KEYS_URL = "https://platform.claude.com/settings/keys";

    /**
     * A configured account a question can be sent to. {@code accountName} is the account's name
     * in {@code config.yaml}, under the provider's namespace ({@link #namespace}).
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

        /** How a person names this account to {@code --account}: {@code <namespace>=<account>}, as {@code isx branch} takes it. */
        public String selection() {
            return namespace(provider) + "=" + accountName;
        }
    }

    /** The credential namespace whose accounts each provider's targets come from. */
    public static String namespace(Provider provider) {
        return switch (provider) {
            case ANTHROPIC, VERTEX -> SpawnConfig.ClaudeConfig.NAMESPACE;
            case OPENAI -> OPENAI_NAMESPACE;
            case GEMINI -> GoogleAccounts.NAMESPACE;
        };
    }

    static final String OPENAI_NAMESPACE = "openai";
    static final String API_KEY = "apiKey";

    /**
     * Every configured account that can answer, the one to use by default first: Claude
     * accounts that serve the API directly (in file order, the default account leading), then
     * OpenAI's and Gemini's API key accounts, in the same order. Empty when nothing can answer:
     * see {@link #noProvider}.
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
        for (var name : apiKeyAccounts(config, OPENAI_NAMESPACE)) result.add(new Target(Provider.OPENAI, name, OPENAI_MODEL));
        for (var name : apiKeyAccounts(config, GoogleAccounts.NAMESPACE)) result.add(new Target(Provider.GEMINI, name, GEMINI_MODEL));
        return result;
    }

    /**
     * The accounts of {@code namespace} that hold an API key, its default first, then file order
     * (#2541). Every one counts, not only the default: an {@code openai} default of
     * {@code type: chatgpt} or a {@code gemini} default of {@code type: google} is a sign-in the
     * proxy serves only for its agent's own operations, so it cannot answer, while a key beside it can.
     */
    static List<String> apiKeyAccounts(SpawnConfig config, String namespace) {
        var tree = config.tree();
        var names = new ArrayList<>(AccountResolver.accountNames(config, namespace));
        var preferred = AccountResolver.defaultName(tree, namespace);
        if (names.remove(preferred)) names.addFirst(preferred);
        var result = new ArrayList<String>();
        for (var name : names) {
            if (AccountResolver.INHERIT.equals(name)) continue;
            var type = AccountResolver.value(tree, namespace, name, "type");
            if (!type.isBlank() && !type.equals("api-key")) continue;
            if (!apiKey(config, namespace, name).isBlank()) result.add(name);
        }
        return result;
    }

    private static String apiKey(SpawnConfig config, String namespace, String account) {
        return AccountResolver.value(config.tree(), namespace, account, API_KEY).strip();
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

    /** The kinds of account that can answer, as the person reads them. */
    public static final String KINDS =
            "An Anthropic API key, Claude on Vertex AI, an OpenAI API key or a Gemini API key can answer.";
    /** The note on API credits for a subscription's user, before the link (#1674). */
    public static final String API_CREDITS_NOTE = "Max and Team plans include monthly API credits for an Anthropic API key:";
    /** The commands that add each kind, never {@code isx init}, which asks everything else too (#2541). */
    public static final String ADD_ONE = "Add one with 'isx account add claude', 'isx account add openai'"
            + " or 'isx account add gemini'.";

    /**
     * Why {@link #targets} found nothing usable, and what to do about it. A Claude subscription
     * counts as configured credentials to the rest of isx, so "no account" would be a confusing
     * thing to tell that user: name the real reason instead. The cause's first sentence is short,
     * so it fits on the TUI's status bar (#1765).
     */
    public static NoProvider noProvider(SpawnConfig config) {
        var subscriptions = subscriptionAccounts(config);
        var claude = config.getClaude().effectiveAccounts();
        if (!claude.isEmpty() && subscriptions.size() == claude.size()) {
            return new NoProvider(subscriptions.size() > 1
                    ? "Your Claude subscriptions cannot answer this. Their tokens work only in Claude Code."
                    : "Your Claude subscription cannot answer this. Its token works only in Claude Code.",
                    List.of(KINDS, API_CREDITS_NOTE + " " + API_CREDITS_URL, ADD_ONE));
        }
        // Describe what is configured rather than inferring it: an account can be present but
        // unusable (a flat 'useVertex: true' missing its project is kept so ProxyMain can report
        // it), and a ChatGPT or Google sign-in answers only its own agent
        var anyAccount = !claude.isEmpty() || !config.getClaude().allAccounts().isEmpty()
                || !AccountResolver.accountNames(config, OPENAI_NAMESPACE).isEmpty()
                || !AccountResolver.accountNames(config, GoogleAccounts.NAMESPACE).isEmpty();
        return new NoProvider(anyAccount
                ? "The accounts on " + Platform.thisComputer() + " cannot answer this."
                : "isx does not have an account that can answer this.", List.of(KINDS, ADD_ONE));
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
        try {
            return send(question, systemBlocks, config, target, onChunk);
        } catch (ApiError e) {
            throw e.withAdvice(rejectedKeyAdvice(target, e.status(), e.keyInvalid));
        }
    }

    private static StreamEnd send(String question, List<String> systemBlocks, SpawnConfig config,
                                  Target target, Consumer<String> onChunk) throws IOException {
        return switch (target.provider()) {
            case ANTHROPIC -> streamAnthropic(question, systemBlocks, target.model(),
                    claudeAccount(config, target), onChunk);
            case VERTEX -> streamVertex(question, systemBlocks, target.model(),
                    claudeAccount(config, target), onChunk);
            case OPENAI -> streamOpenAI(question, String.join("", systemBlocks), target.model(),
                    targetKey(config, target), HELP, onChunk);
            case GEMINI -> streamGemini(question, systemBlocks, target.model(), targetKey(config, target), HELP, onChunk);
        };
    }

    /**
     * How long a request may wait for its answer to start, and how many tokens it may answer
     * with: null leaves the provider's own default for OpenAI, and {@link #GEMINI_MAX_OUTPUT} for Gemini.
     */
    private record Limits(Duration timeout, Long maxOutputTokens) {}

    private static final Limits HELP = new Limits(Duration.ofSeconds(120), null);
    /** A one-word answer: no two-minute wait and no thousands of tokens for it (#2606). */
    private static final Limits KEY_CHECK = new Limits(Duration.ofSeconds(15), 256L);
    private static final long GEMINI_MAX_OUTPUT = 8192L;

    /**
     * Asks {@code target}, an OpenAI or Gemini key account of {@link #targets}, for one word, to
     * check its key before the AI Help offer saves it (#2606). A question, not a list of models,
     * since only a question finds a key with no quota left. Throws {@link ApiError} when the
     * provider answered with an error, and another {@link IOException} when it was not reached.
     */
    public static void checkKey(SpawnConfig config, Target target) throws IOException {
        var question = "Reply with the single word OK.";
        var system = "You check that an API key works.";
        Consumer<String> ignored = chunk -> { };
        switch (target.provider()) {
            case OPENAI -> streamOpenAI(question, system, target.model(), targetKey(config, target), KEY_CHECK, ignored);
            case GEMINI -> streamGemini(question, List.of(system), target.model(), targetKey(config, target), KEY_CHECK, ignored);
            case ANTHROPIC, VERTEX -> throw new IllegalArgumentException("only an OpenAI or Gemini key is checked this way");
        }
    }

    /** A provider's error answer, with its HTTP status and, when the key is the likely cause, what replaces it. */
    public static final class ApiError extends IOException {
        private final int status;
        /** The body names the key as the cause: Gemini's {@code API_KEY_INVALID} reason. */
        private final boolean keyInvalid;
        private final String advice;

        ApiError(int status, String message, boolean keyInvalid) {
            this(status, message, keyInvalid, null);
        }

        private ApiError(int status, String message, boolean keyInvalid, String advice) {
            super(message);
            this.status = status;
            this.keyInvalid = keyInvalid;
            this.advice = advice;
        }

        public int status() { return status; }

        /**
         * The answer refuses the key {@link #checkKey} sent (#2606): a 401, a 429 (no quota left),
         * or Gemini's 400 naming {@code API_KEY_INVALID}. A 403, a retired model's 404, a 400 from
         * a region Gemini does not serve or a 5xx says nothing of the key.
         */
        public boolean refusesKey() {
            return status == 401 || status == 429 || (status == 400 && keyInvalid);
        }

        ApiError withAdvice(String advice) {
            if (advice == null) return this;
            var withIt = new ApiError(status, getMessage(), keyInvalid, advice);
            withIt.setStackTrace(getStackTrace());
            return withIt;
        }
    }

    /**
     * What replaces the key behind {@code e}, one line, when it is an {@link ApiError} that a wrong
     * or revoked key gives; else none. Once a key is saved nothing asks for one again (#2541), so
     * the error is where the person learns how to replace it.
     */
    public static String[] advice(Throwable e) {
        return e instanceof ApiError api && api.advice != null ? new String[] {api.advice} : new String[0];
    }

    /**
     * Each provider's answer to a key it does not take: 401 from Anthropic and OpenAI,
     * 400 {@code API_KEY_INVALID} (or 401) from Gemini. Vertex signs with gcloud, not a key.
     * Gemini's other 400s, such as a request too large, say nothing of the key.
     */
    static String rejectedKeyAdvice(Target target, int status, boolean keyInvalid) {
        var rejected = switch (target.provider()) {
            // A 403 is left out: it is a permission, a model or a disabled API more often than the key
            case ANTHROPIC, OPENAI -> status == 401;
            case GEMINI -> (status == 400 && keyInvalid) || status == 401;
            case VERTEX -> false;
        };
        var namespace = namespace(target.provider());
        // Named by its account, which the person chooses to replace in the command's menu
        return rejected ? "If the key of the " + namespace + " account '" + target.accountName()
                + "' is wrong, replace that account with 'isx account add " + namespace + "'." : null;
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
            throw new IOException("Claude account '" + target.accountName() + "' can no longer answer."
                    + " Check it with 'isx account add claude'.");
        }
        return account;
    }

    /** The API key of an OpenAI or Gemini target, read again: it may have changed since {@link #targets}. */
    private static String targetKey(SpawnConfig config, Target target) throws IOException {
        var namespace = namespace(target.provider());
        var key = apiKey(config, namespace, target.accountName());
        if (key.isEmpty()) {
            throw new IOException("The " + namespace + " account '" + target.accountName() + "' no longer has an API key."
                    + " Add one with 'isx account add " + namespace + "'.");
        }
        return key;
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
                                      String apiKey, Limits limits,
                                      Consumer<String> onChunk) throws IOException {
        var builder = JqObject.builder(5)
                .put("model", model)
                .put("stream", true)
                // Streamed responses only report token usage when asked to, in a final chunk.
                .put("stream_options", JqObject.builder(1).put("include_usage", true).build())
                .put("messages", JqArray.arrayBuilder()
                        .add(JqObject.builder(2).put("role", "system").put("content", systemPrompt).build())
                        .add(JqObject.builder(2).put("role", "user").put("content", question).build())
                        .build());
        if (limits.maxOutputTokens() != null) builder.put("max_completion_tokens", limits.maxOutputTokens());
        var body = builder.build();

        var request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(limits.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body.toJsonString()))
                .build();

        return processOpenAiStream(sendStream(request), onChunk);
    }

    /**
     * Gemini's {@code streamGenerateContent} as server-sent events, the documentation as the
     * system instruction. Gemini caches a repeated prefix on its own (implicit caching) and
     * reports what it read as {@code cachedContentTokenCount}; it reports no writes. Its thinking
     * is held at {@code LOW}, as Claude's effort is, and its thoughts are not shown.
     */
    private static StreamEnd streamGemini(String question, List<String> systemBlocks, String model,
                                          String apiKey, Limits limits, Consumer<String> onChunk) throws IOException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model
                        + ":streamGenerateContent?alt=sse"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(limits.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(geminiBody(question, systemBlocks,
                        limits.maxOutputTokens() != null ? limits.maxOutputTokens() : GEMINI_MAX_OUTPUT).toJsonString()))
                .build();
        return processGeminiStream(sendStream(request), onChunk);
    }

    /** A {@code generateContent} request for one question, {@code systemBlocks} as the system instruction's parts. */
    static JqObject geminiBody(String question, List<String> systemBlocks) {
        return geminiBody(question, systemBlocks, GEMINI_MAX_OUTPUT);
    }

    private static JqObject geminiBody(String question, List<String> systemBlocks, long maxOutputTokens) {
        var system = JqArray.arrayBuilder();
        for (var text : systemBlocks) system.add(JqObject.builder(1).put("text", text).build());
        return JqObject.builder(3)
                .put("systemInstruction", JqObject.builder(1).put("parts", system.build()).build())
                .put("contents", JqArray.arrayBuilder()
                        .add(JqObject.builder(2)
                                .put("role", "user")
                                .put("parts", JqArray.arrayBuilder()
                                        .add(JqObject.builder(1).put("text", question).build()).build())
                                .build())
                        .build())
                .put("generationConfig", JqObject.builder(2)
                        // Gemini counts its thinking against this, so it leaves room beyond Claude's 4096
                        .put("maxOutputTokens", maxOutputTokens)
                        .put("thinkingConfig", JqObject.builder(1).put("thinkingLevel", "LOW").build())
                        .build())
                .build();
    }

    static StreamEnd processGeminiStream(InputStream is, Consumer<String> onChunk) throws IOException {
        var usage = Usage.NONE;
        String finish = "";
        boolean blocked = false;
        try (var lines = new ByteLines(is)) {
            byte[] line;
            while ((line = lines.next()) != null) {
                var data = sseData(line);
                if (data == null || data.length == 0) continue;
                // Gemini ends a stream by closing it; a [DONE] is ended on as everywhere else
                if (Arrays.equals(data, SSE_DONE)) break;
                var node = Jjq.parseForeign(data, "the AI provider's stream event");
                if (node.getField("error").isObject()) {
                    throw new IOException("API stream error: "
                            + OutputFormat.oneLine(node.getField("error").pathText("message", "Unknown error")));
                }
                blocked |= !node.getField("promptFeedback").pathText("blockReason", "").isEmpty();
                var candidate = node.getField("candidates").getElement(0);
                if (candidate.getField("content").getField("parts") instanceof JqArray parts) {
                    for (var part : parts) {
                        // A thought is the model's reasoning, not its answer
                        if (part.pathBoolean("thought", false)) continue;
                        var text = part.pathText("text", null);
                        if (text != null) onChunk.accept(text);
                    }
                }
                finish = candidate.pathText("finishReason", finish);
                var reported = node.getField("usageMetadata");
                if (reported.isObject()) {
                    long cached = Jjq.longOr(reported.getField("cachedContentTokenCount"), 0);
                    usage = new Usage(Jjq.longOr(reported.getField("promptTokenCount"), 0) - cached, cached, 0,
                            Jjq.longOr(reported.getField("candidatesTokenCount"), 0)
                                    + Jjq.longOr(reported.getField("thoughtsTokenCount"), 0));
                }
            }
        }
        if (blocked) return new StreamEnd(usage, REFUSAL_NOTE);
        return new StreamEnd(usage, switch (finish) {
            case "", "STOP", "FINISH_REASON_UNSPECIFIED" -> null;
            case "MAX_TOKENS" -> TRUNCATED_NOTE;
            // What Gemini's safety and policy filters give: the answer was withheld
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> REFUSAL_NOTE;
            // Any other reason, such as OTHER, is no refusal: it is named as Gemini gave it
            default -> "The answer ended early, for the reason Gemini gives as " + OutputFormat.oneLine(finish) + ".";
        });
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
                    var bytes = errStream.readAllBytes();
                    throw new ApiError(response.statusCode(), describeError(response.statusCode(), bytes),
                            new String(bytes, StandardCharsets.UTF_8).contains("API_KEY_INVALID"));
                }
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }
}
