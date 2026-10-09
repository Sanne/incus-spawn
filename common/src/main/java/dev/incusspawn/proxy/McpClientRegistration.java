package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqObject;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef;

/**
 * Registers {@code isx mcp} in the Claude Code of an instance branched with
 * {@code --mcp-client}, and takes it out of every other (#1182).
 *
 * <p>Reconciled from the {@link Metadata#MCP_CALLER} stamp on every isx start, by the exec that
 * already delivers the per-start secret ({@link InstanceSecret#GUEST_SCRIPT}): the host says
 * whether the instance holds the grant ({@link #ENV}), and a marker in the rootfs says whether
 * isx registered the server there before. Only on a mismatch does the script run Claude Code's
 * own {@code claude mcp add-json} or {@code claude mcp remove} (user scope), so a steady-state
 * start costs one file test. A copy of a coordinator carries the registration and the marker
 * until its first isx start, which removes both: no copy carries the stamp.
 *
 * <p>The registration is not the capability. The proxy decides who reaches the server by
 * address, per-start secret and stamp, so a registration left behind, or one a user adds by
 * hand, reaches nothing an instance without the stamp could not.
 *
 * <p>Root runs the script, and {@code claude} lives in the agent's home, where the agent can
 * replace it: it only ever runs as the agent, never as root. The marker is root's, so nothing
 * in the guest but this script decides what isx believes it registered.
 */
public final class McpClientRegistration {

    /** The name the server is registered under in Claude Code, in an instance as on the host. */
    public static final String SERVER_NAME = "isx";
    /** The exec environment variable that says the instance holds the grant: {@code 1}, and nothing else, does. */
    public static final String ENV = "ISX_MCP_CLIENT";
    private static final String MARKER_DIR = "/var/lib/isx";
    /** Root's record that isx registered the server in this rootfs: {@link #ENTRY_VERSION}, of the entry it registered. */
    static final String MARKER = MARKER_DIR + "/mcp-client-registered";
    private static final String AGENT_HOME = "/home/agentuser";
    /** Claude Code's user-scope config, where the entry lives. */
    static final String CLAUDE_CONFIG = AGENT_HOME + "/.claude.json";
    /** How long root may spend reading {@link #CLAUDE_CONFIG}, and how much of it. */
    static final int CONFIG_READ_SECONDS = 2;
    static final int CONFIG_READ_BYTES = 16 << 20;

    /**
     * What Claude Code runs before each request for the headers to send: the secret, read by its
     * absolute path, so it also works for a {@code claude -p} under a systemd unit that never
     * read the login profile naming it ({@link InstanceSecret#FILE_ENV_VAR}).
     */
    static final String HEADERS_HELPER = "printf '{\"" + InstanceSecret.HEADER + "\":\"%s\"}' \"$(cat "
            + InstanceSecret.GUEST_PATH + ")\"";

    /** The server's entry, as {@code claude mcp add-json} takes it. */
    static final String SERVER_JSON = JqObject.builder(3)
            .put("type", "http")
            .put("url", "https://" + ProxyConfig.MCP_DOMAIN + "/mcp")
            .put("headersHelper", HEADERS_HELPER)
            .build().toJsonString();

    /**
     * What the marker records: a digest of {@link #SERVER_JSON}, so a coordinator registered by
     * an isx whose entry differed (another URL or headers helper) is registered again on its next
     * start, at no cost to a start that finds it current.
     */
    static final String ENTRY_VERSION = ToolDef.sha256hex("user " + SERVER_NAME + " " + SERVER_JSON).substring(0, 16);

    /**
     * Shell test that Claude Code's own user config holds an entry for the endpoint: what Claude
     * Code loads, which the marker alone cannot vouch for once the agent or a rewrite of the file
     * has removed it. Read as a file, never through {@code claude mcp get}, which connects to the
     * server.
     *
     * <p>The file is the agent's, read by root in an exec with no time limit of its own, so the
     * read is bounded in both time and size: the agent may put a FIFO there (after the regular
     * file test as easily as before it), a link to a device, or a sparse file of any size. A
     * read cut short answers "no entry", which costs a re-registration, never a hang.
     */
    static final String CONFIG_HAS_ENTRY = "{ [ -f " + CLAUDE_CONFIG + " ] && timeout -k 1 " + CONFIG_READ_SECONDS + " head -c "
            + CONFIG_READ_BYTES + " " + CLAUDE_CONFIG + " 2>/dev/null | grep -qF '" + ProxyConfig.MCP_DOMAIN + "/mcp\"'; }";

    /**
     * Shell defining {@code isx_as_agent}: runs its arguments as the agent, never as root, since
     * the agent can replace anything in its home, {@code claude} included. A clean environment,
     * no stdin, and a bound on how long it may take.
     */
    static final String AS_AGENT = "isx_as_agent() { (cd " + AGENT_HOME + " && timeout 10 runuser -u agentuser -- env -i HOME="
            + AGENT_HOME + " PATH=" + AGENT_HOME + "/.local/bin:/usr/local/bin:/usr/bin:/bin DISABLE_AUTOUPDATER=1 \"$@\")"
            + " </dev/null; }; ";

    private McpClientRegistration() {}

    /**
     * Shell, run as root after the secret is in place, that brings the registration in line with
     * {@link #ENV}. Idempotent, since it rides in scripts retried until they answer, and
     * best-effort like the delivery: an instance whose Claude Code is missing or fails keeps its
     * marker as it was, so the next start tries again, and the start goes on. A copy gets one
     * removal attempt: its marker goes with it whatever {@code claude mcp remove} answered, so an
     * entry it could not remove (one by another name or scope, a removal that timed out) costs
     * no start after the first. Such a leftover entry reaches nothing: the copy has no stamp,
     * and the proxy refuses it.
     */
    static final String GUEST_SCRIPT = "isx_mcp=$" + ENV + "; unset " + ENV + "; { " + AS_AGENT
            // Remove first either way: add-json refuses a name already there, as a hand-made one would be
            + "if [ \"$isx_mcp\" = 1 ]; then isx_have=; [ -e " + MARKER + " ] && read -r isx_have < " + MARKER + "; "
            + "{ [ \"$isx_have\" = " + ENTRY_VERSION + " ] && " + CONFIG_HAS_ENTRY + "; } || { isx_as_agent sh -c 'command -v claude'"
            + " && { isx_as_agent claude mcp remove --scope user " + SERVER_NAME + "; "
            + "isx_as_agent claude mcp add-json --scope user " + SERVER_NAME + " " + Container.shellQuote(SERVER_JSON)
            + " && install -d -m 755 " + MARKER_DIR + " && printf '%s\\n' " + ENTRY_VERSION + " > " + MARKER + "; }; }; "
            + "else [ -e " + MARKER + " ] && isx_as_agent sh -c 'command -v claude'"
            + " && { isx_as_agent claude mcp remove --scope user " + SERVER_NAME + "; rm -f " + MARKER + "; }; fi; "
            + "} >/dev/null 2>&1; unset isx_mcp isx_have; true";
}
