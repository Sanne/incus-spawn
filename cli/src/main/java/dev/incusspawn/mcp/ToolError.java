package dev.incusspawn.mcp;

/**
 * A tool failure the agent is meant to read: returned as an {@code isError} result, never as a
 * protocol error. The message should say what to do next ("ask the user to run ...").
 */
final class ToolError extends RuntimeException {
    ToolError(String message) {
        super(message);
    }
}
