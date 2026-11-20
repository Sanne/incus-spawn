package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * The aggregate the event log keeps beside it (#2429): what {@code EventLog} folds an oldest file into
 * before it deletes that file, so the per-call detail goes and the sums stay.
 *
 * <p>The aggregate is a JSON-lines file of its own, {@code <log>.sums}. A {@code model} line is summed by
 * UTC day, instance, incarnation, task run, model and status into a {@code model_sum} line (calls, the
 * four token counts, the duration, and the first and last time folded). A {@code dropped} line is summed
 * by day into {@code dropped_sum}. Every other line, such as {@code github} pushes and pull requests or
 * the instance and task lines of a later isx, is few and says what ties an instance to its work, so it is
 * kept as written. Keys are ids, never labels: what a purpose or a task is called is read at read time.
 *
 * <p>A fold writes a temporary file and renames it over the aggregate, and only then does the caller delete
 * the folded file, so a failure loses nothing. A {@code folded} marker holds the SHA-256 of each file
 * folded, so a file that survived a crash between the rename and its deletion is not summed twice.
 * Rows older than {@link #RETAIN_DAYS} are dropped, as the aggregate otherwise grows by days times instances.
 */
final class EventLogFold {

    /** How long a day's sums are kept: a little over a year. */
    static final int RETAIN_DAYS = 400;

    private static final int MARKERS = 16;

    private EventLogFold() {}

    /** The aggregate that goes with the log whose current file is {@code log}. */
    static Path sumsOf(Path log) {
        return log.resolveSibling(log.getFileName() + ".sums");
    }

    private static final class Sum {
        long calls, input, output, cacheRead, cacheCreation, ms;
        Instant first, last;

        void add(long calls, long input, long output, long cacheRead, long cacheCreation, long ms,
                 Instant first, Instant last) {
            this.calls += calls;
            this.input += input;
            this.output += output;
            this.cacheRead += cacheRead;
            this.cacheCreation += cacheCreation;
            this.ms += ms;
            if (this.first == null || first.isBefore(this.first)) this.first = first;
            if (this.last == null || last.isAfter(this.last)) this.last = last;
        }
    }

    /**
     * Folds {@code old} into the aggregate of {@code log}, written whole before this returns. Throws
     * when it cannot, and then the caller keeps {@code old}.
     */
    static void fold(Path log, Path old, Instant now) throws IOException {
        var bytes = Files.readAllBytes(old);
        var digest = sha256(bytes);
        var sums = sumsOf(log);

        var models = new TreeMap<String, ModelRow>();
        var dropped = new TreeMap<String, Long>();
        var kept = new ArrayList<String>();
        var markers = new ArrayList<String>();
        var cutoff = now.minus(RETAIN_DAYS, ChronoUnit.DAYS);

        if (Files.exists(sums)) {
            for (var text : lines(Files.readAllBytes(sums))) {
                JqValue line;
                try {
                    line = JqValues.parseStrict(text);
                } catch (RuntimeException torn) {
                    continue; // the aggregate is only ever renamed whole: nothing to keep of a broken line
                }
                switch (line.pathText("kind", "")) {
                    case "model_sum" -> {
                        var row = ModelRow.of(line);
                        if (row != null) {
                            var target = models.computeIfAbsent(row.key(), k -> row);
                            if (target != row) target.sum.add(row.sum.calls, row.sum.input, row.sum.output,
                                    row.sum.cacheRead, row.sum.cacheCreation, row.sum.ms, row.sum.first, row.sum.last);
                        }
                    }
                    case "dropped_sum" -> dropped.merge(line.pathText("day", ""), line.path("lines").asLong(0), Long::sum);
                    case "folded" -> markers.add(text);
                    default -> kept.add(text);
                }
            }
            if (markers.stream().anyMatch(m -> m.contains("\"" + digest + "\""))) return; // folded already
        }

        for (var text : lines(bytes)) {
            JqValue line;
            Instant at;
            try {
                line = JqValues.parseStrict(text);
                at = Instant.parse(line.pathText("t", ""));
            } catch (RuntimeException torn) {
                continue; // a torn line says nothing
            }
            switch (line.pathText("kind", "")) {
                case "model" -> {
                    var row = new ModelRow(at.atZone(ZoneOffset.UTC).toLocalDate().toString(),
                            line.pathText("instance", ""), line.pathText("incarnation", ""),
                            line.pathText("task", ""), line.pathText("model", ""),
                            line.path("status").asLong(0));
                    models.computeIfAbsent(row.key(), k -> row).sum.add(1,
                            line.path("input_tokens").asLong(0), line.path("output_tokens").asLong(0),
                            line.path("cache_read_input_tokens").asLong(0),
                            line.path("cache_creation_input_tokens").asLong(0), line.path("ms").asLong(0), at, at);
                }
                case "dropped" -> dropped.merge(at.atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        line.path("lines").asLong(0), Long::sum);
                default -> kept.add(text);
            }
        }
        markers.add(JqObject.builder(3).put("t", now.toString()).put("kind", "folded").put("sha256", digest)
                .build().toJsonString());

        var out = new StringBuilder();
        var cutoffDay = cutoff.atZone(ZoneOffset.UTC).toLocalDate().toString();
        for (var text : kept) {
            if (!olderThan(text, cutoff)) out.append(text).append('\n');
        }
        for (var row : models.values()) {
            if (row.day.compareTo(cutoffDay) >= 0) out.append(row.json()).append('\n');
        }
        dropped.forEach((day, n) -> {
            if (day.compareTo(cutoffDay) >= 0) {
                out.append(JqObject.builder(4).put("t", day + "T00:00:00Z").put("kind", "dropped_sum")
                        .put("day", day).put("lines", n).build().toJsonString()).append('\n');
            }
        });
        for (var m : markers.subList(Math.max(0, markers.size() - MARKERS), markers.size())) out.append(m).append('\n');

        var tmp = sums.resolveSibling(sums.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var ch = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE)) {
                ch.write(java.nio.ByteBuffer.wrap(out.toString().getBytes(StandardCharsets.UTF_8)));
                ch.force(true); // on disk before the rename and the deletion that follows
            }
            Files.move(tmp, sums, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static boolean olderThan(String text, Instant cutoff) {
        try {
            return Instant.parse(JqValues.parseStrict(text).pathText("t", "")).isBefore(cutoff);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> lines(byte[] bytes) throws IOException {
        // Bytes that are not UTF-8, as a write a full disk cut short leaves, spoil their line alone
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        var all = new ArrayList<String>();
        try (var in = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), decoder))) {
            String line;
            while ((line = in.readLine()) != null) if (!line.isBlank()) all.add(line);
        }
        return all;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record ModelRow(String day, String instance, String incarnation, String task, String model,
                            long status, Sum sum) {
        ModelRow(String day, String instance, String incarnation, String task, String model, long status) {
            this(day, instance, incarnation, task, model, status, new Sum());
        }

        String key() {
            return String.join("\u0000", day, instance, incarnation, task, model, Long.toString(status));
        }

        static ModelRow of(JqValue line) {
            try {
                var row = new ModelRow(line.pathText("day", ""), line.pathText("instance", ""),
                        line.pathText("incarnation", ""), line.pathText("task", ""), line.pathText("model", ""),
                        line.path("status").asLong(0));
                row.sum.add(line.path("calls").asLong(0), line.path("input_tokens").asLong(0),
                        line.path("output_tokens").asLong(0), line.path("cache_read_input_tokens").asLong(0),
                        line.path("cache_creation_input_tokens").asLong(0), line.path("ms").asLong(0),
                        Instant.parse(line.pathText("first", "")), Instant.parse(line.pathText("last", "")));
                return row;
            } catch (RuntimeException e) {
                return null;
            }
        }

        String json() {
            var b = JqObject.builder(14).put("t", sum.first.toString()).put("kind", "model_sum").put("day", day)
                    .put("instance", instance).put("incarnation", incarnation);
            if (!task.isEmpty()) b.put("task", task);
            return b.put("model", model).put("status", status).put("calls", sum.calls)
                    .put("input_tokens", sum.input).put("output_tokens", sum.output)
                    .put("cache_read_input_tokens", sum.cacheRead).put("cache_creation_input_tokens", sum.cacheCreation)
                    .put("ms", sum.ms).put("first", sum.first.toString()).put("last", sum.last.toString())
                    .build().toJsonString();
        }
    }
}
