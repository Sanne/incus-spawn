package dev.incusspawn.tui;

import dev.incusspawn.command.CleanCommand;
import dev.incusspawn.Platform;
import dev.incusspawn.incus.IncusClient;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.checkbox.CheckboxState;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;

import static dev.incusspawn.tui.Tui.STORAGE_WARN_PERCENT;
import static dev.incusspawn.tui.UsageFormat.gibShort;

/**
 * The TUI's pool-cleanup dialog ({@code c}): what a scan of the pool found, a box per category
 * to clean, and then what the clean did. It owns its fields, their keys and its rendering; the
 * {@link Tui} scans the pool to open it, and runs the clean when it hands Enter back.
 */
final class CleanModal {

    /** What a key did: nothing the TUI must act on, close the dialog, or clean what is checked. */
    enum Outcome { HANDLED, CLOSE, CLEAN }

    private final ModalRenderer modal;
    private final TuiTheme theme;

    private final CleanCommand.CleanScan cleanScan;

    private final CheckboxState cleanBuildsCheck;

    private final CheckboxState cleanImagesCheck;

    private final CheckboxState cleanBaseImagesCheck;

    private final CheckboxState cleanDnfCheck;

    private int cleanFieldIndex;

    private CleanCommand.CleanResult cleanResult;

    CleanModal(ModalRenderer modal, TuiTheme theme, CleanCommand.CleanScan scan) {
        this.modal = modal;
        this.theme = theme;
        cleanScan = scan;
        cleanBuildsCheck = new CheckboxState(!cleanScan.failedBuilds().isEmpty());
        cleanImagesCheck = new CheckboxState(!cleanScan.unusedImages().isEmpty());
        cleanBaseImagesCheck = new CheckboxState(false);
        cleanDnfCheck = new CheckboxState(false);
        cleanFieldIndex = cleanConfirmFirstActionableIndex();
    }

    /** Cleans what is checked, and keeps the result for {@link #renderCleanResultModal}; null when there is no CoW pool. */
    CleanCommand.CleanResult clean(IncusClient incus) {
        cleanResult = CleanCommand.cleanPool(incus, cleanBuildsCheck.isChecked(), cleanImagesCheck.isChecked(),
                cleanBaseImagesCheck.isChecked(), cleanDnfCheck.isChecked());
        return cleanResult;
    }

