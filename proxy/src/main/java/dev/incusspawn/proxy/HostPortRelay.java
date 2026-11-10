package dev.incusspawn.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;

/**
 * {@code exposed-host-ports:} (#2117): one TCP listener per listed port on the address the proxy
 * listens on, each connection piped byte for byte to the same port on this host's loopback.
 * <p>
 * It shares nothing with interception, host routes or credentials: no TLS, no headers, no caller
 * identity, nothing injected. Every instance that reaches the proxy's address reaches these ports
 * (design/proxy.md "Exposed host ports"); a proxy-only instance does not, since its ACL lets it
 * reach only isx's own ports there, which a listed port can never be.
 * <p>
 * Blocking sockets on virtual threads, two per connection, rather than Vert.x streams: a socket
 * shut down for writing is what carries one side's end of stream to the other while the reverse
 * direction still flows, which Vert.x's {@code NetSocket} cannot do (it closes on the first
 * end). A blocking write is the backpressure: a slow reader stops its writer, and nothing is
 * buffered beyond the socket buffers. There is no idle timeout, since a model can think for a long
 * time between two tokens; keepalive drops a peer that is gone.
 */
final class HostPortRelay {

    /** How long a connect to loopback may take: answered at once, or by nothing. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private static final int BUFFER_BYTES = 64 * 1024;

    /** How long closing a listener waits for its accepting thread to let go of the port. */
    private static final long ACCEPT_JOIN_SECONDS = 5;

    /** How long a listener waits after a failed accept before it accepts again. */
    private static final long ACCEPT_RETRY_MILLIS = 1_000;

    private final String bindAddress;
    private final List<String> targets;
    private final IntUnaryOperator targetPort;
    /** Who may connect: instances alone, never a peer beside the host ({@link RelayCallers}). */
    private final RelayCallers callers;

    /** Listening, by listed port. */
    private final Map<Integer, Listener> listeners = new LinkedHashMap<>();
    /** Listed but held by something else on {@link #bindAddress}, by port, with why. */
    private final Map<Integer, String> unbound = new LinkedHashMap<>();
    private boolean stopped;

    /** Relay on {@code bindAddress} to the same port on {@code 127.0.0.1}, else {@code ::1}. */
    HostPortRelay(String bindAddress) {
        this(bindAddress, List.of("127.0.0.1", "::1"), port -> port, RelayCallers.forThisHost(bindAddress));
    }

    /**
     * Tests only: connect to {@code targetPort} of the port a guest called, so the relay and the
     * service it reaches can both be on one loopback address.
     */
    HostPortRelay(String bindAddress, List<String> targets, IntUnaryOperator targetPort, RelayCallers callers) {
        this.bindAddress = bindAddress;
        this.targets = List.copyOf(targets);
        this.targetPort = targetPort;
        this.callers = callers;
    }

    /**
     * Listen on exactly {@code ports}: close the listeners, and the connections, of ports no
     * longer listed, and bind those that are not bound yet, a port that failed to bind before
     * included. Ports listed before and still listed are left alone, connections and all. A port
     * that cannot be bound is said and skipped; it never stops the others or the proxy.
     */
    synchronized void listen(Collection<Integer> ports) {
        if (stopped) return;
        var wanted = new TreeSet<>(ports);
        var removed = new ArrayList<Listener>();
        for (var it = listeners.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (wanted.contains(entry.getKey())) continue;
            removed.add(entry.getValue());
            it.remove();
            ProxyLog.info("No longer exposing host port " + entry.getKey());
        }
        closeAll(removed);
        unbound.keySet().retainAll(wanted);
        for (var port : wanted) {
            if (listeners.containsKey(port)) continue;
            try {
                listeners.put(port, bind(port));
                unbound.remove(port);
                ProxyLog.info("Exposing host port " + port + " on " + bindAddress + " (relayed to loopback)");
            } catch (IOException | RuntimeException e) {
                unbound.put(port, String.valueOf(e.getMessage()));
                ProxyLog.warn("Could not expose host port " + port + ": " + bindAddress + ":" + port
                        + " could not be bound (" + e.getMessage() + ")",
                        "If something holds it, instances reach that, not this host's loopback: a service listening"
                                + " on every address, or another service on that port.");
            }
        }
    }

    /** The listed ports that are listening. */
    synchronized Set<Integer> bound() {
        return Set.copyOf(listeners.keySet());
    }

    /** The listed ports that could not be bound, with why, for {@code isx doctor}. */
    synchronized Map<Integer, String> unbound() {
        return Map.copyOf(unbound);
    }

    /** Close every listener and connection; nothing listens again after it. */
    synchronized void stop() {
        stopped = true;
        closeAll(new ArrayList<>(listeners.values()));
        listeners.clear();
        unbound.clear();
    }

    /** Close {@code closing} together: every server first, then one shared wait for all their threads. */
    private static void closeAll(List<Listener> closing) {
        closing.forEach(Listener::beginClose);
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(ACCEPT_JOIN_SECONDS);
        closing.forEach(listener -> listener.awaitClosed(deadline));
    }

