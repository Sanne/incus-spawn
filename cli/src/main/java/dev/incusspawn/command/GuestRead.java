package dev.incusspawn.command;

import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.incus.IncusClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * What the overview reads inside one running guest (#2241), in one exec: the clones' work state
 * ({@link WorkState}) and each agent's sessions ({@link AgentSessions.GuestReader}). Each part is
 * its own section of the one script, run in its own subshell, and parsed on its own, so a session
 * read that fails or overflows never makes the work state unknown: the work section comes first,
 * and only the section the output bound cut is cut.
 */
public record GuestRead(WorkState work, AgentSessions sessions) {

    static final List<AgentSessions.GuestReader> READERS = List.of(new ClaudeSessions());

    /** The whole output kept: the work state's own bound, then room for every reader's newest sessions. */
    static final int MAX_OUTPUT = WorkState.MAX_OUTPUT + 1024 * 1024;

    static final String SCRIPT = script();

    private static String script() {
        // The exit is the work section's: each reader's section is judged by its own end line
        var sb = new StringBuilder("(\n").append(WorkState.SCRIPT).append(")\nw=$?\n");
        for (var reader : READERS) sb.append("(\n").append(reader.script()).append(")\n");
        return sb.append("exit $w\n").toString();
    }

    static final GuestRead NOT_READ = new GuestRead(WorkState.NOT_READ, AgentSessions.NOT_READ);

    static GuestRead unknown(String why) {
        return new GuestRead(WorkState.unknown(why), AgentSessions.unknown(why));
    }

    /** The script's exit and output, each section on its own. */
    static GuestRead parse(int exit, String output, boolean cut) {
        var lines = output.lines().toList();
        var starts = new ArrayList<Integer>();
        for (var reader : READERS) starts.add(lines.indexOf(reader.header()));
        int firstReader = starts.stream().filter(i -> i >= 0).min(Integer::compare).orElse(lines.size());
        var workLines = lines.subList(0, firstReader);
        var workText = String.join("\n", workLines) + "\n";
        // The bound cut the last section only: a work section followed by another is whole
        boolean workCut = firstReader == lines.size() ? cut : workText.length() > WorkState.MAX_OUTPUT;
        var work = WorkState.parse(exit, workText, workCut);
        var read = new ArrayList<AgentSessions>();
        for (int r = 0; r < READERS.size(); r++) {
            int start = starts.get(r);
            if (start < 0) {
                read.add(AgentSessions.unknown(cut ? "its answer was too long" : "its answer was cut short"));
                continue;
            }
            int end = lines.size();
            for (int s : starts) if (s > start && s < end) end = s;
            boolean last = end == lines.size();
            read.add(last && cut ? AgentSessions.unknown("its answer was too long")
                    : READERS.get(r).parse(lines.subList(start, end)));
        }
        return new GuestRead(work, AgentSessions.merge(read));
    }

    /** One instance: read in the guest when it runs, {@link #NOT_READ} when it does not. */
    static GuestRead read(IncusClient incus, InstanceInfo instance) {
        if (!"running".equalsIgnoreCase(instance.status())) return NOT_READ;
        var out = new WorkState.Bounded(MAX_OUTPUT);
        try {
            int exit = incus.execProbe(instance.name(), WorkState.AGENT_UID, WorkState.AGENT_HOME, SCRIPT, out, WorkState.LIMIT);
            return parse(exit, out.text(), out.cut);
        } catch (RuntimeException e) {
            return unknown("it did not answer: " + e.getMessage());
        }
    }

    /** Every instance's read by name, in parallel, so the slowest instance and not their sum sets the wait. */
    public static Map<String, GuestRead> readAll(IncusClient incus, List<InstanceInfo> instances) {
        var reads = new LinkedHashMap<String, GuestRead>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new LinkedHashMap<String, Future<GuestRead>>();
            for (var instance : instances) futures.put(instance.name(), pool.submit(() -> read(incus, instance)));
            futures.forEach((name, future) -> {
                try {
                    reads.put(name, future.get());
                } catch (Exception e) {
                    reads.put(name, unknown("it did not answer: " + e.getMessage()));
                }
            });
        }
        return reads;
    }
}
