package dev.incusspawn.tui;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Every warning raised while the TUI owns the terminal, from any thread.
 * <p>
 * The status line holds one message and is cleared by the next key, so it cannot carry several
 * warnings from several operations. They collect here instead: the status line announces what
 * arrived since it last looked ({@link #takeUnannounced()}), the header shows how many are
 * unread, and the warnings modal lists them all ({@link #entries()}).
 * <p>
 * A warning already in the log is not added twice: it moves to the end with the new time, so
 * one that keeps recurring reads as recent rather than filling the list.
 */
public final class WarningLog {

    /** Oldest entries are dropped beyond this; the log is for reading, not an archive. */
    static final int CAPACITY = 100;

    public record Entry(String message, LocalTime time) {}

    /** An entry plus whether the modal has shown it and the status line has announced it. */
    private static final class Slot {
        final Entry entry;
        boolean read;
        boolean announced;

        Slot(Entry entry) { this.entry = entry; }
    }

    private final List<Slot> slots = new ArrayList<>();
    private final Supplier<LocalTime> clock;

    public WarningLog() {
        this(LocalTime::now);
    }

    /** For tests, which need fixed times to compare rendered output. */
    public WarningLog(Supplier<LocalTime> clock) {
        this.clock = clock;
    }

    /**
     * Add a warning. Surrounding whitespace and a leading "Warning:" (messages written for the
     * CLI carry one) are dropped; inner lines are kept for the modal.
     */
    public synchronized void add(String message) {
        if (message == null || message.isBlank()) return;
        var text = dedent(message).strip().replaceFirst("^Warning:\\s*", "");
        if (text.isEmpty()) return;
        slots.removeIf(s -> s.entry.message().equals(text));
        slots.add(new Slot(new Entry(text, clock.get())));
        if (slots.size() > CAPACITY) {
            slots.remove(0);
        }
    }

    /**
     * Remove the first line's indentation from every line: CLI warnings are indented to sit under
     * a build step, and their continuation lines are indented relative to that.
     */
    private static String dedent(String message) {
        var lines = message.stripTrailing().split("\\R");
        var first = lines[0];
        int indent = first.length() - first.stripLeading().length();
        var out = new StringBuilder(first.stripLeading());
        for (int i = 1; i < lines.length; i++) {
            var line = lines[i];
            int strip = 0;
            while (strip < indent && strip < line.length() && line.charAt(strip) == ' ') strip++;
            out.append('\n').append(line.substring(strip));
        }
        return out.toString();
    }

    /** All warnings, oldest first. */
    public synchronized List<Entry> entries() {
        return slots.stream().map(s -> s.entry).toList();
    }

    /** How many warnings arrived since the modal last showed them. */
    public synchronized int unread() {
        return (int) slots.stream().filter(s -> !s.read).count();
    }

    /** The warnings the modal has not shown yet, oldest first. */
    public synchronized List<Entry> unreadEntries() {
        return slots.stream().filter(s -> !s.read).map(s -> s.entry).toList();
    }

    public synchronized void markRead() {
        slots.forEach(s -> s.read = true);
    }

    /** Whether a warning is waiting for the status line, so the next tick must repaint. */
    public synchronized boolean hasUnannounced() {
        return slots.stream().anyMatch(s -> !s.announced);
    }

    /** The warnings that arrived since the last call, oldest first, for the status line. */
    public synchronized List<Entry> takeUnannounced() {
        var fresh = slots.stream().filter(s -> !s.announced).map(s -> s.entry).toList();
        slots.forEach(s -> s.announced = true);
        return fresh;
    }

    public synchronized void clear() {
        slots.clear();
    }

    /**
     * The status line for newly arrived warnings: the latest on one line, led by how many arrived
     * when there are several. The count comes first because a long warning is cut off at the
     * terminal's width. Null when there are none.
     */
    public static String statusLine(List<Entry> fresh) {
        if (fresh.isEmpty()) return null;
        var latest = fresh.get(fresh.size() - 1).message().replaceAll("\\s*\\R\\s*", " ");
        return fresh.size() == 1 ? "⚠ " + latest
                : "⚠ " + fresh.size() + " new warnings, latest: " + latest;
    }
}
