package dev.incusspawn.vm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the VmAgentClient one-verb-per-connection protocol against a fake agent socket
 * that mimics the in-VM isx-agent dispatch — no VM required.
 */
class VmAgentClientTest {

    private Path dir;
    private Path sock;
    private ServerSocketChannel server;
    private Thread serverThread;
    private final AtomicReference<String> lastVerb = new AtomicReference<>();

    @BeforeEach
    void startFakeAgent() throws IOException {
        dir = Files.createTempDirectory("isxagt");
        sock = dir.resolve("a.sock"); // keep path short (macOS sun_path limit)
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sock.toString()));
        serverThread = Thread.ofVirtual().start(this::serve);
    }

    @AfterEach
    void stop() throws IOException, InterruptedException {
        try { server.close(); } catch (IOException ignored) {}
        serverThread.join(2000);
        Files.deleteIfExists(sock);
        Files.deleteIfExists(dir);
    }

    private void serve() {
        while (server.isOpen()) {
            try (var conn = server.accept()) {
                var in = Channels.newInputStream(conn);
                var verb = readLine(in);
                lastVerb.set(verb);
                var resp = switch (verb) {
                    case "ping" -> "ok";
                    case "socat-count" -> "5";
                    case "forwarder-restart" -> "restarted";
                    default -> "error: unknown verb";
                };
                conn.write(java.nio.ByteBuffer.wrap((resp + "\n").getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                return; // server closed
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') sb.append((char) c);
        return sb.toString();
    }

    @Test
    @Timeout(10)
    void pingRoundTrips() {
        assertTrue(VmAgentClient.send(sock, "ping").isPresent());
        assertTrue(VmAgentClient.send(sock, "ping").map("ok"::equals).orElse(false));
        assertEquals("ping", lastVerb.get());
    }

    @Test
    @Timeout(10)
    void socatCountParsesNumber() {
        var n = VmAgentClient.send(sock, "socat-count");
        assertEquals("5", n.orElse(null));
    }

    @Test
    @Timeout(10)
    void unknownVerbGetsError() {
        assertEquals("error: unknown verb", VmAgentClient.send(sock, "bogus").orElse(null));
    }

    @Test
    @Timeout(10)
    void missingSocketReturnsEmpty() {
        assertTrue(VmAgentClient.send(dir.resolve("nope.sock"), "ping").isEmpty());
    }
}
