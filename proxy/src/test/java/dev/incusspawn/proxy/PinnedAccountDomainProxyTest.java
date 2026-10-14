package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A domain only a named account resolves an entry for is served as a credential domain for an
 * instance pinned to that account: it is intercepted for every account, so it must not be relayed
 * as a stray subdomain just because the default account has no entry for it.
 */
class PinnedAccountDomainProxyTest {

    private static final String DOMAIN = "api.xt.example.test";

    @TempDir
    static Path tempHome;
    static String origHome;
    static Vertx vertx;
    static HttpServer upstream;
    static HttpClient client;
    static MitmProxy proxy;
    static final Map<String, String> received = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        var configDir = Files.createDirectories(tempHome.resolve(".config/incus-spawn"));
        // The credential needs both keys; only the account 'full' holds the second
        Files.createDirectories(configDir.resolve("tools"));
        Files.writeString(configDir.resolve("tools/xt.yaml"), """
                name: xt
                description: "A tool whose entry needs a per-account setting"
                env:
                  - name: XT_TOKEN
                    value: xt-placeholder
                proxy:
                  config-namespace: xt
                  placeholders:
                    - env: XT_TOKEN
                      prefix: xt-
                  configuration:
                    token:
                      config-path: token
                      description: "XT token"
                      secret: true
                    org:
                      config-path: org
                      description: "XT organisation"
                  auth:
                    - domains:
                        - %s
                      type: bearer
                      token: "${token}/${org}"
                """.formatted(DOMAIN));
        Files.writeString(configDir.resolve("config.yaml"), """
                xt:
                  accounts:
                    plain:
                      token: "xt-plain"
                    full:
                      token: "xt-full"
                      org: "acme"
                  default: plain
                """);

        vertx = Vertx.vertx();
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(CertificateAuthority.loadOrCreate(), DOMAIN)))
                .requestHandler(req -> {
                    var auth = req.getHeader("Authorization");
                    received.put(req.path(), auth == null ? "" : auth);
                    req.response().end("{}");
                });
        int upstreamPort = NpmCacheProxyTest.listen(upstream);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        ContainerTls.startInBackground(proxy);
        proxy.overrideUpstream(DOMAIN, "127.0.0.1", upstreamPort);
        var registry = new InstanceRegistry(new IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return io.hyperfoil.tools.jjq.value.JqValues.parse("""
                        [{"name":"acme-1","config":{"user.incus-spawn.static-ip":"127.0.0.1",
                          "user.incus-spawn.account.xt":"full",
                          "user.incus-spawn.instance-proof-sha256":%s}}]
                        """.formatted(io.hyperfoil.tools.jjq.value.JqValues.toPrettyJsonString(
                                io.hyperfoil.tools.jjq.value.JqString.of(ProofVerifier.stamp(ProofTestSupport.SECRET, ProofToken.declared())))));
            }
        });
        registry.refresh();
        proxy.useInstanceRegistry(registry);
        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false).setKeepAlive(false));
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void anInstancePinnedToTheAccountThatResolvesTheEntryGetsItsCredential() throws Exception {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(DOMAIN)
                .setPort(443)
                .setURI("/v1/me");
        int status = client.request(options)
                .compose(req -> req.putHeader("Authorization", "Bearer " + ProofTestSupport.token("xt", "xt-")).send())
                .compose(resp -> resp.body().map(b -> resp.statusCode()))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(200, status);
        assertEquals("Bearer xt-full/acme", received.get("/v1/me"));
    }
}
