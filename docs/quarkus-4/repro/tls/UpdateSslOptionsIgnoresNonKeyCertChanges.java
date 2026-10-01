import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PfxOptions;

import javax.net.ssl.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Issue: NetServer/HttpServer#updateSSLOptions(options) (force = false) ignores a change to the
 * enabled protocols (likewise cipher suites, client auth) when the key/cert options are equal:
 * it returns false and keeps serving the old configuration. Its javadoc says the update is
 * skipped only when the options object is equal; Vert.x 4.5 compared the whole SSLOptions.
 *
 * The server listens with the defaults (TLSv1.2 + TLSv1.3); the update keeps the same key/cert
 * and restricts protocols to TLSv1.2. A JDK client offering both then reports what it got.
 *
 * Exit code 0 = update applied (expected), 1 = silently ignored.
 * Compiles and runs unchanged against vertx-core 4.5.x (SSLOptions) and 5.x (ServerSSLOptions);
 * the options class is picked reflectively for that reason only.
 */
public class UpdateSslOptionsIgnoresNonKeyCertChanges {

    public static void main(String[] args) throws Exception {
        var store = selfSigned("update.example");
        var vertx = Vertx.vertx();
        try {
            var plain = run(vertx, store, false);
            var forced = run(vertx, store, true);
            System.out.println("Vert.x " + vertxVersion());
            System.out.println("updateSSLOptions(TLSv1.2 only, force=false): " + plain);
            System.out.println("updateSSLOptions(TLSv1.2 only, force=true) : " + forced);
            boolean bug = !plain.endsWith("TLSv1.2");
            System.out.println(bug ? "RESULT: BUG - protocol change ignored without force"
                                   : "RESULT: OK - protocol change applied");
            System.exit(bug ? 1 : 0);
        } finally {
            vertx.close();
        }
    }

    static String run(Vertx vertx, Path store, boolean force) throws Exception {
        KeyCertOptions keyCert = new PfxOptions().setPath(store.toString()).setPassword("changeit");
        NetServer server = vertx.createNetServer(new NetServerOptions().setSsl(true).setKeyCertOptions(keyCert))
                .connectHandler(s -> s.close());
        int port = server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS).actualPort();
        try {
            Class<?> optionsClass;
            try {
                optionsClass = Class.forName("io.vertx.core.net.ServerSSLOptions"); // 5.x
            } catch (ClassNotFoundException e) {
                optionsClass = Class.forName("io.vertx.core.net.SSLOptions");       // 4.x
            }
            Object update = optionsClass.getConstructor().newInstance();
            optionsClass.getMethod("setKeyCertOptions", KeyCertOptions.class).invoke(update, keyCert);
            optionsClass.getMethod("setEnabledSecureTransportProtocols", Set.class).invoke(update, Set.of("TLSv1.2"));
            @SuppressWarnings("unchecked")
            var fut = (Future<Boolean>) NetServer.class.getMethod("updateSSLOptions", optionsClass, boolean.class)
                    .invoke(server, update, force);
            boolean updated = fut.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            return "returned " + updated + ", client negotiated " + handshake(port);
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    static String handshake(int port) throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        try (var s = (SSLSocket) tls.getSocketFactory().createSocket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            s.startHandshake();
            return s.getSession().getProtocol();
        }
    }

    static String vertxVersion() throws Exception {
        try (var in = Vertx.class.getResourceAsStream("/META-INF/vertx/vertx-version.txt")) {
            return in == null ? "?" : new String(in.readAllBytes()).trim();
        }
    }

    /** A PKCS12 keystore (password "changeit") holding a self-signed EC cert for {@code host}. */
    static Path selfSigned(String host) throws Exception {
        var dir = Files.createTempDirectory("vertx-repro");
        var store = dir.resolve("server.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=" + host, "-ext", "SAN=dns:" + host,
                "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "changeit", "-keypass", "changeit").inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("keytool failed");
        return store;
    }
}
