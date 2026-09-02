package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.baseimage.BaseImageReleases;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.EnvResolver;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.git.HostRepoRefresh;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.Platform;
import static dev.incusspawn.incus.Container.shellQuote;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;

import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.CodexSetup;
import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.CpuInfo;
import dev.incusspawn.util.TerminalProgress;
import dev.incusspawn.RuntimeServices;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@CommandDefinition(
        name = "build",
        description = "Build or rebuild a template image (e.g. tpl-minimal, tpl-java)",
        generateHelp = true
)
public class BuildCommand extends BaseCommand {

    @Argument(description = "Name of the template (e.g. tpl-minimal, tpl-java)",
            required = false)
    String name;

    @Option(name = "all", hasValue = false, description = "Rebuild all defined templates")
    boolean all;

    @Option(name = "out-of-sync", hasValue = false, description = "Rebuild templates that are out of sync (definition or isx version changed)")
    boolean outOfSync;

    @Option(name = "with-parents", hasValue = false, description = "Rebuild the template and all its parents unconditionally")
    boolean withParents;

    @Option(name = "with-descendants", hasValue = false, description = "Rebuild the template and all templates inheriting from it")
    boolean withDescendants;

    @Option(name = "missing", hasValue = false, description = "Build only templates that don't exist yet")
    boolean missing;

    @Option(name = "type", description = "Instance type: container, vm, or kvm (overrides image definition)")
    InstanceType type;

    @Option(name = "yes", hasValue = false, description = "Skip interactive confirmations (for TUI integration)")
    boolean yes;

