package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

/**
 * Reads the proxy's event log (#2241) back into what each instance did: its model calls and
 * tokens, when it was first and last active, the branches it pushed and the pull requests it
 * opened.
 *
 * <p>The proxy writes the log ({@code EventLog} in the proxy module) and never reads it; this is
 * the reader the overview uses, on demand only. Lines are matched to an instance by its
 * incarnation, the listing's {@code created_at} (#1063), which survives a rename and differs for
 * a name branched again. A line the proxy wrote without one is matched by name alone.
 *
 * <p>The log is bounded by size, so what it says starts at its oldest line ({@link Log#since}),
 * and lines the proxy lost under load are counted ({@link Log#droppedLines}). A line that does not
 * parse, such as one a full disk tore, is skipped and counted.
 */
public final class EventLogReader {

    /** The most branches and pull requests kept per instance, the newest, so a guest that pushes without end cannot grow the overview. */
    public static final int MAX_KEPT = 50;

    private EventLogReader() {}

    /** A pull request an instance opened. */
    public record PullRequest(String repo, long number) {
        @Override
        public String toString() {
            return repo + "#" + number;
        }
    }

    /** A branch an instance pushed and did not delete afterwards. */
    public record Branch(String repo, String name) {
        @Override
        public String toString() {
            return repo + ":" + name;
        }
    }

    /**
     * What one instance did, as the log saw it. {@code first} and {@code last} are null when it has
     * no line. The branches are those its pushes updated and no later push deleted, oldest first.
     */
    public record Effort(long modelCalls, long inputTokens, long outputTokens, long cacheReadTokens,
                         long cacheCreationTokens, Instant first, Instant last,
                         List<Branch> branches, List<PullRequest> pullRequests) {
        public static final Effort NONE = new Effort(0, 0, 0, 0, 0, null, null, List.of(), List.of());

        /** Every token the model calls counted, cached ones included. */
        public long tokens() {
            return inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens;
        }

        /** Whether the log holds anything of it. */
        public boolean any() {
            return first != null;
        }
    }

    /**
     * The whole log, read once. {@code since} is the time of its oldest readable line, null when
     * there is none; {@code files} says whether any file was there at all.
     */
    public record Log(Map<String, Effort> byIncarnation, Map<String, Effort> byName, Instant since,
                      long droppedLines, long unreadableLines, boolean files) {
        public static final Log EMPTY = new Log(Map.of(), Map.of(), null, 0, 0, false);

        /** What the instance of this name and incarnation ({@code created_at}) did. */
        public Effort of(String name, String incarnation) {
            Effort byCreation = incarnation == null || incarnation.isEmpty() ? null : byIncarnation.get(incarnation);
            Effort unnamed = byName.get(name);
            if (byCreation == null) return unnamed == null ? Effort.NONE : unnamed;
            return unnamed == null ? byCreation : merge(byCreation, unnamed);
        }
    }

    /** The log at its place on the host. */
    public static Log read() {
        return read(Environment.proxyEventLogFile());
    }

