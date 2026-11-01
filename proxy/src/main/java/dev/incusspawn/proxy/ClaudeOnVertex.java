package dev.incusspawn.proxy;

import io.vertx.core.http.HttpMethod;

import java.util.regex.Pattern;

/**
 * What a proven call already in Vertex's format buys at api.anthropic.com (#1921): a Claude model call,
 * as Claude Code sends it in Vertex mode, in the Vertex account's own project and region. The proxy
 * signs it with the host's gcloud token, whose reach is every project that user can touch, so it names
 * the project and region itself, as {@link GeminiOnVertex} does, and serves no other publisher, method
 * or API. The guest's own project and region were fixed when it was built, and the account it is served
 * may since have changed, so a call naming others is moved, never sent where the guest named.
 */
final class ClaudeOnVertex {

    /**
     * The Vertex form of a Claude call: a project, a region, the model and the method. Token counting
     * is the model {@code count-tokens}; a model's {@code @date} suffix is the proxy's to strip.
     */
    private static final Pattern MODEL_CALL = Pattern.compile(
            "/v1/projects/[^/]+/locations/[^/]+/publishers/anthropic/models/([A-Za-z0-9][A-Za-z0-9._@-]*)"
                    + ":(rawPredict|streamRawPredict)");

    private ClaudeOnVertex() {}

    /**
     * Where the call {@code method} {@code path} goes in {@code project} and {@code region}, or null
     * when it is no Claude model call and must be refused.
     */
    static String projectPath(HttpMethod method, String path, String project, String region) {
        if (!HttpMethod.POST.equals(method) || path == null) return null;
        var matcher = MODEL_CALL.matcher(path);
        if (!matcher.matches()) return null;
        return "/v1/projects/" + project + "/locations/" + region + "/publishers/anthropic/models/"
                + matcher.group(1) + ":" + matcher.group(2);
    }
}
