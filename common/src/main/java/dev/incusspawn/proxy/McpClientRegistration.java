package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqObject;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.ToolDef;

import java.util.List;

/**
 * Registers {@code isx mcp} in the agents of an instance branched with {@code --mcp-client},
 * Claude Code (#1182), Pi and opencode, and takes it out of every other.
 *
 * <p>Reconciled from the {@link Metadata#MCP_CALLER} stamp on every isx start, by the exec that
 * already delivers the per-start secret ({@link InstanceSecret#GUEST_SCRIPT}): the host says
 * whether the instance holds the grant ({@link #ENV}), and a marker per agent in the rootfs says
 * whether isx registered the server there before. Only on a mismatch does the script run the
 * agent's own commands ({@code claude mcp add-json} or {@code claude mcp remove} at user scope,
 * {@code pi mcp add} or {@code pi mcp remove}, for opencode a Node edit of its config,
 * {@link #OPENCODE_EDIT}), so a steady-state start costs a few file tests
 * per agent and runs nothing as the agent, whichever agents the template has. A copy of a
 * coordinator carries the registrations and markers until its first isx start, which removes
 * them: no copy carries the stamp.
 *
 * <p>The registration is not the capability. The proxy decides who reaches the server by
 * address, per-start secret and stamp, so a registration left behind, or one a user adds by
 * hand, reaches nothing an instance without the stamp could not.
 *
 * <p>Root runs the script, and the agent can put its own {@code claude}, {@code pi} or {@code node} first on
 * the PATH they are run from ({@code claude} lives in its home anyway): each only ever runs as
 * the agent, never as root. The marker is root's, so nothing in the guest but this script
 * decides what isx believes it registered.
 */
public final class McpClientRegistration {

    /** The name the server is registered under in Claude Code, in an instance as on the host. */
    public static final String SERVER_NAME = "isx";
    /** The exec environment variable that says the instance holds the grant: {@code 1}, and nothing else, does. */
    public static final String ENV = "ISX_MCP_CLIENT";
    private static final String MARKER_DIR = "/var/lib/isx";
    /** Root's record that isx registered the server in this rootfs's Claude Code: {@link #ENTRY_VERSION}, of the entry it registered. */
    static final String MARKER = MARKER_DIR + "/mcp-client-registered";
    /** The same record for Pi: {@link #PI_ENTRY_VERSION}. */
    static final String PI_MARKER = MARKER_DIR + "/mcp-client-registered-pi";
    /** The same record for opencode: {@link #OPENCODE_ENTRY_VERSION}. */
    static final String OPENCODE_MARKER = MARKER_DIR + "/mcp-client-registered-opencode";
    private static final String AGENT_HOME = "/home/agentuser";
    /** Claude Code's user-scope config, where the entry lives. */
    static final String CLAUDE_CONFIG = ClaudeSetup.CLAUDE_JSON_PATH;
    /** Pi's user-level MCP config, where its entry lives. */
    static final String PI_CONFIG = AGENT_HOME + "/.pi/agent/mcp.json";
    /** opencode's global config, which {@code OpencodeSetup} writes and its {@code mcp} section is in. */
    static final String OPENCODE_CONFIG = AGENT_HOME + "/.config/opencode/opencode.json";
    /** How long root may spend reading an agent's config, and how much of it. */
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
    static final String CONFIG_HAS_ENTRY = configHasEntry(CLAUDE_CONFIG);

    /** {@link #CONFIG_HAS_ENTRY}'s test of Pi's config. */
    static final String PI_CONFIG_HAS_ENTRY = configHasEntry(PI_CONFIG);

    /** {@link #CONFIG_HAS_ENTRY}'s test of opencode's config. */
    static final String OPENCODE_CONFIG_HAS_ENTRY = configHasEntry(OPENCODE_CONFIG);

    /**
     * Shell test that some agent's config holds an entry for the endpoint, so the instance has
     * a client for it: what {@link McpClientCheck} reports.
     */
    static final String ANY_CONFIG_HAS_ENTRY = "{ " + CONFIG_HAS_ENTRY + " || " + PI_CONFIG_HAS_ENTRY
            + " || " + OPENCODE_CONFIG_HAS_ENTRY + "; }";

    private static String configHasEntry(String config) {
        return "{ [ -f " + config + " ] && timeout -k 1 " + CONFIG_READ_SECONDS + " head -c "
                + CONFIG_READ_BYTES + " " + config + " 2>/dev/null | grep -qF '" + ProxyConfig.MCP_DOMAIN + "/mcp\"'; }";
    }

