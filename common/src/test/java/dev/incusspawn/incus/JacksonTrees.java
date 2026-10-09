package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Test-only bridge from Jackson trees to {@link JqValue}, for the call sites
 * that feed {@link FakeIncusDaemon} output into config-tree readers. The
 * daemon itself stays Jackson (HTTP-level fake); production code has no
 * Jackson trees left to convert.
 */
public final class JacksonTrees {

    private JacksonTrees() {}

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Test-only bridge from {@link JqValue} to Jackson, for feeding jjq trees
     * to APIs that still speak Jackson (the networknt schema validator in
     * {@code StructuredResults}). The reverse of {@link #fromJsonNode}.
     */
    public static JsonNode toJsonNode(JqValue value) {
        if (value == null || value.isNull()) return JSON.nullNode();
        if (value.isString()) return JSON.getNodeFactory().textNode(value.asText());
        if (value.isIntegralNumber()) return JSON.getNodeFactory().numberNode(value.longValue());
        if (value.isFloatingPointNumber()) return JSON.getNodeFactory().numberNode(value.doubleValue());
        if (value.isBoolean()) return JSON.getNodeFactory().booleanNode(value.booleanValue());
        if (value.isArray()) {
            var arr = JSON.createArrayNode();
            value.asList().forEach(child -> arr.add(toJsonNode(child)));
            return arr;
        }
        if (value.isObject()) {
            var obj = JSON.createObjectNode();
            value.asMap().forEach((k, v) -> obj.set(k, toJsonNode(v)));
            return obj;
        }
        return JSON.getNodeFactory().textNode(value.asText());
    }

    public static JqValue fromJsonNode(JsonNode node) {
        if (node == null || node.isNull()) return JqNull.NULL;
        if (node.isTextual()) return JqString.of(node.asText());
        if (node.isIntegralNumber()) return JqNumber.of(node.longValue());
        if (node.isFloatingPointNumber()) return JqNumber.of(node.doubleValue());
        if (node.isBoolean()) return node.booleanValue() ? JqBoolean.TRUE : JqBoolean.FALSE;
        if (node.isArray()) {
            var builder = JqArray.arrayBuilder();
            node.forEach(child -> builder.add(fromJsonNode(child)));
            return builder.build();
        }
        if (node.isObject()) {
            var builder = JqObject.builder(node.size());
            node.fields().forEachRemaining(e -> builder.put(e.getKey(), fromJsonNode(e.getValue())));
            return builder.build();
        }
        return JqString.of(node.asText());
    }
}
