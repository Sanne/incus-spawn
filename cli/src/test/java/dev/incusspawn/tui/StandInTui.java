package dev.incusspawn.tui;

import java.util.ArrayList;
import java.util.List;

/** The TUI with the terminal stood in for: records what it holds when it would start drawing. */
public final class StandInTui extends Tui {
    public final List<String> events = new ArrayList<>();
    public List<String> warningsAtStart;

    @Override
    void waitForUser() {
        events.add("wait");
    }

    @Override
    void runTuiLoop() {
        events.add("draw");
        warningsAtStart = warningMessages();
    }
}
