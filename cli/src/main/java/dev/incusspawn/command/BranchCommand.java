package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    @Option(name = "kvm", description = "Expose /dev/kvm for nested virtualization", hasValue = false)
    boolean kvm;

    @Option(name = "no-kvm", description = "Disable KVM even if the template was built with type: kvm", hasValue = false)
    boolean noKvm;

    @Option(name = "airgap", description = "Disable network access (complete isolation)", hasValue = false)
    boolean airgap;

    @Option(name = "proxy-only", description = "Restrict network to host proxy only (Claude + GitHub via proxy)", hasValue = false)
    boolean proxyOnly;

    @Option(name = "inbox", description = "Host directory to mount read-only at /home/agentuser/inbox")
    Path inbox;

    @Option(name = "cpu", description = "CPU core limit (default: adaptive)")
    Integer cpuLimit;

    @Option(name = "memory", description = "Memory limit, e.g. '8GB' (default: 60% of host RAM for containers, 25% up to 16GiB for VMs)")
    String memoryLimit;

    @Option(name = "disk", description = "Disk size limit (default: adaptive)")
    String diskLimit;

    @Option(name = "no-start", description = "Don't start the instance after creation", hasValue = false)
    boolean noStart;

    @Option(name = "shell", description = "Open a plain shell instead of running the default action", hasValue = false)
    boolean shell;

    @OptionList(name = "account",
            description = "Credential account to use, as <namespace>=<account> "
                    + "(e.g. claude=work). Repeatable; overrides the template's choice.")
    List<String> accounts;

    private IncusClient incus;

    @Override
    protected CommandResult doExecute() throws Exception {
        this.incus = RuntimeServices.incus();

        var resolvedSource = resolveSource();
        if (resolvedSource == null) return CommandResult.valueOf(1);

        if (airgap && proxyOnly) {
            System.err.println("Error: --airgap and --proxy-only are mutually exclusive.");
            return CommandResult.valueOf(1);
        }
        var networkMode = airgap ? NetworkMode.AIRGAP
                : proxyOnly ? NetworkMode.PROXY_ONLY : NetworkMode.FULL;
        Boolean kvmChoice = kvm ? Boolean.TRUE : noKvm ? Boolean.FALSE : null;
        var request = new BranchFlow.Request(resolvedSource, name, gui, kvmChoice, networkMode,
                inbox, cpuLimit, memoryLimit, diskLimit, accounts, !noStart, Map.of());

        BranchFlow.Preflight preflight;
        InstanceLifecycle.RuntimeConfig prefetched;
        try {
            preflight = BranchFlow.preflight(incus, request);
            prefetched = BranchFlow.create(incus, preflight);
        } catch (BranchFlow.BranchException e) {
            if (!e.reported()) System.err.println("Error: " + e.getMessage());
            return CommandResult.valueOf(1);
        }

        BuildOutput.success(name + " is ready.");
        if (noStart) return CommandResult.SUCCESS;

        var shellPrep = prefetched.toShellPrep();
        if (!shell) {
            var defaultCmd = resolveDefaultCommand(resolvedSource, preflight.defs());
            if (defaultCmd != null) {
                shellPrep = shellPrep.withActionCommand(defaultCmd);
            }
        }
        var menu = new ActionResolver(incus, RuntimeServices.toolDefLoader(),
                RuntimeServices.toolSetups(), preflight.defs())
                .shellMenu(name, prefetched.templateName(), prefetched.workdir());
        incus.interactiveShell(name, "agentuser", shellPrep, menu);
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

    private String resolveDefaultCommand(String source, Map<String, ImageDef> defs) {
        var templateName = source;
        if (!defs.containsKey(templateName)) {
            var profile = incus.configGet(source, Metadata.PROFILE);
            if (profile != null && !profile.isEmpty()) {
                templateName = profile;
            }
        }

        var resolver = new ActionResolver(incus, RuntimeServices.toolDefLoader(),
                RuntimeServices.toolSetups(), defs);
        var installedTools = resolver.collectInstalledTools(source, templateName);
        var repos = resolver.collectRepos(templateName);
        var action = resolver.findDefaultAction(name, templateName, installedTools, repos);
        if (action.isEmpty()) return null;

        return action.get().shellCommand(null).orElse(null);
    }
}
