package dev.incusspawn.proxy;

import io.vertx.core.http.HttpMethod;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a claude proof buys at {@link ProxyConfig#GOOGLE_VERTEX_DOMAINS} (#1114): a Gemini model call
 * from Pi's google-vertex provider, which sends it in Vertex's express form, with no project, and the
 * instance's proof as its API key. The proxy signs it with the host's gcloud token, whose reach is
 * every project that user can touch, so it names the Vertex account's project and region itself and
 * serves nothing else. Of the client's query it keeps only {@code alt}, the response format: a
 * {@code userProject} would charge another project, and the client names nothing else this route needs.
 */
final class GeminiOnVertex {

    /** The express form of a Gemini call: the API version, the model and the method. */
    private static final Pattern MODEL_CALL = Pattern.compile(
            "/(v1|v1beta1)/publishers/google/models/([A-Za-z0-9][A-Za-z0-9._@-]*)"
                    + ":(generateContent|streamGenerateContent|countTokens)");

    private GeminiOnVertex() {}

    /**
     * The client's request headers that name a project or a method of their own, which the route
     * must decide alone: {@code x-goog-user-project} charges the call to another quota project.
     */
    static final List<String> CLIENT_OVERRIDES = List.of("x-goog-user-project", "X-HTTP-Method-Override");

    /**
     * Where the call {@code method} {@code uri} goes in {@code project} and {@code region}, with only
     * the {@code alt} parameters of its query, or null when it is no Gemini model call and must be refused.
     */
    static String projectUri(HttpMethod method, String uri, String project, String region) {
        if (!HttpMethod.POST.equals(method) || uri == null) return null;
        var query = uri.indexOf('?');
        var matcher = MODEL_CALL.matcher(query < 0 ? uri : uri.substring(0, query));
        if (!matcher.matches()) return null;
        return "/" + matcher.group(1) + "/projects/" + project + "/locations/" + region
                + "/publishers/google/models/" + matcher.group(2) + ":" + matcher.group(3)
                + (query < 0 ? "" : responseFormat(uri.substring(query + 1)));
    }

    /** A plain {@code alt} value: no separator or escape a server might read as another parameter. */
    private static final Pattern RESPONSE_FORMAT = Pattern.compile("alt=[A-Za-z]+");

    /**
     * The {@code alt} parameters of {@code query}, as the client wrote them, behind a {@code ?}; else
     * nothing. A signed Claude call in Vertex's format keeps the same (#1921).
     */
    static String responseFormat(String query) {
        var kept = Arrays.stream(query.split("&")).filter(p -> RESPONSE_FORMAT.matcher(p).matches()).toList();
        return kept.isEmpty() ? "" : "?" + String.join("&", kept);
    }
}
