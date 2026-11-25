package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** What {@link TranscriptGate} flags and what it keeps, from the examples of the rules (#2542). */
class TranscriptGateTest {

    @Test
    void aNegatedNounIsFlagged() {
        // The two the review on #2528 found, by an author who knew the rule
        assertTrue(TranscriptGate.negatesANoun("No machine ever holds it."));
        assertTrue(TranscriptGate.negatesANoun("A new account owns none yet."));
        assertTrue(TranscriptGate.negatesANoun("    isx filters nothing."));
        assertTrue(TranscriptGate.negatesANoun("Nobody is signed in."));
        assertTrue(TranscriptGate.negatesANoun("No such account yet? Create one first:"));
        // What writing.md keeps is kept only in its own form
        assertTrue(TranscriptGate.negatesANoun("A new account has none yet."));
        assertTrue(TranscriptGate.negatesANoun("It does no check of the token."));
        assertTrue(TranscriptGate.negatesANoun("Saved: nothing."));
    }

    @Test
    void whatWritingMdKeepsIsNotFlagged() {
        for (var kept : List.of("copilot has no credential of its own.", "Pi needs no account from isx.",
                "There is no template by that name.", "Nothing it changes reaches the template.",
                "│  Nothing was saved.", "It costs nothing.", "Enter does nothing here.",
                "The token is no longer valid.", "isx does not filter the connections.",
                "Choice (y/N):", "No, keep it."))
            assertFalse(TranscriptGate.negatesANoun(kept), kept);
    }

    @Test
    void aLabelThatIsMeantIsNotFlagged() {
        assertFalse(TranscriptGate.negatesANoun("      2. None for now"));
        assertFalse(TranscriptGate.negatesANoun("│  - GitHub         none for now                               │"));
        assertFalse(TranscriptGate.negatesANoun("      n  no agent for now"));
        // The label is cut, not the line: a negated noun beside it is still flagged
        assertTrue(TranscriptGate.negatesANoun("      3. None for now, with no account"));
        // Only where it is a label: the same words in a sentence are read
        assertTrue(TranscriptGate.negatesANoun("    You have none for now."));
    }

    @Test
    void aNegationSplitAcrossALineBreakIsReadAsOneSentence() {
        // The flows wrap a sentence by hand, one line at a time
        assertTrue(TranscriptGate.negatesANoun("    isx filters", "    nothing."));
        assertTrue(TranscriptGate.negatesANoun("    Run it from a directory with no", "    .incus-spawn."));
        assertFalse(TranscriptGate.negatesANoun("    Pi needs", "    no account from isx."));
        assertFalse(TranscriptGate.negatesANoun("    It was saved.", "    Nothing else changed."));
        assertFalse(TranscriptGate.negatesANoun("│  It costs                     │", "│  nothing.                     │"));
        // A fault of the previous line alone is that line's, reported once
        assertFalse(TranscriptGate.negatesANoun("    No machine holds it.", "    The next line."));
        var never = Pattern.compile("\\bcontainer\\b");
        assertEquals(1, TranscriptGate.faults("f", "    isx filters\n    nothing.", never, null).size());
        // A blank line or the person's answer ends the sentence
        assertEquals(List.of(), TranscriptGate.faults("f", "    It was saved.\n\n    Nothing else changed.", never, null));
        assertEquals(List.of(), TranscriptGate.faults("f", "    Choice [Y]: ⏎\n    Nothing changed.", never, null));
        assertEquals(List.of(), TranscriptGate.faults("f", "    Choice [1]: 2⏎\n    Nothing changed.", never, null));
        // A line that ends without a full stop, such as a heading or a colon, does not make a capitalized Nothing an object
        assertFalse(TranscriptGate.negatesANoun("    GitHub Account", "    Nothing else is needed."));
        assertFalse(TranscriptGate.negatesANoun("    Saved:", "    Nothing else changed."));
    }

    @Test
    void aStepNumberShownTwiceIsFlagged() {
        var never = Pattern.compile("\\bcontainer\\b");
        var twice = "│  2  GitHub Account                                     [2/3] │";
        var once = "│  GitHub Account                                        [3/3] │";
        assertEquals(1, TranscriptGate.faults("f", twice, never, null).size());
        assertEquals(List.of(), TranscriptGate.faults("f", once, never, null));
    }

    @Test
    void theFlowsWordsHoldUntilTheLineThatIntroducesThem() {
        var never = Pattern.compile("(?i)\\binstances?\\b");
        var text = "An isx machine runs your agent.\nisx commands and docs call it an instance.\nisx list shows each instance.";
        assertEquals(List.of(), TranscriptGate.faults("f", text, never, "isx commands and docs call"));
        assertEquals(2, TranscriptGate.faults("f", text, never, null).size());
    }

    @Test
    void aLineWiderThanTheFrameIsFlaggedButNotAnAddressOrAMarker() {
        var never = Pattern.compile("\\bcontainer\\b");
        var wide = "x".repeat(InitTranscriptsTest.WIDTH + 1);
        assertEquals(1, TranscriptGate.faults("f", wide, never, null).size());
        assertEquals(List.of(), TranscriptGate.faults("f", "    https://" + wide, never, null));
        assertEquals(List.of(), TranscriptGate.faults("f", "⟪" + wide + "⟫", never, null));
        // A typed answer is the person's, not the program's line
        assertEquals(List.of(), TranscriptGate.faults("f", "x".repeat(InitTranscriptsTest.WIDTH - 2) + "••••", never, null));
    }
}
