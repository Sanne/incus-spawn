package dev.incusspawn.config;

import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.tool.PiSetup;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whether an instance about to be branched has the credentials its tools need, answered for the
 * accounts it will actually use -- template pins, the source's own pins and any {@code --account}
 * alike (#793) -- so a gap is reported before anything is created rather than as a failed API
 * call inside the container.
 *
 * <p>Credentials are declared, not listed: a tool needs every {@code secret: true} entry its
 * proxy definition declares, including one it borrows (Copilot's {@code github.token}), and
 * everything it {@code requires:} is checked too. Only two tools are special: Claude, whose
 * credential is a typed account rather than one key, and pi, which spends Claude's or OpenAI's
 * depending on its {@code provider} ({@link PiSetup#credentialFor}).
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
     * @param allTools every known tool, to follow {@code requires:}
     * @param served   the tools the proxy serves credentials for ({@link ToolProxyResolver#proxyToolSetups})
     */
    static String check(SpawnConfig config, ImageDef template, Map<String, ImageDef> allDefs,
                        Map<String, String> selection, Map<String, ToolSetup> allTools,
                        Map<String, ToolSetup> served) {
        // The whole chain: first occurrence wins, so a child's params override an ancestor's.
        var toolRefs = new LinkedHashMap<String, ToolDef.ToolRef>();
        for (var current = template; current != null;
             current = current.isRoot() ? null : allDefs.get(current.getParent())) {
            for (var toolRef : current.getTools()) toolRefs.putIfAbsent(toolRef.getName(), toolRef);
        }
        var tools = new LinkedHashSet<String>();
        toolRefs.keySet().forEach(name -> ToolSetup.addWithRequires(name, allTools, tools));

        var needsClaude = tools.contains("claude");
        var needsVertex = false;
        var secretsOf = new LinkedHashSet<>(tools);
        if (tools.contains("pi")) {
            var piRef = toolRefs.get("pi");
            switch (PiSetup.credentialFor(piRef == null ? Map.of() : piRef.getParams())) {
                case ANTHROPIC -> needsClaude = true;
                case VERTEX -> needsVertex = true;
                case OPENAI -> secretsOf.addAll(declaring("openai", served));
                case NONE -> { }
            }
        }

        var missing = new LinkedHashSet<String>();
        try {
            if (needsClaude || needsVertex) {
                var claude = config.getClaude().accountNamed(selection.get(SpawnConfig.ClaudeConfig.NAMESPACE));
                // Complete, not merely present: a pre-accounts 'useVertex: true' with no region
                // or project still presents as an account, and fails every request.
                var usable = claude != null && claude.isComplete();
                if (needsClaude && !usable) {
                    missing.add("Anthropic API key, OAuth token, or Vertex AI");
                }
                if (needsVertex && !(usable && claude.effectiveType() == SpawnConfig.ClaudeAccountType.VERTEX)) {
                    missing.add("Vertex AI configuration");
                }
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

    /** The tools declaring {@code namespace} as theirs: whose proxy entries hold its credential. */
    private static Set<String> declaring(String namespace, Map<String, ToolSetup> served) {
        var names = new LinkedHashSet<String>();
        served.forEach((name, tool) -> {
            var proxy = tool.proxy();
            if (proxy != null && namespace.equals(proxy.getConfigNamespace())) names.add(name);
        });
        return names;
    }
}
