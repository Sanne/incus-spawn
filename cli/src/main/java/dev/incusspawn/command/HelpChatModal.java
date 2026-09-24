package dev.incusspawn.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import dev.incusspawn.ai.AiHelpClient.AiResponse;
import dev.incusspawn.ai.AiHelpClient.Target;
import dev.incusspawn.ai.AiHelpClient.Usage;
import dev.incusspawn.tui.ShiftTabBindings;
import dev.incusspawn.tui.TuiTheme;
import dev.incusspawn.util.TerminalLink;
import dev.incusspawn.util.TerminalProgress;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Margin;
import dev.tamboui.layout.Padding;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.checkbox.CheckboxState;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.select.SelectState;

/**
 * The "AI Help" dialog: state, key handling and rendering. Independent of Incus and of
 * {@link ListCommand}, so it can be rendered headlessly in tests.
 */
final class HelpChatModal {

    /** Sends a question to {@code target} and returns the answer. */
    @FunctionalInterface
    interface Asker {
        AiResponse ask(Target target, String question, boolean attachDefinitions) throws Exception;
    }

    private enum Field { ACCOUNT, QUESTION, ATTACH }

    /** Rows the question box shows before it starts scrolling; grows with the text in between. */
    private static final int MIN_INPUT_ROWS = 3;
    private static final int MAX_INPUT_ROWS = 12;

    private final ModalRenderer modal;
    private final TuiTheme theme;
    private final List<Target> targets;
    private final List<String> subscriptionAccounts;
    private final Asker asker;
    private final Executor executor;
    private final Runnable onAnswer;

    private final SelectState accountSelect;
    private TextAreaState input = new TextAreaState();
    private final CheckboxState attachCheck = new CheckboxState(false);
    private Field field = Field.QUESTION;
    /** Size of the definitions the checkbox attaches, or -1 while it is still being measured. */
    private volatile long attachmentBytes = -1;
    private volatile boolean loading;
    /**
     * Identifies the request in flight. Cancelling or asking again bumps it, and a worker
     * publishes its result only while its own id is still current -- otherwise a cancelled
     * request finishing late would show a stale answer, or end the newer request's spinner.
     * Guarded by {@code this}, together with {@link #thread}.
     */
    private long requestId;
    private Thread thread;
    private volatile List<String> responseLines;
    private volatile Usage usage = Usage.NONE;
    private volatile String error;
    private int scrollOffset;

    /**
     * @param targets accounts that can answer, at least one; {@code initial} is preselected when present
     * @param subscriptionAccounts Claude Pro/Max accounts, listed as not usable here
     * @param executor runs the {@link Asker}; tests pass {@code Runnable::run} to answer synchronously
     * @param onAnswer called from the executor once the answer (or an error) has arrived
     */
    HelpChatModal(ModalRenderer modal, TuiTheme theme, List<Target> targets, Target initial,
                  List<String> subscriptionAccounts, Asker asker, Executor executor, Runnable onAnswer) {
        this.modal = modal;
        this.theme = theme;
        this.targets = List.copyOf(targets);
        this.subscriptionAccounts = List.copyOf(subscriptionAccounts);
        this.asker = asker;
        this.executor = executor;
        this.onAnswer = onAnswer;
        this.accountSelect = new SelectState(targets.stream().map(Target::label).toList(),
                initial == null ? 0 : Math.max(0, this.targets.indexOf(initial)));
    }

    boolean isLoading() {
        return loading;
    }

    /** Whether an answer or an error is showing, rather than the question form. */
    private boolean hasResult() {
        return responseLines != null || error != null;
    }

    /** The account currently selected, so the next opening can preselect it. */
    Target target() {
        return targets.get(accountSelect.selectedIndex());
    }

    void setAttachmentBytes(long bytes) {
        attachmentBytes = bytes;
    }

