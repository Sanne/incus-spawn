package dev.incusspawn.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code exposed-host-ports:} (#2117): what an instance sends a listed port reaches the same port
 * of this host's loopback, byte for byte, in both directions, with each side's end of stream passed
 * on while the other still flows.
 * <p>
 * The relay and the service it reaches cannot share a port on one address, so each listed port is
 * relayed to a target port of its own here ({@link HostPortRelay}'s test constructor).
 */
@Timeout(30)
class HostPortRelayTest {

    private static final String LOOPBACK = "127.0.0.1";

    private final Map<Integer, Integer> targetOf = new HashMap<>();
    private final HostPortRelay relay = new HostPortRelay(LOOPBACK, List.of(LOOPBACK),
            port -> targetOf.getOrDefault(port, port));
    private final List<ServerSocket> services = new java.util.ArrayList<>();

    @AfterEach
    void stop() throws IOException {
        relay.stop();
        for (var service : services) service.close();
    }

    /** Reads each connection to end of stream, then answers with how many bytes it got and closes: needs a half-close. */
    private int countingService() throws IOException {
        var server = service();
        Thread.ofVirtual().start(() -> {
            while (true) {
                Socket accepted;
                try {
                    accepted = server.accept();
                } catch (IOException closed) {
                    return;
                }
                Thread.ofVirtual().start(() -> {
                    try (var socket = accepted) {
                        var count = socket.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                        socket.getOutputStream().write(("got " + count).getBytes(StandardCharsets.UTF_8));
                    } catch (IOException gone) {
                        // the test fails on what it reads
                    }
                });
            }
        });
        return server.getLocalPort();
    }

    private ServerSocket service() throws IOException {
        var server = new ServerSocket(0, 50, InetAddress.ofLiteral(LOOPBACK));
        services.add(server);
        return server;
    }

    /** A port of the loopback that nothing listens on as the test starts. */
    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.ofLiteral(LOOPBACK))) {
            return socket.getLocalPort();
        }
    }

    /** A listed port, relayed to {@code target}. */
    private int exposed(int target) throws IOException {
        var port = freePort();
        targetOf.put(port, target);
        return port;
    }

    private static Socket connect(int port) throws IOException {
        var socket = new Socket();
        socket.connect(new InetSocketAddress(LOOPBACK, port), 5_000);
        socket.setSoTimeout(10_000);
        return socket;
    }

    @Test
    void bytesReachTheServiceAndItsAnswerComesBackAfterTheGuestsEndOfStream() throws Exception {
        var port = exposed(countingService());
        relay.listen(List.of(port));
        assertEquals(Set.of(port), relay.bound());
        try (var guest = connect(port)) {
            guest.getOutputStream().write("hello relay".getBytes(StandardCharsets.UTF_8));
            // A relay that closed on the guest's end of stream would lose the answer
            guest.shutdownOutput();
            assertEquals("got 11", new String(guest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aLargeStreamReachesASlowReaderWhole() throws Exception {
        var server = service();
        var bytes = 16 * 1024 * 1024;
        var payload = new byte[bytes];
        new java.util.Random(2117).nextBytes(payload);
        Thread.ofVirtual().start(() -> {
            try (var socket = server.accept()) {
                socket.getOutputStream().write(payload);
            } catch (IOException ignored) {
                // the test fails on what it reads
            }
        });
        var port = exposed(server.getLocalPort());
        relay.listen(List.of(port));
        try (var guest = connect(port)) {
            // Slow to start reading, so the relay has to stop its writer rather than buffer
            Thread.sleep(500);
            var in = guest.getInputStream();
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[4096];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                total += read;
            }
            assertEquals(bytes, total);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload), digest.digest());
        }
    }

    @Test
    void nothingOnTheLoopbackPortClosesTheGuestsConnection() throws Exception {
        var port = exposed(freePort());
        relay.listen(List.of(port));
        try (var guest = connect(port)) {
            assertEquals(-1, guest.getInputStream().read());
        }
    }

    @Test
    void aReloadAddsAndRemovesPortsAndLeavesUnchangedOnesConnected() throws Exception {
        var service = countingService();
        var kept = exposed(service);
        var added = exposed(service);
        relay.listen(List.of(kept));
        try (var open = connect(kept)) {
            open.getOutputStream().write("before".getBytes(StandardCharsets.UTF_8));

            relay.listen(List.of(kept, added));
            assertEquals(Set.of(kept, added), relay.bound());
            try (var guest = connect(added)) {
                guest.shutdownOutput();
                assertEquals("got 0", new String(guest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }

            open.getOutputStream().write(" and after".getBytes(StandardCharsets.UTF_8));
            open.shutdownOutput();
            assertEquals("got 16", new String(open.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                    "a connection on a port still listed is not touched by the reload");
        }

        try (var open = connect(added)) {
            relay.listen(List.of(kept));
            assertEquals(Set.of(kept), relay.bound());
            assertThrows(IOException.class, () -> connect(added), "an unlisted port is closed");
            // Its connections go with it: nothing an unlisted port let in stays relayed
            var read = readOrReset(open);
            assertEquals(-1, read);
        }
    }

    /**
     * Once {@code listen} has unlisted a port, nothing completes a connection to it: the socket is
     * released only when the thread blocked in its accept wakes, and until then the kernel went on
     * accepting connections, which the relay then closed (seen on CI as an empty answer).
     */
    @Test
    void anUnlistedPortRefusesConnectionsAsSoonAsListenReturns() throws Exception {
        var service = countingService();
        for (int i = 0; i < 50; i++) {
            var port = exposed(service);
            relay.listen(List.of(port));
            // As a reload does it: just after a connection, with the accept loop back in accept()
            try (var guest = connect(port)) {
                guest.shutdownOutput();
                assertEquals("got 0", new String(guest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            relay.listen(List.of());
            var round = i;
            assertThrows(IOException.class, () -> connect(port), () -> "round " + round + ": port " + port + " still accepts");
        }
    }

    @Test
    void aPortHeldBySomethingElseIsSkippedAndTheOthersServe() throws Exception {
        var held = service();
        var heldPort = held.getLocalPort();
        targetOf.put(heldPort, freePort());
        var port = exposed(countingService());
        relay.listen(List.of(heldPort, port));
        assertEquals(Set.of(port), relay.bound());
        assertEquals(Set.of(heldPort), relay.unbound().keySet());
        try (var guest = connect(port)) {
            guest.shutdownOutput();
            assertEquals("got 0", new String(guest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }

        // Freed, it is bound on the next reload
        held.close();
        relay.listen(List.of(heldPort, port));
        assertEquals(Set.of(heldPort, port), relay.bound());
        assertEquals(Map.of(), relay.unbound());
    }

    @Test
    void aStoppedRelayListensNoMore() throws Exception {
        var port = exposed(countingService());
        relay.listen(List.of(port));
        relay.stop();
        assertThrows(IOException.class, () -> connect(port));
        relay.listen(List.of(port));
        assertEquals(Set.of(), relay.bound());
    }

    /** End of stream, or a reset taken as one: either way the connection is gone. */
    private static int readOrReset(Socket socket) {
        try {
            return socket.getInputStream().read();
        } catch (IOException reset) {
            return -1;
        }
    }
}
