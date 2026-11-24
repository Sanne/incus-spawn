package dev.incusspawn.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * A terminal that shows outcomes and keeps the details in a log ({@link Output#detailsLogged}):
 * for the block of plumbing a first run of {@code isx init} prints (#2528). A step line, a note,
 * a step note and a list go to the log alone, while live steps, {@code ok} lines, warnings, errors
 * and questions reach the terminal as they would.
 *
 * <p>A detail is held, not dropped: when a warning, an error or a question follows before the
 * next outcome, the details held since that outcome are shown first, since they are what the
 * person reads the warning or answers the question by. An outcome ({@code ok}, a live step's end)
 * forgets them, so the screen shows one line for it, and the step notes right under it, which
 * say what it means.
 */
final class DetailsLoggedOutput extends ForwardingOutput {

    private final Output terminal;
    private final Consumer<String> log;
    /** What was logged since the last outcome, in order: each entry shows itself on the terminal. */
    private final List<Runnable> held = new ArrayList<>();
    /** Whether the last line was an outcome, whose step notes are its own and shown under it. */
    private boolean afterOutcome;

    DetailsLoggedOutput(Output terminal, Consumer<String> log) {
        this.terminal = terminal;
        this.log = log;
    }

    @Override
    Output delegate() {
        return terminal;
    }

    private void hold(String msg, Runnable show) {
        if (msg == null) return;
        afterOutcome = false;
        // A blank line is held, not logged: a list of choices a question shows is spaced by them
        if (!msg.isBlank()) BuildOutput.safeLines(msg.strip()).lines().forEach(log);
        held.add(show);
    }

    /** Shows what was held, before a message the person acts on. */
    private void showHeld() {
        var shown = List.copyOf(held);
        held.clear();
        shown.forEach(Runnable::run);
    }

    @Override public void step(String msg) { hold(msg, () -> terminal.step(msg)); }
    @Override public void note(String msg) { hold(msg, () -> terminal.note(msg)); }
    @Override public void stepNote(String msg) {
        // A note right under an outcome says what it means, as the token's "isx keeps it" does
        if (afterOutcome) terminal.stepNote(msg);
        else hold(msg, () -> terminal.stepNote(msg));
    }
    @Override public void list(Collection<String> items) {
        hold(String.join(", ", items), () -> terminal.list(items));
    }

    @Override public void ok(String msg) { held.clear(); afterOutcome = true; terminal.ok(msg); }
    @Override public void success(String msg) { held.clear(); afterOutcome = true; terminal.success(msg); }
    // A gap after held details keeps its place among them, before the question they lead to
    @Override public void gap() {
        if (held.isEmpty()) terminal.gap();
        else held.add(terminal::gap);
    }

    // A new header starts what the next warning is read by: the details before it are another's
    @Override public void header(String msg, String detail) { held.clear(); afterOutcome = false; terminal.header(msg, detail); }
    @Override public void buildHeader(String name, int index, int total) {
        held.clear(); afterOutcome = false; terminal.buildHeader(name, index, total);
    }
    @Override public void branchHeader(String name, String source) {
        held.clear(); afterOutcome = false; terminal.branchHeader(name, source);
    }
    @Override public void section(String msg) { held.clear(); afterOutcome = false; terminal.section(msg); }
    @Override public void stepDone(String detail) { held.clear(); afterOutcome = true; terminal.stepDone(detail); }

    // A live step's ✗ is followed by what its own command printed, not by the lines before it
    @Override public void stepStart(String msg) { held.clear(); afterOutcome = false; terminal.stepStart(msg); }
    @Override public void stepBreak() { afterOutcome = false; terminal.stepBreak(); }
    @Override public Group group(String title, String detail) { afterOutcome = false; return terminal.group(title, detail); }

    @Override public void warn(String msg, String... details) { showHeld(); terminal.warn(msg, details); }
    @Override public void error(String msg, String... details) { showHeld(); terminal.error(msg, details); }
    @Override public void warnAside(String msg) { showHeld(); terminal.warnAside(msg); }
    @Override public void stepWarn(String msg) { showHeld(); terminal.stepWarn(msg); }
    @Override public void warnBanner(String title, String... lines) { showHeld(); terminal.warnBanner(title, lines); }
    @Override public void stepFail(String msg) { showHeld(); terminal.stepFail(msg); }
    @Override public void prompt(String question) { showHeld(); terminal.prompt(question); }
    @Override public void frame(String... lines) { showHeld(); terminal.frame(lines); }
    // A live step that failed: its ✗, then what its command printed
    @Override public void abandonStep() { terminal.abandonStep(); showHeld(); }
}
