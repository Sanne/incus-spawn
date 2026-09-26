package dev.incusspawn.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.net.KeyCertOptions;

import javax.net.ssl.KeyManagerFactory;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The MITM server's certificates, chosen per SNI name.
 * <p>
 * dnsmasq's {@code address=/<domain>/} sends <em>every</em> name under an intercepted
 * domain to the proxy, at any depth, but a wildcard SAN covers exactly one label
 * (RFC 6125). A keystore holding only {@code <domain>} and {@code *.<domain>} therefore
 * cannot serve {@code results-receiver.actions.githubusercontent.com}, and Vert.x, finding
 * no alias for the SNI name, falls back to an arbitrary entry in the keystore -- which is
 * how a container came to be offered {@code *.api.openai.com} for a GitHub host (#783).
 * <p>
 * So the certificate is picked by {@link #certNameFor}: the domain itself for an exact
 * match, otherwise a wildcard for the name's parent, which is minted on first use through
 * {@link CertStore} (and so persisted like every other leaf). Vert.x runs the SNI mapping on
 * a worker thread, so minting there does not block the event loop.
 * <p>
 * Everything else -- a name outside every intercepted domain, which DNS never sends here,
 * or a client that sends no SNI at all -- gets the default key manager, which holds
 * <em>no</em> certificate, so the handshake ends at once with {@code handshake_failure}
 * rather than presenting a certificate for somebody else's name. Refusal is expressed that
 * way, not by throwing from the mapper: Netty's SNI handler leaves the connection hanging
 * until the handshake timeout when the lookup fails. Returning {@code null} also keeps
 * Vert.x from caching an SSL context per refused name.
 */
final class InterceptedCertOptions implements KeyCertOptions {

    /**
     * Upper bound on leaves minted on demand per proxy run. Each costs an RSA key
     * generation and a file pair on the host, and a container picks the names; ordinary
     * traffic stays in the dozens.
     */
    static final int MAX_ON_DEMAND_CERTS = 512;

    private static final char[] PASSWORD = "changeit".toCharArray();

    private final CertificateAuthority ca;
    private final CertStore store;
    private final Set<String> domains;
    private final KeyManagerFactory defaultFactory;
    private final ConcurrentHashMap<String, KeyManagerFactory> factories = new ConcurrentHashMap<>();
    private final AtomicInteger onDemand = new AtomicInteger();

    /**
     * Pre-mints the certs for every intercepted domain and its one-level wildcard, so the
     * common names are ready (and persisted) before the first handshake.
     */
    InterceptedCertOptions(CertificateAuthority ca, Set<String> interceptedDomains) throws Exception {
        this.ca = ca;
        this.store = new CertStore(ca);
        this.domains = interceptedDomains.stream()
                .map(d -> d.toLowerCase(Locale.ROOT))
                .filter(d -> {
                    if (CertStore.isHostname(d)) return true;
                    // Tool YAML domains are not syntax-checked; one bad entry must not
                    // take down interception for every other domain.
                    ProxyLog.warn("Not minting a certificate for intercepted domain '" + d
                            + "': not a valid hostname");
                    return false;
                })
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var names = domains.stream()
                .sorted()
                .flatMap(d -> java.util.stream.Stream.of(d, "*." + d))
                .toList();
        names.parallelStream().forEach(store::get);
        this.defaultFactory = factoryFor(emptyKeyStore());
    }

    /**
     * The certificate name that verifies for {@code serverName}, or {@code null} when the
     * name is not under any intercepted domain (or is not a hostname at all). Pure, so the
     * "every name DNS sends here gets a matching leaf" rule is testable on its own.
     */
    static String certNameFor(String serverName, Set<String> interceptedDomains) {
        if (serverName == null) return null;
        var name = serverName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (!CertStore.isHostname(name)) return null;
        if (interceptedDomains.contains(name)) return name;
        for (var d : interceptedDomains) {
            if (name.endsWith("." + d)) {
                // One label below d gives "*.d"; deeper names get their own parent's wildcard.
                return "*." + name.substring(name.indexOf('.') + 1);
            }
        }
        return null;
    }

    /** The key manager for {@code serverName}, or {@code null} to refuse the handshake. */
    private KeyManagerFactory resolve(String serverName) {
        var certName = certNameFor(serverName, domains);
        if (certName == null) {
            ProxyLog.warn("Refusing TLS handshake for '" + serverName
                    + "': not under any intercepted domain");
            return null;
        }
        // A null from mint() is not stored, so a name refused at the cap stays refused.
        return factories.computeIfAbsent(certName, this::mint);
    }

    private KeyManagerFactory mint(String certName) {
        var preMinted = domains.contains(certName)
                || (certName.startsWith("*.") && domains.contains(certName.substring(2)));
        if (!preMinted && onDemand.incrementAndGet() > MAX_ON_DEMAND_CERTS) {
            ProxyLog.warn("Refusing TLS handshake for '" + certName + "': "
                    + MAX_ON_DEMAND_CERTS + " certificates already minted on demand since the proxy started");
            return null;
        }
        if (!preMinted) ProxyLog.info("Minting certificate for " + certName);
        try {
            var keyStore = emptyKeyStore();
            addEntry(keyStore, certName, store.get(certName));
            return factoryFor(keyStore);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build a key manager for " + certName, e);
        }
    }

    private void addEntry(KeyStore keyStore, String alias, CertificateAuthority.CertEntry entry) {
        try {
            keyStore.setKeyEntry(alias, entry.key(), PASSWORD,
                    new X509Certificate[]{entry.cert(), ca.caCert()});
        } catch (Exception e) {
            throw new IllegalStateException("Could not store the certificate for " + alias, e);
        }
    }

    private static KeyStore emptyKeyStore() throws Exception {
        var keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        return keyStore;
    }

    private static KeyManagerFactory factoryFor(KeyStore keyStore) throws Exception {
        var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, PASSWORD);
        return factory;
    }

    // Immutable apart from its caches, which are safe to share: Vert.x copies options when
    // they are set on a server, and each reload builds a fresh instance.
    @Override
    public KeyCertOptions copy() {
        return this;
    }

    @Override
    public KeyManagerFactory getKeyManagerFactory(Vertx vertx) {
        return defaultFactory;
    }

    @Override
    public Function<String, KeyManagerFactory> keyManagerFactoryMapper(Vertx vertx) {
        return this::resolve;
    }
}
