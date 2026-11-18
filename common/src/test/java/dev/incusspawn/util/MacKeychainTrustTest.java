package dev.incusspawn.util;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.proxy.CertificateAuthority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.Map;
import java.util.Set;

import static dev.incusspawn.util.MacKeychainTrust.Decision.DENY;
import static dev.incusspawn.util.MacKeychainTrust.Decision.NONE;
import static dev.incusspawn.util.MacKeychainTrust.Decision.TRUST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How macOS trust settings, as {@code security trust-settings-export -d} writes them, decide what
 * isx trusts. Runs everywhere: the plists are text, and only {@code read()} needs a Mac
 * ({@code KeychainTrustMacTest} covers that on a disposable runner).
 */
class MacKeychainTrustTest {

    /** {@code kSecTrustSettingsPolicy} for SSL as a plist stores it: the OID's content octets. */
    private static final String SSL = "<key>kSecTrustSettingsPolicy</key><data>KoZIhvdjZAED</data>"
            + "<key>kSecTrustSettingsPolicyName</key><string>sslServer</string>";
    /** The same for S/MIME (1.2.840.113635.100.1.8). */
    private static final String SMIME = "<key>kSecTrustSettingsPolicy</key><data>KoZIhvdjZAEI</data>"
            + "<key>kSecTrustSettingsPolicyName</key><string>SMIME</string>";

    @TempDir
    Path temp;

    private String savedHome;

    @BeforeEach
    void saveHome() {
        savedHome = System.getProperty("user.home");
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", savedHome);
    }

    @Test
    void anEmptySettingsListTrustsAsRoot() throws Exception {
        assertEquals(TRUST, decide("<array/>"));
    }

    @Test
    void whatAMacRecordedIsReadAsMacOsVerifiedIt() throws Exception {
        // trust-settings-export -d after the six cases in macos-trust-settings/README.md
        var certs = new java.util.LinkedHashMap<String, X509Certificate>();
        for (var name : List.of("root", "ssl", "smime", "deny", "host", "notrust")) {
            try (var in = getClass().getResourceAsStream("/macos-trust-settings/" + name + ".pem")) {
                certs.put(name, SystemTrust.certificates(in).getFirst());
            }
        }
        String xml;
        try (var in = getClass().getResourceAsStream("/macos-trust-settings/admin-domain.plist")) {
            xml = new String(in.readAllBytes());
        }

        var trust = MacKeychainTrust.evaluate(xml, List.copyOf(certs.values()));

        assertEquals(List.of(certs.get("root"), certs.get("ssl")), trust.trusted());
        assertEquals(Set.of(MacKeychainTrust.sha1(certs.get("deny"))), trust.denied());
    }

    @Test
    void aRecordWithNoSettingsTrustsAsRoot() {
        // How `add-trusted-cert -d -r trustRoot` without -p exports
        assertEquals(TRUST, MacKeychainTrust.decide(null));
    }

    @Test
    void anEntryForSslOrForEveryPolicyTrusts() throws Exception {
        assertEquals(TRUST, decide(entries(SSL + result(1))));
        assertEquals(TRUST, decide(entries(result(2))));
        // No result is kSecTrustSettingsResultTrustRoot
        assertEquals(TRUST, decide(entries(SSL)));
    }

    @Test
    void anEntryForAnotherPolicyDecidesNothingForTls() throws Exception {
        assertEquals(NONE, decide(entries(SMIME + result(1))));
        // A deny for S/MIME does not deny TLS either
        assertEquals(NONE, decide(entries(SMIME + result(3))));
    }

    @Test
    void aDenyForTlsDenies() throws Exception {
        assertEquals(DENY, decide(entries(result(3))));
        assertEquals(DENY, decide(entries(SSL + result(3))));
    }

    @Test
    void theFirstEntryThatAppliesDecides() throws Exception {
        assertEquals(DENY, decide(entries(SMIME + result(1), SSL + result(3), result(1))));
        assertEquals(TRUST, decide(entries(SSL + result(4), result(1))));
        assertEquals(NONE, decide(entries(SSL + result(4))));
    }

