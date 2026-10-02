package dev.incusspawn.proxy;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container's side of the MITM server: the isx CA as its only trust anchor, and a TLS
 * handshake like curl's. Shared by the tests that pin what the server presents, so a fix to
 * how they handshake -- such as the no-SNI case below -- reaches all of them.
 */
final class ContainerTls {

    record Handshake(X509Certificate leaf, String alpn, String protocol, String cipher) {}

    private ContainerTls() {}

    /**
     * Handshakes with the MITM server on {@code port}, sending {@code host} as SNI and offering
     * {@code h2} and {@code http/1.1} over ALPN, and returns what was negotiated.
     * <p>
     * With a host, the leaf must verify for it, as curl and Go's net/http check. Without one
     * there is no name to check, so only the CA signature is: the client then accepts any leaf
     * the server presents, and the handshake fails only if the server refuses it. Checking
     * against the connect address instead would fail every no-SNI handshake on the client's
     * side, and a server handing out a certificate would go unnoticed.
     */
    static Handshake handshake(int port, String host, X509Certificate ca) throws Exception {
        return handshake(port, host, ca, new AtomicBoolean());
    }

    /**
     * Asserts the server refused the handshake before presenting any certificate. A failed
     * handshake alone is not enough: the client rejecting the leaf it was served -- one for
     * another name, or signed by a CA it no longer trusts -- fails it too, and would hide a
     * server that answers with a certificate where it should refuse.
     */
    static void assertServerRefuses(int port, String host, X509Certificate ca, String message) {
        var certificateSeen = new AtomicBoolean();
        assertThrows(SSLException.class, () -> handshake(port, host, ca, certificateSeen), message);
        assertFalse(certificateSeen.get(),
                message + ": the server presented a certificate, and only the client turned it down");
    }

    private static Handshake handshake(int port, String host, X509Certificate ca, AtomicBoolean certificateSeen)
            throws Exception {
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("isx", ca);
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        // A fresh context per handshake, like a fresh curl: a shared one would resume an
        // earlier session and skip the certificate exchange these tests are about.
        var tls = SSLContext.getInstance("TLS");
        var containerTrust = (X509ExtendedTrustManager) tmf.getTrustManagers()[0];
        tls.init(null, new TrustManager[]{recording(containerTrust, certificateSeen)}, null);
        try (var socket = (SSLSocket) tls.getSocketFactory().createSocket("127.0.0.1", port)) {
            // A refusal must be prompt: a hang surfaces as SocketTimeoutException, not SSLException.
            socket.setSoTimeout(5_000);
            var params = socket.getSSLParameters();
            if (host != null) {
                params.setServerNames(List.of(new SNIHostName(host)));
                params.setEndpointIdentificationAlgorithm("HTTPS");
            } else {
                params.setServerNames(List.of());
            }
            params.setApplicationProtocols(new String[]{"h2", "http/1.1"});
            socket.setSSLParameters(params);
            socket.startHandshake();
            var session = socket.getSession();
            return new Handshake((X509Certificate) session.getPeerCertificates()[0],
                    socket.getApplicationProtocol(), session.getProtocol(), session.getCipherSuite());
        }
    }

    /** {@code delegate}, noting in {@code certificateSeen} whenever the server presents a chain. */
    private static X509ExtendedTrustManager recording(X509ExtendedTrustManager delegate, AtomicBoolean certificateSeen) {
        return new X509ExtendedTrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                certificateSeen.set(true);
                delegate.checkServerTrusted(chain, authType, socket);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                certificateSeen.set(true);
                delegate.checkServerTrusted(chain, authType, engine);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                certificateSeen.set(true);
                delegate.checkServerTrusted(chain, authType);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                delegate.checkClientTrusted(chain, authType, socket);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                delegate.checkClientTrusted(chain, authType, engine);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                delegate.checkClientTrusted(chain, authType);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return delegate.getAcceptedIssuers();
            }
        };
    }

    /**
     * Starts {@code proxy} on a daemon thread, since {@link MitmProxy#start} blocks, and waits
     * until it listens. A start that fails is rethrown here rather than waited out.
     */
    static void startInBackground(MitmProxy proxy) throws Exception {
        var ready = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var thread = new Thread(() -> {
            try {
                proxy.start(ready::countDown);
            } catch (Exception e) {
                failure.set(e);
                ready.countDown();
            }
        }, "test-proxy");
        thread.setDaemon(true);
        thread.start();
        assertTrue(ready.await(30, TimeUnit.SECONDS), "Proxy did not start in time");
        if (failure.get() != null) throw failure.get();
    }

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
