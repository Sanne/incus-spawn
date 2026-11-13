package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDK takes its poller mode once per process, so each case runs in a JVM of its own: this
 * one's pollers were chosen by whichever test first read a socket on a virtual thread (#2129).
 */
class VirtualThreadPollersTest {

    @Test
    void aVirtualThreadsSocketReadIsWokenByAPlatformPoller() throws Exception {
        var seen = probe(List.of());
        assertTrue(seen.contains("mode=1"), seen);
        // SYSTEM_THREADS names its platform pollers so; VTHREAD_POLLERS has one platform
        // MasterPoller, and its SubPollers are virtual threads, which getAllStackTraces leaves out
        assertTrue(seen.contains("thread=Read-Poller"), seen);
        assertFalse(seen.contains("thread=MasterPoller"), seen);
    }

    @Test
    void aModeSetOnTheCommandLineIsKept() throws Exception {
        var seen = probe(List.of("-D" + VirtualThreadPollers.PROPERTY + "=2"));
        assertTrue(seen.contains("mode=2"), seen);
    }

    /** What {@link Probe} printed, in a JVM started with {@code jvmArgs}. */
    private static String probe(List<String> jvmArgs) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmArgs);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName()));
        var child = new ProcessBuilder(command).redirectErrorStream(true).start();
        var finished = child.waitFor(60, TimeUnit.SECONDS);
        // A probe still running would hold its output open, so it is killed before the read
        if (!finished) child.destroyForcibly().waitFor();
        var out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(finished, "the probe did not finish: " + out);
        assertEquals(0, child.exitValue(), out);
        return out;
    }

    /**
     * Selects the pollers as an entry point does, has a virtual thread wait on a socket, and
     * prints the mode and the name of every live thread.
     */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            VirtualThreadPollers.usePlatformThreads();
            try (var server = ServerSocketChannel.open()) {
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                try (var client = SocketChannel.open(server.getLocalAddress());
                     var accepted = server.accept()) {
                    var read = new AtomicInteger(-2);
                    var reader = Thread.ofVirtual().start(() -> {
                        try {
                            read.set(accepted.read(ByteBuffer.allocate(1)));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
                    // The reader parks with the poller first: data already there is read without one
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (reader.getState() != Thread.State.WAITING) {
                        if (System.nanoTime() > deadline) throw new IllegalStateException("the reader never parked");
                        Thread.sleep(10);
                    }
                    client.write(ByteBuffer.wrap(new byte[]{1}));
                    reader.join(TimeUnit.SECONDS.toMillis(30));
                    if (reader.isAlive()) throw new IllegalStateException("the read was never woken");
                    // A failed read ends the reader too, but reads no byte: the wakeup is what is checked
                    if (read.get() != 1) throw new IllegalStateException("the read returned " + read.get());
                }
            }
            var out = new StringBuilder("mode=" + System.getProperty(VirtualThreadPollers.PROPERTY) + "\n");
            out.append(Thread.getAllStackTraces().keySet().stream()
                    .map(t -> "thread=" + t.getName()).sorted().collect(Collectors.joining("\n")));
            TerminalStreams.out().println(out);
        }
    }
}
