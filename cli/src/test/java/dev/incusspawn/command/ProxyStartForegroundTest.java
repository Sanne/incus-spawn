package dev.incusspawn.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Terminating {@code isx proxy start} alone (a {@code kill}, a supervisor, a cancelled CI step)
 * must not leave the foreground {@code isx-proxy} running as an orphan that keeps the ports
 * (issue #882). Each test runs {@link ProxyStartCommand.ForegroundProxy} in a separate JVM, as the CLI
 * does, with a stand-in for the proxy, sends that JVM SIGTERM, and checks the stand-in is gone.
 */
@DisabledOnOs(OS.WINDOWS)
class ProxyStartForegroundTest {

    private final List<ProcessHandle> spawned = new ArrayList<>();

    @AfterEach
    void killLeftovers() {
        spawned.forEach(ProcessHandle::destroyForcibly);
    }

    @Test
    void sigtermToTheCliStopsTheProxy() throws Exception {
        var proxy = terminateCliRunning(Duration.ofSeconds(10), "sleep", "600");
        awaitExit(proxy, Duration.ofSeconds(10));
    }

    @Test
    void aProxyThatIgnoresSigtermIsKilledAfterTheGracePeriod() throws Exception {
        var proxy = terminateCliRunning(Duration.ofMillis(500),
                "sh", "-c", "trap '' TERM; exec sleep 600");
        awaitExit(proxy, Duration.ofSeconds(10));
    }

    /** Start the CLI stand-in, wait for its proxy child, SIGTERM the CLI; returns the child. */
    private ProcessHandle terminateCliRunning(Duration grace, String... proxyCmd) throws Exception {
        var cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                Cli.class.getName(), String.valueOf(grace.toMillis())));
        cmd.addAll(List.of(proxyCmd));
        var cli = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        spawned.add(cli.toHandle());

        var proxy = awaitChild(cli.toHandle(), Duration.ofSeconds(20));
        spawned.add(proxy);

        cli.destroy(); // SIGTERM to the CLI alone, as `kill <pid>` does
        assertTrue(cli.waitFor(20, TimeUnit.SECONDS), "the CLI did not exit on SIGTERM");
        return proxy;
    }

    private static ProcessHandle awaitChild(ProcessHandle parent, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            // Wait for the exec to `sleep 600`: before it, a trap may not be in place yet.
            var child = parent.children()
                    .filter(c -> List.of("600").equals(c.info().arguments().map(List::of).orElse(null)))
                    .findFirst();
            if (child.isPresent()) return child.get();
            Thread.sleep(20);
        }
        return fail("the CLI never started the proxy");
    }

    private static void awaitExit(ProcessHandle proxy, Duration timeout) throws Exception {
        try {
            proxy.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("the proxy (pid " + proxy.pid() + ") outlived the CLI that started it");
        }
    }

    /** Stand-in for {@code isx proxy start}'s foreground path: args are grace millis, then the proxy command. */
    public static final class Cli {
        public static void main(String[] args) throws Exception {
            var grace = Duration.ofMillis(Long.parseLong(args[0]));
            System.exit(new ProxyStartCommand.ForegroundProxy(List.of(args).subList(1, args.length), grace).run());
        }
    }
}
