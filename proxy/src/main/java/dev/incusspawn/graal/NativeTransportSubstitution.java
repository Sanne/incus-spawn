package dev.incusspawn.graal;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import io.vertx.core.VertxOptions;
import io.vertx.core.impl.transports.JDKTransport;
import io.vertx.core.spi.transport.Transport;

// Quarkus unconditionally substitutes VertxBuilder.nativeTransport() to return
// JDKTransport.INSTANCE, defeating epoll/kqueue in native images.
// We bypass that by substituting initTransport() instead — a different method
// on the same target class, which GraalVM SVM allows.
//
// Transport classes are instantiated reflectively to avoid GraalVM linking the
// underlying Netty classes (e.g. io.netty.channel.kqueue.KQueue) on platforms
// where they aren't on the classpath.

@TargetClass(className = "io.vertx.core.impl.VertxBuilder")
final class Target_VertxBuilder_Transport {

    @Alias
    private Transport transport;

    @Alias
    private VertxOptions options;

    @Substitute
    private void initTransport() {
        if (transport != null) {
            return;
        }
        if (options.getPreferNativeTransport()) {
            Transport t = NativeTransportProbe.probe();
            if (t != null) {
                transport = t;
                return;
            }
        }
        transport = JDKTransport.INSTANCE;
    }
}
