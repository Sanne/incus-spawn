package dev.incusspawn.mcp;

import dev.incusspawn.config.ImageDef;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Everything the MCP tools do to Incus, behind one seam so the session logic can be tested
 * without a daemon. {@link IncusInstanceBackend} is the real one.
 */
interface InstanceBackend {

    /**
     * Not a config key: the instance's Incus status ({@code Running}, {@code Stopped}, ...), which
     * {@link #metadata} and {@link #mcpInstances} add from the same read, so knowing it costs no
     * request. No {@code user.incus-spawn.*} key can collide with it.
     */
    String STATUS = "status";

    static boolean stopped(Map<String, String> metadata) {
        return "Stopped".equals(metadata.get(STATUS));
    }

    /** Whether Incus says it runs; true when the read carried no status, so nothing is refused on a guess. */
    static boolean running(Map<String, String> metadata) {
        return metadata.getOrDefault(STATUS, "Running").equals("Running");
    }

    /**
     * What {@code list_templates} shows about one template. {@code delegateModel} is the model
     * its Claude Code is configured with, null for Claude Code's own default. {@code projectLocal} when the
     * image was built from a repository's {@code .incus-spawn/} definitions. {@code definitions}
     * are the ones it was described from, which {@link #create} branches with rather than
     * loading them again; never shown to the agent.
     */
    record TemplateInfo(String name, String description, boolean built, boolean stale,
                        List<String> tools, String delegateModel, boolean projectLocal,
                        Map<String, ImageDef> definitions) {
        boolean supportsDelegate() {
            return tools.contains("claude");
        }
    }

    /**
     * Every template the trusted definitions know (never the working directory's
     * {@code .incus-spawn/}), with what Incus says about each.
     */
    List<TemplateInfo> templates();

    /** One template, as {@link #templates()} would describe it, without listing every instance. */
    java.util.Optional<TemplateInfo> template(String name);

    /**
     * Branch {@code template}, as {@link #template} described it, into {@code name} exactly as
     * {@code isx branch} would, with {@code stamps} written by the copy itself. Throws
     * {@link ToolError} on refusal.
     */
    CreatedInstance create(TemplateInfo template, String name, Map<String, String> stamps);

    /**
     * Branch the instance {@code source}, which descends from {@code lineage}, into {@code name}
     * exactly as {@code isx branch <name> --from <source>} would: the source's account pins and
     * files, every other setting at its default (full network, as every agent's instance has),
     * with {@code stamps} written by the copy itself. Throws
     * {@link ToolError} on refusal.
     */
    CreatedInstance fork(TemplateInfo lineage, String source, String name, Map<String, String> stamps);

    /** {@code accounts} are the credential account pins the branch stamped, by namespace. */
    record CreatedInstance(String name, String ip, String workdir, Map<String, String> accounts) {}

    /**
     * Delete an instance known to exist, as {@code isx destroy} does. Does not tell the proxy:
     * call {@link #refreshProxy()} once after a batch.
     */
    void destroy(String name);

    /**
     * Destroy an instance only if {@code session} still holds it -- and, with {@code onlyIfStopped},
     * only if it is still stopped -- for a caller that does not: the orphan sweep. Marks it {@link dev.incusspawn.incus.Metadata#OP_DELETING} first and
     * re-reads the holder under that mark; an adoption stamps first and reads the mark after
     * ({@link McpSession#adopt}), so of the two, one always sees the other. Returns whether it
     * was destroyed, and throws {@link ToolError} when another isx process holds its lock; like
     * {@link #destroy}, does not tell the proxy.
     */
    boolean destroyIfHeldBy(String name, String session, boolean onlyIfStopped);

    /**
     * Stop a running instance, as the TUI does: under its lock, marked
     * {@link dev.incusspawn.incus.Metadata#OP_STOPPING} meanwhile. Throws {@link ToolError} when another isx process holds the lock or the stop fails.
     */
    void stop(String name);

    /**
     * Start a stopped instance as {@code isx shell} would before opening a shell: proxy check,
     * static IP and CA repair, then the start, under the instance's lock. Throws
     * {@link ToolError} on refusal.
     */
    void start(String name);

    /** Tell the proxy instances went away, so a reused address never maps to one of them. */
    void refreshProxy();

    /**
     * The instance's {@code user.incus-spawn.*} config and its {@link #STATUS}, or null if Incus says it does not exist.
     * Throws when Incus cannot be asked, so that is never mistaken for the instance being gone.
     */
    Map<String, String> metadata(String name);

    /**
     * The account an instance pinned to {@code pinned} (null for none) uses in
     * {@code namespace} now: the pin, or the configured default, which the user may change at
     * any time. A pin that does not resolve to a usable account is returned as it was pinned.
     */
    String effectiveAccount(String namespace, String pinned);

    /** Set config keys in one write; a null value removes the key. */
    void stamp(String name, Map<String, String> config);

    /** Instances whose config carries an MCP session, with their config and {@link #STATUS}. */
    Map<String, Map<String, String>> mcpInstances();

    /** Run a script as agentuser in a login shell. */
    int exec(String name, String script, InputStream stdin, OutputStream stdout, OutputStream stderr);

    /**
     * Run one of isx's own read-only scripts as agentuser, but without a login shell (nothing
     * the user's profile does runs first), killed once {@code limit} has passed and given up on
     * shortly after (throwing): for asking an instance that may never answer something the
     * caller can do without.
     */
    int probe(String name, String script, OutputStream stdout, Duration limit);
}
