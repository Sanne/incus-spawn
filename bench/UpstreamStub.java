import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;

/**
 * Stand-in for Maven Central during {@code bench/run.sh --load=maven}: serves one
 * artifact, with the {@code X-Checksum-SHA1} header Central sends, and its
 * {@code .sha1}. The proxy confirms every cache hit with upstream, so without a
 * stub the benchmark would send its whole load to the real Central, and measure
 * Central's latency rather than the proxy's.
 *
 * <p>Usage: {@code java UpstreamStub.java <port> <keystore.p12> <password> <url-path> <payload>}
 */
public class UpstreamStub {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        char[] password = args[2].toCharArray();
        String artifactPath = args[3];
        byte[] artifact = Files.readAllBytes(Path.of(args[4]));
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(artifact));
        byte[] sha1Body = sha1.getBytes(StandardCharsets.US_ASCII);

        var keyStore = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(args[1])) {
            keyStore.load(in, password);
        }
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        var tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);

        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 1024);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            try (exchange) {
                var path = exchange.getRequestURI().getRawPath();
                byte[] body;
                if (path.equals(artifactPath)) {
                    exchange.getResponseHeaders().set("X-Checksum-SHA1", sha1);
                    body = artifact;
                } else if (path.equals(artifactPath + ".sha1")) {
                    body = sha1Body;
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("ready");
    }
}
