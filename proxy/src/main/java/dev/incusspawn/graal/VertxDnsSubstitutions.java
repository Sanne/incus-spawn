package dev.incusspawn.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import io.vertx.core.Vertx;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.dns.impl.DefaultAddressResolverProvider;
import io.vertx.core.spi.dns.AddressResolverProvider;

// Quarkus runs Vert.x with the blocking JDK resolver (vertx.disableDnsResolver=true, the
// quarkus.vertx.resolver default), but Vert.x 5 reads that property at run time, so the native
// image still links Netty's async DNS resolver: ~0.6 MiB of code we never run. Quarkus 3 cut it
// with the same substitution on Vert.x 4's ResolverProvider, which its Vert.x 5 port dropped.
// Safe: the proxy never enables the async resolver.

@TargetClass(AddressResolverProvider.class)
final class Target_io_vertx_core_spi_dns_AddressResolverProvider {

    @Substitute
    public static AddressResolverProvider factory(Vertx vertx, AddressResolverOptions options) {
        return new DefaultAddressResolverProvider();
    }
}
