package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewalldCheck;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.InstanceType;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyHealthCheck;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.nio.file.Path;

@CommandDefinition(
        name = "branch",
        description = "Create a new instance from an existing one",
        generateHelp = true
)
public class BranchCommand extends BaseCommand {

    @Argument(description = "Name for the new instance", required = true)
    String name;

    @Option(name = "from", description = "Source instance to branch from (auto-detected from cwd if omitted)")
    String source;

    @Option(name = "gui", description = "Enable GUI passthrough (Wayland + GPU + audio)", hasValue = false)
    boolean gui;

    @Option(name = "airgap", description = "Disable network access (complete isolation)", hasValue = false)
    boolean airgap;

    @Option(name = "proxy-only", description = "Restrict network to host proxy only (Claude + GitHub via proxy)", hasValue = false)
    boolean proxyOnly;

    @Option(name = "inbox", description = "Host directory to mount read-only at /home/agentuser/inbox")
    Path inbox;

    @Option(name = "cpu", description = "CPU core limit (default: adaptive)")
    Integer cpuLimit;

    @Option(name = "memory", description = "Memory limit, e.g. '8GB' (default: adaptive)")
    String memoryLimit;

    @Option(name = "disk", description = "Disk size limit (default: adaptive)")
    String diskLimit;

    @Option(name = "no-start", description = "Don't start the instance after creation", hasValue = false)
    boolean noStart;

    private IncusClient incus;

    @Override
    protected CommandResult doExecute() throws Exception {
        this.incus = RuntimeServices.incus();

        var resolvedSource = resolveSource();
        if (resolvedSource == null) return CommandResult.valueOf(1);

        if (incus.exists(name)) {
            System.err.println("Error: an instance named '" + name + "' already exists.");
            return CommandResult.valueOf(1);
        }

        var networkMode = resolveNetworkMode();
        if (networkMode != NetworkMode.AIRGAP) {
            if (!ProxyHealthCheck.checkOrWarn(incus)) return CommandResult.valueOf(1);
            BridgeSubnetCheck.warnIfConflict(incus);
            FirewalldCheck.warnIfNotRunning();
            if (checkCaMismatch(resolvedSource)) return CommandResult.valueOf(1);
        }

        System.out.println("Branching '" + name + "' from '" + resolvedSource + "'...");
        incus.copy(resolvedSource, name);

        var cpu = String.valueOf(cpuLimit != null ? cpuLimit : ResourceLimits.adaptiveCpuLimit());
        var memory = memoryLimit != null ? memoryLimit : ResourceLimits.adaptiveMemoryLimit();
        var disk = diskLimit != null ? diskLimit : ResourceLimits.defaultDiskLimit();

        System.out.println("Applying resource limits: " + cpu + " CPUs, " + memory + " memory, " + disk + " disk");
        InstanceLifecycle.applyResourceLimits(incus, name, cpu, memory, disk);
        InstanceLifecycle.configureNetwork(incus, name, networkMode);
        InstanceLifecycle.tagMetadata(incus, name, Metadata.TYPE_CLONE, resolvedSource);
        InstanceLifecycle.integrateWithHost(incus, name, InstanceType.INSTANCE);

        // Configure GUI before start so environment.* keys are visible to init
        if (gui) {
            if (configureGui()) {
                incus.configSet(name, Metadata.GUI_ENABLED, "true");
            } else {
                GuiPassthrough.removeGui(incus, name);
                System.err.println("Continuing without GUI passthrough.");
            }
        } else {
            // Clean up inherited GUI devices/env from incus copy
            GuiPassthrough.removeGui(incus, name);
            warnIfTemplateWantsGui(resolvedSource);
        }

        if (noStart) {
            System.out.println("Branch '" + name + "' created (not started).");
            return CommandResult.SUCCESS;
        }

        System.out.println("Starting container...");
        incus.start(name);
        InstanceLifecycle.setupRuntime(incus, name, networkMode, inbox);

        System.out.println("Branch '" + name + "' is ready.\n");
        incus.interactiveShell(name, "agentuser");
        return CommandResult.SUCCESS;
    }

    private String resolveSource() {
        if (source != null) {
            if (!incus.exists(source)) {
                System.err.println("Error: source instance '" + source + "' does not exist.");
                return null;
            }
            return source;
        }

        // Try to auto-detect from cwd
        var projectConfig = ProjectConfig.findInDirectory(Path.of("."));
        if (projectConfig != null && projectConfig.getName() != null) {
            var detected = projectConfig.getName();
            if (incus.exists(detected)) {
                System.out.println("Auto-detected source: " + detected);
                return detected;
            }
            System.err.println("Error: auto-detected source '" + detected + "' does not exist.");
            return null;
        }

        System.err.println("Error: no --from specified and no incus-spawn.yaml found in current directory.");
        System.err.println("Usage: isx branch <name> --from <source-instance>");
        return null;
    }

    private boolean configureGui() {
        return GuiPassthrough.configureGui(incus, name);
    }

    private void warnIfTemplateWantsGui(String source) {
        if ("true".equals(incus.configGet(source, Metadata.GUI_ENABLED))) {
            System.err.println("Note: '" + source + "' has GUI passthrough — consider using --gui.");
            return;
        }
        var defs = ImageDef.loadAll();
        var def = defs.get(source);
        if (def != null && def.isGui()) {
            System.err.println("Note: '" + source + "' has GUI passthrough — consider using --gui.");
        }
    }

    private NetworkMode resolveNetworkMode() {
        if (airgap && proxyOnly) {
            System.err.println("Error: --airgap and --proxy-only are mutually exclusive.");
            System.exit(1);
        }
        if (airgap) return NetworkMode.AIRGAP;
        if (proxyOnly) return NetworkMode.PROXY_ONLY;
        return NetworkMode.FULL;
    }

    private boolean checkCaMismatch(String source) {
        var imageCaFp = incus.configGet(source, Metadata.CA_FINGERPRINT);
        if (imageCaFp.isEmpty()) return false;
        var localCaFp = CertificateAuthority.currentCaFingerprint();
        if (localCaFp.isEmpty() || imageCaFp.equals(localCaFp)) return false;
        var profile = incus.configGet(source, Metadata.PROFILE);
        var sep = "\033[33m" + "─".repeat(60) + "\033[0m";
        System.err.println(sep);
        System.err.println("\033[1;33mCA certificate mismatch\033[0m");
        System.err.println("Template '" + source + "' was built with a different CA certificate.");
        System.err.println("TLS connections through the proxy will fail in branches.");
        if (!profile.isEmpty()) {
            System.err.println("Rebuild the template to fix: \033[1misx build " + profile + "\033[0m");
        }
        System.err.println(sep);
        return true;
    }


}
