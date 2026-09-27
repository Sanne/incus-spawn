package dev.incusspawn.incus;

import dev.incusspawn.util.BuildOutput;

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
                .filter(l -> l.contains("incus-agent"))
                .map(l -> BuildOutput.stripAnsi(l).replace("\r", "").strip())
                .filter(VmAgentFailure::isFailure)
                .distinct()
                .limit(MAX_LINES)
                .toList();
    }

    /**
     * {@link #matchingLines}, or none when systemd started the agent after its last failure:
     * a failure the agent recovered from says nothing about why it is unresponsive now.
     * systemd reports both on the console only during boot, so their order is comparable.
     */
    public static List<String> unrecoveredLines(String consoleLog) {
        if (consoleLog == null || consoleLog.isEmpty()) return List.of();
        int lastFailure = -1;
        int lastStart = -1;
        var lines = consoleLog.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (!line.contains("incus-agent")) continue;
            if (line.contains("Started incus-agent")) lastStart = i;
            else if (isFailure(line)) lastFailure = i;
        }
        return lastFailure > lastStart ? matchingLines(consoleLog) : List.of();
    }

    /** Say that {@code name}'s agent failed to start, quoting {@code lines} from its console log. */
    public static String report(String name, List<String> lines) {
        var msg = new StringBuilder("The incus-agent in VM ").append(name)
                .append(" failed to start. Its console log says:\n");
        lines.forEach(l -> msg.append("  ").append(l).append('\n'));
        msg.append("Full boot log: incus console ").append(name).append(" --show-log");
        return msg.toString();
    }

    static boolean isFailure(String line) {
        if (line.contains("Failed to start incus-agent")) return true;
        return line.contains("avc:") && line.contains("denied") && line.contains("comm=\"incus-agent\"");
    }
}
