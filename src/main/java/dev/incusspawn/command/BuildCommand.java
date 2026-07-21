package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.EnvResolver;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.IncusClient;
import static dev.incusspawn.incus.Container.shellQuote;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.MitmProxy;
import dev.incusspawn.proxy.ProxyHealthCheck;

import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
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
        System.out.print(prompt + " (y/N): ");
        if (!console.readLine().strip().equalsIgnoreCase("y")) {
            System.out.println("Aborted.");
            return false;
        }
        return true;
    }
    private static final String DNF_CACHE_DEVICE = "dnf-cache";
    static final String REBUILDING_SUFFIX = "-rebuilding";

    private volatile String[] activeBuild;

    @Override
    protected CommandResult doExecute() throws Exception {
        this.incus = RuntimeServices.incus();
        this.toolDefLoader = RuntimeServices.toolDefLoader();
        this.toolSetups = RuntimeServices.toolSetups();
        if (!InitCommand.requireInit()) return CommandResult.valueOf(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var build = activeBuild;
            if (build != null) {
                promoteToFailedInstance(build[0], build[1]);
            }
        }));

        var defs = ImageDef.loadAll();

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
                buildWithDescendants(imageDef, defs);
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
            build(imageDef, defs);
            return CommandResult.SUCCESS;
        } catch (BuildFailedException e) {
            return CommandResult.valueOf(1);
        }
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
            System.out.println("All templates are up to date.");
            return;
        }

        // Confirm with user
        System.out.println((outdatedOnly ? "Templates to rebuild: " : "This will rebuild all templates: ")
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

        System.out.println();
        for (var templateName : templates) {
            var imageDef = defs.get(templateName);
            if (imageDef == null) {
                System.err.println("Template definition not found: " + templateName);
                failedBuilds.add(templateName);
                continue;
            }
            if (shouldSkipDueToFailedParent(imageDef, defs, failedBuilds)) {
                System.out.println("Skipping " + templateName + " (parent failed to build)");
                failedBuilds.add(templateName);
                continue;
            }

            try {
                buildSingleImage(imageDef, defs);
                System.out.println();
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

        System.out.println("This will rebuild: " + String.join(", ", chain));
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

        System.out.println("This will rebuild: " + String.join(", ", chain));
        if (!confirm("Continue?")) return;

        rebuildAll(chain, defs);
    }

    /**
     * Build an image. If the image has a parent, ensure the parent
     * is built first (recursively).
     */
    private void build(ImageDef imageDef, Map<String, ImageDef> defs) {
        var dnsOverrides = MitmProxy.getDnsOverrides(incus);
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
                        System.out.println("Parent image '" + parentName + "' not found, building it first...\n");
                    } else {
                        System.out.println("Parent image '" + parentName + "' is outdated, rebuilding it first...\n");
                    }
                    buildChain(parentDef, defs);
                    System.out.println();
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

        System.out.println("Building image: " + canonicalName);

        if (incus.exists(canonicalName)) {
            if (!yes) {
                System.out.println("Image '" + canonicalName + "' already exists.");
                System.out.println("It will be replaced if the build succeeds.");
            }
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
            System.err.println("\n\033[33m" + "─".repeat(60) + "\033[0m");
            System.err.println("\033[1mBuild failed for " + canonicalName + ": " + e.getMessage() + "\033[0m");
            printBuildDiagnostics(tempName);
            try {
                var failedHostResources = HostResourceSetup.collectEffective(imageDef, defs);
                HostResourceSetup.removeBuildDevices(incus, tempName, failedHostResources);
            } catch (Exception ignored) {}
            promoteToFailedInstance(tempName, canonicalName);
            activeBuild = null;
            throw new BuildFailedException(canonicalName);
        }

        incus.deleteIfExists(canonicalName);
        incus.rename(tempName, canonicalName);
        activeBuild = null;
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

    private void printBuildDiagnostics(String buildName) {
        try {
            var status = incus.getInstanceStatus(buildName);
            System.err.println("  Container status: " + (status.isEmpty() ? "(unknown)" : status));

            var log = incus.getLog(buildName);
            if (!log.isBlank()) {
                var lines = log.lines().toList();
                var tail = lines.subList(Math.max(0, lines.size() - 20), lines.size());
                System.err.println("  LXC log (last " + tail.size() + " lines):");
                tail.forEach(l -> System.err.println("    " + l));
            }

            var pool = incus.findCowPool();
            if (pool != null) {
                System.err.println("  " + incus.getStoragePoolUsage(pool));
            }

            var mem = incus.getServerMemoryUsage();
            if (!mem.isEmpty()) {
                System.err.println("  " + mem);
            }

            if ("Error".equals(status)) {
                var dmesg = incus.queryDmesgForContainer(buildName);
                if (!dmesg.isEmpty()) {
                    var cause = diagnoseCrashCause(dmesg);
                    if (cause != null) {
                        System.err.println("  Cause: " + cause);
                    }
                    System.err.println("  Kernel log (dmesg):");
                    dmesg.lines().forEach(l -> System.err.println("    " + l));
                }
            }
        } catch (Exception diag) {
            System.err.println("  (could not collect diagnostics: " + diag.getMessage() + ")");
        }
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

    private void promoteToFailedInstance(String buildName, String canonicalName) {
        var promotedName = canonicalName + "-failed-build";
        try {
            incus.deleteIfExists(promotedName);
            try { unmountDnfCache(buildName); } catch (Exception ignored) {}
            if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(buildName))) {
                incus.forceStop(buildName);
            }
            incus.rename(buildName, promotedName);
            incus.configSet(promotedName, Metadata.TYPE, Metadata.TYPE_FAILED_BUILD);
            incus.configSet(promotedName, Metadata.PARENT, canonicalName);
            incus.configSet(promotedName, Metadata.CREATED, Metadata.now());
            System.err.println("\033[1mContainer promoted to instance '" + promotedName + "' for inspection.\033[0m");
        } catch (Exception promoteError) {
            System.err.println("Failed to promote container: " + promoteError.getMessage());
            System.err.println("Container '" + buildName + "' may still exist for manual cleanup.");
        }
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

        System.out.println("Deriving from parent image '" + parentCanonical + "'...");
        incus.copy(parentSource, buildName);
        if (!effectiveVm) {
            incus.configSet(buildName, "security.idmap.size", "165536");
            incus.configSet(buildName, "security.nesting", "true");
            if (Environment.isLinux()) {
                incus.configSet(buildName, "security.syscalls.intercept.setxattr", "true");
            }
            incus.configSet(buildName, "raw.lxc", "lxc.cap.drop =");
        }
        incus.start(buildName);
        incus.waitForReady(buildName);

        var container = new Container(incus, buildName);
        if (!effectiveVm) {
            prepareContainerForPackageInstall(container);
        }

        incus.waitForSystemd(buildName);

        if (!effectiveVm) {
            waitForIpv4(container);
        }

        var gatewayIp = MitmProxy.resolveGatewayIp(incus);
        container.sh(
                "sed -i 's/resolve \\[!UNAVAIL=return\\] //' /etc/nsswitch.conf; " +
                "rm -f /etc/resolv.conf; " +
                "echo 'nameserver " + gatewayIp + "' > /etc/resolv.conf")
                .assertSuccess("Failed to fix DNS after copy");

        waitForNetwork(buildName);

        mountDnfCache(buildName, effectiveVm);

        var hostResources = HostResourceSetup.collectEffective(imageDef, defs);
        if (!hostResources.isEmpty()) {
            System.out.println("Applying host-resources...");
            HostResourceSetup.applyForBuild(incus, container, hostResources, effectiveVm);
        }

        removePackages(container, imageDef);

        var toolResolution = collectEffectiveTools(imageDef, defs);
        enablePackageRepos(container, imageDef, toolResolution.effective(), toolResolution.ancestors(), defs);
        installAllPackages(container, imageDef, toolResolution.effective(), toolResolution.ancestors(), defs);

        runToolSetup(container, toolResolution.effective());
        var allTools = new ArrayList<>(toolResolution.ancestors());
        allTools.addAll(toolResolution.effective());
        writeEnvFile(container, imageDef, defs, allTools, canonicalName);
        maskServices(container, imageDef);
        installSkills(container, imageDef, defs);
        cloneRepos(container, imageDef, effectiveVm);
        updateClaudeJsonTrust(container, imageDef);

        HostResourceSetup.removeBuildDevices(incus, buildName, hostResources);
        unmountDnfCache(buildName);

        cleanCaches(buildName);

        tagTemplateMetadata(buildName, canonicalName, imageDef, parentCanonical, hostResources, defs);

        System.out.println("Stopping image...");
        incus.stop(buildName);

        System.out.println("Image " + canonicalName + " built successfully.");
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
                            System.out.println("Base image is container-only, using " + image + " for VM build.");
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
        System.out.println("Launching " + image + (effectiveVm ? " (VM)..." : "..."));
        try {
            incus.create(image, buildName, effectiveVm);
        } catch (IncusException e) {
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

        var container = new Container(incus, buildName);

        // Install CA cert before security configs — update-ca-trust triggers
        // setxattr calls that conflict with security.syscalls.intercept.
        System.out.println("Installing MITM proxy CA certificate...");
        var ca = CertificateAuthority.loadOrCreate();
        container.sh(
                "cat > /etc/pki/ca-trust/source/anchors/incus-spawn-mitm.crt << 'CERTEOF'\n" +
                ca.caCertPem() +
                "CERTEOF")
                .assertSuccess("Failed to install MITM CA certificate");
        container.exec("update-ca-trust")
                .assertSuccess("Failed to update CA trust");

        // Container-only security tweaks: UID mapping, nesting, capability
        // retention, and setxattr interception. VMs run a full kernel and
        // don't need any of these. Restart activates the new config.
        if (!effectiveVm) {
            incus.configSet(buildName, "raw.idmap", "both 1000 1000");
            incus.configSet(buildName, "security.idmap.size", "165536");
            incus.configSet(buildName, "security.nesting", "true");
            if (Environment.isLinux()) {
                incus.configSet(buildName, "security.syscalls.intercept.setxattr", "true");
            }
            incus.configSet(buildName, "raw.lxc", "lxc.cap.drop =");
            prepareContainerForPackageInstall(container);

            System.out.println("Restarting container with updated security config...");
            incus.restart(buildName);
            incus.waitForSystemd(buildName);
            waitForIpv4(container);
        }

        System.out.println("Configuring DNS...");
        var gatewayIp = MitmProxy.resolveGatewayIp(incus);
        container.sh(
                "sed -i 's/resolve \\[!UNAVAIL=return\\] //' /etc/nsswitch.conf; " +
                "rm -f /etc/resolv.conf; " +
                "echo 'nameserver " + gatewayIp + "' > /etc/resolv.conf")
                .assertSuccess("Failed to configure DNS");

        waitForNetwork(buildName);

        mountDnfCache(buildName, effectiveVm);

        if (effectiveVm) {
            System.out.println("Expanding VM root filesystem...");
            container.runInteractive("Failed to expand VM root filesystem",
                    "sh", "-c",
                    "dnf install -y -q cloud-utils-growpart e2fsprogs xfsprogs && " +
                    "growpart /dev/sda 2 && " +
                    "if findmnt -n -o FSTYPE / | grep -q xfs; then xfs_growfs /; else resize2fs /dev/sda2; fi");
        }

        if (!prebaked) {
            removePackages(container, imageDef);

            System.out.println("Updating system packages...");
            runDnf(container, "Failed to update system packages",
                    "dnf", "-y", "--setopt=keepcache=true", "--setopt=metadata_expire=3600", "--setopt=tsflags=nodocs", "upgrade");

            if (effectiveVm) {
                System.out.println("Regenerating initramfs for VM...");
                container.runInteractive("Failed to regenerate initramfs",
                        "dracut", "--force", "--regenerate-all");
            }

            // Disable systemd-resolved AFTER dnf upgrade — the upgrade can re-enable
            // it. Masking prevents package scripts from restarting it. Also remove
            // 'resolve' from nsswitch.conf so .local domains use dnsmasq, not mDNS.
            System.out.println("Finalizing DNS configuration...");
            container.sh(
                    "systemctl disable --now systemd-resolved 2>/dev/null; " +
                    "systemctl mask systemd-resolved 2>/dev/null; " +
                    "sed -i 's/resolve \\[!UNAVAIL=return\\] //' /etc/nsswitch.conf")
                    .assertSuccess("Failed to finalize DNS configuration");

            maskServices(container, imageDef);
        }

        if (!prebaked || !container.exec("id", "agentuser").success()) {
            System.out.println("Creating agentuser...");
            container.exec("useradd", "-m", "-u", "1000", "-G", "systemd-journal", "agentuser")
                    .assertSuccess("Failed to create agentuser");
            container.exec("chown", "-R", "agentuser:agentuser", "/home/agentuser")
                    .assertSuccess("Failed to set home directory ownership");
            container.exec("mkdir", "-p", "/home/agentuser/inbox")
                    .assertSuccess("Failed to create inbox directory");
            container.sh(
                    "echo 'agentuser ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/agentuser")
                    .assertSuccess("Failed to configure passwordless sudo");
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

            System.out.println("Installing base packages...");
            runDnf(container, "Failed to install base packages",
                    "dnf", "install", "-y", "--setopt=keepcache=true", "--setopt=metadata_expire=3600", "--setopt=tsflags=nodocs",
                    "git", "curl", "which", "procps-ng", "findutils");
        }

        var hostResources = HostResourceSetup.collectEffective(imageDef, defs);
        if (!hostResources.isEmpty()) {
            System.out.println("Applying host-resources...");
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
                System.out.println("\nApplying layer: " + layer.getName());
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
        }
        writeEnvFile(container, imageDef, defs, allTools, canonicalName);

        HostResourceSetup.removeBuildDevices(incus, buildName, hostResources);
        unmountDnfCache(buildName);

        cleanCaches(buildName);

        var parentCanonical = imageDef.isRoot() ? null : imageDef.getParent();
        tagTemplateMetadata(buildName, canonicalName, imageDef, parentCanonical, hostResources, defs);

        System.out.println("Stopping image...");
        incus.stop(buildName);

        System.out.println("Image " + canonicalName + " built successfully.");
    }

    private void checkPinnedWarning(ImageDef imageDef) {
        if (!imageDef.isPinned()) return;
        var builtin = ImageDef.loadBuiltinByName(imageDef.getName());
        if (builtin == null || builtin.getImageTag() == null) return;
        var pinnedTag = imageDef.getImageTag();
        var builtinTag = builtin.getImageTag();
        if (pinnedTag != null && builtinTag.compareTo(pinnedTag) > 0) {
            System.out.println("Warning: base image is pinned to " + pinnedTag
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
                System.out.println("Base image '" + localAlias + "' is up to date (" + tag + ").");
                return;
            }
            System.out.println("Base image '" + localAlias + "' is outdated"
                    + (installedTag != null ? " (" + installedTag + " -> " + tag + ")" : "")
                    + ", replacing...");
            incus.deleteImageAlias(localAlias);
            incus.deleteImage(existingFingerprint);
        }
        var resolvedUrl = imageUrl.replace("{arch}", arch);
        if (tag != null) {
            resolvedUrl = resolvedUrl.replace("{tag}", tag);
        }

        System.out.println("Downloading base image from " + resolvedUrl + "...");

        try {
            var cache = new DownloadCache();
            var cached = cache.download(resolvedUrl, expectedSha256);

            System.out.println("Importing image into Incus...");
            var fingerprint = incus.importImage(cached);

            System.out.println("Creating alias '" + localAlias + "' -> "
                    + fingerprint.substring(0, Math.min(12, fingerprint.length())) + "...");
            if (tag != null) {
                incus.setImageProperty(fingerprint, "incus-spawn.tag", tag);
            }
            incus.createImageAlias(localAlias, fingerprint);

            System.out.println("Base image ready.");
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
                // Legacy: also configure dhcpcd if present (old base images)
                "if [ -f /etc/dhcpcd.conf ]; then " +
                "  for opt in 'nohook resolv.conf' noarp nodev; do " +
                "    grep -q \"^${opt}$\" /etc/dhcpcd.conf 2>/dev/null || " +
                "    echo \"${opt}\" >> /etc/dhcpcd.conf; " +
                "  done; " +
                "  sed -i 's/RestartSec=5/RestartSec=1/' /etc/systemd/system/dhcpcd-eth0.service 2>/dev/null; " +
                "fi; true")
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
                System.err.println("Warning: unknown tool '" + name + "', skipping.");
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
                    System.out.println("  Auto-adding dependency: " + depRef.getName() + " (required by " + name + ")");
                }
                resolveWithDeps(depRef.getName(), depRef.getParams(), resolved, visiting, explicit, toolDefLoader, cdiTools, quiet);
            }
        } else {
            for (var dep : tool.requires()) {
                if (!quiet && !explicit.contains(dep)) {
                    System.out.println("  Auto-adding dependency: " + dep + " (required by " + name + ")");
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
        System.out.println("Removing unnecessary packages...");
        container.sh(
                "dnf remove -y --setopt=clean_requirements_on_remove=True " +
                String.join(" ", pkgs) + " 2>/dev/null; true");
    }

    private void maskServices(Container container, ImageDef imageDef) {
        var services = imageDef.getMaskServices();
        if (services.isEmpty()) return;
        System.out.println("Masking unnecessary services...");
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
            System.out.println("All " + totalCount + " packages already installed.");
            return;
        }

        System.out.println("Installing " + allPackages.size() + " packages (" +
                (totalCount - allPackages.size()) + " already installed): " +
                String.join(", ", allPackages) + "...");
        var args = new ArrayList<String>();
        args.addAll(List.of("dnf", "install", "-y", "--setopt=keepcache=true", "--setopt=metadata_expire=3600", "--setopt=tsflags=nodocs"));
        args.addAll(allPackages);
        runDnf(container, "Failed to install packages", args.toArray(String[]::new));
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
                case "copr" -> {
                    System.out.println("Enabling COPR repo " + key.name() + "...");
                    container.runInteractive("Failed to enable COPR repo " + key.name(),
                            "dnf", "copr", "enable", "-y", key.name());
                }
                default -> System.err.println("Warning: unknown package_repos type '" + key.type()
                        + "' for '" + key.name() + "', skipping.");
            }
        }
    }

    /**
     * Run the non-package setup steps for each tool (scripts, files, env, verify).
     */
    private void runToolSetup(Container container, List<ResolvedTool> tools) {
        for (var resolved : tools) {
            if (resolved.reconfigureOnly()) {
                resolved.setup().reconfigure(container, resolved.parameters());
            } else {
                resolved.setup().install(container, resolved.parameters());
            }
        }
    }

    private void writeEnvFile(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                               List<ResolvedTool> allTools, String canonicalName) {
        var resolver = new EnvResolver();

        resolver.add(EnvEntry.raw("export ISX_CONTAINER=\"${HOSTNAME}\""), "built-in");
        resolver.add(EnvEntry.set("ISX_TEMPLATE", canonicalName), "built-in");
        resolver.add(EnvEntry.prepend("JAVA_TOOL_OPTIONS",
                "-Djavax.net.ssl.trustStore=/etc/pki/java/cacerts", " "), "built-in");

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

    private static ToolSetup findTool(String name, ToolDefLoader toolDefLoader, Iterable<ToolSetup> cdiTools) {
        var tool = toolDefLoader.find(name);
        if (tool != null) return tool;
        for (var t : cdiTools) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    private void runDnf(Container container, String failureMessage, String... args) {
        try {
            container.runInteractive(failureMessage, args);
        } catch (IncusException e) {
            System.out.println("DNF failed, clearing metadata and retrying with --refresh...");
            container.sh("dnf clean metadata");
            var retryArgs = new ArrayList<>(List.of(args));
            retryArgs.add(1, "--refresh");
            container.runInteractive(failureMessage, retryArgs.toArray(String[]::new));
        }
    }

    private void cleanCaches(String container) {
        System.out.println("Cleaning up caches...");
        incus.shellExec(container, "sh", "-c",
                "dnf clean all; rm -rf /var/cache/libdnf5 /tmp/* /var/tmp/*; true");
    }

    private void waitForIpv4(Container container) {
        System.out.println("Waiting for network...");
        var result = container.sh(
                "systemctl start systemd-networkd 2>/dev/null; " +
                "systemctl start dhcpcd-eth0.service 2>/dev/null; " +
                "for i in $(seq 1 30); do " +
                "  ip -4 -o addr show eth0 | grep -q 'inet ' && exit 0; " +
                "  sleep 0.5; " +
                "done; exit 1");
        if (result.success()) {
            System.out.println("  Network ready.");
            return;
        }
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
        System.out.println("Verifying DNS resolution...");
        for (int attempt = 0; attempt < 10; attempt++) {
            var dnsCheck = incus.shellExec(container, "sh", "-c",
                    "curl -4 -s -o /dev/null -w '%{http_code}' https://mirrors.fedoraproject.org");
            if (dnsCheck.success() && dnsCheck.stdout().strip().contains("302")) {
                System.out.println("  DNS working.");
                return;
            }
            if (attempt == 9) {
                var diagnostic = BridgeSubnetCheck.detectConflictDiagnostic(incus);
                var fwDiagnostic = FirewalldCheck.detectDiagnostic();
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
            System.out.println("  Waiting for DNS... (attempt " + (attempt + 2) + "/10)");
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

    /** Global skills directory for Claude Code inside the container. */
    private static final String SKILLS_DIR = "/home/agentuser/.claude/skills";

    /**
     * Install Claude Code skills declared in the image definition.
     * Fetches SKILL.md files on the host and writes them directly into the container.
     * Deduplicates against skills already declared by ancestor images.
     */
    void installSkills(Container container, ImageDef imageDef, Map<String, ImageDef> defs) {
        var skillSources = collectEffectiveSkills(imageDef, defs);
        if (skillSources.isEmpty()) return;

        var repo = imageDef.getSkills().getRepo();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        var cache = new dev.incusspawn.tool.SkillsCache();

        container.exec("mkdir", "-p", SKILLS_DIR);

        for (var entry : skillSources) {
            String resolved;
            try {
                resolved = resolveSkillSource(entry, repo);
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                System.err.println("Use the fully qualified form 'owner/repo@skill-name', or set 'skills.repo' in your image definition.");
                throw new BuildFailedException();
            }
            System.out.println("Installing skill: " + resolved + "...");
            try {
                var skills = fetchSkills(resolved, http, cache);
                for (var skill : skills) {
                    var skillDir = SKILLS_DIR + "/" + skill.name();
                    container.exec("mkdir", "-p", skillDir);
                    container.writeFile(skillDir + "/SKILL.md", skill.content());
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Error: Failed to fetch skill '" + resolved + "': " + e.getMessage());
                throw new BuildFailedException();
            }
        }

        // Fix ownership so agentuser owns the skills directory
        container.exec("chown", "-R", "agentuser:agentuser", SKILLS_DIR);
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
            var token = System.getenv("GITHUB_TOKEN");
            var reqBuilder = HttpRequest.newBuilder(URI.create(treeUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json");
            if (token != null && !token.isBlank()) {
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

    record RepoReference(String deviceName, String containerPath) {}

    /**
     * Clone git repos declared in the image definition as agentuser.
     * When a matching host-side checkout is available (via SpawnConfig host-path/repo-paths),
     * uses {@code --reference} to speed up cloning from local objects. Dissociation
     * is deferred to after checkout: a manual {@code repack -a -d} followed by
     * alternates removal makes the clone self-contained while the reference device
     * is still mounted.
     */
    void cloneRepos(Container container, ImageDef imageDef, boolean isVm) {
        var config = SpawnConfig.load();

        for (var repo : imageDef.getRepos()) {
            System.out.println("Cloning " + repo.getUrl() + "...");

            boolean cloned = false;
            RepoReference ref = null;

            try {
                ref = tryMountReference(container, repo.getUrl(), config, isVm);
                if (ref != null) {
                    System.out.println("  \033[1;32mUsing local host reference to speed up clone...\033[0m");
                    try {
                        container.runAsUser("agentuser",
                                buildCloneCommand(repo, ref.containerPath()),
                                "Failed to clone " + repo.getUrl() + " with reference");
                        // Dissociate now that checkout succeeded: repack referenced
                        // objects locally and drop the alternates entry, so the clone
                        // is self-contained before the reference device is removed.
                        var expandedPath = expandHome(repo.getPath());
                        var clonePath = shellQuote(expandedPath);
                        container.runAsUser("agentuser",
                                "git -C " + clonePath + " repack -a -d"
                                        + " && rm -f -- " + shellQuote(expandedPath + "/.git/objects/info/alternates"),
                                "Failed to dissociate " + repo.getUrl() + " from reference");
                        cloned = true;
                        System.out.println("  Done.");
                    } catch (Exception e) {
                        System.out.println("  Reference clone failed, falling back to normal clone...");
                        container.runAsUser("agentuser",
                                "rm -rf " + shellQuote(expandHome(repo.getPath())),
                                "Failed to clean up partial clone");
                    }
                }
            } finally {
                if (ref != null) {
                    try {
                        incus.deviceRemove(container.name(), ref.deviceName());
                    } catch (Exception e) {
                        System.err.println("Warning: failed to remove reference device: " + e.getMessage());
                    }
                }
            }

            if (!cloned) {
                container.runAsUser("agentuser",
                        buildCloneCommand(repo, null),
                        "Failed to clone " + repo.getUrl());
            }

            // Restore full fetch refspec so the clone behaves like a regular clone.
            // --single-branch narrows it to one branch; this undoes that without
            // downloading anything — other branches are fetched lazily on demand.
            var repoPath = shellQuote(expandHome(repo.getPath()));
            container.runAsUser("agentuser",
                    "git -C " + repoPath + " remote set-branches origin '*'",
                    "Failed to restore fetch refspec for " + repo.getUrl());

            if (repo.getPrime() != null && !repo.getPrime().isBlank()) {
                System.out.println("Priming " + repo.getPath() + "...");
                var expanded = expandHome(repo.getPath());
                container.runAsUserPty("agentuser",
                        "cd " + shellQuote(expanded) + " && " + repo.getPrime(),
                        "Failed to prime " + repo.getPath());
            }
        }
    }

    private static String buildCloneCommand(ImageDef.RepoEntry repo, String referencePath) {
        var cmd = new StringBuilder("git clone --single-branch");
        if (referencePath != null) {
            cmd.append(" --reference ").append(shellQuote(referencePath));
        }
        if (repo.getBranch() != null && !repo.getBranch().isBlank()) {
            cmd.append(" --branch ").append(shellQuote(repo.getBranch()));
        }
        cmd.append(" -- ").append(shellQuote(repo.getUrl()));
        cmd.append(" ").append(shellQuote(expandHome(repo.getPath())));
        return cmd.toString();
    }

    RepoReference tryMountReference(Container container, String cloneUrl, SpawnConfig config, boolean isVm) {
        try {
            var repoName = GitRemoteUtils.repoNameFromUrl(cloneUrl);
            if (repoName.isEmpty()) return null;

            var hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, config);
            if (hostPath == null) return null;
            if (!Files.isDirectory(hostPath)) {
                System.out.println("  Host repo path " + hostPath + " not found, skipping reference clone");
                return null;
            }
            if (!GitRemoteUtils.isGitRepo(hostPath)) {
                System.out.println("  Host path " + hostPath + " is not a git repo, skipping reference clone");
                return null;
            }

            if (!GitRemoteUtils.anyRemoteMatches(hostPath, cloneUrl)) {
                System.out.println("  No remote in " + hostPath + " matches " + cloneUrl + ", skipping reference clone");
                return null;
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

            return new RepoReference(deviceName, containerPath);
        } catch (Exception e) {
            System.err.println("Warning: could not set up repo reference: " + e.getMessage());
            return null;
        }
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
