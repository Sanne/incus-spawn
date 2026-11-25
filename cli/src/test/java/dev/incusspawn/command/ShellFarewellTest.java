package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What isx says when a shell in an instance ends (#2528). */
class ShellFarewellTest {

    /** Leaving says what isx did, then the two commands that follow, each on a line that fits 80 columns for a name of ordinary length. */
    @Test
    void leavingSaysTheInstanceIsLeftRunningAndHowToReturnOrStopIt() {
        assertEquals(List.of("Left my-first, which isx leaves running.",
                "Return: isx shell my-first. Stop it: isx stop my-first."), ShellFarewell.left("my-first"));
        // With the note's indent, within 80 columns for a name of ordinary length
        for (var line : ShellFarewell.left("my-first-agent")) assertTrue(line.length() <= 76, line);
        var hostile = String.join("|", ShellFarewell.left("a\u001b[2Jb\nc"));
        assertFalse(hostile.contains("\u001b") || hostile.contains("\n"), hostile);
    }
}
