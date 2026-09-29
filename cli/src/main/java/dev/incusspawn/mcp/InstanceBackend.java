package dev.incusspawn.mcp;

import dev.incusspawn.config.ImageDef;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Everything the MCP tools do to Incus, behind one seam so the session logic can be tested
 * without a daemon. {@link IncusInstanceBackend} is the real one.
 */
interface InstanceBackend {

    /**
     * What {@code list_templates} shows about one template. {@code projectLocal} when the
     * image was built from a repository's {@code .incus-spawn/} definitions. {@code definitions}
     * are the ones it was described from, which {@link #create} branches with rather than
     * loading them again; never shown to the agent.
     */
    record TemplateInfo(String name, String description, boolean built, boolean stale,
                        List<String> tools, boolean projectLocal, Map<String, ImageDef> definitions) {
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

    record CreatedInstance(String name, String ip, String workdir) {}

    /**
     * Delete an instance known to exist, as {@code isx destroy} does. Does not tell the proxy:
     * call {@link #refreshProxy()} once after a batch.
     */
    void destroy(String name);

    /** Tell the proxy instances went away, so a reused address never maps to one of them. */
    void refreshProxy();

    /**
     * The instance's {@code user.incus-spawn.*} config, or null if Incus says it does not exist.
     * Throws when Incus cannot be asked, so that is never mistaken for the instance being gone.
     */
    Map<String, String> metadata(String name);

    /** Set config keys in one write; a null value removes the key. */
    void stamp(String name, Map<String, String> config);

    /** Instances whose config carries an MCP session, with their config. */
    Map<String, Map<String, String>> mcpInstances();

    /** Run a script as agentuser in a login shell. */
    int exec(String name, String script, InputStream stdin, OutputStream stdout, OutputStream stderr);
}