    /** The log whose current file is {@code file}: its older files first, {@code .4} to {@code .1}, then it. */
    public static Log read(Path file) {
        var reading = new Reading();
        // Every file is opened before any is read: a rollover renames them, and an open file keeps
        // its lines, so one during the read neither skips a file nor reads one twice
        var opened = new java.util.ArrayList<InputStream>();
        try {
            for (int i = KEPT; i >= 0; i--) {
                var path = i == 0 ? file : file.resolveSibling(file.getFileName() + "." + i);
                try {
                    opened.add(Files.newInputStream(path));
                } catch (NoSuchFileException absent) {
                    // Not kept yet
                } catch (IOException e) {
                    reading.unreadable++;
                }
            }
            for (var in : opened) reading.file(in);
        } finally {
            for (var in : opened) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // read already
                }
            }
        }
        return reading.done();
    }

    /** The older files the proxy keeps beside the current one: {@code EventLog} rolls by this. */
    public static final int KEPT = 4;

    private static final class Tally {
        long calls, input, output, cacheRead, cacheCreation;
        Instant first, last;
        final SequencedSet<Branch> branches = new LinkedHashSet<>();
        final SequencedSet<PullRequest> pulls = new LinkedHashSet<>();

        void seen(Instant at) {
            if (first == null || at.isBefore(first)) first = at;
            if (last == null || at.isAfter(last)) last = at;
        }

        Effort effort() {
            return new Effort(calls, input, output, cacheRead, cacheCreation, first, last,
                    List.copyOf(branches), List.copyOf(pulls));
        }
    }

    private static final class Reading {
        final Map<String, Tally> byIncarnation = new LinkedHashMap<>();
        final Map<String, Tally> byName = new HashMap<>();
        Instant since;
        long dropped, unreadable;
        boolean files;

        void file(InputStream stream) {
            // Bytes that are not UTF-8, as a write a full disk cut short leaves, spoil their line alone
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, decoder))) {
                files = true;
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) line(line);
                }
            } catch (IOException | RuntimeException e) {
                // An unreadable file: what came before in it still counts
                unreadable++;
            }
        }

        void line(String text) {
            JqValue line;
            Instant at;
            try {
                line = JqValues.parseStrict(text);
                at = Instant.parse(line.pathText("t", ""));
            } catch (RuntimeException torn) {
                unreadable++;
                return;
            }
            if (since == null || at.isBefore(since)) since = at;
            var kind = line.pathText("kind", "");
            if (kind.equals("dropped")) {
                dropped += line.path("lines").asLong(0);
                return;
            }
            var name = line.pathText("instance", "");
            if (name.isEmpty()) return;
            if (!kind.equals("model") && !kind.equals("github")) return; // a kind of a later isx
            var incarnation = line.pathText("incarnation", "");
            var tally = (incarnation.isEmpty() ? byName : byIncarnation)
                    .computeIfAbsent(incarnation.isEmpty() ? name : incarnation, k -> new Tally());
            switch (kind) {
                case "model" -> {
                    tally.seen(at);
                    tally.calls++;
                    tally.input += line.path("input_tokens").asLong(0);
                    tally.output += line.path("output_tokens").asLong(0);
                    tally.cacheRead += line.path("cache_read_input_tokens").asLong(0);
                    tally.cacheCreation += line.path("cache_creation_input_tokens").asLong(0);
                }
                case "github" -> {
                    tally.seen(at);
                    var repo = line.pathText("repo", "");
                    switch (line.pathText("op", "")) {
                        case "push" -> {
                            for (var ref : strings(line.path("refs"))) {
                                var branch = new Branch(repo, branchName(ref));
                                tally.branches.remove(branch);
                                tally.branches.add(branch);
                                if (tally.branches.size() > MAX_KEPT) tally.branches.removeFirst();
                            }
                            for (var ref : strings(line.path("deleted_refs"))) {
                                tally.branches.remove(new Branch(repo, branchName(ref)));
                            }
                        }
                        case "pull_request" -> {
                            tally.pulls.add(new PullRequest(repo, line.path("number").asLong(0)));
                            if (tally.pulls.size() > MAX_KEPT) tally.pulls.removeFirst();
                        }
                        default -> { }
                    }
                }
                default -> { }
            }
        }

        Log done() {
            var incarnations = new LinkedHashMap<String, Effort>();
            byIncarnation.forEach((k, t) -> incarnations.put(k, t.effort()));
            var names = new HashMap<String, Effort>();
            byName.forEach((k, t) -> names.put(k, t.effort()));
            return new Log(Map.copyOf(incarnations), Map.copyOf(names), since, dropped, unreadable, files);
        }
    }

    /** A branch's name, {@code refs/heads/} left out; a tag or another ref keeps its full name. */
    static String branchName(String ref) {
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }

    private static List<String> strings(JqValue array) {
        if (!array.isArray()) return List.of();
        return array.asList().stream().filter(JqValue::isString).map(JqValue::asText).toList();
    }

    private static Instant earlier(Instant a, Instant b) {
        return a == null ? b : b == null || a.isBefore(b) ? a : b;
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    private static Effort merge(Effort a, Effort b) {
        var branches = new LinkedHashSet<>(a.branches());
        branches.addAll(b.branches());
        var pulls = new LinkedHashSet<>(a.pullRequests());
        pulls.addAll(b.pullRequests());
        return new Effort(a.modelCalls() + b.modelCalls(), a.inputTokens() + b.inputTokens(),
                a.outputTokens() + b.outputTokens(), a.cacheReadTokens() + b.cacheReadTokens(),
                a.cacheCreationTokens() + b.cacheCreationTokens(),
                earlier(a.first(), b.first()), later(a.last(), b.last()),
                newest(branches), newest(pulls));
    }

    private static <T> List<T> newest(SequencedSet<T> all) {
        return all.stream().skip(Math.max(0, all.size() - MAX_KEPT)).toList();
    }
}
