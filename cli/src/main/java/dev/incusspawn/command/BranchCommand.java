package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.RuntimeSetup;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.proxy.McpClientCheck;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    @Option(name = "gui", description = "Enable GUI passthrough (Wayland + GPU + audio); "
            + "the default for a gui: true container template, from a Wayland session", hasValue = false)
    boolean gui;

    @Option(name = "no-gui", description = "Disable GUI passthrough even if the template has gui: true", hasValue = false)
    boolean noGui;

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

    @Option(name = "cpu", description = "CPU core limit (default: none for containers; for VMs host CPUs minus 2, at most 8, and on a hybrid host at most its top tier's physical cores)")
    Integer cpuLimit;

    @Option(name = "memory", description = "Memory limit, e.g. '8GB' (default: 60% of host RAM for containers, 25% up to 16GiB for VMs)")
    String memoryLimit;

    @Option(name = "disk", description = "Disk size limit (default: adaptive)")
    String diskLimit;

    @Option(name = "no-start", description = "Don't start the instance after creation", hasValue = false)
    boolean noStart;

    @Option(name = "shell", description = "Open a plain shell instead of running the default action", hasValue = false)
    boolean shell;

    @Option(name = "mcp-client", description = "Let this instance drive isx over MCP, at https://"
            + ProxyConfig.MCP_DOMAIN + "/mcp (a coordinating agent). Never inherited by its copies.",
            hasValue = false)
    boolean mcpClient;

    @OptionList(name = "account",
            description = "Credential account to use, as <namespace>=<account> "
                    + "(e.g. claude=work). Repeatable; overrides the template's choice.")
    List<String> accounts;

    // Output for scripts (#1036): see OutputFormat for what plain and json promise.
    @Option(name = "format", description = "Output format: table (default), or plain or json to print"
            + " only the new instance's name (progress to stderr, no shell opened)")
    String format;

    private IncusClient incus;

    /** A branch {@link #create} made, and what opening its shell needs. */
    private record Created(String source, BranchFlow.Preflight preflight,
                           RuntimeSetup.RuntimeConfig prefetched) {}

    @Override
    protected CommandResult doExecute() throws Exception {
        var outputFormat = OutputFormat.parse(format);
        this.incus = RuntimeServices.incus();

        if (outputFormat != OutputFormat.TABLE) {
            // A script captures the new name, so stdout holds only that, and no shell is opened.
            var created = withStdoutOnStderr(this::create);
            if (created == null) return CommandResult.valueOf(1);
            outputFormat.printOne(System.out, record(name));
            return CommandResult.SUCCESS;
        }

        var created = create();
        if (created == null) return CommandResult.valueOf(1);
        if (noStart) return CommandResult.SUCCESS;
        if (!IncusClient.hasTerminal()) {
            // The branch is done; only the shell needs a terminal (#1027).
            System.out.println("No terminal, so no shell opened. From a terminal: isx shell " + name);
            return CommandResult.SUCCESS;
        }

        var preflight = created.preflight();
        var shellPrep = created.prefetched().toShellPrep();
        var resolver = new ActionResolver(incus, RuntimeServices.toolDefLoader(),
                RuntimeServices.toolSetups(), preflight.defs());
        if (!shell) {
            var defaultCmd = resolver.defaultCommandForBranch(preflight.template(), preflight.sourceInstance());
            if (defaultCmd != null) {
                shellPrep = shellPrep.withActionCommand(defaultCmd);
            }
        }
        var menu = resolver.shellMenu(name, created.prefetched().templateName(), created.prefetched().workdir());
        incus.interactiveShell(name, "agentuser", shellPrep, menu);
        return CommandResult.SUCCESS;
    }

    /**
     * The fields of {@code isx branch --format=plain|json}: the new name alone, so that
     * {@code name=$(isx branch ... --format=plain)} captures it. Add to the end, never rename.
     */
    static Map<String, Object> record(String name) {
        var record = new LinkedHashMap<String, Object>();
        record.put("name", name);
        return record;
    }

    /** Create (and unless {@code --no-start}, start) the branch; {@code null} once the error is reported. */
    private Created create() {
        var named = source != null ? source : detectSource();
        if (named == null) return null;
        // From the lookup through the branch's start, so a rebuild cannot swap the template away meanwhile (#1212)
        try (var held = TemplateLock.reading(named, System.err::println)) {
            return create(named);
        } catch (HostLock.HostLockException e) {
            System.err.println("Error: " + e.getMessage());
            return null;
        }
    }

    private Created create(String named) {
        var resolvedSource = resolveSource(named);
        if (resolvedSource == null) return null;

        if (airgap && proxyOnly) {
            System.err.println("Error: --airgap and --proxy-only are mutually exclusive.");
            return null;
        }
        if (airgap && mcpClient) {
            System.err.println("Error: an --airgap instance cannot reach " + ProxyConfig.MCP_DOMAIN
                    + "; --mcp-client needs --proxy-only or full network.");
            return null;
        }
        var networkMode = airgap ? NetworkMode.AIRGAP
                : proxyOnly ? NetworkMode.PROXY_ONLY : NetworkMode.FULL;
        if (gui && noGui) {
            System.err.println("Error: --gui and --no-gui are mutually exclusive.");
            return null;
        }
        Boolean guiChoice = gui ? Boolean.TRUE : noGui ? Boolean.FALSE : null;
        Boolean kvmChoice = kvm ? Boolean.TRUE : noKvm ? Boolean.FALSE : null;
        var request = new BranchFlow.Request(resolvedSource, name, guiChoice, kvmChoice, networkMode,
                inbox, cpuLimit, memoryLimit, diskLimit, accounts, !noStart,
                mcpClient ? Map.of(Metadata.MCP_CALLER, Metadata.newMcpCallerGrant()) : Map.of());

        BranchFlow.Preflight preflight;
        RuntimeSetup.RuntimeConfig prefetched;
        try {
            preflight = BranchFlow.preflight(incus, request);
            prefetched = BranchFlow.create(incus, preflight);
        } catch (BranchFlow.BranchException e) {
            if (!e.reported()) System.err.println("Error: " + e.getMessage());
            return null;
        }

        if (mcpClient && !noStart) {
            // Asked the way the instance's Claude Code will ask, while the user is here to see it
            BuildOutput.stepStart("Checking isx mcp from " + name + "...");
            var reached = McpClientCheck.run(incus, name);
            if (reached.ok()) BuildOutput.stepDone("isx " + OutputFormat.oneLine(reached.version()) + ", " + reached.tools() + " tools");
            else BuildOutput.stepFail(BuildOutput.STEP_INDENT + "Warning: " + reached.describe(name));
        }
        BuildOutput.success(name + " is ready.");
        return new Created(resolvedSource, preflight, prefetched);
    }

    /** {@code named}, the source given or {@linkplain #detectSource detected}, if it exists. */
    private String resolveSource(String named) {
        if (!incus.exists(named)) {
            System.err.println("Error: " + (source != null ? "source instance" : "auto-detected source")
                    + " '" + named + "' does not exist.");
            return null;
        }
        if (source == null) System.out.println("Auto-detected source: " + named);
        return named;
    }

    /** The source the incus-spawn.yaml in the working directory names, or null once that is reported. */
    private String detectSource() {
        var projectConfig = ProjectConfig.findInDirectory(Path.of("."));
        if (projectConfig != null && projectConfig.getName() != null) {
            return projectConfig.getName();
        }

        System.err.println("Error: no --from specified and no incus-spawn.yaml found in current directory.");
        System.err.println("Usage: isx branch <name> --from <source-instance>");
        return null;
    }
}