    /**
     * The value of Pi's header: Pi runs a value that starts with {@code !} as a command, per
     * connection, so it sends the current secret, read by its path as {@link #HEADERS_HELPER} is.
     */
    static final String PI_HEADER_VALUE = "!cat " + InstanceSecret.GUEST_PATH;

    /**
     * The arguments {@code pi mcp add} takes for the entry, after the server's name. Its tools
     * are {@code direct}: declared to the model like Claude Code's, rather than Pi's default of
     * reaching them only from codemode scripts, since driving isx is a coordinator's main job and
     * the set is small.
     */
    static final List<String> PI_ADD_ARGS = List.of(
            "--url", "https://" + ProxyConfig.MCP_DOMAIN + "/mcp",
            "--header", InstanceSecret.HEADER + "=" + PI_HEADER_VALUE,
            "--exposure", "direct",
            "--description", "isx: create, run and delegate to disposable isx instances");

    /** {@link #ENTRY_VERSION} for Pi's entry. */
    static final String PI_ENTRY_VERSION = ToolDef.sha256hex("pi " + SERVER_NAME + " " + String.join(" ", PI_ADD_ARGS))
            .substring(0, 16);

    /**
     * opencode's entry: a remote server whose header opencode reads from the secret's file when it
     * loads its config, once per process ({@code {file:...}}), so an opencode started before a
     * rotation keeps the old secret; every rotation is a start of a stopped instance, which no
     * process outlives. A missing file fails opencode's config, and with it opencode, until isx
     * delivers the secret again, which every way into the instance through isx does first.
     * {@code oauth: false}, so a refusal is reported as one, never answered with an OAuth flow.
     */
    static final String OPENCODE_SERVER_JSON = JqObject.builder(4)
            .put("type", "remote")
            .put("url", "https://" + ProxyConfig.MCP_DOMAIN + "/mcp")
            .put("headers", JqObject.builder(1).put(InstanceSecret.HEADER, "{file:" + InstanceSecret.GUEST_PATH + "}").build())
            .put("oauth", false)
            .build().toJsonString();

    /** {@link #ENTRY_VERSION} for opencode's entry. */
    static final String OPENCODE_ENTRY_VERSION = ToolDef.sha256hex("opencode " + SERVER_NAME + " " + OPENCODE_SERVER_JSON)
            .substring(0, 16);

    /**
     * Node, run as the agent, that sets ({@code add}) or deletes ({@code remove}) the server's
     * entry under {@code mcp} in opencode's config: opencode has no command that removes one, and
     * its {@code mcp add} cannot set {@code oauth}. It reads the file as JSON, which is what
     * {@code OpencodeSetup} writes; a file it cannot parse (a person's comments) is left as it is,
     * and the failure leaves the marker too, so the next start tries again. It replaces the file
     * by a rename, keeping its mode; a link (a dotfiles resource) is followed, so the rename
     * replaces its target, never the link.
     */
    static final String OPENCODE_EDIT = "const fs=require('fs'),path=require('path');"
            + "let f='" + OPENCODE_CONFIG + "';try{f=fs.realpathSync(f)}catch(e){}"
            + "const add=process.argv[1]==='add';let c={},mode=0o644;"
            + "try{mode=fs.statSync(f).mode&0o777;c=JSON.parse(fs.readFileSync(f,'utf8'))}"
            + "catch(e){if(e.code!=='ENOENT')process.exit(1);if(!add)process.exit(0)}"
            + "if(!c||typeof c!=='object'||Array.isArray(c))process.exit(1);"
            + "const m=c.mcp&&typeof c.mcp==='object'&&!Array.isArray(c.mcp)?c.mcp:{};"
            + "if(add)m." + SERVER_NAME + "=" + OPENCODE_SERVER_JSON + ";"
            + "else if(!m." + SERVER_NAME + ")process.exit(0);"
            + "else delete m." + SERVER_NAME + ";"
            + "c.mcp=m;fs.mkdirSync(path.dirname(f),{recursive:true});"
            + "const t=f+'.isx-'+process.pid;fs.writeFileSync(t,JSON.stringify(c,null,2)+'\\n',{mode});fs.renameSync(t,f)";

    /** The PATH the agent's commands run with, the agent's own directory first. */
    private static final List<String> AGENT_PATH = List.of(AGENT_HOME + "/.local/bin", "/usr/local/bin", "/usr/bin", "/bin");

