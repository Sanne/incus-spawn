package dev.incusspawn.command;

import dev.incusspawn.config.MavenMirrors;
import dev.incusspawn.config.MavenRepositories;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code isx doctor}'s check of a {@code maven-mirrors:} entry (#2681), over a stand-in network. */
class MavenMirrorProbeTest {

    private static final MavenRepositories.Url CENTRAL = new MavenRepositories.Url("repo.maven.apache.org", "/maven2/");
    private static final MavenRepositories.Url NAS = new MavenRepositories.Url("nas", 8080, "/releases/");
    private static final MavenMirrors.Entry ENTRY = new MavenMirrors.Entry(CENTRAL, NAS);
    private static final String ARTIFACT = "https://nas:8080/releases/junit/junit/4.13.2/junit-4.13.2.pom";
    private static final byte[] POM = "<project/>".getBytes(StandardCharsets.UTF_8);

    /** What the stand-in network answers, by URI, and what it was asked. */
    private final Map<String, Object> answers = new HashMap<>();
    private final List<String> asked = new ArrayList<>();

    private MavenMirrorProbe.Result probe(MavenMirrors.Entry entry) {
        return new MavenMirrorProbe((uri, maxBytes, deadline) -> {
            asked.add(uri.toString());
            var answer = answers.get(uri.toString());
            if (answer instanceof IOException e) throw e;
            if (answer == null) return new MavenMirrorProbe.Answer(404, null, new byte[0]);
            return (MavenMirrorProbe.Answer) answer;
        }).probe(entry);
    }

    private static MavenMirrorProbe.Answer ok(byte[] body) {
        return new MavenMirrorProbe.Answer(200, null, body);
    }