    @Test
    void aResultOfAnotherTypeDecidesNothingAndTrustsNothingAfterIt() throws Exception {
        var asString = "<key>kSecTrustSettingsResult</key><string>3</string>";
        assertEquals(NONE, decide(entries(asString)));
        // A later deny still denies; a later trust does not trust, since macOS may read it differently
        assertEquals(DENY, decide(entries(asString, result(3))));
        assertEquals(NONE, decide(entries(asString, result(1))));
        // macOS reads any number: a real 3 denies
        assertEquals(DENY, decide(entries("<key>kSecTrustSettingsResult</key><real>3</real>", result(1))));
        assertEquals(NONE, decide(entries("<key>kSecTrustSettingsResult</key><real>1</real>")));
    }

    @Test
    void aPolicyThatIsNotDataTrustsNothingAfterIt() throws Exception {
        var policyAsString = "<key>kSecTrustSettingsPolicy</key><string>sslServer</string>";
        assertEquals(NONE, decide(entries(policyAsString + result(1))));
        assertEquals(NONE, decide(entries(policyAsString + result(4), result(1))));
        assertEquals(DENY, decide(entries(policyAsString + result(1), result(3))));
        // A deny on that very entry denies: macOS may read the policy as SSL's
        assertEquals(DENY, decide(entries(policyAsString + result(3))));
    }

    @Test
    void whenTheSystemKeychainCannotBeReadTheDeniesStillApply() throws Exception {
        var trusted = ca("trusted");
        var denied = ca("denied");
        var xml = plist(record(trusted, "<array/>") + record(denied, entries(result(3)))
                + "<key>kSecTrustSettingsDefaultRootCertSetting</key><dict><key>trustSettings</key>" + entries(result(3)) + "</dict>");
        // A timeout, an exit status or output that does not parse: all the same
        for (var failure : List.<Exception>of(new java.io.IOException("security find-certificate timed out after 5s"),
                new java.security.cert.CertificateException("not PEM"))) {
            var trust = MacKeychainTrust.evaluate(xml, () -> { throw failure; }, List.of(trusted));
            assertEquals(List.of(), trust.trusted());
            assertEquals(Set.of(MacKeychainTrust.sha1(denied)), trust.denied());
            assertEquals(true, trust.defaultRootsDenied());
            assertEquals(true, trust.problem().contains(failure.getClass().getSimpleName()), trust.problem());
        }
        // Read: the login keychain's bodies join the System keychain's
        assertEquals(List.of(trusted), MacKeychainTrust.evaluate(xml, List::of, List.of(trusted)).trusted());
    }

    @Test
    void anEntryThatIsNotADictIsSkippedButTrustsNothingAfterIt() throws Exception {
        assertEquals(DENY, decide("<array><string>not a dict</string><dict>" + result(3) + "</dict></array>"));
        assertEquals(NONE, decide("<array><string>not a dict</string><dict>" + result(1) + "</dict></array>"));
    }

    @Test
    void aConstrainedEntryOnlyEverNarrows() throws Exception {
        var forOneHost = "<key>kSecTrustSettingsPolicyString</key><string>intranet.example</string>";
        // Trusted for one host name only: not an anchor for every TLS connection
        assertEquals(NONE, decide(entries(SSL + forOneHost + result(1))));
        // Denied for one host name: denied
        assertEquals(DENY, decide(entries(SSL + forOneHost + result(3))));
        var forOneApp = "<key>kSecTrustSettingsApplication</key><data>AAAA</data>";
        assertEquals(NONE, decide(entries(forOneApp + result(1))));
        var signingOnly = "<key>kSecTrustSettingsKeyUsage</key><integer>1</integer>";
        assertEquals(NONE, decide(entries(signingOnly + result(1))));
        var anyUsage = "<key>kSecTrustSettingsKeyUsage</key><integer>4294967295</integer>";
        assertEquals(TRUST, decide(entries(anyUsage + result(1))));
    }

    @Test
    void anAllowedErrorDoesNotStopTheEntryTrusting() throws Exception {
        // Keychain Access writes these beside "Always Trust"; isx just does not allow the error
        assertEquals(TRUST, decide(entries(SSL + "<key>kSecTrustSettingsAllowedError</key><integer>-2147409654</integer>" + result(1))));
    }

