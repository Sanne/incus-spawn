package dev.incusspawn.incus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * Lightweight TCP-to-Unix-socket proxy. Listens on {@code localhost:<port>}
 * and forwards each connection to a Unix domain socket (the vfkit vsock
 * endpoint). This allows the standard {@link java.net.http.HttpClient} and
 * WebSocket APIs to reach the Incus HTTPS API inside the VM without any
 * TCP connection to the VM subnet — bypassing network-layer socket filters
 * such as Cisco AnyConnect.
 */
public final class UnixSocketProxy {

    private static volatile int activePort;

    private UnixSocketProxy() {}

    /**
     * Start the proxy if not already running. Returns the localhost port.
     */
    public static synchronized int startIfNeeded(Path unixSocketPath) throws IOException {
        if (activePort > 0) return activePort;

        var serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress("localhost", 0));
        int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

        Thread.ofVirtual().name("vsock-proxy-accept").start(() -> {
            try {
                while (serverChannel.isOpen()) {
                    var client = serverChannel.accept();
                    Thread.ofVirtual().name("vsock-proxy-conn").start(() ->
                            bridge(client, unixSocketPath));
                }
            } catch (IOException e) {
                if (serverChannel.isOpen()) {
                    System.err.println("vsock proxy accept error: " + e.getMessage());
                }
            }
        });

        activePort = port;
        return port;
    }

    private static void bridge(SocketChannel client, Path unixSocketPath) {
        try (client) {
            try (var upstream = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                upstream.connect(UnixDomainSocketAddress.of(unixSocketPath));
                var t = Thread.ofVirtual().name("vsock-proxy-up").start(() ->
                        pipe(upstream, client));
                pipe(client, upstream);
                t.join();
            }
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private static void pipe(SocketChannel from, SocketChannel to) {
        var buf = ByteBuffer.allocate(16384);
        try {
            while (from.read(buf) >= 0) {
                buf.flip();
                while (buf.hasRemaining()) to.write(buf);
                buf.clear();
            }
        } catch (IOException ignored) {
        } finally {
            try { to.shutdownOutput(); } catch (IOException ignored) {}
        }
    }
}