    private static byte[] sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)).getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void aMirrorThatServesTheArtifactPassesAndIsAskedOnItsOwnPort() throws Exception {
        answers.put(ARTIFACT + ".sha1", ok(sha1(POM)));
        answers.put(ARTIFACT, ok(POM));
        var result = probe(ENTRY);
        assertNull(result.problem());
        assertEquals("junit/junit/4.13.2/junit-4.13.2.pom", result.artifact());
        assertEquals(List.of(ARTIFACT + ".sha1", ARTIFACT), asked, "its .sha1, then the artifact, as the proxy asks");
    }

    @Test
    void aStatusOtherThan200IsNamed() {
        var result = probe(ENTRY);
        assertEquals("it answered HTTP 404 for " + ARTIFACT + ".sha1", result.problem());
        assertEquals(List.of(ARTIFACT + ".sha1"), asked, "without its .sha1, the artifact is not asked");
    }

    @Test
    void aSha1ThatIsNotOneIsNamed() {
        answers.put(ARTIFACT + ".sha1", ok("<html>login</html>".getBytes(StandardCharsets.UTF_8)));
        assertEquals("it does not serve a usable .sha1 at " + ARTIFACT + ".sha1", probe(ENTRY).problem());
    }

    @Test
    void anArtifactThatDoesNotMatchItsSha1IsNamed() throws Exception {
        answers.put(ARTIFACT + ".sha1", ok(sha1("other".getBytes(StandardCharsets.UTF_8))));
        answers.put(ARTIFACT, ok(POM));
        assertEquals(ARTIFACT + " does not match the mirror's own .sha1", probe(ENTRY).problem());
    }

    @Test
    void theSha1sRedirectIsFollowedOverHttpsAndTheArtifactsIsNot() throws Exception {
        answers.put(ARTIFACT + ".sha1", new MavenMirrorProbe.Answer(302, "http://cdn.example.net/x.sha1", new byte[0]));
        answers.put("https://cdn.example.net/x.sha1", ok(sha1(POM)));
        answers.put(ARTIFACT, new MavenMirrorProbe.Answer(302, "https://cdn.example.net/x", new byte[0]));
        var result = probe(ENTRY);
        assertEquals(List.of(ARTIFACT + ".sha1", "https://cdn.example.net/x.sha1", ARTIFACT), asked);
        assertTrue(result.problem().contains("HTTP 302") && result.problem().contains("does not follow"), result.problem());
    }

    @Test
    void aFailureSaysWhatFailed() {
        var nas = NAS;
        assertEquals("TLS with nas:8080 failed (PKIX path building failed)", MavenMirrorProbe.failure(
                new IOException("wrapped", new SSLHandshakeException("PKIX path building failed")), nas));
        assertEquals("isx could not connect to nas:8080 within 4 s",
                MavenMirrorProbe.failure(new HttpConnectTimeoutException("timed out"), nas));
        assertEquals("nas:8080 did not serve the check within 5 s",
                MavenMirrorProbe.failure(new HttpTimeoutException("request timed out"), nas));
        assertEquals("isx could not connect to nas:8080 (Connection refused)",
                MavenMirrorProbe.failure(new ConnectException("Connection refused"), nas));
        assertEquals("isx could not resolve nas",
                MavenMirrorProbe.failure(new IOException("x", new java.net.UnknownHostException("nas")), nas));
        // As HttpClient reports a name it cannot resolve, and a refused connection
        var unresolved = new ConnectException();
        unresolved.initCause(new java.nio.channels.UnresolvedAddressException());
        assertEquals("isx could not resolve nas", MavenMirrorProbe.failure(unresolved, nas));
        assertEquals("isx could not connect to nas:8080", MavenMirrorProbe.failure(new ConnectException(), nas));
        // A server's text reaches the terminal on one line
        assertEquals("the request to nas:8080 failed (a b)", MavenMirrorProbe.failure(new IOException("a\nb"), nas));

        answers.put(ARTIFACT + ".sha1", new ConnectException("Connection refused"));
        assertEquals("isx could not connect to nas:8080 (Connection refused)", probe(ENTRY).problem());
    }

    @Test
    void aRepositoryWithNoKnownArtifactIsNotChecked() {
        var declared = new MavenMirrors.Entry(new MavenRepositories.Url("maven.google.com", "/"), NAS);
        var result = probe(declared);
        assertTrue(result.untested());
        assertEquals(List.of(), asked);
    }

    @Test
    void everyBuiltInRepositoryThatTakesAMirrorHasAKnownArtifact() {
        for (var cached : dev.incusspawn.proxy.CachedRepositoryRules.BUILT_IN) {
            if (!cached.maven()) continue;
            assertTrue(MavenMirrorProbe.KNOWN_ARTIFACTS.containsKey("https://" + cached.domain() + cached.pathPrefix()), cached.toString());
        }
    }

    @Test
    void theSha1IsReadAsTheProxyReadsIt() {
        var hex = "73bc5be628edeb297a1caf421a5a2e494798b92f";
        assertEquals(hex, MavenMirrorProbe.sha1Of((hex + "  junit-4.13.2.pom\n").getBytes(StandardCharsets.US_ASCII)));
        assertEquals(hex, MavenMirrorProbe.sha1Of(("junit-4.13.2.pom " + hex.toUpperCase()).getBytes(StandardCharsets.US_ASCII)));
        assertNull(MavenMirrorProbe.sha1Of(new byte[0]));
        assertNull(MavenMirrorProbe.sha1Of(null));
    }

    @Test
    void doctorShowsEachResultAsAFinding() {
        var findings = DoctorCommand.mavenMirrorFindings(List.of(
                new MavenMirrorProbe.Result(ENTRY, "a.pom", null),
                new MavenMirrorProbe.Result(ENTRY, "a.pom", "it answered HTTP 404 for x"),
                new MavenMirrorProbe.Result(ENTRY, null, null)));
        assertEquals(DoctorCommand.Status.OK, findings.get(0).status());
        assertEquals(DoctorCommand.Status.WARN, findings.get(1).status());
        assertEquals("Maven mirror https://nas:8080/releases/ does not work", findings.get(1).label());
        assertTrue(findings.get(1).detail().contains("it answered HTTP 404 for x"), findings.get(1).detail());
        assertTrue(findings.get(1).detail().contains("asks https://repo.maven.apache.org/maven2/ instead"), findings.get(1).detail());
        assertEquals(DoctorCommand.Status.NOTE, findings.get(2).status());
    }

    @Test
    void doctorAsksOnlyTheMirrorsTheProxyUses() throws Exception {
        assertEquals(List.of(), DoctorCommand.usedMavenMirrors(new dev.incusspawn.config.SpawnConfig()),
                "no section, no mirror and no request");
        var config = dev.incusspawn.config.Jjq.spawnConfig("""
                maven-mirrors:
                  - repository: https://maven.example.com/releases/
                    mirror: https://nas:8080/other/
                  - repository: https://repo.maven.apache.org/maven2/
                    mirror: https://nas:8080/releases/
                """);
        // The first names a repository isx does not cache, so the proxy ignores it
        assertEquals(List.of(ENTRY), DoctorCommand.usedMavenMirrors(config));
    }

    @Test
    void aRedirectThatIsNotFollowedSaysWhy() {
        answers.put(ARTIFACT + ".sha1", new MavenMirrorProbe.Answer(302, "ftp://cdn.example.net/x.sha1", new byte[0]));
        assertEquals(ARTIFACT + ".sha1 redirected to ftp://cdn.example.net/x.sha1, which the proxy does not follow:"
                + " only an https URL with a host", probe(ENTRY).problem());

        // An http URL with no host is not followed either, as the proxy does not follow it
        answers.put(ARTIFACT + ".sha1", new MavenMirrorProbe.Answer(302, "http:x.sha1", new byte[0]));
        assertEquals(ARTIFACT + ".sha1 redirected to http:x.sha1, which the proxy does not follow:"
                + " only an https URL with a host", probe(ENTRY).problem());

        answers.put(ARTIFACT + ".sha1", new MavenMirrorProbe.Answer(302, "https://cdn example/x", new byte[0]));
        assertTrue(probe(ENTRY).problem().endsWith("which is not a URL"), probe(ENTRY).problem());

        // A loop: every hop redirects again
        answers.put(ARTIFACT + ".sha1", new MavenMirrorProbe.Answer(302, ARTIFACT + ".sha1", new byte[0]));
        assertEquals("its .sha1 redirected more than 10 times, the most the proxy follows", probe(ENTRY).problem());
    }

    @Test
    void aLocationTheMirrorChoseReachesTheTerminalOnOneLine() {
        answers.put(ARTIFACT + ".sha1", new MavenMirrorProbe.Answer(302, "gopher://x\u202e\nfake", new byte[0]));
        var problem = probe(ENTRY).problem();
        assertFalse(problem.contains("\n") || problem.contains("\u202e"), problem);
    }

    @Test
    void aMirrorThatStallsAfterItsHeadersIsGivenUpAtTheDeadline() throws Exception {
        // Sends a 200's headers promising a body, then nothing
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var held = new java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>();
            var accepting = Thread.ofVirtual().start(() -> {
                try {
                    var socket = server.accept();
                    held.add(socket);
                    socket.getInputStream().read(new byte[4096]);
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nabc"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // The test ended
                }
            });
            // plain HTTP: a loopback server of the test's own, no TLS to check
            var client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
            var transport = MavenMirrorProbe.networkTransport(client);
            var started = System.nanoTime();
            var uri = java.net.URI.create("http://127.0.0.1:" + server.getLocalPort() + "/a.pom.sha1");
            assertThrows(HttpTimeoutException.class,
                    () -> transport.get(uri, 1024, started + java.time.Duration.ofMillis(500).toNanos()));
            var took = java.time.Duration.ofNanos(System.nanoTime() - started);
            assertTrue(took.compareTo(java.time.Duration.ofSeconds(3)) < 0, "gave up after " + took);
            accepting.join(1000);
            for (var socket : held) socket.close();
        }
    }

    @Test
    void aBodyLargerThanAskedIsNotKept() throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) {
                    socket.getInputStream().read(new byte[4096]);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2048\r\n\r\n" + "x".repeat(2048))
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // The client let the connection go
                }
            });
            // plain HTTP: a loopback server of the test's own, no TLS to check
            var client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
            var answer = MavenMirrorProbe.networkTransport(client).get(
                    java.net.URI.create("http://127.0.0.1:" + server.getLocalPort() + "/a.pom"), 1024,
                    System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos());
            assertEquals(200, answer.status());
            assertNull(answer.body());
        }
    }

    @Test
    void aFailingRedirectTargetIsNamedRatherThanTheMirror() {
        answers.put(ARTIFACT + ".sha1", new MavenMirrorProbe.Answer(302, "https://cdn.example.net:8443/x.sha1", new byte[0]));
        answers.put("https://cdn.example.net:8443/x.sha1", new java.io.IOException("x", new java.net.UnknownHostException("cdn.example.net")));
        assertEquals("isx could not resolve cdn.example.net", probe(ENTRY).problem());
        answers.put("https://cdn.example.net:8443/x.sha1", new ConnectException());
        assertEquals("isx could not connect to cdn.example.net:8443", probe(ENTRY).problem());
    }
}