    @Test
    void picksTheSystemKeychainsCertificatesBySha1() throws Exception {
        var trusted = ca("trusted");
        var denied = ca("denied");
        var smimeOnly = ca("smime");
        var notInKeychain = ca("elsewhere");
        var xml = plist(record(trusted, entries(SSL + result(1)))
                + record(denied, entries(result(3)))
                + record(smimeOnly, entries(SMIME + result(1)))
                + record(notInKeychain, "<array/>"));

        var trust = MacKeychainTrust.evaluate(xml, List.of(trusted, denied, smimeOnly));

        assertEquals(List.of(trusted), trust.trusted());
        assertEquals(Set.of(MacKeychainTrust.sha1(denied)), trust.denied());
    }

    @Test
    void theUserDomainsDeniesApplyAndItsTrustsDoNot() throws Exception {
        // macOS refuses what a user marked "Never Trust" (#1552); a user's trust must never widen isx's
        var adminTrusted = ca("admin trusted");
        var userTrusted = ca("user trusted");
        var admin = MacKeychainTrust.evaluate(plist(record(adminTrusted, "<array/>")), List.of(adminTrusted, userTrusted));
        var user = plist(record(adminTrusted, entries(result(3))) + record(userTrusted, "<array/>")
                + "<key>kSecTrustSettingsDefaultRootCertSetting</key><dict><key>trustSettings</key>" + entries(result(3)) + "</dict>");

        var trust = MacKeychainTrust.withUserDenies(admin, () -> user);

        assertEquals(List.of(), trust.trusted());
        assertEquals(Set.of(MacKeychainTrust.sha1(adminTrusted)), trust.denied());
        assertEquals(true, trust.defaultRootsDenied());
    }

    @Test
    void aDenyCoversEveryCopyOfTheCaWhoseBodyAKeychainHolds() throws Exception {
        // A deny names one certificate; a CA's cross-signed copy has its subject and key, not its hash (#1552)
        var denied = CertChain.create("copies denied", "localhost");
        var other = CertChain.create("copies other", "localhost");
        var crossSigned = denied.rootCrossSignedBy(other);
        var byHash = MacKeychainTrust.withUserDenies(MacKeychainTrust.Trust.NONE, () -> plist(record(denied.root(), entries(result(3)))));

        var withBody = byHash.withDeniedKeys(List.of(other.root(), denied.root()));

        assertEquals(Set.of(MacKeychainTrust.keyOf(denied.root())), withBody.deniedKeys());
        assertEquals(true, withBody.denies(crossSigned));
        assertEquals(false, withBody.denies(other.root()));
        assertEquals(false, withBody.denies(denied.intermediate()));
        // Without its body the key is unknown, and the hash still denies the certificate itself
        assertEquals(false, byHash.denies(crossSigned));
        assertEquals(true, byHash.denies(denied.root()));
        // An admin-trusted copy of the denied CA is no longer listed as trusted, as isx doctor shows it
        var trustingCopy = new MacKeychainTrust.Trust(List.of(crossSigned, other.root()), byHash.denied(), null);
        assertEquals(List.of(other.root()), trustingCopy.withDeniedKeys(List.of(denied.root())).trusted());
    }

    @Test
    void aUserDenyOfSectigoE46RefusesTheChainApiGithubComSends() throws Exception {
        // The case behind #1552 with Sectigo's real certificates (#1631): a "Never Trust" on the
        // self-signed E46, while api.github.com sends E46 cross-signed by USERTrust ECC
        var e46 = fixture("root-e46.pem");
        var e46ByUsertrust = fixture("root-e46-by-usertrust-ecc.pem");
        var dvE36 = fixture("ca-dv-e36.pem");
        var user = plist(record(e46, entries(result(3))));

        // read() matches the deny against the bodies the keychains and Apple's roots hold; here the
        // self-signed E46 stands for them (the README says what was not checked)
        var trust = MacKeychainTrust.withUserDenies(MacKeychainTrust.Trust.NONE, () -> user).withDeniedKeys(List.of(e46));

        assertEquals(Set.of(MacKeychainTrust.sha1(e46)), trust.denied());
        assertEquals(true, trust.denies(e46ByUsertrust), "the cross-signed copy the server sends");
        assertEquals(false, trust.denies(dvE36), "E46's intermediate is not denied itself");
        var chain = new X509Certificate[] {dvE36, e46ByUsertrust};
        var refused = assertThrows(java.security.cert.CertificateException.class,
                () -> denyingTrustManager(trust).checkServerTrusted(chain, "EC"));
        assertTrue(refused.getMessage().contains("subject and key of one denied"), refused.getMessage());
        // The overload a handshake calls, as the proxy's and the CLI's do
        assertThrows(java.security.cert.CertificateException.class,
                () -> denyingTrustManager(trust).checkServerTrusted(chain, "EC", (SSLEngine) null));

        // Back to "Use System Defaults": no user deny, and the same chain passes
        var restored = MacKeychainTrust.withUserDenies(MacKeychainTrust.Trust.NONE, () -> null).withDeniedKeys(List.of(e46));
        assertEquals(false, restored.denies(e46ByUsertrust));
        assertSame(ACCEPT_ALL, DeniedChainTrustManager.wrap(new TrustManager[] {ACCEPT_ALL}, restored)[0]);
    }

