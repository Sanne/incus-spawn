package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Asks for a second Ctrl+C before a running build is interrupted (#1415): a terminal can send
 * Ctrl+C for Ctrl+Shift+C, its copy shortcut (design/templates.md "A running build takes two Ctrl+C").
 * The second, within {@link #WINDOW}, goes to the handler that was there before.
 */
final class BuildInterrupt implements SignalHandler {

    static final Duration WINDOW = Duration.ofSeconds(5);

    /** Restores the SIGINT handler that was there before {@link #install}. */
    interface Installed extends AutoCloseable {
        @Override
        void close();
    }

    private final BooleanSupplier building;
    private final LongSupplier nanoTime;
    private final Signal sigint = new Signal("INT");
    private volatile SignalHandler previous;
    private boolean warned;
    private long warnedAt;

    BuildInterrupt(BooleanSupplier building, LongSupplier nanoTime) {
        this.building = building;
        this.nanoTime = nanoTime;
    }

    /** Whether this Ctrl+C ends the process; false when it only asks for a second one. */
    synchronized boolean shouldInterrupt() {
        if (!building.getAsBoolean()) return true;
        long now = nanoTime.getAsLong();
        if (warned && now - warnedAt <= WINDOW.toNanos()) return true;
        warned = true;
        warnedAt = now;
        return false;
    }

    /** Whether a person at a terminal is running isx, whose Ctrl+C may be a copy. */
    static boolean atTerminal() {
        return Prompts.terminal() != null;
    }

    /**
     * Guards SIGINT while {@code building} holds, until closed. The Ctrl+C that ends the build
     * puts the previous handler back and raises the signal again, so it ends the process exactly
     * as an unguarded one would.
     */
    static Installed install(BooleanSupplier building) {
        var interrupt = new BuildInterrupt(building, System::nanoTime);
        try {
            interrupt.previous = Signal.handle(interrupt.sigint, interrupt);
        } catch (IllegalArgumentException e) {
            return () -> {}; // SIGINT cannot be handled here: Ctrl+C keeps its default
        }
        return () -> Signal.handle(interrupt.sigint, interrupt.previous);
    }

    @Override
    public void handle(Signal signal) {
        if (shouldInterrupt()) {
            var handler = previous;
            if (handler == null) Runtime.getRuntime().exit(130); // before install finished: as the JVM would
            Signal.handle(sigint, handler);
            Signal.raise(sigint);
        } else {
            BuildOutput.stepWarn("Press Ctrl+C again within " + WINDOW.toSeconds()
                    + " seconds to interrupt the build (some terminals send Ctrl+C for Ctrl+Shift+C).");
        }
    }
}
