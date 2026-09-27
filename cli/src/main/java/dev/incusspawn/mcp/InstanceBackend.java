package dev.incusspawn.mcp;

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
     * definition, or the image as built, came from a repository's {@code .incus-spawn/}.
     */
    record TemplateInfo(String name, String description, boolean built, boolean stale,
                        List<String> tools, boolean projectLocal) {
        boolean supportsDelegate() {
            return tools.contains("claude");
        }
    }

    /** Every template the definitions know, with what Incus says about each. */
    List<TemplateInfo> templates();

    /**
     * Branch {@code template} into {@code name} exactly as {@code isx branch} would, with
     * {@code stamps} written by the copy itself. Throws {@link ToolError} on refusal.
     */
    CreatedInstance create(String template, String name, Map<String, String> stamps);

    record CreatedInstance(String name, String ip, String workdir) {}

    /** Delete an instance, as {@code isx destroy} does. Returns false if it was already gone. */
    boolean destroy(String name);

    /** The instance's {@code user.incus-spawn.*} config, or null if it does not exist. */
    Map<String, String> metadata(String name);

    /** Stamp one config key. */
    void stamp(String name, String key, String value);

    /** Instances whose config carries an MCP session, with their config. */
    Map<String, Map<String, String>> mcpInstances();

    /** Run a script as agentuser in a login shell. */
    int exec(String name, String script, InputStream stdin, OutputStream stdout, OutputStream stderr);
}