    @Test
    void aUserDomainThatCannotBeReadTrustsNothingAndKeepsTheAdminDomainsDenies() throws Exception {
        var trusted = ca("trusted");
        var denied = ca("denied");
        var admin = MacKeychainTrust.evaluate(plist(record(trusted, "<array/>") + record(denied, entries(result(3)))), List.of(trusted));
        // None exists (`security` exits with "No Trust Settings"): nothing to deny
        assertEquals(admin, MacKeychainTrust.withUserDenies(admin, () -> null));
        // A timeout, an exit status, output that does not parse: a deny there is unknown, so a CA the user
        // denied is not trusted unseen, nor handed to every instance by host-ca propagation (#1530)
        for (Callable<String> unread : List.<Callable<String>>of(
                () -> { throw new java.io.IOException("exited 1"); }, () -> "not xml")) {
            var trust = MacKeychainTrust.withUserDenies(admin, unread);
            assertEquals(List.of(), trust.trusted());
            assertEquals(admin.denied(), trust.denied());
            assertNotNull(trust.problem());
        }
        // And an admin domain that cannot be read keeps the user's denies
        var failed = MacKeychainTrust.withUserDenies(MacKeychainTrust.Trust.failed("timed out"), () -> plist(record(denied, entries(result(3)))));
        assertEquals(Set.of(MacKeychainTrust.sha1(denied)), failed.denied());
        assertEquals("timed out", failed.problem());
    }

    @Test
    void aDenyOfTheDefaultRootsIsKeptAndOtherKeysAreNotCertificates() throws Exception {
        var deniedRoots = "<key>kSecTrustSettingsDefaultRootCertSetting</key><dict><key>trustSettings</key>"
                + entries(result(3)) + "</dict><key>notAFingerprint</key><dict><key>trustSettings</key>" + entries(result(3)) + "</dict>";
        var trust = MacKeychainTrust.evaluate(plist(deniedRoots), List.of());
        assertEquals(true, trust.defaultRootsDenied());
        assertEquals(Set.of(), trust.denied());
        assertEquals(false, trust.isEmpty());

        var trustedRoots = "<key>kSecTrustSettingsDefaultRootCertSetting</key><dict><key>trustSettings</key><array/></dict>";
        assertEquals(false, MacKeychainTrust.evaluate(plist(trustedRoots), List.of()).defaultRootsDenied());
    }

    @Test
    void aCertificateMustMatchItsRecordsIssuerAndSerialToo() throws Exception {
        var trusted = ca("trusted");
        var other = ca("other");
        // The record names `trusted` by SHA-1, but its issuer and serial are another certificate's
        var b64 = java.util.Base64.getEncoder();
        var mismatched = "<key>" + MacKeychainTrust.sha1(trusted) + "</key><dict>"
                + "<key>issuerName</key><data>" + b64.encodeToString(trusted.getIssuerX500Principal().getEncoded()) + "</data>"
                + "<key>serialNumber</key><data>" + b64.encodeToString(other.getSerialNumber().toByteArray()) + "</data>"
                + "<key>trustSettings</key><array/></dict>";
        assertEquals(List.of(), MacKeychainTrust.evaluate(plist(mismatched), List.of(trusted)).trusted());
    }

