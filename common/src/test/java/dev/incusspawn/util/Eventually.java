package dev.incusspawn.util;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The one way a test waits for something another thread or process does.
 * It samples the condition often, first after a millisecond and then at most every {@link #MAX_PAUSE_MS} ms,
 * so a wait lasts barely longer than the condition takes and a generous timeout costs nothing when it is met.
 * A timeout is for a hung test and may be long; it never sets the pace.
 * A failure names what was awaited, and {@code state} adds what a person needs to see then (read when it happens).
 */
public final class Eventually {

    /** Longest pause between two samples. */
    public static final long MAX_PAUSE_MS = 10;

    /** The timeout when a test gives none: long enough for a loaded CI host, paid only by a hung test. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    @FunctionalInterface
    public interface Condition {
        boolean holds() throws Exception;
    }

    @FunctionalInterface
    public interface Source<T> {
        /** The value, or {@code null} while there is none yet. */
        T get() throws Exception;
    }

    private Eventually() {}

    public static void await(String what, Condition condition) throws Exception {
        await(what, DEFAULT_TIMEOUT, condition);
    }

    public static void await(String what, Duration timeout, Condition condition) throws Exception {
        await(what, timeout, condition, () -> null);
    }

    public static void await(String what, Duration timeout, Condition condition, Supplier<String> state)
            throws Exception {
        if (!holdsWithin(timeout, condition)) {
            throw new AssertionError(timedOut(what, timeout, state));
        }
    }

    /** The first non-null value {@code source} gives, or an {@link AssertionError} when none comes in time. */
    public static <T> T poll(String what, Duration timeout, Source<T> source) throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        var pause = 1L;
        while (true) {
            var value = source.get();
            if (value != null) return value;
            if (System.nanoTime() - deadline > 0) throw new AssertionError(timedOut(what, timeout, () -> null));
            Thread.sleep(pause);
            pause = Math.min(pause * 2, MAX_PAUSE_MS);
        }
    }

    public static <T> T poll(String what, Source<T> source) throws Exception {
        return poll(what, DEFAULT_TIMEOUT, source);
    }

    /**
     * Whether the condition held within the timeout, for a test that asserts on what follows anyway.
     * Prefer {@link #await}, which says what was awaited.
     */
    public static boolean holdsWithin(Duration timeout, Condition condition) throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        var pause = 1L;
        while (!condition.holds()) {
            if (System.nanoTime() - deadline > 0) return false;
            Thread.sleep(pause);
            pause = Math.min(pause * 2, MAX_PAUSE_MS);
        }
        return true;
    }

    private static String timedOut(String what, Duration timeout, Supplier<String> state) {
        var seen = state.get();
        return "Timed out after " + timeout.toMillis() + " ms waiting for " + what
                + (seen == null ? "" : " (" + seen + ")");
    }
}
