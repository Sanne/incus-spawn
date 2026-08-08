package dev.incusspawn.tool;

/**
 * Outcome of executing a ToolAction.
 */
public record ActionResult(boolean success, String message) {

    public static ActionResult ok(String message) {
        return new ActionResult(true, message);
    }

    public static ActionResult error(String message) {
        return new ActionResult(false, message);
    }
}