    /** A key in the confirm dialog; Enter with something checked hands the clean back to the TUI. */
    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            return Outcome.CLOSE;
        }
        int actionableCount = cleanConfirmActionableCount();
        if (actionableCount == 0) {
            if (key.isKey(KeyCode.ENTER)) {
                return Outcome.CLOSE;
            }
            return Outcome.HANDLED;
        }
        if (key.code() == KeyCode.CHAR && key.character() == ' ') {
            var option = cleanConfirmOption(cleanFieldIndex);
            if (option != null) option.run();
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j') || key.isKey(KeyCode.TAB)) {
            cleanFieldIndex = cleanConfirmNextActionable(cleanFieldIndex, 1);
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k') || ShiftTabBindings.isShiftTab(key)) {
            cleanFieldIndex = cleanConfirmNextActionable(cleanFieldIndex, -1);
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.ENTER)) {
            if (!cleanBuildsCheck.isChecked() && !cleanImagesCheck.isChecked()
                    && !cleanBaseImagesCheck.isChecked() && !cleanDnfCheck.isChecked()) {
                return Outcome.CLOSE;
            }
            return Outcome.CLEAN;
        }
        return Outcome.HANDLED;
    }

    /** Rows of the pool-cleanup modal: failed builds, unused images, cached base images, DNF cache. */
    private static final int CLEAN_CATEGORIES = 4;

    private boolean cleanConfirmIsActionable(int index) {
        return switch (index) {
            case 0 -> !cleanScan.failedBuilds().isEmpty();
            case 1 -> !cleanScan.unusedImages().isEmpty();
            case 2 -> !cleanScan.baseImages().isEmpty();
            case 3 -> cleanScan.dnfCacheExists();
            default -> false;
        };
    }

    private int cleanConfirmActionableCount() {
        int count = 0;
        for (int i = 0; i < CLEAN_CATEGORIES; i++) if (cleanConfirmIsActionable(i)) count++;
        return count;
    }

    private int cleanConfirmFirstActionableIndex() {
        for (int i = 0; i < CLEAN_CATEGORIES; i++) if (cleanConfirmIsActionable(i)) return i;
        return -1;
    }

    private int cleanConfirmNextActionable(int current, int direction) {
        for (int step = 1; step <= CLEAN_CATEGORIES; step++) {
            int next = (current + direction * step % CLEAN_CATEGORIES + CLEAN_CATEGORIES) % CLEAN_CATEGORIES;
            if (cleanConfirmIsActionable(next)) return next;
        }
        return current;
    }

    private Runnable cleanConfirmOption(int index) {
        if (!cleanConfirmIsActionable(index)) return null;
        return switch (index) {
            case 0 -> () -> cleanBuildsCheck.toggle();
            case 1 -> () -> cleanImagesCheck.toggle();
            case 2 -> () -> cleanBaseImagesCheck.toggle();
            case 3 -> () -> cleanDnfCheck.toggle();
            default -> null;
        };
    }

    void renderCleanConfirmModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var usage = cleanScan.usage();
        boolean hasUsage = usage != null && usage.totalBytes() > 0;
        boolean nothingActionable = cleanConfirmActionableCount() == 0;
        boolean showResizeHint = Platform.isMacOS() && usage != null
                && usage.percent() >= STORAGE_WARN_PERCENT;

        var constraints = new ArrayList<Constraint>();
        constraints.add(Constraint.length(1)); // top spacing
        if (hasUsage) {
            constraints.add(Constraint.length(1));
            constraints.add(Constraint.length(1));
        }
        for (int i = 0; i < CLEAN_CATEGORIES; i++) constraints.add(Constraint.length(1));
        if (nothingActionable) {
            constraints.add(Constraint.length(1));
            constraints.add(Constraint.length(1));
        }
        if (showResizeHint) {
            constraints.add(Constraint.length(1));
            constraints.add(Constraint.length(1));
        }
        constraints.add(Constraint.fill());

        int modalHeight = constraints.size() + 3;
        var modalArea = ModalRenderer.centerRect(screen, 54, modalHeight);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" Pool cleanup ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = Layout.vertical().constraints(constraints).split(inner);
        int row = 1;

        if (hasUsage) {
            frame.renderWidget(Paragraph.from(Line.styled(
                    gibShort(usage.usedBytes()) + " used / "
                            + gibShort(usage.totalBytes()) + " (" + usage.percent() + "%)",
                    Style.EMPTY.fg(modal.fg()).bg(modal.bg()))), rows.get(row++));
            row++;
        }

        if (!cleanScan.failedBuilds().isEmpty()) {
            var n = cleanScan.failedBuilds().size();
            modal.renderToggle(frame, rows.get(row++), "Failed builds (" + n + ")",
                    cleanBuildsCheck, cleanFieldIndex == 0);
        } else {
            modal.renderDisabledLine(frame, rows.get(row++), "Failed builds", "none");
        }
        if (!cleanScan.unusedImages().isEmpty()) {
            var n = cleanScan.unusedImages().size();
            var size = CleanCommand.formatSize(cleanScan.unusedImagesBytes());
            modal.renderToggle(frame, rows.get(row++), "Unused images (" + n + ", ~" + size + ")",
                    cleanImagesCheck, cleanFieldIndex == 1);
        } else {
            modal.renderDisabledLine(frame, rows.get(row++), "Unused images", "all match a template");
        }
        if (!cleanScan.baseImages().isEmpty()) {
            var n = cleanScan.baseImages().size();
            var size = CleanCommand.formatSize(cleanScan.baseImagesBytes());
            modal.renderToggle(frame, rows.get(row++), "Cached base images (" + n + ", ~" + size + ")",
                    cleanBaseImagesCheck, cleanFieldIndex == 2);
        } else {
            modal.renderDisabledLine(frame, rows.get(row++), "Cached base images", "none downloaded");
        }
        if (cleanScan.dnfCacheExists()) {
            modal.renderToggle(frame, rows.get(row++), "DNF build cache",
                    cleanDnfCheck, cleanFieldIndex == 3);
        } else {
            modal.renderDisabledLine(frame, rows.get(row++), "DNF build cache", "no cache volume");
        }

        if (nothingActionable) {
            row++;
            frame.renderWidget(Paragraph.from(Line.styled("  Pool is clean.",
                    Style.EMPTY.fg(theme.statusSuccess()).bg(modal.bg()))), rows.get(row++));
        }
        if (showResizeHint) {
            row++;
            frame.renderWidget(Paragraph.from(Line.styled(
                    "  Tip: isx vm resize can grow the storage pool",
                    Style.EMPTY.fg(theme.textDim()).bg(modal.bg()))), rows.get(row++));
        }

        var hintSpans = new ArrayList<Span>();
        if (nothingActionable) {
            modal.addKey(hintSpans, "Esc", "Close");
        } else {
            modal.addKey(hintSpans, "Space", "Toggle");
            modal.addKey(hintSpans, "Enter", "Clean");
            modal.addKey(hintSpans, "Esc", "Cancel");
        }
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(row));
    }

    private Line cleanCheckLine(String text) {
        return Line.from(List.of(
                Span.styled("✓ ", Style.EMPTY.fg(theme.statusSuccess()).bg(modal.bg())),
                Span.styled(text, Style.EMPTY.fg(modal.fg()).bg(modal.bg()))));
    }

    void renderCleanResultModal(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var lines = new ArrayList<Line>();
        var before = cleanResult.beforeUsage();

        if (before != null && before.totalBytes() > 0) {
            lines.add(Line.styled(gibShort(before.usedBytes()) + " used / "
                    + gibShort(before.totalBytes()) + " (" + before.percent() + "%)",
                    Style.EMPTY.fg(modal.fg()).bg(modal.bg())));
        }

        if (cleanResult.found()) {
            lines.add(Line.styled("", Style.EMPTY));
            if (cleanResult.failedBuildsDeleted() > 0) {
                lines.add(cleanCheckLine("Deleted " + cleanResult.failedBuildsDeleted()
                        + " failed build" + (cleanResult.failedBuildsDeleted() > 1 ? "s" : "")));
            }
            if (cleanResult.unusedImagesDeleted() > 0) {
                lines.add(cleanCheckLine("Deleted " + cleanResult.unusedImagesDeleted()
                        + " unused image" + (cleanResult.unusedImagesDeleted() > 1 ? "s" : "")));
            }
            if (cleanResult.baseImagesDeleted() > 0) {
                lines.add(cleanCheckLine("Deleted " + cleanResult.baseImagesDeleted()
                        + " cached base image" + (cleanResult.baseImagesDeleted() > 1 ? "s" : "")));
            }
            if (cleanResult.dnfCacheDeleted()) {
                lines.add(cleanCheckLine("Deleted DNF cache volume"));
            }

            var after = cleanResult.afterUsage();
            if (after != null && before != null) {
                long freed = before.usedBytes() - after.usedBytes();
                if (freed > 0) {
                    lines.add(Line.styled("", Style.EMPTY));
                    lines.add(Line.from(List.of(
                            Span.styled("Freed " + gibShort(freed),
                                    Style.EMPTY.bold().fg(theme.statusSuccess()).bg(modal.bg())),
                            Span.styled("  →  " + gibShort(after.usedBytes()) + " used ("
                                    + after.percent() + "%)",
                                    Style.EMPTY.fg(theme.textDim()).bg(modal.bg())))));
                }
            }
        } else {
            lines.add(Line.styled("", Style.EMPTY));
            lines.add(Line.styled("Nothing to clean — no reclaimable artifacts found.",
                    Style.EMPTY.fg(theme.textDim()).bg(modal.bg())));
        }

        for (var warn : cleanResult.warnings()) {
            lines.add(Line.styled("  ⚠ " + warn,
                    Style.EMPTY.fg(modal.warn()).bg(modal.bg())));
        }

        int width = 54;
        int modalHeight = lines.size() + 5;
        var modalArea = ModalRenderer.centerRect(screen, width, modalHeight);
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" Pool cleanup ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill(), Constraint.length(1))
                .split(inner);

        frame.renderWidget(Paragraph.from(Text.from(lines)), rows.get(1));

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "any key", "Close");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(2));
    }
}
