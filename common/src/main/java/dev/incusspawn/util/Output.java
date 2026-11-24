package dev.incusspawn.util;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Where isx's messages go: the one seam between the code that reports and whoever owns the
 * terminal (#1703). Code reports through {@link BuildOutput}'s static methods, which hand each
 * message to the bound output ({@link BuildOutput#bind}); the owner of the terminal binds once.
 * The CLI keeps the default, a {@link TerminalOutput}. The TUI binds {@link #diagnosticsTo} its
 * warning log while it draws and {@link #copying} it from a terminal it has released; {@code isx
 * mcp} binds a terminal on stderr alone, and the proxy its own log.
 *
 * <p>The vocabulary is what isx already prints. Progress (headers, groups, steps, live steps,
 * lists) and notes mean something only on a terminal, so an output that is not one ignores them by
 * default. Diagnostics never are: {@link #stepWarn} and {@link #warnBanner} default to
 * {@link #warn}, and {@link #stepFail} reports through {@link #stepWarn}, so an output that implements only that one method still receives
 * every warning and error.
 */
public interface Output {

    /** A warning, with any lines of advice under it. Blank messages are skipped by every output. */
    void warn(String msg, String... details);

    /**
     * Why a command failed, with any lines of advice under it: a terminal prints it as an error,
     * on stderr. Off a terminal it is a diagnostic like any warning.
     */
    default void error(String msg, String... details) {
        warn(msg, details);
    }

    /**
     * A warning raised beside a command's flow rather than in it, by code with no caller to hand
     * it a sink ({@code Warnings}): a terminal prints it on stderr alone, so a query command's
     * stdout still holds only its results.
     */
    default void warnAside(String msg) {
        warn(msg);
    }

    /** A warning about the step above. */
    default void stepWarn(String msg) {
        warn(msg);
    }

    /** A warning that must stand out: a title and its lines. */
    default void warnBanner(String title, String... lines) {
        warn(title, lines);
    }

    /** Fail the live step, then report {@code msg} as a warning about it. */
    default void stepFail(String msg) {
        stepBreak();
        stepWarn(msg);
    }

    /** An informational line beside the warnings; not a warning, so not kept off a terminal. */
    default void note(String msg) {}

    default void header(String msg, String detail) {}

    default void buildHeader(String name, int index, int total) {}

    default void branchHeader(String name, String source) {}

    default void section(String msg) {}

    /** Open a titled group of steps, indented under it until the returned group is closed. */
    default Group group(String title, String detail) {
        return () -> {};
    }

    /** The indent of a step line at the current nesting; empty off a terminal. */
    default String indent() {
        return "";
    }

    default void step(String msg) {}

    default void stepNote(String msg) {}

    default void ok(String msg) {}

    default void success(String msg) {}

    default void list(Collection<String> items) {}

    /**
     * A block the caller laid out itself, drawn as given at column 0 on stdout: {@code isx
     * init}'s banner, step boxes and closing box. Not a result, and nothing off a terminal.
     */
    default void frame(String... lines) {}

    /** A blank line, none when one just ended the block above: spaces a question from the answer before it. */
    default void gap() {}

    /** A question, at the step indent, with no line end: its answer is typed after it ({@code Prompts}). */
    default void prompt(String question) {}

    /**
     * The terminal window's title, set by an escape: only on a terminal, where {@code NO_COLOR}
     * does not stop it, since a title is no colour. It is written as one line
     * ({@link OutputFormat#oneLine}), so nothing in it can end the escape early.
     */
    default void windowTitle(String title) {}

    /**
     * Where the command's answer goes: a status report, a table, a script's records
     * ({@link OutputFormat}), each line as given. A terminal's is its stdout, which holds results
     * alone; an output that is not a terminal has none, so by default what is written is dropped.
     * Ask for it where the answer is written: a terminal's is the stream it holds at that moment,
     * guarded by a live step only when the step started first.
     */
    default PrintStream results() {
        return DISCARD;
    }

    default void stepStart(String msg) {}

    /**
     * What the live step is doing right now, read again each time its line is drawn, so a value
     * that keeps changing (a download's rate and time left) needs no call per change.
     */
    default void stepProgress(Supplier<String> detail) {}

    /** Complete the live step, with a result shown beside it when {@code detail} is not null. */
    default void stepDone(String detail) {}

    default void stepBreak() {}

    default void releaseTerminal() {}

    default void abandonStep() {}

    /**
     * The outputs that remember what {@code Warnings} reported through this one: itself, unless
     * it only passes messages on, so a warning the TUI's log already holds is not added again by a
     * build that copies its warnings there.
     */
    default List<Output> destinations() {
        return List.of(this);
    }

    /** What {@link #results()} is off a terminal. */
    PrintStream DISCARD = new PrintStream(OutputStream.nullOutputStream());

    /** A {@link #group} being printed; closing it returns to the enclosing indent. */
    @FunctionalInterface
    interface Group extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * A daemon's log: progress to {@code info}, notes to {@code notes}, diagnostics to
     * {@code warn}, one line each, safe to keep. For the proxy, whose shared code reports its
     * progress as any command does; a note (a request's debug line) is kept apart from progress,
     * which a lifecycle log keeps.
     */
    static Output logTo(Consumer<String> info, Consumer<String> notes, Consumer<String> warn) {
        return new LogOutput(info, notes, warn);
    }

    /**
     * An output that is not a terminal: each diagnostic reaches {@code sink} as one message, its
     * lines made safe ({@link OutputFormat#oneLine}) since they can quote template text, and
     * everything else is dropped. For the TUI while it draws, a pre-TUI hold, a daemon's log.
     */
    static Output diagnosticsTo(Consumer<String> sink) {
        return (msg, details) -> {
            var text = message(msg, details);
            if (text != null) sink.accept(text);
        };
    }

    /**
     * Everything to {@code terminal}, and every warning also to {@code log}: for a terminal the
     * TUI has released to a build or a branch, which it will then draw over. A failed step is not
     * copied: whoever called it reports the failure, as the TUI's status line does.
     */
    static Output copying(Output terminal, Output log) {
        return new CopyingOutput(terminal, log, true);
    }

    /**
     * Everything to {@code terminal}, but what {@code Warnings} reports to {@code log} alone: for
     * the window before the TUI opens, whose notices are held for its log, while a warning about
     * a step still prints under it.
     */
    static Output holding(Output terminal, Output log) {
        return new CopyingOutput(terminal, log, false);
    }

    /**
     * {@code terminal}'s outcomes, warnings and questions, with each step line, note and list
     * written to {@code log} instead, and shown on {@code terminal} only before a warning, an
     * error or a question that follows it: for the first run's block of setup (#2528).
     */
    static Output detailsLogged(Output terminal, Consumer<String> log) {
        return new DetailsLoggedOutput(terminal, log);
    }

    /** {@code msg} and its {@code details} as one message, each line made safe; null when {@code msg} is blank. */
    static String message(String msg, String... details) {
        var text = BuildOutput.warningText(msg);
        if (text == null) return null;
        return Stream.concat(Stream.of(text), Arrays.stream(details))
                .map(BuildOutput::safeLines)
                // A blank line spaces a terminal's banner; a log keeps the text alone
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
