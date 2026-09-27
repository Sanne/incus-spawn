package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A small builder for tool input schemas (JSON Schema objects). */
final class Schema {

    private final ObjectNode root = JsonRpc.JSON.createObjectNode();
    private final ObjectNode properties;
    private final ArrayNode required;

    private Schema() {
        root.put("type", "object");
        properties = root.putObject("properties");
        required = root.putArray("required");
        root.put("additionalProperties", false);
    }

    static Schema object() {
        return new Schema();
    }

    Schema string(String name, String description, boolean isRequired) {
        return prop(name, "string", description, isRequired);
    }

    Schema integer(String name, String description, boolean isRequired) {
        return prop(name, "integer", description, isRequired);
    }

    Schema bool(String name, String description) {
        return prop(name, "boolean", description, false);
    }

    Schema stringMap(String name, String description) {
        var p = properties.putObject(name);
        p.put("type", "object");
        p.put("description", description);
        p.putObject("additionalProperties").put("type", "string");
        return this;
    }

    private Schema prop(String name, String type, String description, boolean isRequired) {
        var p = properties.putObject(name);
        p.put("type", type);
        p.put("description", description);
        if (isRequired) required.add(name);
        return this;
    }

    ObjectNode build() {
        return root.deepCopy();
    }
}
