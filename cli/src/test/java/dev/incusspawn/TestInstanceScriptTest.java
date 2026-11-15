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
 * The helpers of {@code .github/scripts/test-instance.sh}, sourced without running its checks.
 * Maven Central sometimes answers a CI runner with a 403, which the proxy relays unchanged:
 * the check must say so, with the status and Server header, rather than fail silently (#1810).
 * A refusal is retried first, for about two minutes: Maven Central's rate limit (429) passes,
 * though not always within 30 s (#2063, #2268).
 */
class TestInstanceScriptTest {

    private static final Path SCRIPT = Path.of("../.github/scripts/test-instance.sh").toAbsolutePath();

    private HttpServer server;
    private final AtomicInteger limitedRequests = new AtomicInteger();
    private final AtomicInteger refused = new AtomicInteger();
    private final AtomicInteger throttledRequests = new AtomicInteger();
    private final AtomicInteger octalRequests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/refused", exchange -> {
            refused.incrementAndGet();
            exchange.getResponseHeaders().add("Server", "AkamaiGHost");
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/limited", exchange -> {
            if (limitedRequests.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Server", "cloudflare");
                exchange.getResponseHeaders().add("Retry-After", "1");
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] body = "<artifactId>junit</artifactId>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        // Throttled four times without a Retry-After, as Cloudflare answered a runner for over 30 s (#2268)
        server.createContext("/throttled", exchange -> {
            if (throttledRequests.getAndIncrement() < 4) {
                exchange.getResponseHeaders().add("Server", "cloudflare");
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] body = "<artifactId>junit</artifactId>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        // A Retry-After with a leading zero, which shell arithmetic would read as octal
        server.createContext("/octal", exchange -> {
            if (octalRequests.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "08");
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] body = "<artifactId>junit</artifactId>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.createContext("/pom", exchange -> {
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
    void anUpstreamRefusalIsReportedWithItsStatusAndServer() throws Exception {
        var result = fetchMatching("/refused", "<artifactId>junit</artifactId>");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("HTTP 403 (Server: AkamaiGHost, Retry-After: none)"), result.output);
        assertTrue(result.output.contains("the TLS handshake with the proxy succeeded"), result.output);
        assertTrue(result.output.contains("after 6 attempts"), result.output);
        assertEquals(6, refused.get(), result.output);
    }

    @Test
    void aRateLimitedAnswerIsRetried() throws Exception {
        var result = fetchMatching("/limited", "<artifactId>junit</artifactId>");
        assertEquals(0, result.exitCode, result.output);
        assertEquals(2, limitedRequests.get(), result.output);
    }

    @Test
    void aRateLimitThatOutlastsTheFirstRetriesIsStillWaitedOut() throws Exception {
        var result = fetchMatching("/throttled", "<artifactId>junit</artifactId>");
        assertEquals(0, result.exitCode, result.output);
        assertEquals(5, throttledRequests.get(), result.output);
    }

    @Test
    void eachWaitWithoutARetryAfterDoublesUpToTheCap() throws Exception {
        // sleep is shadowed by a function that only says how long it would wait
        var result = run("http://127.0.0.1:" + server.getAddress().getPort() + "/refused",
                "<artifactId>junit</artifactId>", "sleep() { echo \"slept $1\"; }; ", "60");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("slept 5\nslept 10\nslept 20\nslept 40\nslept 60\n"), result.output);
        assertTrue(result.output.contains("after 6 attempts and 135s of waiting"), result.output);
    }

    @Test
    void aRetryAfterWithALeadingZeroIsReadInBaseTen() throws Exception {
        var result = run("http://127.0.0.1:" + server.getAddress().getPort() + "/octal",
                "<artifactId>junit</artifactId>", "sleep() { echo \"slept $1\"; }; ", "60");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("slept 8"), result.output);
        assertEquals(2, octalRequests.get(), result.output);
    }

    @Test
    void aMatchingBodyPasses() throws Exception {
        var result = fetchMatching("/pom", "<artifactId>junit</artifactId>");
        assertEquals(0, result.exitCode, result.output);
        assertEquals("", result.output);
    }

    @Test
    void aBodyThatDoesNotMatchIsReported() throws Exception {
        var result = fetchMatching("/pom", "^[0-9a-f]{40}$");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("does not match"), result.output);
    }

    @Test
    void aFailedConnectionIsReportedByCurl() throws Exception {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/pom";
        server.stop(0);
        var result = run(url, "<artifactId>junit</artifactId>");
        assertEquals(1, result.exitCode, result.output);
        assertFalse(result.output.contains("handshake"), result.output);
    }

    /**
     * The host-distro jobs (#1972) pass {@code --core}: no echo server, fixture tools or GitHub
     * credential there, so the script must stop after section 6b and leave GitHub's interception
     * to the native job.
     */
    @Test
    void coreIsTheFlagThatStopsAfterSection6b() throws Exception {
        assertEquals(0, shell("source \"$1\" && core_only --core").exitCode);
        assertEquals(1, shell("source \"$1\" && core_only").exitCode);

        var script = Files.readString(SCRIPT);
        int stop = script.indexOf("core_only \"$@\" && report\n");
        assertTrue(stop > script.indexOf("# --- 6b."), "--core no longer stops after section 6b");
        assertTrue(stop < script.indexOf("# --- 7."), "--core no longer stops before section 7");
        int section2 = script.indexOf("# --- 2.");
        assertTrue(script.indexOf("if ! core_only \"$@\"; then", section2) < script.indexOf("# --- 3."),
                "--core no longer skips GitHub's interception in section 2");
    }

    @Test
    void reportFailsWhenACheckFailed() throws Exception {
        var failed = shell("source \"$1\" && TESTS=2 PASS=1 FAIL=1 ERRORS='  - one\\n' && report");
        assertEquals(1, failed.exitCode, failed.output);
        assertTrue(failed.output.contains("1/2 passed"), failed.output);
        assertEquals(0, shell("source \"$1\" && TESTS=1 PASS=1 FAIL=0 && report").exitCode);
    }

    private Result shell(String command) throws Exception {
        var process = new ProcessBuilder("bash", "--norc", "--noprofile", "-c", command, "test", SCRIPT.toString())
                .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script hung:\n" + output);
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {}

    private Result fetchMatching(String path, String pattern) throws Exception {
        return run("http://127.0.0.1:" + server.getAddress().getPort() + path, pattern);
    }

    // No coreutils timeout(1): macOS does not ship it, and the unit tests run there too.
    private Result run(String url, String pattern) throws Exception {
        // Every retry's wait, Retry-After included, is capped at 0 s, so the tests do not sleep.
        return run(url, pattern, "", "0");
    }

    private Result run(String url, String pattern, String prelude, String maxDelay) throws Exception {
        Path output = Files.createTempFile("fetch-matching", ".log");
        try {
            var builder = new ProcessBuilder("bash", "--norc", "--noprofile", "-c",
                    "source \"$1\" && " + prelude + "fetch_matching \"$2\" \"$3\"", "test", SCRIPT.toString(), url, pattern)
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().keySet().removeIf(name -> name.toLowerCase().endsWith("_proxy"));
            builder.environment().put("FETCH_MAX_DELAY", maxDelay);
            var process = builder.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            process.destroyForcibly();
            String text = Files.readString(output);
            assertTrue(finished, "fetch_matching hung:\n" + text);
            return new Result(process.exitValue(), text);
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
