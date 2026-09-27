package dev.incusspawn.lifecycle;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Points {@code user.home} at a fresh directory for each test. Branch configuration takes the
 * host-wide static IP lock under the home directory: against the real one, a test would wait
 * on (and delay) whatever isx is branching on the developer's machine.
 */
final class TempHome implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(TempHome.class);

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        var store = context.getStore(NS);
        store.put("original", System.getProperty("user.home"));
        var home = Files.createTempDirectory("isx-home");
        store.put("home", home);
        System.setProperty("user.home", home.toString());
    }

    @Override
    public void afterEach(ExtensionContext context) throws IOException {
        var store = context.getStore(NS);
        System.setProperty("user.home", store.get("original", String.class));
        try (var walk = Files.walk(store.get("home", Path.class))) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
