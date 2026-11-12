package dev.incusspawn.proxy;

import dev.incusspawn.Platform;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Vert.x on the socket transport the proxy runs on: Netty's native epoll on Linux (#475).
 * A plain {@code Vertx.vertx()} runs on Java NIO, whose errors differ: a port in use is a
 * {@link java.net.BindException} there, and a {@code NativeIoException} on epoll.
 */
final class ProxyTransport {

    private ProxyTransport() {}

    static Vertx vertx() {
        var vertx = Vertx.vertx(new VertxOptions().setPreferNativeTransport(true));
        if (!Platform.isMacOS()) {
            assertTrue(vertx.isNativeTransportEnabled(),
                    "epoll did not load: " + vertx.unavailableNativeTransportCause());
        }
        return vertx;
    }
}
