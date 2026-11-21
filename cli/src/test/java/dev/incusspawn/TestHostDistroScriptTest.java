package dev.incusspawn;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The host-distro jobs' instance phase, {@code .github/scripts/test-host-distro.sh} sourced without
 * running a phase. Maven Central rate-limits a whole runner at times, the proxy relaying its 429
 * (#2278). When the instance's only failures are such 429s, the host asks Maven Central itself,
 * without the proxy: a 429 there too is the runner being throttled, a warning, while an answer
 * there means only the proxied request was refused, a failure.
 */
class TestHostDistroScriptTest {

    private static final Path SCRIPT = Path.of("../.github/scripts/test-host-distro.sh").toAbsolutePath();

    private HttpServer server;
    private final AtomicInteger controlRequests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/throttled", exchange -> {
            controlRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Server", "cloudflare");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        // Central's edge answers a throttled host with a 404 and a Retry-After (#2422)
        server.createContext("/limited-404", exchange -> {
            controlRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "33665");
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            controlRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/pom", exchange -> {
            controlRequests.incrementAndGet();
            byte[] body = "<artifactId>junit</artifactId>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aThrottledInstanceOnAThrottledHostPassesWithAWarning() throws Exception {
        var result = instanceChecks(75, "/throttled");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("::warning title=Maven Central throttled this runner::"), result.output);
        assertEquals(1, controlRequests.get(), result.output);
    }

    @Test
    void aThrottledInstanceOnAHostMavenCentralAnswersFails() throws Exception {
        var result = instanceChecks(75, "/pom");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("HTTP 200"), result.output);
        assertFalse(result.output.contains("::warning"), result.output);
    }

    @Test
    void anyOtherInstanceFailureFailsWithoutAskingMavenCentral() throws Exception {
        var result = instanceChecks(1, "/throttled");
        assertEquals(1, result.exitCode, result.output);
        assertEquals(0, controlRequests.get(), result.output);
    }

    @Test
    void aThrottledInstanceOnAHostWhoseMavenCentralAnswers404WithARetryAfterPassesWithAWarning() throws Exception {
        var result = instanceChecks(75, "/limited-404");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("::warning title=Maven Central throttled this runner::HTTP 404"), result.output);
    }

    @Test
    void aThrottledInstanceOnAHostWhoseMavenCentralAnswersAPlain404Fails() throws Exception {
        var result = instanceChecks(75, "/missing");
        assertEquals(1, result.exitCode, result.output);
        assertFalse(result.output.contains("::warning"), result.output);
    }

    private static final String MIRROR_LINE = "Fetching junit/junit/4.13.2/junit-4.13.2.jar from its mirror"
            + " maven-central.storage-download.googleapis.com; repo1.maven.org confirms the copy at its next use\n";

    @Test
    void passingInstanceChecksPassWhenTheProxyLogShowsTheMirrorAndCentralConfirming() throws Exception {
        var result = instanceChecksLogging(MIRROR_LINE + "Maven/Gradle cache: 1 hit, 380 KB (1 confirmed first)\n");
        assertEquals(0, result.exitCode, result.output);
        assertEquals(0, controlRequests.get(), result.output);
        assertFalse(result.output.contains("::warning"), result.output);
    }

    @Test
    void passingInstanceChecksWarnWhenCentralThrottledItsConfirmation() throws Exception {
        var result = instanceChecksLogging(MIRROR_LINE
                + "Maven/Gradle cache: 1 hit, 380 KB (1 unconfirmed, upstream unreachable)\n");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("::warning title=Maven Central throttled this runner::"), result.output);
    }

    @Test
    void passingInstanceChecksFailWhenTheMirrorDidNotServeTheJar() throws Exception {
        var result = instanceChecksLogging("Maven/Gradle cache: 1 hit, 380 KB (1 confirmed first)\n");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("not in effect"), result.output);
    }

    @Test
    void passingInstanceChecksFailWhenNeitherSummaryNorConfirmationIsLogged() throws Exception {
        var result = instanceChecksLogging(MIRROR_LINE);
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("no cache summary"), result.output);
    }

    @Test
    void whatTheProxyLoggedBeforeTheChecksIsNotGraded() throws Exception {
        var log = Files.createTempFile("proxy", ".log");
        Files.writeString(log, MIRROR_LINE + "Maven/Gradle cache: 1 hit, 380 KB (1 confirmed first)\n");
        var result = instanceChecksAt(0, "http://127.0.0.1:1/x", log, "");
        assertEquals(1, result.exitCode, result.output);
    }

    private Result instanceChecksLogging(String logged) throws Exception {
        return instanceChecksAt(0, "http://127.0.0.1:1/x", Files.createTempFile("proxy", ".log"), logged);
    }

    @Test
    void aThrottledInstanceOnAHostThatCannotReachMavenCentralFails() throws Exception {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/throttled";
        server.stop(0);
        var result = instanceChecksAt(75, url);
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("could not reach Maven Central"), result.output);
        assertFalse(result.output.contains("::warning"), result.output);
    }

    /** The guest's script and this one agree on the exit code that means "throttled only". */
    @Test
    void bothScriptsAgreeOnTheThrottledExitCode() throws Exception {
        var hostDistro = new ProcessBuilder("bash", "--norc", "--noprofile", "-c",
                "source \"$1\" && echo \"$THROTTLED_EXIT\"", "test", SCRIPT.toString())
                .redirectErrorStream(true).start();
        var instance = new ProcessBuilder("bash", "--norc", "--noprofile", "-c",
                "source \"$1\" && echo \"$THROTTLED_EXIT\"", "test",
                SCRIPT.resolveSibling("test-instance.sh").toString())
                .redirectErrorStream(true).start();
        String fromHostDistro = new String(hostDistro.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        String fromInstance = new String(instance.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(hostDistro.waitFor(30, TimeUnit.SECONDS) && instance.waitFor(30, TimeUnit.SECONDS));
        assertEquals("75", fromHostDistro);
        assertEquals(fromHostDistro, fromInstance);
    }

    private Result instanceChecks(int guestExit, String controlPath) throws Exception {
        return instanceChecksAt(guestExit, "http://127.0.0.1:" + server.getAddress().getPort() + controlPath);
    }

    /** Runs instance_checks_pass with the guest's test-instance.sh exiting {@code guestExit}. */
    private Result instanceChecksAt(int guestExit, String controlUrl) throws Exception {
        return instanceChecksAt(guestExit, controlUrl, Files.createTempFile("proxy", ".log"), "");
    }

    /** As above, the guest's run appending {@code logged} to the proxy log, which the grading reads. */
    private Result instanceChecksAt(int guestExit, String controlUrl, Path log, String logged) throws Exception {
        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", "-c",
                "source \"$1\" && guest_exec() { printf '%b' \"$LOGGED\" >> \"$MAVEN_PROXY_LOG\"; return "
                        + guestExit + "; } && instance_checks_pass",
                "test", SCRIPT.toString())
                .redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.toLowerCase().endsWith("_proxy"));
        builder.environment().put("MAVEN_CONTROL_URL", controlUrl);
        builder.environment().put("MAVEN_PROXY_LOG", log.toString());
        builder.environment().put("LOGGED", logged);
        builder.environment().put("GRADE_WAIT", "1");
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script hung:\n" + output);
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {}
}
