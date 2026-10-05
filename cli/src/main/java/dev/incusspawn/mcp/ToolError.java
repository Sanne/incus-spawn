package dev.incusspawn.mcp;

import java.util.Locale;

/**
 * A tool failure the agent is meant to read: returned as an {@code isError} result, never as a
 * protocol error. The message should say what to do next ("ask the user to run ..."); the
 * {@link Code} is for a program, which should not have to parse the message to branch on it.
 */
final class ToolError extends RuntimeException {

    /** What kind of refusal, on the wire in lower case: a program branches on this, so keep it stable. */
    enum Code {
        /** An argument is missing, mistyped, out of range, or combined with one it excludes. */
        INVALID_ARGUMENT,
        /** The template is not approved for agents, or no longer is. */
        NOT_APPROVED,
        /** No such instance or task. */
        NOT_FOUND,
        /** It exists, but another session holds it. */
        NOT_HELD,
        /** It is in a state that does not allow this: stopped, running, attached, still being created, not built. */
        WRONG_STATE,
        /** Another isx operation holds it right now: try again shortly. */
        BUSY,
        /** The user's instance or task limit is reached. */
        LIMIT,
        /** Incus or the instance could not be reached or did not do what was asked. */
        UNAVAILABLE,
        /** A delegated task ended without a report. */
        TASK_FAILED,
        /** Refused for another reason: the message says why. */
        REFUSED,
        /** isx failed: a bug, or something it did not expect. */
        INTERNAL;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    final Code code;

    ToolError(Code code, String message) {
        super(message);
        this.code = code;
    }
}
