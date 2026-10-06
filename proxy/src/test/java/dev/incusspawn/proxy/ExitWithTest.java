package dev.incusspawn.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A proxy started with {@code --exit-with-pid} stops once {@code isx proxy start} is gone, even
 * when that CLI was SIGKILLed and ran no shutdown hook (#923). Nothing here relies on PID 1
 * reaping: a container's PID 1 may not, and then every process that ends stays a zombie.
 */
@DisabledOnOs(OS.WINDOWS)
class ExitWithTest {

    @TempDir Path home;

    private final List<ProcessHandle> spawned = new ArrayList<>();

    @AfterEach
    void killLeftovers() {
        spawned.forEach(ProcessHandle::destroyForcibly);
    }

    /**
     * The CLI is SIGKILLed and its own parent never reaps it, so it stays a zombie, which
     * {@link ProcessHandle#onExit()} counts as alive. The proxy is reparented all the same.
     */
    @Test
    @EnabledOnOs(OS.LINUX) // a zombie's end is read from /proc
    void exitsWhenTheKilledCliIsAZombie() throws Exception {
        // sh starts the CLI, then becomes a `sleep` that never waits for it.
        var sh = new ProcessBuilder("sh", "-c", "\"$0\" \"$@\" & echo $!; exec sleep 600",
                java(), "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                Cli.class.getName()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        spawned.add(sh.toHandle());
        var out = new BufferedReader(new InputStreamReader(sh.getInputStream()));
        var cli = ProcessHandle.of(Long.parseLong(out.readLine().trim())).orElseThrow();
        spawned.add(cli);
        assertEquals(WATCHING, out.readLine(), "the proxy stand-in did not start its watch");
        var proxy = cli.children().findFirst().orElseThrow();
        spawned.add(proxy);

        cli.destroyForcibly(); // SIGKILL: no hook of the CLI's runs
        awaitEnded(cli.pid(), "the CLI");
        assertTrue(cli.isAlive(), "the CLI was reaped, so this did not test a zombie CLI");
        awaitEnded(proxy.pid(), "the proxy outlived the SIGKILLed CLI: it");
    }

    /**
     * Only the processes above this one count, never one that is merely alive: a CLI that died
     * before the proxy started watching, and was left a zombie, is alive but no longer above it.
     */
    @Test
    void theCliCountsOnlyWhileItIsAnAncestor() throws Exception {
        var parent = ProcessHandle.current().parent().orElseThrow();
        assertTrue(ExitWith.isAncestor(parent.pid()));
        parent.parent().ifPresent(grandparent -> assertTrue(ExitWith.isAncestor(grandparent.pid()),
                "a launcher between the CLI and the proxy hid the CLI"));

        var unrelated = new ProcessBuilder("sleep", "600").start().toHandle();
        spawned.add(unrelated);
        assertFalse(ExitWith.isAncestor(unrelated.pid()), "a live process that is not above the proxy counted");
        assertFalse(ExitWith.isAncestor(deadPid()));
    }

    @Test
    void exitsAtOnceWhenTheCliIsAlreadyGone() {
        var exited = new AtomicBoolean();
        ExitWith.watch(() -> false, Duration.ofSeconds(1), () -> exited.set(true));
        assertTrue(exited.get(), "a CLI that died before the proxy started was not noticed");
    }

    @Test
    void exitsOnceTheCliIsGone() throws Exception {
        var cliAlive = new AtomicBoolean(true);
        var exited = new CountDownLatch(1);
        ExitWith.watch(cliAlive::get, Duration.ofMillis(10), exited::countDown);
        assertFalse(exited.await(200, TimeUnit.MILLISECONDS), "exited while the CLI was running");

        cliAlive.set(false);
        assertTrue(exited.await(5, TimeUnit.SECONDS), "the proxy kept running once the CLI was gone");
    }

    /** {@code ProxyMain} itself acts on the argument: it stops before needing an initialized home. */
    @Test
    void proxyMainExitsForAGoneCli() throws Exception {
        var main = new ProcessBuilder(java(), "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                RunProxyMain.class.getName(), ExitWith.ARG, String.valueOf(deadPid()))
                .redirectErrorStream(true).start();
        spawned.add(main.toHandle());
        var output = new String(main.getInputStream().readAllBytes());
        assertTrue(main.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, main.exitValue(), output);
        assertTrue(output.contains("is gone; stopping"), output);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static long deadPid() throws Exception {
        var gone = new ProcessBuilder("true").start();
        gone.waitFor();
        return gone.pid();
    }

    private static void awaitEnded(long pid, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (ended(pid)) return;
            Thread.sleep(50);
        }
        fail(what + " (pid " + pid + ") did not end");
    }

    /** Gone, or a zombie nobody has reaped yet: either way it has stopped running. */
    private static boolean ended(long pid) {
        try {
            var stat = Files.readString(Path.of("/proc", String.valueOf(pid), "stat"));
            return stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z");
        } catch (IOException e) {
            return true;
        }
    }

    private static final String WATCHING = "watching";

    /** Stand-in for {@code isx proxy start}: runs the proxy stand-in with its own pid, as the CLI does. */
    public static final class Cli {
        public static void main(String[] args) throws Exception {
            new ProcessBuilder(java(), "-Duser.home=" + System.getProperty("user.home"),
                    "-cp", System.getProperty("java.class.path"), Proxy.class.getName(),
                    ExitWith.ARG, String.valueOf(ProcessHandle.current().pid()))
                    .inheritIO().start();
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /** Stand-in for {@code isx-proxy}: only the watch, then serving forever. */
    public static final class Proxy {
        public static void main(String[] args) throws Exception {
            ExitWith.watchIfAsked(args);
            System.out.println(WATCHING);
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    public static final class RunProxyMain {
        public static void main(String[] args) {
            System.exit(new ProxyMain().run(args));
        }
    }
}
