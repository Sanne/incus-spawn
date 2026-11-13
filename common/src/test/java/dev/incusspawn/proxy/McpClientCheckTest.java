package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx branch --mcp-client} asks the server from inside the guest whether it is reached
 * (#1182). The script runs in a real shell against a {@code curl} that answers as the proxy's
 * bridge does.
 */
class McpClientCheckTest {

    @TempDir Path root;

    private static final String INIT = "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
            + "\"serverInfo\":{\"name\":\"isx\",\"version\":\"9.9\"}}}";
    private static final String TOOLS = "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}}";

    /** A curl that records its calls and answers as {@code mode} says the bridge would. */
    private Path curl(String mode) throws Exception {
        var bin = Files.createDirectories(root.resolve("bin"));
        var curl = bin.resolve("curl");
        Files.writeString(curl, """
                #!/bin/sh
                log=%1$s/curl.log
                printf '%%s\\n' "$*" >> "$log"
                prev=; for a in "$@"; do
                  case "$prev" in
                    -H) case "$a" in @*) cat "${a#@}" >> %1$s/headers ;; esac ;;
                    -D) if [ "%2$s" = ok ]; then printf 'HTTP/1.1 200 OK\\r\\nMcp-Session-Id: instance:coord:ab\\r\\n\\r\\n' > "$a";
                        else printf 'HTTP/1.1 403 Forbidden\\r\\n\\r\\n' > "$a"; fi ;;
                  esac; prev=$a; done
                case "$*" in *DELETE*) exit 0 ;; esac
                case "%2$s" in
                  refused) echo 'only an isx instance may call isx mcp'; printf '\\n@code 403\\n'; exit 0 ;;
                  down) echo 'curl: (6) Could not resolve host: mcp.isx.internal'; printf '\\n@code 000\\n'; exit 6 ;;
                esac
                case "$*" in
                  *initialize*) printf ': keepalive\\n\\n%%s\\n\\n' '%3$s' ;;
                  *tools/list*) printf '%%s\\n\\n' '%4$s' ;;
                esac
                printf '\\n@code 200\\n'
                """.formatted(root, mode, INIT, TOOLS));
        Files.setPosixFilePermissions(curl, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    private String runCheck(Path bin, String secret) throws Exception {
        var file = root.resolve("instance-secret");
        if (secret != null) Files.writeString(file, secret + "\n");
        var script = McpClientCheck.SCRIPT.replace(InstanceSecret.GUEST_PATH, file.toString())
                .replace(McpClientRegistration.CLAUDE_CONFIG, root.resolve("claude.json").toString())
                .replace(McpClientRegistration.PI_CONFIG, root.resolve("pi-mcp.json").toString());
        var pb = new ProcessBuilder("sh", "-c", script)
                .redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + McpClientRegistrationTest.guestPath(root.resolve("shim")));
        var run = pb.start();
        var out = new String(run.getInputStream().readAllBytes());
        assertEquals(0, run.waitFor(), out);
        return out;
    }

    @Test
    void aReachableServerReportsItsVersionAndTools() throws Exception {
        var secret = InstanceSecret.generate();
        // Claude Code's own config, as add-json leaves it
        Files.writeString(root.resolve("claude.json"), "{\"mcpServers\":{\"isx\":{\"type\":\"http\",\"url\": \"https://mcp.isx.internal/mcp\"}}}");
        var outcome = McpClientCheck.parse(runCheck(curl("ok"), secret));

        assertTrue(outcome.ok(), outcome.toString());
        assertEquals("9.9", outcome.version());
        assertEquals(3, outcome.tools());
        assertEquals("coord reaches isx mcp at https://mcp.isx.internal/mcp: isx 9.9, 3 tools.", outcome.describe("coord"));

        var calls = Files.readAllLines(root.resolve("curl.log"));
        assertEquals(4, calls.size(), "initialize, initialized, tools/list, and the session ended: " + calls);
        assertTrue(calls.getLast().contains("DELETE") && calls.getLast().contains("instance:coord:ab"), calls.getLast());
        assertTrue(calls.stream().noneMatch(c -> c.contains(secret)), "any guest user can read a command line");
        assertTrue(Files.readString(root.resolve("headers")).contains(InstanceSecret.HEADER + ": " + secret + "\n"));
    }

    @Test
    void aCoordinatorWhoseClaudeCodeWasNotRegisteredIsReported() throws Exception {
        // Reachable by hand, but its Claude Code has no tools: the case #1182 reported
        var outcome = McpClientCheck.parse(runCheck(curl("ok"), InstanceSecret.generate()));
        assertTrue(outcome.reached());
        assertFalse(outcome.ok());
        assertTrue(outcome.describe("coord").contains("none of its Claude Code, Pi or opencode has an 'isx' server"), outcome.describe("coord"));
    }

    @Test
    void aCoordinatorWhosePiIsRegisteredIsReady() throws Exception {
        // Pi's own config, as pi mcp add leaves it: a coordinator need not run Claude Code
        Files.writeString(root.resolve("pi-mcp.json"), """
                {
                  "mcpServers": {
                    "isx": {
                      "url": "https://mcp.isx.internal/mcp",
                      "headers": {
                        "X-Isx-Instance-Secret": "!cat /run/isx/instance-secret"
                      }
                    }
                  }
                }
                """);
        var outcome = McpClientCheck.parse(runCheck(curl("ok"), InstanceSecret.generate()));
        assertTrue(outcome.ok(), outcome.describe("coord"));
    }

    @Test
    void aConfigWithoutTheEntryIsNotRegistered() throws Exception {
        Files.writeString(root.resolve("claude.json"), "{\"mcpServers\":{\"other\":{\"url\":\"https://example.com/mcp\"}}}");
        assertFalse(McpClientCheck.parse(runCheck(curl("ok"), InstanceSecret.generate())).ok());
    }

    @Test
    void aConfigThatIsNoRegularFileIsNotRead() throws Exception {
        // The agent owns its home: a FIFO there must not stall root's read, and the branch with it
        var fifo = root.resolve("claude.json");
        new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor();
        assertFalse(McpClientCheck.parse(runCheck(curl("ok"), InstanceSecret.generate())).ok());
    }

    @Test
    void aRefusalSaysWhy() throws Exception {
        var outcome = McpClientCheck.parse(runCheck(curl("refused"), InstanceSecret.generate()));
        assertFalse(outcome.reached());
        assertEquals("HTTP 403: only an isx instance may call isx mcp", outcome.problem());
        assertEquals(1, Files.readAllLines(root.resolve("curl.log")).size(), "no session, nothing more to ask");
    }

    @Test
    void anUnreachableProxySaysWhatCurlSaw() throws Exception {
        var outcome = McpClientCheck.parse(runCheck(curl("down"), InstanceSecret.generate()));
        assertEquals("curl: (6) Could not resolve host: mcp.isx.internal", outcome.problem());
    }

    @Test
    void anInstanceWithoutASecretIsNotAsked() throws Exception {
        var outcome = McpClientCheck.parse(runCheck(curl("ok"), null));
        assertEquals("the instance holds no secret for this start", outcome.problem());
        assertFalse(Files.exists(root.resolve("curl.log")));
    }

    @Test
    void aJsonRpcErrorIsAProblem() {
        var outcome = McpClientCheck.parse("@init\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"boom\"}}\n\n@code 200\n");
        assertEquals("boom", outcome.problem());
    }

    @Test
    void whatTheGuestSaysCannotReachTheTerminalRaw() {
        var outcome = McpClientCheck.parse("@registered\n@init\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"serverInfo\":"
                + "{\"version\":\"1\\u001b]0;pwned\\u0007\\nok\"}}}\n\n@code 200\n@tools\n" + TOOLS + "\n\n@code 200\n");
        assertTrue(outcome.ok(), outcome.toString());
        var said = outcome.describe("coord");
        assertTrue(said.codePoints().noneMatch(c -> c < 0x20 || c == 0x7f), said);
        var refused = McpClientCheck.parse("@init\nno\u001b[2Jpe\n\n@code 403\n").describe("coord");
        assertTrue(refused.codePoints().noneMatch(c -> c < 0x20 || c == 0x7f), refused);
    }

    @Test
    void anErrorInAnEventStreamIsNotReportedAsItsFraming() {
        var outcome = McpClientCheck.parse("@init\nevent: message\nid: 1\nsession expired\n\n@code 404\n");
        assertEquals("HTTP 404: session expired", outcome.problem());
    }

    @Test
    void nothingReadableIsAProblem() {
        assertFalse(McpClientCheck.parse("").reached());
        assertFalse(McpClientCheck.parse("@init\n\n@code 200\n").reached());
    }
}
