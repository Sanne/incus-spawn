package dev.incusspawn.tui;

import dev.incusspawn.Warnings;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * What bare {@code isx} says before the TUI takes the terminal (#1154). The TUI draws over
 * whatever is on screen, so a line printed while isx gets ready (the macOS VM starting,
 * {@code isx init} running, a stale-appliance notice) would be lost under it, read only after
 * quitting.
 * <p>
 * From {@link #begin()} to {@link #handOver}, warnings raised through {@link Warnings} are held
 * and handed to the TUI's warning log, which announces them on the status line. Everything else
 * still reaches the terminal as it is written, and is remembered: if anything was printed, the
 * hand-over waits for the user before the TUI opens. Closing without a hand-over (the TUI never
 * opened) prints the held warnings, so none is dropped.
 */
public final class PreTuiOutput implements AutoCloseable {

    private final PrintStream out;
    private final PrintStream err;
    private final PrintStream notingOut;
    private final PrintStream notingErr;
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AtomicBoolean printed = new AtomicBoolean();
    private final Warnings.Redirect redirect;
    /** Where the held warnings go on close: stderr, unless the TUI took them. */
    private Consumer<String> warningSink;
    private boolean closed;

    private PreTuiOutput() {
        out = System.out;
        err = System.err;
        warningSink = w -> err.println("Warning: " + w);
        var flag = printed;
        notingOut = BuildOutput.onWrite(out, () -> flag.set(true));
        notingErr = BuildOutput.onWrite(err, () -> flag.set(true));
        System.setOut(notingOut);
        System.setErr(notingErr);
        redirect = Warnings.redirect(new Warnings.Channel(warnings::add));
    }

    /** Start holding warnings and noting output, until {@link #handOver} or {@link #close}. */
    public static PreTuiOutput begin() {
        return new PreTuiOutput();
    }

    /**
     * The TUI is about to take the terminal: give it the held warnings and, if anything was
     * printed, let the user read it first ({@code waitForUser}, normally {@link #waitForEnter}).
     */
    public void handOver(Consumer<String> warningSink, Runnable waitForUser) {
        if (closed) return;
        this.warningSink = warningSink;
        close();
        if (printed.get()) waitForUser.run();
    }

    /** Wait for Enter, when there is a terminal to wait on. */
    public static void waitForEnter() {
        if (!IncusClient.hasTerminal()) return;
        System.console().readLine("Press Enter to continue to isx... ");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        notingOut.flush();
        notingErr.flush();
        // Only our own: whatever replaced them meanwhile restores its own.
        if (System.out == notingOut) System.setOut(out);
        if (System.err == notingErr) System.setErr(err);
        redirect.close();
        warnings.forEach(warningSink);
    }
}
