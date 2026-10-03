package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EscapeSequenceParserTest {

    @Test
    void normalCharactersPassThrough() {
        var parser = new EscapeSequenceParser();
        var input = "hello".getBytes();
        var result = parser.feed(input, 0, input.length);
        assertFalse(result.f12Detected());
        assertEquals(0, result.menuKey());
        assertArrayEquals(input, result.toForward());
    }

    @Test
    void singleCharacterPassesThrough() {
        var parser = new EscapeSequenceParser();
        var result = parser.feed(new byte[]{0x61}, 0, 1); // 'a'
        assertFalse(result.f12Detected());
        assertArrayEquals(new byte[]{0x61}, result.toForward());
    }

    @Test
    void controlCharactersPassThrough() {
        var parser = new EscapeSequenceParser();
        // Ctrl+D = 0x04
        var result = parser.feed(new byte[]{0x04}, 0, 1);
        assertArrayEquals(new byte[]{0x04}, result.toForward());

        // Enter = 0x0D
        result = parser.feed(new byte[]{0x0D}, 0, 1);
        assertArrayEquals(new byte[]{0x0D}, result.toForward());

        // Newline = 0x0A
        result = parser.feed(new byte[]{0x0A}, 0, 1);
        assertArrayEquals(new byte[]{0x0A}, result.toForward());
    }

    @Test
    void f12DetectedInSingleRead() {
        var parser = new EscapeSequenceParser();
        // F12 = ESC [ 2 4 ~
        var input = new byte[]{0x1B, 0x5B, 0x32, 0x34, 0x7E};
        var result = parser.feed(input, 0, input.length);
        assertTrue(result.f12Detected());
        assertEquals(0, result.toForward().length);
    }

    @Test
    void f12DetectedAcrossReads() {
        var parser = new EscapeSequenceParser();

        // First read: ESC [
        var result = parser.feed(new byte[]{0x1B, 0x5B}, 0, 2);
        assertFalse(result.f12Detected());
        assertEquals(0, result.toForward().length); // buffering

        // Second read: 2 4 ~
        result = parser.feed(new byte[]{0x32, 0x34, 0x7E}, 0, 3);
        assertTrue(result.f12Detected());
    }

    @Test
    void nonF12CsiSequencePassesThrough() {
        var parser = new EscapeSequenceParser();
        // Up arrow = ESC [ A
        var input = new byte[]{0x1B, 0x5B, 0x41};
        var result = parser.feed(input, 0, input.length);
        assertFalse(result.f12Detected());
        assertArrayEquals(input, result.toForward());
    }

    @Test
    void textBeforeF12IsForwarded() {
        var parser = new EscapeSequenceParser();
        // "ab" then F12
        var input = new byte[]{0x61, 0x62, 0x1B, 0x5B, 0x32, 0x34, 0x7E};
        var result = parser.feed(input, 0, input.length);
        assertTrue(result.f12Detected());
        assertArrayEquals(new byte[]{0x61, 0x62}, result.toForward());
    }

    @Test
    void menuModeEscapeClosesMenu() {
        var parser = new EscapeSequenceParser();
        parser.setMenuMode(true);

        // Bare ESC
        var result = parser.feed(new byte[]{0x1B}, 0, 1);
        assertEquals((byte) 0x1B, result.menuKey());
        assertFalse(result.f12Detected());
    }

    @Test
    void menuModeShortcutReturned() {
        var parser = new EscapeSequenceParser();
        parser.setMenuMode(true);

        var result = parser.feed(new byte[]{0x76}, 0, 1); // 'v'
        assertEquals((byte) 0x76, result.menuKey());
        assertFalse(result.f12Detected());
    }

    @Test
    void menuModeF12StillDetected() {
        var parser = new EscapeSequenceParser();
        parser.setMenuMode(true);

        var input = new byte[]{0x1B, 0x5B, 0x32, 0x34, 0x7E};
        var result = parser.feed(input, 0, input.length);
        assertTrue(result.f12Detected());
    }

    @Test
    void menuModeArrowKeysConsumed() {
        var parser = new EscapeSequenceParser();
        parser.setMenuMode(true);

        // Up arrow = ESC [ A — should be consumed (no menu key, no forward)
        var input = new byte[]{0x1B, 0x5B, 0x41};
        var result = parser.feed(input, 0, input.length);
        assertFalse(result.f12Detected());
        assertEquals(0, result.menuKey());
        assertEquals(0, result.toForward().length);
    }

    @Test
    void partialEscapeSequenceFlushedOnNonCsi() {
        var parser = new EscapeSequenceParser();
        // ESC followed by non-[ character (not a CSI sequence)
        var input = new byte[]{0x1B, 0x4F, 0x50}; // ESC O P (F1 in some terminals)
        var result = parser.feed(input, 0, input.length);
        assertFalse(result.f12Detected());
        // Should forward the entire sequence
        assertArrayEquals(input, result.toForward());
    }

    @Test
    void emptyInputReturnsEmpty() {
        var parser = new EscapeSequenceParser();
        var result = parser.feed(new byte[0], 0, 0);
        assertFalse(result.f12Detected());
        assertEquals(0, result.toForward().length);
        assertEquals(0, result.menuKey());
    }

    @Test
    void rapidNormalInputAfterNonF12Csi() {
        var parser = new EscapeSequenceParser();
        // CSI sequence (not F12) followed by normal text
        // ESC [ 1 ~ (Home key) then "abc"
        var input = new byte[]{0x1B, 0x5B, 0x31, 0x7E, 0x61, 0x62, 0x63};
        var result = parser.feed(input, 0, input.length);
        assertFalse(result.f12Detected());
        // Should forward the entire input (CSI + text)
        assertArrayEquals(input, result.toForward());
    }

    private static final byte[] F12 = {0x1B, 0x5B, 0x32, 0x34, 0x7E};

    @Test
    void aBareEscapeIsForwardedAtOnce() {
        // vim leaves insert mode on Esc: holding it until the next key delays that by a keystroke.
        var parser = new EscapeSequenceParser();
        var result = parser.feed(new byte[]{0x1B}, 0, 1);
        assertArrayEquals(new byte[]{0x1B}, result.toForward());

        result = parser.feed(new byte[]{0x61}, 0, 1);
        assertArrayEquals(new byte[]{0x61}, result.toForward());
    }

    @Test
    void bytesAfterF12InTheSameReadAreLeftForTheNextFeed() {
        // F12 then a shortcut typed fast enough to arrive in one read.
        var parser = new EscapeSequenceParser();
        var input = new byte[]{0x61, 0x1B, 0x5B, 0x32, 0x34, 0x7E, 0x76}; // a F12 v
        var result = parser.feed(input, 0, input.length);
        assertTrue(result.f12Detected());
        assertArrayEquals(new byte[]{0x61}, result.toForward());
        assertEquals(6, result.consumed());

        parser.setMenuMode(true);
        result = parser.feed(input, 6, 1);
        assertEquals((byte) 0x76, result.menuKey());
        assertEquals(1, result.consumed());
    }

    @Test
    void menuKeysAfterTheFirstInOneReadAreLeftForTheNextFeed() {
        var parser = new EscapeSequenceParser();
        parser.setMenuMode(true);
        var input = new byte[]{0x78, 0x1B, 0x5B, 0x32, 0x34, 0x7E}; // x F12
        var result = parser.feed(input, 0, input.length);
        assertEquals((byte) 0x78, result.menuKey());
        assertEquals(1, result.consumed());

        result = parser.feed(input, 1, input.length - 1);
        assertTrue(result.f12Detected());
        assertEquals(5, result.consumed());
    }

    @Test
    void aReadWithoutAnEventIsConsumedWhole() {
        var parser = new EscapeSequenceParser();
        var input = "ls -l\r".getBytes();
        assertEquals(input.length, parser.feed(input, 0, input.length).consumed());
        assertEquals(F12.length, parser.feed(F12, 0, F12.length).consumed());
    }

    @Test
    void aLongCsiSequencePassesThroughIntact() {
        // An SGR mouse report, longer than the bytes the parser keeps to recognise F12.
        var parser = new EscapeSequenceParser();
        var input = "\033[<0;123;45M".getBytes();
        assertArrayEquals(input, parser.feed(input, 0, input.length).toForward());
    }

    @Test
    void anApplicationCursorKeyInTheMenuIsSwallowedWhole() {
        // Up arrow with application cursor keys on (vim, less): ESC O A. Neither closes the
        // menu nor leaves an "A" for the shell.
        var parser = new EscapeSequenceParser();
        parser.setMenuMode(true);
        var input = new byte[]{0x1B, 0x4F, 0x41};
        var result = parser.feed(input, 0, input.length);
        assertEquals(0, result.menuKey());
        assertEquals(3, result.consumed());
        assertEquals(0, result.toForward().length);
    }
}
