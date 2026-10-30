package dev.incusspawn.tui;

import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.EventParser;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keys a terminal sends in the kitty or modifyOtherKeys encoding reach the TUI as legacy keys. */
class KeyboardProtocolTest {

    /** Typed input behind the decoder, read by tamboui's own parser, as the TUI reads it. */
    private static final class Typed extends HeadlessBackend {
        final ArrayDeque<Integer> bytes = new ArrayDeque<>();
        int timeouts;
        final KeyboardProtocol keys = new KeyboardProtocol(new KeyboardProtocol.Input() {
            @Override
            public int read(int timeoutMs) {
                if (bytes.isEmpty()) timeouts++;
                return bytes.isEmpty() ? -2 : bytes.poll();
            }

            @Override
            public int peek(int timeoutMs) {
                if (bytes.isEmpty()) timeouts++;
                return bytes.isEmpty() ? -2 : bytes.peek();
            }
        });

        Typed(String input) {
            super(80, 24);
            input.codePoints().forEach(bytes::add);
        }

        @Override public int read(int timeoutMs) {
            try {
                return keys.read(timeoutMs);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override public int peek(int timeoutMs) {
            try {
                return keys.peek(timeoutMs);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        List<Event> events() throws IOException {
            // A dropped key reads as a timeout, so read on until the input is spent
            var events = new ArrayList<Event>();
            while (!bytes.isEmpty()) {
                var event = EventParser.readEvent(this, 0, BindingSets.defaults());
                if (event != null) events.add(event);
            }
            Event event;
            while ((event = EventParser.readEvent(this, 0, BindingSets.defaults())) != null) events.add(event);
            return events;
        }
    }

    private static KeyEvent onlyKey(String input) throws IOException {
        var events = new Typed(input).events();
        assertEquals(1, events.size(), () -> "events: " + events);
        return assertInstanceOf(KeyEvent.class, events.getFirst());
    }

    @Test
    void escInEveryEncodingIsEscape() throws IOException {
        for (var esc : List.of("\u001b", "\u001b[27u", "\u001b[27;1u", "\u001b[27;1:1u", "\u001b[27;129u",
                "\u001b[27;1;27~")) {
            var key = onlyKey(esc);
            assertTrue(key.isKey(KeyCode.ESCAPE), () -> esc.substring(1) + " read as " + key);
            assertFalse(key.hasAlt() || key.hasCtrl() || key.hasShift(), esc.substring(1));
        }
    }

    @Test
    void ctrlCInEveryEncodingIsCtrlC() throws IOException {
        for (var ctrlC : List.of("\u0003", "\u001b[99;5u", "\u001b[99;5:1u", "\u001b[27;5;99~")) {
            assertTrue(onlyKey(ctrlC).isCtrlC(), ctrlC.substring(1));
        }
    }

    @Test
    void modifiedKeysKeepTheirModifiers() throws IOException {
        var altOne = onlyKey("\u001b[49;3u");
        assertTrue(altOne.isChar('1') && altOne.hasAlt(), altOne.toString());
        var ctrlL = onlyKey("\u001b[108;5u");
        assertTrue(ctrlL.isChar('l') && ctrlL.hasCtrl(), ctrlL.toString());
        assertTrue(onlyKey("\u001b[97;2u").isChar('A'));
        assertTrue(ShiftTabBindings.isShiftTab(onlyKey("\u001b[9;2u")));
        assertTrue(onlyKey("\u001b[13u").isKey(KeyCode.ENTER));
        assertTrue(onlyKey("\u001b[127u").isKey(KeyCode.BACKSPACE));
        assertTrue(onlyKey("\u001b[233u").isChar('é'));
    }

    @Test
    void shiftWithAShiftedFormIsThatForm() throws IOException {
        var altBang = onlyKey("\u001b[49:33;4u");
        assertTrue(altBang.isChar('!') && altBang.hasAlt() && !altBang.hasShift(), altBang.toString());
        assertTrue(onlyKey("\u001b[49:33;2u").isChar('!'));
        assertTrue(onlyKey("\u001b[97:65;2u").isChar('A'));
        assertTrue(onlyKey("\u001b[49:33:49;2u").isChar('!'), "with a base layout key");
        assertTrue(onlyKey("\u001b[49:33;3u").isChar('1'), "no Shift: the base key");
        assertTrue(DigitKeys.asFunctionKey(onlyKey("\u001b[49:33;4u")).isChar('!'), "not Shift+F1");
    }

    @Test
    void aDroppedKeyIsATimeoutNotASecondWait() throws IOException {
        var typed = new Typed("\u001b[27;1:3u");
        assertEquals(-2, typed.read(100));
        assertEquals(0, typed.timeouts, "the release alone, then no further read of the input");
        typed = new Typed("\u001b[27u\u001b[27;1:3u");
        assertTrue(((KeyEvent) EventParser.readEvent(typed, 100, BindingSets.defaults())).isKey(KeyCode.ESCAPE));
        assertEquals(null, EventParser.readEvent(typed, 100, BindingSets.defaults()));
        assertEquals(0, typed.timeouts);
    }

    @Test
    void releasesAndKeysWithNoLegacyByteAreDropped() throws IOException {
        assertEquals(List.of(), new Typed("\u001b[27;1:3u").events());
        assertEquals(List.of(), new Typed("\u001b[57399u").events(), "keypad 0");
        var key = onlyKey("\u001b[57399u\u001b[27u");
        assertTrue(key.isKey(KeyCode.ESCAPE));
    }

    @Test
    void otherSequencesPassUntouched() throws IOException {
        assertTrue(onlyKey("\u001b[A").isKey(KeyCode.UP));
        assertTrue(onlyKey("\u001b[1;5A").isKey(KeyCode.UP));
        assertTrue(onlyKey("\u001b[13~").isKey(KeyCode.F3));
        assertTrue(onlyKey("\u001bOP").isKey(KeyCode.F1));
        assertTrue(ShiftTabBindings.isShiftTab(onlyKey("\u001b[Z")));
        var alt = onlyKey("\u001bx");
        assertTrue(alt.isChar('x') && alt.hasAlt());
        var paste = new Typed("\u001b[200~hi\u001b[201~").events();
        assertEquals("hi", assertInstanceOf(PasteEvent.class, paste.getFirst()).text());
    }

    @Test
    void escThenKeysAreSeparateKeys() throws IOException {
        var events = new Typed("\u001b[27uq").events();
        assertEquals(2, events.size(), events::toString);
    }

    @Test
    void aLoneEscIsWaitedForOnce() throws IOException {
        var typed = new Typed("\u001b[27u");
        assertTrue(((KeyEvent) EventParser.readEvent(typed, 0, BindingSets.defaults())).isKey(KeyCode.ESCAPE));
        assertEquals(0, typed.timeouts, "decoded Esc needs no wait");
        typed = new Typed("\u001b");
        assertTrue(((KeyEvent) EventParser.readEvent(typed, 0, BindingSets.defaults())).isKey(KeyCode.ESCAPE));
        assertEquals(1, typed.timeouts, "the decoder's wait stands for the parser's");
    }

    @Test
    void anUnknownKeyIsNotShiftTab() {
        assertFalse(ShiftTabBindings.isShiftTab(KeyEvent.ofKey(KeyCode.UNKNOWN)));
    }
}