    private Listener bind(int port) throws IOException {
        var server = new ServerSocket();
        try {
            server.bind(new InetSocketAddress(InetAddress.ofLiteral(bindAddress), port));
        } catch (IOException | RuntimeException e) {
            server.close();
            throw e;
        }
        var listener = new Listener(port, server);
        listener.acceptor = Thread.ofVirtual().name("host-port-" + port).start(listener::accept);
        return listener;
    }

    /** One listed port's server socket and the connections it accepted. */
    private final class Listener {
        private final int port;
        private final ServerSocket server;
        private final Set<Socket> open = ConcurrentHashMap.newKeySet();
        private volatile boolean closed;
        /** The thread blocked in {@link ServerSocket#accept()}, which {@link #awaitClosed} waits out. */
        private volatile Thread acceptor;

        Listener(int port, ServerSocket server) {
            this.port = port;
            this.server = server;
        }

        void accept() {
            while (!closed) {
                Socket guest;
                try {
                    guest = server.accept();
                } catch (IOException e) {
                    if (closed) return;
                    // Out of file descriptors, say: a reload would not rebind a port still listed,
                    // so keep the listener and try again rather than leave guests in its backlog.
                    ProxyLog.warn("Exposed host port " + port + " could not accept: " + e.getMessage());
                    try {
                        Thread.sleep(ACCEPT_RETRY_MILLIS);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    continue;
                }
                open.add(guest);
                // Closed after the add, so close() either saw it or this sees closed.
                if (closed) {
                    closeQuietly(guest);
                    return;
                }
                Thread.ofVirtual().name("host-port-" + port + "-connect").start(() -> relay(guest));
            }
        }

        private void relay(Socket guest) {
            // Before loopback is touched: a peer on a network beside the host that routes to the
            // proxy's address must not become a loopback caller (the weak host model)
            if (!callers.admits(guest.getInetAddress())) {
                ProxyLog.trace("Exposed host port " + port + ": refused " + guest.getInetAddress().getHostAddress()
                        + ", which is no instance's address");
                open.remove(guest);
                closeQuietly(guest);
                return;
            }
            var host = connect(targetPort.applyAsInt(port));
            if (host == null) {
                open.remove(guest);
                closeQuietly(guest);
                return;
            }
            open.add(host);
            if (closed) {
                closeBoth(guest, host);
                return;
            }
            try {
                guest.setKeepAlive(true);
                host.setKeepAlive(true);
                guest.setTcpNoDelay(true);
                host.setTcpNoDelay(true);
            } catch (IOException ignored) {
                // Options only: the relay works without them.
            }
            var remaining = new java.util.concurrent.atomic.AtomicInteger(2);
            Runnable done = () -> {
                if (remaining.decrementAndGet() == 0) closeBoth(guest, host);
            };
            Thread.ofVirtual().name("host-port-" + port + "-up").start(() -> pipe(guest, host, done));
            pipe(host, guest, done);
        }

        /** Loopback at the target port, 127.0.0.1 first, or null when nothing answers there. */
        private Socket connect(int target) {
            for (var address : targets) {
                var socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(InetAddress.ofLiteral(address), target), CONNECT_TIMEOUT_MILLIS);
                    return socket;
                } catch (IOException e) {
                    closeQuietly(socket);
                }
            }
            // Nothing listening on loopback is the service being down, not the relay failing.
            ProxyLog.trace("Exposed host port " + port + ": nothing answers on loopback port " + target
                    + "; closing the instance's connection");
            return null;
        }

        /**
         * Copy {@code from} to {@code to} until end of stream, then pass the end on by shutting
         * {@code to} down for writing, so the other direction keeps flowing. A failure either way
         * closes both sides: a reset is not an end of stream.
         */
        private void pipe(Socket from, Socket to, Runnable done) {
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                var buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                to.shutdownOutput();
                done.run();
            } catch (IOException e) {
                closeBoth(from, to);
            }
        }

        private void closeBoth(Socket a, Socket b) {
            open.remove(a);
            open.remove(b);
            closeQuietly(a);
            closeQuietly(b);
        }

        /**
         * Stop accepting: close the server socket and wake the accepting thread. The interrupt
         * also wakes one sleeping out a failed accept. {@link #awaitClosed} finishes the close.
         */
        void beginClose() {
            closed = true;
            closeQuietly(server);
            var thread = acceptor;
            if (thread != null) thread.interrupt();
        }

        /**
         * Wait for the accepting thread to exit, by {@code deadline} ({@link System#nanoTime()}),
         * then close the connections. A socket closed while a virtual thread is blocked in
         * accept() is released only once that thread wakes: until then the kernel still completes
         * connections to the port. So an unlisted port refuses connections once this returns.
         */
        void awaitClosed(long deadline) {
            var thread = acceptor;
            if (thread != null) {
                try {
                    var left = Math.max(0, deadline - System.nanoTime());
                    if (!thread.join(java.time.Duration.ofNanos(left))) {
                        ProxyLog.warn("Exposed host port " + port + " did not stop accepting in time");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (var socket : new ArrayList<>(open)) closeQuietly(socket);
            open.clear();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing: nothing to do about a failure.
        }
    }
}