    @Option(name = "skip-git-refresh", hasValue = false, description = "Skip refreshing host-side git repositories before building")
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
        var console = System.console();
        if (console == null) return true;
        if (!askConfirmation(console, prompt, false)) {
            System.out.println("Aborted.");
            return false;
        }
        return true;
    }
    private static final String DNF_CACHE_DEVICE = "dnf-cache";
    static final String REBUILDING_SUFFIX = "-rebuilding";

    private int buildIndex;
    private int buildTotal;
    private volatile boolean savedFailureSummary;

    private volatile String[] activeBuild;

    private HostRepoRefresh.AsyncRefresh hostRepoRefresh;

    /** Root defs whose latest base image was already resolved this invocation (avoids re-fetching). */
    private final Set<String> resolvedRoots = new HashSet<>();

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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var build = activeBuild;
            if (build != null) {
                reportBuildFailure(build[0], build[1], "Build interrupted for " + build[1]);
                promoteToFailedInstance(build[0], build[1]);
            }
        }));

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
        var defs = loaded.defs();

        var executor = Executors.newCachedThreadPool(r -> {
            var t = new Thread(r, "git-refresh");
            t.setDaemon(true);
            return t;
        });
        try {
            if (withParents) {
                if (name == null) {
                    System.err.println("Usage: isx build <template-name> --with-parents");
                    return CommandResult.valueOf(1);
                }
                var imageDef = defs.get(name);
                if (imageDef == null) {
                    System.err.println("Unknown image: " + name);
                    System.err.println("Available images: " + String.join(", ", defs.keySet()));
                    return CommandResult.valueOf(1);
                }
                startHostRepoRefresh(List.of(imageDef), defs, executor);
                buildWithParents(imageDef, defs);
                return CommandResult.SUCCESS;
            }
            if (withDescendants) {
                if (name == null) {
                    System.err.println("Usage: isx build <template-name> --with-descendants");
                    return CommandResult.valueOf(1);
                }
                var imageDef = defs.get(name);
                if (imageDef == null) {
                    System.err.println("Unknown image: " + name);
                    System.err.println("Available images: " + String.join(", ", defs.keySet()));
                    return CommandResult.valueOf(1);
                }
                startHostRepoRefresh(List.of(imageDef), defs, executor);
                buildWithDescendants(imageDef, defs);
                return CommandResult.SUCCESS;
            }
            if (missing) {
                startHostRepoRefresh(new ArrayList<>(defs.values()), defs, executor);
                buildMissing(defs);
                return CommandResult.SUCCESS;
            }
            if (outOfSync) {
                startHostRepoRefresh(new ArrayList<>(defs.values()), defs, executor);
                buildAll(defs, true);
                return CommandResult.SUCCESS;
            }
            if (all) {
                startHostRepoRefresh(new ArrayList<>(defs.values()), defs, executor);
                buildAll(defs, false);
                return CommandResult.SUCCESS;
            }

            if (name == null) {
                System.err.println("Usage: isx build <image-name>  or  isx build --all");
                System.err.println("Available images: " + String.join(", ", defs.keySet()));
                return CommandResult.valueOf(1);
            }

            var imageDef = defs.get(name);
            if (imageDef == null && incus.exists(name)) {
                var buildSource = BuildSource.fromJson(
                        incus.configGet(name, Metadata.BUILD_SOURCE));
                if (buildSource != null) {
                    for (var entry : buildSource.getDefinitions().entrySet()) {
                        defs.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                    toolDefLoader.addFallbacks(buildSource.getTools());
                    imageDef = defs.get(name);
                }
            }
            if (imageDef == null) {
                System.err.println("Unknown image: " + name);
                System.err.println("Available images: " + String.join(", ", defs.keySet()));
                return CommandResult.valueOf(1);
            }
            startHostRepoRefresh(List.of(imageDef), defs, executor);
            build(imageDef, defs);
            return CommandResult.SUCCESS;
        } catch (BuildFailedException e) {
            return CommandResult.valueOf(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private void startHostRepoRefresh(List<ImageDef> targets, Map<String, ImageDef> defs,
                                      ExecutorService executor) {
        if (skipGitRefresh) {
            hostRepoRefresh = HostRepoRefresh.AsyncRefresh.COMPLETE;
            return;
        }
        var config = SpawnConfig.load();
        var allRepos = HostRepoRefresh.collectAllRepos(targets, defs);
        hostRepoRefresh = HostRepoRefresh.refreshAsync(allRepos, config, true, executor);
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

        // Confirm with user
        BuildOutput.step((outdatedOnly ? "Templates to rebuild: " : "This will rebuild: ")
                + String.join(", ", templatesToRebuild));
        if (!confirm(outdatedOnly ? "Rebuild?" : "Continue?")) return;

        rebuildAll(templatesToRebuild, defs);
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

        if (!failedBuilds.isEmpty()) {
            System.err.println("\n\033[1;31mSome templates failed to build: " +
                    String.join(", ", failedBuilds) + "\033[0m");
            throw new BuildFailedException();
        }
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
            for (var template : defs.values()) {
                if (seen.contains(template.getName())) continue;
                if (!incus.exists(template.getName())
                        || isImageOutdated(template.getName(), template, incus, toolDefLoader, defs)) {
                    collectAncestors(template, defs, result, seen, incus, toolDefLoader);
                    if (seen.add(template.getName())) {
                        result.add(template.getName());
                    }
                    collectDescendants(template.getName(), defs, result, seen);
                }
            }
        } else {
            for (var leaf : leaves) {
                collectAllRecursive(leaf, defs, result, seen);
            }
        }
    }

    static void collectAllRecursive(ImageDef imageDef, Map<String, ImageDef> defs,
                                     List<String> result, Set<String> seen) {
        var name = imageDef.getName();
        if (seen.contains(name)) return;
        if (!imageDef.isRoot()) {
            var parentDef = defs.get(imageDef.getParent());
            if (parentDef != null) {
                collectAllRecursive(parentDef, defs, result, seen);
            }
        }
        seen.add(name);
        result.add(name);
    }

    private static void collectAncestors(ImageDef imageDef, Map<String, ImageDef> defs,
                                          List<String> result, Set<String> seen,
                                          IncusClient incus, ToolDefLoader toolDefLoader) {
        if (imageDef.isRoot()) return;
        var parentName = imageDef.getParent();
        if (seen.contains(parentName)) return;
        var parentDef = defs.get(parentName);
        if (parentDef == null) return;
        if (!incus.exists(parentName)
                || isImageOutdated(parentName, parentDef, incus, toolDefLoader, defs)) {
            collectAncestors(parentDef, defs, result, seen, incus, toolDefLoader);
            seen.add(parentName);
            result.add(parentName);
        }
    }

    static void collectDescendants(String parentName, Map<String, ImageDef> defs,
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
        var current = imageDef;
        while (!current.isRoot()) {
            var parentName = current.getParent();
            if (failedBuilds.contains(parentName)) {
                return true;
            }
            current = defs.get(parentName);
            if (current == null) break;
        }
        return false;
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
        var leaves = defs.values().stream()
                .filter(d -> !parentNames.contains(d.getName()))
                .toList();
        for (var leaf : leaves) {
            if (!incus.exists(leaf.getName())) {
                build(leaf, defs);
                System.out.println();
            }
        }
    }

    /**
     * Unconditionally rebuild a template and all its ancestors.
     */
    private void buildWithParents(ImageDef imageDef, Map<String, ImageDef> defs) {
        var chain = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        collectAllRecursive(imageDef, defs, chain, seen);

        BuildOutput.step("This will rebuild: " + String.join(", ", chain));
        if (!confirm("Continue?")) return;

        rebuildAll(chain, defs);
    }

    /**
     * Unconditionally rebuild a template and all templates that inherit from it.
     */
    private void buildWithDescendants(ImageDef imageDef, Map<String, ImageDef> defs) {
        var chain = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        chain.add(imageDef.getName());
        seen.add(imageDef.getName());
        collectDescendants(imageDef.getName(), defs, chain, seen);

        BuildOutput.step("This will rebuild: " + String.join(", ", chain));
        if (!confirm("Continue?")) return;

        rebuildAll(chain, defs);
    }

    /**
     * Build an image. If the image has a parent, ensure the parent
     * is built first (recursively).
     */
    private void build(ImageDef imageDef, Map<String, ImageDef> defs) {
        var dnsOverrides = ProxyConfig.getDnsOverrides(incus);
        if (!dnsOverrides.isEmpty() && dnsOverrides.contains("address=/")) {
            ProxyHealthCheck.requireProxy(incus);
        }

        buildChain(imageDef, defs);
    }

    private void buildChain(ImageDef imageDef, Map<String, ImageDef> defs) {
        if (!imageDef.isRoot()) {
            var parentName = imageDef.getParent();
            var parentDef = defs.get(parentName);
            if (parentDef == null) {
                System.err.println("Parent image '" + parentName + "' not found in definitions.");
                System.exit(1);
            }

            // When the target type differs from the parent's resolved type
            // (e.g. building a VM from container parents), buildFromScratch
            // applies the entire ancestor chain from definitions alone —
            // parent Incus instances are not needed.
            boolean typeChange = effectiveVm(imageDef) != effectiveVm(parentDef);
            if (!typeChange) {
                boolean parentMissing = !incus.exists(parentName);
                boolean needsRebuild = parentMissing || isImageOutdated(parentName, parentDef, incus, toolDefLoader, defs);

                if (needsRebuild) {
                    if (parentMissing) {
                        BuildOutput.note("Parent '" + parentName + "' not found, building first.");
                    } else {
                        BuildOutput.note("Parent '" + parentName + "' is outdated, rebuilding first.");
                    }
                    buildChain(parentDef, defs);
                }
            }
        }

        buildSingleImage(imageDef, defs);
    }

    /**
     * Build a single image without checking or building parents.
     * Assumes parent is already built and up-to-date.
     * Builds with a temporary name and swaps atomically on success.
     */
    private void buildSingleImage(ImageDef imageDef, Map<String, ImageDef> defs) {
        var canonicalName = imageDef.getName();
        var tempName = canonicalName + REBUILDING_SUFFIX;

        BuildOutput.buildHeader(canonicalName, buildIndex, buildTotal);

        if (incus.exists(canonicalName)) {
            if (!yes) {
                BuildOutput.step("Image already exists. It will be replaced if the build succeeds.");
            }
            warnDroppedTools(canonicalName, imageDef, defs);
            if (!confirm("Rebuild?")) return;
        }

        incus.deleteIfExists(tempName);
        activeBuild = new String[]{tempName, canonicalName};

        try {
            boolean typeChange = !imageDef.isRoot()
                    && effectiveVm(imageDef) != incus.isVm(imageDef.getParent());
            if (imageDef.isRoot() || typeChange) {
                buildFromScratch(imageDef, defs, tempName);
            } else {
                buildFromParent(imageDef, defs, tempName, imageDef.getParent());
            }
        } catch (Exception e) {
            reportBuildFailure(tempName, canonicalName,
                    "Build failed for " + canonicalName + ": " + e.getMessage());
            try {
                var failedHostResources = HostResourceSetup.collectEffective(imageDef, defs);
                HostResourceSetup.removeBuildDevices(incus, tempName, failedHostResources);
            } catch (Exception ignored) {}
            promoteToFailedInstance(tempName, canonicalName);
            activeBuild = null;
            throw new BuildFailedException(canonicalName);
        }

        // Measure the just-built template's referenced size BEFORE deleting the previous subvolume.
        // Deleting a btrfs subvolume marks the pool's qgroup accounting inconsistent, so an rfer read
        // taken after deleteIfExists (i.e. on every *rebuild*) can come back stale or zero — which
        // drops the stamp and collapses the TUI's per-template delta model to the fold fallback
        // (every template ~0, a shrunken base on the root). tempName's rfer is exactly what
        // canonicalName reports after the rename: rfer is per-subvolume, unaffected by renaming it or
        // by deleting a sibling subvolume.
        long referenced = probeReferencedSize(tempName);

        incus.deleteIfExists(canonicalName);
        incus.rename(tempName, canonicalName);
        activeBuild = null;

        stampReferencedSize(canonicalName, referenced);
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
            // sync=true: force a commit so the build's final (otherwise uncommitted) writes are
            // accounted. This is the rare accuracy-critical read; sampling uses the plain flavour.
            var rfer = dev.incusspawn.incus.BtrfsUsage.probe(probe.poolName(), true).get(name);
            return rfer == null ? -1 : rfer;
        } catch (Exception ignored) {
            // Non-fatal: the referenced-size stamp is a display optimisation, not build state.
            return -1;
        }
    }

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

    private void warnDroppedTools(String existingImage, ImageDef imageDef, Map<String, ImageDef> defs) {
        var oldSourceJson = incus.configGet(existingImage, Metadata.BUILD_SOURCE);
        var removed = findDroppedTools(oldSourceJson, imageDef, defs);
        if (!removed.isEmpty()) {
            BuildOutput.step("\033[33m⚠ Tools no longer included in " + imageDef.getName() + ":\033[0m");
            for (var tool : removed) {
                BuildOutput.step("  - " + tool);
            }
            BuildOutput.step("  Add to your template's tools: list if you still need them.");
        }
    }

    static Set<String> findDroppedTools(String oldBuildSourceJson, ImageDef imageDef, Map<String, ImageDef> defs) {
        var oldSource = BuildSource.fromJson(oldBuildSourceJson);
        if (oldSource == null) return Set.of();

        var oldTools = new LinkedHashSet<String>();
        for (var def : oldSource.getDefinitions().values()) {
            if (def.getTools() != null) {
                for (var t : def.getTools()) oldTools.add(t.getName());
            }
        }

        var newTools = new LinkedHashSet<String>();
        var current = imageDef;
        while (current != null) {
            if (current.getTools() != null) {
                for (var t : current.getTools()) newTools.add(t.getName());
            }
            if (current.isRoot()) break;
            current = defs.get(current.getParent());
        }

        oldTools.removeAll(newTools);
        return oldTools;
    }

    /**
     * Check if an image is outdated (built with an older version of isx or with a different definition).
     */
    static boolean isImageOutdated(String imageName, ImageDef imageDef,
                                    IncusClient incus, ToolDefLoader toolDefLoader,
                                    Map<String, ImageDef> defs) {
        var currentVersion = BuildInfo.instance().version();
        var buildVersion = incus.configGet(imageName, Metadata.BUILD_VERSION);

        // Check if built with an older version of isx
        if (buildVersion != null && !buildVersion.isEmpty() && !buildVersion.equals(currentVersion)) {
            return true;
        }

        // Check if built with a missing version (very old build)
        if (buildVersion == null || buildVersion.isEmpty()) {
            return true;
        }

        // Check if definition has changed
        var storedSha = incus.configGet(imageName, Metadata.DEFINITION_SHA);
        if (storedSha != null && !storedSha.isEmpty()) {
            var currentSha = imageDef.contentFingerprint(
                    computeToolFingerprints(imageDef, toolDefLoader, defs));
            if (!storedSha.equals(currentSha)) {
                return true;
            }
        }

        return false;
    }

    private String printBuildDiagnostics(String buildName) {
        var diag = new StringBuilder();
        try {
            var status = incus.getInstanceStatus(buildName);
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

            var pool = incus.findCowPool();
            if (pool != null) {
                var poolUsage = incus.getPoolUsageBytes(pool);
                if (poolUsage != null) {
                    appendDiag(diag, "  " + poolUsage.format(pool));
                    if (poolUsage.percent() >= IncusClient.PoolUsage.CRIT_PERCENT) {
                        var hint = Platform.isMacOS()
                                ? " or 'isx vm resize' to grow the appliance disk"
                                : "";
                        appendDiag(diag, "  \033[1mLikely cause: storage pool is "
                                + poolUsage.percent() + "% full"
                                + " — run 'isx clean pool' to reclaim space" + hint + "\033[0m");
                    }
                }
            }

            var mem = incus.getServerMemoryUsage();
            if (!mem.isEmpty()) {
                appendDiag(diag, "  " + mem);
            }

            if ("Error".equals(status) || "Stopped".equals(status)) {
                var cause = diagnoseInotifyExhaustion(incus);
                if (cause != null) {
                    appendDiag(diag, "  Cause: " + cause);
                }
                if ("Error".equals(status)) {
                    var dmesg = incus.queryDmesgForContainer(buildName);
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
            int limit = incus.getInotifyMaxInstances();
            if (limit < 0) return null;
            var instances = incus.list();
            long running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .count();
            // +1: the container that just died isn't Running anymore but was consuming inotify instances
            long estimatedUsage = (running + 1) * 10;
            if (estimatedUsage >= limit * 0.7) {
                return "inotify instance limit likely exhausted — ~" + (running + 1)
                        + " containers × ~10 inotify instances each ≈ " + estimatedUsage
                        + ", limit is " + limit
                        + ". Fix: sudo sysctl -w fs.inotify.max_user_instances=8192"
                        + " (or run 'isx init' to apply permanently)";
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void promoteToFailedInstance(String buildName, String canonicalName) {
        var promotedName = canonicalName + "-failed-build";
        try {
            incus.deleteIfExists(promotedName);
            try { unmountDnfCache(buildName); } catch (Exception ignored) {}
            if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(buildName))) {
                incus.forceStop(buildName);
            }
            incus.rename(buildName, promotedName);
            incus.configSetAll(promotedName, Map.of(
                    Metadata.TYPE, Metadata.TYPE_FAILED_BUILD,
                    Metadata.PARENT, canonicalName,
                    Metadata.CREATED, Metadata.now()));
            var hint = savedFailureSummary ? " (see ~/inbox/BUILD_FAILURE.txt)" : "";
            System.err.println("\033[1mContainer promoted to instance '" + promotedName
                    + "' for inspection" + hint + ".\033[0m");
        } catch (Exception promoteError) {
            System.err.println("Failed to promote container: " + promoteError.getMessage());
            System.err.println("Container '" + buildName + "' may still exist for manual cleanup.");
        }
    }

    private void reportBuildFailure(String buildName, String canonicalName, String errorLine) {
        System.err.println("\n\033[33m" + "─".repeat(60) + "\033[0m");
        System.err.println("\033[1m" + errorLine + "\033[0m");
        var diagnostics = printBuildDiagnostics(buildName);
        savedFailureSummary = false;
        try {
            new Container(incus, buildName)
                    .writeFile("/home/agentuser/inbox/BUILD_FAILURE.txt",
                            errorLine + "\n\n" + diagnostics);
            savedFailureSummary = true;
        } catch (Exception ignored) {}
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
        var effectiveVm = effectiveVm(imageDef);

        BuildOutput.stepStart("Deriving from parent image '" + parentCanonical + "'...");
        incus.copy(parentSource, buildName);
        if (!effectiveVm) {
            incus.configSet(buildName, "security.idmap.size", "165536");
            incus.configSet(buildName, "security.nesting", "true");
            if (Platform.isLinux()) {
                incus.configSet(buildName, "security.syscalls.intercept.setxattr", "true");
            }
            incus.configSet(buildName, "raw.lxc", "lxc.cap.drop =");
            incus.deviceAdd(buildName, "tun", "unix-char",
                    "source=/dev/net/tun", "path=/dev/net/tun", "mode=0666");
        }
        incus.start(buildName);
        incus.waitForReady(buildName);

        var container = new Container(incus, buildName);
        if (!effectiveVm) {
            prepareContainerForPackageInstall(container);
        }

        incus.waitForSystemd(buildName);
        BuildOutput.stepDone();

        if (!effectiveVm) {
            waitForIpv4(container);
        }

        container.sh(
                "sed -i 's/resolve \\[!UNAVAIL=return\\] //' /etc/nsswitch.conf; " +
                "rm -f /etc/resolv.conf; " +
                "printf '%s' '" + ProxyConfig.resolvConfContent(incus) + "' > /etc/resolv.conf")
                .assertSuccess("Failed to fix DNS after copy");

        if (CertificateAuthority.fixContainerCaIfNeeded(incus, buildName)) {
            BuildOutput.step("Refreshed MITM proxy CA certificate.");
        }

        waitForNetwork(buildName);

        mountDnfCache(buildName, effectiveVm);

        var hostResources = HostResourceSetup.collectEffective(imageDef, defs);
        if (!hostResources.isEmpty()) {
            BuildOutput.step("Applying host resources.");
            HostResourceSetup.applyForBuild(incus, container, hostResources, effectiveVm);
        }

        removePackages(container, imageDef);

        var toolResolution = collectEffectiveTools(imageDef, defs);
        syncInheritedGcloudStub(container, toolResolution);
        enablePackageRepos(container, imageDef, toolResolution.effective(), toolResolution.ancestors(), defs);
        installAllPackages(container, imageDef, toolResolution.effective(), toolResolution.ancestors(), defs);

        runToolSetup(container, toolResolution.effective());
        var allTools = new ArrayList<>(toolResolution.ancestors());
        allTools.addAll(toolResolution.effective());
        writeEnvFile(container, imageDef, defs, allTools, canonicalName);
        linkJavaTrustStores(container);
        maskServices(container, imageDef);
        installSkills(container, imageDef, defs);
        cloneRepos(container, imageDef, effectiveVm);
        updateClaudeJsonTrust(container, imageDef);
        updateCodexTrust(container, imageDef);

        HostResourceSetup.removeBuildDevices(incus, buildName, hostResources);
        unmountDnfCache(buildName);

        cleanCaches(buildName);

        tagTemplateMetadata(buildName, canonicalName, imageDef, parentCanonical, hostResources, defs);

        BuildOutput.stepStart("Stopping image...");
        incus.stop(buildName);
        BuildOutput.stepDone();

        buildDone(canonicalName);
    }

    private boolean effectiveVm(ImageDef imageDef) {
        if (type != null) return type == InstanceType.vm;
        return imageDef.isVm();
    }

    private String effectiveType(ImageDef imageDef) {
        if (type != null) return type.name();
        if (imageDef.getType() != null) return imageDef.getType();
        return "container";
    }

    private void buildFromScratch(ImageDef imageDef, Map<String, ImageDef> defs, String buildName) {
        var canonicalName = imageDef.getName();
        var ancestors = ImageDef.ancestors(imageDef, defs);
        var rootDef = ancestors.isEmpty() ? imageDef : ancestors.get(ancestors.size() - 1);
        resolveTrackedBaseImage(rootDef);
        var image = rootDef.getImage();
        var effectiveVm = effectiveVm(imageDef);
        var prebaked = false;

        if (effectiveVm && rootDef.getVmImageUrl() != null) {
            // Prebaked VM disk image available — use it directly
            var vmAlias = rootDef.getImage() + "-vm";
            ensureBaseImage(imageDef);
            downloadAndAliasImage(vmAlias, rootDef.getVmImageUrl(),
                    rootDef.getVmImageSha256(), rootDef.getImageTag());
            image = vmAlias;
            prebaked = true;
        } else {
            ensureBaseImage(imageDef);
            prebaked = imageDef.getImageUrl() != null;

            // Prebaked images are container format (.tar.xz) — VMs need a disk image.
            // Detect container-only images and fall back to the standard remote source.
            if (effectiveVm && !image.contains(":")) {
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
                                    "Cannot build VM from container image '" + rootDef.getImage() + "'. "
                                    + "The image lacks OS/release metadata to derive a VM image source.");
                        }
                    }
                }
            }
        }

        // Create instance — for VMs, expand the disk before first boot so
        // cloud-init's growpart module handles partition + filesystem resize.
        BuildOutput.stepStart("Launching " + image + (effectiveVm ? " (VM)..." : "..."));
        try {
            incus.create(image, buildName, effectiveVm);
        } catch (IncusException e) {
            BuildOutput.stepBreak();
            if (incus.exists(buildName)) {
                var log = incus.getLog(buildName);
                if (log.contains("Exec format error")) {
                    throw new RuntimeException(
                            "The cached image for '" + image + "' has a broken /sbin/init " +
                            "(Exec format error). " +
                            "Delete it with 'incus image list' + 'incus image delete <fingerprint>' " +
                            "and retry the build.", e);
                }
            }
            throw e;
        }
        if (effectiveVm) {
            incus.deviceConfigSet(buildName, "root", "size", ResourceLimits.defaultDiskLimit());
            incus.configSet(buildName, "limits.memory", ResourceLimits.adaptiveMemoryLimit());
        }
        incus.start(buildName);
        waitForReady(buildName);
        BuildOutput.stepDone();

        var container = new Container(incus, buildName);

        BuildOutput.stepStart("Installing MITM proxy CA certificate...");
        var ca = CertificateAuthority.loadOrCreate();
        container.sh(
                "cat > /etc/pki/ca-trust/source/anchors/incus-spawn-mitm.crt << 'CERTEOF'\n" +
                ca.caCertPem() +
                "CERTEOF")
                .assertSuccess("Failed to install MITM CA certificate");
        container.exec("update-ca-trust")
                .assertSuccess("Failed to update CA trust");
        BuildOutput.stepDone();

        // Container-only security tweaks: UID mapping, nesting, capability
        // retention, and setxattr interception. VMs run a full kernel and
        // don't need any of these. Restart activates the new config.
        if (!effectiveVm) {
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
            incus.start(buildName);
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

        waitForNetwork(buildName);

        mountDnfCache(buildName, effectiveVm);

        if (effectiveVm) {
            var installDeps = String.join(" ",
                    dnfCommand("install", "-y", "-q", "cloud-utils-growpart", "e2fsprogs", "xfsprogs"));
            runWithSpinner("Expanding", "VM root filesystem", "Failed to expand VM root filesystem",
                    state -> state.set(0, stepFrom(container.sh(
                            installDeps + " && " +
                            "growpart /dev/sda 2 && " +
                            "if findmnt -n -o FSTYPE / | grep -q xfs; then xfs_growfs /; else resize2fs /dev/sda2; fi"))));
        }

        if (!prebaked) {
            removePackages(container, imageDef);

            runDnf(container, "Updating system packages", "Failed to update system packages",
                    dnfCommand("-y", "upgrade"));

            if (effectiveVm) {
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
            container.exec("chown", "-R", "agentuser:agentuser", "/home/agentuser")
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

        var hostResources = HostResourceSetup.collectEffective(imageDef, defs);
        if (!hostResources.isEmpty()) {
            BuildOutput.step("Applying host resources.");
            HostResourceSetup.applyForBuild(incus, container, hostResources, effectiveVm);
        }

        // Build the full ancestor chain (root first) so that each layer's
        // packages, tools, repos, and skills are applied in order. For root
        // images this list contains only imageDef itself.
        var chain = new ArrayList<ImageDef>();
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            chain.add(ancestors.get(i));
        }
        chain.add(imageDef);

        var allTools = new ArrayList<ResolvedTool>();
        for (var layer : chain) {
            if (chain.size() > 1) {
                BuildOutput.note("Applying layer: " + layer.getName());
            }
            removePackages(container, layer);
            var toolResolution = collectEffectiveTools(layer, defs);
            enablePackageRepos(container, layer, toolResolution.effective(), toolResolution.ancestors(), defs);
            installAllPackages(container, layer, toolResolution.effective(), toolResolution.ancestors(), defs);

            runToolSetup(container, toolResolution.effective());
            allTools.addAll(toolResolution.effective());
            maskServices(container, layer);
            installSkills(container, layer, defs);
            cloneRepos(container, layer, effectiveVm);
            updateClaudeJsonTrust(container, layer);
            updateCodexTrust(container, layer);
        }
        writeEnvFile(container, imageDef, defs, allTools, canonicalName);
        linkJavaTrustStores(container);

        HostResourceSetup.removeBuildDevices(incus, buildName, hostResources);
        unmountDnfCache(buildName);

        cleanCaches(buildName);

        var parentCanonical = imageDef.isRoot() ? null : imageDef.getParent();
        tagTemplateMetadata(buildName, canonicalName, imageDef, parentCanonical, hostResources, defs);

        BuildOutput.stepStart("Stopping image...");
        incus.stop(buildName);
        BuildOutput.stepDone();

        buildDone(canonicalName);
    }

    /**
     * When the root base image is unpinned, resolve the newest published
     * release and swap its tag + checksums into the definition, so a plain
     * build always tracks the latest base image. The built-in {@code image_tag}
     * (baked into the binary) is only an offline fallback: any failure to reach
     * or read the release list leaves the definition untouched and the build
     * proceeds on the built-in version. A pin ({@code pinned: true}) opts out.
     */
    private void resolveTrackedBaseImage(ImageDef rootDef) {
        if (rootDef.isPinned()) return;
        var releases = BaseImageReleases.fromImageUrl(rootDef.getImageUrl());
        if (releases == null) return; // not a releases-tracked base image
        if (!resolvedRoots.add(rootDef.getName())) return; // already resolved this build

        var builtinTag = rootDef.getImageTag();
        var fallback = "; using built-in base image (" + builtinTag + ").";
        try {
            var available = releases.fetchReleases();
            if (available.isEmpty()) return;
            var latest = available.get(0);
            if (latest.tag().equals(builtinTag)) return; // already newest

            var checksums = releases.fetchChecksums(latest);
            if (checksums == null || checksums.container().isEmpty()) {
                BuildOutput.note("Could not fetch checksums for " + latest.tag() + fallback);
                return;
            }
            rootDef.setImageTag(latest.tag());
            rootDef.setImageSha256(checksums.container());
            if (rootDef.getVmImageUrl() != null && !checksums.vm().isEmpty()) {
                rootDef.setVmImageSha256(checksums.vm());
            }
            BuildOutput.step("Tracking latest base image: "
                    + (builtinTag != null ? builtinTag + " -> " : "") + latest.tag() + ".");
        } catch (IOException e) {
            BuildOutput.note("Could not reach base-image release list (" + e.getMessage() + ")" + fallback);
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

    private void ensureBaseImage(ImageDef imageDef) {
        checkPinnedWarning(imageDef);
        downloadAndAliasImage(imageDef.getImage(), imageDef.getImageUrl(),
                imageDef.getImageSha256(), imageDef.getImageTag());
    }

    private void downloadAndAliasImage(String localAlias, String imageUrl,
            Map<String, String> sha256Map, String tag) {
        if (imageUrl == null || imageUrl.isBlank()) return;
        if (localAlias.contains(":")) return;

        var arch = normalizeHostArch();
        String expectedSha256 = null;
        if (sha256Map != null) {
            expectedSha256 = sha256Map.get(arch);
        }

        var existingFingerprint = incus.imageAliasTarget(localAlias);
        if (existingFingerprint != null) {
            var installedTag = incus.getImageProperty(existingFingerprint, "incus-spawn.tag");
            if (tag != null && tag.equals(installedTag)) {
                BuildOutput.step("Base image '" + localAlias + "' is up to date (" + tag + ").");
                return;
            }
            BuildOutput.step("Base image '" + localAlias + "' is outdated"
                    + (installedTag != null ? " (" + installedTag + " -> " + tag + ")" : "")
                    + ", replacing...");
            incus.deleteImageAlias(localAlias);
            incus.deleteImage(existingFingerprint);
        }
        var resolvedUrl = imageUrl.replace("{arch}", arch);
        if (tag != null) {
            resolvedUrl = resolvedUrl.replace("{tag}", tag);
        }

        BuildOutput.stepStart("Downloading base image...");

        try {
            var cache = new DownloadCache();
            var cached = cache.download(resolvedUrl, expectedSha256);

            var fingerprint = incus.importImage(cached);

            if (tag != null) {
                incus.setImageProperty(fingerprint, "incus-spawn.tag", tag);
            }
            incus.createImageAlias(localAlias, fingerprint);

            BuildOutput.stepDone();
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to download base image from " + resolvedUrl + ": " + e.getMessage(), e);
        }
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
                // Branches replace this with a static config at creation time.
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

    /**
     * Resolve all tools referenced by the image definition, including
     * transitive dependencies declared via {@code requires}.
     */
    record ResolvedTool(
        String name,
        ToolSetup setup,
        Map<String, String> parameters,
        boolean reconfigureOnly
    ) {
        ResolvedTool(String name, ToolSetup setup, Map<String, String> parameters) {
            this(name, setup, parameters, false);
        }
    }

    record ToolResolution(
        List<ResolvedTool> effective,
        List<ResolvedTool> ancestors
    ) {}

    private List<ResolvedTool> resolveTools(ImageDef imageDef) {
        return resolveTools(imageDef, toolDefLoader, toolSetups, false);
    }

    static List<ResolvedTool> resolveTools(ImageDef imageDef, ToolDefLoader toolDefLoader, boolean quiet) {
        return resolveTools(imageDef, toolDefLoader, List.of(), quiet);
    }

    static List<ResolvedTool> resolveTools(ImageDef imageDef, ToolDefLoader toolDefLoader,
                                                      Iterable<ToolSetup> cdiTools, boolean quiet) {
        var explicit = new LinkedHashSet<String>();
        for (var toolRef : imageDef.getTools()) {
            explicit.add(toolRef.getName());
        }
        var resolved = new LinkedHashMap<String, ResolvedTool>();

        for (var toolRef : imageDef.getTools()) {
            resolveWithDeps(toolRef.getName(), toolRef.getParams(), resolved,
                new LinkedHashSet<>(), explicit, toolDefLoader, cdiTools, quiet);
        }
        return new ArrayList<>(resolved.values());
    }

    private void resolveWithDeps(String name, Map<String, String> params,
                                  LinkedHashMap<String, ResolvedTool> resolved,
                                  LinkedHashSet<String> visiting, Set<String> explicit) {
        resolveWithDeps(name, params, resolved, visiting, explicit, toolDefLoader, toolSetups, false);
    }

    private static void resolveWithDeps(String name, Map<String, String> params,
                                  LinkedHashMap<String, ResolvedTool> resolved,
                                  LinkedHashSet<String> visiting, Set<String> explicit,
                                  ToolDefLoader toolDefLoader, Iterable<ToolSetup> cdiTools, boolean quiet) {
        if (!visiting.add(name)) {
            if (!quiet) {
                System.err.println("Warning: dependency cycle detected: " +
                        String.join(" -> ", visiting) + " -> " + name + ", skipping.");
            }
            return;
        }
        var tool = findTool(name, toolDefLoader, cdiTools);
        if (tool == null) {
            if (!quiet) {
                var ungated = findToolUngated(name, toolDefLoader, cdiTools);
                if (ungated != null) {
                    System.err.println("Warning: tool '" + name + "' requires feature '"
                            + ungated.feature() + "' — add it to the features list in config.yaml to enable.");
                } else {
                    System.err.println("Warning: unknown tool '" + name + "', skipping.");
                }
            }
            visiting.remove(name);
            return;
        }

        // Resolve parameters and validate
        Map<String, String> resolvedParams = params != null ? params : Map.of();
        var parameterDefs = tool.parameters();
        if (!parameterDefs.isEmpty()) {
            var validation = dev.incusspawn.tool.ParameterResolver.resolve(
                parameterDefs, resolvedParams);
            if (validation.hasErrors()) {
                throw new IllegalArgumentException(
                    "Error in tool '" + name + "' parameters:\n" +
                    String.join("\n", validation.errors().stream().map(e -> "  " + e).toList())
                );
            }
            resolvedParams = validation.resolvedValues();
        } else if (!resolvedParams.isEmpty()) {
            throw new IllegalArgumentException(
                "Tool '" + name + "' does not accept parameters, but received: " + resolvedParams.keySet()
            );
        }

        // Check if tool already resolved - if parameters differ (after resolution), that's an error
        if (resolved.containsKey(name)) {
            var existing = resolved.get(name);
            if (!existing.parameters().equals(resolvedParams)) {
                throw new IllegalArgumentException(
                    "Tool '" + name + "' specified multiple times with different parameters:\n" +
                    "  First:  " + existing.parameters() + "\n" +
                    "  Second: " + resolvedParams
                );
            }
            visiting.remove(name);
            return;
        }

        // Recursively resolve dependencies with their parameters
        if (tool instanceof dev.incusspawn.tool.YamlToolSetup yts) {
            for (var depRef : yts.toolDef().getRequires()) {
                if (!quiet && !explicit.contains(depRef.getName())) {
                    BuildOutput.note("Auto-adding dependency: " + depRef.getName() + " (required by " + name + ")");
                }
                resolveWithDeps(depRef.getName(), depRef.getParams(), resolved, visiting, explicit, toolDefLoader, cdiTools, quiet);
            }
        } else {
            for (var dep : tool.requires()) {
                if (!quiet && !explicit.contains(dep)) {
                    BuildOutput.note("Auto-adding dependency: " + dep + " (required by " + name + ")");
                }
                resolveWithDeps(dep, Map.of(), resolved, visiting, explicit, toolDefLoader, cdiTools, quiet);
            }
        }

        resolved.put(name, new ResolvedTool(name, tool, resolvedParams));
        visiting.remove(name);
    }

    private void removePackages(Container container, ImageDef imageDef) {
        var pkgs = imageDef.getRemovePackages();
        if (pkgs.isEmpty()) return;
        BuildOutput.step("Removing unnecessary packages...");
        container.sh(
                "dnf remove -y --setopt=clean_requirements_on_remove=True " +
                String.join(" ", pkgs) + " 2>/dev/null; true");
    }

    private void maskServices(Container container, ImageDef imageDef) {
        var services = imageDef.getMaskServices();
        if (services.isEmpty()) return;
        BuildOutput.step("Masking unnecessary services...");
        container.sh(
                "systemctl mask " + String.join(" ", services) + " 2>/dev/null; true");
    }

    /**
     * Collect all packages from the image definition and its tools,
     * subtract those already installed by ancestor images, and install
     * only the remaining packages. Accepts pre-resolved ancestor tools
     * to avoid redundant resolution.
     */
    private void installAllPackages(Container container, ImageDef imageDef,
                                    List<ResolvedTool> tools,
                                    List<ResolvedTool> ancestorTools,
                                    Map<String, ImageDef> defs) {
        var allPackages = new LinkedHashSet<>(imageDef.getPackages());
        for (var tool : tools) {
            allPackages.addAll(tool.setup().packages());
        }
        if (allPackages.isEmpty()) return;

        // Collect packages already installed by ancestor images
        var ancestorPackages = new LinkedHashSet<String>();
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            ancestorPackages.addAll(ancestor.getPackages());
        }
        for (var tool : ancestorTools) {
            ancestorPackages.addAll(tool.setup().packages());
        }

        var totalCount = allPackages.size();
        allPackages.removeAll(ancestorPackages);

        if (allPackages.isEmpty()) {
            BuildOutput.step("All " + totalCount + " packages already installed.");
            return;
        }

        var alreadyInstalled = totalCount - allPackages.size();
        var pkgHeader = "Installing " + allPackages.size() + " packages"
                + (alreadyInstalled > 0 ? " (" + alreadyInstalled + " already installed)" : "")
                + ":";
        BuildOutput.stepWithList(pkgHeader, allPackages);
        var rest = new ArrayList<String>(List.of("install", "-y"));
        rest.addAll(allPackages);
        // Label carries no count: the println above states the requested packages, while
        // dnf's own N/M in the spinner detail counts the fully-resolved transaction
        // (requested packages + their dependencies, one step per action phase), so a count
        // here would look like it should match dnf's much larger N when it never will.
        runDnf(container, "Installing packages and dependencies", "Failed to install packages",
                dnfCommand(rest.toArray(String[]::new)));
    }

    /**
     * Enable package repositories (e.g. COPR) from the image and its tools,
     * skipping any already enabled by ancestor images. Must be called before
     * {@link #installAllPackages}.
     */
    private record RepoKey(String type, String name) {
        RepoKey(ImageDef.PackageRepo repo) { this(repo.getType(), repo.getName()); }
    }

    private void enablePackageRepos(Container container, ImageDef imageDef,
                                    List<ResolvedTool> tools,
                                    List<ResolvedTool> ancestorTools,
                                    Map<String, ImageDef> defs) {
        var allRepos = new LinkedHashSet<RepoKey>();
        for (var repo : imageDef.getPackageRepos()) {
            allRepos.add(new RepoKey(repo));
        }
        for (var tool : tools) {
            for (var repo : tool.setup().packageRepos()) {
                allRepos.add(new RepoKey(repo));
            }
        }
        if (allRepos.isEmpty()) return;

        var ancestorRepos = new LinkedHashSet<RepoKey>();
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            for (var repo : ancestor.getPackageRepos()) {
                ancestorRepos.add(new RepoKey(repo));
            }
        }
        for (var tool : ancestorTools) {
            for (var repo : tool.setup().packageRepos()) {
                ancestorRepos.add(new RepoKey(repo));
            }
        }

        allRepos.removeAll(ancestorRepos);
        if (allRepos.isEmpty()) return;

        for (var key : allRepos) {
            switch (key.type()) {
                case "copr" -> runWithSpinner("Enabling", "COPR repo " + key.name(),
                        "Failed to enable COPR repo " + key.name(),
                        state -> state.set(0, stepFrom(
                                container.exec("dnf", "copr", "enable", "-y", key.name()))));
                default -> System.err.println("Warning: unknown package_repos type '" + key.type()
                        + "' for '" + key.name() + "', skipping.");
            }
        }
    }

    /**
     * Run the non-package setup steps for each tool (scripts, files, env, verify).
     */
    private void runToolSetup(Container container, List<ResolvedTool> tools) {
        var installable = tools.stream().filter(t -> !t.reconfigureOnly()).toList();
        if (!installable.isEmpty()) {
            var names = installable.stream().map(ResolvedTool::name).toList();
            BuildOutput.stepWithList("Setting up " + names.size() + " tool"
                    + (names.size() == 1 ? "" : "s") + ":", names);
        }

        for (var resolved : tools) {
            if (resolved.reconfigureOnly()) {
                resolved.setup().reconfigure(container, resolved.parameters());
            } else {
                resolved.setup().install(container, resolved.parameters());
            }
        }

        if (!installable.isEmpty()) {
            BuildOutput.note(installable.size() + " tool"
                    + (installable.size() == 1 ? "" : "s") + " ready.");
        }
    }

    static void syncInheritedGcloudStub(Container container, ToolResolution toolResolution) {
        var effectiveNames = toolResolution.effective().stream()
                .map(ResolvedTool::name).collect(java.util.stream.Collectors.toSet());
        for (var tool : toolResolution.ancestors()) {
            if (!effectiveNames.contains(tool.name()) && tool.setup() instanceof ClaudeSetup claudeSetup) {
                claudeSetup.syncGcloudStub(container, SpawnConfig.load().getClaude());
            }
        }
    }

    private void writeEnvFile(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                               List<ResolvedTool> allTools, String canonicalName) {
        var resolver = new EnvResolver();

        resolver.add(EnvEntry.raw("export ISX_CONTAINER=\"${HOSTNAME}\""), "built-in");
        resolver.add(EnvEntry.set("ISX_TEMPLATE", canonicalName), "built-in");
        var ancestors = ImageDef.ancestors(imageDef, defs);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            var ancestor = ancestors.get(i);
            resolver.addAll(ancestor.getEnv(), "template " + ancestor.getName());
        }
        resolver.addAll(imageDef.getEnv(), "template " + imageDef.getName());

        for (var resolved : allTools) {
            var entries = resolved.setup().envEntries(resolved.parameters());
            resolver.addAll(entries, "tool " + resolved.name());
        }

        var script = resolver.resolve();
        container.writeFile("/etc/profile.d/isx-env.sh", script);
    }

    private static void linkJavaTrustStores(Container container) {
        container.sh(
                "find /usr/lib/jvm /opt -name cacerts -path '*/lib/security/cacerts' 2>/dev/null | while IFS= read -r f; do " +
                "t=$(readlink -f \"$f\" 2>/dev/null); " +
                "if [ \"$t\" != /etc/pki/java/cacerts ]; then " +
                "ln -sf /etc/pki/java/cacerts \"$f\"; " +
                "fi; done");
    }

    private static ToolSetup findTool(String name, ToolDefLoader toolDefLoader, Iterable<ToolSetup> cdiTools) {
        var tool = toolDefLoader.find(name);
        if (tool != null) return isFeatureGated(tool) ? null : tool;
        for (var t : cdiTools) {
            if (t.name().equals(name)) return isFeatureGated(t) ? null : t;
        }
        return null;
    }

    static boolean isFeatureGated(ToolSetup tool) {
        var feature = tool.feature();
        return feature != null && !SpawnConfig.load().isFeatureEnabled(feature);
    }

    static boolean isFeatureGated(ToolSetup tool, SpawnConfig config) {
        var feature = tool.feature();
        return feature != null && !config.isFeatureEnabled(feature);
    }

    private static ToolSetup findToolUngated(String name, ToolDefLoader toolDefLoader, Iterable<ToolSetup> cdiTools) {
        var tool = toolDefLoader.find(name);
        if (tool != null && tool.feature() != null) return tool;
        for (var t : cdiTools) {
            if (t.name().equals(name) && t.feature() != null) return t;
        }
        return null;
    }

    /**
     * Shared dnf options: keep the download cache, cap metadata refresh, skip
     * docs, and parallelize downloads. {@code max_parallel_downloads} is bounded
     * to dnf's practical ceiling (20) and scaled to the host's logical cores so
     * beefier machines fetch more concurrently — the download phase is the only
     * parallelizable part; the rpm transaction itself is serial.
     */
    private static final String[] DNF_BASE_OPTS = {
            "--setopt=keepcache=true",
            "--setopt=metadata_expire=3600",
            "--setopt=tsflags=nodocs",
            "--setopt=max_parallel_downloads=" + Math.min(20, Math.max(8, CpuInfo.logicalCores())),
    };

    /** Build a full {@code dnf} command line: {@code dnf <shared opts> <rest>}. */
    private static String[] dnfCommand(String... rest) {
        var cmd = new ArrayList<String>();
        cmd.add("dnf");
        cmd.addAll(List.of(DNF_BASE_OPTS));
        cmd.addAll(List.of(rest));
        return cmd.toArray(String[]::new);
    }

    /** dnf5's non-TTY per-step progress line, e.g.
     *  {@code [3/6] Installing setup-0:2.15.0-28.fc  100% | 26 MiB/s | ...} or a
     *  download line {@code [1/2] filesystem-0:3.18-52.fc44.aarch64  100% | ...}.
     *  Group 1/2 are the counters, group 3 is the action+package (dnf truncates it
     *  to its assumed 80-col width). */
    private static final Pattern DNF_STEP = Pattern.compile("^\\[(\\d+)/(\\d+)]\\s+(.*)$");

    /**
     * Run a dnf command behind an animated one-line spinner. dnf's verbose output
     * is streamed through a parser (not echoed to the terminal) so the spinner can
     * show live "N/M — current package" feedback from dnf's own progress lines,
     * keeping isx's warnings visible instead of buried in a flood. On failure it
     * clears metadata and retries once with {@code --refresh}; if that also fails
     * the full captured output is printed and {@code failureMessage} thrown.
     *
     * @param label the full phrase for the spinner line (e.g. "Installing base packages")
     */
    private void runDnf(Container container, String label, String failureMessage, String... args) {
        var state = new AtomicReferenceArray<StepProgress>(1);
        state.set(0, StepProgress.running("", "starting"));
        TerminalProgress.run(1, 1,
                idx -> dnfWork(container, args, state),
                (idx, frame) -> formatDnfLine(label, state.get(0), frame),
                idx -> plainDnfLine(label, state.get(0)),
                System.out::println);
        finishSpinner(label, failureMessage, state);
    }

    /** The install/retry work for {@link #runDnf}, recording progress in {@code state[0]}.
     *  A thrown exception (e.g. an Incus transport error) is recorded as a failed state
     *  rather than propagated, since {@code TerminalProgress} swallows task exceptions —
     *  an uncaught one would leave the step stuck RUNNING and lose the real cause. */
    private void dnfWork(Container container, String[] args, AtomicReferenceArray<StepProgress> state) {
        try {
            var log = new StringBuilder();
            int code = container.execLines(line -> onDnfLine(line, log, state), args);
            if (code == 0) {
                state.set(0, StepProgress.done(null));
                return;
            }
            // Metadata may be stale — clear it and retry once with --refresh.
            state.set(0, StepProgress.running("", "retrying with --refresh"));
            container.sh("dnf clean metadata");
            var retryArgs = new ArrayList<>(List.of(args));
            retryArgs.add(1, "--refresh");
            var retryLog = new StringBuilder();
            int retryCode = container.execLines(line -> onDnfLine(line, retryLog, state),
                    retryArgs.toArray(String[]::new));
            if (retryCode == 0) {
                state.set(0, StepProgress.done("succeeded after refresh"));
            } else {
                var out = retryLog.toString();
                state.set(0, StepProgress.failed(lastNonEmptyLine(out), out));
            }
        } catch (RuntimeException e) {
            state.set(0, StepProgress.failed(e.getMessage(), stackTrace(e)));
        }
    }

    /** Accumulate a streamed dnf line into {@code log} and, if it's a progress line,
     *  update the spinner's live detail to "N/M — package". */
    static void onDnfLine(String line, StringBuilder log, AtomicReferenceArray<StepProgress> state) {
        synchronized (log) { log.append(line).append('\n'); }
        var m = DNF_STEP.matcher(line.strip());
        if (!m.matches()) return;
        // Drop the trailing "100% | rate | size | time" progress bar. dnf truncates
        // the action/package to a fixed column, so there may be only a single space
        // before the percentage — key off the "<n>% |" shape, not the spacing.
        var body = m.group(3).replaceAll("\\s+\\d+%\\s*\\|.*$", "").strip();
        if (body.isEmpty() || body.equals("Total")) return; // skip the download subtotal line
        state.set(0, StepProgress.running("", m.group(1) + "/" + m.group(2) + "  " + shortenNevra(body)));
    }

    /** dnf's non-TTY column truncates the version/arch tail off each NEVRA anyway, and the
     *  version is just noise in a live progress line, so reduce {@code name-epoch:ver-rel.arch}
     *  to its bare package name (everything before the {@code -<epoch>:} marker). Action-only
     *  lines ("Verify package files") and names truncated before the epoch are left untouched. */
    static String shortenNevra(String body) {
        return body.replaceFirst("-\\d+:.*$", "");
    }

    /** Render the dnf spinner line: {@code     ⠋ <label>  <dim live detail>}. */
    static String formatDnfLine(String label, StepProgress p, int frame) {
        var sb = new StringBuilder(BuildOutput.STEP_INDENT);
        switch (p.state()) {
            case RUNNING -> sb.append(TerminalProgress.SPINNER[frame % TerminalProgress.SPINNER.length])
                    .append(' ').append(label);
            case DONE    -> sb.append(label).append(" done.");
            case FAILED  -> sb.append("\033[31m✗\033[0m ").append(label);
        }
        if (p.state() == StepState.RUNNING && p.detail() != null && !p.detail().isEmpty()) {
            sb.append("  \033[2m").append(p.detail()).append("\033[0m");
        }
        if (p.state() == StepState.DONE && p.note() != null && !p.note().isEmpty()) {
            sb.append(" \033[2m(").append(p.note()).append(")\033[0m");
        }
        if (p.state() == StepState.FAILED && p.detail() != null && !p.detail().isEmpty()) {
            sb.append("  \033[31m").append(p.detail()).append("\033[0m");
        }
        return sb.toString();
    }

    /** Non-ANSI fallback line for a dnf step (emitted once, on completion). */
    private static String plainDnfLine(String label, StepProgress p) {
        if (p.state() == StepState.DONE) {
            var line = BuildOutput.STEP_INDENT + label + " done.";
            if (p.note() != null && !p.note().isEmpty()) line += " (" + p.note() + ")";
            return line;
        }
        var msg = BuildOutput.STEP_INDENT + "Warning: " + label + " failed";
        if (p.detail() != null && !p.detail().isEmpty()) msg += ": " + p.detail();
        return msg;
    }

    /**
     * Run a single operation behind an animated one-line spinner (mirroring the
     * clone/prime display) instead of streaming its output. {@code work} performs
     * the operation with captured exec and records the terminal {@link StepProgress}
     * in {@code state[0]}; it may set an intermediate {@code running(...)} to advance
     * the verb mid-flight. On a non-DONE result the captured log is printed to
     * stderr and {@code failureMessage} thrown.
     */
    private void runWithSpinner(String activity, String label, String failureMessage,
                                Consumer<AtomicReferenceArray<StepProgress>> work) {
        var state = new AtomicReferenceArray<StepProgress>(1);
        state.set(0, StepProgress.running(activity));
        TerminalProgress.run(1, 1,
                idx -> runSpinnerWork(work, state),
                (idx, frame) -> formatStepLine(label, null, state.get(0), frame, "Done"),
                idx -> plainStepLine(label, state.get(0), "Done", activity.toLowerCase()),
                System.out::println);
        finishSpinner(label, failureMessage, state);
    }

    /** Run a {@link #runWithSpinner} task, converting a thrown exception into a recorded
     *  failed state. {@code TerminalProgress} swallows task exceptions, so without this the
     *  step would stay RUNNING and {@code finishSpinner} would throw the generic failure
     *  message with no captured cause. */
    static void runSpinnerWork(Consumer<AtomicReferenceArray<StepProgress>> work,
                                       AtomicReferenceArray<StepProgress> state) {
        try {
            work.accept(state);
        } catch (RuntimeException e) {
            state.set(0, StepProgress.failed(e.getMessage(), stackTrace(e)));
        }
    }

    /** Render a throwable's stack trace to a string for a failed step's captured log. */
    private static String stackTrace(Throwable t) {
        var sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** After a one-task spinner completes, surface a non-DONE result: print the full
     *  captured log (if any) to stderr — after the animated line, so it doesn't
     *  interleave with the live display — and throw {@code failureMessage}. */
    private static void finishSpinner(String label, String failureMessage,
                                      AtomicReferenceArray<StepProgress> state) {
        var progress = state.get(0);
        if (progress != null && progress.state() == StepState.DONE) return;

        if (progress != null && progress.log() != null && !progress.log().isBlank()) {
            System.err.println("\033[1m─── output: " + label + " ───\033[0m");
            System.err.println(progress.log().strip());
            System.err.println("\033[1m─── end output: " + label + " ───\033[0m");
        }
        var detail = progress != null && progress.detail() != null && !progress.detail().isEmpty()
                ? ": " + progress.detail() : "";
        throw new IncusException(failureMessage + detail);
    }

    /** Turn a captured exec result into a terminal {@link StepProgress}. */
    private static StepProgress stepFrom(IncusClient.ExecResult result) {
        if (result.success()) return StepProgress.done(null);
        var combined = combinedOutput(result);
        return StepProgress.failed(lastNonEmptyLine(combined), combined);
    }

    private void cleanCaches(String container) {
        BuildOutput.stepStart("Cleaning up caches...");
        incus.shellExec(container, "sh", "-c",
                "dnf clean all; rm -rf /var/cache/libdnf5 /tmp/* /var/tmp/*; true");
        BuildOutput.stepDone();
    }

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

    private void waitForReady(String container) {
        if (!incus.pollUntilReady(container, 30, "echo", "ready")) {
            throw new RuntimeException(
                    "Container " + container + " failed to become ready after 30 seconds");
        }
    }

    private void stampBuildVersion(String container, dev.incusspawn.config.ImageDef imageDef,
                                    Map<String, ImageDef> defs) {
        var info = BuildInfo.instance();
        incus.configSet(container, Metadata.BUILD_VERSION, info.version());
        incus.configSet(container, Metadata.BUILD_SHA, info.gitSha());
        incus.configSet(container, Metadata.CA_FINGERPRINT, CertificateAuthority.currentCaFingerprint());
        incus.configSet(container, Metadata.DEFINITION_SHA,
                imageDef.contentFingerprint(computeToolFingerprints(imageDef, toolDefLoader, defs)));
    }

    private static Map<String, String> computeToolFingerprints(
            dev.incusspawn.config.ImageDef imageDef,
            ToolDefLoader toolDefLoader,
            Map<String, ImageDef> defs) {
        var rawFps = new TreeMap<String, String>();
        var depMap = new TreeMap<String, List<String>>();
        // Always quiet: this method only fingerprints YAML tools and doesn't have
        // CDI tools, so non-YAML tools would produce spurious "unknown tool" warnings.
        for (var resolvedTool : resolveTools(imageDef, toolDefLoader, true)) {
            if (resolvedTool.setup() instanceof YamlToolSetup yts) {
                rawFps.put(yts.toolDef().getName(), yts.toolDef().contentFingerprint());
                var depNames = yts.toolDef().getRequires().stream()
                    .map(dev.incusspawn.tool.ToolDef.ToolRef::getName)
                    .toList();
                depMap.put(yts.toolDef().getName(), depNames);
            }
        }
        return dev.incusspawn.tool.ToolDef.compositeFingerprints(rawFps, depMap);
    }

    private BuildSource collectBuildSource(ImageDef imageDef, Map<String, ImageDef> defs) {
        var definitions = new LinkedHashMap<String, ImageDef>();
        var tools = new LinkedHashMap<String, dev.incusspawn.tool.ToolDef>();
        var toolInstances = new LinkedHashMap<String, BuildSource.ToolInstance>();
        var sources = new LinkedHashMap<String, String>();

        var visited = new HashSet<String>();
        var current = imageDef;
        while (current != null) {
            definitions.put(current.getName(), current);
            sources.put(current.getName(), current.getSource());
            collectToolDefs(current, tools, visited);
            collectToolInstances(current, toolInstances);
            if (current.isRoot()) break;
            current = defs.get(current.getParent());
        }

        return new BuildSource(definitions, tools, toolInstances, sources);
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

    private void tagTemplateMetadata(String buildName, String canonicalName, ImageDef imageDef,
                                    String parentCanonicalName,
                                    List<ImageDef.HostResource> hostResources,
                                    Map<String, ImageDef> defs) {
        incus.configSet(buildName, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(buildName, Metadata.PROFILE, canonicalName);
        incus.configSet(buildName, Metadata.INSTANCE_MODE, effectiveType(imageDef));
        if (parentCanonicalName != null) {
            incus.configSet(buildName, Metadata.PARENT, parentCanonicalName);
        }
        incus.configSet(buildName, Metadata.CREATED, Metadata.today());
        stampBuildVersion(buildName, imageDef, defs);
        if (!hostResources.isEmpty()) {
            incus.configSet(buildName, Metadata.HOST_RESOURCES,
                    HostResourceSetup.serialize(hostResources));
        }
        incus.configSet(buildName, Metadata.BUILD_SOURCE,
                collectBuildSource(imageDef, defs).toJson());

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

    static String resolveEffectiveWorkdir(ImageDef imageDef, Map<String, ImageDef> defs) {
        if (imageDef.getWorkdir() != null && !imageDef.getWorkdir().isBlank()) {
            return expandHome(imageDef.getWorkdir());
        }
        var current = imageDef;
        while (current != null) {
            if (!current.getRepos().isEmpty()) {
                return expandHome(current.getRepos().get(0).getPath());
            }
            if (current.isRoot() || current.getParent() == null) break;
            current = defs.get(current.getParent());
        }
        return null;
    }

    private static void validateDefaultAction(String ref, ImageDef imageDef, Map<String, ImageDef> defs) {
        var toolName = ref;
        int colon = ref.indexOf(':');
        if (colon >= 0) toolName = ref.substring(0, colon);

        var allTools = new java.util.LinkedHashSet<String>();
        var current = imageDef;
        while (current != null) {
            for (var toolRef : current.getTools()) {
                allTools.add(toolRef.getName());
            }
            if (current.isRoot() || current.getParent() == null) break;
            current = defs.get(current.getParent());
        }
        if (!allTools.contains(toolName)) {
            System.err.println("Warning: default-action '" + ref
                    + "' references tool '" + toolName
                    + "' which is not in the tools list. "
                    + "Add it to tools: [" + toolName + "] or remove default-action.");
        }
    }

    static String resolveEffectiveDefaultAction(ImageDef imageDef, Map<String, ImageDef> defs) {
        var current = imageDef;
        while (current != null) {
            if (current.getDefaultAction() != null) {
                return current.getDefaultAction();
            }
            if (current.isRoot() || current.getParent() == null) break;
            current = defs.get(current.getParent());
        }
        return null;
    }

    enum InstanceType {
        container,
        vm,
        kvm
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
     * Mount a shared DNF cache volume into the container. This shares
     * metadata and downloaded packages across builds, avoiding redundant
     * downloads when building a parent→child image chain.
     */
    static final String DNF_CACHE_VOLUME = "dnf-cache";

    private void mountDnfCache(String container, boolean isVm) {
        if (isVm) return;
        try {
            var pool = incus.findCowPool();
            if (pool == null) return;
            incus.ensureStorageVolume(pool, DNF_CACHE_VOLUME);
            incus.deviceAdd(container, DNF_CACHE_DEVICE, "disk",
                    "pool=" + pool,
                    "source=" + DNF_CACHE_VOLUME,
                    "path=/var/cache/libdnf5");
        } catch (Exception e) {
            System.err.println("Warning: could not mount DNF cache (builds will be slower): " + e.getMessage());
        }
    }

    private void unmountDnfCache(String container) {
        // Safe even if mountDnfCache was skipped: deviceRemove is a read-modify-write
        // that filters the device map — a missing device is a no-op, not an error.
        incus.deviceRemove(container, DNF_CACHE_DEVICE);
    }

    /** Agent home directory inside the container, shared across agents. */
    private static final String AGENTS_DIR = "/home/agentuser/.agents";

    /** Global skills directory inside the container, shared across agents. */
    private static final String SKILLS_DIR = AGENTS_DIR + "/skills";

    /**
     * Install agent skills declared in the image definition.
     * Fetches SKILL.md files on the host and writes them directly into the container.
     * Deduplicates against skills already declared by ancestor images.
     */
    void installSkills(Container container, ImageDef imageDef, Map<String, ImageDef> defs) {
        var skillSources = collectEffectiveSkills(imageDef, defs);
        if (skillSources.isEmpty()) return;

        var repo = imageDef.getSkills().getRepo();

        var resolvedNames = new ArrayList<String>(skillSources.size());
        for (var entry : skillSources) {
            try {
                resolvedNames.add(resolveSkillSource(entry, repo));
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                System.err.println("Use the fully qualified form 'owner/repo@skill-name', or set 'skills.repo' in your image definition.");
                throw new BuildFailedException();
            }
        }

        BuildOutput.stepWithList("Installing " + resolvedNames.size() + " skill"
                + (resolvedNames.size() == 1 ? "" : "s") + ":", resolvedNames);

        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        var cache = new dev.incusspawn.tool.SkillsCache();

        container.exec("mkdir", "-p", SKILLS_DIR);

        for (var resolved : resolvedNames) {
            BuildOutput.stepStart("Installing skill: " + resolved + "...");
            try {
                var skills = fetchSkills(resolved, http, cache);
                for (var skill : skills) {
                    var skillDir = SKILLS_DIR + "/" + skill.name();
                    container.exec("mkdir", "-p", skillDir);
                    container.writeFile(skillDir + "/SKILL.md", skill.content());
                }
                BuildOutput.stepDone();
            } catch (IOException | InterruptedException e) {
                BuildOutput.stepBreak();
                System.err.println("Error: Failed to fetch skill '" + resolved + "': " + e.getMessage());
                throw new BuildFailedException();
            }
        }
        // Fix ownership so agentuser owns the agents / skills directories
        container.exec("chown", "-R", "agentuser:agentuser", AGENTS_DIR);
        // Ensure .claude/skills points to the shared location if Claude Code is installed
        // (handles inherited-claude case where ClaudeSetup.linkSkillsDir didn't run)
        container.sh("[ ! -d /home/agentuser/.claude ] || [ -L /home/agentuser/.claude/skills ]"
                + " || { rm -rf /home/agentuser/.claude/skills"
                + " && ln -sfn " + SKILLS_DIR + " /home/agentuser/.claude/skills; }");
    }

    /** A fetched skill ready to be written into the container. */
    record SkillFile(String name, String content) {}

    /**
     * Fetch one or more SKILL.md files for the given resolved source.
     * GitHub skills are cached on the host at {@code ~/.cache/incus-spawn/skills/}.
     * Supports:
     * <ul>
     *   <li>{@code owner/repo@skill-name} — single skill from a GitHub repo</li>
     *   <li>{@code owner/repo} — all skills from a GitHub repo (via Trees API)</li>
     *   <li>{@code https://github.com/owner/repo} — same as owner/repo</li>
     *   <li>{@code ./local/path} or {@code /absolute/path} — local directory</li>
     * </ul>
     */
    static List<SkillFile> fetchSkills(String source, HttpClient http,
            dev.incusspawn.tool.SkillsCache cache)
            throws IOException, InterruptedException {
        // Local path
        if (source.startsWith("./") || source.startsWith("/")) {
            return fetchLocalSkills(Path.of(source));
        }

        // Normalise GitHub URL to owner/repo[@skill]
        var normalised = source;
        if (normalised.startsWith("https://github.com/")) {
            normalised = normalised.substring("https://github.com/".length()).replaceAll("\\.git$", "");
        }

        // owner/repo@skill-name
        var atIdx = normalised.indexOf('@');
        if (atIdx >= 0) {
            var ownerRepo = normalised.substring(0, atIdx);
            var skillName = normalised.substring(atIdx + 1);
            return List.of(new SkillFile(skillName, cache.fetchSkillMd(ownerRepo, skillName, http)));
        }

        // owner/repo — fetch all skills via Trees API
        return fetchAllGitHubSkills(normalised, http, cache);
    }

    private static List<SkillFile> fetchAllGitHubSkills(String ownerRepo, HttpClient http,
            dev.incusspawn.tool.SkillsCache cache)
            throws IOException, InterruptedException {
        // Use GitHub Trees API to find all SKILL.md files
        for (var branch : List.of("main", "master")) {
            var treeUrl = "https://api.github.com/repos/" + ownerRepo + "/git/trees/"
                    + branch + "?recursive=1";
            var token = Environment.strippedEnv("GITHUB_TOKEN");
            var reqBuilder = HttpRequest.newBuilder(URI.create(treeUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json");
            if (!token.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + token);
            }
            var response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) continue;

            var mapper = new ObjectMapper();
            var tree = mapper.readTree(response.body()).path("tree");
            var skills = new ArrayList<SkillFile>();
            for (var node : tree) {
                var path = node.path("path").asText();
                // Match <skill-name>/SKILL.md at the top level only
                if (path.matches("[^/]+/SKILL\\.md")) {
                    var skillName = path.substring(0, path.indexOf('/'));
                    skills.add(new SkillFile(skillName, cache.fetchSkillMd(ownerRepo, skillName, http)));
                }
            }
            if (!skills.isEmpty()) return skills;
        }
        throw new IOException("No SKILL.md files found in " + ownerRepo);
    }

    private static List<SkillFile> fetchLocalSkills(Path localPath) throws IOException {
        if (!Files.isDirectory(localPath)) {
            throw new IOException("Local skill path is not a directory: " + localPath);
        }
        // If there's a SKILL.md directly in this dir, treat it as a single skill
        var directSkill = localPath.resolve("SKILL.md");
        if (Files.exists(directSkill)) {
            return List.of(new SkillFile(localPath.getFileName().toString(),
                    Files.readString(directSkill)));
        }
        // Otherwise scan subdirectories for SKILL.md files
        var skills = new ArrayList<SkillFile>();
        try (var entries = Files.list(localPath)) {
            for (var entry : entries.toList()) {
                var skillMd = entry.resolve("SKILL.md");
                if (Files.isDirectory(entry) && Files.exists(skillMd)) {
                    skills.add(new SkillFile(entry.getFileName().toString(),
                            Files.readString(skillMd)));
                }
            }
        }
        if (skills.isEmpty()) {
            throw new IOException("No SKILL.md files found in " + localPath);
        }
        return skills;
    }

    /**
     * Collect skills declared in this image, minus any already declared by ancestor images.
     */
    List<String> collectEffectiveSkills(ImageDef imageDef, Map<String, ImageDef> defs) {
        var skills = new LinkedHashSet<>(imageDef.getSkills().getList());
        if (skills.isEmpty()) return List.of();

        var ancestorSkills = new LinkedHashSet<String>();
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            ancestorSkills.addAll(ancestor.getSkills().getList());
        }
        skills.removeAll(ancestorSkills);
        return new ArrayList<>(skills);
    }

    /**
     * Resolve tools for this image, removing any already installed by ancestor images.
     * If an ancestor declares the same tool with different parameters, that's an error
     * (the parent's setup already ran and can't be undone).
     * Returns both the effective tools to install and the resolved ancestor tools.
     */
    ToolResolution collectEffectiveTools(ImageDef imageDef, Map<String, ImageDef> defs) {
        return collectEffectiveTools(imageDef, defs, toolDefLoader, toolSetups);
    }

    static ToolResolution collectEffectiveTools(ImageDef imageDef, Map<String, ImageDef> defs,
                                                 ToolDefLoader toolDefLoader,
                                                 Iterable<ToolSetup> cdiTools) {
        var tools = resolveTools(imageDef, toolDefLoader, cdiTools, false);

        var ancestorToolsMap = new LinkedHashMap<String, ResolvedTool>();
        var ancestorTemplateNames = new LinkedHashMap<String, String>();
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            for (var resolved : resolveTools(ancestor, toolDefLoader, cdiTools, true)) {
                if (ancestorToolsMap.putIfAbsent(resolved.name(), resolved) == null) {
                    ancestorTemplateNames.put(resolved.name(), ancestor.getName());
                }
            }
        }

        var ancestorTools = new ArrayList<>(ancestorToolsMap.values());
        if (tools.isEmpty()) {
            return new ToolResolution(tools, ancestorTools);
        }

        var effective = new ArrayList<ResolvedTool>();
        for (var tool : tools) {
            var ancestorTool = ancestorToolsMap.get(tool.name());
            if (ancestorTool == null) {
                effective.add(tool);
            } else if (!ancestorTool.parameters().equals(tool.parameters())) {
                var paramDefs = tool.setup().parameters();
                var allReconfigurable = true;
                for (var key : tool.parameters().keySet()) {
                    var ancestorValue = ancestorTool.parameters().get(key);
                    var childValue = tool.parameters().get(key);
                    if (!java.util.Objects.equals(ancestorValue, childValue)) {
                        var def = paramDefs.get(key);
                        if (def == null || !def.isReconfigurable()) {
                            allReconfigurable = false;
                            break;
                        }
                    }
                }
                if (allReconfigurable) {
                    for (var key : ancestorTool.parameters().keySet()) {
                        if (!tool.parameters().containsKey(key)) {
                            var def = paramDefs.get(key);
                            if (def == null || !def.isReconfigurable()) {
                                allReconfigurable = false;
                                break;
                            }
                        }
                    }
                }
                if (allReconfigurable) {
                    effective.add(new ResolvedTool(tool.name(), tool.setup(), tool.parameters(), true));
                } else {
                    var ancestorTemplateName = ancestorTemplateNames.get(tool.name());
                    throw new IllegalArgumentException(
                        "Tool '" + tool.name() + "' is already installed by ancestor template '" +
                        ancestorTemplateName + "' with different parameters:\n" +
                        "  Ancestor: " + ancestorTool.parameters() + "\n" +
                        "  Current:  " + tool.parameters()
                    );
                }
            }
        }
        return new ToolResolution(effective, ancestorTools);
    }

    /**
     * Resolve a skill entry to a fully-qualified source string.
     * <ul>
     *   <li>Contains {@code ://} or starts with {@code .} or {@code /} → local/URL, pass through</li>
     *   <li>Contains {@code /} → owner/repo or owner/repo@skill, pass through</li>
     *   <li>Plain name → prepend {@code skillsRepo@}; throws if no skillsRepo set</li>
     * </ul>
     */
    static String resolveSkillSource(String skill, String skillsRepo) {
        if (skill.contains("://") || skill.startsWith(".") || skill.startsWith("/")) {
            return skill;
        }
        if (skill.contains("/")) {
            return skill;
        }
        if (skillsRepo == null || skillsRepo.isBlank()) {
            throw new IllegalArgumentException(
                    "Skill '" + skill + "' is a short name but no skills.repo is defined in the image definition.");
        }
        return skillsRepo + "@" + skill;
    }

    record RepoReference(String deviceName, String containerPath, String skipReason) {
        static RepoReference skipped(String reason) { return new RepoReference(null, null, reason); }
        boolean mounted() { return deviceName != null; }
    }

    enum StepState { RUNNING, DONE, FAILED }

    /** Progress state for one repo's clone→prime pipeline. {@code activity} is the
     *  live verb shown while RUNNING (e.g. "Cloning", then "Priming"); {@code note}
     *  is a dim annotation shown on success; {@code detail} is a concise one-line
     *  error for the inline display; {@code log} is the full captured command output,
     *  printed on failure so the diagnostic isn't reduced to the single inline line. */
    record StepProgress(StepState state, String activity, String note, boolean noteHighlight, String detail, String log) {
        static StepProgress running(String activity) { return new StepProgress(StepState.RUNNING, activity, null, false, null, null); }
        static StepProgress running(String activity, String detail) { return new StepProgress(StepState.RUNNING, activity, null, false, detail, null); }
        static StepProgress done(String note) { return new StepProgress(StepState.DONE, null, note, false, null, null); }
        static StepProgress doneHighlight(String note) { return new StepProgress(StepState.DONE, null, note, true, null, null); }
        static StepProgress failed(String detail, String log) {
            return new StepProgress(StepState.FAILED, null, null, false, detail, log);
        }
    }

    /**
     * Clone git repos declared in the image definition as agentuser.
     *
     * <p>Repos are cloned concurrently (bounded to the host's high-performance
     * core count) with an animated per-repo progress display. Each repo's
     * declared {@code prime} command runs in the same worker as soon as that
     * repo's clone finishes, so priming pipelines with the remaining clones
     * instead of waiting for the whole clone batch to complete. Incus config
     * mutations — mounting/removing the host-reference disk devices — are kept
     * out of the parallel section (serial phases before and after) because
     * concurrent instance-config edits can conflict. Each reference stays mounted
     * across its clone and URL fixup, so removal only happens once all clones
     * are done.
     *
     * <p>When a matching host-side checkout is available (via SpawnConfig
     * host-path/repo-paths), the clone runs locally from the mounted reference
     * ({@code git clone --no-hardlinks}) instead of fetching from the remote.
     * This copies pack files directly — no repack or dissociation needed.  After
     * the local clone, the remote URL is fixed to the real origin and a
     * {@code git fetch} picks up any commits added since the last host refresh.
     */
    void cloneRepos(Container container, ImageDef imageDef, boolean isVm) {
        if (hostRepoRefresh != null) {
            if (!hostRepoRefresh.isDone()) {
                BuildOutput.stepStart("Waiting for host repo refresh");
                hostRepoRefresh.join();
                BuildOutput.stepDone();
            } else {
                hostRepoRefresh.join();
            }
            for (var w : hostRepoRefresh.warnings()) {
                BuildOutput.note(w);
            }
            hostRepoRefresh = null;
        }

        var repos = imageDef.getRepos();
        if (repos.isEmpty()) return;

        BuildOutput.step("Preparing " + repos.size() + (repos.size() == 1 ? " repository:" : " repositories:"));

        var config = SpawnConfig.load();

        // Phase 1 (serial): mount all host-reference disk devices up front.
        var refs = new RepoReference[repos.size()];
        for (int i = 0; i < repos.size(); i++) {
            refs[i] = tryMountReference(container, repos.get(i).getUrl(), config, isVm);
        }

        // Phase 2 (parallel, bounded): clone each repo from its reference (local)
        // or the remote (fallback), restore the fetch refspec, then prime it —
        // all in one worker so priming starts as soon as that repo's clone
        // finishes rather than waiting at a barrier for the whole clone batch.
        var states = new AtomicReferenceArray<StepProgress>(repos.size());
        for (int i = 0; i < repos.size(); i++) {
            states.set(i, StepProgress.running("Cloning"));
        }
        int concurrency = Math.min(repos.size(), CpuInfo.highPerfCores());
        var failureSeen = new AtomicBoolean(false);
        try {
            TerminalProgress.run(repos.size(), concurrency,
                    idx -> prepareOne(container, repos.get(idx), refs[idx], states, idx, failureSeen),
                    (idx, frame) -> formatStepLine(repoDisplayName(repos.get(idx)),
                            repos.get(idx).getUrl(), states.get(idx), frame, "Ready"),
                    idx -> plainStepLine(repoDisplayName(repos.get(idx)), states.get(idx), "Ready", "prepare"),
                    System.out::println);
        } finally {
            // Phase 3 (serial): remove all reference devices.
            for (int i = 0; i < repos.size(); i++) {
                if (refs[i] != null && refs[i].mounted()) {
                    try {
                        incus.deviceRemove(container.name(), refs[i].deviceName());
                    } catch (Exception e) {
                        System.err.println("Warning: failed to remove reference device: " + e.getMessage());
                    }
                }
            }
        }

        assertNoStepFailures(repos, states, "prepare");
    }

    /** Clone a repo and, on success, immediately prime it — recording progress/failure
     *  in {@code states[idx]} rather than throwing. {@code failureSeen} is a shared
     *  best-effort fail-fast flag: once any repo has failed the build will abort, so a
     *  clone that finishes afterwards skips its (potentially expensive) prime rather
     *  than doing work that will be thrown away. Primes already in flight run to
     *  completion — this only gates launching new ones. */
    void prepareOne(Container container, ImageDef.RepoEntry repo, RepoReference ref,
                    AtomicReferenceArray<StepProgress> states, int idx, AtomicBoolean failureSeen) {
        var clone = cloneOne(container, repo, ref, states, idx);
        if (!clone.success()) {
            failureSeen.set(true);
            return; // failure already recorded in states[idx]
        }

        String note = clone.usedReference() ? "via host reference" : null;
        boolean highlight = false;
        if (!clone.usedReference() && ref != null && ref.skipReason() != null) {
            note = ref.skipReason();
            highlight = true;
        }
        if (repo.getPrime() != null && !repo.getPrime().isBlank()) {
            if (failureSeen.get()) {
                // Another repo already failed; don't start priming a build that's
                // going to abort. The clone itself succeeded, so say so.
                var skipNote = note == null ? "priming skipped" : note + "; priming skipped";
                states.set(idx, highlight ? StepProgress.doneHighlight(skipNote)
                        : StepProgress.done(skipNote));
                return;
            }
            states.set(idx, StepProgress.running("Priming"));
            if (!primeOne(container, repo, states, idx)) {
                failureSeen.set(true);
                return; // failure recorded
            }
        }
        states.set(idx, highlight ? StepProgress.doneHighlight(note) : StepProgress.done(note));
    }

    private record CloneResult(boolean success, boolean usedReference) {
        static final CloneResult FAILED = new CloneResult(false, false);
    }

    /** Clone a single repo. On failure records it in {@code states[idx]} and returns
     *  {@link CloneResult#FAILED}; on success returns without setting a terminal state
     *  (the caller finalizes it once priming, if any, is done). */
    private CloneResult cloneOne(Container container, ImageDef.RepoEntry repo, RepoReference ref,
                                 AtomicReferenceArray<StepProgress> states, int idx) {
        try {
            boolean usedReference = false;

            if (ref != null && ref.mounted()) {
                var expandedPath = expandHome(repo.getPath());
                var clone = container.shAsUser("agentuser", buildCloneCommand(repo, ref.containerPath()));
                if (clone.success()) {
                    // Point origin at the real remote, fetch current refs, and
                    // detect the remote's default branch.  Objects are already
                    // local (copied from the reference's pack files), so only ref
                    // advertisements travel the network.  set-head --auto is needed
                    // because the local clone inherits HEAD from the host checkout,
                    // which may be on a different branch than the remote's default.
                    var clonePath = shellQuote(expandedPath);
                    var fixup = container.shAsUser("agentuser",
                            "git -C " + clonePath + " remote set-url origin " + shellQuote(repo.getUrl())
                                    + " && git -C " + clonePath + " fetch --quiet origin"
                                    + " && git -C " + clonePath + " remote set-head origin --auto");
                    if (fixup.success()) {
                        String branchArg;
                        if (repo.getBranch() != null && !repo.getBranch().isBlank()) {
                            branchArg = shellQuote(repo.getBranch());
                        } else {
                            branchArg = "\"$(git -C " + clonePath
                                    + " symbolic-ref --short refs/remotes/origin/HEAD)\"";
                        }
                        var checkout = container.shAsUser("agentuser",
                                "git -C " + clonePath + " checkout " + branchArg);
                        usedReference = checkout.success();
                    }
                }
                if (!usedReference) {
                    container.shAsUser("agentuser", "rm -rf " + shellQuote(expandedPath));
                }
            }

            if (!usedReference) {
                var clone = container.shAsUser("agentuser", buildCloneCommand(repo, null));
                if (!clone.success()) {
                    states.set(idx, StepProgress.failed(gitError(clone), combinedOutput(clone)));
                    return CloneResult.FAILED;
                }

                // Widen the fetch refspec that --single-branch narrowed, so the
                // clone behaves like a regular one.  The local-clone path doesn't
                // use --single-branch, so it skips this.
                var repoPath = shellQuote(expandHome(repo.getPath()));
                var restore = container.shAsUser("agentuser",
                        "git -C " + repoPath + " remote set-branches origin '*'");
                if (!restore.success()) {
                    states.set(idx, StepProgress.failed(gitError(restore), combinedOutput(restore)));
                    return CloneResult.FAILED;
                }
            }

            return new CloneResult(true, usedReference);
        } catch (Exception e) {
            states.set(idx, StepProgress.failed(e.getMessage(), null));
            return CloneResult.FAILED;
        }
    }

    /** Run a repo's prime command. Returns true on success; on failure records it in
     *  {@code states[idx]} and returns false. */
    private boolean primeOne(Container container, ImageDef.RepoEntry repo,
                             AtomicReferenceArray<StepProgress> states, int idx) {
        try {
            var expanded = expandHome(repo.getPath());
            var result = container.shAsUser("agentuser",
                    "cd " + shellQuote(expanded) + " && " + repo.getPrime());
            if (result.success()) return true;
            // Prime output is not git, so use the last meaningful line for the
            // concise inline label; the full log is preserved and printed on failure.
            var combined = combinedOutput(result);
            states.set(idx, StepProgress.failed(lastNonEmptyLine(combined), combined));
            return false;
        } catch (Exception e) {
            states.set(idx, StepProgress.failed(e.getMessage(), null));
            return false;
        }
    }

    private static void assertNoStepFailures(List<ImageDef.RepoEntry> repos,
                                             AtomicReferenceArray<StepProgress> states, String verb) {
        var errors = new ArrayList<String>();
        for (int i = 0; i < repos.size(); i++) {
            var progress = states.get(i);
            // Require an explicit DONE: a step left RUNNING or unset (e.g. a task that
            // returned without recording a result) is a failure, not a silent success.
            if (progress != null && progress.state() == StepState.DONE) continue;

            var name = repoDisplayName(repos.get(i));
            var detail = progress == null || progress.detail() == null || progress.detail().isEmpty()
                    ? verb + " failed" : progress.detail();
            errors.add(name + ": " + detail);

            // Print the full captured output so the diagnostic isn't reduced to the
            // concise inline line. Done here, after the animated batch, to avoid
            // interleaving multi-line logs with the live progress display.
            if (progress != null && progress.log() != null && !progress.log().isBlank()) {
                System.err.println("\033[1m─── " + verb + " output: " + name + " ───\033[0m");
                System.err.println(progress.log().strip());
                System.err.println("\033[1m─── end " + verb + " output: " + name + " ───\033[0m");
            }
        }
        if (!errors.isEmpty()) {
            throw new IncusException("Failed to " + verb + " " + errors.size()
                    + " repo(s):\n  " + String.join("\n  ", errors));
        }
    }

    private static String repoDisplayName(ImageDef.RepoEntry repo) {
        var name = GitRemoteUtils.repoNameFromUrl(repo.getUrl());
        return name.isEmpty() ? repo.getUrl() : name;
    }

    /** Render one animated progress line, aligned across running/done/failed states.
     *  The running verb comes from the live state ({@code activity}) so a task can
     *  advance through phases (e.g. Cloning → Priming) within one line. */
    static String formatStepLine(String label, String dimContext, StepProgress progress, int frame,
                                 String doneWord) {
        var runningWord = progress.activity() != null ? progress.activity() : "Working";
        var sb = new StringBuilder(BuildOutput.STEP_INDENT);
        switch (progress.state()) {
            case RUNNING -> sb.append(TerminalProgress.SPINNER[frame % TerminalProgress.SPINNER.length])
                    .append(" \033[2m").append(padStatus(runningWord)).append("\033[0m ");
            case DONE    -> sb.append("\033[32m✓\033[0m \033[2m").append(padStatus(doneWord)).append("\033[0m ");
            case FAILED  -> sb.append("\033[31m✗ ").append(padStatus("Failed")).append("\033[0m ");
        }
        sb.append(label);
        if (dimContext != null && !dimContext.isEmpty()) {
            sb.append(" \033[2m(").append(dimContext).append(")\033[0m");
        }
        if (progress.state() == StepState.DONE && progress.note() != null && !progress.note().isEmpty()) {
            if (progress.noteHighlight()) {
                sb.append(" \033[1m").append(progress.note()).append("\033[0m");
            } else {
                sb.append(" \033[2m").append(progress.note()).append("\033[0m");
            }
        }
        if (progress.state() == StepState.FAILED && progress.detail() != null && !progress.detail().isEmpty()) {
            sb.append("  \033[31m").append(progress.detail()).append("\033[0m");
        }
        return sb.toString();
    }

    private static String plainStepLine(String label, StepProgress progress, String doneWord, String verb) {
        if (progress.state() == StepState.DONE) {
            var line = BuildOutput.STEP_INDENT + doneWord + " " + label;
            if (progress.note() != null && !progress.note().isEmpty()) line += " (" + progress.note() + ")";
            return line;
        }
        var msg = BuildOutput.STEP_INDENT + "Warning: " + verb + " failed for " + label;
        if (progress.detail() != null && !progress.detail().isEmpty()) msg += ": " + progress.detail();
        return msg;
    }

    private static String padStatus(String word) {
        return word.length() >= 8 ? word : word + " ".repeat(8 - word.length());
    }

    /** Extract a concise error line from a failed git exec (stderr first, then stdout). */
    private static String gitError(IncusClient.ExecResult result) {
        var err = firstGitError(result.stderr());
        return !err.isEmpty() ? err : firstGitError(result.stdout());
    }

    /** Full captured output (stdout + stderr) of an exec, for surfacing on failure. */
    private static String combinedOutput(IncusClient.ExecResult result) {
        var out = result.stdout() == null ? "" : result.stdout().strip();
        var err = result.stderr() == null ? "" : result.stderr().strip();
        if (out.isEmpty()) return err;
        if (err.isEmpty()) return out;
        return out + "\n" + err;
    }

    /** Last non-empty line of some text, or "" if none. */
    static String lastNonEmptyLine(String text) {
        if (text == null || text.isEmpty()) return "";
        String last = "";
        for (var line : text.split("\n")) {
            var trimmed = line.strip();
            if (!trimmed.isEmpty()) last = trimmed;
        }
        return last;
    }

    /** First fatal:/error: line from git output, else the last non-empty line. */
    static String firstGitError(String text) {
        if (text == null || text.isEmpty()) return "";
        String lastNonEmpty = "";
        for (var line : text.split("\n")) {
            var trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("fatal:") || trimmed.startsWith("error:")) return trimmed;
            lastNonEmpty = trimmed;
        }
        return lastNonEmpty;
    }

    private static String buildCloneCommand(ImageDef.RepoEntry repo, String referencePath) {
        var cmd = new StringBuilder("git clone");
        if (referencePath != null) {
            // Local clone from mounted host reference — copies pack files
            // directly.  Branch checkout is handled separately after the
            // remote URL is fixed up and a fetch supplies current refs.
            cmd.append(" --no-hardlinks");
        } else {
            cmd.append(" --single-branch");
            if (repo.getBranch() != null && !repo.getBranch().isBlank()) {
                cmd.append(" --branch ").append(shellQuote(repo.getBranch()));
            }
        }
        cmd.append(" -- ").append(shellQuote(referencePath != null ? referencePath : repo.getUrl()));
        cmd.append(" ").append(shellQuote(expandHome(repo.getPath())));
        return cmd.toString();
    }

    RepoReference tryMountReference(Container container, String cloneUrl, SpawnConfig config, boolean isVm) {
        try {
            var repoName = GitRemoteUtils.repoNameFromUrl(cloneUrl);
            if (repoName.isEmpty()) return null;

            var hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, config);
            if (hostPath == null) return null;
            if (!Files.isDirectory(hostPath) || !GitRemoteUtils.isGitRepo(hostPath)
                    || !GitRemoteUtils.anyRemoteMatches(hostPath, cloneUrl)) {
                return RepoReference.skipped("no local reference found to speedup cloning");
            }

            var containerPath = GitRemoteUtils.referenceContainerPath(repoName, cloneUrl);
            var deviceName = GitRemoteUtils.referenceDeviceName(repoName, cloneUrl);
            container.exec("mkdir", "-p", containerPath);
            var refArgs = new java.util.ArrayList<>(java.util.List.of(
                    "source=" + HostResourceSetup.translateForVm(hostPath.toString()),
                    "path=" + containerPath,
                    "readonly=true"));
            HostResourceSetup.addShiftIfSupported(refArgs, isVm);
            incus.deviceAdd(container.name(), deviceName, "disk", refArgs.toArray(String[]::new));

            return new RepoReference(deviceName, containerPath, null);
        } catch (Exception e) {
            System.err.println("Warning: could not set up repo reference: " + e.getMessage());
            return null;
        }
    }

    private static final String CODEX_CONFIG_PATH = CodexSetup.CONFIG_PATH;

    void updateCodexTrust(Container container, ImageDef imageDef) {
        if (imageDef.getRepos().isEmpty()) return;

        var checkResult = container.exec("test", "-f", CODEX_CONFIG_PATH);
        if (!checkResult.success()) return;

        var catResult = container.exec("cat", CODEX_CONFIG_PATH);
        if (!catResult.success()) return;

        var existing = catResult.stdout();
        var sb = new StringBuilder();

        for (var repo : imageDef.getRepos()) {
            var expandedPath = expandHome(repo.getPath());
            var section = "[projects.\"" + expandedPath + "\"]";
            if (!existing.contains(section) && !sb.toString().contains(section)) {
                sb.append("\n").append(section).append("\n");
                sb.append("trust_level = \"trusted\"\n");
            }
        }

        if (sb.isEmpty()) return;

        container.writeFile(CODEX_CONFIG_PATH, existing + sb);
        container.chown(CODEX_CONFIG_PATH, "agentuser:agentuser");
    }

    private static final String CLAUDE_JSON_PATH = "/home/agentuser/.claude.json";
    private static final String AGENTUSER_HOME = "/home/agentuser";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Update .claude.json to pre-trust cloned repo directories and register GitHub repo paths.
     */
    void updateClaudeJsonTrust(Container container, ImageDef imageDef) {
        if (imageDef.getRepos().isEmpty()) return;

        var checkResult = container.exec("test", "-f", CLAUDE_JSON_PATH);
        if (!checkResult.success()) return;

        var catResult = container.exec("cat", CLAUDE_JSON_PATH);
        if (!catResult.success()) {
            System.err.println("Warning: could not read " + CLAUDE_JSON_PATH);
            return;
        }

        try {
            var root = (ObjectNode) JSON.readTree(catResult.stdout());

            var projects = root.has("projects")
                    ? (ObjectNode) root.get("projects")
                    : root.putObject("projects");

            var githubRepoPaths = root.has("githubRepoPaths")
                    ? (ObjectNode) root.get("githubRepoPaths")
                    : root.putObject("githubRepoPaths");

            for (var repo : imageDef.getRepos()) {
                var expandedPath = expandHome(repo.getPath());

                if (!projects.has(expandedPath)) {
                    var projectEntry = projects.putObject(expandedPath);
                    projectEntry.putArray("allowedTools");
                    projectEntry.put("hasTrustDialogAccepted", true);
                }

                var ownerRepo = parseGitHubOwnerRepo(repo.getUrl());
                if (ownerRepo != null) {
                    ArrayNode paths;
                    if (githubRepoPaths.has(ownerRepo)) {
                        paths = (ArrayNode) githubRepoPaths.get(ownerRepo);
                    } else {
                        paths = githubRepoPaths.putArray(ownerRepo);
                    }
                    boolean found = false;
                    for (var node : paths) {
                        if (node.asText().equals(expandedPath)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        paths.add(expandedPath);
                    }
                }
            }

            var updatedJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            container.writeFile(CLAUDE_JSON_PATH, updatedJson);
            container.chown(CLAUDE_JSON_PATH, "agentuser:agentuser");
        } catch (Exception e) {
            System.err.println("Warning: failed to update " + CLAUDE_JSON_PATH + ": " + e.getMessage());
        }
    }

    static String expandHome(String path) {
        if (path.startsWith("~/")) {
            return AGENTUSER_HOME + path.substring(1);
        }
        if (path.equals("~")) {
            return AGENTUSER_HOME;
        }
        return path;
    }

    static String parseGitHubOwnerRepo(String url) {
        if (url == null) return null;
        var prefix = "https://github.com/";
        if (!url.startsWith(prefix)) return null;
        var rest = url.substring(prefix.length());
        if (rest.endsWith(".git")) {
            rest = rest.substring(0, rest.length() - 4);
        }
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        var parts = rest.split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return parts[0] + "/" + parts[1];
    }

}
