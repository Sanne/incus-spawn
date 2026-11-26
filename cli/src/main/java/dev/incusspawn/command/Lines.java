package dev.incusspawn.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Text for init's questions and answers, broken into lines that fit its frame (#2528): a line
 * printed at the step indent is never wider than the box above it, whatever a name or a path
 * in it adds.
 */
final class Lines {

    /**
     * What a line holds at the step indent: a line under a step header, its indent included, is
     * at most {@link InitCommand#BOX_WIDTH} columns (.claude/rules/interface-text.md).
     */
    static final int WIDTH = InitCommand.BOX_WIDTH - 4;

    private Lines() {}

    /** {@code text} broken at spaces into lines of at most {@link #WIDTH}; a longer word keeps a line of its own. */
    static List<String> wrap(String text) {
        return wrap(text, WIDTH);
    }

    /** {@code text} broken at spaces into lines of at most {@code width}. */
    static List<String> wrap(String text, int width) {
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        // Counted as the terminal draws it: a link's escapes and URL take no columns
        var drawn = 0;
        for (var word : text.split(" ")) {
            if (word.isEmpty()) continue;
            var wordWidth = dev.incusspawn.util.BuildOutput.stripAnsi(word).length();
            if (!line.isEmpty() && drawn + 1 + wordWidth > width) {
                lines.add(line.toString());
                line.setLength(0);
                drawn = 0;
            }
            if (!line.isEmpty()) {
                line.append(' ');
                drawn++;
            }
            line.append(word);
            drawn += wordWidth;
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /**
     * Warns at the width of the frame, so a long message or detail never overflows it: under a
     * step header, and in a build's block, whose warnings sit at the same indent.
     */
    static void warnWrapped(String first, String... more) {
        var lines = new ArrayList<>(wrap(dev.incusspawn.util.OutputFormat.oneLine(first), WIDTH - 2));
        for (var detail : more) lines.addAll(wrap(detail, WIDTH - 2));
        if (!lines.isEmpty()) {
            dev.incusspawn.util.BuildOutput.warn(lines.getFirst(), lines.subList(1, lines.size()).toArray(String[]::new));
        }
    }

    /** Each line of {@code text} as a step of the bound output. */
    static void step(String text) {
        for (var line : wrap(text)) dev.incusspawn.util.BuildOutput.step(line);
    }
}
