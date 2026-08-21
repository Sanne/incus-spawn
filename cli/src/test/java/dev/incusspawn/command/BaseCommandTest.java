package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaseCommandTest {

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
