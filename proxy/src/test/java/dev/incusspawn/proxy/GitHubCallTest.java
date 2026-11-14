package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class GitHubCallTest {

    private static final String ZERO = "0".repeat(40);
    private static final String SHA = "a".repeat(40);

    @TempDir
    Path dir;

    private static String pkt(String payload) {
        return String.format("%04x", payload.length() + 4) + payload;
    }

    private static byte[] pushBody(String... commands) {
        var sb = new StringBuilder();
        for (var i = 0; i < commands.length; i++) {
            sb.append(pkt(commands[i] + (i == 0 ? "\0 report-status side-band-64k agent=git/2" : "")));
        }
        return (sb + "0000PACK\0\0binary pack that is never read").getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void classifiesOnlyWhatTheLogRecords() {
        assertEquals(GitHubCall.Kind.PUSH, GitHubCall.classify("github.com", "POST", "/o/r.git/git-receive-pack", false));
        assertEquals(GitHubCall.Kind.PUSH, GitHubCall.classify("github.com", "POST", "/o/r/git-receive-pack", false));
        assertNull(GitHubCall.classify("github.com", "GET", "/o/r.git/info/refs", false));
        assertNull(GitHubCall.classify("github.com", "POST", "/o/r.git/git-upload-pack", false));
        assertNull(GitHubCall.classify("github.com", "POST", "/o/r.git/git-receive-pack", true), "an encoded body is not read");
        assertNull(GitHubCall.classify("evil.example", "POST", "/o/r.git/git-receive-pack", false));
        assertEquals(GitHubCall.Kind.PULL_REST, GitHubCall.classify("api.github.com", "POST", "/repos/o/r/pulls", false));
        assertNull(GitHubCall.classify("api.github.com", "GET", "/repos/o/r/pulls", false));
        assertNull(GitHubCall.classify("api.github.com", "POST", "/repos/o/r/pulls/3/comments", false));
        assertEquals(GitHubCall.Kind.PULL_GRAPHQL, GitHubCall.classify("api.github.com", "POST", "/graphql", false));
    }

    @Test
    void readsPushCommandsAndStopsAtTheFlushOrAnythingElse() {
        var body = pushBody(ZERO + " " + SHA + " refs/heads/new", SHA + " " + SHA + " refs/heads/main",
                SHA + " " + ZERO + " refs/heads/gone");
        var c = GitHubCall.pushCommands(body, body.length);
        assertEquals(java.util.List.of("refs/heads/new", "refs/heads/main"), c.refs());
        assertEquals(java.util.List.of("refs/heads/gone"), c.deleted());

        var cut = GitHubCall.pushCommands(body, 200);
        assertEquals(1, cut.refs().size() + cut.deleted().size(), "a line cut off is not read");

        var garbage = "zzzz not a pkt line at all".getBytes(StandardCharsets.ISO_8859_1);
        var none = GitHubCall.pushCommands(garbage, garbage.length);
        assertTrue(none.refs().isEmpty() && none.deleted().isEmpty());
    }

    @Test
    void aShallowClonesShallowLinesAreSkipped() {
        var body = (pkt("shallow " + SHA) + pkt(ZERO + " " + SHA + " refs/heads/x\0 report-status") + "0000PACK")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(java.util.List.of("refs/heads/x"), GitHubCall.pushCommands(body, body.length).refs());
    }

    @Test
    void aLongRefNameIsBounded() {
        var body = pushBody(ZERO + " " + SHA + " refs/heads/" + "x".repeat(400));
        var c = GitHubCall.pushCommands(body, body.length);
        assertEquals(GitHubCall.MAX_NAME, c.refs().getFirst().length());
    }

    private java.util.List<String> run(java.util.function.Consumer<EventLog> act) throws Exception {
        var file = dir.resolve("events.jsonl");
        try (var log = new EventLog(file)) {
            act.accept(log);
            log.flush();
        }
        return Files.exists(file) ? Files.readAllLines(file) : java.util.List.of();
    }

    @Test
    void aPushThatGitHubAcceptedIsOneLineWithItsBranches() throws Exception {
        var now = new AtomicLong(1_000);
        var lines = run(log -> {
            var call = new GitHubCall(log, "w", "inc", GitHubCall.Kind.PUSH, "/o/r.git/git-receive-pack", now.get());
            var body = pushBody(ZERO + " " + SHA + " refs/heads/topic");
            // split across chunks, and the pack after the flush is never kept
            call.request(Buffer.buffer(java.util.Arrays.copyOfRange(body, 0, 20)));
            call.request(Buffer.buffer(java.util.Arrays.copyOfRange(body, 20, body.length)));
            call.respond(200);
            now.set(1_300);
            call.end(now.get());
            call.end(now.get());
        });
        assertEquals(1, lines.size());
        var line = Jjq.parseForeign(lines.get(0).getBytes(), "line");
        assertEquals("github", line.path("kind").asText());
        assertEquals("push", line.path("op").asText());
        assertEquals("o/r", line.path("repo").asText());
        assertEquals("w", line.path("instance").asText());
        assertTrue(lines.get(0).contains("\"refs\":[\"refs/heads/topic\"]"));
        assertEquals(300, line.path("ms").asLong(0));
        assertFalse(lines.get(0).contains("PACK"));
    }

    @Test
    void aFailedOrRefusedCallCostsTheLogNothing() throws Exception {
        var lines = run(log -> {
            var body = pushBody(ZERO + " " + SHA + " refs/heads/topic");
            var push = new GitHubCall(log, "w", "", GitHubCall.Kind.PUSH, "/o/r.git/git-receive-pack", 0);
            push.request(Buffer.buffer(body));
            push.respond(403);
            push.end(5);
            var pr = new GitHubCall(log, "w", "", GitHubCall.Kind.PULL_REST, "/repos/o/r/pulls", 0);
            pr.response(Buffer.buffer("{\"message\":\"Validation Failed\"}"));
            pr.respond(422);
            pr.end(5);
        });
        assertTrue(lines.isEmpty());
    }

    @Test
    void aPullRequestOpenedIsLoggedWithItsNumberFromRestOrGraphQl() throws Exception {
        var lines = run(log -> {
            var rest = new GitHubCall(log, "w", "", GitHubCall.Kind.PULL_REST, "/repos/o/r/pulls", 0);
            rest.response(Buffer.buffer("{\"url\":\"https://api.github.com/repos/o/r/pulls/7\",\"id\":1,"
                    + "\"html_url\":\"https://github.com/o/r/pull/7\",\"body\":\"SECRET\"}"));
            rest.respond(201);
            rest.end(10);

            var gql = new GitHubCall(log, "w", "", GitHubCall.Kind.PULL_GRAPHQL, "/graphql", 0);
            gql.request(Buffer.buffer("{\"query\":\"mutation PullRequestCreate($input: CreatePullRequestInput!) "
                    + "{ createPullRequest(input: $input) { pullRequest { url } } }\",\"variables\":{}}"));
            gql.response(Buffer.buffer("{\"data\":{\"createPullRequest\":{\"pullRequest\":"
                    + "{\"url\":\"https://github.com/o/r2/pull/12\"}}}}"));
            gql.respond(200);
            gql.end(10);

            var other = new GitHubCall(log, "w", "", GitHubCall.Kind.PULL_GRAPHQL, "/graphql", 0);
            other.request(Buffer.buffer("{\"query\":\"query { viewer { login } }\"}"));
            other.response(Buffer.buffer("{\"url\":\"https://github.com/o/r/pull/99\"}"));
            other.respond(200);
            other.end(10);
        });
        assertEquals(2, lines.size());
        var a = Jjq.parseForeign(lines.get(0).getBytes(), "line");
        assertEquals("pull_request", a.path("op").asText());
        assertEquals("o/r", a.path("repo").asText());
        assertEquals(7, a.path("number").asLong(0));
        assertFalse(lines.get(0).contains("SECRET"));
        var b = Jjq.parseForeign(lines.get(1).getBytes(), "line");
        assertEquals("o/r2", b.path("repo").asText());
        assertEquals(12, b.path("number").asLong(0));
    }

    @Test
    void anActivityWithNoLogBeginsNothing() {
        assertNull(new ApiActivity().beginGitHub("w", "", "github.com", "POST", "/o/r.git/git-receive-pack", false));
    }
}
