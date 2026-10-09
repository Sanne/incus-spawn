package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small builder for tool input and output schemas (JSON Schema 2020-12 objects, the dialect MCP
 * assumes when a schema names none). Every object is closed ({@code additionalProperties: false}),
 * so a field a result gains without its schema fails validation in the tests.
 */
final class Schema {

    private final Map<String, JqValue> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private Schema() {}

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
        var types = JqArray.arrayBuilder().add("integer").add("null").build();
        return add(name, description, isRequired, Map.of("type", types));
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
        var e = JqArray.arrayBuilder();
        for (var v : values) e.add(v);
        var fields = new LinkedHashMap<String, JqValue>();
        fields.put("type", JqString.of("string"));
        fields.put("enum", e.build());
        return add(name, description, isRequired, fields);
    }

    Schema stringList(String name, String description) {
        return stringList(name, description, false);
    }

    Schema stringList(String name, String description, boolean isRequired) {
        var fields = new LinkedHashMap<String, JqValue>();
        fields.put("type", JqString.of("array"));
        fields.put("items", JqObject.builder(1).put("type", "string").build());
        return add(name, description, isRequired, fields);
    }

    Schema stringMap(String name, String description) {
        var fields = new LinkedHashMap<String, JqValue>();
        fields.put("type", JqString.of("object"));
        fields.put("additionalProperties", JqObject.builder(1).put("type", "string").build());
        return add(name, description, false, fields);
    }

    Schema object(String name, String description, boolean isRequired, Schema nested) {
        return add(name, description, isRequired, nested.build().asMap());
    }

    Schema list(String name, String description, boolean isRequired, Schema items) {
        var fields = new LinkedHashMap<String, JqValue>();
        fields.put("type", JqString.of("array"));
        fields.put("items", items.build());
        return add(name, description, isRequired, fields);
    }

    private Schema prop(String name, String type, String description, boolean isRequired) {
        return add(name, description, isRequired, Map.of("type", JqString.of(type)));
    }

    private Schema add(String name, String description, boolean isRequired, Map<String, JqValue> fields) {
        var b = JqObject.builder(fields.size() + 1);
        if (description != null) b.put("description", description);
        fields.forEach(b::put);
        properties.put(name, b.build());
        if (isRequired) required.add(name);
        return this;
    }

    JqObject build() {
        var props = JqObject.builder(properties.size());
        properties.forEach(props::put);
        var req = JqArray.arrayBuilder();
        required.forEach(req::add);
        return JqObject.builder(4)
                .put("type", "object")
                .put("properties", props.build())
                .put("required", req.build())
                .put("additionalProperties", false)
                .build();
    }
}
