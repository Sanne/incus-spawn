package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The tree translation {@link VertexBodyRewriter} replaced (#1315), kept as the oracle its tests
 * compare against: copied from {@code MitmProxy.translateToVertex} as it was, with the field
 * logging left out, and with the one rule added since: a tool definition's {@code strict} is
 * dropped (#1556), also from a body already in Vertex's format (#1568, {@link #vertexFormat}).
 */
final class LegacyVertexTranslation {

    record Translation(String path, byte[] body) {}

    private static final Set<String> VERTEX_ALLOWED_FIELDS = Set.of(
            "anthropic_version", "messages", "system", "max_tokens",
            "temperature", "top_p", "top_k", "stop_sequences", "stream",
            "metadata", "tools", "tool_choice", "thinking", "output_config"
    );

    private LegacyVertexTranslation() {}

    static Translation translate(String originalPath, byte[] bodyBytes, String projectId, String region) {
        var tree = bodyBytes.length > 0 ? JqValues.parseStrict(bodyBytes) : null;
        if (!(tree instanceof JqObject root)) {
            return new Translation(originalPath, bodyBytes);
        }
        var model = root.pathText("model", dev.incusspawn.config.ModelIds.get("vertex-rewriter-default"));
        model = model.replaceFirst("-\\d{8}$", "");
        var streaming = root.keys().contains("stream") && root.getField("stream").asBoolean(false);
        var rebuilt = JqObject.builder(root.size() + 1);
        root.forEach((field, value) -> {
            if (field.equals("model")) return;
            if (!VERTEX_ALLOWED_FIELDS.contains(field)) return;
            var stripped = stripCacheControlScope(value);
            rebuilt.put(field, field.equals("tools") ? stripToolStrict(stripped) : stripped);
        });
        rebuilt.put("anthropic_version", "vertex-2023-10-16");
        var rewrittenBytes = rebuilt.build().toJsonString().getBytes(StandardCharsets.UTF_8);
        var endpoint = streaming ? ":streamRawPredict" : ":rawPredict";
        var vertexPath = "/v1/projects/" + projectId + "/locations/" + region
                + "/publishers/anthropic/models/" + model + endpoint;
        return new Translation(vertexPath, rewrittenBytes);
    }

    /** A body already in Vertex's format, as it is forwarded: only a tool definition's {@code strict} goes. */
    static Translation vertexFormat(String path, byte[] bodyBytes) {
        var tree = bodyBytes.length > 0 ? JqValues.parseStrict(bodyBytes) : null;
        if (!(tree instanceof JqObject root)) {
            return new Translation(path, bodyBytes);
        }
        var rebuilt = JqObject.builder(root.size());
        root.forEach((field, value) -> rebuilt.put(field, field.equals("tools") ? stripToolStrict(value) : value));
        return new Translation(path, rebuilt.build().toJsonString().getBytes(StandardCharsets.UTF_8));
    }

    private static JqValue stripToolStrict(JqValue tools) {
        if (!(tools instanceof JqArray array)) return tools;
        var builder = JqArray.arrayBuilder();
        array.asList().forEach(tool -> {
            if (tool instanceof JqObject obj) {
                var kept = JqObject.builder(obj.size());
                obj.forEach((k, v) -> {
                    if (!k.equals("strict")) kept.put(k, v);
                });
                builder.add(kept.build());
            } else {
                builder.add(tool);
            }
        });
        return builder.build();
    }

    private static JqValue stripCacheControlScope(JqValue node) {
        if (node instanceof JqObject obj) {
            var builder = JqObject.builder(obj.size());
            obj.forEach((key, value) -> {
                if (key.equals("cache_control") && value instanceof JqObject cc) {
                    var unscoped = JqObject.builder(cc.size());
                    cc.forEach((k, v) -> {
                        if (!k.equals("scope")) unscoped.put(k, v);
                    });
                    builder.put(key, unscoped.build());
                } else {
                    builder.put(key, stripCacheControlScope(value));
                }
            });
            return builder.build();
        }
        if (node instanceof JqArray array) {
            var builder = JqArray.arrayBuilder();
            array.asList().forEach(e -> builder.add(stripCacheControlScope(e)));
            return builder.build();
        }
        return node;
    }
}
