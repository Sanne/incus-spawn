package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import io.hyperfoil.tools.jjq.value.JqObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The proxy's event log (#2241): one JSON line per notable request, appended to a file on the host
 * so that what an instance did outlives a proxy restart and the in-memory counts of {@link ApiActivity}.
 *
 * <p>A line holds the time, the instance's name and incarnation ({@code created_at}, #1063), the
 * domain and, for a model call, the model, the status and the tokens. It holds no request or
 * response content. The log is a record of observations: nothing reads it back to decide routing
 * (#935).
 *
 * <p>Writing costs the request path one queue offer. A single platform thread drains the queue and
 * does the file work; when the queue is full a line is dropped and counted, and the next line
 * written says how many were. Retention is by size alone: the file rolls over at
 * {@link #MAX_FILE_BYTES} and keeps {@link #KEPT} older files, so the log never holds more than
 * about {@code (KEPT + 1) * MAX_FILE_BYTES} of lines. The oldest file is first folded, on the writer
 * thread, into daily sums beside the log ({@link EventLogFold}, #2429): lines queued meanwhile wait.
 */
final class EventLog implements AutoCloseable {

    static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    static final int KEPT = EventLogReader.KEPT;
    static final int QUEUE = 4096;

    /** What a model call from an instance did. */
    record ModelCall(Instant at, String instance, String incarnation, String domain, String model,
                     int status, long durationMillis, long input, long output, long cacheRead, long cacheCreation) {}

    /** A push GitHub accepted: the branches its commands name (#2241). */
    record Push(Instant at, String instance, String incarnation, String repo, int status, long durationMillis,
                java.util.List<String> refs, java.util.List<String> deleted) {}

    /** A pull request GitHub answered a create for. */
    record PullRequest(Instant at, String instance, String incarnation, String repo, long number, int status,
                       long durationMillis) {}

    /** An incarnation first seen, or whose purpose changed: what it is, for a report that outlives it (#2427). */
    record Instance(Instant at, String instance, String incarnation, String template, String purpose, String session) {}

    private final Path file;
    private final long maxFileBytes;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE);
    /** Lines offered and not yet written or dropped. */
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean closed;
    /** The file's size, or -1 until read (again): only the writer thread. */
    private long size = -1;
    private boolean torn;

    EventLog(Path file) {
        this(file, MAX_FILE_BYTES);
    }

    EventLog(Path file, long maxFileBytes) {
        this.file = file;
        this.maxFileBytes = maxFileBytes;
        this.writer = new Thread(this::drain, "isx-event-log");
        writer.setDaemon(true);
        writer.start();
    }

    /** The log at its place on the host. */
    static EventLog atDefaultPlace() {
        return new EventLog(Environment.proxyEventLogFile());
    }

    /** Queues a model call; never blocks, and never throws. */
    void modelCall(ModelCall c) {
        var line = JqObject.builder(12)
                .put("t", c.at().toString())
                .put("kind", "model")
                .put("instance", c.instance())
                .put("incarnation", c.incarnation() == null ? "" : c.incarnation())
                .put("domain", c.domain() == null ? "" : c.domain())
                .put("model", c.model() == null ? "" : c.model())
                .put("status", c.status())
                .put("ms", c.durationMillis())
                .put("input_tokens", c.input())
                .put("output_tokens", c.output())
                .put("cache_read_input_tokens", c.cacheRead())
                .put("cache_creation_input_tokens", c.cacheCreation())
                .build().toJsonString();
        offer(line);
    }

    /** Whether the line was queued; a full queue drops it and counts it. */
    private boolean offer(String line) {
        pending.incrementAndGet();
        if (closed || !queue.offer(line)) {
            pending.decrementAndGet();
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    /** Queues an instance line; never blocks, and never throws. A later line for the same incarnation replaces it. Whether it was queued. */
    boolean instance(Instance i) {
        return offer(JqObject.builder(8)
                .put("t", i.at().toString())
                .put("kind", "instance")
                .put("instance", i.instance())
                .put("incarnation", i.incarnation() == null ? "" : i.incarnation())
                .put("template", i.template())
                .put("purpose", i.purpose())
                .put("session", i.session())
                .build().toJsonString());
    }

    /** Queues a push; never blocks, and never throws. */
    void push(Push p) {
        var refs = io.hyperfoil.tools.jjq.value.JqArray.arrayBuilder();
        for (var r : p.refs()) refs.add(r);
        var deleted = io.hyperfoil.tools.jjq.value.JqArray.arrayBuilder();
        for (var r : p.deleted()) deleted.add(r);
        offer(JqObject.builder(9)
                .put("t", p.at().toString())
                .put("kind", "github")
                .put("op", "push")
                .put("instance", p.instance())
                .put("incarnation", p.incarnation() == null ? "" : p.incarnation())
                .put("repo", p.repo())
                .put("status", p.status())
                .put("ms", p.durationMillis())
                .put("refs", refs.build())
                .put("deleted_refs", deleted.build())
                .build().toJsonString());
    }

    /** Queues a pull request opened; never blocks, and never throws. */
    void pullRequest(PullRequest p) {
        offer(JqObject.builder(9)
                .put("t", p.at().toString())
                .put("kind", "github")
                .put("op", "pull_request")
                .put("instance", p.instance())
                .put("incarnation", p.incarnation() == null ? "" : p.incarnation())
                .put("repo", p.repo())
                .put("number", p.number())
                .put("status", p.status())
                .put("ms", p.durationMillis())
                .build().toJsonString());
    }

    /** Waits until everything queued so far is written; for tests and a clean stop. */
    void flush() throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (pending.get() > 0 && System.nanoTime() < deadline) Thread.sleep(2);
    }

    @Override
    public void close() {
        closed = true;
        writer.interrupt();
    }

    private void drain() {
        while (!closed) {
            try {
                var line = queue.take();
                var lost = dropped.getAndSet(0);
                if (lost > 0) {
                    // Not written: the count goes back, to be reported with the next line
                    if (!append(JqObject.builder(3).put("t", Instant.now().toString()).put("kind", "dropped")
                            .put("lines", lost).build().toJsonString())) {
                        dropped.addAndGet(lost);
                    }
                }
                if (!append(line)) dropped.incrementAndGet();
                pending.decrementAndGet();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Whether the line was written; a failure leaves the log to try again with the next line. */
    private boolean append(String line) {
        try {
            // After a failed write the file may end in a partial line: this one starts on its own
            var bytes = ((torn ? "\n" : "") + line + "\n").getBytes(StandardCharsets.UTF_8);
            if (size < 0) size = Files.exists(file) ? Files.size(file) : 0;
            // Removed while the proxy runs: made again, rather than losing every later line
            if (!Files.isDirectory(file.getParent())) Files.createDirectories(file.getParent(),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (size > 0 && size + bytes.length > maxFileBytes) {
                try {
                    roll();
                } catch (IOException e) {
                    // The file just grows until a rollover works: not retried on every line
                }
            }
            if (!Files.exists(file)) {
                try {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (java.nio.file.FileAlreadyExistsException raced) {
                    // created meanwhile: appended to below
                }
                size = 0;
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            size += bytes.length;
            torn = false;
            return true;
        } catch (IOException | RuntimeException e) {
            // A full disk or a removed directory loses lines, never the request that made them
            torn = true;
            size = -1;
            return false;
        }
    }

    private void roll() throws IOException {
        // A rollover that fails is not retried on every line: the file just grows until the next one
        size = 0;
        // The oldest file is folded into the aggregate before it goes (#2429); one that cannot be folded
        // stays, and the file just grows until the next rollover
        if (Files.exists(older(KEPT))) {
            EventLogFold.fold(file, older(KEPT), Instant.now());
            Files.delete(older(KEPT));
        }
        for (int i = KEPT - 1; i >= 1; i--) {
            if (Files.exists(older(i))) Files.move(older(i), older(i + 1), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(file, older(1), StandardCopyOption.REPLACE_EXISTING);
    }

    private Path older(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }
}
