package dev.incusspawn.util;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The recording binding (#1703): an {@link Output} that keeps what code reported as typed
 * {@link Event}s, in order, so a test asserts on what was said and of which kind, never on bytes
 * a swapped {@code System.out} caught. Bind it ({@link BuildOutput#bind}) for code that reports
 * through {@link BuildOutput} or {@code Warnings}, or hand it to code that takes an {@link Output}.
 *
 * <p>It overrides every method of {@link Output} ({@code OutputBindingTest}), so nothing falls
 * through to a default that would drop it unrecorded.
 */
public final class RecordingOutput implements Output {

    /** Reports nowhere: for a test that does not look at what was said. */
    public static final Output QUIET = (msg, details) -> {};

    /** Fails the test on any warning, error or banner: for code that must have nothing to warn of. */
    public static final Output FAIL_ON_WARN = (msg, details) -> fail("unexpected warning: " + msg);

    /** What kind of message an event is: one per method of {@link Output} that reports. */
    public enum Kind {
        WARN, ERROR, WARN_ASIDE, STEP_WARN, WARN_BANNER, STEP_FAIL, NOTE, FAILURE_DETAIL,
        HEADER, BUILD_HEADER, BRANCH_HEADER, SECTION, GROUP, GROUP_END,
        STEP, STEP_NOTE, OK, SUCCESS, LIST, RESULT, FRAME, GAP, PROMPT, WINDOW_TITLE,
        STEP_START, STEP_PROGRESS, STEP_DONE, STEP_BREAK, RELEASE_TERMINAL, ABANDON_STEP
    }

    /** The kinds {@link Output} treats as diagnostics: those that default to {@link Output#warn}. */
    private static final Set<Kind> DIAGNOSTICS =
            EnumSet.of(Kind.WARN, Kind.ERROR, Kind.WARN_ASIDE, Kind.STEP_WARN, Kind.WARN_BANNER, Kind.STEP_FAIL);
    /** Whether a terminal prints {@code kind} on stderr: every diagnostic, and notes. */
    public static boolean onStderr(Kind kind) {
        return DIAGNOSTICS.contains(kind) || kind == Kind.NOTE;
    }

    /** The kinds a terminal skips when blank ({@link TerminalOutput}). */
    private static final Set<Kind> SKIPPED_WHEN_BLANK =
            EnumSet.of(Kind.WARN, Kind.ERROR, Kind.WARN_ASIDE, Kind.STEP_WARN, Kind.STEP_NOTE, Kind.NOTE);
    /** The kinds whose own {@code Warning:} marker a terminal drops for its ⚠ ({@link BuildOutput#warningText}). */
    private static final Set<Kind> MARKER_DROPPED = EnumSet.of(Kind.WARN, Kind.WARN_ASIDE);
    /** The kinds {@link TerminalOutput} prints through {@link BuildOutput#safeLines}. */
    private static final Set<Kind> SAFE_LINES =
            EnumSet.of(Kind.WARN, Kind.ERROR, Kind.WARN_ASIDE, Kind.STEP_WARN, Kind.STEP_FAIL, Kind.STEP_NOTE);

    /** One message: its kind, its text (null for a kind that has none) and the lines under it. */
    public record Event(Kind kind, String msg, List<String> details) {
        public Event(Kind kind, String msg, String... details) {
            // A null detail reads "null", as a terminal prints it
            this(kind, msg, Arrays.stream(details).map(String::valueOf).toList());
        }

        /**
         * The message and its details as one text: a diagnostic's advice on lines under it, as a log
         * keeps a warning, and everything else on one line, as a terminal draws a header.
         */
        public String text() {
            // A list's message already reads its items
            if (details.isEmpty() || kind == Kind.LIST || kind == Kind.FRAME) return msg;
            if (DIAGNOSTICS.contains(kind)) return msg + "\n" + String.join("\n", details);
            if (kind == Kind.BUILD_HEADER) return msg + " [" + details.get(0) + "/" + details.get(1) + "]";
            return msg + " " + String.join(" ", details);
        }
    }

    private final List<Event> events = new ArrayList<>();
    /** Each line written to {@link #results()}, blank ones included, as a {@link Kind#RESULT}. */
    private final PrintStream results =
            new PrintStream(new LineOutputStream(line -> record(Kind.RESULT, line), true), true, StandardCharsets.UTF_8);
    /** Where {@link #into} also adds each line; null for a recorder read through its methods. */
    private final List<String> mirror;

    public RecordingOutput() {
        this(null);
    }

    private RecordingOutput(List<String> mirror) {
        this.mirror = mirror;
    }

    /**
     * A recorder that also adds each line ({@link #lines()}) to {@code lines} as it comes: for a
     * test that collects into a list it already has.
     */
    public static RecordingOutput into(List<String> lines) {
        return new RecordingOutput(lines);
    }

    /** Work a test runs, which may throw. */
    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    /**
     * What {@code work} reported through {@link BuildOutput} and {@code Warnings}, with a new
     * recorder bound around it: the test's stand-in for capturing the streams. A checked exception
     * from {@code work} fails the test, so a caller inside another lambda can use this as it is.
     */
    public static RecordingOutput during(Work work) {
        var recorder = new RecordingOutput();
        try (var bound = BuildOutput.bind(recorder)) {
            work.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return recorder;
    }

    private synchronized void record(Kind kind, String msg, String... details) {
        // What a terminal skips is not recorded either (a blank warning or note); a blank failed step
        // still ends the live step, and a banner with no title still prints its lines
        // A note is one line, and every diagnostic's lines are made safe, as a terminal prints them
        if (kind == Kind.NOTE && msg != null) msg = OutputFormat.oneLine(msg);
        if (SKIPPED_WHEN_BLANK.contains(kind) && (msg == null || msg.isBlank())) return;
        if (MARKER_DROPPED.contains(kind)) msg = BuildOutput.warningText(msg);
        // What a terminal makes safe, and only that: a banner it prints as given
        if (SAFE_LINES.contains(kind)) {
            msg = BuildOutput.safeLines(msg);
            details = Arrays.stream(details).map(BuildOutput::safeLines).toArray(String[]::new);
        }
        var event = new Event(kind, msg, details);
        events.add(event);
        if (mirror != null && isLine(event)) mirror.add(event.text());
    }

    @Override public void warn(String msg, String... details) { record(Kind.WARN, msg, details); }
    @Override public void error(String msg, String... details) { record(Kind.ERROR, msg, details); }
    @Override public void warnAside(String msg) { record(Kind.WARN_ASIDE, msg); }
    @Override public void stepWarn(String msg) { record(Kind.STEP_WARN, msg); }
    @Override public void warnBanner(String title, String... lines) { record(Kind.WARN_BANNER, title, lines); }
    @Override public void stepFail(String msg) { record(Kind.STEP_FAIL, msg); }
    @Override public void note(String msg) { record(Kind.NOTE, msg); }
    @Override public void failureDetail(String msg) { record(Kind.FAILURE_DETAIL, msg); }
    @Override public void header(String msg, String detail) { record(Kind.HEADER, msg, optional(detail)); }
    @Override public void buildHeader(String name, int index, int total) {
        // A terminal shows the name as one line of text (TerminalOutput.buildHeader)
        record(Kind.BUILD_HEADER, OutputFormat.oneLine(name), String.valueOf(index), String.valueOf(total));
    }
    @Override public void branchHeader(String name, String source) { record(Kind.BRANCH_HEADER, name, source); }
    @Override public void section(String msg) { record(Kind.SECTION, msg); }
    @Override public Group group(String title, String detail) {
        record(Kind.GROUP, title, optional(detail));
        return () -> record(Kind.GROUP_END, null);
    }
    @Override public String indent() { return ""; }
    @Override public void step(String msg) { record(Kind.STEP, msg); }
    @Override public void stepNote(String msg) { record(Kind.STEP_NOTE, msg); }
    @Override public void ok(String msg) { record(Kind.OK, msg); }
    @Override public void success(String msg) { record(Kind.SUCCESS, msg); }
    /** Its items as the details, each its own; the message is them as a line reads them. */
    @Override public void list(Collection<String> items) {
        record(Kind.LIST, String.join(", ", items), items.toArray(String[]::new));
    }
    @Override public PrintStream results() { return results; }
    /** Its lines as the details; the message is them as one text. */
    @Override public void frame(String... lines) { record(Kind.FRAME, String.join("\n", lines), lines); }
    @Override public void gap() { record(Kind.GAP, ""); }
    @Override public void prompt(String question) { record(Kind.PROMPT, question); }
    @Override public void windowTitle(String title) { record(Kind.WINDOW_TITLE, title); }
    @Override public void stepStart(String msg) { record(Kind.STEP_START, msg); }
    /** The detail as it reads when it is given: a test sets the value before the call. */
    @Override public void stepProgress(java.util.function.Supplier<String> detail) { record(Kind.STEP_PROGRESS, detail.get()); }
    @Override public void stepDone(String detail) { record(Kind.STEP_DONE, detail); }
    @Override public void stepBreak() { record(Kind.STEP_BREAK, null); }
    @Override public void releaseTerminal() { record(Kind.RELEASE_TERMINAL, null); }
    @Override public void abandonStep() { record(Kind.ABANDON_STEP, null); }
    @Override public List<Output> destinations() { return List.of(this); }

    /** Everything reported, in order. */
    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    /** The events of {@code kind}. */
    public synchronized List<Event> events(Kind kind) {
        return events.stream().filter(e -> e.kind() == kind).toList();
    }

    /**
     * Every message a person reads, in order, each with its details: what a {@code Consumer<String>}
     * sink saw. Not a live step's progress or end, which only redraw a line already counted, nor a window title.
     */
    public synchronized List<String> lines() {
        return events.stream().filter(RecordingOutput::isLine).map(Event::text).toList();
    }

    /** The lines of every {@link Output#frame} drawn, in order. */
    public synchronized List<String> frameLines() {
        return events(Kind.FRAME).stream().flatMap(e -> e.details().stream()).toList();
    }

    /** What was written to {@link #results()}, a line each, blank ones included. */
    public synchronized List<String> resultLines() {
        return events(Kind.RESULT).stream().map(Event::msg).toList();
    }

    /** The diagnostics (the kinds that default to {@link Output#warn}), each with its details under it. */
    public synchronized List<String> warnings() {
        return events.stream().filter(e -> DIAGNOSTICS.contains(e.kind())).map(Event::text).toList();
    }

    private static String[] optional(String detail) {
        return detail == null ? new String[0] : new String[] {detail};
    }

    private static boolean isLine(Event event) {
        return event.msg() != null && switch (event.kind()) {
            case STEP_PROGRESS, STEP_DONE, GROUP_END, WINDOW_TITLE -> false;
            default -> true;
        };
    }
}
