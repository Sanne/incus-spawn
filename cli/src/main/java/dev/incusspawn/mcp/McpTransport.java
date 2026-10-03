package dev.incusspawn.mcp;

import java.io.IOException;

/**
 * Where MCP messages come from and go to. Only stdio exists today; a loopback HTTP transport
 * would implement this too, identifying its session by token rather than by process.
 */
interface McpTransport {

    /** The next message as raw JSON text, or null once the client has gone away. */
    String read() throws IOException;

    /** Send one message. Safe to call from several threads at once. */
    void send(String json) throws IOException;
}
