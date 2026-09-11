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

    public enum Provider { ANTHROPIC, OPENAI, VERTEX }

    public record AiResponse(String content, Provider provider) {}

    public static final String ANTHROPIC_MODEL = "claude-sonnet-5";
    public static final String VERTEX_MODEL = "claude-sonnet-5";
    public static final String OPENAI_MODEL = "gpt-4o-mini";

    /**
     * Picks the provider to answer with, or null when nothing usable is configured.
     *
     * <p>A Claude Pro/Max OAuth token is deliberately not a candidate. Such a token is only
     * valid for Claude Code itself -- the Messages API answers anything else with an opaque
     * HTTP 429 {@code rate_limit_error} -- so isx only ever forwards it into instances, where
     * real Claude Code uses it. It is not a credential this command can spend.
     */
    public static Provider detectProvider(SpawnConfig config) {
        var claude = config.getClaude();
        if (!claude.getApiKey().isBlank()) return Provider.ANTHROPIC;
        if (claude.isUseVertex()) return Provider.VERTEX;
        if (config.getOpenai().hasAuth()) return Provider.OPENAI;
        return null;
    }

    /**
     * Explains why {@link #detectProvider} found nothing usable. A Claude Pro/Max OAuth token
     * counts as configured credentials to the rest of isx, so "no credentials" would be a
     * confusing thing to tell that user -- name the real reason instead.
     */
    public static String noProviderMessage(SpawnConfig config) {
        if (config.getClaude().isOauthMode()) {
            return "A Claude Pro/Max OAuth token is only valid for Claude Code itself, not for AI help. "
                    + "Run 'isx init' to set an Anthropic or OpenAI API key.";
        }
        return "No AI credentials configured. "
                + "Run 'isx init' to set up Anthropic, Vertex AI, or OpenAI credentials.";
    }

    public static AiResponse ask(String question, String systemPrompt, SpawnConfig config) throws IOException {
        var sb = new StringBuilder();
        var provider = requireProvider(config);
        dispatch(question, systemPrompt, config, provider, sb::append);
        return new AiResponse(sb.toString(), provider);
    }

    public static void askStreaming(String question, String systemPrompt,
                                    SpawnConfig config, Consumer<String> onChunk) throws IOException {
        dispatch(question, systemPrompt, config, requireProvider(config), onChunk);
    }

    private static void dispatch(String question, String systemPrompt,
                                  SpawnConfig config, Provider provider,
                                  Consumer<String> onChunk) throws IOException {
        switch (provider) {
            case ANTHROPIC -> streamAnthropic(question, systemPrompt, config.getClaude(), onChunk);
            case VERTEX -> streamVertex(question, systemPrompt, config.getClaude(), onChunk);
            case OPENAI -> streamOpenAI(question, systemPrompt, config.getOpenai(), onChunk);
        }
    }

    private static Provider requireProvider(SpawnConfig config) throws IOException {
        var provider = detectProvider(config);
        if (provider == null) {
            throw new IOException(noProviderMessage(config));
        }
        return provider;
    }

    private static void streamAnthropic(String question, String systemPrompt,
                                         SpawnConfig.ClaudeConfig claude,
                                         Consumer<String> onChunk) throws IOException {
        var body = JSON.createObjectNode();
        body.put("model", ANTHROPIC_MODEL);
        body.put("max_tokens", 4096);
        body.put("stream", true);
        body.put("system", systemPrompt);
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

        builder.header("x-api-key", claude.getApiKey());

        processStream(sendStream(builder.build()), onChunk);
    }

    private static void streamVertex(String question, String systemPrompt,
                                      SpawnConfig.ClaudeConfig claude,
                                      Consumer<String> onChunk) throws IOException {
        var gcpToken = getGcloudAccessToken();

        var body = JSON.createObjectNode();
        body.put("anthropic_version", "vertex-2023-10-16");
        body.put("max_tokens", 4096);
        body.put("stream", true);
        body.put("system", systemPrompt);
        var messages = body.putArray("messages");
        var msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", question);

        var region = claude.getCloudMlRegion();
        var project = claude.getVertexProjectId();
        var host = ProxyConfig.vertexHost(region);
        var uri = "https://" + host + "/v1/projects/" + project
                + "/locations/" + region + "/publishers/anthropic/models/"
                + VERTEX_MODEL + ":streamRawPredict";

        var request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + gcpToken)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

        processStream(sendStream(request), onChunk);
    }

    private static void streamOpenAI(String question, String systemPrompt,
                                      SpawnConfig.OpenaiConfig openai,
                                      Consumer<String> onChunk) throws IOException {
        var body = JSON.createObjectNode();
        body.put("model", OPENAI_MODEL);
        body.put("stream", true);
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

        var is = sendStream(request);
        try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                var data = line.substring(6).strip();
                if ("[DONE]".equals(data)) break;
                var node = JSON.readTree(data);
                var text = node.path("choices").path(0).path("delta").path("content").asText(null);
                if (text != null) onChunk.accept(text);
            }
        }
    }

    private static void processStream(InputStream is, Consumer<String> onChunk) throws IOException {
        boolean inTextBlock = false;
        try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                var data = line.substring(6).strip();
                if ("[DONE]".equals(data)) break;

                var node = JSON.readTree(data);
                var type = node.path("type").asText("");

                switch (type) {
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
