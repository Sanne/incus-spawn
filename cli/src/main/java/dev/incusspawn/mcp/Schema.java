package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A small builder for tool input and output schemas (JSON Schema 2020-12 objects, the dialect MCP
 * assumes when a schema names none). Every object is closed ({@code additionalProperties: false}),
 * so a field a result gains without its schema fails validation in the tests.
 */
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

    /** An integer, or null where there is none to give (a binary file's line counts). */
    Schema nullableInteger(String name, String description, boolean isRequired) {
        var p = add(name, description, isRequired);
        p.putArray("type").add("integer").add("null");
        return this;
    }

    Schema number(String name, String description, boolean isRequired) {
        return prop(name, "number", description, isRequired);
    }

    Schema bool(String name, String description) {
        return bool(name, description, false);
    }

    Schema bool(String name, String description, boolean isRequired) {
        return prop(name, "boolean", description, isRequired);
    }

    /** A string that is one of {@code values}. */
    Schema choice(String name, String description, boolean isRequired, String... values) {
        var p = add(name, description, isRequired);
        p.put("type", "string");
        var e = p.putArray("enum");
        for (var v : values) e.add(v);
        return this;
    }

    Schema stringList(String name, String description) {
        return stringList(name, description, false);
    }

    Schema stringList(String name, String description, boolean isRequired) {
        var p = add(name, description, isRequired);
        p.put("type", "array");
        p.putObject("items").put("type", "string");
        return this;
    }

    Schema stringMap(String name, String description) {
        var p = add(name, description, false);
        p.put("type", "object");
        p.putObject("additionalProperties").put("type", "string");
        return this;
    }

    Schema object(String name, String description, boolean isRequired, Schema nested) {
        var p = add(name, description, isRequired);
        p.setAll(nested.root);
        return this;
    }

    Schema list(String name, String description, boolean isRequired, Schema items) {
        var p = add(name, description, isRequired);
        p.put("type", "array");
        p.set("items", items.root);
        return this;
    }

    private Schema prop(String name, String type, String description, boolean isRequired) {
        add(name, description, isRequired).put("type", type);
        return this;
    }

    private ObjectNode add(String name, String description, boolean isRequired) {
        var p = properties.putObject(name);
        if (description != null) p.put("description", description);
        if (isRequired) required.add(name);
        return p;
    }

    ObjectNode build() {
        return root;
    }
}
