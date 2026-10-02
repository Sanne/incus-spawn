package dev.incusspawn.lifecycle;

import dev.incusspawn.proxy.ProxyService;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Points {@code user.home} at a fresh directory for each test. Branch configuration takes the
 * host-wide static IP lock under the home directory: against the real one, a test would wait
 * on (and delay) whatever isx is branching on the developer's machine.
 *
 * <p>It also stands in for the proxy's account-refresh signal, which would otherwise reach
 * whatever isx proxy runs on the developer's machine; {@link #proxySignals()} counts the calls.
 */
final class TempHome implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(TempHome.class);

    private static final AtomicInteger PROXY_SIGNALS = new AtomicInteger();

    /** How often the code under test signalled the proxy to re-read its accounts. */
    static int proxySignals() {
        return PROXY_SIGNALS.get();
    }

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        var store = context.getStore(NS);
        store.put("original", System.getProperty("user.home"));
        var home = Files.createTempDirectory("isx-home");
        store.put("home", home);
        System.setProperty("user.home", home.toString());
        PROXY_SIGNALS.set(0);
        store.put("signal", ProxyService.replaceAccountRefreshSignal(PROXY_SIGNALS::incrementAndGet));
    }

    @Override
    public void afterEach(ExtensionContext context) throws IOException {
        var store = context.getStore(NS);
        var signal = store.get("signal", Runnable.class);
        if (signal != null) ProxyService.replaceAccountRefreshSignal(signal);
        System.setProperty("user.home", store.get("original", String.class));
        try (var walk = Files.walk(store.get("home", Path.class))) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
