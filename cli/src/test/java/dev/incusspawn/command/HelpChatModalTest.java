package dev.incusspawn.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import dev.incusspawn.ai.AiHelpClient.AiResponse;
import dev.incusspawn.ai.AiHelpClient.Provider;
import dev.incusspawn.ai.AiHelpClient.Usage;
import dev.incusspawn.ai.AiHelpClient.Target;
import dev.incusspawn.tui.TuiSnapshot;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

class HelpChatModalTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private static final String ANSWER = """
            To branch an instance, select it in the list and press **b**. The branch is a \
            copy-on-write clone, so it is created in seconds regardless of how large the source is.

            From the command line:

                isx branch my-instance my-experiment

            See https://github.com/Sanne/incus-spawn#branching for details.""";

    private static final Target API_KEY = new Target(Provider.ANTHROPIC, "work", "claude-sonnet-5");
    private static final Target VERTEX = new Target(Provider.VERTEX, "gcp", "claude-sonnet-5");
    private static final Target OPENAI = new Target(Provider.OPENAI, "", "gpt-4o-mini");

    /** A follow-up question: the documentation read from the cache. */
    private static final Usage USAGE = new Usage(15, 51_200, 0, 312);

    private final List<String> questions = new ArrayList<>();
    private final List<Boolean> attached = new ArrayList<>();
    private final List<Target> askedTargets = new ArrayList<>();

    private HelpChatModal modal(List<Target> targets, List<String> subscriptions, HelpChatModal.Asker asker) {
        var modal = new HelpChatModal(new ModalRenderer(THEME), THEME, targets, null, subscriptions,
                asker, Runnable::run, () -> {});
        modal.setAttachmentBytes(12_537);
        return modal;
    }

    private HelpChatModal modal(HelpChatModal.Asker asker) {
        return modal(List.of(API_KEY), List.of(), asker);
    }

    private HelpChatModal answering(List<Target> targets, List<String> subscriptions, String answer) {
        return modal(targets, subscriptions, (target, q, attach) -> {
            askedTargets.add(target);
            questions.add(q);
            attached.add(attach);
            return new AiResponse(answer, USAGE);
        });
    }

    private HelpChatModal answering(String answer) {
        return answering(List.of(API_KEY), List.of(), answer);
    }

    /** Types {@code text}; a newline presses Enter, as a terminal delivers a pasted line break. */
    private static void type(HelpChatModal modal, String text) {
        text.chars().forEach(c -> modal.handleKey(c == '\n'
                ? KeyEvent.ofKey(KeyCode.ENTER) : KeyEvent.ofChar((char) c)));
    }

    private static final KeyEvent CTRL_S = KeyEvent.ofChar('s', KeyModifiers.CTRL);

    // --- Rendering --------------------------------------------------------------------------

    @Test
    void inputAtMinimumTerminalSize() {
        var modal = answering(ANSWER);
        TuiSnapshot.assertMatches("help-chat-input-80x24",
                TuiSnapshot.render(80, 24, f -> modal.render(f, f.area())));
    }

    @Test
    void inputAtCommonTerminalSize() {
        var modal = answering(ANSWER);
        type(modal, "how do I branch an instance?");
        TuiSnapshot.assertMatches("help-chat-input-120x40",
                TuiSnapshot.render(120, 40, f -> modal.render(f, f.area())));
    }

    @Test
    void inputWithCheckboxFocusedAndTicked() {
        var modal = answering(ANSWER);
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        modal.handleKey(KeyEvent.ofChar(' '));
        TuiSnapshot.assertMatches("help-chat-input-checkbox-200x60",
                TuiSnapshot.render(200, 60, f -> modal.render(f, f.area())));
    }

    private static final String LONG_QUESTION = "I have a template tpl-java that builds fine, but when I branch"
            + " it the Maven repository cache seems to be empty and every build downloads the internet again."
            + " Is the cache supposed to be shared between branches, and if so what could be preventing it?";

    @Test
    void severalAccountsOfferAChoice() {
        var modal = answering(List.of(API_KEY, VERTEX, OPENAI), List.of("personal"), ANSWER);
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        TuiSnapshot.assertMatches("help-chat-input-accounts-80x24",
                TuiSnapshot.render(80, 24, f -> modal.render(f, f.area())));
    }

    @Test
    void attachmentNoteNamesTheSelectedService() {
        var modal = answering(List.of(API_KEY, VERTEX), List.of(), ANSWER);
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        modal.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        TuiSnapshot.assertMatches("help-chat-input-vertex-120x40",
                TuiSnapshot.render(120, 40, f -> modal.render(f, f.area())));
    }

    @Test
    void longQuestionWrapsAndGrowsTheBox() {
        var modal = answering(ANSWER);
        type(modal, LONG_QUESTION);
        TuiSnapshot.assertMatches("help-chat-input-long-80x24",
                TuiSnapshot.render(80, 24, f -> modal.render(f, f.area())));
    }

    @Test
    void multiLineQuestion() {
        var modal = answering(ANSWER);
        type(modal, "Why does this fail?\n\n  Error: proxy CA not trusted\n  at MitmProxy.handshake\n");
        TuiSnapshot.assertMatches("help-chat-input-multiline-120x40",
                TuiSnapshot.render(120, 40, f -> modal.render(f, f.area())));
    }

    @Test
    void questionBoxStopsGrowingAndScrolls() {
        var modal = answering(ANSWER);
        type(modal, String.join("\n", java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "log line " + i).toList()));
        // 24 rows: the box is capped at a third of the screen and shows the end, where the cursor is.
        TuiSnapshot.assertMatches("help-chat-input-overflow-80x24",
                TuiSnapshot.render(80, 24, f -> modal.render(f, f.area())));
    }

    @Test
    void answer() {
        var modal = answering(ANSWER);
        type(modal, "how do I branch?");
        modal.handleKey(CTRL_S);
        TuiSnapshot.assertMatches("help-chat-answer-120x40",
                TuiSnapshot.render(120, 40, f -> modal.render(f, f.area())));
    }

    @Test
    void longAnswerScrolls() {
        var longAnswer = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 60)
                .mapToObj(i -> "Line " + i + " of a long answer.").toList());
        var modal = answering(longAnswer);
        type(modal, "?");
        modal.handleKey(CTRL_S);
        for (int i = 0; i < 5; i++) modal.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        TuiSnapshot.assertMatches("help-chat-answer-scrolled-80x24",
                TuiSnapshot.render(80, 24, f -> modal.render(f, f.area())));
    }

    @Test
    void error() {
        var modal = modal((target, q, t) -> { throw new IllegalStateException(
                "HTTP 401 from api.anthropic.com: authentication_error: invalid x-api-key. Check the key"
                        + " with 'isx init' or 'isx doctor'."); });
        type(modal, "hello");
        modal.handleKey(CTRL_S);
        TuiSnapshot.assertMatches("help-chat-error-80x24",
                TuiSnapshot.render(80, 24, f -> modal.render(f, f.area())));
    }

    @Test
    void disclaimerIsNeverTruncated() {
        for (int width : new int[] {40, 60, 80, 120, 200}) {
            var modal = answering(ANSWER);
            var text = TuiSnapshot.toText(TuiSnapshot.render(width, 30, f -> modal.render(f, f.area())));
            // Rejoin the wrapped lines (strip the border and padding) and look for the full sentence.
            var joined = String.join(" ", text.lines()
                    .map(l -> l.replaceAll("^[\\s║]+|[\\s║]+$", ""))
                    .toList());
            assertTrue(joined.contains("its usage is billed to that account."),
                    "disclaimer truncated at width " + width + ":\n" + text);
        }
    }

    // --- Behaviour --------------------------------------------------------------------------

    @Test
    void enterSendsQuestionWithCheckboxState() {
        var modal = answering("ok");
        type(modal, "  what is isx?  ");
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        modal.handleKey(KeyEvent.ofChar(' '));
        modal.handleKey(CTRL_S);
        assertEquals(List.of("what is isx?"), questions);
        assertEquals(List.of(true), attached);
    }

    @Test
    void theSelectedAccountIsAsked() {
        var modal = answering(List.of(API_KEY, VERTEX, OPENAI), List.of(), "ok");
        type(modal, "hi");
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));  // Question -> Attach -> Account
        modal.handleKey(KeyEvent.ofKey(KeyCode.LEFT)); // wraps to the last one
        modal.handleKey(CTRL_S);
        assertEquals(List.of(OPENAI), askedTargets);
        assertEquals(OPENAI, modal.target());
    }

    @Test
    void theInitialAccountIsPreselected() {
        var modal = new HelpChatModal(new ModalRenderer(THEME), THEME, List.of(API_KEY, VERTEX), VERTEX,
                List.of(), (t, q, a) -> new AiResponse("ok", Usage.NONE), Runnable::run, () -> {});
        assertEquals(VERTEX, modal.target());
    }

    @Test
    void aSingleAccountIsNotAFocusStop() {
        var modal = answering("ok");
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));  // Question -> Attach
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));  // Attach -> Question, skipping Account
        type(modal, "typed");
        modal.handleKey(CTRL_S);
        assertEquals(List.of("typed"), questions);
    }

    @Test
    void enterAddsALineBreakInTheQuestion() {
        var modal = answering("ok");
        type(modal, "Explain:\nline one\nline two");
        modal.handleKey(CTRL_S);
        assertEquals(List.of("Explain:\nline one\nline two"), questions);
    }

    @Test
    void enterAsksFromTheCheckbox() {
        var modal = answering("ok");
        type(modal, "what is isx?");
        modal.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        modal.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(List.of("what is isx?"), questions);
    }

    @Test
    void emptyQuestionIsNotSent() {
        var modal = answering("ok");
        type(modal, "   ");
        modal.handleKey(CTRL_S);
        assertTrue(questions.isEmpty());
    }

    @Test
    void escapeClosesFromInput() {
        assertFalse(answering("ok").handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
    }

    @Test
    void qTypesInInputButClosesAnswer() {
        var modal = answering("ok");
        assertTrue(modal.handleKey(KeyEvent.ofChar('q')));
        modal.handleKey(CTRL_S);
        assertEquals(List.of("q"), questions);
        assertFalse(modal.handleKey(KeyEvent.ofChar('q')));
    }

    @Test
    void newQuestionReturnsToInput() {
        var modal = answering("ok");
        type(modal, "first");
        modal.handleKey(CTRL_S);
        modal.handleKey(KeyEvent.ofChar('n'));
        type(modal, "second");
        modal.handleKey(CTRL_S);
        assertEquals(List.of("first", "second"), questions);
    }

    @Test
    void aRequestCancelledBeforeItStartsNeverLands() {
        var pending = new java.util.ArrayDeque<Runnable>();
        var modal = new HelpChatModal(new ModalRenderer(THEME), THEME, List.of(API_KEY), null, List.of(),
                (t, q, a) -> new AiResponse("answer to " + q, Usage.NONE), pending::add, () -> {});
        type(modal, "first");
        modal.handleKey(CTRL_S);
        modal.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));   // cancel before the worker has run
        modal.handleKey(KeyEvent.ofChar('n'));
        type(modal, "second");
        modal.handleKey(CTRL_S);
        assertTrue(modal.isLoading());

        pending.poll().run();                              // the cancelled request finishes late
        assertTrue(modal.isLoading(), "a stale request must not end the current one");
        pending.poll().run();
        assertFalse(modal.isLoading());
        assertTrue(renderedText(modal).contains("answer to second"));
        assertFalse(renderedText(modal).contains("answer to first"));
    }

    @Test
    void aRequestCancelledWhileRunningIsDropped() {
        var holder = new HelpChatModal[1];
        var modal = new HelpChatModal(new ModalRenderer(THEME), THEME, List.of(API_KEY), null, List.of(),
                (t, q, a) -> {
                    // The user cancels and starts a new question while this answer is on its way.
                    holder[0].handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));
                    Thread.interrupted(); // cancel() interrupts the running worker: the test thread here
                    holder[0].handleKey(KeyEvent.ofChar('n'));
                    type(holder[0], "draft");
                    return new AiResponse("stale answer", Usage.NONE);
                }, Runnable::run, () -> {});
        holder[0] = modal;
        type(modal, "first");
        modal.handleKey(CTRL_S);

        assertFalse(modal.isLoading());
        var text = renderedText(modal);
        assertFalse(text.contains("stale answer"), text);
        assertTrue(text.contains("draft"), "still editing the new question:\n" + text);
    }

    private static String renderedText(HelpChatModal modal) {
        return TuiSnapshot.toText(TuiSnapshot.render(120, 40, f -> modal.render(f, f.area())));
    }

    @Test
    void usageNoteSaysWhetherTheCacheWasUsed() {
        assertEquals("51.2k tokens in (51.2k from cache) · 312 out",
                HelpChatModal.usageNote(new Usage(15, 51_200, 0, 312)));
        assertEquals("51.2k tokens in (51.2k cached for follow-ups within 5 min) · 312 out",
                HelpChatModal.usageNote(new Usage(15, 0, 51_200, 312)));
        assertEquals("54.3k tokens in (51.2k from cache, 3.1k cached for follow-ups within 5 min) · 312 out",
                HelpChatModal.usageNote(new Usage(15, 51_200, 3_100, 312)));
        assertEquals("900 tokens in · 40 out", HelpChatModal.usageNote(new Usage(900, 0, 0, 40)));
        assertEquals(null, HelpChatModal.usageNote(Usage.NONE));
    }
}
