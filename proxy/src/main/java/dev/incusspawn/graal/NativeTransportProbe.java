package dev.incusspawn.graal;

import io.vertx.core.spi.transport.Transport;

final class NativeTransportProbe {

    private static final String[][] TRANSPORTS = {
            {"io.netty.channel.epoll.Epoll", "io.vertx.core.impl.transports.EpollTransport"},
            {"io.netty.channel.kqueue.KQueue", "io.vertx.core.impl.transports.KQueueTransport"},
    };

    static Transport probe() {
        for (var entry : TRANSPORTS) {
            try {
                var nettyClass = Class.forName(entry[0]);
                if ((boolean) nettyClass.getMethod("isAvailable").invoke(null)) {
                    return (Transport) Class.forName(entry[1])
                            .getDeclaredConstructor()
                            .newInstance();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
