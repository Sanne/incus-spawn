package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.ClientLog;
import dev.incusspawn.Environment;
import dev.incusspawn.baseimage.BaseImageReleases;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusDiagnostics;
import dev.incusspawn.Platform;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.InstanceNames;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.lifecycle.BuildAccounts;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.RuntimeSetup;
import dev.incusspawn.lifecycle.InstanceNetwork;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;

import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.command.BuildProgress.StepProgress;
import dev.incusspawn.command.BuildProgress.TransferProgress;
import dev.incusspawn.command.BuildTools.ResolvedTool;
import dev.incusspawn.command.BuildTools.ToolResolution;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static dev.incusspawn.command.BuildTools.computeToolFingerprints;
import static dev.incusspawn.command.BuildProgress.dnfCommand;
import static dev.incusspawn.command.BuildProgress.formatDuration;
import static dev.incusspawn.command.BuildProgress.runDnf;
import static dev.incusspawn.command.BuildProgress.runLiveStep;
import static dev.incusspawn.command.BuildProgress.runWithSpinner;
import static dev.incusspawn.command.BuildProgress.stepFrom;
import static dev.incusspawn.command.GuestProvisioning.assertGuestSelinuxNotEnforcing;
import static dev.incusspawn.command.GuestProvisioning.disableGuestSelinux;
import static dev.incusspawn.command.GuestProvisioning.enablePackageRepos;
import static dev.incusspawn.command.GuestProvisioning.expandHome;
import static dev.incusspawn.command.GuestProvisioning.installAllPackages;
import static dev.incusspawn.command.GuestProvisioning.bindInstalledRuntimes;
import static dev.incusspawn.command.GuestProvisioning.bindTrustStores;
import static dev.incusspawn.command.GuestProvisioning.maskServices;
import static dev.incusspawn.command.GuestProvisioning.refreshInheritedTools;
import static dev.incusspawn.command.GuestProvisioning.removePackages;
import static dev.incusspawn.command.GuestProvisioning.runToolSetup;
import static dev.incusspawn.command.GuestProvisioning.updateClaudeJsonTrust;
import static dev.incusspawn.command.GuestProvisioning.updateCodexTrust;
import static dev.incusspawn.command.GuestProvisioning.verifyTools;
import static dev.incusspawn.command.GuestProvisioning.warnDnfCacheUnavailable;
import static dev.incusspawn.command.GuestProvisioning.writeAgentContext;
import static dev.incusspawn.command.GuestProvisioning.writeEnvFile;
import static dev.incusspawn.command.SkillInstaller.installSkills;
import static dev.incusspawn.util.BuildOutput.BOLD;
import static dev.incusspawn.util.BuildOutput.RED;
import static dev.incusspawn.util.BuildOutput.YELLOW;
import static dev.incusspawn.util.BuildOutput.styled;


@CommandDefinition(
        name = "build",
        description = "Build or rebuild a template image (e.g. tpl-minimal)",
        generateHelp = true
)
public class BuildCommand extends BaseCommand {

    @Arguments(description = "Names of the templates (e.g. tpl-minimal my-template)")
    List<String> names;

    @Option(name = "all", hasValue = false, description = "Rebuild all defined templates")
    boolean all;

    @Option(name = "out-of-sync", hasValue = false, description = "Rebuild templates that are out of sync (definition or isx version changed, or parent rebuilt since)")
    boolean outOfSync;

    @Option(name = "with-parents", hasValue = false, description = "Rebuild the templates and all their parents unconditionally, shared parents once")
    boolean withParents;

    @Option(name = "with-descendants", hasValue = false, description = "Rebuild the templates and all templates inheriting from them")
    boolean withDescendants;

    @Option(name = "missing", hasValue = false, description = "Build only templates that don't exist yet")
    boolean missing;

    @Option(name = "type", description = "Instance type: container, vm, or kvm (overrides image definition)")
    InstanceType type;

    @Option(name = "yes", hasValue = false, description = "Skip interactive confirmations (for TUI integration)")
    boolean yes;

    @Option(name = "skip-git-refresh", hasValue = false, description = "Compatibility option; builds no longer refresh host checkouts")
    boolean skipGitRefresh;

    IncusClient incus;
    ToolDefLoader toolDefLoader;
    Iterable<ToolSetup> toolSetups;

    /**
     * Prompt the user for confirmation unless {@code --yes} was passed.
     * Returns {@code true} if the operation should proceed, {@code false} if
     * the user declined. When there is no interactive console the prompt is
     * skipped and {@code true} is returned (non-interactive CI behaviour).
     */
    private boolean confirm(String prompt) {
        if (yes) return true;
        var prompts = prompts();
        if (prompts == null) return true;
        if (!askConfirmation(prompts, prompt, false)) {
            System.out.println("Aborted.");
            return false;
        }
        return true;
    }

    /** Where confirmations are read from; overridden by tests. */
    Prompts prompts() {
        return Prompts.console();
    }

    public static final String REBUILDING_SUFFIX = "-rebuilding";

    /** The longest template name that can be rebuilt: it is built as {@code <name>-rebuilding} first. */
    static final int MAX_TEMPLATE_NAME_LENGTH = InstanceNames.MAX_LENGTH - REBUILDING_SUFFIX.length();

    /** True once it has said that {@code name} is too long to be built under its temporary name. */
    static boolean reportNameTooLong(String name) {
        if (name.length() <= MAX_TEMPLATE_NAME_LENGTH) return false;
        var shown = shown(name);
        System.err.println("Error: '" + shown + "' is too long to build: it is built as '" + shown + REBUILDING_SUFFIX
                + "' first, and Incus allows " + InstanceNames.MAX_LENGTH
                + " characters, so a template name may have at most " + MAX_TEMPLATE_NAME_LENGTH + ".");
        return true;
    }
    /**
     * Whether the chain of {@code imageDef} runs into a parent cycle, which it has then reported.
     * Loading refuses one, but a build-source snapshot merged in for a target whose file is gone
     * can close one (#1443), and a build from it would start from a layer that has a parent.
     */
    static boolean reportParentCycle(ImageDef imageDef, Map<String, ImageDef> defs) {
        var cycle = ImageDef.parentCycle(imageDef, defs);
        if (cycle == null) return false;
        System.err.println("Error: cannot build '" + shown(imageDef.getName()) + "': its chain runs into a "
                + shown(cycle) + ". Fix the 'parent:' in one of their definition files; one that has none was"
                + " brought back from its earlier build, so restore its file or destroy that template.");
        return true;
    }
    private static final String INBOX_FAILURE_PATH = "/home/agentuser/inbox/BUILD_FAILURE.txt";

    private int buildIndex;
    private int buildTotal;
    private volatile boolean savedFailureSummary;
    private volatile boolean savedHostReport;

    record ActiveBuild(String tempName, String canonicalName, MachineType machineType, Exception cause) {
        ActiveBuild(String tempName, String canonicalName, MachineType machineType) {
            this(tempName, canonicalName, machineType, null);
        }
    }
    private volatile ActiveBuild activeBuild;

    /** Templates whose build failed in this invocation, so a later target built on them is skipped. */
    private final Set<String> failedInRun = new LinkedHashSet<>();
    /** Building several targets, whose run names every failure once, at its end. */
    private boolean severalTargets;

    /** The user accepted a batch rebuild up front, which covers replacing each existing image. */
    private boolean batchConfirmed;
    /** Deprecated templates this run already warned about: a chain of builds names each once. */
    private final Set<String> warnedDeprecated = new HashSet<>();

    /** The base image each tracked root resolved to this invocation (avoids re-fetching). */
    private final Map<String, BaseImage> trackedBaseImages = new HashMap<>();

    /** On exit mid-build (Ctrl-C): report the build that was running and keep it for inspection. */
    void reportInterruptedBuild() {
        var build = activeBuild;
        if (build != null) {
            failBuild(build, "Build interrupted for " + shown(build.canonicalName()), build.cause(), () -> {});
        }
    }

    private void buildDone(String name) {
        BuildOutput.success(name + " built successfully.");
    }

    @Override
    protected CommandResult doExecute() throws Exception {
        this.incus = RuntimeServices.incus();
        this.toolDefLoader = RuntimeServices.toolDefLoader();
        this.toolSetups = RuntimeServices.toolSetups();
        if (!InitCommand.requireInit()) return CommandResult.valueOf(1);
        buildTotal = 1;
        buildIndex = 1;

        Runtime.getRuntime().addShutdownHook(new Thread(this::reportInterruptedBuild));

        // Re-read tool defs from disk before resolving/stamping. The loader is a
        // process-lifetime singleton; when a build is triggered in-process from the TUI it
        // would otherwise reuse the cached tools and stamp a definition-sha that no longer
        // matches the on-disk YAML. Runs before conflicts()/find() and before addFallbacks().
        toolDefLoader.reload();
        var loaded = ImageDef.loadAllWithConflicts();
        var toolConflicts = toolDefLoader.conflicts();
        if (!loaded.conflicts().isEmpty() || !toolConflicts.isEmpty()) {
            System.err.println("Cannot build: definitions have conflicting names.");
            for (var conflict : loaded.conflicts()) {
                System.err.println();
                System.err.println(conflict.message());
            }
            for (var conflict : toolConflicts) {
                System.err.println();
                System.err.println(conflict.message());
            }
            return CommandResult.valueOf(1);
        }
        // A definition that failed to parse, or whose name or parent no instance can have, was
        // skipped with a warning above. Its name is unknown or unusable, so building anyway
        // could silently use something else in its place: a lower layer's definition of the
        // same name, or the stale build-source snapshot of an existing template. Refuse, even
        // for targets that look unrelated.
        var unparsable = new ArrayList<>(loaded.parseFailures());
        unparsable.addAll(toolDefLoader.parseFailures());
        var parseError = unparsableDefinitionsError(unparsable);
        if (parseError != null) {
            System.err.println(parseError);
            return CommandResult.valueOf(1);
        }
        var defs = loaded.defs();

        // Only definitions read from disk: dispatch may add stale build-source snapshots to defs
        var loadedDefs = Map.copyOf(defs);
        // At a terminal one Ctrl+C may be a copy (Ctrl+Shift+C), so a running build waits for a second
        try (var ignored = BuildInterrupt.atTerminal() ? BuildInterrupt.install(() -> activeBuild != null) : null) {
            var result = dispatch(defs);
            if (result.equals(CommandResult.SUCCESS)) syncDefaultActions(loadedDefs);
            return result;
        } catch (BuildFailedException e) {
            // Templates this build skipped or had already promoted are still worth syncing
            syncDefaultActions(loadedDefs);
            return CommandResult.valueOf(1);
        }
    }

