package dev.incusspawn.config;

import dev.incusspawn.proxy.InheritedProof;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

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
     * A credential that is not configured, and the command that configures it: {@code isx
     * account add <namespace>} when one step owns it (#1469), else {@code isx init}. {@code
     * notes} are what the tools needing it say of it ({@link ToolSetup#credentialNote}), each once.
     *
     * <p>Its identity is the credential and its command: notes only add to what is said of it, so
     * one credential is one entry however many tools and templates need it.
     */
    public record Missing(String credential, String command, List<String> notes) {
        public Missing {
            // A blank note says nothing, and must not count as one
            notes = notes.stream().filter(n -> !n.isBlank()).distinct().toList();
        }

        public Missing(String credential, String command) {
            this(credential, command, List.of());
        }

        /** This credential, said of with {@code notes} instead. */
        Missing withNotes(List<String> notes) {
            return new Missing(credential, command, notes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Missing m && credential.equals(m.credential) && command.equals(m.command);
        }

        @Override
        public int hashCode() {
            return Objects.hash(credential, command);
        }
    }

    /**
     * Whether {@code tool} has a credential step of its own, as {@code isx init}'s credential
     * menu and {@code isx account add} offer: a proxy configuration that is its own, not one it
     * borrows from another tool (Copilot spends {@code github.token}).
     */
    public static boolean hasCredentialStep(ToolSetup tool) {
        var proxy = tool.proxy();
        return tool.hasOwnCredentials() && proxy != null && !proxy.getConfiguration().isEmpty();
    }

    /**
     * The tool whose credential step configures {@code name}, given as a credential namespace
     * ({@code github}) or as the tool itself ({@code gh}); {@code null} when no step does. The
     * namespace wins, so a tool named after another tool's namespace cannot take it over.
     */
    public static String credentialTool(String name, Map<String, ToolSetup> allTools) {
        var owner = namespaceOwner(name, allTools);
        if (owner != null) return owner;
        var tool = allTools.get(name);
        return tool != null && hasCredentialStep(tool) ? name : null;
    }

    /**
     * Every name {@link #credentialTool} resolves, sorted: each credential step's tool and the
     * namespace it declares ({@code gh} and {@code github}).
     */
    public static SortedSet<String> acceptedNames(Map<String, ToolSetup> allTools) {
        var names = new TreeSet<String>();
        allTools.forEach((toolName, tool) -> {
            if (!hasCredentialStep(tool)) return;
            names.add(toolName);
            var namespace = tool.proxy().getConfigNamespace();
            if (!namespace.isBlank()) names.add(namespace);
        });
        return names;
    }

    /** The tool whose step owns {@code namespace}: the first with a step that declares it, or {@code null}. */
    private static String namespaceOwner(String namespace, Map<String, ToolSetup> allTools) {
        if (namespace.isBlank()) return null;
        var withStep = new LinkedHashMap<String, ToolSetup>();
        allTools.forEach((toolName, tool) -> { if (hasCredentialStep(tool)) withStep.put(toolName, tool); });
        var owner = AccountSelection.byNamespace(withStep).get(namespace);
        return owner == null ? null : owner.name();
    }

    /**
     * The command that runs {@code toolName}'s own credential step: {@code isx account add} with
     * its namespace when it owns that, else with its name when that resolves to it, else
     * {@code isx init}, whose menu lists every step.
     */
    public static String stepCommand(String toolName, Map<String, ToolSetup> allTools) {
        var tool = allTools.get(toolName);
        var namespace = tool == null || tool.proxy() == null ? "" : tool.proxy().getConfigNamespace();
        if (toolName.equals(namespaceOwner(namespace, allTools))) return ACCOUNT_ADD + namespace;
        if (toolName.equals(credentialTool(toolName, allTools))) return ACCOUNT_ADD + toolName;
        return "isx init";
    }

    /** The command {@link Missing} names when one step configures it, before the step's name. */
    private static final String ACCOUNT_ADD = "isx account add ";

    /**
     * The tool among {@code allTools} whose credential step configures {@code missing}, as
     * {@code isx account add} would run it; {@code null} when its command is {@code isx init} or
     * names no step there. The caller chooses {@code allTools}, so a step is taken only from tools
     * it trusts, whatever tools found the gap.
     */
    public static String stepTool(Missing missing, Map<String, ToolSetup> allTools) {
        if (!missing.command().startsWith(ACCOUNT_ADD)) return null;
        return credentialTool(missing.command().substring(ACCOUNT_ADD.length()), allTools);
    }

    /**
     * {@code Run '<command>' to configure it.}, naming each distinct command in {@code missing},
     * then each distinct note.
     */
    public static String remedy(Collection<Missing> missing) {
        var commands = missing.stream().map(m -> "'" + m.command() + "'").distinct().toList();
        var remedy = new StringBuilder("Run " + OutputFormat.andList(commands) + " to configure "
                + (missing.size() == 1 ? "it." : "them."));
        missing.stream().flatMap(m -> m.notes().stream()).distinct()
                .forEach(n -> remedy.append(' ').append(n));
        return remedy.toString();
    }

    /**
     * {@code credential} of {@code namespace}, declared by {@code toolName}, with the command that
     * configures it. The namespace's owner configures it, unless the declaring tool has a step of
     * its own and is not that owner: the owner's step never asks for another tool's key, so the
     * declaring tool's step is named instead, provided its name resolves to it. A tool that
     * borrows the key without a step (Copilot) is sent to the owner; with neither, to init.
     */
    private static Missing missing(String credential, String namespace, String toolName,
                                   Map<String, ToolSetup> allTools, List<String> notes) {
        var owner = namespaceOwner(namespace, allTools);
        var tool = allTools.get(toolName);
        if (tool != null && hasCredentialStep(tool) && !toolName.equals(owner)) {
            return new Missing(credential, stepCommand(toolName, allTools), notes);
        }
        return new Missing(credential, owner != null ? ACCOUNT_ADD + namespace : "isx init", notes);
    }

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
        try {
            var gaps = gaps(config, template, allDefs, selection, allTools, served);
            if (!gaps.inherited().isEmpty() && InheritedProof.parentSecret() == null) {
                return InheritedProof.notNestedMessage(gaps.inherited().getFirst());
            }
            if (gaps.missing().isEmpty()) return "";
            return "Missing credentials: " + String.join(", ", gaps.missing().stream().map(Missing::credential).toList())
                    + ". " + remedy(gaps.missing());
        } catch (AccountResolver.UnknownAccountException e) {
            // Callers validate the selection first; this only keeps a stale one from escaping.
            return e.getMessage();
        }
    }

    /**
     * What {@link #check(SpawnConfig, ImageDef, Map, Map, Map, Map)} reports missing, one entry
     * per credential; empty when it reports nothing or something no account can fix: a credential
     * that must come from a parent instance, or an unknown account.
     */
    public static List<Missing> missing(SpawnConfig config, ImageDef template, Map<String, ImageDef> allDefs,
                                        Map<String, String> selection, Map<String, ToolSetup> allTools,
                                        Map<String, ToolSetup> served) {
        try {
            var gaps = gaps(config, template, allDefs, selection, allTools, served);
            if (!gaps.inherited().isEmpty() && InheritedProof.parentSecret() == null) return List.of();
            return gaps.missing();
        } catch (AccountResolver.UnknownAccountException e) {
            return List.of();
        }
    }

    private static Gaps gaps(SpawnConfig config, ImageDef template, Map<String, ImageDef> allDefs,
                             Map<String, String> selection, Map<String, ToolSetup> allTools,
                             Map<String, ToolSetup> served) {
        return gaps(config.tree(), AccountSelection.byNamespace(served), config, template, allDefs,
                selection, allTools, served, trustedTools(config));
    }

    /**
     * Each credential missing for any of {@code templates}, mapped to the templates that need it, in
     * the order given; empty when nothing is missing. Each template is checked for the accounts its
     * definition selects, which is what a branch of it starts with -- so this names, before a
     * build or at the end of init, what {@code isx branch} will refuse (#1438). A template whose
     * selection is invalid or inherited is left to the build and the branch to report.
     */
    public static Map<Missing, List<String>> missingByCredential(SpawnConfig config, Collection<String> templates,
                                                                 Map<String, ImageDef> allDefs, ToolDefLoader loader) {
        return missingByCredential(config, templates, allDefs, loader.allToolSetups(),
                ToolProxyResolver.proxyToolSetups(config, loader));
    }

    /**
     * As {@link #missingByCredential(SpawnConfig, Collection, Map, ToolDefLoader)}, from the tool
     * setups a caller already resolved.
     */
    public static Map<Missing, List<String>> missingByCredential(SpawnConfig config, Collection<String> templates,
                                                                 Map<String, ImageDef> allDefs,
                                                                 Map<String, ToolSetup> allTools,
                                                                 Map<String, ToolSetup> served) {
        // Once for every template: the tree serializes the whole config
        var tree = config.tree();
        var owners = AccountSelection.byNamespace(served);
        var byCredential = new LinkedHashMap<Missing, List<String>>();
        // Beside the key, which keeps the notes of the first template that needed it
        var notes = new LinkedHashMap<Missing, List<String>>();
        var trusted = trustedTools(config);
        for (var name : templates) {
            var template = allDefs.get(name);
            if (template == null) continue;
            try {
                var selection = ImageDef.resolveAccounts(template, allDefs);
                for (var credential : gaps(tree, owners, config, template, allDefs, selection, allTools, served, trusted).missing()) {
                    byCredential.computeIfAbsent(credential, c -> new ArrayList<>()).add(name);
                    notes.computeIfAbsent(credential, c -> new ArrayList<>()).addAll(credential.notes());
                }
            } catch (AccountResolver.UnknownAccountException e) {
                // Not this check's to report: the build refuses an unknown account by itself
            }
        }
        var withNotes = new LinkedHashMap<Missing, List<String>>();
        byCredential.forEach((credential, names) ->
                withNotes.put(credential.withNotes(notes.get(credential)), names));
        return withNotes;
    }

    /**
     * The tools that decide what {@code config.yaml} keys are read, loaded once and only when a
     * gap needs its note: the caller's {@code served} may hold a project's tools, which can shadow
     * a built-in, and must not decide what is reported (#1733).
     */
    private static java.util.function.Supplier<Map<String, ToolSetup>> trustedTools(SpawnConfig config) {
        var loaded = new java.util.concurrent.atomic.AtomicReference<Map<String, ToolSetup>>();
        return () -> {
            if (loaded.get() == null) loaded.set(UnreadConfigKeys.trustedTools(config));
            return loaded.get();
        };
    }

    /** What is missing, and the namespaces that are the parent instance's instead. */
    private record Gaps(List<Missing> missing, List<String> inherited) {}

    private static Gaps gaps(JqValue tree, Map<String, ToolSetup> owners, SpawnConfig config, ImageDef template, Map<String, ImageDef> allDefs,
                             Map<String, String> selection, Map<String, ToolSetup> allTools,
                             Map<String, ToolSetup> served, java.util.function.Supplier<Map<String, ToolSetup>> trusted) {
        // Leaf first, so a child's params win over an ancestor's for the same tool.
        var toolRefs = new LinkedHashMap<String, ToolDef.ToolRef>();
        for (var layer : ImageDef.chain(template, allDefs).reversed()) {
            for (var toolRef : layer.getTools()) toolRefs.putIfAbsent(toolRef.getName(), toolRef);
        }
        var tools = new LinkedHashSet<String>();
        toolRefs.keySet().forEach(name -> ToolSetup.addWithRequires(name, allTools, tools));

        var secretsOf = new LinkedHashSet<>(tools);
        // Each missing credential once, with the notes of the tools that need it
        var missing = new LinkedHashMap<Missing, List<String>>();
        // An inherited credential is the parent instance's, whatever this config holds: its
        // tool's own check is not asked, and there must be a parent instead
        var inherited = new LinkedHashSet<String>();
        // Each tool's problem with the namespace it is in, and the namespaces some tool needs
        // without a note: a note there would say another provider does, when it would not
        var problems = new ArrayList<Map.Entry<Missing, String>>();
        var needed = new LinkedHashSet<String>();
        for (var name : tools) {
            var tool = allTools.get(name);
            if (tool == null) continue;
            var ref = toolRefs.get(name);
            // Resolved as the build resolves them: defaults applied, a null value included.
            // An invalid value is the build's to report, so what did resolve is used.
            var params = dev.incusspawn.tool.ParameterResolver.resolve(tool.parameters(),
                    ref == null ? Map.of() : ref.getParams()).resolvedValues();
            var namespaces = tool.credentialNamespaces(params);
            var own = "";
            for (var namespace : namespaces) {
                var owner = owners.get(namespace);
                if (owner != null) secretsOf.add(owner.name());
                if (AccountResolver.inherits(tree, namespace, selection.get(namespace))) inherited.add(namespace);
                else if (own.isEmpty()) own = namespace;
            }
            if (own.isEmpty() && !namespaces.isEmpty()) continue;
            var problem = tool.credentialProblem(config, params, selection);
            if (problem.isEmpty()) continue;
            var gap = missing(problem, own, name, allTools, tool.credentialNote(params));
            problems.add(Map.entry(gap, own));
            if (gap.notes().isEmpty() && !own.isEmpty()) needed.add(own);
        }
        // The namespace each gap is in, for a credential typed under a key isx does not read (#1733)
        var namespaceOf = new LinkedHashMap<Missing, String>();
        for (var problem : problems) {
            var gap = problem.getKey();
            missing.computeIfAbsent(gap, g -> new ArrayList<>())
                    .addAll(needed.contains(problem.getValue()) ? List.of() : gap.notes());
            namespaceOf.putIfAbsent(gap, problem.getValue());
        }
        for (var secret : ToolProxyResolver.missingSecrets(tree, served, secretsOf, selection)) {
            var gap = missing(secret.description().isBlank() ? secret.path() : secret.description(),
                    secret.namespace(), secret.tool(), allTools, List.of());
            missing.computeIfAbsent(gap, g -> new ArrayList<>());
            namespaceOf.putIfAbsent(gap, secret.namespace());
        }
        missing.forEach((gap, notes) -> notes.add(UnreadConfigKeys.note(tree, namespaceOf.get(gap), trusted.get())));
        return new Gaps(missing.entrySet().stream()
                .map(e -> e.getKey().withNotes(e.getValue())).toList(),
                List.copyOf(inherited));
    }
}
