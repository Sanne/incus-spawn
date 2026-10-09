package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

/**
 * JSON-RPC 2.0 message shapes, over jjq trees so nothing needs reflection registration in
 * the native image.
 */
final class JsonRpc {

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private JsonRpc() {}

    static JqValue parse(String line) {
        return JqValues.parse(line);
    }

    static JqObject emptyObject() {
        return JqObject.builder(0).build();
    }

    static JqValue response(JqValue id, JqValue result) {
        return JqObject.builder(3)
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .build();
    }

    static JqValue error(JqValue id, int code, String message) {
        var err = JqObject.builder(2).put("code", (long) code).put("message", message).build();
        return JqObject.builder(3)
                .put("jsonrpc", "2.0")
                .put("id", id == null ? JqNull.NULL : id)
                .put("error", err)
                .build();
    }

    static JqValue notification(String method, JqValue params) {
        return JqObject.builder(3)
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("params", params)
                .build();
    }

    /**
     * Jackson's {@code path(field).asText(dflt)}: a missing field reads as the default,
     * an explicit null as {@code "null"}, a container as {@code ""}, anything else as its text.
     */
    static String text(JqValue node, String field, String dflt) {
        if (!(node instanceof JqObject object) || !object.keys().contains(field)) return dflt;
        return text(object.getField(field));
    }

    /** Jackson's {@code asText()}: null reads as {@code "null"}, containers as {@code ""}. */
    static String text(JqValue value) {
        if (value == null) return "";
        if (value.isNull()) return "null";
        if (value.isContainer()) return "";
        return value.asText();
    }

    /** Display fallback: a missing or null value reads as the default, anything else as its text. */
    static String orDefault(JqValue value, String dflt) {
        if (value == null || value.isNull()) return dflt;
        if (value.isContainer()) return "";
        return value.asText();
    }

    /** Jackson's {@code path(field).asBoolean(dflt)} for machine-generated input. */
    static boolean bool(JqValue node, String field, boolean dflt) {
        if (!(node instanceof JqObject object) || !object.keys().contains(field)) return dflt;
        var value = object.getField(field);
        return value.isBoolean() ? value.booleanValue() : dflt;
    }

    static String write(JqValue message) {
        return message.toJsonString();
    }

    static JqValue nullNode() {
        return JqNull.NULL;
    }
}
