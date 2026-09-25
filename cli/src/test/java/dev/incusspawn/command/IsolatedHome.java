package dev.incusspawn.command;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Points {@code user.home} at a fresh directory for each test, so anything that calls
 * {@code SpawnConfig.save()} writes there rather than over the developer's own
 * {@code ~/.config/incus-spawn/config.yaml}.
 */
final class IsolatedHome implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(IsolatedHome.class);

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
        var original = store.get("original", String.class);
        if (original == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", original);
        }
        try (var walk = Files.walk(store.get("home", Path.class))) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
