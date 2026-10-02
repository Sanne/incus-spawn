package dev.incusspawn.incus;

import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResult;
import dev.incusspawn.tool.ToolAction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What of the typed input reaches the shell while the status bar is up. */
class StatusBarInputRelayTest {

    private static final byte[] F12 = {0x1B, 0x5B, 0x32, 0x34, 0x7E};

    /** The bytes a session would send to the container. */
    private static final class Shell implements IncusTransport.WsConnection {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        @Override public byte[] readPayload() { return null; }
        @Override public byte[] readMessage() { return null; }
        @Override public long millisSinceLastReceived() { return 0; }
        @Override public void sendData(byte[] data, int offset, int length) {
            received.write(data, offset, length);
        }
        @Override public void sendPing() {}
        @Override public void sendClose() {}
        @Override public void close() {}
    }

    private static ToolAction action() {
        return new ToolAction() {
            @Override public String toolName() { return "vscode-remote"; }
            @Override public String label() { return "Open in VS Code"; }
            @Override public Optional<String> type() { return Optional.of("url"); }
            @Override public Optional<String> shortcut() { return Optional.of("v"); }
            @Override public ActionResult execute(ActionContext context) { return ActionResult.ok("ok"); }
        };
    }

    private static ShellStatusBar bar(List<ToolAction> menu) {
        var bar = new ShellStatusBar("dev-1", new ByteArrayOutputStream());
        bar.setMenuActions(menu, null);
        bar.setup(80, 24);
        return bar;
    }

    @Test
    void withAnEmptyMenuF12IsTheShells() {
        // mc and others bind F12; a bar with nothing to offer must not swallow it.
        assertNull(IncusApi.inputParserFor(bar(List.of())));
        assertNull(IncusApi.inputParserFor(null));
    }

    @Test
    void f12AndAShortcutInOneReadOpenTheMenuAndRunTheAction() throws Exception {
        var bar = bar(List.of(action()));
        var parser = IncusApi.inputParserFor(bar);
        assertNotNull(parser);
        var shell = new Shell();
        var input = new byte[]{'l', 's', F12[0], F12[1], F12[2], F12[3], F12[4], 'v', 'x'};
        IncusApi.relayInput(input, input.length, parser, bar, shell);
        // The shortcut closed the menu, so the key after it is the shell's again.
        assertArrayEquals(new byte[]{'l', 's', 'x'}, shell.received.toByteArray());
    }

    @Test
    void keysTypedInTheOpenMenuStayOutOfTheShell() throws Exception {
        var bar = bar(List.of(action()));
        var parser = IncusApi.inputParserFor(bar);
        var shell = new Shell();
        IncusApi.relayInput(F12, F12.length, parser, bar, shell);
        assertTrue(bar.isMenuActive());
        var keys = new byte[]{'q', 0x1B, 'O', 'A', 'z'};
        IncusApi.relayInput(keys, keys.length, parser, bar, shell);
        assertArrayEquals(new byte[0], shell.received.toByteArray());
        assertTrue(bar.isMenuActive());
    }
}
