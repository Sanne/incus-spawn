package dev.incusspawn.command;

import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.incus.IncusClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * What the overview reads inside one running guest (#2241), in one exec: the clones' work state
 * ({@link WorkState}) and each agent's sessions ({@link AgentSessions.GuestReader}). Each part is
 * its own section of the one script, run in its own subshell, and parsed on its own, so a session
 * read that fails or overflows never makes the work state unknown: the work section comes first,
 * and only the section the output bound cut is cut.
 */
public record GuestRead(WorkState work, AgentSessions sessions) {

    /**
     * The agents found but not read come first ({@link UnreadSessions}): their sections are a few
     * lines, so the output bound never cuts them.
     */
    static final List<AgentSessions.GuestReader> READERS = java.util.stream.Stream.concat(UnreadSessions.ALL.stream(),
            java.util.stream.Stream.of(new ClaudeSessions(), new CodexSessions(), new PiSessions(), new AntigravitySessions(), new OpencodeSessions())).toList();

    /** The whole output kept: the work state's own bound, then each reader's ({@link AgentSessions.GuestReader#maxOutput}). */
    static final int MAX_OUTPUT = WorkState.MAX_OUTPUT + READERS.stream().mapToInt(AgentSessions.GuestReader::maxOutput).sum();

    static final String SCRIPT = script();

    private static String script() {
        // The exit is the work section's: each reader's section is judged by its own end line
        var sb = new StringBuilder("(\n").append(WorkState.SCRIPT).append(")\nw=$?\n");
        for (var reader : READERS) sb.append("(\n").append(reader.script()).append(")\n");
        return sb.append("exit $w\n").toString();
    }

    public static final GuestRead NOT_READ = new GuestRead(WorkState.NOT_READ, AgentSessions.NOT_READ);

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
        var agents = new ArrayList<String>();
        for (int r = 0; r < READERS.size(); r++) {
            agents.add(READERS.get(r).agent());
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
        return new GuestRead(work, AgentSessions.merge(agents, read, READERS.stream().map(AgentSessions.GuestReader::readsSessions).toList()));
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

    /**
     * Every instance's read by name, in parallel, but only as many at once as the connection cap
     * carries (#2527, {@code IncusApi.BOUNDED_EXECS}: 62 on Linux, 10 on macOS): a rolling window, so
     * N reads take about N/slots times one read. On macOS that is up to about 40 s for 30 instances
     * whose reads all time out (10 s and the 3 s kill grace, three waves).
     */
    public static Map<String, GuestRead> readAll(IncusClient incus, List<InstanceInfo> instances) {
        var done = new java.util.concurrent.ConcurrentHashMap<String, GuestRead>();
        readEach(incus, instances, done::put);
        var reads = new LinkedHashMap<String, GuestRead>();
        for (var instance : instances) reads.put(instance.name(), done.getOrDefault(instance.name(), unknown("it did not answer")));
        return reads;
    }

    /**
     * As {@link #readAll}, but each instance's read is handed to {@code read} as soon as it ends,
     * on the thread that made it, so the TUI shows a fast guest without waiting for a slow one
     * (#2625). The reads are started in the order of {@code instances}, so the first ones take the
     * first slots. Returns once every read was handed on.
     */
    public static void readEach(IncusClient incus, List<InstanceInfo> instances,
                                java.util.function.BiConsumer<String, GuestRead> read) {
        // One operation subscription tells every read when its command ended (#2626); none is
        // opened when no instance runs, since then no read execs anything
        boolean anyRunning = instances.stream().anyMatch(i -> "running".equalsIgnoreCase(i.status()));
        try (var batch = anyRunning ? incus.probeBatch() : null;
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var instance : instances) {
                pool.submit(() -> {
                    GuestRead guest;
                    try {
                        guest = read(incus, instance);
                    } catch (RuntimeException e) {
                        guest = unknown("it did not answer: " + e.getMessage());
                    }
                    read.accept(instance.name(), guest);
                });
            }
        }
    }
}
