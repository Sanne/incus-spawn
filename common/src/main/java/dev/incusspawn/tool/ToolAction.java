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
     * Stable identifier for referencing this action (e.g. from {@code default-action}).
     */
    default java.util.Optional<String> id() {
        return java.util.Optional.empty();
    }

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
     * Whether this action must be executed after leaving the TUI (e.g. interactive commands).
     */
    default boolean needsDeferredExecution() {
        return false;
    }

    /**
     * If this action runs a command inside the container, returns the shell command string.
     * Empty for non-command actions (url, clipboard, etc.).
     */
    default java.util.Optional<String> shellCommand(ActionContext context) {
        return java.util.Optional.empty();
    }

    default boolean isShellMenu() {
        return false;
    }

    default java.util.Optional<String> shortcut() {
        return java.util.Optional.empty();
    }

    default java.util.Optional<String> type() {
        return java.util.Optional.empty();
    }

    /**
     * The declaration this action was made from: the copies {@code expand: repos} makes of one
     * declaration, one per repo, share it. Compared by identity.
     */
    default Object expandedFrom() {
        return this;
    }

    default java.util.Optional<String> repoPath() {
        return java.util.Optional.empty();
    }

    /**
     * Execute the action. Returns a result indicating what happened.
     *
     * @param context runtime information about the instance
     * @return result describing outcome (message to display, or URL opened)
     */
    ActionResult execute(ActionContext context);

    /**
     * Execute the action without ever reading the terminal or printing to it, for a caller that
     * does not own the terminal -- the shell status bar's menu runs while the session holds it in
     * raw mode. Where {@link #execute} would ask the user something, this returns an error saying
     * what to do instead. Refuses unless an implementation knows it never prompts: falling back to
     * {@link #execute} would make that guarantee depend on every future action remembering it.
     */
    default ActionResult executeWithoutPrompting(ActionContext context) {
        return ActionResult.error("'" + label() + "' cannot run from here; use isx run or the TUI");
    }
}