    @Test
    void readsTheKeychainsPemOutput() throws Exception {
        var a = ca("a");
        var b = ca("b");
        // find-certificate -p prints the certificates back to back
        assertEquals(List.of(a, b), parse(pem(a) + pem(b)));
        assertEquals(List.of(), parse(""));
    }

    @Test
    void refusesWhatIsNotTrustSettings() {
        assertThrows(Exception.class, () -> MacKeychainTrust.evaluate("not xml", List.of()));
        assertThrows(Exception.class, () -> MacKeychainTrust.evaluate(
                "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>trustVersion</key><integer>1</integer></dict></plist>", List.of()));
    }

    @Test
    void neverResolvesAnEntity() throws Exception {
        var secret = Files.writeString(temp.resolve("secret"), "<string>leaked</string>");
        var xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE plist [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>\n"
                + "<plist version=\"1.0\"><dict><key>trustList</key><dict/><key>x</key><string>&x;</string></dict></plist>";
        assertThrows(Exception.class, () -> MacKeychainTrust.parsePlist(xml));
    }

    @Test
    void parsesApplesDoctype() throws Exception {
        var parsed = MacKeychainTrust.parsePlist(plist(""));
        assertEquals(Map.of("trustList", Map.of(), "trustVersion", 1L), parsed);
    }

    /** A certificate from {@code macos-trust-settings/sectigo-e46}, where its README says where it came from. */
    private static X509Certificate fixture(String name) throws Exception {
        try (var in = java.util.Objects.requireNonNull(MacKeychainTrustTest.class.getResourceAsStream(
                "/macos-trust-settings/sectigo-e46/" + name), name)) {
            return (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    /** Stands for the JDK's PKIX check, which the real chain would fail here only for want of its leaf. */
    private static final X509ExtendedTrustManager ACCEPT_ALL = new X509ExtendedTrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    private static X509ExtendedTrustManager denyingTrustManager(MacKeychainTrust.Trust trust) {
        return (X509ExtendedTrustManager) DeniedChainTrustManager.wrap(new TrustManager[] {ACCEPT_ALL}, trust)[0];
    }

    // --- plists shaped like trust-settings-export's ---

    private static MacKeychainTrust.Decision decide(String trustSettings) throws Exception {
        var parsed = (Map<?, ?>) MacKeychainTrust.parsePlist(plist("<key>0000</key><dict><key>trustSettings</key>" + trustSettings + "</dict>"));
        var record = (Map<?, ?>) ((Map<?, ?>) parsed.get("trustList")).get("0000");
        return MacKeychainTrust.decide(record.get("trustSettings"));
    }

    private static String result(int value) {
        return "<key>kSecTrustSettingsResult</key><integer>" + value + "</integer>";
    }

    private static String entries(String... dicts) {
        var sb = new StringBuilder("<array>");
        for (var d : dicts) sb.append("<dict>").append(d).append("</dict>");
        return sb.append("</array>").toString();
    }

    private static String record(X509Certificate cert, String trustSettings) {
        var b64 = java.util.Base64.getEncoder();
        return "<key>" + MacKeychainTrust.sha1(cert) + "</key><dict>"
                + "<key>issuerName</key><data>" + b64.encodeToString(cert.getIssuerX500Principal().getEncoded()) + "</data>"
                + "<key>modDate</key><date>2026-10-18T12:00:00Z</date>"
                + "<key>serialNumber</key><data>" + b64.encodeToString(cert.getSerialNumber().toByteArray()) + "</data>"
                + "<key>trustSettings</key>" + trustSettings + "</dict>";
    }

    private static String plist(String trustList) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                \t<key>trustList</key>
                \t<dict>%s</dict>
                \t<key>trustVersion</key>
                \t<integer>1</integer>
                </dict>
                </plist>
                """.formatted(trustList);
    }

    private X509Certificate ca(String name) {
        System.setProperty("user.home", temp.resolve(name).toString());
        return CertificateAuthority.loadOrCreate().caCert();
    }

    private static List<X509Certificate> parse(String pem) throws Exception {
        return SystemTrust.certificates(new java.io.ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    private static String pem(X509Certificate cert) throws Exception {
        return DerEncoder.toPem("CERTIFICATE", cert.getEncoded());
    }
}
