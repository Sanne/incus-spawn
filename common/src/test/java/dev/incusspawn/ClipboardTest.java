package dev.incusspawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which command copies to the person's clipboard, answered from a given environment and path (#2594). */
class ClipboardTest {

    private static List<String> command(Map<String, String> env, Set<String> onPath) {
        return Clipboard.command(name -> env.getOrDefault(name, ""), onPath::contains);
    }

    private static final Set<String> ALL = Set.of("pbcopy", "wl-copy", "xclip", "xsel");

    @Test
    void waylandCopiesWithWlCopy() {
        assumeFalse(Platform.isMacOS());
        assertEquals(List.of("wl-copy"), command(Map.of("WAYLAND_DISPLAY", "wayland-0", "DISPLAY", ":0"), ALL));
    }

    @Test
    void x11CopiesWithXclipThenXsel() {
        assumeFalse(Platform.isMacOS());
        assertEquals(List.of("xclip", "-selection", "clipboard"), command(Map.of("DISPLAY", ":0"), ALL));
        assertEquals(List.of("xsel", "--clipboard", "--input"), command(Map.of("DISPLAY", ":0"), Set.of("xsel")));
    }

    @Test
    void noScreenHasNoClipboard() {
        assumeFalse(Platform.isMacOS());
        assertNull(command(Map.of(), ALL));
    }

    /** Over SSH a command here reaches this computer's clipboard, not the one the person pastes from. */
    @Test
    void noClipboardOverSsh() {
        assertNull(command(Map.of("SSH_TTY", "/dev/pts/1", "WAYLAND_DISPLAY", "wayland-0", "DISPLAY", ":0"), ALL));
        assertNull(command(Map.of("SSH_CONNECTION", "10.0.0.1 50000 10.0.0.2 22", "DISPLAY", ":0"), ALL));
    }

    @Test
    void aFailingCommandIsNoCopy() {
        assertFalse(Clipboard.copy(null, "text"));
        assertFalse(Clipboard.copy(List.of("false"), "text"));
        assertFalse(Clipboard.copy(List.of("isx-no-such-clipboard-command"), "text"));
    }
}
