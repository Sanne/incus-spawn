package dev.incusspawn.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTransportTest {

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void nativeTransportEnabled() {
        var vertx = Vertx.vertx(new VertxOptions().setPreferNativeTransport(true));
        try {
            assertTrue(vertx.isNativeTransportEnabled(),
                    "Native transport (epoll/kqueue) should be enabled on " + System.getProperty("os.name"));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().join();
        }
    }
}
