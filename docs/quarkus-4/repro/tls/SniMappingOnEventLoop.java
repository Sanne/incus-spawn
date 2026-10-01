import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PfxOptions;

import javax.net.ssl.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Issue: a TLS server's SNI key-manager mapper (KeyCertOptions.keyManagerFactoryMapper) runs on
 * the event loop in Vert.x 5 by default. Vert.x 4.5 always ran it on a worker thread, and
 * ServerSslContextProvider#serverNameAsyncMapping's javadoc still says it does.
 *
 * Exit code 0 = mapper ran off the event loop (expected), 1 = it ran on the event loop.
 * Compiles and runs unchanged against vertx-core 4.5.x and 5.x.
 */
public class SniMappingOnEventLoop {

    public static void main(String[] args) throws Exception {
        var store = selfSigned("sni.example");
        var vertx = Vertx.vertx();
        try {
            String dflt = run(vertx, store, false);
            String worker = run(vertx, store, true);
            System.out.println("Vert.x " + vertxVersion());
            System.out.println("default SSL engine options        : mapper ran on " + dflt);
            System.out.println("JdkSSLEngineOptions.useWorkerThread: mapper ran on " + worker);
            boolean bug = dflt.startsWith("EVENT LOOP");
            System.out.println(bug ? "RESULT: BUG - SNI mapper runs on the event loop by default"
                                   : "RESULT: OK - SNI mapper runs off the event loop");
            System.exit(bug ? 1 : 0);
        } finally {
            vertx.close();
        }
    }

    static String run(Vertx vertx, Path store, boolean useWorkerThread) throws Exception {
        var pfx = new PfxOptions().setPath(store.toString()).setPassword("changeit");
        var kmf = pfx.getKeyManagerFactory(vertx);
        List<String> calls = new CopyOnWriteArrayList<>();
        KeyCertOptions recording = new KeyCertOptions() {
            public KeyCertOptions copy() { return this; }
            public KeyManagerFactory getKeyManagerFactory(Vertx v) { return kmf; }
            public Function<String, KeyManagerFactory> keyManagerFactoryMapper(Vertx v) {
                return name -> {
                    calls.add((Context.isOnEventLoopThread() ? "EVENT LOOP" : "non-event-loop")
                            + " (" + Thread.currentThread().getName() + ")");
                    return kmf;
                };
            }
        };
        var options = new NetServerOptions().setSsl(true).setSni(true).setKeyCertOptions(recording);
        if (useWorkerThread) options.setSslEngineOptions(new JdkSSLEngineOptions().setUseWorkerThread(true));
        var server = vertx.createNetServer(options).connectHandler(s -> s.close());
        int port = server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS).actualPort();
        try {
            handshake(port, "sni.example");
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        return calls.isEmpty() ? "(never called)" : calls.get(0);
    }

    static void handshake(int port, String sni) throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        try (var s = (SSLSocket) tls.getSocketFactory().createSocket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            var p = s.getSSLParameters();
            p.setServerNames(List.of(new SNIHostName(sni)));
            s.setSSLParameters(p);
            s.startHandshake();
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
