package dev.incusspawn.proxy;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A held body never takes more heap than the {@link SpoolBudget}, whatever its size, however slow
 * the disk, and gives everything back on every way its exchange ends (#1315). The disk here is a
 * stand-in whose writes complete only when a test lets them.
 */
class BodySpoolTest {

    static final int CHUNK = 8 * 1024;
    static final long LIMIT = 64 * 1024;

    static Vertx vertx;
    static Context context;

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
    }

    @AfterAll
    static void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Runs {@code task} on the spool's event loop and waits for it. */
    static <T> T on(Callable<T> task) throws Exception {
        var result = new CompletableFuture<T>();
        context.runOnContext(v -> {
            try {
                result.complete(task.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    /** A disk whose writes wait until {@link #completeWrites} (unless {@code instant}), and that counts. */
    static final class FakeStore implements SpoolStore {
        boolean instant = true;
        boolean admits = true;
        boolean room = true;
        boolean failWrites;
        final List<Runnable> stalled = new ArrayList<>();
        int open;
        int opened;
        long pending;
        long reads;
        volatile Thread closedOn;

        @Override
        public Future<SpoolFile> open() {
            opened++;
            open++;
            var disk = new ByteArrayOutputStream();
            return Future.succeededFuture(new SpoolFile() {
                boolean closed;

                @Override
                public Future<Void> write(Buffer data, long position) {
                    var promise = Promise.<Void>promise();
                    Runnable complete = () -> {
                        if (failWrites) {
                            promise.fail("disk error");
                            return;
                        }
                        synchronized (disk) {
                            assertEquals(disk.size(), position, "written front to back");
                            disk.writeBytes(data.getBytes());
                        }
                        promise.complete();
                    };
                    if (instant) complete.run();
                    else stalled.add(complete);
                    return promise.future();
                }

                @Override
                public Future<Buffer> read(long position, int length) {
                    reads++;
                    synchronized (disk) {
                        var bytes = disk.toByteArray();
                        return Future.succeededFuture(Buffer.buffer(java.util.Arrays.copyOfRange(bytes, (int) position, (int) position + length)));
                    }
                }

                @Override
                public Future<Void> close() {
                    closedOn = Thread.currentThread();
                    if (!closed) {
                        closed = true;
                        open--;
                    }
                    return Future.succeededFuture();
                }
            });
        }

        void completeWrites() {
            var now = new ArrayList<>(stalled);
            stalled.clear();
            now.forEach(Runnable::run);
        }

        @Override
        public boolean hasRoom() {
            return room;
        }

        @Override
        public boolean admit(long n) {
            if (!admits) return false;
            pending += n;
            return true;
        }

        @Override
        public void written(long n) {
            pending -= n;
        }

        @Override
        public void abandoned(long n) {
            pending -= n;
        }

        @Override
        public String refusal() {
            return "no room";
        }
    }

    /** Collects what a replay writes, and can hold it back like a socket that is not draining. */
    static class Sink implements WriteStream<Buffer> {
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        boolean full;
        Handler<Void> drain;

        @Override
        public Future<Void> write(Buffer data) {
            got.writeBytes(data.getBytes());
            return Future.succeededFuture();
        }

        @Override
        public boolean writeQueueFull() {
            return full;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            drain = handler;
            return this;
        }

        void release() {
            full = false;
            var h = drain;
            drain = null;
            if (h != null) h.handle(null);
        }

        @Override public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) { return this; }
        @Override public Future<Void> end() { return Future.succeededFuture(); }
        @Override public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) { return this; }
    }

    static Buffer chunk(int i) {
        var bytes = new byte[CHUNK];
        java.util.Arrays.fill(bytes, (byte) i);
        return Buffer.buffer(bytes);
    }

    static byte[] body(int chunks) {
        var out = new ByteArrayOutputStream();
        for (int i = 0; i < chunks; i++) out.writeBytes(chunk(i).getBytes());
        return out.toByteArray();
    }

    @Test
    void aSmallBodyStaysInMemory() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var store = new FakeStore();
        var spool = new BodySpool(budget, store, context);
        on(() -> {
            spool.write(chunk(0));
            return null;
        });
        assertFalse(spool.spilled());
        assertEquals(CHUNK, budget.held());
        on(() -> {
            spool.close();
            return null;
        });
        assertEquals(0, budget.held());
        assertEquals(0, store.opened);
    }

    /** Beyond half the budget, the rest goes to disk; the replay is the body, twice over. */
    @Test
    void aLargeBodyIsSpilledAndReplaysWhole() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var store = new FakeStore();
        var spool = new BodySpool(budget, store, context);
        var chunks = 128;
        on(() -> {
            for (int i = 0; i < chunks; i++) {
                spool.write(chunk(i));
                assertTrue(budget.held() <= LIMIT + CHUNK, "held " + budget.held());
            }
            return null;
        });
        on(() -> spool.end()).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(spool.spilled());
        assertEquals((long) chunks * CHUNK, spool.size());
        assertEquals(LIMIT / 2, budget.held(), "only the kept half stays");
        for (int send = 0; send < 2; send++) {
            var sink = new Sink();
            on(() -> spool.replay(sink)).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertArrayEquals(body(chunks), sink.got.toByteArray(), "send " + (send + 1));
        }
        on(() -> {
            spool.close();
            return null;
        });
        assertEquals(0, budget.held());
        assertEquals(0, store.open, "the file is closed");
        assertEquals(0, store.pending);
    }

    /**
     * A disk that does not keep up pauses the writer: what waits for it counts against the budget,
     * which is never exceeded by more than one chunk, and the writer resumes once writes complete.
     */
    @Test
    void stalledDiskWritesPauseTheWriterWithinTheBudget() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var store = new FakeStore();
        store.instant = false;
        var spool = new BodySpool(budget, store, context);
        var accepted = on(() -> {
            int n = 0;
            while (!spool.writeQueueFull() && n < 1000) {
                spool.write(chunk(n++));
            }
            return n;
        });
        assertTrue(accepted < 1000, "the writer was never told to wait");
        assertTrue(budget.held() <= LIMIT + CHUNK, "held " + budget.held() + " with the disk stalled");
        var resumed = new CompletableFuture<Void>();
        on(() -> {
            spool.drainHandler(() -> resumed.complete(null));
            return null;
        });
        assertFalse(resumed.isDone(), "resumed while the disk was still stalled");
        on(() -> {
            store.completeWrites();
            return null;
        });
        resumed.get(5, TimeUnit.SECONDS);
        assertEquals(LIMIT / 2, budget.held());
        on(() -> {
            spool.close();
            return null;
        });
        assertEquals(0, budget.held());
    }

    /** One spool's writes waiting on the disk are bounded on their own, with budget to spare. */
    @Test
    void aSpoolsOwnDiskQueueIsBounded() throws Exception {
        var budget = new SpoolBudget(64L * 1024 * 1024);
        var store = new FakeStore();
        store.instant = false;
        var spool = new BodySpool(budget, store, context);
        var accepted = on(() -> {
            int n = 0;
            while (!spool.writeQueueFull() && n < 10_000) spool.write(chunk(n++));
            return n;
        });
        var waiting = (long) accepted * CHUNK - budget.limit() / 2;
        assertTrue(waiting <= BodySpool.FILE_QUEUE_BYTES + CHUNK, "waiting on the disk: " + waiting);
        on(() -> {
            spool.close();
            store.completeWrites();
            return null;
        });
        assertEquals(0, budget.held());
    }

    /** A replay reads no faster than its socket takes it, and counts what it has read until it is written. */
    @Test
    void replayWaitsForTheSocket() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var store = new FakeStore();
        var spool = new BodySpool(budget, store, context);
        on(() -> {
            for (int i = 0; i < 64; i++) spool.write(chunk(i));
            return spool.end();
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var sink = new Sink();
        sink.full = true;
        var replay = on(() -> spool.replay(sink));
        Thread.sleep(200);
        assertEquals(0, store.reads, "read from disk while the socket was full");
        assertFalse(replay.isComplete());
        on(() -> {
            sink.release();
            return null;
        });
        replay.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertArrayEquals(body(64), sink.got.toByteArray());
        on(() -> {
            spool.close();
            return null;
        });
        assertEquals(0, budget.held());
    }

    /** A spool closed with its exchange sends nothing: never a credentialed head with no body behind it. */
    @Test
    void aClosedSpoolDoesNotReplay() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var spool = new BodySpool(budget, new FakeStore(), context);
        on(() -> {
            spool.write(chunk(0));
            spool.end();
            spool.close();
            return null;
        });
        var sink = new Sink();
        var replay = on(() -> spool.replay(sink));
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> replay.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS), "replayed a closed spool");
        assertEquals(0, sink.got.size());
    }

    /**
     * A replay asked for on another event loop (the token fetch it waited on completed there) still
     * reads and writes on the spool's own, where its close runs: they never interleave.
     */
    @Test
    void replayRunsOnTheSpoolsOwnEventLoop() throws Exception {
        var other = Vertx.vertx();
        try {
            var budget = new SpoolBudget(LIMIT);
            var spool = new BodySpool(budget, new FakeStore(), context);
            var spoolThread = on(() -> {
                for (int i = 0; i < 16; i++) spool.write(chunk(i));
                spool.end();
                return Thread.currentThread();
            });
            var threads = java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
            var sink = new Sink() {
                @Override
                public Future<Void> write(Buffer data) {
                    threads.add(Thread.currentThread());
                    return super.write(data);
                }
            };
            var replayed = new CompletableFuture<Void>();
            other.getOrCreateContext().runOnContext(v ->
                    spool.replay(sink).onComplete(ar -> replayed.complete(null)));
            replayed.get(5, TimeUnit.SECONDS);
            assertEquals(java.util.Set.of(spoolThread), threads);
            assertArrayEquals(body(16), sink.got.toByteArray());
            on(() -> {
                spool.close();
                return null;
            });
        } finally {
            other.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /** A replay stopped for the retry reads no more of the file. */
    @Test
    void aStoppedReplayReadsNoMore() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var store = new FakeStore();
        var spool = new BodySpool(budget, store, context);
        on(() -> {
            for (int i = 0; i < 64; i++) spool.write(chunk(i));
            return spool.end();
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var sink = new Sink();
        sink.full = true;
        var replay = on(() -> spool.replay(sink));
        Thread.sleep(200);
        on(() -> {
            spool.stopReplay();
            sink.release();
            return null;
        });
        Thread.sleep(200);
        assertTrue(replay.failed());
        assertEquals(0, store.reads, "read on after it was stopped");
        on(() -> {
            spool.close();
            return null;
        });
        assertEquals(0, budget.held());
    }

    /**
     * A socket whose queue drains just as the replay finds it full (the drain runs on the socket's own
     * event loop, so it may land between the check and anything installed after it), and whose drains
     * also come when nobody waits: the replay neither stalls nor sends any byte twice.
     */
    @Test
    void racingAndSpuriousDrainsNeitherStallNorDuplicate() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var store = new FakeStore();
        var spool = new BodySpool(budget, store, context);
        var chunks = 64;
        on(() -> {
            for (int i = 0; i < chunks; i++) spool.write(chunk(i));
            return spool.end();
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var sink = new Sink() {
            int checks;

            @Override
            public boolean writeQueueFull() {
                if (++checks % 3 != 0) return false;
                // Full when looked at, and drained at once, before the caller can react: the drain is
                // told to whatever handler is installed right now, twice over
                var now = drain;
                if (now != null) {
                    now.handle(null);
                    now.handle(null);
                }
                return true;
            }

            @Override
            public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
                drain = handler;
                return this;
            }
        };
        var replay = on(() -> spool.replay(sink));
        try {
            replay.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            fail("the replay stalled on a drain it missed; " + sink.got.size() + " bytes sent");
        }
        Thread.sleep(200);
        assertArrayEquals(body(chunks), sink.got.toByteArray(), "every byte once, in order");
        on(() -> {
            spool.close();
            return null;
        });
        assertEquals(0, budget.held());
    }

    /** A replay waiting on its socket ends with the exchange: close() stops it, and its budget comes back. */
    @Test
    void closingStopsAReplayWaitingOnItsSocket() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var spool = new BodySpool(budget, new FakeStore(), context);
        on(() -> {
            for (int i = 0; i < 64; i++) spool.write(chunk(i));
            return spool.end();
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var sink = new Sink();
        sink.full = true;
        var replay = on(() -> spool.replay(sink));
        Thread.sleep(200);
        on(() -> {
            spool.close();
            return null;
        });
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> replay.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(0, budget.held());
    }

    /** The kept part goes out under the socket's backpressure too, not all at once. */
    @Test
    void theKeptPartWaitsForTheSocketToo() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var spool = new BodySpool(budget, new FakeStore(), context);
        on(() -> {
            for (int i = 0; i < 4; i++) spool.write(chunk(i));
            return spool.end();
        }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(spool.spilled());
        var sink = new Sink();
        sink.full = true;
        var replay = on(() -> spool.replay(sink));
        Thread.sleep(200);
        assertEquals(0, sink.got.size(), "written while the socket was full");
        on(() -> {
            sink.release();
            return null;
        });
        replay.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertArrayEquals(body(4), sink.got.toByteArray());
        on(() -> {
            spool.close();
            return null;
        });
    }

    /**
     * A replay's end may come on the upstream socket's event loop (a write failing there), but the
     * spool it releases is closed on its own: the client's leaving closes it there too, and the two
     * must never both give back what it kept.
     */
    @Test
    void closeOnceSentClosesOnTheSpoolsOwnEventLoop() throws Exception {
        var other = Vertx.vertx();
        try {
            var budget = new SpoolBudget(LIMIT);
            var store = new FakeStore();
            var spool = new BodySpool(budget, store, context);
            var spoolThread = on(() -> {
                for (int i = 0; i < 64; i++) spool.write(chunk(i));
                spool.end();
                return Thread.currentThread();
            });
            var socketContext = other.getOrCreateContext();
            var sink = new Sink() {
                int writes;

                @Override
                public Future<Void> write(Buffer data) {
                    if (++writes < 7) return super.write(data);
                    // Upstream reset the connection: the write fails on the socket's own event loop
                    var failed = Promise.<Void>promise();
                    socketContext.runOnContext(v -> failed.fail("connection reset"));
                    return failed.future();
                }
            };
            on(() -> {
                spool.replay(sink);
                spool.closeOnceSent();
                return null;
            });
            for (int i = 0; i < 50 && store.closedOn == null; i++) Thread.sleep(100);
            assertSame(spoolThread, store.closedOn, "closed off the spool's own event loop");
            for (int i = 0; i < 50 && budget.held() != 0; i++) Thread.sleep(100);
            assertEquals(0, budget.held());
        } finally {
            other.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /** Closed from several threads at once, a spool gives back what it kept once, never more. */
    @Test
    void concurrentClosesGiveBackOnce() throws Exception {
        for (int round = 0; round < 200; round++) {
            var budget = new SpoolBudget(LIMIT);
            var spool = new BodySpool(budget, new FakeStore(), context);
            on(() -> {
                spool.write(chunk(0));
                return null;
            });
            var start = new java.util.concurrent.CountDownLatch(1);
            var threads = new ArrayList<Thread>();
            for (int t = 0; t < 4; t++) {
                var thread = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    spool.close();
                });
                thread.start();
                threads.add(thread);
            }
            start.countDown();
            for (var thread : threads) thread.join();
            on(() -> null);
            Thread.sleep(5);
            on(() -> null);
            assertEquals(0, budget.held(), "round " + round);
        }
    }

    enum Ending {
        /** The exchange ended normally after the body was sent. */
        AFTER_REPLAY,
        /** The client left mid-body, with disk writes still stalled; they complete afterwards. */
        MID_BODY_WITH_WRITES_PENDING,
        /** The exchange ended while a replay waited on its socket. */
        DURING_REPLAY,
        /** The disk failed a write. */
        WRITE_FAILED,
        /** The disk reserve refused the body. */
        RESERVE_REFUSED,
        /** Closed twice, as the end and the close of a response both close it. */
        CLOSED_TWICE
    }

    @ParameterizedTest
    @EnumSource(Ending.class)
    void everyEndingGivesEverythingBack(Ending ending) throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var store = new FakeStore();
        store.instant = ending != Ending.MID_BODY_WITH_WRITES_PENDING;
        store.failWrites = ending == Ending.WRITE_FAILED;
        store.admits = ending != Ending.RESERVE_REFUSED;
        var spool = new BodySpool(budget, store, context);
        var failures = new ArrayList<Throwable>();
        spool.failureHandler(failures::add);
        on(() -> {
            for (int i = 0; i < 32 && !spool.writeQueueFull(); i++) spool.write(chunk(i));
            return null;
        });
        switch (ending) {
            case AFTER_REPLAY -> {
                on(() -> spool.end()).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                on(() -> spool.replay(new Sink())).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            case DURING_REPLAY -> {
                on(() -> spool.end()).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                var sink = new Sink();
                sink.full = true;
                on(() -> spool.replay(sink));
            }
            case WRITE_FAILED -> assertEquals("disk error", failures.get(0).getMessage());
            case RESERVE_REFUSED -> assertInstanceOf(BodySpool.SpoolFull.class, failures.get(0));
            default -> {}
        }
        on(() -> {
            spool.close();
            if (ending == Ending.CLOSED_TWICE) spool.close();
            return null;
        });
        if (ending == Ending.MID_BODY_WITH_WRITES_PENDING) {
            assertTrue(budget.held() > 0, "pending writes still count until they complete");
            on(() -> {
                store.completeWrites();
                return null;
            });
        }
        assertEquals(0, budget.held(), "budget given back");
        assertEquals(0, store.open, "file handles closed");
        assertEquals(0, store.pending, "disk reservations settled");
    }

    /** Spools share one budget: together they hold no more than it, one chunk each over at most. */
    @Test
    void concurrentSpoolsShareTheBudget() throws Exception {
        var budget = new SpoolBudget(LIMIT);
        var store = new FakeStore();
        store.instant = false;
        var spools = new ArrayList<BodySpool>();
        for (int i = 0; i < 8; i++) spools.add(new BodySpool(budget, store, context));
        on(() -> {
            for (int round = 0; round < 100; round++) {
                for (var spool : spools) if (!spool.writeQueueFull()) spool.write(chunk(round));
            }
            return null;
        });
        assertTrue(budget.held() <= LIMIT + 8L * CHUNK, "held " + budget.held());
        on(() -> {
            spools.forEach(BodySpool::close);
            store.completeWrites();
            return null;
        });
        assertEquals(0, budget.held());
        assertEquals(0, store.open);
    }

    // --- The disk reserve ---

    static final class Disk implements DiskReserve.Probe {
        long usable;
        final long total;

        Disk(long usable, long total) {
            this.usable = usable;
            this.total = total;
        }

        @Override
        public long usable() {
            return usable;
        }

        @Override
        public long total() {
            return total;
        }
    }

    static final long GIB = 1024L * 1024 * 1024;
    static final long MIB = 1024L * 1024;

    @Test
    void reserveKeepsJournaldsShare() {
        assertEquals(3 * GIB, DiskReserve.keepFree(20 * GIB));
        assertEquals(4 * GIB, DiskReserve.keepFree(2048 * GIB));
        assertEquals(4 * GIB, DiskReserve.keepFree(64 * GIB));
    }

    /** Pending writes and writes completed since the last sample both count against what it said was free. */
    @Test
    void reserveCountsPendingAndUnsampledWrites() throws Exception {
        var disk = new Disk(3 * GIB + 10 * MIB, 20 * GIB);
        var tasks = new ArrayList<Runnable>();
        var reserve = new DiskReserve(disk, tasks::add, () -> 0L);
        assertTrue(reserve.admit(4 * MIB));
        assertTrue(reserve.admit(4 * MIB), "two writes pending at once");
        assertFalse(reserve.admit(4 * MIB), "a third would eat into the 3 GiB kept free");
        reserve.written(4 * MIB);
        assertFalse(reserve.admit(4 * MIB), "a completed write still counts until a sample has seen it");
        reserve.written(4 * MIB);
        // 8 MiB written since the last resample: one is asked for, off the caller's thread
        disk.usable -= 8 * MIB;
        assertFalse(reserve.admit(4 * MIB));
        assertEquals(1, tasks.size(), "a resample was started");
        tasks.remove(0).run();
        assertTrue(reserve.admit(1 * MIB), "the sample now holds the writes, and the rest is free");
        assertFalse(reserve.admit(4 * MIB));
    }

    /** The check before a body is accepted samples again too: a refusal is not stuck on an old sample. */
    @Test
    void reserveRefusalIsNotPermanent() throws Exception {
        var disk = new Disk(2 * GIB, 20 * GIB);
        var tasks = new ArrayList<Runnable>();
        var now = new long[1];
        var reserve = new DiskReserve(disk, tasks::add, () -> now[0]);
        assertFalse(reserve.hasRoom());
        disk.usable = 10 * GIB;
        now[0] = DiskReserve.RESAMPLE_MILLIS;
        assertFalse(reserve.hasRoom(), "the old sample still says so");
        assertEquals(1, tasks.size());
        tasks.remove(0).run();
        assertTrue(reserve.hasRoom(), "space given back is seen");
    }

    /** A sample that could not even be scheduled does not stop every later one. */
    @Test
    void reserveSamplesAgainAfterAFailedSample() throws Exception {
        var disk = new Disk(10 * GIB, 20 * GIB);
        var now = new long[1];
        var refuse = new boolean[] {true};
        var tasks = new ArrayList<Runnable>();
        var reserve = new DiskReserve(disk, task -> {
            if (refuse[0]) throw new java.util.concurrent.RejectedExecutionException("busy");
            tasks.add(task);
        }, () -> now[0]);
        now[0] = DiskReserve.RESAMPLE_MILLIS;
        assertTrue(reserve.hasRoom());
        refuse[0] = false;
        assertTrue(reserve.hasRoom());
        assertEquals(1, tasks.size(), "sampled again");
    }

    @Test
    void reserveResamplesOnTime() throws Exception {
        var disk = new Disk(10 * GIB, 20 * GIB);
        var tasks = new ArrayList<Runnable>();
        var now = new long[1];
        var reserve = new DiskReserve(disk, tasks::add, () -> now[0]);
        assertTrue(reserve.admit(MIB));
        assertTrue(tasks.isEmpty());
        now[0] = DiskReserve.RESAMPLE_MILLIS;
        disk.usable = 3 * GIB;
        assertTrue(reserve.admit(MIB));
        tasks.remove(0).run();
        assertFalse(reserve.hasRoom(), "the new sample shows the disk filled by something else");
    }
}