    /**
     * Shell defining {@code isx_as_agent}: runs its arguments as the agent, never as root, since
     * the agent can replace anything in its home, {@code claude} included. A clean environment,
     * no stdin, and a bound on how long it may take.
     */
    static final String AS_AGENT = "isx_as_agent() { (cd " + AGENT_HOME + " && timeout 10 runuser -u agentuser -- env -i HOME="
            + AGENT_HOME + " PATH=" + String.join(":", AGENT_PATH) + " DISABLE_AUTOUPDATER=1 \"$@\")"
            + " </dev/null; }; ";

    /**
     * Shell test that {@code binary} is on {@link #AGENT_PATH}: root's own file tests, so an
     * instance without the agent spends no process on asking, on any start. Each only stats the
     * path, never opens it, so nothing the agent puts there can make root block. A binary that
     * root sees as executable and the agent cannot run fails its command, which leaves the marker
     * as it was, as any failure does.
     */
    static String onAgentPath(String binary) {
        return "{ " + String.join(" || ", AGENT_PATH.stream().map(d -> "[ -x " + d + "/" + binary + " ]").toList()) + "; }";
    }

    private McpClientRegistration() {}

    /**
     * Shell, run as root after the secret is in place, that brings each agent's registration in
     * line with {@link #ENV}. Idempotent, since it rides in scripts retried until they answer, and
     * best-effort like the delivery: an instance whose agent is missing or fails keeps that
     * agent's marker as it was, so the next start tries again, and the start goes on. A copy gets
     * one removal attempt per agent: its marker goes with it whatever the removal answered, so an
     * entry it could not remove (one by another name or scope, a removal that timed out) costs
     * no start after the first. Such a leftover entry reaches nothing: the copy has no stamp,
     * and the proxy refuses it.
     */
    static final String GUEST_SCRIPT = "isx_mcp=$" + ENV + "; unset " + ENV + "; { " + AS_AGENT
            // Remove first either way: add-json refuses a name already there, as a hand-made one would be
            + reconcile(onAgentPath("claude"), MARKER, ENTRY_VERSION, CONFIG_HAS_ENTRY, true,
                    "claude mcp remove --scope user " + SERVER_NAME,
                    "claude mcp add-json --scope user " + SERVER_NAME + " " + Container.shellQuote(SERVER_JSON))
            // pi mcp add replaces an entry of the same name, so only a copy removes it
            + reconcile(onAgentPath("pi"), PI_MARKER, PI_ENTRY_VERSION, PI_CONFIG_HAS_ENTRY, false,
                    "pi mcp remove " + SERVER_NAME,
                    "pi mcp add " + SERVER_NAME + " "
                            + String.join(" ", PI_ADD_ARGS.stream().map(Container::shellQuote).toList()))
            // Set in place, so it is never removed first either; the edit is Node's, which opencode's own
            // binary does not need, so an instance without it is left to try again rather than run a missing command
            + reconcile(onAgentPath("opencode") + " && " + onAgentPath("node"), OPENCODE_MARKER, OPENCODE_ENTRY_VERSION, OPENCODE_CONFIG_HAS_ENTRY, false,
                    "node -e " + Container.shellQuote(OPENCODE_EDIT) + " remove",
                    "node -e " + Container.shellQuote(OPENCODE_EDIT) + " add")
            + "} >/dev/null 2>&1; unset isx_mcp isx_have; true";

    /**
     * Shell reconciling one agent: {@code add} (after {@code remove} when
     * {@code removeFirst}) when granted and its marker or config does not show {@code version};
     * {@code remove} once when not granted and the marker is there. Both run through
     * {@code isx_as_agent}, only when {@code present}, root's test that what they run is on the
     * agent's PATH ({@link #onAgentPath}), holds.
     */
    private static String reconcile(String present, String marker, String version, String hasEntry,
                                    boolean removeFirst, String remove, String add) {
        return "if [ \"$isx_mcp\" = 1 ]; then isx_have=; [ -e " + marker + " ] && read -r isx_have < " + marker + "; "
                + "{ [ \"$isx_have\" = " + version + " ] && " + hasEntry + "; } || { " + present
                + " && { " + (removeFirst ? "isx_as_agent " + remove + "; " : "") + "isx_as_agent " + add
                + " && install -d -m 755 " + MARKER_DIR + " && printf '%s\\n' " + version + " > " + marker + "; }; }; "
                + "else [ -e " + marker + " ] && " + present
                + " && { isx_as_agent " + remove + "; rm -f " + marker + "; }; fi; ";
    }
}
