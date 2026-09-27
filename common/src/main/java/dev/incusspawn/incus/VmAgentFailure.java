package dev.incusspawn.incus;

import java.util.List;

/**
 * Recognizes, in a VM's console log, the lines that say its incus-agent failed to start.
 *
 * <p>A VM whose agent never comes up is otherwise indistinguishable from one still booting: every
 * exec simply fails. The console log says which it is, in lines short and distinctive enough to
 * match (#844). The SELinux case of #842 logs both of these:
 *
 * <pre>
 * avc:  denied  { listen } for  pid=1288 comm="incus-agent" ... tclass=vsock_socket permissive=0
 * systemd[1]: Failed to start incus-agent.service - Incus - agent.
 * </pre>
 */
public final class VmAgentFailure {

    private VmAgentFailure() {}

    /** At most this many matched lines are reported; the agent's restarts repeat them. */
    static final int MAX_LINES = 6;

    /** The lines of {@code consoleLog} that report the agent failing, trimmed and deduplicated. */
    public static List<String> matchingLines(String consoleLog) {
        if (consoleLog == null || consoleLog.isEmpty()) return List.of();
        return consoleLog.lines()
                .map(VmAgentFailure::stripControl)
                .map(String::strip)
                .filter(VmAgentFailure::isFailure)
                .distinct()
                .limit(MAX_LINES)
                .toList();
    }

    static boolean isFailure(String line) {
        if (line.contains("Failed to start incus-agent")) return true;
        return line.contains("avc:") && line.contains("denied") && line.contains("comm=\"incus-agent\"");
    }

    /** Console output carries ANSI colour and carriage returns; neither belongs in an error message. */
    private static String stripControl(String line) {
        return line.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").replace("\r", "");
    }
}
