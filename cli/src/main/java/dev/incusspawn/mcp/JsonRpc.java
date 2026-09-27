package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 message shapes, over Jackson trees so nothing needs reflection registration in
 * the native image.
 */
final class JsonRpc {

    static final ObjectMapper JSON = new ObjectMapper();

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {}

    static ObjectNode response(JsonNode id, JsonNode result) {
        var msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        msg.set("result", result);
        return msg;
    }

    static ObjectNode error(JsonNode id, int code, String message) {
        var msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id == null ? JSON.nullNode() : id);
        var err = msg.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return msg;
    }

    static ObjectNode notification(String method, JsonNode params) {
        var msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        msg.set("params", params);
        return msg;
    }
}
