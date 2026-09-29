package dev.incusspawn.lifecycle;

import dev.incusspawn.Platform;
import dev.incusspawn.incus.FakeIncusDaemon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * An {@code --inbox} directory deleted while its instance is stopped must not fail the next start
 * with Incus's {@code Missing source path} (#854).
 */
class StaleInboxTest {

    private static final String NAME = "dev-1";

    @BeforeEach
    void linuxSourcesOnly() {
        // On macOS the device source is the appliance VM's /host view of the path.
        assumeFalse(Platform.isMacOS());
    }

    private static FakeIncusDaemon withInbox(Path source) {
        // Filtering on, as isx branch leaves it, so the pre-start repair has only the inbox to do.
        return new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true")
                .device(NAME, InstanceLifecycle.INBOX_DEVICE, Map.of("type", "disk",
                        "source", source.toString(), "path", InstanceLifecycle.INBOX_PATH, "readonly", "true"));
    }

    @Test
    void inboxWhoseDirectoryIsGoneIsRemovedBeforeStart(@TempDir Path tmp) {
        var deleted = tmp.resolve("deleted");
        var daemon = withInbox(deleted);

        var err = new java.io.ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new java.io.PrintStream(err, true));
        try {
            InstanceLifecycle.prepareHostDevicesForStart(daemon.client(), NAME);
        } finally {
            System.setErr(original);
        }

        assertTrue(daemon.instance(NAME).path("devices").path(InstanceLifecycle.INBOX_DEVICE).isMissingNode(),
                daemon.instance(NAME).path("devices").toString());
        // The re-add hint must carry the device's own source form, not a placeholder the user
        // would fill with a host path (wrong on macOS, where incus sees the home under /host).
        assertTrue(err.toString().contains("disk source=" + deleted + " path="), err.toString());
    }

    @Test
    void warningGoesToTheCallersSinkInsteadOfStderr(@TempDir Path tmp) {
        var daemon = withInbox(tmp.resolve("deleted"));
        var warnings = new java.util.ArrayList<String>();

        var err = new java.io.ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new java.io.PrintStream(err, true));
        try {
            // The TUI's sink: stderr there would be drawn over by the TUI and lost.
            InstanceLifecycle.prepareHostDevicesForStart(daemon.client(), NAME, warnings::add);
        } finally {
            System.setErr(original);
        }

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("inbox directory not found"), warnings.get(0));
        assertEquals("", err.toString());
    }

    @Test
    void healthyInboxIsKeptAtNoExtraRequest(@TempDir Path tmp) throws Exception {
        var daemon = withInbox(Files.createDirectories(tmp.resolve("inbox")));

        InstanceLifecycle.prepareHostDevicesForStart(daemon.client(), NAME);

        assertFalse(daemon.instance(NAME).path("devices").path(InstanceLifecycle.INBOX_DEVICE).isMissingNode());
        assertEquals(1, daemon.requests().size(), String.join("\n", daemon.requests()));
    }
}
