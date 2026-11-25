package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What isx says when a shell in an instance ends (#2528). */
class ShellFarewellTest {

    /** Leaving says what isx did and the two commands that follow, on one line, with no control character of the name's. */
    @Test
    void leavingSaysTheInstanceIsLeftRunningAndHowToReturnOrStopIt() {
        assertEquals("Left my-first, which isx leaves running. Return: isx shell my-first. Stop it: isx stop my-first.",
                ShellFarewell.left("my-first"));
        var hostile = ShellFarewell.left("a\u001b[2Jb\nc");
        assertFalse(hostile.contains("\u001b") || hostile.contains("\n"), hostile);
    }
}
