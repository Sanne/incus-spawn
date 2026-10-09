package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.OutputFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether an instance branched with {@code --mcp-client} reaches {@code isx mcp} (#1182): one
 * {@code initialize} and one {@code tools/list} from inside the guest, the way its Claude Code
 * will call, so {@code isx branch} reports a coordinator that cannot work while the user is
 * still there to see it.
 */
public final class McpClientCheck {

    /**
     * What the check found: whether the start registered the server in the instance's Claude
     * Code, and the server's version and tool count, or why it was not reached.
     */
    public record Outcome(boolean registered, String version, int tools, String problem) {
        public boolean reached() {
            return problem == null;
        }

        /** Reached, and registered: a coordinator whose Claude Code has the tools. */
        public boolean ok() {
            return reached() && registered;
        }

        /** What {@code isx branch} says about instance {@code name}. */
        public String describe(String name) {
            var endpoint = "isx mcp at https://" + ProxyConfig.MCP_DOMAIN + "/mcp";
            // What the guest printed may be anything: its curl, its proxy answer, its version
            var said = reached() ? name + " reaches " + endpoint + ": isx " + OutputFormat.oneLine(version) + ", " + tools + " tools"
                    : name + " cannot reach " + endpoint + ": " + OutputFormat.oneLine(problem);
            return said + (registered ? "." : (reached() ? "; but" : ", and") + " its Claude Code has no '"
                    + McpClientRegistration.SERVER_NAME + "' server in its config: the template has no claude tool, or"
                    + " registering it failed (the next start tries again).");
        }
    }

    /**
     * Shell, run as root in the guest. The secret goes to curl in a header file only root can
     * read, never on its command line; the session is ended afterwards, so it does not wait for
     * the coordinator's own {@code initialize} to replace it. Prints {@code @init}, then
     * {@code @tools} once a session was opened, each followed by curl's output and
     * {@code @code <status>}; or {@code @error <why>} when it cannot try. First,
     * {@code @registered} when Claude Code's own user config holds an entry for the endpoint
     * ({@link McpClientRegistration#CONFIG_HAS_ENTRY}, the test the start's reconcile makes too).
     */
    static final String SCRIPT = """
            %4$s && echo @registered
            command -v curl >/dev/null 2>&1 || { echo '@error curl is not installed in the instance'; exit 0; }
            [ -s %1$s ] || { echo '@error the instance holds no secret for this start'; exit 0; }
            d=$(mktemp -d) || { echo '@error mktemp failed'; exit 0; }
            trap 'rm -rf "$d"' EXIT
            (umask 077 && printf '%2$s: %%s\\n' "$(cat %1$s)" > "$d/h")
            u=https://%3$s/mcp
            isx_post() { t=$1; shift; curl -sS --max-time "$t" -H @"$d/h" -H 'Content-Type: application/json' \
              -H 'Accept: application/json, text/event-stream' -w '\\n@code %%{http_code}\\n' "$@" "$u" 2>&1; }
            echo @init
            isx_post 30 -D "$d/r" --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"isx-branch-check","version":"1"}}}'
            sid=$(sed -n 's/^[Mm][Cc][Pp]-[Ss][Ee][Ss][Ss][Ii][Oo][Nn]-[Ii][Dd]: *//p' "$d/r" 2>/dev/null | tr -d '\\r')
            [ -n "$sid" ] || exit 0
            isx_post 5 -H "Mcp-Session-Id: $sid" --data '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
            echo @tools
            isx_post 10 -H "Mcp-Session-Id: $sid" --data '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
            curl -sS --max-time 5 -H @"$d/h" -H "Mcp-Session-Id: $sid" -X DELETE "$u" >/dev/null 2>&1
            true
            """.formatted(InstanceSecret.GUEST_PATH, InstanceSecret.HEADER, ProxyConfig.MCP_DOMAIN,
                    McpClientRegistration.CONFIG_HAS_ENTRY);

    private McpClientCheck() {}

    /** Run the check in running instance {@code name}. Never throws: a failure is an outcome. */
    public static Outcome run(IncusClient incus, String name) {
        try {
            var result = incus.shellExec(name, "sh", "-c", SCRIPT);
            if (result.stdout().isBlank()) {
                return new Outcome(false, null, 0, "the check did not run (exit code " + result.exitCode() + ")");
            }
            return parse(result.stdout());
        } catch (RuntimeException e) {
            return new Outcome(false, null, 0, "the check did not run: " + e.getMessage());
        }
    }

    /** What {@link #SCRIPT} printed, read. */
    static Outcome parse(String stdout) {
        var init = new ArrayList<String>();
        var tools = new ArrayList<String>();
        List<String> current = null;
        var registered = stdout.lines().anyMatch("@registered"::equals);
        for (var line : stdout.lines().toList()) {
            if (line.startsWith("@error ")) return new Outcome(registered, null, 0, line.substring("@error ".length()));
            if (line.equals("@init")) current = init;
            else if (line.equals("@tools")) current = tools;
            else if (current != null) current.add(line);
        }
        String version = null;
        try {
            version = answer(init).path("serverInfo").path("version").asText("");
            if (tools.isEmpty()) return new Outcome(registered, version, 0, "isx mcp opened no session");
            return new Outcome(registered, version, answer(tools).path("tools").size(), null);
        } catch (Unanswered e) {
            return new Outcome(registered, version, 0, (version == null ? "" : "tools/list: ") + e.getMessage());
        }
    }

    /** Why a call got no JSON-RPC result. */
    private static final class Unanswered extends Exception {
        Unanswered(String why) {
            super(why, null, false, false);
        }
    }

    /** One curl call's output: its JSON-RPC result. */
    private static JqValue answer(List<String> lines) throws Unanswered {
        String code = null;
        var body = new ArrayList<String>();
        for (var line : lines) {
            if (line.startsWith("@code ")) code = line.substring("@code ".length()).strip();
            // Event-stream framing and keepalive comments say nothing about the answer
            else if (!line.isBlank() && !line.startsWith(":") && !line.startsWith("event:")
                    && !line.startsWith("id:") && !line.startsWith("retry:")) body.add(line);
        }
        if (lines.isEmpty()) throw new Unanswered("the check printed nothing it could read");
        if (!"200".equals(code)) {
            var why = body.isEmpty() ? "no answer" : body.getFirst().strip();
            throw new Unanswered(code == null || code.equals("000") ? why : "HTTP " + code + ": " + why);
        }
        for (var line : body) {
            // An event stream's one message, or a plain JSON answer
            var json = line.startsWith("data:") ? line.substring("data:".length()).strip() : line.strip();
            if (!json.startsWith("{")) continue;
            try {
                var message = JqValues.parse(json);
                if (message instanceof JqObject obj && obj.keys().contains("error")) {
                    throw new Unanswered(obj.getField("error").pathText("message", "error"));
                }
                if (message instanceof JqObject res && res.keys().contains("result")) {
                    return res.getField("result");
                }
            } catch (RuntimeException notJson) {
                // keep looking
            }
        }
        throw new Unanswered("no JSON-RPC answer in the response");
    }
}