    CommandResult dispatch(Map<String, ImageDef> defs) {
        var names = this.names == null ? List.<String>of() : this.names.stream().distinct().toList();
        if (withParents || withDescendants) {
            var flag = withParents ? "--with-parents" : "--with-descendants";
            if (names.isEmpty()) {
                System.err.println("Usage: isx build <template-name>... " + flag);
                return CommandResult.valueOf(1);
            }
            var targets = definitionsOf(names, defs);
            if (targets == null) return CommandResult.valueOf(1);
            if (withParents) buildWithParents(targets, defs);
            else buildWithDescendants(targets, defs);
            return CommandResult.SUCCESS;
        }
        if (missing) {
            buildMissing(defs);
            return CommandResult.SUCCESS;
        }
        if (outOfSync) {
            buildAll(defs, true);
            return CommandResult.SUCCESS;
        }
        if (all) {
            buildAll(defs, false);
            return CommandResult.SUCCESS;
        }

        if (names.isEmpty()) {
            System.err.println("Usage: isx build <image-name>...  or  isx build --all");
            System.err.println("Available images: " + String.join(", ", defs.keySet()));
            return CommandResult.valueOf(1);
        }

        for (var name : names) {
            if (defs.containsKey(name) || !incus.exists(name)) continue;
            var buildSource = BuildSource.fromJson(
                    incus.configGet(name, Metadata.BUILD_SOURCE));
            if (buildSource != null) {
                for (var entry : buildSource.getDefinitions().entrySet()) {
                    defs.putIfAbsent(entry.getKey(), entry.getValue());
                }
                toolDefLoader.addFallbacks(buildSource.getTools());
            }
        }
        var targets = definitionsOf(names, defs);
        if (targets == null) return CommandResult.valueOf(1);
        // A snapshot just merged can close a parent cycle loading refused (#1443): before any check
        // or question walks the cut-short chain, and before anything is built from it. Like an
        // unknown name, one such target builds nothing, not the others named with it.
        for (var target : targets) {
            if (reportParentCycle(target, defs)) return CommandResult.valueOf(1);
        }
        // buildChain may rebuild any ancestor that turns out to be missing or outdated
        var chains = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var target : targets) collectAllRecursive(target, defs, chains, seen);
        preflight(chains, defs);
        build(parentsFirst(targets, defs), defs);
        return CommandResult.SUCCESS;
    }

    /**
     * {@code targets} with each one's ancestors among them moved before it: built in the order
     * named, a child would be copied from its parent's build before the one that follows.
     */
    static List<ImageDef> parentsFirst(List<ImageDef> targets, Map<String, ImageDef> defs) {
        var named = targets.stream().map(ImageDef::getName).collect(Collectors.toSet());
        var ordered = new LinkedHashSet<ImageDef>();
        for (var target : targets) {
            for (var def : ImageDef.chain(target, defs)) {
                if (named.contains(def.getName())) ordered.add(def);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * The definitions of {@code names}, in order, or {@code null} after naming every unknown
     * one: a list with an unknown name builds nothing, not the names before it.
     */
    private static List<ImageDef> definitionsOf(List<String> names, Map<String, ImageDef> defs) {
        var unknown = names.stream().filter(n -> !defs.containsKey(n)).toList();
        if (unknown.isEmpty()) return names.stream().map(defs::get).toList();
        System.err.println((unknown.size() == 1 ? "Unknown image: " : "Unknown images: ")
                + String.join(", ", unknown));
        System.err.println("Available images: " + String.join(", ", defs.keySet()));
        return null;
    }

    /** The refusal for definition files that failed to load, or null when there are none. */
    static String unparsableDefinitionsError(List<Path> files) {
        if (files.isEmpty()) return null;
        var sb = new StringBuilder("Cannot build: ")
                .append(files.size() == 1
                        ? "a definition file could not be loaded"
                        : files.size() + " definition files could not be loaded")
                .append(" (see the error above):\n");
        for (var file : files) {
            // A cloned repository names the files in its .incus-spawn/ (#1228).
            sb.append("  • ").append(shown(file.toString())).append('\n');
        }
        return sb.append("Fix the file, or move it out of the definitions directory.").toString();
    }

    /**
     * Rebuild templates.
     * @param outdatedOnly if true, only rebuild outdated/missing templates; if false, rebuild all
     */
    private void buildAll(Map<String, ImageDef> defs, boolean outdatedOnly) {
        // Identify which images are parents of other images
        var parentNames = defs.values().stream()
                .filter(d -> !d.isRoot())
                .map(ImageDef::getParent)
                .collect(Collectors.toSet());

        // Leaf images = images that no other image references as parent
        var leaves = defs.values().stream()
                .filter(d -> !parentNames.contains(d.getName()))
                .toList();

        // Collect templates to rebuild (in build order: parents before children)
        var templatesToRebuild = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        collectTemplatesToRebuild(leaves, defs, templatesToRebuild, seen, incus, toolDefLoader, outdatedOnly);

        if (templatesToRebuild.isEmpty()) {
            BuildOutput.step("All templates are up to date.");
            return;
        }
        preflight(templatesToRebuild, defs);

        if (!confirmBatch(outdatedOnly ? "Templates to rebuild: " : "This will rebuild: ",
                templatesToRebuild, defs, outdatedOnly ? "Rebuild?" : "Continue?")) return;

        rebuildAll(templatesToRebuild, defs);
    }

    /**
     * Ask once for a whole batch, after showing everything a per-template prompt would have:
     * which images exist and will be replaced, and which tools their rebuild drops (#911).
     */
    private boolean confirmBatch(String heading, List<String> templates, Map<String, ImageDef> defs,
                                 String prompt) {
        BuildOutput.step(heading + String.join(", ", templates));
        var existing = templates.stream().filter(incus::exists).toList();
        if (!yes && !existing.isEmpty()) {
            BuildOutput.step(String.join(", ", existing)
                    + " will be replaced, each only if its build succeeds.");
        }
        for (var name : existing) {
            warnDroppedTools(name, defs.get(name), defs);
        }
        if (!confirm(prompt)) return false;
        batchConfirmed = true;
        return true;
    }

    /**
     * Build all templates with atomic per-template swaps. Each template is built
     * with a temporary name and promoted to the canonical name immediately on
     * success. This means child templates always copy from the already-promoted
     * parent. If a build fails, the remaining templates (which depend on it) are
     * skipped — their originals are preserved since they were never touched.
     */
    private void rebuildAll(List<String> templates, Map<String, ImageDef> defs) {
        var failedBuilds = new HashSet<String>();
        buildTotal = templates.size();
        buildIndex = 0;

        try {
            for (var templateName : templates) {
                buildIndex++;
                var imageDef = defs.get(templateName);
                if (imageDef == null) {
                    System.err.println("Template definition not found: " + templateName);
                    failedBuilds.add(templateName);
                    continue;
                }
                if (shouldSkipDueToFailedParent(imageDef, defs, failedBuilds)) {
                    BuildOutput.buildHeader(templateName, buildIndex, buildTotal);
                    BuildOutput.step("Skipped — parent failed to build.");
                    failedBuilds.add(templateName);
                    continue;
                }

                try {
                    buildSingleImage(imageDef, defs);
                } catch (BuildFailedException e) {
                    failedBuilds.add(templateName);
                }
            }
        } finally {
            // The answer and the counter covered this batch only.
            batchConfirmed = false;
            buildTotal = 1;
            buildIndex = 1;
        }

        failedInRun.addAll(failedBuilds);
        if (severalTargets && !failedBuilds.isEmpty()) throw new BuildFailedException();
        failIfAny(failedBuilds);
    }

    /** End a run that left templates unbuilt by naming them, as a failed build. */
    static void failIfAny(Collection<String> failed) {
        if (failed.isEmpty()) return;
        System.err.println("\n" + styled(BOLD + RED, "Some templates failed to build: "
                + shown(String.join(", ", failed))));
        throw new BuildFailedException();
    }

    /**
     * Collect templates to rebuild from a list of leaves, in build order (parents before children).
     * @param outdatedOnly if true, only collect outdated/missing templates; if false, collect all
     */
    private static void collectTemplatesToRebuild(List<ImageDef> leaves,
                                                   Map<String, ImageDef> defs,
                                                   List<String> result,
                                                   Set<String> seen,
                                                   IncusClient incus,
                                                   ToolDefLoader toolDefLoader,
                                                   boolean outdatedOnly) {
        if (outdatedOnly) {
            // Judged from one listing by the rules isx templates and the TUI apply (#1142); a
            // listing Incus cannot give is thrown, never taken for "nothing is built".
            var built = InstanceListing.builtTemplates(incus.listJsonConfig());
            var staleness = TemplateStaleness.assess(built, defs, Set.of(),
                    () -> TemplateStaleness.toolFingerprints(defs.values(), toolDefLoader),
                    BuildInfo.instance().version());
            // Every outdated template and everything copied from it, collected before any is ordered:
            // ordering while collecting built a child before a parent the walk reached later (#1150)
            var batch = new HashSet<String>();
            for (var template : defs.values()) {
                if (batch.contains(template.getName())) continue;
                var stale = staleness.get(template.getName());
                if (stale == null || stale.outOfSync()) {
                    batch.add(template.getName());
                    collectDescendants(template.getName(), defs, new ArrayList<>(), batch);
                }
            }
            var inBatch = new LinkedHashMap<String, ImageDef>();
            defs.forEach((name, def) -> { if (batch.contains(name)) inBatch.put(name, def); });
            for (var def : inBatch.values()) {
                collectAllRecursive(def, inBatch, result, seen);
            }
            // Tool parameters that no longer validate are thrown before anything is built, not halfway through
            for (var name : result) {
                try {
                    BuildTools.resolveTools(defs.get(name), toolDefLoader, true);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Template '" + name + "': " + e.getMessage(), e);
                }
            }
        } else {
            for (var leaf : leaves) {
                collectAllRecursive(leaf, defs, result, seen);
            }
        }
    }

    public static void collectAllRecursive(ImageDef imageDef, Map<String, ImageDef> defs,
                                     List<String> result, Set<String> seen) {
        if (seen.contains(imageDef.getName())) return;
        // ImageDef.chain, root-first, stops at a parent cycle a merged build-source snapshot closed (#1443)
        for (var layer : ImageDef.chain(imageDef, defs)) {
            if (seen.add(layer.getName())) result.add(layer.getName());
        }
    }

    public static void collectDescendants(String parentName, Map<String, ImageDef> defs,
                                            List<String> result, Set<String> seen) {
        for (var def : defs.values()) {
            if (parentName.equals(def.getParent()) && seen.add(def.getName())) {
                result.add(def.getName());
                collectDescendants(def.getName(), defs, result, seen);
            }
        }
    }

    /**
     * Check if a template should be skipped because one of its ancestors failed to build.
     */
    boolean shouldSkipDueToFailedParent(ImageDef imageDef, Map<String, ImageDef> defs,
                                         Set<String> failedBuilds) {
        // Each layer's parent name, so a failed parent whose definition is missing still counts
        return ImageDef.chain(imageDef, defs).stream()
                .anyMatch(layer -> !layer.isRoot() && failedBuilds.contains(layer.getParent()));
    }

    /**
     * Build only templates that don't exist yet. Skips already-built
     * images without deleting them. Parents are built recursively if missing.
     */
    private void buildMissing(Map<String, ImageDef> defs) {
        var parentNames = defs.values().stream()
                .filter(d -> !d.isRoot())
                .map(ImageDef::getParent)
                .collect(Collectors.toSet());
        var missingLeaves = defs.values().stream()
                .filter(d -> !parentNames.contains(d.getName()))
                .filter(d -> !incus.exists(d.getName()))
                .toList();
        var toCheck = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        for (var leaf : missingLeaves) {
            collectAllRecursive(leaf, defs, toCheck, seen);
        }
        preflight(toCheck, defs);
        build(missingLeaves, defs);
    }

    /**
     * Unconditionally rebuild templates and all their ancestors, as one batch: the union of
     * their chains, parents before children, so an ancestor they share is built once (#1130).
     */
    void buildWithParents(List<ImageDef> targets, Map<String, ImageDef> defs) {
        var chain = withParentsChain(targets, defs);
        preflight(chain, defs);

        if (!confirmBatch("This will rebuild: ", chain, defs, "Continue?")) return;

        rebuildAll(chain, defs);
    }

    /**
     * Unconditionally rebuild templates and all templates that inherit from them, as one batch.
     * A target that descends from another is reached through that one, so parents still come
     * before children whatever order the targets were named in.
     */
    void buildWithDescendants(List<ImageDef> targets, Map<String, ImageDef> defs) {
        var chain = withDescendantsChain(targets, defs);
        preflight(chain, defs);

        if (!confirmBatch("This will rebuild: ", chain, defs, "Continue?")) return;

        rebuildAll(chain, defs);
    }

    /** What {@code --with-parents} rebuilds for {@code targets}: their chains' union, parents first, each once. */
    public static List<String> withParentsChain(List<ImageDef> targets, Map<String, ImageDef> defs) {
        var chain = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        for (var target : targets) {
            collectAllRecursive(target, defs, chain, seen);
        }
        return chain;
    }

    /** What {@code --with-descendants} rebuilds for {@code targets}: their subtrees' union, parents first, each once. */
    public static List<String> withDescendantsChain(List<ImageDef> targets, Map<String, ImageDef> defs) {
        var targetNames = targets.stream().map(ImageDef::getName).collect(Collectors.toSet());
        var chain = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        for (var target : targets) {
            if (ImageDef.ancestors(target, defs).stream().anyMatch(a -> targetNames.contains(a.getName()))) continue;
            if (seen.add(target.getName())) chain.add(target.getName());
            collectDescendants(target.getName(), defs, chain, seen);
        }
        return chain;
    }

    /**
     * Build images one after the other, each after any of its parents that is missing or
     * outdated; a parent an earlier one rebuilt is current by then, so it is built once.
     */
    private void build(List<ImageDef> targets, Map<String, ImageDef> defs) {
        if (targets.isEmpty()) return;
        var dnsOverrides = ProxyConfig.getDnsOverrides(incus);
        if (!dnsOverrides.isEmpty() && dnsOverrides.contains("address=/")) {
            ProxyHealthCheck.requireProxy(incus);
        }

        if (targets.size() == 1) {
            buildChain(targets.getFirst(), defs);
            return;
        }
        // Like a batch, a failed target does not stop the others; one that inherits from a
        // template that failed in this run is skipped, since its build would use the old one.
        // The run ends naming every template it left unbuilt, a parent a chain failed on included.
        failedInRun.clear();
        severalTargets = true;
        try {
            buildEach(targets, defs);
        } finally {
            severalTargets = false;
        }
        failIfAny(failedInRun);
    }

    private void buildEach(List<ImageDef> targets, Map<String, ImageDef> defs) {
        for (var target : targets) {
            if (shouldSkipDueToFailedParent(target, defs, failedInRun)) {
                BuildOutput.step("Skipped " + target.getName() + " — a parent failed to build.");
                failedInRun.add(target.getName());
            } else {
                try {
                    buildChain(target, defs);
                } catch (BuildFailedException e) {
                    failedInRun.add(target.getName());
                }
            }
            System.out.println();
        }
    }

    void buildChain(ImageDef imageDef, Map<String, ImageDef> defs) {
        var chain = new ArrayList<String>();
        collectParentsToBuild(imageDef, defs, chain);
        if (chain.isEmpty()) {
            buildSingleImage(imageDef, defs);
            return;
        }
        chain.add(imageDef.getName());
        // Before any of it is built: a child that cannot be built is no reason to rebuild its parents
        if (chain.stream().anyMatch(BuildCommand::reportNameTooLong)) throw new BuildFailedException(imageDef.getName());
        // One question for the chain, not one per existing image (#1069). Like a single build,
        // a chain that replaces nothing does not ask. Each asked holding it, so one caught in
        // another isx's swap is not taken for missing and replaced without asking (#1212).
        if (chain.stream().anyMatch(this::existsHeld)
                && !confirmBatch("This will rebuild: ", chain, defs, "Rebuild?")) return;
        rebuildAll(chain, defs);
    }

    /** The missing or outdated ancestors {@code buildChain} builds first, parents before children. */
    private void collectParentsToBuild(ImageDef imageDef, Map<String, ImageDef> defs, List<String> chain) {
        var parents = new ArrayList<String>();
        var child = imageDef;
        // ImageDef.ancestors stops at a parent cycle a merged build-source snapshot closed (#1443)
        for (var parentDef : ImageDef.ancestors(imageDef, defs)) {
            // When the target type differs from the parent's resolved type
            // (e.g. building a VM from container parents), buildFromScratch
            // applies the entire ancestor chain from definitions alone —
            // parent Incus instances are not needed.
            if (effectiveMachineType(child) != effectiveMachineType(parentDef)
                    || !parentNeedsBuild(parentDef.getName(), parentDef)) {
                chain.addAll(parents.reversed());
                return;
            }
            parents.add(parentDef.getName());
            child = parentDef;
        }
        if (!child.isRoot() && !defs.containsKey(child.getParent())) {
            System.err.println("Parent image '" + child.getParent() + "' not found in definitions.");
            System.exit(1);
        }
        chain.addAll(parents.reversed());
    }

    private boolean existsHeld(String name) {
        try (var held = TemplateLock.reading(name, BuildOutput::note)) {
            return incus.exists(name);
        }
    }

    /**
     * Whether the parent is missing or outdated, saying which. Asked holding the parent in place:
     * caught between the delete and the rename of another isx's rebuild, it would read as missing
     * and be rebuilt a second time, racing the first (#1212). Its own parent too: the outdated check
     * reads it, and one caught missing would make the parent look current.
     */
    boolean parentNeedsBuild(String parentName, ImageDef parentDef) {
        try (var parentHeld = TemplateLock.reading(parentName, BuildOutput::note);
             var grandparentHeld = TemplateLock.reading(parentDef.getParent(), BuildOutput::note)) {
            if (!incus.exists(parentName)) {
                BuildOutput.note("Parent '" + parentName + "' not found, building first.");
                return true;
            }
            if (isImageOutdated(parentName, parentDef, incus, toolDefLoader)) {
                BuildOutput.note("Parent '" + parentName + "' is outdated, rebuilding first.");
                return true;
            }
            return false;
        }
    }

    /**
     * Build a single image without checking or building parents.
     * Assumes parent is already built and up-to-date.
     * Builds with a temporary name and swaps atomically on success.
     */
    void buildSingleImage(ImageDef imageDef, Map<String, ImageDef> defs) {
        var canonicalName = imageDef.getName();
        var tempName = canonicalName + REBUILDING_SUFFIX;
        if (reportNameTooLong(canonicalName)) throw new BuildFailedException(canonicalName);

        BuildOutput.buildHeader(canonicalName, buildIndex, buildTotal);

        // Refuse before anything is created: the same check runs again when the resources are
        // applied, but by then the build container exists and the failure would leave it behind.
        try {
            HostResourceSetup.collectEffective(imageDef, defs);
            requireConfinedBaseImages(imageDef, defs);
        } catch (HostResourceSetup.HostPathOutsideProjectException
                 | HostResourceSetup.ForbiddenMountTargetException e) {
            // Built with the template's text quoted safely; each line is made safe again as it
            // is printed, so nothing added to these messages later can slip through (#1228).
            e.getMessage().lines().forEach(line -> System.err.println(shown(line)));
            throw new BuildFailedException(canonicalName);
        }

        if (reportStrandedStorage(incus, canonicalName, tempName)) throw new BuildFailedException(canonicalName);
        boolean vmByOption = imageDef.machineType() != MachineType.VM;
        if (reportNoVmSupport(incus, canonicalName, effectiveMachineType(imageDef), vmByOption)) {
            throw new BuildFailedException(canonicalName);
        }

        if (!batchConfirmed && incus.exists(canonicalName)) {
            if (!yes) {
                BuildOutput.step("Image already exists. It will be replaced if the build succeeds.");
            }
            warnDroppedTools(canonicalName, imageDef, defs);
            if (!confirm("Rebuild?")) return;
        }

        // Once the build is certain to go ahead: a refused or declined build built nothing on it
        warnDeprecated(imageDef, defs);
        incus.deleteIfExists(tempName);
        var build = new ActiveBuild(tempName, canonicalName, effectiveMachineType(imageDef));
        activeBuild = build;

        long referenced;
        try {
            buildInto(imageDef, defs, tempName);

            // Measure the just-built template's referenced size BEFORE deleting the previous subvolume.
            // Deleting a btrfs subvolume marks the pool's qgroup accounting inconsistent, so an rfer read
            // taken after deleteIfExists (i.e. on every *rebuild*) can come back stale or zero — which
            // drops the stamp and collapses the TUI's per-template delta model to the fold fallback
            // (every template ~0, a shrunken base on the root). tempName's rfer is exactly what
            // canonicalName reports after the rename: rfer is per-subvolume, unaffected by renaming it or
            // by deleting a sibling subvolume.
            referenced = probeReferencedSize(tempName);

            // The swap is part of the build: when it fails the build has not produced a template, so it
            // must go through the same report-and-promote path as any other failure.
            // Trusted inside the swap's lock: a branch or an isx mcp delegate waiting on this rebuild
            // copies the template only after, and would otherwise keep its taint for good
            TemplateLock.replace(incus, tempName, canonicalName, BuildOutput::note, () -> trustBuilt(canonicalName));
            activeBuild = null;
            buildDone(canonicalName);
        } catch (Exception e) {
            BuildOutput.abandonStep();
            activeBuild = new ActiveBuild(build.tempName(), build.canonicalName(), build.machineType(), e);
            failBuild(activeBuild, "Build failed for " + shown(canonicalName) + ": " + e.getMessage(), e, () -> {
                try {
                    var failedHostResources = HostResourceSetup.collectEffective(imageDef, defs);
                    HostResourceSetup.removeBuildDevices(incus, tempName, failedHostResources);
                } catch (Exception ignored) {}
            });
            activeBuild = null;
            throw new BuildFailedException(canonicalName);
        }

        stampReferencedSize(canonicalName, referenced);
    }

    /**
     * Refuse to start a build whose final swap cannot succeed (#717). A subvolume Incus has no record
     * of under either name makes the create or the rename fail with "file exists", but only after
     * the whole build; a record whose subvolume is missing would be deleted along the way, stranding
     * whatever data it had. Silent when the pool cannot be inspected. True once it has reported why
     * the build must not start; {@code isx project create} swaps the same way, and asks too.
     */
    static boolean reportStrandedStorage(IncusClient incus, String canonicalName, String tempName) {
        var scan = incus.scanSubvolumes().orElse(null);
        if (scan == null) return false;
        var problems = new ArrayList<String>();
        for (var name : List.of(canonicalName, tempName)) {
            if (scan.isOrphan(name)) {
                problems.add("pool '" + scan.pool() + "' holds a subvolume for '" + name
                        + "' that Incus has no record of, so the build could not take that name");
            }
            if (scan.isDangling(name)) {
                problems.add("Incus has a record of '" + name + "' but pool '" + scan.pool()
                        + "' has no subvolume for it, and replacing it could strand its data");
            }
        }
        if (problems.isEmpty()) return false;
        System.err.println("Cannot build " + shown(canonicalName) + ":");
        problems.forEach(p -> System.err.println("  - " + shown(p)));
        System.err.println("Run 'isx doctor' to inspect the storage pool.");
        return true;
    }

    /**
     * Refuses a VM build on a daemon that cannot run VMs, before its base image is downloaded
     * and a build instance is asked for: Incus would refuse that only at the instance record, with
     * a KVM message that does not say what to build instead (#1391). A daemon that does not say
     * is let through, and Incus decides. {@code vmByOption} is a container template that
     * {@code --type vm} turned into a VM build: the way out is then the option, not the template.
     */
    static boolean reportNoVmSupport(IncusClient incus, String canonicalName, MachineType machineType,
                                     boolean vmByOption) {
        if (machineType != MachineType.VM) return false;
        if (incus.supportsVirtualMachines().orElse(true)) return false;
        // The macOS client only ever talks to its own appliance; elsewhere Incus may be remote,
        // so the hint names the machine Incus runs on, not this one.
        var why = Platform.isMacOS()
                ? " (the isx VM on macOS has no nested virtualization)."
                : " (no KVM where Incus runs: check that /dev/kvm exists there and virtualization is enabled).";
        if (vmByOption) {
            System.err.println("Cannot build " + shown(canonicalName) + " as a VM (--type vm): this Incus cannot run VMs" + why);
            System.err.println("Leave out --type vm to build it as a container.");
        } else {
            System.err.println("Cannot build " + shown(canonicalName) + ": it is a VM template, and this Incus cannot run VMs" + why);
            System.err.println("Use a container template instead.");
        }
        return true;
    }

    /** Builds the template under {@code tempName}, from scratch or from its parent. */
    void buildInto(ImageDef imageDef, Map<String, ImageDef> defs, String tempName) {
        boolean typeChange = !imageDef.isRoot()
                && activeBuild.machineType() != effectiveMachineType(defs.get(imageDef.getParent()));
        if (imageDef.isRoot() || typeChange) {
            buildFromScratch(imageDef, defs, tempName);
        } else {
            buildFromParent(imageDef, defs, tempName, imageDef.getParent());
        }
    }

    /**
     * The just-built template's btrfs referenced (rfer) size, or -1 when it can't be read (non-btrfs
     * pool, quota off, btrfs unreachable). Measure this against {@code name} while it still exists as
     * the freshly-built subvolume — <em>before</em> any {@code deleteIfExists}/{@code rename}, since a
     * subvolume delete marks qgroup accounting inconsistent and would poison a later read. The read
     * forces a filesystem sync (see {@link dev.incusspawn.incus.BtrfsUsage}) so the final, otherwise
     * uncommitted, build writes are accounted for.
     */
    private long probeReferencedSize(String name) {
        try {
            var probe = incus.probeCowPool();
            if (probe.poolName() == null || !probe.isBtrfs()) return -1;
            var pool = probe.poolName();
            // Never stamp from inconsistent accounting. btrfs freezes every qgroup counter once the
            // pool is flagged inconsistent (a previous rebuild's subvolume delete is enough), and the
            // frozen figure is a plausible non-zero number — typically the base image's — that would
            // sail through the `<= 0` guard and be recorded as this template's weight. Trigger the
            // repair (a background rescan) and give it a short, bounded wait: on a developer-sized
            // pool it finishes in seconds and the stamp is taken correctly right here; on a huge one
            // the template is simply left unstamped and the TUI backfills it once the flag clears.
            var status = dev.incusspawn.incus.BtrfsUsage.repairIfInconsistent(pool);
            if (status.untrusted()) {
                BuildOutput.stepStart("Repairing disk accounting...");
                status = dev.incusspawn.incus.BtrfsUsage.awaitConsistent(pool, ACCOUNTING_REPAIR_WAIT);
                if (status.untrusted()) {
                    BuildOutput.stepDone("still running, size will be recorded later");
                    return -1;
                }
                BuildOutput.stepDone();
            }
            // sync=true: force a commit so the build's final (otherwise uncommitted) writes are
            // accounted. This is the rare accuracy-critical read; sampling uses the plain flavour.
            var rfer = dev.incusspawn.incus.BtrfsUsage.probe(pool, true).get(name);
            return rfer == null ? -1 : rfer;
        } catch (Exception ignored) {
            // Non-fatal: the referenced-size stamp is a display optimisation, not build state.
            return -1;
        }
    }

    /** How long a build waits for a just-triggered qgroup rescan before giving up on the stamp. */
    private static final Duration ACCOUNTING_REPAIR_WAIT = Duration.ofSeconds(20);

    /**
     * Record a template's btrfs referenced (rfer) size as metadata. Templates are immutable and rfer
     * is stable, so this one-time measurement stays correct and lets the TUI show each template as a
     * delta from its parent without shelling out to btrfs on every refresh (see
     * {@link dev.incusspawn.incus.BtrfsUsage}). Best-effort: a non-positive/absent size (see
     * {@link #probeReferencedSize}) is simply not stamped and the TUI falls back to exclusive-usage
     * display. Must run after the rename to the canonical name (the subvolume path btrfs reports).
     */
    private void stampReferencedSize(String canonicalName, long referenced) {
        if (referenced <= 0) return;
        try {
            incus.configSet(canonicalName, Metadata.DISK_REFERENCED, String.valueOf(referenced));
        } catch (Exception ignored) {
            // Non-fatal: the referenced-size stamp is a display optimisation, not build state.
        }
    }

    /**
     * Warn if {@code imageDef}, or the parent it names, is deprecated (#1195, #1353), once per
     * template however many times a run builds it.
     */
    private void warnDeprecated(ImageDef imageDef, Map<String, ImageDef> defs) {
        ImageDef.deprecationWarning(imageDef, defs).filter(warnedDeprecated::add)
                .ifPresent(w -> BuildOutput.warn(shown(w)));
    }

    private void warnDroppedTools(String existingImage, ImageDef imageDef, Map<String, ImageDef> defs) {
        var oldSourceJson = incus.configGet(existingImage, Metadata.BUILD_SOURCE);
        var removed = findDroppedTools(oldSourceJson, imageDef, defs);
        if (!removed.isEmpty()) {
            BuildOutput.warn("Tools no longer included in " + imageDef.getName() + ": "
                    + String.join(", ", removed),
                    "Add them to your template's tools: list if you still need them.");
        }
    }

    static Set<String> findDroppedTools(String oldBuildSourceJson, ImageDef imageDef, Map<String, ImageDef> defs) {
        return TemplateChanges.droppedTools(BuildSource.fromJson(oldBuildSourceJson), imageDef, defs).keySet();
    }

    /**
     * Check if an image is outdated (built with an older version of isx or with a different
     * definition, or copied from an earlier build of its parent), by {@link TemplateStaleness}'s
     * rules. Its stamps are one read, and its parent's another, made only when the rest are
     * current. A template that is not built, or still carries a build's
     * {@link Metadata#UNTRUSTED_LINEAGE}, is outdated; a failure to ask Incus is thrown.
     */
    static boolean isImageOutdated(String imageName, ImageDef imageDef,
                                    IncusClient incus, ToolDefLoader toolDefLoader) {
        var instance = incus.instanceMetadataOrThrow(imageName);
        if (instance == null) return true;
        // A build that never reached its trustBuilt (#936): what it holds is not what its definition says
        if (!instance.path("config").path(Metadata.UNTRUSTED_LINEAGE).asText("").isBlank()) return true;
        var built = TemplateStaleness.Built.of(imageName, instance);
        if (TemplateStaleness.versionOutdated(built.buildVersion(), BuildInfo.instance().version())) return true;
        // Checked here too so the tools are fingerprinted only when there is a stamp to compare.
        if (!built.definitionSha().isEmpty() && TemplateStaleness.definitionChanged(built.definitionSha(),
                imageDef, computeToolFingerprints(imageDef, toolDefLoader))) return true;
        if (imageDef.isRoot()) return false;
        var parent = incus.instanceMetadataOrThrow(imageDef.getParent());
        return parent != null && TemplateStaleness.parentRebuilt(
                TemplateStaleness.Built.of(imageDef.getParent(), parent), built);
    }

    /**
     * Report a failed build and keep its container for inspection, removing what
     * {@code removeDevices} removes first. A build that failed before its container existed (the
     * base image's download, say) has nothing to inspect: its report is the error and where it was
     * saved, without a promotion that fails and asks for a manual cleanup of nothing (#1439).
     */
    private void failBuild(ActiveBuild build, String errorLine, Runnable removeDevices) {
        failBuild(build, errorLine, null, removeDevices);
    }

    private void failBuild(ActiveBuild build, String errorLine, Exception cause, Runnable removeDevices) {
        if (!reportBuildFailure(build.tempName(), build.canonicalName(), errorLine, cause)) return;
        removeDevices.run();
        promoteToFailedInstance(build.tempName(), build.canonicalName(), build.machineType());
    }

    /**
     * Whether the build's container was created: only Incus answering that it does not exist says
     * no. A failure report that mentions a container which never existed is noise, one that hides
     * a container left behind is worse.
     */
    private boolean buildContainerExists(String buildName) {
        try {
            return incus.instanceMetadataOrThrow(buildName) != null;
        } catch (Exception e) {
            return true;
        }
    }

    private String printBuildDiagnostics(String buildName, boolean created) {
        var diag = new StringBuilder();
        try {
            // Without a container only the host's state is left to report: a full pool can be
            // why it was never created
            var status = "";
            if (created) {
                status = incus.getInstanceStatus(buildName);
                appendDiag(diag, "  Container status: " + (status.isEmpty() ? "(unknown)" : status));

                var log = incus.getLog(buildName);
                if (!log.isBlank()) {
                    var lines = log.lines()
                            .filter(l -> !l.contains("No security context received"))
                            .toList();
                    if (lines.isEmpty()) {
                        appendDiag(diag, "  LXC log: (no actionable entries)");
                    } else {
                        var tail = lines.subList(Math.max(0, lines.size() - 20), lines.size());
                        appendDiag(diag, "  LXC log (last " + tail.size() + " lines):");
                        tail.forEach(l -> appendDiag(diag, "    " + l));
                    }
                }
            }

            var pool = incus.findCowPool();
            if (pool != null) {
                var poolUsage = incus.getPoolUsageBytes(pool);
                if (poolUsage != null) {
                    appendDiag(diag, "  " + poolUsage.format(pool));
                    if (poolUsage.percent() >= IncusClient.PoolUsage.CRIT_PERCENT) {
                        var hint = Platform.isMacOS()
                                ? " or 'isx vm resize' to grow the appliance disk"
                                : "";
                        appendDiag(diag, "  " + styled(BOLD, "Likely cause: storage pool is "
                                + poolUsage.percent() + "% full"
                                + " — run 'isx clean pool' to reclaim space" + hint));
                    }
                }
            }

            var mem = new IncusDiagnostics(incus).getServerMemoryUsage();
            if (!mem.isEmpty()) {
                appendDiag(diag, "  " + mem);
            }

            if ("Error".equals(status) || "Stopped".equals(status)) {
                var cause = diagnoseInotifyExhaustion(incus);
                if (cause != null) {
                    appendDiag(diag, "  Cause: " + cause);
                }
                if ("Error".equals(status)) {
                    var dmesg = new IncusDiagnostics(incus).queryDmesgForContainer(buildName);
                    if (!dmesg.isEmpty()) {
                        var dmesgCause = diagnoseCrashCause(dmesg);
                        if (dmesgCause != null && cause == null) {
                            appendDiag(diag, "  Cause: " + dmesgCause);
                        }
                        appendDiag(diag, "  Kernel log (dmesg):");
                        dmesg.lines().forEach(l -> appendDiag(diag, "    " + l));
                    }
                }
            }
        } catch (Exception e) {
            appendDiag(diag, "  (could not collect diagnostics: " + e.getMessage() + ")");
        }
        return diag.toString();
    }

    private static void appendDiag(StringBuilder diag, String line) {
        System.err.println(line);
        diag.append(BuildOutput.stripAnsi(line)).append('\n');
    }

    static String diagnoseCrashCause(String dmesg) {
        boolean oom = dmesg.lines().anyMatch(l ->
                l.contains("oom-kill:") || l.contains("Out of memory") || l.contains("Memory cgroup out of memory"));
        if (oom) {
            return "out of memory — the kernel killed the container because the VM ran out of RAM";
        }
        boolean pidsLimit = dmesg.lines().anyMatch(l ->
                l.contains("fork rejected by pids controller"));
        if (pidsLimit) {
            return "process limit exceeded — the container hit the cgroup process (PID) limit";
        }
        return null;
    }

    static String diagnoseInotifyExhaustion(IncusClient incus) {
        try {
            int limit = new IncusDiagnostics(incus).getInotifyMaxInstances();
            if (limit < 0) return null;
            var instances = incus.list();
            long running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .count();
            // +1: the container that just died isn't Running anymore but was consuming inotify instances
            long estimatedUsage = (running + 1) * IncusDiagnostics.INOTIFY_INSTANCES_PER_CONTAINER;
            if (estimatedUsage >= limit * 0.7) {
                return "inotify instance limit likely exhausted — ~" + (running + 1)
                        + " containers × ~" + IncusDiagnostics.INOTIFY_INSTANCES_PER_CONTAINER
                        + " inotify instances each ≈ " + estimatedUsage
                        + ", limit is " + limit
                        + ". Fix: sudo sysctl -w fs.inotify.max_user_instances=8192"
                        + " (or run 'isx init' to apply permanently)";
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * The build container, copied from its parent and untrusted from that very request on: a build
     * that never reaches {@link #tagTemplateMetadata}, however it stops, must not pass for the
     * parent it copied (#936).
     */
    void copyFromParent(String parentSource, String buildName, IncusClient.CopyPlan copyPlan) {
        incus.copy(parentSource, buildName, copyPlan, BUILDING);
    }

    /**
     * The last step of a build that succeeded, after its swap and inside the swap's lock: only now
     * is the template what its definition says, so only now does it lose
     * {@link Metadata#UNTRUSTED_LINEAGE} (#936), and nothing copies it in between. Anything that
     * stops a build before this, a failure after tagTemplateMetadata or in the swap included, leaves
     * it set. Should this write fail, the template stays untrusted, which {@link #isImageOutdated}
     * counts, so the next {@code isx build} of it or of a template built on it rebuilds it.
     */
    void trustBuilt(String template) {
        try {
            incus.configUnset(template, Metadata.UNTRUSTED_LINEAGE);
        } catch (RuntimeException e) {
            BuildOutput.warn("'" + shown(template) + "' was built, but host routes still refuse it and its branches: "
                    + shown(e.getMessage()) + ". Run 'isx build " + shown(template) + "' again.");
        }
    }

    /** What a build container carries from its first request until its build has succeeded (#936). */
    static final Map<String, String> BUILDING = Map.of(Metadata.UNTRUSTED_LINEAGE, Metadata.UNTRUSTED_BUILDING);

    private void promoteToFailedInstance(String buildName, String canonicalName, MachineType machineType) {
        var promotedName = canonicalName + "-failed-build";
        try {
            BuildAccounts.revoke(incus, buildName);
            ProxyService.signalAccountRefresh();
            incus.deleteIfExists(promotedName);
            try { new GuestProvisioning(incus).unmountDnfCache(buildName, machineType); } catch (Exception ignored) {}
            if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(buildName))) {
                incus.forceStop(buildName);
            }
            incus.rename(buildName, promotedName);
            // Untrusted since its first request, until trustBuilt after a swap this build never
            // reached; this only says why. Should this write fail, it still is
            incus.configSetAll(promotedName, Map.of(
                    Metadata.TYPE, Metadata.TYPE_FAILED_BUILD,
                    Metadata.UNTRUSTED_LINEAGE, Metadata.TYPE_FAILED_BUILD,
                    Metadata.PARENT, canonicalName,
                    Metadata.CREATED, Metadata.now()));
            var hint = savedHostReport
                    ? " — see " + Environment.buildFailureLogFile(canonicalName)
                    : (savedFailureSummary ? " — see ~/inbox/BUILD_FAILURE.txt inside the instance" : "");
            System.err.println(styled(BOLD, "Container promoted to instance '" + shown(promotedName)
                    + "' for inspection" + shown(hint) + "."));
        } catch (Exception promoteError) {
            System.err.println("Failed to promote container: " + shown(promoteError.getMessage()));
            System.err.println("Container '" + shown(buildName) + "' may still exist for manual cleanup.");
        }
    }

    /**
     * {@code value} as one line that cannot drive a terminal, for a message that quotes a
     * template's name or what was derived from it (a cloned repository chooses a project-local
     * template's name, #1228). The same rule as {@code HostResourceSetup.shown}.
     */
    private static String shown(Object value) {
        return OutputFormat.oneLine(String.valueOf(value));
    }

    /** Whether the build's container exists: only then is there one to report on and to promote. */
    boolean reportBuildFailure(String buildName, String canonicalName, String errorLine) {
        return reportBuildFailure(buildName, canonicalName, errorLine, null);
    }

    boolean reportBuildFailure(String buildName, String canonicalName, String errorLine, Exception cause) {
        System.err.println("\n" + styled(YELLOW, "─".repeat(60)));
        // The message can quote what a template chose, and only isx's own line breaks are kept:
        // each line is made safe as it is printed (#1228).
        errorLine.lines().forEach(line -> System.err.println(styled(BOLD, shown(line))));

        var stepOutput = cause instanceof IncusException ie && ie.stepOutput() != null
                ? ie.stepOutput() : "";

        // Resolve the host log path first; the path-traversal guard in buildFailureLogFile can
        // throw for a malformed template name, and that must not abort failure handling.
        Path hostLog;
        try {
            hostLog = Environment.buildFailureLogFile(canonicalName);
        } catch (Exception e) {
            System.err.println("Could not resolve host failure log path: " + shown(e.getMessage()));
            hostLog = null;
        }

        // Write the host report with the error line and step output BEFORE running diagnostics.
        // printBuildDiagnostics can hang when the exec channel is wedged -- exactly the failures
        // this host report exists for -- so the report must exist before those calls.
        if (hostLog != null) {
            try {
                Files.createDirectories(hostLog.getParent());
                var earlyReport = stepOutput.isEmpty() ? errorLine + "\n"
                        : errorLine + "\n\n" + stepOutput;
                Files.writeString(hostLog, earlyReport);
            } catch (Exception e) {
                System.err.println("Could not save failure report to " + shown(hostLog) + ": " + shown(e.getMessage()));
            }
        }

        // Asked only now: Incus may be what failed, and the host report must exist before it is asked
        boolean created = buildContainerExists(buildName);
        var diagnostics = printBuildDiagnostics(buildName, created);
        var report = errorLine + "\n\n" + diagnostics;
        if (!stepOutput.isEmpty()) {
            report += "\n" + stepOutput;
        }

        // Update the host report with the full diagnostics. Write to a temp file and move so
        // a failure (e.g. disk full) after truncation cannot erase the initial error-only report.
        savedHostReport = false;
        if (hostLog != null) {
            var tmp = hostLog.resolveSibling(hostLog.getFileName() + ".tmp");
            try {
                Files.writeString(tmp, report);
                Files.move(tmp, hostLog, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                savedHostReport = true;
                System.err.println("Failure report saved to " + shown(hostLog) + ".");
            } catch (Exception e) {
                System.err.println("Could not save failure report to " + shown(hostLog) + ": " + shown(e.getMessage()));
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }

        // The in-container copy is a convenience for poking around inside the promoted instance.
        savedFailureSummary = false;
        if (!created) return false;
        try {
            new Container(incus, buildName).writeFile(INBOX_FAILURE_PATH, report);
            savedFailureSummary = true;
        } catch (Exception e) {
            System.err.println("Could not write " + INBOX_FAILURE_PATH + " inside the container: "
                    + shown(e.getMessage()));
        }
        return true;
    }

    /**
     * Gives a child build the parent's trust set brought up to date. Unlike an instance's
     * {@code fixContainerCaIfNeeded}, a failure fails the build: a template left without its
     * anchors must not be finished as one that holds them.
     */
    static boolean refreshTrustAnchors(IncusClient incus, String buildName) {
        try (var lock = CertificateAuthority.lockTrust(buildName)) {
            var trust = CertificateAuthority.collectTrustAnchors();
            if (trust.stamp().equals(incus.configGet(buildName, Metadata.CA_FINGERPRINT))) return false;
            installTrustAnchors(incus, buildName, trust);
            return true;
        }
    }

    /**
     * Installs {@code trust} and stamps the build with it, under {@link CertificateAuthority#lockTrust}
     * as an instance's reconcile is, and from this one read: a stamp read
     * again later, at the end of the build, could name a set the guest does not hold (the CA,
     * a host anchor or propagation changed meanwhile), and the template would read as current.
     */
    private static void installTrustAnchors(IncusClient incus, String buildName,
                                            CertificateAuthority.TrustAnchors trust) {
        CertificateAuthority.installTrustAnchors(incus, buildName, trust)
                .assertSuccess("Failed to install trust anchors");
        incus.configSet(buildName, Metadata.CA_FINGERPRINT, trust.stamp());
    }

    /**
     * Build an image by copying its parent and applying layers from the image definition.
     * @param buildName the Incus container name to create (temp name during atomic rebuild)
     * @param parentSource the parent container to copy from
     */
    private void buildFromParent(ImageDef imageDef, Map<String, ImageDef> defs,
                                  String buildName, String parentSource) {
        var canonicalName = imageDef.getName();
        var parentCanonical = imageDef.getParent();
        var machineType = activeBuild.machineType();

        // Another isx may be rebuilding the parent: hold it in place from the read to the copy
        try (var parentHeld = TemplateLock.reading(parentSource, BuildOutput::note)) {
            var copyPlan = incus.planCopy(parentSource);
            if (!copyPlan.cow()) {
                BuildOutput.warn("Deriving will be a full copy, not a CoW clone: "
                        + copyPlan.fullCopyReason() + ". Run 'isx doctor' for details.");
            }
            BuildOutput.stepStart("Deriving from parent image '" + parentCanonical + "'...");
            copyFromParent(parentSource, buildName, copyPlan);
        }
        if (machineType == MachineType.CONTAINER) {
            incus.configSet(buildName, "security.idmap.size", "165536");
            incus.configSet(buildName, "security.nesting", "true");
            if (Platform.isLinux()) {
                incus.configSet(buildName, "security.syscalls.intercept.setxattr", "true");
            }
            incus.configSet(buildName, "raw.lxc", "lxc.cap.drop =");
            incus.deviceAdd(buildName, "tun", "unix-char",
                    "source=/dev/net/tun", "path=/dev/net/tun", "mode=0666");
        } else {
            // A parent built by an older isx carries a stale memory size, no page reporting and
            // Secure Boot
            InstanceLifecycle.prepareVmBuild(incus, buildName);
        }
        var hostResources = HostResourceSetup.collectEffective(imageDef, defs);
        var dnfCacheWarning = attachBootDevices(buildName, hostResources, machineType);
        var started = startBuild(buildName, imageDef, defs);
        incus.waitForReady(buildName, machineType, dev.incusspawn.proxy.InstanceSecret.GUEST_SCRIPT, started.guestEnv());

        var container = new Container(incus, buildName);
        if (machineType == MachineType.CONTAINER) {
            prepareContainerForPackageInstall(container);
        }

        incus.waitForSystemd(buildName);
        BuildOutput.stepDone();
        announceBuildAccounts(imageDef, defs);
        warnDnfCacheUnavailable(dnfCacheWarning);

        if (machineType == MachineType.CONTAINER) {
            waitForIpv4(container);
        }

        container.sh(
                "sed -i 's/resolve \\[!UNAVAIL=return\\] //' /etc/nsswitch.conf; " +
                "rm -f /etc/resolv.conf; " +
                "printf '%s' '" + ProxyConfig.resolvConfContent(incus) + "' > /etc/resolv.conf")
                .assertSuccess("Failed to fix DNS after copy");

        if (refreshTrustAnchors(incus, buildName)) {
            BuildOutput.step("Refreshed trust anchors.");
        }

        requireBuildAddress(buildName, started);
        waitForNetwork(buildName);

        if (!hostResources.isEmpty()) {
            BuildOutput.step("Applying host resources.");
            HostResourceSetup.applyForBuild(incus, container, hostResources, machineType);
        }

        if (machineType == MachineType.VM) {
            disableGuestSelinux(container);
        }

        removePackages(container, imageDef);

        var toolResolution = collectEffectiveTools(imageDef, defs);
        refreshInheritedTools(container, toolResolution);
        enablePackageRepos(container, imageDef, toolResolution.effective(), toolResolution.ancestors(), defs);
        installAllPackages(container, imageDef, toolResolution.effective(), toolResolution.ancestors(), defs);

        var allTools = new ArrayList<>(toolResolution.ancestors());
        allTools.addAll(toolResolution.effective());
        // Install hooks can authenticate (gh derives the Git identity here). A login must already
        // see each tool's placeholder, so the delivered proof profile can replace it.
        writeEnvFile(container, imageDef, defs, allTools, canonicalName);
        runToolSetup(container, toolResolution.effective(), ImageDef.resolveAccounts(imageDef, defs));
        // After tool setup, not before: a parent without gh still carries a GitHub stamp, and
        // writing an identity first would create the .gitconfig whose absence is how gh's setup
        // knows to write its git defaults.
        var identityStamps = settleIdentities(container, imageDef, defs, started.inheritedIdentities());
        verifyTools(container, toolResolution.effective());
        // Before the repos' primes, which may use a JDK this layer just installed.
        bindInstalledRuntimes(container);
        maskServices(container, imageDef);
        // Only this layer's tools: an ancestor's tool skills came in with the parent copy.
        installSkills(container, imageDef, defs, toolResolution.effective());
        cloneRepos(container, imageDef, defs, machineType);
        // After the repos' prime commands, which may download a runtime (a Gradle toolchain into
        // ~/.gradle/jdks); the parent's path units do not settle in time before the stop.
        bindTrustStores(container);
        updateClaudeJsonTrust(container, imageDef, defs);
        updateCodexTrust(container, imageDef, defs);
        // After the repos it lists have actually been cloned, matching buildFromScratch.
        writeAgentContext(container, imageDef, defs, allTools, canonicalName);
        if (machineType == MachineType.VM) {
            assertGuestSelinuxNotEnforcing(container);
        }

        HostResourceSetup.removeBuildDevices(incus, buildName, hostResources);
        new GuestProvisioning(incus).unmountDnfCache(buildName, machineType);

        new GuestProvisioning(incus).cleanCaches(buildName);

        tagTemplateMetadata(buildName, canonicalName, imageDef, parentCanonical, hostResources, defs,
                identityStamps, null);

        stopBuild(buildName, started);
    }

    private MachineType effectiveMachineType(ImageDef imageDef) {
        if (type != null) return type == InstanceType.vm ? MachineType.VM : MachineType.CONTAINER;
        return imageDef.machineType();
    }

    /**
     * The local alias a template's prebaked {@code vm_image_url} is imported under, chosen by the
     * file. isx releases before #1255 import their stock {@code -vm.tar.xz} under
     * {@code <image>-vm} and reuse it whenever its tag matches; the stock file goes there from
     * this isx too, so both share one image of it. The image built from scratch
     * ({@code -vm-direct.tar.xz}) goes under {@code <image>-vm-direct}, which no older isx reads:
     * there it would pass for their stock image of the same tag, and their VMs, with Secure Boot
     * on and the root on virtio-scsi, could not boot it.
     */
    static String vmImageAlias(String image, String vmImageUrl) {
        return vmImageUrl != null && vmImageUrl.endsWith(DIRECT_VM_SUFFIX) ? image + "-vm-direct" : image + "-vm";
    }

    /** Every alias {@link #vmImageAlias} can give a template's {@code image}. */
    static List<String> vmImageAliases(String image) {
        return List.of(image + "-vm", image + "-vm-direct");
    }

    private static final String DIRECT_VM_SUFFIX = "-vm-direct.tar.xz";

    private String effectiveType(ImageDef imageDef) {
        if (type != null) return type.name();
        return imageDef.getType() != null ? imageDef.getType() : "container";
    }

    private void buildFromScratch(ImageDef imageDef, Map<String, ImageDef> defs, String buildName) {
        var canonicalName = imageDef.getName();
        var ancestors = ImageDef.ancestors(imageDef, defs);
        var rootDef = ancestors.isEmpty() ? imageDef : ancestors.get(ancestors.size() - 1);
        var base = resolveTrackedBaseImage(rootDef);
        var ownBase = imageDef == rootDef ? base : BaseImage.of(imageDef);
        var image = rootDef.getImage();
        var machineType = activeBuild.machineType();
        var prebaked = false;
        var vmFromUrl = machineType == MachineType.VM && rootDef.getVmImageUrl() != null;

        if (vmFromUrl) {
            // Prebaked VM disk image available — use it directly
            var vmAlias = vmImageAlias(rootDef.getImage(), rootDef.getVmImageUrl());
            ensureBaseImage(imageDef, ownBase);
            downloadAndAliasImage(vmAlias, rootDef.getVmImageUrl(),
                    base.vmSha256(), base.vmTag(), rootDef);
            image = vmAlias;
            prebaked = true;
        } else {
            ensureBaseImage(imageDef, ownBase);
            prebaked = imageDef.getImageUrl() != null;

            // Prebaked images are container format (.tar.xz) — VMs need a disk image.
            // Detect container-only images and fall back to the standard remote source.
            if (machineType == MachineType.VM && !image.contains(":")) {
                var fingerprint = incus.imageAliasTarget(image);
                if (fingerprint != null) {
                    var imageType = incus.getImageType(fingerprint);
                    if (!"virtual-machine".equals(imageType)) {
                        var os = incus.getImageProperty(fingerprint, "os");
                        var release = incus.getImageProperty(fingerprint, "release");
                        if (os != null && release != null) {
                            image = "images:" + os.toLowerCase() + "/" + release;
                            prebaked = false;
                            BuildOutput.step("Base image is container-only, using " + image + " for VM build.");
                        } else {
                            throw new RuntimeException(
                                    "Cannot build VM from container image '" + shown(rootDef.getImage()) + "'. "
                                    + "The image lacks OS/release metadata to derive a VM image source.");
                        }
                    }
                }
            }
        }

        requireImageTrustedFor(image, rootDef);

        // Create instance — for VMs, expand the disk before first boot so
        // cloud-init's growpart module handles partition + filesystem resize.
        var hostResources = HostResourceSetup.collectEffective(imageDef, defs);
        // Only an image isx imported from a URL comes back on the next build if it is deleted
        var reimportable = prebaked && !isRemoteImage(image);
        var launched = launchBuildInstance(image, buildName, machineType, canonicalName, reimportable, () -> {
            if (machineType == MachineType.VM) {
                incus.deviceConfigSet(buildName, "root", "size", ResourceLimits.defaultDiskLimit());
                InstanceLifecycle.prepareVmBuild(incus, buildName);
            }
            var dnfWarning = attachBootDevices(buildName, hostResources, machineType);
            return new Launched(startBuild(buildName, imageDef, defs), dnfWarning);
        });
        var started = launched.started();
        var dnfCacheWarning = launched.dnfCacheWarning();
        incus.waitForReady(buildName, machineType, dev.incusspawn.proxy.InstanceSecret.GUEST_SCRIPT, started.guestEnv());
        BuildOutput.stepDone();
        announceBuildAccounts(imageDef, defs);
        warnDnfCacheUnavailable(dnfCacheWarning);

        var container = new Container(incus, buildName);

        BuildOutput.stepStart("Installing trust anchors...");
        try (var lock = CertificateAuthority.lockTrust(buildName)) {
            installTrustAnchors(incus, buildName, CertificateAuthority.collectTrustAnchors());
        }
        BuildOutput.stepDone();

        // Container-only security tweaks: UID mapping, nesting, capability
        // retention, and setxattr interception. VMs run a full kernel and
        // don't need any of these. Restart activates the new config.
        if (machineType == MachineType.CONTAINER) {
            incus.configSet(buildName, "raw.idmap", "both 1000 1000");
            incus.configSet(buildName, "security.idmap.size", "165536");
            incus.configSet(buildName, "security.nesting", "true");
            if (Platform.isLinux()) {
                incus.configSet(buildName, "security.syscalls.intercept.setxattr", "true");
            }
            incus.configSet(buildName, "raw.lxc", "lxc.cap.drop =");
            prepareContainerForPackageInstall(container);

            BuildOutput.stepStart("Restarting container...");
            incus.stop(buildName);
            incus.deviceAdd(buildName, "tun", "unix-char",
                    "source=/dev/net/tun", "path=/dev/net/tun", "mode=0666");
            InstanceLifecycle.startForUse(incus, buildName, machineType, BuildOutput::warn);
            incus.waitForSystemd(buildName);
            BuildOutput.stepDone();
            waitForIpv4(container);
        }

        BuildOutput.stepStart("Configuring DNS...");
        container.sh(
                "sed -i 's/resolve \\[!UNAVAIL=return\\] //' /etc/nsswitch.conf; " +
                "rm -f /etc/resolv.conf; " +
                "printf '%s' '" + ProxyConfig.resolvConfContent(incus) + "' > /etc/resolv.conf")
                .assertSuccess("Failed to configure DNS");
        BuildOutput.stepDone();

        requireBuildAddress(buildName, started);
        waitForNetwork(buildName);

        if (machineType == MachineType.VM) {
            // Before the first dnf run below: the resize tools' install is a package install too.
            disableGuestSelinux(container);
            // The prebaked VM image ships these tools; dnf would still spend seconds (tens
            // on a cold cache) loading repo metadata just to find them installed.
            var installDeps = "{ command -v growpart && command -v resize2fs && command -v xfs_growfs; } "
                    + ">/dev/null 2>&1 || " + String.join(" ",
                    dnfCommand("install", "-y", "-q", "cloud-utils-growpart", "e2fsprogs", "xfsprogs"));
            runWithSpinner("Expanding", "VM root filesystem", "Failed to expand VM root filesystem",
                    state -> state.set(0, stepFrom(container.sh(
                            "{ " + installDeps + "; } && " + VM_ROOT_EXPANSION))));
        }

        if (!prebaked) {
            removePackages(container, imageDef);

            runDnf(container, "Updating system packages", "Failed to update system packages",
                    dnfCommand("-y", "upgrade"));

            if (machineType == MachineType.VM) {
                BuildOutput.stepStart("Regenerating initramfs for VM...");
                container.runQuiet("Failed to regenerate initramfs",
                        "dracut", "--force", "--regenerate-all");
                BuildOutput.stepDone();
            }

            BuildOutput.stepStart("Finalizing DNS configuration...");
            container.sh(
                    "systemctl disable --now systemd-resolved 2>/dev/null; " +
                    "systemctl mask systemd-resolved 2>/dev/null; " +
                    "sed -i 's/resolve \\[!UNAVAIL=return\\] //' /etc/nsswitch.conf")
                    .assertSuccess("Failed to finalize DNS configuration");
            BuildOutput.stepDone();

            maskServices(container, imageDef);
        }

        if (!prebaked || !container.exec("id", "agentuser").success()) {
            BuildOutput.stepStart("Creating agentuser...");
            container.exec("useradd", "-m", "-u", "1000", "-G", "systemd-journal", "agentuser")
                    .assertSuccess("Failed to create agentuser");
            container.sh(AGENT_HOME_OWNERSHIP)
                    .assertSuccess("Failed to set home directory ownership");
            container.exec("mkdir", "-p", "/home/agentuser/inbox")
                    .assertSuccess("Failed to create inbox directory");
            container.sh(
                    "echo 'agentuser ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/agentuser")
                    .assertSuccess("Failed to configure passwordless sudo");
            BuildOutput.stepDone();
        }
        container.sh(
                "echo 'agentuser:100000:65536' > /etc/subuid && " +
                "echo 'agentuser:100000:65536' > /etc/subgid")
                .assertSuccess("Failed to configure subordinate UIDs");

        if (!prebaked) {
            container.sh(
                    "echo 'PROMPT_COMMAND=\"printf \\\"\\033]0;isx:%s\\007\\\" \\\"${HOSTNAME}\\\"\"' >> /home/agentuser/.bashrc")
                    .assertSuccess("Failed to configure .bashrc");
        }
        if (!prebaked) {
            // Enable bash completion
            container.appendToProfile("if [ -f /usr/share/bash-completion/bash_completion ]; then");
            container.appendToProfile("  . /usr/share/bash-completion/bash_completion");
            container.appendToProfile("fi");

            runDnf(container, "Installing base packages", "Failed to install base packages",
                    dnfCommand("install", "-y", "git", "curl", "which", "procps-ng", "findutils"));
        }

        if (!hostResources.isEmpty()) {
            BuildOutput.step("Applying host resources.");
            HostResourceSetup.applyForBuild(incus, container, hostResources, machineType);
        }

        // Root-first, so each layer's packages, tools, repos and skills are applied in order.
        var chain = ImageDef.chain(imageDef, defs);

        var allTools = new ArrayList<ResolvedTool>();
        for (var layer : chain) {
            if (chain.size() > 1) {
                BuildOutput.note("Applying layer: " + layer.getName());
            }
            removePackages(container, layer);
            var toolResolution = collectEffectiveTools(layer, defs);
            enablePackageRepos(container, layer, toolResolution.effective(), toolResolution.ancestors(), defs);
            installAllPackages(container, layer, toolResolution.effective(), toolResolution.ancestors(), defs);

            allTools.addAll(toolResolution.effective());
            writeEnvFile(container, imageDef, defs, allTools, canonicalName);
            runToolSetup(container, toolResolution.effective(), ImageDef.resolveAccounts(imageDef, defs));
            maskServices(container, layer);
            installSkills(container, layer, defs, toolResolution.effective());
            cloneRepos(container, layer, defs, machineType);
        }
        updateClaudeJsonTrust(container, imageDef, defs);
        updateCodexTrust(container, imageDef, defs);
        var identityStamps = settleIdentities(container, imageDef, defs, started.inheritedIdentities());
        writeEnvFile(container, imageDef, defs, allTools, canonicalName);
        verifyTools(container, allTools);
        writeAgentContext(container, imageDef, defs, allTools, canonicalName);
        bindTrustStores(container);
        if (machineType == MachineType.VM) {
            assertGuestSelinuxNotEnforcing(container);
        }

        HostResourceSetup.removeBuildDevices(incus, buildName, hostResources);
        new GuestProvisioning(incus).unmountDnfCache(buildName, machineType);

        new GuestProvisioning(incus).cleanCaches(buildName);

        var parentCanonical = imageDef.isRoot() ? null : imageDef.getParent();
        // The release the build started from: a VM's own when it came from vm_image_url
        var startedFrom = vmFromUrl ? base.vmTag() : base.tag();
        var trackedImageTag = Objects.equals(startedFrom, rootDef.getImageTag()) ? null : startedFrom;
        tagTemplateMetadata(buildName, canonicalName, imageDef, parentCanonical, hostResources, defs,
                identityStamps, trackedImageTag);

        stopBuild(buildName, started);
    }

    /**
     * The base image a build from scratch of {@code rootDef} downloads. When it is unpinned,
     * that is the newest published release, so a plain build always tracks the latest base
     * image. The built-in {@code image_tag} (baked into the binary) is only an offline fallback:
     * any failure to reach or read the release list leaves the build on the built-in version.
     * A pin ({@code pinned: true}) opts out.
     * The definition itself is left as loaded: its fingerprint is what the staleness checks
     * compare, and a newer release being published does not change it (#1244).
     */
    BaseImage resolveTrackedBaseImage(ImageDef rootDef) {
        if (rootDef.isPinned()) return BaseImage.of(rootDef);
        var releases = baseImageReleases(rootDef.getImageUrl());
        if (releases == null) return BaseImage.of(rootDef); // not a releases-tracked base image
        // Resolved once per invocation, however many templates are built from scratch on it
        return trackedBaseImages.computeIfAbsent(rootDef.getName(), name -> latestBaseImage(rootDef, releases));
    }

    private static BaseImage latestBaseImage(ImageDef rootDef, BaseImageReleases releases) {
        var builtin = BaseImage.of(rootDef);
        var fallback = "; using built-in base image (" + builtin.tag() + ").";
        try {
            var available = releases.fetchReleases();
            if (available.isEmpty()) return builtin;
            var latest = available.get(0);
            if (latest.tag().equals(builtin.tag())) return builtin; // already newest

            var checksums = releases.fetchChecksums(latest, rootDef.getImageUrl(), rootDef.getVmImageUrl());
            var arch = normalizeHostArch();
            if (checksums == null || !checksums.container().containsKey(arch)) {
                BuildOutput.note("Could not fetch checksums for " + latest.tag() + fallback);
                return builtin;
            }
            BuildOutput.step("Tracking latest base image: "
                    + (builtin.tag() != null ? builtin.tag() + " -> " : "") + latest.tag() + ".");
            // A release need not carry the VM image this definition names (the one built from
            // scratch is only in releases from #1255 on): the VM then stays on the built-in
            // release, tag and checksum together, never the built-in checksum under the newer tag.
            if (rootDef.getVmImageUrl() == null || !checksums.vm().containsKey(arch)) {
                if (rootDef.getVmImageUrl() != null) {
                    BuildOutput.note(latest.tag() + " has no VM image for this definition; a VM build stays on "
                            + builtin.vmTag() + ".");
                }
                return new BaseImage(latest.tag(), checksums.container(), builtin.vmTag(), builtin.vmSha256());
            }
            return new BaseImage(latest.tag(), checksums.container(), latest.tag(), checksums.vm());
        } catch (IOException e) {
            BuildOutput.note("Could not read the base-image releases (" + e.getMessage() + ")" + fallback);
            return builtin;
        }
    }

    /** The release list a base image is tracked against, or null if it is not tracked; a test seam. */
    BaseImageReleases baseImageReleases(String imageUrl) {
        return BaseImageReleases.fromImageUrl(imageUrl);
    }

    /**
     * The base image releases a build from scratch downloads from, with their per-arch checksums:
     * {@code tag} for the container image, {@code vmTag} for the VM disk image. They differ only
     * when the tracked release has no VM image the definition can use.
     */
    record BaseImage(String tag, Map<String, String> sha256, String vmTag, Map<String, String> vmSha256) {
        static BaseImage of(ImageDef def) {
            return new BaseImage(def.getImageTag(), def.getImageSha256(), def.getImageTag(), def.getVmImageSha256());
        }
    }

    private void checkPinnedWarning(ImageDef imageDef) {
        if (!imageDef.isPinned()) return;
        var builtin = ImageDef.loadBuiltinByName(imageDef.getName());
        if (builtin == null || builtin.getImageTag() == null) return;
        var pinnedTag = imageDef.getImageTag();
        var builtinTag = builtin.getImageTag();
        if (pinnedTag != null && builtinTag.compareTo(pinnedTag) > 0) {
            BuildOutput.step("Warning: base image is pinned to " + pinnedTag
                    + ", but " + builtinTag + " is available."
                    + " Run 'isx update-base --latest' to update.");
        }
    }

    /**
     * A {@code file://} base image is a host path like any other: a project-local template may
     * only name one inside its project (#765).
     */
    static void requireConfinedBaseImages(ImageDef imageDef, Map<String, ImageDef> defs) {
        for (var def : ImageDef.chain(imageDef, defs)) {
            if (def.getProjectRoot() == null) continue;
            for (var url : java.util.Arrays.asList(def.getImageUrl(), def.getVmImageUrl())) {
                if (url == null || !url.regionMatches(true, 0, "file:", 0, 5)) continue;
                var resolved = resolveImageUrl(url, def.getImageTag());
                String escape;
                try {
                    escape = HostResourceSetup.projectEscape(Path.of(java.net.URI.create(resolved)),
                            def.getProjectRoot());
                } catch (IllegalArgumentException e) {
                    escape = "is not an absolute file:// URL";
                }
                if (escape != null) {
                    throw HostResourceSetup.outsideProject(def, "base image '" + resolved + "'", escape);
                }
            }
        }
    }

    public static String resolveImageUrl(String imageUrl, String tag) {
        var resolved = imageUrl.replace("{arch}", normalizeHostArch());
        return tag != null ? resolved.replace("{tag}", tag) : resolved;
    }

    private void ensureBaseImage(ImageDef imageDef, BaseImage base) {
        checkPinnedWarning(imageDef);
        downloadAndAliasImage(imageDef.getImage(), imageDef.getImageUrl(),
                base.sha256(), base.tag(), imageDef);
    }

    /**
     * Image property recording which project's project-local template imported an image: the
     * project root, or empty for an import by a trusted definition. Always written after import,
     * so a value shipped in the image tarball itself never survives.
     */
    static final String IMAGE_PROJECT_PROPERTY = "incus-spawn.project";

    private static final java.util.regex.Pattern SHA256_HEX = java.util.regex.Pattern.compile("[0-9a-fA-F]{64}");

    /** Image property recording the URL a base image was imported from, {@code {arch}} and {@code {tag}} filled in. */
    static final String IMAGE_SOURCE_PROPERTY = "incus-spawn.source";

    /**
     * Local image aliases are shared by every template on the host, so a project-local template
     * that could replace one would swap the base image of the user's own templates (#765). It
     * may therefore only replace images it imported itself, and an image it imported is never
     * reused by a trusted definition: one with an {@code image_url} re-imports it, and any other
     * build refuses it in {@link #requireImageTrustedFor}.
     */
    void downloadAndAliasImage(String localAlias, String imageUrl,
            Map<String, String> sha256Map, String tag, ImageDef owner) {
        if (imageUrl == null || imageUrl.isBlank()) return;
        if (localAlias.contains(":")) return;
        var ownerProject = projectOf(owner);
        // Fail closed: a failed lookup must not read as "no alias" or "imported by a trusted definition".

        var arch = normalizeHostArch();
        String expectedSha256 = null;
        if (sha256Map != null) {
            expectedSha256 = sha256Map.get(arch);
        }
        // Incus resolves an image by fingerprint prefix, so a checksum that is not a full
        // SHA-256 would match, and alias, any image starting with it: fail before anything is.
        if (expectedSha256 != null && !SHA256_HEX.matcher(expectedSha256).matches()) {
            throw new IllegalStateException("The " + arch + " checksum of base image '" + shown(localAlias)
                    + "' is not a SHA-256 (64 hex digits): '" + shown(expectedSha256) + "'.");
        }

        var existingFingerprint = incus.imageAliasTargetOrThrow(localAlias);
        if (existingFingerprint != null) {
            var importedBy = importingProject(existingFingerprint);
            if (!ownerProject.isEmpty() && !ownerProject.equals(importedBy)) {
                throw new IllegalStateException("Template '" + shown(owner.getName())
                        + "' is project-local (" + shown(owner.getSource()) + "),\n"
                        + "  and its image_url would replace the existing local image '"
                        + shown(localAlias) + "'.\n"
                        + "  A project-local template may only replace images it imported itself;"
                        + " otherwise a cloned repository could swap the base image of your own templates.\n"
                        + "  Give it an image name of its own, or, if you trust it, move it to "
                        + ImageDef.userImagesDir() + " or a configured search path.");
            }
            var installedTag = incus.getImageProperty(existingFingerprint, "incus-spawn.tag");
            // The tag alone does not say which file of a release was imported: one release carries
            // two VM images (#1255), and an alias imported from one must not pass for the other.
            // A base image is imported from one tarball, whose sha256 Incus makes its fingerprint,
            // so with a checksum to expect the fingerprint says which file it is, for imports by
            // any isx. With none to expect, the import must have recorded the same URL; one that
            // recorded none (isx before #1255) is imported again, once.
            // A fingerprint that is the expected checksum is that file whatever tag it was
            // imported under (a release can carry an unchanged asset), so it is reused as it is.
            var sameFile = expectedSha256 != null
                    ? expectedSha256.equalsIgnoreCase(existingFingerprint)
                    : resolveImageUrl(imageUrl, tag).equals(incus.getImageProperty(existingFingerprint, IMAGE_SOURCE_PROPERTY));
            var sameRelease = expectedSha256 != null || (tag != null && tag.equals(installedTag));
            if (sameRelease && sameFile && ownerProject.equals(importedBy)) {
                BuildOutput.step("Base image '" + localAlias + "' is up to date (" + tag + ").");
                return;
            }
            if (!ownerProject.equals(importedBy)) {
                BuildOutput.step("Base image '" + localAlias + "' was imported by a project-local template ("
                        + importedBy + "), replacing...");
            } else {
                BuildOutput.step("Base image '" + localAlias + "' is outdated"
                        + (installedTag == null ? ""
                                : installedTag.equals(tag) ? " (another file of " + tag + ")"
                                : " (" + installedTag + " -> " + tag + ")")
                        + ", replacing...");
            }
            incus.deleteImageAlias(localAlias);
            incus.deleteImage(existingFingerprint);
        }
        // Incus keeps one image per fingerprint, and a one-tarball import's fingerprint is the
        // tarball's sha256: the expected file already imported under another alias (an older
        // isx's, or none) cannot be imported again, so that image is aliased here as it is. Its
        // properties stay as they are, since the other alias's owner reads them too.
        if (expectedSha256 != null) {
            var fingerprint = expectedSha256.toLowerCase(java.util.Locale.ROOT);
            if (incus.imageExistsOrThrow(fingerprint) && ownerProject.equals(importingProject(fingerprint))) {
                BuildOutput.step("Base image '" + localAlias + "': the expected file is already imported, reusing it.");
                incus.createImageAlias(localAlias, fingerprint);
                return;
            }
        }
        var resolvedUrl = resolveImageUrl(imageUrl, tag);

        var cached = downloadBaseImage(localAlias, resolvedUrl, expectedSha256);
        var fingerprint = importBaseImage(localAlias, cached);

        // Stamped before the alias exists, and verified: setImageProperty fails silently, and
        // a project's import that lost its stamp would pass for a trusted one.
        incus.setImageProperty(fingerprint, IMAGE_PROJECT_PROPERTY, ownerProject);
        // The resulting value is what matters, not whether this particular write landed: a
        // tarball that already carried the expected value is stamped correctly either way.
        boolean stamped;
        try {
            stamped = ownerProject.equals(importingProject(fingerprint));
        } catch (IncusException e) {
            stamped = false;
        }
        if (!stamped) {
            incus.deleteImage(fingerprint);
            throw new IllegalStateException("Could not record which project imported base image '"
                    + shown(localAlias) + "'; the imported image was deleted.");
        }
        // One write, and the source read back like the project: a value the tarball's own
        // metadata carried must not survive a write that did not land, or it could vouch for a
        // file this import is not.
        var stamps = new HashMap<String, String>();
        if (tag != null) stamps.put("incus-spawn.tag", tag);
        stamps.put(IMAGE_SOURCE_PROPERTY, resolvedUrl);
        incus.setImageProperties(fingerprint, stamps);
        if (!resolvedUrl.equals(incus.getImageProperty(fingerprint, IMAGE_SOURCE_PROPERTY))) {
            incus.deleteImage(fingerprint);
            throw new IllegalStateException("Could not record where base image '"
                    + shown(localAlias) + "' came from; the imported image was deleted.");
        }
        incus.createImageAlias(localAlias, fingerprint);
    }

    /**
     * Fetch a base image tarball into the host download cache, or reuse the cached copy when
     * its checksum still matches. Base images run to gigabytes, so the spinner line shows the
     * percentage, size, rate and time left while the body arrives.
     */
    private Path downloadBaseImage(String alias, String url, String sha256) {
        var progress = new TransferProgress();
        var result = new java.util.concurrent.atomic.AtomicReference<Path>();
        runLiveStep("Downloading base image " + alias, progress::detail,
                "Failed to download base image from " + url, state -> {
                    try {
                        result.set(newDownloadCache().downloadAllowingLocalFile(url, sha256, progress));
                        state.set(0, StepProgress.done(progress.fetched()
                                ? "Downloaded base image " + alias + " (" + progress.summary() + ")."
                                : "Using cached download of base image " + alias + "."));
                    } catch (IOException e) {
                        state.set(0, StepProgress.failed(e.getMessage(), null));
                    }
                });
        return result.get();
    }

    /**
     * Import a downloaded tarball into Incus. For a VM disk image this is minutes of unpacking
     * on the Incus side that reports no progress, so the line shows the time spent instead.
     */
    private String importBaseImage(String alias, Path tarball) {
        var start = System.nanoTime();
        var result = new java.util.concurrent.atomic.AtomicReference<String>();
        runLiveStep("Importing base image " + alias + " into Incus",
                () -> formatDuration(System.nanoTime() - start),
                "Failed to import base image " + alias, state -> {
                    try {
                        result.set(incus.importImage(tarball));
                        state.set(0, StepProgress.done("Imported base image " + alias + " into Incus ("
                                + formatDuration(System.nanoTime() - start) + ")."));
                    } catch (RuntimeException e) {
                        state.set(0, StepProgress.failed(e.getMessage(), null));
                    }
                });
        return result.get();
    }

    DownloadCache newDownloadCache() {
        return new DownloadCache();
    }

    /** The {@link #IMAGE_PROJECT_PROPERTY} value for imports on behalf of {@code def}. */
    private static String projectOf(ImageDef def) {
        return def.getProjectRoot() != null ? def.getProjectRoot().toString() : "";
    }

    /**
     * Which project imported the image; empty for a trusted import or one that predates the
     * stamp. Throws when the image cannot be read, rather than mistaking that for "trusted".
     */
    private String importingProject(String fingerprint) {
        var project = incus.imagePropertyOrThrow(fingerprint, IMAGE_PROJECT_PROPERTY);
        return project != null ? project : "";
    }

    /**
     * A build may only start from a local image imported by a trusted definition or by its own
     * project: a trusted template naming an alias without an {@code image_url} never reaches
     * {@link #downloadAndAliasImage}, so this is where a project's import is kept out of it.
     */
    void requireImageTrustedFor(String image, ImageDef rootDef) {
        requireImageTrustedFor(image, rootDef, prompts());
    }

    /**
     * The gate stays shut either way: a refused image is deleted only when someone at a terminal
     * says so, and the build still stops, since what it was about to start from is gone.
     */
    void requireImageTrustedFor(String image, ImageDef rootDef, Prompts prompts) {
        if (isRemoteImage(image)) return; // fetched by Incus itself
        var fingerprint = image.startsWith("sha256:") ? image.substring("sha256:".length())
                : incus.imageAliasTargetOrThrow(image);
        if (fingerprint == null) return;
        var importedBy = importingProject(fingerprint);
        if (importedBy.isEmpty() || importedBy.equals(projectOf(rootDef))) return;
        // The image, the project and the name are a project's choosing: one line each (#1228).
        var shownImage = shown(image);
        var shownProject = shown(importedBy);
        var refused = "Local image '" + shownImage + "' was imported by a project-local template in "
                + shownProject + ",\n"
                + "  so '" + shown(rootDef.getName()) + "' will not be built on it.\n";
        var reimport = " (a template with an image_url re-imports it automatically).";
        if (prompts == null) {
            throw new IllegalStateException(refused + "  Re-run the build in a terminal to remove it" + reimport);
        }
        if (!askConfirmation(prompts, "  Delete local image '" + shownImage + "' (imported by project "
                + shownProject + ")?", false)) {
            throw new IllegalStateException(refused + "  It was kept, so the build cannot start from it.");
        }
        incus.deleteImageOrThrow(fingerprint);
        throw new IllegalStateException(refused + "  It has been deleted: re-run the build" + reimport);
    }

    /** A started build instance, and the DNF cache warning its boot devices left, if any. */
    record Launched(BuildAccounts.Started started, String dnfCacheWarning) {}

    /**
     * Create the build instance from {@code image} and have {@code prepareAndStart} configure and
     * start it. A cached image whose {@code /sbin/init} cannot exec ("Exec format error", which
     * Incus reports when the start fails and LXC writes to the instance's log) is known to be
     * corrupt. A copy isx can get back is deleted without asking, along with the failed instance:
     * a remote image is fetched again by one more launch, and a local one isx imported from the
     * template's URL ({@code reimportable}) by the next build. A local image isx did not import
     * (no URL to import it from) is left in place: deleting it would lose the only copy.
     */
    <T> T launchBuildInstance(String image, String buildName, MachineType machineType, String templateName,
                              boolean reimportable, Supplier<T> prepareAndStart) {
        var launching = "Launching " + image + (machineType == MachineType.VM ? " (VM)..." : "...");
        for (int attempt = 1; ; attempt++) {
            BuildOutput.stepStart(launching);
            try {
                incus.create(image, buildName, machineType, BUILDING);
                return prepareAndStart.get();
            } catch (IncusException e) {
                BuildOutput.stepBreak();
                if (attempt > 1) {
                    if (hasBrokenInit(buildName)) {
                        throw new RuntimeException("The image fetched again for '" + shown(image)
                                + "' has a broken /sbin/init too (Exec format error).", e);
                    }
                    throw e;
                }
                if (!removeBrokenImage(image, buildName, templateName, reimportable, e)) throw e;
            }
        }
    }

    /** {@code remote:alias}; a {@code sha256:} fingerprint names a local image. */
    private static boolean isRemoteImage(String image) {
        return image.contains(":") && !image.startsWith("sha256:");
    }

    private boolean hasBrokenInit(String buildName) {
        return incus.exists(buildName) && incus.getLog(buildName).contains("Exec format error");
    }

    /**
     * Whether a failed start was a broken cached image, now removed so a fetch can replace it;
     * throws when it was one but cannot be fetched again here. False leaves everything as it was.
     */
    private boolean removeBrokenImage(String image, String buildName, String templateName, boolean reimportable,
                                      IncusException e) {
        if (!hasBrokenInit(buildName)) return false;
        var remote = isRemoteImage(image);
        // Both from the definition: one line each in the refusals below (#1228).
        var shownImage = shown(image);
        var shownName = shown(templateName);
        if (!remote && !reimportable) {
            throw new RuntimeException("The local image '" + shownImage + "' has a broken /sbin/init (Exec format error)."
                    + " isx did not import it, as '" + shownName + "' has no image_url to import it from,"
                    + " so it is left in place: replace the image under that alias, or give the template an"
                    + " image_url, then re-run 'isx build " + shownName + "'.", e);
        }
        var fingerprint = incus.configGet(buildName, "volatile.base_image");
        incus.delete(buildName, true);
        var broken = "The cached image for '" + shownImage + "' has a broken /sbin/init (Exec format error)";
        if (fingerprint.isEmpty()) {
            throw new RuntimeException(broken + ", and Incus did not record which image it was.", e);
        }
        incus.deleteImageOrThrow(fingerprint);
        if (!remote) {
            throw new RuntimeException(broken + ". isx removed it: re-run 'isx build " + shownName
                    + "' to import it again.", e);
        }
        BuildOutput.step(broken + "; removed it, fetching it again.");
        return true;
    }

    private void prepareContainerForPackageInstall(Container container) {
        container.sh(
                "mkdir -p /etc/tmpfiles.d; " +
                "for f in $(grep -rl '/dev/net/tun\\|/dev/fuse' /usr/lib/tmpfiles.d/ 2>/dev/null); do " +
                "  test -f /etc/tmpfiles.d/$(basename \"$f\") || " +
                "  printf '# container override\\n' > /etc/tmpfiles.d/$(basename \"$f\"); " +
                "done; " +
                "mkdir -p /usr/share/man/man{1,2,3,4,5,6,7,8,9}; " +
                // Write a temporary DHCP network config for systemd-networkd to use during the build.
                // Branches replace this with a static config at creation time. Containers only:
                // Incus names a container's NIC eth0, a VM's keeps its own name (#997).
                "mkdir -p /etc/systemd/network; " +
                "printf '[Match]\\nName=eth0\\n\\n[Network]\\nDHCP=ipv4\\n\\n[DHCPv4]\\nUseDNS=no\\n' " +
                "> /etc/systemd/network/10-eth0.network; " +
                "systemctl enable systemd-networkd 2>/dev/null; " +
                "systemctl restart systemd-networkd 2>/dev/null; " +
                "true")
                .assertSuccess("Failed to prepare container for package install");
    }

    private static String normalizeHostArch() {
        var arch = System.getProperty("os.arch");
        return switch (arch) {
            case "amd64" -> "x86_64";
            case "arm64" -> "aarch64";
            default -> arch;
        };
    }

    private List<ResolvedTool> resolveTools(ImageDef imageDef) {
        return BuildTools.resolveTools(imageDef, toolDefLoader, toolSetups, false);
    }

    private void resolveWithDeps(String name, Map<String, String> params,
                                  LinkedHashMap<String, ResolvedTool> resolved,
                                  LinkedHashSet<String> visiting, Set<String> explicit,
                                  Set<String> explicitlyResolved, boolean isExplicit) {
        BuildTools.resolveWithDeps(name, params, resolved, visiting, explicit, explicitlyResolved, isExplicit,
            toolDefLoader, toolSetups, false);
    }

    /**
     * Bring the identities this image bakes in line with its accounts, once its tools are set up,
     * and say what to stamp for them: decided by what the guest holds, not by what config.yaml
     * says it should.
     *
     * <p>Re-derives what the parent baked from an account that has since changed -- another
     * account chosen, or its token replaced with another user's (#281). The copy carries the
     * parent's {@code account-identity} stamps along with its {@code .gitconfig}, and the tools'
     * setup skips an identity that is already present, so without this a rebuilt child would
     * keep the parent's identity while its stamp claimed the current one -- hiding it from the
     * reconcile at branch time too.
     *
     * <p>Also re-derives an identity the guest lacks though an account could supply it
     * ({@link dev.incusspawn.tool.ToolSetup#lacksBakedIdentity}, one exec for gh): a parent
     * built while no token was configured, whether it is stamped
     * {@link Metadata#ACCOUNT_IDENTITY_NONE}, unstamped by an older isx, or stamped by an older
     * isx with an identity it never got. A parent with no stamp but an identity -- built before
     * stamps existed -- keeps it. Where no account can supply one the stamp says
     * {@link Metadata#ACCOUNT_IDENTITY_NONE}, so configuring one later is reconciled on the
     * branches. A failure to re-derive fails the build rather than produce a template whose
     * stamp lies.
     *
     * @return the stamps {@link #stampAccountIdentities} writes once the build is done
     */
    private Map<String, String> settleIdentities(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                                                 Map<String, String> inherited) {
        var config = SpawnConfig.load();
        var setups = AccountSelection.namespaceSetups(config, toolDefLoader);
        return BuildAccounts.settleIdentities(container, config, setups, ImageDef.resolveAccounts(imageDef, defs),
                AccountSelection.rederivableNamespaces(imageDef, defs, toolDefLoader.allToolSetups(), setups),
                inherited, BuildOutput::step, BuildOutput::warn);
    }

    /** Containers only: their NIC is eth0, a VM's is not (see RuntimeSetup.addressUpCheck). */
    private void waitForIpv4(Container container) {
        BuildOutput.stepStart("Waiting for network...");
        var result = container.sh(
                "systemctl start systemd-networkd 2>/dev/null; " +
                "for i in $(seq 1 30); do " +
                "  ip -4 -o addr show eth0 | grep -q 'inet ' && exit 0; " +
                "  sleep 0.5; " +
                "done; exit 1");
        if (result.success()) {
            BuildOutput.stepDone();
            return;
        }
        BuildOutput.stepBreak();
        var diag = container.sh(
                "echo '--- systemd-networkd status ---'; " +
                "systemctl status systemd-networkd 2>&1 || true; " +
                "echo '--- networkctl ---'; " +
                "networkctl status eth0 2>&1 || true; " +
                "echo '--- ip link ---'; " +
                "ip link show eth0 2>&1 || true; " +
                "echo '--- journalctl networkd ---'; " +
                "journalctl -u systemd-networkd --no-pager -n 20 2>&1 || true");
        throw new RuntimeException(
                "Container did not acquire an IPv4 address within 15 seconds.\n" +
                "Diagnostics:\n" + diag.stdout() + diag.stderr());
    }

    private void waitForNetwork(String container) {
        BuildOutput.stepStart("Verifying DNS resolution...");
        for (int attempt = 0; attempt < 10; attempt++) {
            var dnsCheck = incus.shellExec(container, "sh", "-c",
                    "curl -4 -s -o /dev/null -w '%{http_code}' https://mirrors.fedoraproject.org");
            if (dnsCheck.success() && dnsCheck.stdout().strip().contains("302")) {
                BuildOutput.stepDone();
                return;
            }
            if (attempt == 9) {
                BuildOutput.stepBreak();
                var diagnostic = BridgeSubnetCheck.detectConflictDiagnostic(incus);
                var fwDiagnostic = FirewallDetector.detectDiagnostic();
                var message = "DNS resolution is not working. Check your network setup.";
                if (diagnostic != null) {
                    message += "\n\n" + diagnostic;
                }
                if (fwDiagnostic != null) {
                    message += "\n\n" + fwDiagnostic;
                }
                throw new RuntimeException(message);
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }
    }

    void stampBuildVersion(String container, dev.incusspawn.config.ImageDef imageDef,
                                    Map<String, String> identityStamps) {
        var info = BuildInfo.instance();
        incus.configSet(container, Metadata.BUILD_VERSION, info.version());
        incus.configSet(container, Metadata.BUILD_SHA, info.gitSha());
        incus.configSet(container, Metadata.DEFINITION_SHA,
                imageDef.contentFingerprint(computeToolFingerprints(imageDef, toolDefLoader)));
        stampAccountIdentities(container, identityStamps);
    }

    /**
     * What every build path checks before building anything, given exactly the templates it may
     * build: refusals first, then the credentials whose absence {@code isx branch} would refuse.
     */
    private void preflight(Collection<String> templates, Map<String, ImageDef> defs) {
        requireValidAccounts(templates, defs);
        requireConsistentRepos(templates, defs);
        warnUnbranchable(templates, defs);
    }

    /**
     * Warn, before the build's time is spent, about each credential a template needs that is not
     * configured: the build goes ahead, but {@code isx branch} refuses its instances (#1438).
     */
    private void warnUnbranchable(Collection<String> templates, Map<String, ImageDef> defs) {
        Map<CredentialCheck.Missing, List<String>> missing;
        try {
            missing = CredentialCheck.missingByCredential(SpawnConfig.load(), templates, defs, toolDefLoader);
        } catch (RuntimeException e) {
            // Only advice: whatever stops this check, the build and the branch report themselves
            ClientLog.warn("Missing-credential check skipped: " + e);
            return;
        }
        missing.forEach((credential, names) -> BuildOutput.warn("Missing credential: " + credential.credential()
                + ". 'isx branch' will refuse " + OutputFormat.andList(names)
                + " until it is configured with '" + credential.command() + "'."));
    }

    /**
     * Fail the build up front when a template about to be built names an account that is not
     * configured: otherwise it surfaces as an unhandled exception partway through a build, from
     * ClaudeSetup.envEntries or the metadata stamp. Each build path passes exactly the templates
     * it may build, so a broken definition elsewhere never blocks an unrelated build (#805).
     */
    private static void requireValidAccounts(Collection<String> templates, Map<String, ImageDef> defs) {
        var accountError = validateTemplateAccounts(templates, defs);
        if (accountError != null) {
            System.err.println("Cannot build: " + accountError);
            throw new BuildFailedException();
        }
    }

    /** Refuses before any build work when a template redeclares a repo destination differently (#1394). */
    private static void requireConsistentRepos(Collection<String> templates, Map<String, ImageDef> defs) {
        for (var name : templates) {
            var conflict = ImageDef.repoConflict(defs.get(name), defs);
            if (conflict != null) {
                System.err.println("Cannot build: " + conflict);
                throw new BuildFailedException();
            }
        }
    }

    /**
     * The first of {@code templates} whose {@code accounts:} names something that is not
     * configured, or null when all of them resolve. Only definitions that select anything are
     * examined, so a host with no named accounts pays nothing.
     */
    static String validateTemplateAccounts(Collection<String> templates, Map<String, ImageDef> defs) {
        SpawnConfig config = null;
        for (var name : templates) {
            var selection = ImageDef.resolveAccounts(defs.get(name), defs);
            if (selection.isEmpty()) continue;
            if (config == null) config = SpawnConfig.load();
            try {
                AccountSelection.validate(config, selection);
            } catch (dev.incusspawn.config.AccountResolver.UnknownAccountException e) {
                // The namespace and account come from the template's accounts: map (#1228).
                return "template '" + shown(name) + "': " + shown(e.getMessage());
            }
        }
        return null;
    }

    /**
     * Start the stopped build container, first making it known to the proxy as its template
     * with fresh proofs, for explicit account pins and defaults alike: see {@link BuildAccounts#start}.
     */
    BuildAccounts.Started startBuild(String buildName, ImageDef imageDef, Map<String, ImageDef> defs) {
        return BuildAccounts.start(incus, buildName, ImageDef.resolveAccounts(imageDef, defs),
                imageDef.getName(), ProxyService::signalAccountRefresh);
    }

    /** Fail the build if its guest did not take the address {@link #startBuild} gave it. */
    void requireBuildAddress(String buildName, BuildAccounts.Started started) {
        if (started.address() != null) {
            InstanceNetwork.requireBuildAddress(incus, buildName, started.address());
        }
    }

    /** Stop the finished template and give back the address {@link #startBuild} gave it. */
    void stopBuild(String buildName, BuildAccounts.Started started) {
        BuildAccounts.revoke(incus, buildName);
        ProxyService.signalAccountRefresh();
        BuildAccounts.clearGuestProofs(new Container(incus, buildName));
        BuildOutput.stepStart("Stopping image...");
        incus.stop(buildName);
        BuildOutput.stepDone();
        if (started.address() != null) releaseBuildAddress(buildName);
    }

    /**
     * Give back the finished template's address. Warns rather than fails: the template is
     * built, and an address it keeps costs one of the bridge's addresses, not correctness --
     * its copies never carry it, and it is freed when the template is next rebuilt or removed.
     */
    private void releaseBuildAddress(String buildName) {
        try {
            InstanceNetwork.releaseBuildAddress(incus, buildName);
        } catch (RuntimeException e) {
            BuildOutput.warn("Could not release the build's static IP: " + e.getMessage()
                    + ". The template keeps it until it is rebuilt or removed.");
        }
    }

    /** Say which accounts the build is served, so a wrong one is not silent. */
    private static void announceBuildAccounts(ImageDef imageDef, Map<String, ImageDef> defs) {
        var selection = ImageDef.resolveAccounts(imageDef, defs);
        if (!selection.isEmpty()) {
            BuildOutput.step("Credential accounts: " + AccountSelection.describe(selection) + ".");
        }
    }

    /**
     * Record what this template baked from each namespace's account
     * ({@link #settleIdentities}). The pins themselves were stamped before the build started
     * ({@link BuildAccounts#start}).
     *
     * <p>They travel to every branch through the CoW copy. The stamps are what
     * {@link dev.incusspawn.config.AccountSelection#incompatibilityReason} compares against to
     * refuse a swap that the baked environment could not honour, and what the reconcile at
     * branch time re-derives from.
     */
    private void stampAccountIdentities(String container, Map<String, String> identityStamps) {
        if (!identityStamps.isEmpty()) incus.configSetAll(container, identityStamps);
    }

    private BuildSource collectBuildSource(ImageDef imageDef, Map<String, ImageDef> defs) {
        var definitions = new LinkedHashMap<String, ImageDef>();
        var tools = new LinkedHashMap<String, dev.incusspawn.tool.ToolDef>();
        var toolInstances = new LinkedHashMap<String, BuildSource.ToolInstance>();
        var sources = new LinkedHashMap<String, String>();
        var projectRoots = new LinkedHashMap<String, String>();

        var visited = new HashSet<String>();
        for (var current : ImageDef.chain(imageDef, defs).reversed()) {
            definitions.put(current.getName(), current);
            sources.put(current.getName(), current.getSource());
            if (current.getProjectRoot() != null) {
                projectRoots.put(current.getName(), current.getProjectRoot().toString());
            }
            collectToolDefs(current, tools, visited);
            collectToolInstances(current, toolInstances);
        }

        var buildSource = new BuildSource(definitions, tools, toolInstances, sources);
        buildSource.setProjectRoots(projectRoots);
        return buildSource;
    }

    private void collectToolInstances(ImageDef imageDef, Map<String, BuildSource.ToolInstance> instances) {
        for (var resolvedTool : resolveTools(imageDef)) {
            if (!resolvedTool.parameters().isEmpty()) {
                // Use putIfAbsent so child parameters win over parent parameters
                instances.putIfAbsent(resolvedTool.name(),
                    new BuildSource.ToolInstance(resolvedTool.name(), resolvedTool.parameters()));
            }
        }
    }

    private void collectToolDefs(ImageDef imageDef, Map<String, dev.incusspawn.tool.ToolDef> tools,
                                  Set<String> visited) {
        var toolRefs = imageDef.getTools();
        if (toolRefs == null) return;
        for (var toolRef : toolRefs) {
            collectToolDefRecursive(toolRef.getName(), tools, visited);
        }
    }

    private void collectToolDefRecursive(String name, Map<String, dev.incusspawn.tool.ToolDef> tools,
                                          Set<String> visited) {
        if (!visited.add(name)) return;
        var setup = toolDefLoader.find(name);
        if (setup instanceof YamlToolSetup yts) {
            tools.put(name, yts.toolDef());
            var deps = yts.toolDef().getRequires();
            if (deps != null) {
                for (var depRef : deps) {
                    collectToolDefRecursive(depRef.getName(), tools, visited);
                }
            }
        }
    }

    void tagTemplateMetadata(String buildName, String canonicalName, ImageDef imageDef,
                                    String parentCanonicalName,
                                    List<ImageDef.HostResource> hostResources,
                                    Map<String, ImageDef> defs,
                                    Map<String, String> identityStamps, String trackedImageTag) {
        incus.configSet(buildName, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(buildName, Metadata.PROFILE, canonicalName);
        incus.configSet(buildName, Metadata.INSTANCE_MODE, effectiveType(imageDef));
        if (parentCanonicalName != null) {
            incus.configSet(buildName, Metadata.PARENT, parentCanonicalName);
        }
        incus.configSet(buildName, Metadata.CREATED, Metadata.today());
        stampBuildVersion(buildName, imageDef, identityStamps);
        if (!hostResources.isEmpty()) {
            incus.configSet(buildName, Metadata.HOST_RESOURCES,
                    HostResourceSetup.serialize(hostResources));
        }
        var buildSource = collectBuildSource(imageDef, defs);
        buildSource.setTrackedImageTag(trackedImageTag);
        incus.configSet(buildName, Metadata.BUILD_SOURCE, buildSource.toJson());

        var effectiveWorkdir = resolveEffectiveWorkdir(imageDef, defs);
        if (effectiveWorkdir != null) {
            incus.configSet(buildName, Metadata.WORKDIR, effectiveWorkdir);
        }
        if (imageDef.getShellCommand() != null && !imageDef.getShellCommand().isBlank()) {
            incus.configSet(buildName, Metadata.SHELL_COMMAND, imageDef.getShellCommand());
        }
        var effectiveDefaultAction = resolveEffectiveDefaultAction(imageDef, defs);
        if (effectiveDefaultAction != null) {
            validateDefaultAction(effectiveDefaultAction, imageDef, defs);
            incus.configSet(buildName, Metadata.DEFAULT_ACTION, effectiveDefaultAction);
        } else {
            incus.configUnset(buildName, Metadata.DEFAULT_ACTION);
        }
    }

    /**
     * Bring the default-action stamp of every up-to-date template in line with its definition
     * (#284). default-action is left out of the fingerprint, since changing it does not change
     * the image, so editing it never makes a template outdated and would otherwise never reach
     * the stamp ActionResolver falls back to. One list request covers every template, whichever
     * ones this build skipped; a stamp is written only where it differs.
     */
    void syncDefaultActions(Map<String, ImageDef> defs) {
        JqValue instances;
        try {
            instances = incus.listJsonConfig();
        } catch (IncusException e) {
            System.err.println("Warning: could not sync default-action onto templates: " + e.getMessage());
            return;
        }
        for (var instance : instances.asList()) {
            var name = instance.pathText("name", "");
            var def = defs.get(name);
            var config = instance.getField("config");
            if (def == null || !Metadata.TYPE_BASE.equals(config.pathText(Metadata.TYPE, ""))) continue;
            var chain = definitionChain(def, defs);
            if (chain == null) continue;
            var effective = resolveEffectiveDefaultAction(def, defs);
            if (Objects.requireNonNullElse(effective, "").equals(config.path(Metadata.DEFAULT_ACTION).asText(""))) {
                continue;
            }
            // Any template may fail here, e.g. on tool parameters that no longer validate. It is
            // not the one this build was about, so it must not turn a finished build into an error.
            try {
                if (!builtFromCurrentChain(chain, config)) continue;
                if (effective != null) {
                    incus.configSet(name, Metadata.DEFAULT_ACTION, effective);
                } else {
                    incus.configUnset(name, Metadata.DEFAULT_ACTION);
                }
            } catch (RuntimeException e) {
                System.err.println("Warning: could not sync default-action onto " + name + ": " + e.getMessage());
            }
        }
    }

    /**
     * {@code def} and its ancestors, child first, or null when the chain does not reach a root
     * in {@code defs}: with a parent's definition gone, its default-action is unknown.
     */
    static List<ImageDef> definitionChain(ImageDef def, Map<String, ImageDef> defs) {
        var chain = new ArrayList<ImageDef>();
        var seen = new HashSet<String>();
        for (var current = def; current != null; current = defs.get(current.getParent())) {
            if (!seen.add(current.getName())) return null;
            chain.add(current);
            if (current.isRoot()) return chain;
        }
        return null;
    }

    /**
     * Whether a template is the build of {@code chain} as it stands, and so may take its
     * default-action: its own definition-sha is current, and its build-source recorded every
     * ancestor with the definition and project it has now (an ancestor's tools are not
     * compared). An outdated template, or one built before a parent changed, keeps the stamp
     * that matches what it has installed until it is rebuilt, and a template built from another
     * project's definitions is not theirs to change.
     */
    private boolean builtFromCurrentChain(List<ImageDef> chain, JqValue config) {
        var def = chain.getFirst();
        if (!BuildInfo.instance().version().equals(config.pathText(Metadata.BUILD_VERSION, ""))
                || !def.contentFingerprint(computeToolFingerprints(def, toolDefLoader))
                        .equals(config.pathText(Metadata.DEFINITION_SHA, ""))) {
            return false;
        }
        var buildSource = BuildSource.fromJson(config.pathText(Metadata.BUILD_SOURCE, ""));
        if (buildSource == null) return false;
        for (var current : chain) {
            var built = buildSource.getDefinitions().get(current.getName());
            if (built == null
                    || !Objects.equals(built.getProjectRoot(), current.getProjectRoot())
                    || !built.contentFingerprint(Map.of()).equals(current.contentFingerprint(Map.of()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The nearest explicit {@code workdir} in the chain, else the first repo of the nearest
     * template that declares any. An explicit workdir is checked across the whole chain first,
     * so a child that only adds a helper repo keeps the workdir its parent chose (#1262).
     */
    static String resolveEffectiveWorkdir(ImageDef imageDef, Map<String, ImageDef> defs) {
        var chain = ImageDef.chain(imageDef, defs).reversed();
        for (var def : chain) {
            if (def.getWorkdir() != null && !def.getWorkdir().isBlank()) {
                return expandHome(def.getWorkdir());
            }
        }
        for (var def : chain) {
            if (!def.getRepos().isEmpty()) {
                return expandHome(def.getRepos().get(0).getPath());
            }
        }
        return null;
    }

    private static void validateDefaultAction(String ref, ImageDef imageDef, Map<String, ImageDef> defs) {
        var toolName = ref;
        int colon = ref.indexOf(':');
        if (colon >= 0) toolName = ref.substring(0, colon);

        var allTools = new java.util.LinkedHashSet<String>();
        for (var current : ImageDef.chain(imageDef, defs).reversed()) {
            for (var toolRef : current.getTools()) {
                allTools.add(toolRef.getName());
            }
        }
        if (!allTools.contains(toolName)) {
            System.err.println("Warning: default-action '" + ref
                    + "' references tool '" + toolName
                    + "' which is not in the tools list. "
                    + "Add it to tools: [" + toolName + "] or remove default-action.");
        }
    }

    static String resolveEffectiveDefaultAction(ImageDef imageDef, Map<String, ImageDef> defs) {
        for (var current : ImageDef.chain(imageDef, defs).reversed()) {
            if (current.getDefaultAction() != null) {
                return current.getDefaultAction();
            }
        }
        return null;
    }

    static class BuildFailedException extends RuntimeException {
        final String containerName;

        BuildFailedException() {
            this(null);
        }

        BuildFailedException(String containerName) {
            super(null, null, true, false);
            this.containerName = containerName;
        }
    }

    /**
     * Grow a VM's root partition and filesystem into the disk {@code root.size} gave it, wherever
     * the root is: {@code sda2} on virtio-scsi, {@code vda2} on virtio-blk, which VM builds use
     * (#1255), {@code nvme0n1p2} on NVMe. The partition is the root's mount source, without a
     * btrfs subvolume ({@code -v}); its disk comes from {@code lsblk}, and its number is what
     * follows the disk's name (and NVMe's {@code p}), since lsblk's {@code PARTN} needs
     * util-linux 2.39. With no initrd the kernel mounts the root as {@code /dev/root}, which
     * findmnt resolves to the partition by its device number.
     */
    static final String VM_ROOT_EXPANSION =
            "root=$(findmnt -n -v -o SOURCE /) && disk=$(lsblk -ndo PKNAME \"$root\") && [ -n \"$disk\" ] && "
            + "part=${root##*/} && part=${part#\"$disk\"} && part=${part#p} && "
            + "growpart \"/dev/$disk\" \"$part\" && "
            + "if findmnt -n -o FSTYPE / | grep -q xfs; then xfs_growfs /; else resize2fs \"$root\"; fi";

    /**
     * Give agentuser its home when {@code useradd -m} created it, and when it did not: host
     * resources are attached before start (#828), so a mount under {@code /home/agentuser} already
     * made the directory as root. {@code useradd} then succeeds but skips {@code /etc/skel}, and a
     * {@code chown -R} would fail on the read-only mount. So copy skel without clobbering, chown
     * everything except mount points, which belong to the host, and give the home the 0700 that
     * {@code useradd -m} would have (a pre-created one is 0755). The copy's status is ignored:
     * GNU coreutils 9.2 to 9.4 exit 1 whenever {@code -n} leaves a file alone, which after a normal
     * {@code useradd -m} is every file (#981). The ownership and mode steps still fail the build.
     */
    static final String AGENT_HOME_OWNERSHIP =
            "cp -an /etc/skel/. /home/agentuser/; chown agentuser:agentuser /home/agentuser && "
            + "chmod 700 /home/agentuser && "
            + "find /home/agentuser -mindepth 1 -exec mountpoint -q {} \\; -prune "
            + "-o -exec chown -h agentuser:agentuser {} +";

    /**
     * Attach every disk device the build needs while the instance is still stopped (#828). On a VM
     * a device present at start gets its own PCIe root port, while a hot-plug takes one of only 8
     * spare slots, which repo references need; and incus-agent mounts boot-time devices before it
     * serves the first exec, so nothing has to poll for them. Returns the DNF cache warning, if any.
     */
    private String attachBootDevices(String buildName, List<ImageDef.HostResource> hostResources,
                                     MachineType machineType) {
        var dnfCacheWarning = new GuestProvisioning(incus).attachDnfCache(buildName);
        HostResourceSetup.attachBuildDevices(incus, buildName, hostResources, machineType);
        return dnfCacheWarning;
    }

    /** {@link BuildTools#collectEffectiveTools} with this command's tool loader and CDI tools. */
    ToolResolution collectEffectiveTools(ImageDef imageDef, Map<String, ImageDef> defs) {
        return BuildTools.collectEffectiveTools(imageDef, defs, toolDefLoader, toolSetups);
    }

    /** Prepare and clone the repos without changing user checkouts. */
    void cloneRepos(Container container, ImageDef imageDef, Map<String, ImageDef> defs, MachineType machineType) {
        new RepoCloner(incus).cloneRepos(container, imageDef, defs, machineType);
    }

    enum InstanceType {
        container,
        vm,
        kvm
    }

}
