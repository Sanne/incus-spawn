package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseCommandTest {

    @Test
    void destructiveConfirmationRefusesWithoutATerminal() {
        // Piped stdin, cron, a wrapper script: never a silent yes for something that wipes data.
        assertFalse(BaseCommand.confirmDestructive("Wipe?", false, null));
        assertTrue(BaseCommand.confirmDestructive("Wipe?", true, null), "--yes still skips the question");
    }

    @Test
    void parseConfirmationAcceptsYesAndNoCaseInsensitively() {
        assertEquals(true, BaseCommand.parseConfirmation(" y ", false));
        assertEquals(true, BaseCommand.parseConfirmation("Y", false));
        assertEquals(false, BaseCommand.parseConfirmation(" n ", true));
        assertEquals(false, BaseCommand.parseConfirmation("N", true));
    }

    @Test
    void parseConfirmationUsesDefaultForBlankResponses() {
        assertEquals(true, BaseCommand.parseConfirmation("", true));
        assertEquals(false, BaseCommand.parseConfirmation("   ", false));
        assertNull(BaseCommand.parseConfirmation(null, true));
        assertNull(BaseCommand.parseConfirmation(null, false));
    }

    @Test
    void parseConfirmationRejectsInvalidResponses() {
        assertNull(BaseCommand.parseConfirmation("maybe", true));
    }
}
