package dev.incusspawn.vm;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Talks to the in-VM control agent over its dedicated vsock port (exposed on the host as a
 * Unix socket). The agent speaks one verb per connection: we write "{@code <verb>\n}", read
 * the response until EOF, and the connection closes. Independent of the Incus tunnel, so it
 * works even when the forwarder is wedged.
 *
 * The agent only honours a fixed allowlist of verbs (see appliance/.../isx-agent); this
 * client exposes typed wrappers for them.
 */
public final class VmAgentClient {

    private static final int TIMEOUT_SECONDS = 5;

    private VmAgentClient() {}

    /** Send a raw verb; returns the trimmed response, or empty if the agent is unreachable. */
    public static Optional<String> send(String verb) {
        return send(Environment.vmAgentSocket(), verb);
    }

    /** Package-private overload so tests can point at a fake agent socket. */
    static Optional<String> send(Path socketPath, String verb) {
        if (!Files.exists(socketPath)) return Optional.empty();
        var addr = UnixDomainSocketAddress.of(socketPath.toString());
        try (var ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            var watchdog = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(TIMEOUT_SECONDS * 1000L);
                    ch.close();
                } catch (InterruptedException | IOException ignored) {}
            });
            try {
                ch.connect(addr);
                var out = Channels.newOutputStream(ch);
                out.write((verb + "\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                var body = Channels.newInputStream(ch).readAllBytes();
                return Optional.of(new String(body, StandardCharsets.UTF_8).strip());
            } finally {
                watchdog.interrupt();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** In-guest count of forwarder socat processes, or empty if unavailable/unparseable. */
    public static OptionalInt socatCount() {
        var resp = send("socat-count");
        if (resp.isEmpty()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(resp.get().strip()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** Restart the in-VM forwarder without rebooting the VM. Returns true on confirmed success. */
    public static boolean restartForwarder() {
        return send("forwarder-restart").map("restarted"::equals).orElse(false);
    }

    /** Whether the agent is reachable at all. */
    public static boolean ping() {
        return send("ping").map("ok"::equals).orElse(false);
    }
}
