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
 *
 * <p>{@code at} is when the guest answered, null when it was not read. A running instance's read
 * is kept on the host ({@link KeptReads}), and so is one made just before each stop isx makes, so
 * a stopped instance shows the last reading of its guest with its age (#2752).
 */
public record GuestRead(WorkState work, AgentSessions sessions, java.time.Instant at) {

    GuestRead(WorkState work, AgentSessions sessions) {
        this(work, sessions, null);
    }

    /**
     * The agents found but not read come first ({@link UnreadSessions}): their sections are a few
     * lines, so the output bound never cuts them.
     */
    static final List<AgentSessions.GuestReader> READERS = java.util.stream.Stream.concat(UnreadSessions.ALL.stream(),
            java.util.stream.Stream.of(new ClaudeSessions(), new CodexSessions(), new PiSessions(), new AntigravitySessions(),
                    new GeminiSessions(), new CopilotSessions(), new OpencodeSessions(), new BobSessions())).toList();

    /** The whole output kept: the work state's own bound, then each reader's ({@link AgentSessions.GuestReader#maxOutput}). */
    static final int MAX_OUTPUT = WorkState.MAX_OUTPUT + READERS.stream().mapToInt(AgentSessions.GuestReader::maxOutput).sum();

    static final String SCRIPT = script();

    /**
     * The work section and the readers' run at once (#2627), so the clones' read, the slow part,
     * no longer waits for the sessions' or they for it. The work section is written straight
     * out; the readers' sections go, in their order, to a file removed as soon as it is open, so
     * a probe killed at any point leaves nothing behind, and at most the whole output's bound plus
     * a byte of them is kept there, so readers past their room still read as cut. Once both ended
     * the readers' are written after the work section, the output in the same order as one at a
     * time, so the output bound cuts the same section. When no such file can be made, the
     * sections run one after another as before.
     */
    private static String script() {
        // The exit is the work section's: each reader's section is judged by its own end line
        var sb = new StringBuilder("work() (\n").append(WorkState.SCRIPT).append(")\nreaders() {\n");
        for (var reader : READERS) sb.append("(\n").append(reader.script()).append(")\n");
        return sb.append("""
                }
                w=
                if f=$(mktemp "${TMPDIR:-/tmp}/isx-guest-read.XXXXXX" 2>/dev/null); then
                  {
                    rm -f "$f"
                    readers </dev/null 2>/dev/null | head -c %d >&3 &
                    b=$!
                    trap 'kill "$b" 2>/dev/null; exit 143' TERM
                    work
                    w=$?
                    wait "$b"
                    cat <&4
                  } 3>"$f" 4<"$f"
                  rm -f "$f"
                fi
                if [ -z "$w" ]; then
                  work
                  w=$?
                  readers
                fi
                exit $w
                """.formatted(MAX_OUTPUT + 1)).toString();
    }

    public static final GuestRead NOT_READ = new GuestRead(WorkState.NOT_READ, AgentSessions.NOT_READ);

    static GuestRead unknown(String why) {
        return new GuestRead(WorkState.unknown(why), AgentSessions.unknown(why));
    }

    /** What the guest answered, as the exec gave it: kept as it is, and parsed when shown. */
    record Answer(int exit, String output, boolean cut, java.time.Instant at) {
        GuestRead parsed() {
            var read = GuestRead.parse(exit, output, cut);
            return new GuestRead(read.work(), read.sessions(), at);
        }
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

    /**
     * One instance: read in the guest when it runs, and kept; when it does not, the reading
     * {@code kept} holds of it, or {@link #NOT_READ}.
     */
    static GuestRead read(IncusClient incus, InstanceInfo instance, KeptReads kept) {
        var incarnation = OverviewCommand.incarnation(instance);
        if (!"running".equalsIgnoreCase(instance.status())) {
            var last = kept.get(instance.name(), incarnation);
            return last == null ? NOT_READ : last;
        }
        try {
            var answer = ask(incus, instance.name(), WorkState.LIMIT);
            kept.put(instance.name(), incarnation, answer);
            return answer.parsed();
        } catch (RuntimeException e) {
            return unknown("it did not answer: " + e.getMessage());
        }
    }

    /** Runs the script in the guest, killed after {@code limit}. */
    static Answer ask(IncusClient incus, String name, java.time.Duration limit) {
        var out = new WorkState.Bounded(MAX_OUTPUT);
        int exit = incus.execProbe(name, WorkState.AGENT_UID, WorkState.AGENT_HOME, SCRIPT, out, limit);
        return new Answer(exit, out.text(), out.cut, java.time.Instant.now());
    }

    /**
     * Every instance's read by name, in parallel, but only as many at once as the connection cap
     * carries (#2527, {@code IncusApi.BOUNDED_EXECS}: 62 on Linux, 36 on macOS): a rolling window, so
     * N reads take about N/slots times one read. For 30 instances whose reads all time out that is
     * one wave on either platform, about 13 s (10 s and the 3 s kill grace).
     */
    public static Map<String, GuestRead> readAll(IncusClient incus, List<InstanceInfo> instances, KeptReads kept) {
        var done = new java.util.concurrent.ConcurrentHashMap<String, GuestRead>();
        readEach(incus, instances, kept, done::put);
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
    public static void readEach(IncusClient incus, List<InstanceInfo> instances, KeptReads kept,
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
                        guest = read(incus, instance, kept);
                    } catch (RuntimeException e) {
                        guest = unknown("it did not answer: " + e.getMessage());
                    }
                    read.accept(instance.name(), guest);
                });
            }
        }
    }
}
