package dev.incusspawn.command;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import dev.incusspawn.Environment;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.proxy.ProxyService;

/**
 * Points {@code user.home} at a fresh directory for each test, so anything that calls
 * {@code SpawnConfig.save()} writes there rather than over the developer's own
 * {@code ~/.config/incus-spawn/config.yaml}. The static helpers read and write that file for
 * the tests using it, so they can assert on what actually reached disk.
 *
 * <p>It also stands in for the proxy's account-refresh signal, which would otherwise reach
 * whatever isx proxy runs on the developer's machine; {@link #proxySignals()} counts the calls.
 * And it gives {@link CredentialSetup} an Incus of its own, empty until a test adds to it through
 * {@link #incus()}, so no flow reads the instances of an Incus the developer runs (#1965).
 */
public final class IsolatedHome implements BeforeEachCallback, AfterEachCallback {

    /** The config.yaml the code under test reads and writes. */
    static Path configFile() {
        return Environment.configDir().resolve("config.yaml");
    }

    /** Writes {@code yaml} as the config file and loads it, as {@code isx init} would. */
    static SpawnConfig seed(String yaml) throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), yaml);
        return SpawnConfig.load();
    }

    /** A dotted path's value in the config as saved on disk, or {@code ""}. */
    static String saved(String path) {
        return AccountResolver.navigate(SpawnConfig.load().tree(), path);
    }

    /** No config file was written at all. */
    static void assertNothingSaved() {
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(configFile()),
                "nothing should have been saved, but config.yaml was written");
    }

    /** The config file is byte-for-byte what {@link #seed} wrote: not even reformatted. */
    static void assertUnchanged(String seeded) throws IOException {
        org.junit.jupiter.api.Assertions.assertEquals(seeded, Files.readString(configFile()),
                "config.yaml should not have been rewritten");
    }

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(IsolatedHome.class);

    private static final AtomicInteger PROXY_SIGNALS = new AtomicInteger();

    /** How often the code under test signalled the proxy to re-read its accounts. */
    static int proxySignals() {
        return PROXY_SIGNALS.get();
    }

    private static volatile FakeIncusDaemon incus;

    /** The Incus the code under test asks, which has no instances until the test adds some. */
    static FakeIncusDaemon incus() {
        return incus;
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
        var daemon = new FakeIncusDaemon();
        incus = daemon;
        store.put("incus", CredentialSetup.replaceIncus(daemon::client));
    }

    @Override
    public void afterEach(ExtensionContext context) throws IOException {
        var store = context.getStore(NS);
        var signal = store.get("signal", Runnable.class);
        if (signal != null) ProxyService.replaceAccountRefreshSignal(signal);
        @SuppressWarnings("unchecked")
        java.util.function.Supplier<dev.incusspawn.incus.IncusClient> previous = store.get("incus", java.util.function.Supplier.class);
        if (previous != null) CredentialSetup.replaceIncus(previous);
        incus = null;
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
