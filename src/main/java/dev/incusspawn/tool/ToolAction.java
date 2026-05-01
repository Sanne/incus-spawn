package dev.incusspawn.tool;

/**
 * A runtime action contributed by a tool that can be performed on a live instance.
 * Actions are discovered by the TUI/CLI and presented to the user when the
 * required tool is installed on the instance's template.
 */
public interface ToolAction {

    /**
     * The tool name this action belongs to (must match a ToolSetup name).
     */
    String toolName();

    /**
     * Human-readable label shown in the actions menu (e.g., "Open in Gateway").
     */
    String label();

    /**
     * Whether the instance must be running for this action to be available.
     */
    default boolean requiresRunning() {
        return true;
    }

    /**
     * Execute the action. Returns a result indicating what happened.
     *
     * @param context runtime information about the instance
     * @return result describing outcome (message to display, or URL opened)
     */
    ActionResult execute(ActionContext context);
}
