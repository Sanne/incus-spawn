package dev.incusspawn.config;

import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Whether an instance about to be branched has the credentials its tools need, answered for the
 * accounts it will actually use -- template pins, the source's own pins and any {@code --account}
 * alike (#793) -- so a gap is reported before anything is created rather than as a failed API
 * call inside the container.
 *
 * <p>Credentials are declared, not listed. Each tool the template chain names, and everything it
 * {@code requires:}, spends its {@linkplain ToolSetup#credentialNamespaces(Map) credential
 * namespaces}; the {@code secret: true} entries of the tools serving those must resolve, and so
 * must the tool's own, borrowed ones included (Copilot's {@code github.token}). A tool whose
 * readiness is more than a key being set says so in {@link ToolSetup#credentialProblem}, as
 * Claude's typed accounts do.
 */
public final class CredentialCheck {

    private CredentialCheck() {}

    /**
     * A message naming what is missing, or {@code ""} when everything is configured.
     *
     * @param template  the template the instance was built from
     * @param selection the instance's effective account selection, namespace to account
     * @param loader    where tool definitions come from; the caller's, so they are not re-read
     */
    public static String check(SpawnConfig config, ImageDef template, Map<String, ImageDef> allDefs,
                               Map<String, String> selection, ToolDefLoader loader) {
        return check(config, template, allDefs, selection, loader.allToolSetups(),
                ToolProxyResolver.proxyToolSetups(config, loader));
    }

    /**
     * As {@link #check(SpawnConfig, ImageDef, Map, Map, ToolDefLoader)}, from the tool setups a
     * caller already resolved.
     *
     * @param allTools every known tool, to follow {@code requires:}
     * @param served   the tools the proxy serves credentials for ({@link ToolProxyResolver#proxyToolSetups})
     */
    public static String check(SpawnConfig config, ImageDef template, Map<String, ImageDef> allDefs,
                               Map<String, String> selection, Map<String, ToolSetup> allTools,
                               Map<String, ToolSetup> served) {
        // Leaf first, so a child's params win over an ancestor's for the same tool.
        var toolRefs = new LinkedHashMap<String, ToolDef.ToolRef>();
        for (var layer : ImageDef.chain(template, allDefs).reversed()) {
            for (var toolRef : layer.getTools()) toolRefs.putIfAbsent(toolRef.getName(), toolRef);
        }
        var tools = new LinkedHashSet<String>();
        toolRefs.keySet().forEach(name -> ToolSetup.addWithRequires(name, allTools, tools));

        var owners = AccountSelection.byNamespace(served);
        var secretsOf = new LinkedHashSet<>(tools);
        var missing = new LinkedHashSet<String>();
        try {
            for (var name : tools) {
                var tool = allTools.get(name);
                if (tool == null) continue;
                var ref = toolRefs.get(name);
                // Resolved as the build resolves them: defaults applied, a null value included.
                // An invalid value is the build's to report, so what did resolve is used.
                var params = dev.incusspawn.tool.ParameterResolver.resolve(tool.parameters(),
                        ref == null ? Map.of() : ref.getParams()).resolvedValues();
                for (var namespace : tool.credentialNamespaces(params)) {
                    var owner = owners.get(namespace);
                    if (owner != null) secretsOf.add(owner.name());
                }
                var problem = tool.credentialProblem(config, params, selection);
                if (!problem.isEmpty()) missing.add(problem);
            }
            for (var secret : ToolProxyResolver.missingSecrets(config.tree(), served, secretsOf, selection)) {
                missing.add(secret.description().isBlank() ? secret.path() : secret.description());
            }
        } catch (AccountResolver.UnknownAccountException e) {
            // Callers validate the selection first; this only keeps a stale one from escaping.
            return e.getMessage();
        }

        if (missing.isEmpty()) return "";
        return "Missing credentials: " + String.join(", ", missing) + ". Run 'isx init' to configure.";
    }
}
