package dev.incusspawn.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of {@code isx gui}: where the host compositor is, and the text of what runs. */
class GuiBridgeTest {

    private static GuiBridge.HostDisplay find(Map<String, String> env, Set<String> sockets, Map<String, List<String>> dirs) {
        return GuiBridge.hostDisplay(env, sockets::contains, dir -> dirs.getOrDefault(dir, List.of()));
    }

    @Test
    void theSessionsOwnDisplayComesFirst() {
        var found = find(Map.of("XDG_RUNTIME_DIR", "/run/user/1000", "WAYLAND_DISPLAY", "wayland-1", "TMPDIR", "/t"),
                Set.of("/run/user/1000/wayland-1", "/t/cocoa-way/wayland-0"),
                Map.of("/t/cocoa-way", List.of("wayland-0")));
        assertEquals(new GuiBridge.HostDisplay("/run/user/1000", "wayland-1"), found);
        assertEquals("/run/user/1000/wayland-1", found.path());
    }

    @Test
    void cocoaWaysSocketIsFoundWhereItsOwnWrapperLooks() {
        // A Mac's shell has TMPDIR and no Wayland variables; the lock file beside the socket is no socket
        var found = find(Map.of("TMPDIR", "/var/folders/x/T/"),
                Set.of("/var/folders/x/T/cocoa-way/wayland-0"),
                Map.of("/var/folders/x/T/cocoa-way", List.of("wayland-0.lock", "wayland-0", "other")));
        assertEquals(new GuiBridge.HostDisplay("/var/folders/x/T/cocoa-way", "wayland-0"), found);

        var named = find(Map.of("COCOA_WAY_RUNTIME_DIR", "/c", "TMPDIR", "/t"),
                Set.of("/c/wayland-3", "/t/cocoa-way/wayland-0"),
                Map.of("/c", List.of("wayland-3"), "/t/cocoa-way", List.of("wayland-0")));
        assertEquals("/c/wayland-3", named.path());

        assertEquals("/tmp/cocoa-way/wayland-0", find(Map.of(), Set.of("/tmp/cocoa-way/wayland-0"),
                Map.of("/tmp/cocoa-way", List.of("wayland-0"))).path());
    }

    @Test
    void aDisplayThatIsNotThereIsNotFound() {
        assertNull(find(Map.of("XDG_RUNTIME_DIR", "/run/user/1000", "WAYLAND_DISPLAY", "wayland-1"), Set.of(), Map.of()));
        // a regular file named like a display is none
        assertNull(find(Map.of(), Set.of(), Map.of("/tmp/cocoa-way", List.of("wayland-0"))));
    }

    @Test
    void aRealSocketIsToldFromAFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("wayland-0"), "");
        var env = Map.of("COCOA_WAY_RUNTIME_DIR", dir.toString());
        assertNull(GuiBridge.hostDisplay(env));
        try (var server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(java.net.UnixDomainSocketAddress.of(dir.resolve("wayland-1")));
            assertEquals(dir.resolve("wayland-1").toString(), GuiBridge.hostDisplay(env).path());
        }
    }

    @Test
    void theHostCommandGoesThroughIsxsOwnSshConfigWithTheScriptAsOneWord() {
        var argv = GuiBridge.waypipeCommand("waypipe", Path.of("/home/u/.config/incus-spawn/ssh/config"), "my-box",
                GuiBridge.HELD_DISPLAY, "echo 'it''s'\n");
        assertEquals(List.of("waypipe", "--no-gpu", "--display", "/run/isx-gui/wayland-0",
                "ssh", "-F", "/home/u/.config/incus-spawn/ssh/config", "-o", "BatchMode=yes", "my-box",
                "bash", "-lc"), argv.subList(0, argv.size() - 1));
        // ssh joins its arguments with spaces and the guest's shell splits them again
        assertTrue(argv.getLast().startsWith("'") && argv.getLast().endsWith("'"), argv.getLast());
    }

    @Test
    void aRunsDisplayIsItsOwn() {
        assertEquals("/run/isx-gui/wayland-123-abc", GuiBridge.runDisplay("123-abc"));
        assertThrows(IllegalArgumentException.class, () -> GuiBridge.runDisplay("a b"));
        assertThrows(IllegalArgumentException.class, () -> GuiBridge.runDisplay("../x"));
    }

    @Test
    void theGuestScriptRunsTheCommandQuotedWordByWord() throws Exception {
        var script = GuiBridge.guestScript("/home/agentuser/my project", List.of("printf", "%s|", "a b", "$HOME", "it's"));
        assertTrue(script.contains("export MOZ_ENABLE_WAYLAND=1\n"), script);
        assertTrue(script.contains("cd -- '/home/agentuser/my project' 2>/dev/null\n"), script);
        // a missing workdir does not stop the command, and no word is expanded or split
        assertEquals("a b|$HOME|it's|", sh(script, Map.of()).strip());
    }

    @Test
    void withNoCommandTheGuestScriptWaitsForAsLongAsTheDisplayExists(@TempDir Path dir) throws Exception {
        var script = GuiBridge.guestScript("", List.of());
        assertFalse(script.contains("exec"), script);
        // no socket at the display's path: the wait ends at once
        assertEquals("", sh(script, Map.of("WAYLAND_DISPLAY", dir.resolve("gone").toString())));
    }

    @Test
    void theLoginScriptLeavesAShellThatHasADisplayAlone(@TempDir Path dir) throws Exception {
        var profile = GuiBridge.profileScript();
        // No held display in this test's host: nothing is exported
        assertEquals("unset", sh(profile + "echo \"${WAYLAND_DISPLAY:-unset}\"\n", Map.of()).strip());
        assertEquals("mine", sh(profile + "echo \"$WAYLAND_DISPLAY\"\n", Map.of("WAYLAND_DISPLAY", "mine")).strip());
        assertTrue(GuiBridge.prepareScript().contains(profile), "the prepare script writes the login script");
        assertTrue(GuiBridge.prepareScript().startsWith("command -v waypipe >/dev/null 2>&1 || exit 3\n"));
    }

    @Test
    void cleanupEndsOnlyItsOwnDisplaysServer() {
        var script = GuiBridge.cleanupScript("/run/isx-gui/wayland-0");
        // The pattern must not match the shell that runs it, whose command line holds the script
        assertFalse(script.contains("--display"), script);
        assertTrue(java.util.regex.Pattern.compile("[-]-display \\Q/run/isx-gui/wayland-0\\E server")
                .matcher("waypipe --no-gpu --socket /tmp/s --display /run/isx-gui/wayland-0 server bash -lc x").find());
        assertTrue(script.contains("rm -f -- '/run/isx-gui/wayland-0' '/run/isx-gui/wayland-0'.lock\n"), script);
    }

    private static String sh(String script, Map<String, String> env) throws IOException, InterruptedException {
        var builder = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        builder.environment().remove("WAYLAND_DISPLAY");
        builder.environment().putAll(env);
        var process = builder.start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the script ended");
        return output;
    }
}
