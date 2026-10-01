import io.vertx.core.Vertx;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.ConnectOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PfxOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Issue (security): one NetClient, per-connect ClientSSLOptions that differ only in
 * hostnameVerificationAlgorithm share one cached SSL context provider, so whichever
 * connect came first decides whether hostname verification happens for all later ones.
 * The cache key (SslContextManager.ConfigKey) holds key/cert, trust and CRLs only.
 *
 * The server's certificate is for "other.example" (trusted), and the client connects to
 * 127.0.0.1 without SNI, so "HTTPS" verification must fail and "" must succeed, in any order.
 *
 * Exit code 0 = each connect honours its own algorithm (expected), 1 = first one wins.
 * Vert.x 5 only: per-connect ClientSSLOptions do not exist in 4.5.
 */
public class ClientSslCacheIgnoresHostnameVerification {

    public static void main(String[] args) throws Exception {
        var store = selfSigned("other.example");
        var vertx = Vertx.vertx();
        try {
            var pfx = new PfxOptions().setPath(store.toString()).setPassword("changeit");
            var server = vertx.createNetServer(new NetServerOptions().setSsl(true).setKeyCertOptions(pfx))
                    .connectHandler(s -> s.close());
            int port = server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).actualPort();

            System.out.println("Vert.x " + vertxVersion());
            boolean bug = false;
            for (var order : new String[][]{{"", "HTTPS"}, {"HTTPS", ""}}) {
                NetClient client = vertx.createNetClient(); // fresh client, fresh cache
                var line = new StringBuilder("fresh client:");
                for (var algo : order) {
                    boolean ok = connect(client, port, pfx, algo);
                    boolean expected = algo.isEmpty();
                    if (ok != expected) bug = true;
                    line.append(String.format("  algo=%-7s -> %-9s (expected %s)",
                            "'" + algo + "'", ok ? "connected" : "rejected", expected ? "connected" : "rejected"));
                }
                System.out.println(line);
                client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            System.out.println(bug ? "RESULT: BUG - the first connect's hostname verification applies to later ones"
                                   : "RESULT: OK - each connect honours its own hostname verification");
            System.exit(bug ? 1 : 0);
        } finally {
            vertx.close();
        }
    }

    static boolean connect(NetClient client, int port, PfxOptions trust, String algo) {
        var options = new ConnectOptions().setHost("127.0.0.1").setPort(port).setSsl(true)
                .setSslOptions(new ClientSSLOptions().setTrustOptions(trust).setHostnameVerificationAlgorithm(algo));
        try {
            client.connect(options).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).close();
            return true;
        } catch (Exception e) {
            return false;
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