    /** Handles a key; returns false when the dialog should close. */
    boolean handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            if (loading) {
                cancel();
                return true;
            }
            return false;
        }

        // Response mode: scrollable response or error
        if (hasResult()) {
            if (key.isKey(KeyCode.DOWN) || key.isChar('j')) { scrollOffset++; return true; }
            if (key.isKey(KeyCode.UP) || key.isChar('k')) {
                if (scrollOffset > 0) scrollOffset--;
                return true;
            }
            if (key.isKey(KeyCode.HOME) || key.isChar('g')) { scrollOffset = 0; return true; }
            if (key.isKey(KeyCode.END) || key.isChar('G')) {
                scrollOffset = Integer.MAX_VALUE;
                return true;
            }
            if (key.isCharIgnoreCase('n') && !key.hasCtrl()) {
                input = new TextAreaState();
                responseLines = null;
                usage = Usage.NONE;
                error = null;
                scrollOffset = 0;
                field = Field.QUESTION;
                return true;
            }
            return !(key.isCharIgnoreCase('q') && !key.hasCtrl());
        }

        if (loading) return true;

        // Input mode. Enter adds a line break in the question box, so asking has its own key
        // (terminals can't report Ctrl+Enter); from the other fields Enter asks too.
        if (isAskKey(key) || (field != Field.QUESTION && key.isKey(KeyCode.ENTER))) {
            ask();
            return true;
        }
        if (ShiftTabBindings.isShiftTab(key)) {
            moveFocus(-1);
            return true;
        }
        if (key.isKey(KeyCode.TAB)) {
            moveFocus(1);
            return true;
        }
        switch (field) {
            case ACCOUNT -> {
                if (key.isKey(KeyCode.LEFT)) accountSelect.selectPrevious();
                if (key.isKey(KeyCode.RIGHT) || key.isChar(' ')) accountSelect.selectNext();
            }
            case ATTACH -> {
                if (key.isChar(' ')) attachCheck.toggle();
            }
            case QUESTION -> handleQuestionKey(key);
        }
        return true;
    }

    private void handleQuestionKey(KeyEvent key) {
        if (key.isKey(KeyCode.ENTER))          input.insert('\n');
        else if (key.isKey(KeyCode.BACKSPACE)) input.deleteBackward();
        else if (key.isKey(KeyCode.DELETE))    input.deleteForward();
        else if (key.isKey(KeyCode.LEFT))      input.moveCursorLeft();
        else if (key.isKey(KeyCode.RIGHT))     input.moveCursorRight();
        else if (key.isKey(KeyCode.UP))        input.moveCursorUp(inputWidth(), Overflow.WRAP_WORD);
        else if (key.isKey(KeyCode.DOWN))      input.moveCursorDown(inputWidth(), Overflow.WRAP_WORD);
        else if (key.isKey(KeyCode.HOME))      input.moveCursorToLineStart();
        else if (key.isKey(KeyCode.END))       input.moveCursorToLineEnd();
        else if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) input.insert(key.character());
    }

    private void ask() {
        var question = input.text().strip();
        if (question.isEmpty()) return;
        var target = target();
        var attach = attachCheck.isChecked();
        long id;
        synchronized (this) {
            id = ++requestId;
            loading = true;
        }
        executor.execute(() -> {
            synchronized (this) {
                if (requestId != id) return; // cancelled before it started
                thread = Thread.currentThread();
            }
            AiResponse response = null;
            String failure = null;
            try {
                response = asker.ask(target, question, attach);
            } catch (Exception e) {
                failure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            synchronized (this) {
                if (requestId != id) return; // cancelled while running: drop the result
                thread = null;
                if (response != null) {
                    usage = response.usage();
                    responseLines = response.content().lines().toList();
                } else {
                    error = failure;
                }
                loading = false;
            }
            onAnswer.run();
        });
    }

    private synchronized void cancel() {
        requestId++;
        if (thread != null) thread.interrupt();
        thread = null;
        loading = false;
        error = "Cancelled.";
    }

    /** Cycles focus in on-screen order; the account is only a stop when there is a choice. */
    private void moveFocus(int step) {
        var order = hasAccountChoice()
                ? List.of(Field.ACCOUNT, Field.QUESTION, Field.ATTACH)
                : List.of(Field.QUESTION, Field.ATTACH);
        int i = order.indexOf(field);
        field = order.get(Math.floorMod(i + step, order.size()));
    }

    private boolean hasAccountChoice() {
        return targets.size() > 1;
    }

    private static boolean isAskKey(KeyEvent key) {
        return key.hasCtrl() && key.isCharIgnoreCase('s');
    }

    private int inputWidth() {
        return Math.max(1, input.lastRenderedWidth());
    }

    void render(Frame frame, Rect screen) {
        boolean showResponse = hasResult();
        // Grow with the terminal, but cap the width to keep answers readable.
        int width = Math.min(screen.width() - 4, Math.clamp(screen.width() * 2 / 3, 80, 110));
        int maxHeight = screen.height() - 2;
        // Border (2) + horizontal padding (2) around the content.
        int contentWidth = Math.max(1, width - 4);

        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" AI Help ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(Padding.horizontal(1))
                .build();

        if (showResponse) {
            // Leave a column for the scrollbar.
            var answer = answerLines(Math.max(1, contentWidth - 1));
            var usageNote = usageNote(usage);
            // Fit the answer (+ border, spacer + usage, and hint rows), but grow no taller than 3/4 of the screen.
            int height = Math.min(maxHeight, Math.clamp(answer.size() + 3 + (usageNote != null ? 2 : 0),
                    8, Math.max(24, screen.height() * 3 / 4)));
            var inner = renderFrame(frame, screen, block, width, height);
            renderResponse(frame, inner, answer, usageNote);
        } else if (loading) {
            var inner = renderFrame(frame, screen, block, width, 5);
            renderLoading(frame, inner);
        } else {
            var layout = new InputLayout(contentWidth, screen);
            var inner = renderFrame(frame, screen, block, width, Math.min(maxHeight, layout.height()));
            renderInput(frame, inner, layout);
        }
    }

    private Rect renderFrame(Frame frame, Rect screen, Block block, int width, int height) {
        var modalArea = ModalRenderer.centerRect(screen, width, height);
        modal.renderBlock(frame, block, modalArea);
        return block.inner(modalArea);
    }

    private void renderResponse(Frame frame, Rect inner, List<Line> contentLines, String usageNote) {
        var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(usageNote != null ? 2 : 0), Constraint.length(1))
                .split(inner);

        scrollOffset = modal.renderScrollableContent(frame, rows.get(0), contentLines, scrollOffset);
        if (usageNote != null) {
            // After a spacer row, indented to line up with the key hints below.
            frame.renderWidget(Paragraph.from(Text.from(Line.from(""), Line.styled(" " + usageNote,
                    Style.EMPTY.fg(theme.textDim()).bg(modal.bg())))), rows.get(1));
        }

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "n", "New question");
        modal.addKey(hintSpans, "q/Esc", "Close");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(2));
    }

    /**
     * What the answer cost in tokens, and whether the prompt cache was used -- the only visible
     * sign that caching works, since a broken cache fails silently as a higher bill. Null when
     * the provider reported nothing.
     */
    static String usageNote(Usage usage) {
        if (usage.totalInputTokens() == 0) return null;
        var cache = new ArrayList<String>();
        if (usage.cacheReadTokens() > 0) cache.add(tokens(usage.cacheReadTokens()) + " from cache");
        if (usage.cacheWriteTokens() > 0) {
            cache.add(tokens(usage.cacheWriteTokens()) + " cached for follow-ups within 5 min");
        }
        return tokens(usage.totalInputTokens()) + " tokens in"
                + (cache.isEmpty() ? "" : " (" + String.join(", ", cache) + ")")
                + " · " + tokens(usage.outputTokens()) + " out";
    }

    private static String tokens(long count) {
        return count < 1000 ? Long.toString(count) : String.format(java.util.Locale.ROOT, "%.1fk", count / 1000.0);
    }

    private void renderLoading(Frame frame, Rect inner) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill(), Constraint.length(1))
                .split(inner);
        var spinnerFrames = TerminalProgress.SPINNER;
        var spin = spinnerFrames[(int) ((System.currentTimeMillis() / 100) % spinnerFrames.length)];
        frame.renderWidget(Paragraph.from(Line.styled(
                spin + " Asking " + target().label() + "…",
                Style.EMPTY.fg(modal.accent()).bg(modal.bg()))), rows.get(0));
        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "Esc", "Cancel");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(2));
    }

    /** The wrapped text blocks of the input form, measured once so height and layout agree. */
    private final class InputLayout {
        final List<String> intro;
        final List<String> accountNote;
        final List<String> attachNote;
        final int inputRows;

        InputLayout(int contentWidth, Rect screen) {
            int indented = Math.max(1, contentWidth - modal.toggleLabelColumn());
            intro = ModalRenderer.wrapText(introText(), contentWidth);
            accountNote = subscriptionAccounts.isEmpty() ? List.of()
                    : ModalRenderer.wrapText(subscriptionNote(), indented);
            attachNote = ModalRenderer.wrapText("Sent to " + target().provider().serviceName()
                    + " with your question, for better answers about your own setup.", indented);
            inputRows = inputRows(contentWidth - ModalRenderer.FIELD_PREFIX, screen);
        }

        int height() {
            // Border (2); spacers after the intro, account, question box and attach note (4);
            // account, "Question:", checkbox and hint rows (4).
            return 2 + 4 + 4 + intro.size() + accountNote.size() + inputRows + attachNote.size();
        }
    }

    private void renderInput(Frame frame, Rect inner, InputLayout layout) {
        var rows = Layout.vertical()
                .constraints(
                        Constraint.length(layout.intro.size()),
                        Constraint.length(1),                          // spacer
                        Constraint.length(1),                          // account
                        Constraint.length(layout.accountNote.size()),  // unusable accounts
                        Constraint.length(1),                          // spacer
                        Constraint.length(1),                          // "Question:"
                        Constraint.length(layout.inputRows),           // question box
                        Constraint.length(1),                          // spacer
                        Constraint.length(1),                          // attach checkbox
                        Constraint.length(layout.attachNote.size()),   // where it is sent
                        Constraint.length(1),                          // spacer
                        Constraint.fill())                             // hints
                .split(inner);

        var dimStyle = Style.EMPTY.fg(theme.textDim()).bg(modal.bg());
        renderLines(frame, rows.get(0), layout.intro, "", dimStyle);

        if (hasAccountChoice()) {
            modal.renderSelect(frame, rows.get(2), "Account:", accountSelect, field == Field.ACCOUNT);
        } else {
            modal.renderValue(frame, rows.get(2), "Account:", target().label());
        }
        var noteIndent = " ".repeat(modal.toggleLabelColumn());
        renderLines(frame, rows.get(3), layout.accountNote, noteIndent, dimStyle);

        modal.renderLabel(frame, rows.get(5), "Question:", field == Field.QUESTION);

        var inputStyle = field == Field.QUESTION
                ? Style.EMPTY.fg(theme.focusedLabel()).bg(theme.inputBg())
                : Style.EMPTY.fg(modal.fg()).bg(theme.inputInactiveBg());
        TextArea.builder()
                .placeholder("ask anything about incus-spawn…")
                .style(inputStyle)
                .overflow(Overflow.WRAP_WORD)
                .build()
                .renderWithCursor(rows.get(6).inner(new Margin(0, 0, 0, ModalRenderer.FIELD_PREFIX)),
                        frame.buffer(), input, frame);

        modal.renderToggle(frame, rows.get(8), "Attach my template and tool definitions" + attachmentSize(),
                attachCheck, field == Field.ATTACH);
        renderLines(frame, rows.get(9), layout.attachNote, noteIndent, dimStyle);

        var hintSpans = new ArrayList<Span>();
        switch (field) {
            case QUESTION -> {
                modal.addKey(hintSpans, "Ctrl+S", "Ask");
                modal.addKey(hintSpans, "Enter", "New line");
            }
            case ATTACH -> {
                modal.addKey(hintSpans, "Ctrl+S/Enter", "Ask");
                modal.addKey(hintSpans, "Space", "Toggle");
            }
            case ACCOUNT -> {
                modal.addKey(hintSpans, "Ctrl+S/Enter", "Ask");
                modal.addKey(hintSpans, "←/→", "Change");
            }
        }
        modal.addKey(hintSpans, "Tab", "Next field");
        modal.addKey(hintSpans, "Esc", "Close");
        frame.renderWidget(Paragraph.from(Line.from(hintSpans)), rows.get(11));
    }

    private static void renderLines(Frame frame, Rect area, List<String> lines, String indent, Style style) {
        if (lines.isEmpty()) return;
        frame.renderWidget(Paragraph.from(Text.from(lines.stream()
                .map(l -> Line.styled(indent + l, style)).toList())), area);
    }

    private String introText() {
        return "Your question is processed by an LLM using the account "
                + (hasAccountChoice() ? "selected" : "shown") + " below; its usage is billed to that account.";
    }

    private String subscriptionNote() {
        var names = String.join(", ", subscriptionAccounts.stream().map(n -> "\"" + n + "\"").toList());
        return "Not available: " + names + (subscriptionAccounts.size() > 1 ? " are" : " is")
                + " a Claude Pro/Max subscription, whose token only works in Claude Code itself.";
    }

    private String attachmentSize() {
        long bytes = attachmentBytes;
        if (bytes < 0) return "";
        return " (~" + CleanCommand.formatSize(bytes) + ")";
    }

    /** Rows for the question box: enough for the wrapped text, within the min/max bounds. */
    private int inputRows(int width, Rect screen) {
        int needed = input.computeDisplayRows(width, Overflow.WRAP_WORD).size();
        int max = Math.max(MIN_INPUT_ROWS, Math.min(MAX_INPUT_ROWS, screen.height() / 3));
        return Math.clamp(needed, MIN_INPUT_ROWS, max);
    }

    private List<Line> answerLines(int wrapWidth) {
        var lines = new ArrayList<Line>();
        if (error != null) {
            var style = Style.EMPTY.fg(modal.warn()).bg(modal.bg());
            for (var l : ModalRenderer.wrapText("Error: " + error, wrapWidth)) lines.add(Line.styled(l, style));
            return lines;
        }
        for (var line : responseLines) {
            var segments = TerminalLink.parseSegments(line);
            if (TerminalLink.displayLength(segments) <= wrapWidth) {
                lines.add(lineFromSegments(segments));
            } else {
                for (var wrappedSegs : TerminalLink.wrapSegments(segments, wrapWidth)) {
                    lines.add(lineFromSegments(wrappedSegs));
                }
            }
        }
        return lines;
    }

    private Line lineFromSegments(List<TerminalLink.Segment> segments) {
        if (segments.size() == 1 && segments.getFirst() instanceof TerminalLink.Segment.Text t) {
            return Line.styled(t.text(), Style.EMPTY.fg(modal.fg()).bg(modal.bg()));
        }
        var spans = new ArrayList<Span>();
        for (var seg : segments) {
            switch (seg) {
                case TerminalLink.Segment.Text t ->
                    spans.add(Span.styled(t.text(), Style.EMPTY.fg(modal.fg()).bg(modal.bg())));
                case TerminalLink.Segment.Link l ->
                    spans.add(Span.styled(l.label(),
                            Style.EMPTY.fg(modal.accent()).bg(modal.bg()).hyperlink(l.url())));
            }
        }
        return Line.from(spans);
    }
}
