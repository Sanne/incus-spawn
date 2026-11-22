package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The stdio-to-Streamable-HTTP bridge an agent that reads no variable and runs no header helper
 * in its MCP config is given (#2476): Antigravity CLI registers it as a stdio server, and it reads
 * the credential from its variable (as agy started it) or the instance's secret file (when it sends each request), so
 * no token is ever written into the agent's config. Node runs it ({@code AntigravitySetup}
 * installs {@code nodejs}); it is root's file in the guest, which the agent cannot rewrite.
 */
public final class McpStdioBridge {

    /** Where the guest has it. */
    public static final String PATH = "/usr/local/lib/isx/mcp-bridge.cjs";
    private static final String RESOURCE = "mcp/isx-mcp-bridge.cjs";

    private McpStdioBridge() {}

    /** The script's source. */
    public static String script() {
        try (var in = McpStdioBridge.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Puts the script in the guest, readable and never writable by the agent. */
    public static void install(Container c) {
        c.sh("install -d -m 755 -o root -g root " + PATH.substring(0, PATH.lastIndexOf('/'))).assertSuccess("Failed to create the MCP bridge's directory");
        c.writeFile(PATH, script());
        c.sh("chown root:root " + PATH + " && chmod 0644 " + PATH).assertSuccess("Failed to install the MCP bridge");
    }

    /** The command and arguments that run the bridge to {@code url}, the token read from {@code variable} when agy starts the bridge. */
    public static List<String> commandForBearer(String url, String variable) {
        return List.of("node", PATH, "--url", url, "--bearer-env", variable);
    }
}
