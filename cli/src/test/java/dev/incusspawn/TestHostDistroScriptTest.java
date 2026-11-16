package dev.incusspawn;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    void passingInstanceChecksPassWithoutAskingMavenCentral() throws Exception {
        var result = instanceChecks(0, "/throttled");
        assertEquals(0, result.exitCode, result.output);
        assertEquals(0, controlRequests.get(), result.output);
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
        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", "-c",
                "source \"$1\" && guest_exec() { return " + guestExit + "; } && instance_checks_pass",
                "test", SCRIPT.toString())
                .redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.toLowerCase().endsWith("_proxy"));
        builder.environment().put("MAVEN_CONTROL_URL", controlUrl);
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script hung:\n" + output);
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {}
}
