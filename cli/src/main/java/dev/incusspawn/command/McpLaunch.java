package dev.incusspawn.command;

import dev.incusspawn.Platform;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The command an MCP client on this host runs to start {@code isx mcp} (#2189).
 * {@code isx mcp register}, {@code isx mcp config}, and init's registration and its plan all give
 * this one, so an agent never has to work out the form itself.
 *
 * <p>On Linux it runs under {@code sg incus-admin} when the group database lists the user in
 * {@code incus-admin} and {@code sg} is installed.
 * An MCP client starts the server with the groups of its own session, and a session from before
 * {@code isx init}'s root script added the user to the group is refused by the Incus socket.
 * {@code sg} is a safeguard, never a requirement: without it the command is the plain one, which
 * works once the client's session has the group, from a new login.
 * The plain {@code isx mcp} still re-runs itself under {@code sg} when the socket refuses it
 * ({@link GroupContinuation}), so the wrapper is a second safeguard.
 * It keeps {@code incus-admin} as the primary group, unlike {@link GroupContinuation}'s inner
 * {@code sg}, so the entry stays one a person can read; {@code incus-admin} is root's equal on the
 * host, so files the server writes under that group give its members nothing they lacked.
 */
class McpLaunch {

    static final String GROUP = "incus-admin";

    /** What advice shows when no installed isx is found; never quoted. */
    static final String PLACEHOLDER = "<path to isx>";

    /** An argument the shell reads as itself, so it is shown and written without quotes. */
    private static final Pattern SHELL_SAFE = Pattern.compile("[A-Za-z0-9_./:=+,@%-]+");

    enum Form {
        /** {@code <isx> mcp}: not Linux, or the user is not in {@code incus-admin}. */
        PLAIN,
        /** {@code sg incus-admin -c "exec <isx> mcp"}. */
        SG,
        /** {@code <isx> mcp}, though the user is in {@code incus-admin}, since {@code sg} is not installed. */
        PLAIN_WITHOUT_SG
    }

    /** The form this host takes, which runs {@code id} only on Linux. */
    Form form() {
        if (!Platform.isLinux() || !inGroup()) return Form.PLAIN;
        return hasSg() ? Form.SG : Form.PLAIN_WITHOUT_SG;
    }

    /** The command for the isx at {@code isxPath}, in this host's form. */
    List<String> command(String isxPath) {
        return command(isxPath, form());
    }

    static List<String> command(String isxPath, Form form) {
        if (form != Form.SG) return List.of(isxPath, "mcp");
        var isx = isxPath.equals(PLACEHOLDER) ? isxPath : quoted(isxPath);
        return List.of("sg", GROUP, "-c", "exec " + isx + " mcp");
    }

    /** {@code command} as a person types it into a shell. */
    static String display(List<String> command) {
        return command.stream().map(McpLaunch::quoted).collect(Collectors.joining(" "));
    }

    /** {@code command} after {@code prefix}, as one list. */
    static List<String> after(List<String> prefix, List<String> command) {
        var all = new ArrayList<>(prefix);
        all.addAll(command);
        return List.copyOf(all);
    }

    /**
     * Whether the agent's session is likely to be refused: {@code sg} is missing and this process,
     * which the agent started, cannot reach the Incus socket.
     */
    boolean sessionRefused(Form form) {
        return form == Form.PLAIN_WITHOUT_SG && IncusClient.socketDenied();
    }

    boolean inGroup() {
        return new GroupContinuation().configuredGroups().contains(GROUP);
    }

    boolean hasSg() {
        return new GroupContinuation().hasSg();
    }

    private static String quoted(String arg) {
        return arg.equals(PLACEHOLDER) || SHELL_SAFE.matcher(arg).matches() ? arg : Container.shellQuote(arg);
    }
}
