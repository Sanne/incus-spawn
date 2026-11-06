package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.McpScope;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.RuntimeSetup;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ShellMenu;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import java.nio.file.Path;
import java.util.ArrayList;
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

    @Option(name = "from", completer = Completers.Source.class, description = "Source instance to branch from (auto-detected from cwd if omitted)")
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

    @Option(name = "airgap", description = "No network devices (masks NICs and outbound proxies, refuses pci/usb passthrough)", hasValue = false)
    boolean airgap;

    @Option(name = "proxy-only", description = "Restrict network to host proxy only (Claude + GitHub via proxy)", hasValue = false)
    boolean proxyOnly;

    @Option(name = "inbox", completer = Completers.HostPath.class, description = "Host directory to mount read-only at /home/agentuser/inbox")
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
            + ProxyConfig.MCP_DOMAIN + "/mcp (a coordinating agent); the default for a template whose coordinator: block says so"
            + " (isx-control-capability, true when control-over lists templates). Never inherited by its copies.",
            hasValue = false)
    boolean mcpClient;

    @Option(name = "no-mcp-client", description = "Do not let this instance drive isx over MCP, even if the template"
            + " makes coordinators by default", hasValue = false)
    boolean noMcpClient;

    @Option(name = "owner", description = "The external tool that controls this instance's lifecycle,"
            + " such as orca. Never inherited by its copies.")
    String owner;

    @Option(name = "owner-id", description = "The owner's own handle for this instance, such as an"
            + " orchestrator's workspace id (needs --owner)")
    String ownerId;

    @OptionList(name = "account", completer = Completers.Account.class,
            description = "Credential account to use, as <namespace>=<account> "
                    + "(e.g. claude=work). Repeatable; overrides the template's choice.")
    List<String> accounts;

    // Output for scripts (#1036): see OutputFormat for what plain and json promise.
    @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), or plain or json to print"
            + " only the new instance's name, json with how to reach it (progress to stderr, no shell opened)")
    String format;

    private IncusClient incus;

    /** A branch {@link #create} made, and what opening its shell needs. */
    private record Created(String source, BranchFlow.Preflight preflight,
                           RuntimeSetup.RuntimeConfig prefetched, McpScope mcpScope) {}

    @Override
    protected CommandResult doExecute() throws Exception {
        var outputFormat = OutputFormat.parse(format);
        this.incus = RuntimeServices.incus();

        if (outputFormat != OutputFormat.TABLE) {
            // A script captures the new name, so stdout holds only that, and no shell is opened.
            var created = withStdoutOnStderr(this::create);
            if (created == null) return CommandResult.valueOf(1);
            // plain stays the name alone, which name=$(isx branch ... --format=plain) captures
            outputFormat.printOne(BuildOutput.results(), outputFormat == OutputFormat.JSON
                    ? record(name, owner, ownerId, reach(name, created.preflight().sourceInstance()),
                            created.preflight().request().grantsMcpClient(), created.mcpScope())
                    : record(name));
            return CommandResult.SUCCESS;
        }

        var created = create();
        if (created == null) return CommandResult.valueOf(1);
        if (noStart) return CommandResult.SUCCESS;
        if (!IncusClient.hasTerminal()) {
            // The branch is done; only the shell needs a terminal (#1027).
            BuildOutput.step("No terminal, so no shell opened. From a terminal: isx shell " + name);
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

    /**
     * The fields of {@code isx branch --format=json}: {@link #record(String)}'s, then who controls
     * the instance and how a program reaches it over SSH (#1720), so one call gives an
     * orchestrator what it needs, then whether it is a coordinator and what it may control (#1812;
     * null when mcp.yaml could not be read). Add to the end, never rename.
     */
    static Map<String, Object> record(String name, String owner, String ownerId, Reach reach,
                                      boolean mcpClient, McpScope mcpScope) {
        var record = record(name);
        record.put("owner", owner);
        record.put("owner_id", ownerId);
        var ssh = reach.ssh();
        record.put("ssh_host", ssh == null ? null : name);
        record.put("ssh_user", ssh == null ? null : SshKeyManager.USER);
        record.put("ssh_port", ssh == null ? null : 22);
        record.put("ssh_identity_file", ssh == null ? null : ssh.identityFile());
        record.put("ssh_proxy_command", ssh == null ? null : ssh.proxyCommand());
        record.put("project_root", reach.projectRoot());
        record.put("mcp_client", mcpClient);
        record.put("mcp_scope", mcpScope == null ? null : mcpScope.templates());
        return record;
    }

    /**
     * How a program reaches a branch of {@code source}: over SSH when its template set up sshd (the
     * key is made on the first start of such an instance, a --no-start branch's included), through
     * the ProxyCommand that starts a stopped instance first, and
     * in the directory a shell opens in. Read from the source as preflight read it, which the copy
     * carries: no request of its own.
     */
    record Reach(Ssh ssh, String projectRoot) {}

    /** The SSH half of {@link Reach}: null when the instance cannot be reached over SSH. */
    record Ssh(String identityFile, String proxyCommand) {}

    static Reach reach(String name, io.hyperfoil.tools.jjq.value.JqValue source) {
        return reach(name, source, BranchCommand::ensureSshKey);
    }

    /** {@link #reach(String, io.hyperfoil.tools.jjq.value.JqValue)}, with what makes the managed key exist. */
    static Reach reach(String name, io.hyperfoil.tools.jjq.value.JqValue source, java.util.function.BooleanSupplier keyExists) {
        // The key named is the one a start authorizes in the instance; it must exist when it is named,
        // a --no-start branch's included, or the field would point at nothing
        var ssh = RuntimeSetup.hasSshCapability(source) && keyExists.getAsBoolean()
                ? new Ssh(dev.incusspawn.Environment.sshKeyFile().toString(), SshKeyManager.proxyCommand(name, true))
                : null;
        var workdir = source.path("config").pathText(Metadata.WORKDIR, "");
        return new Reach(ssh, workdir.isBlank() ? ShellMenu.DEFAULT_WORKDIR : workdir);
    }

    /** Make isx's SSH key exist; false, said as a warning, when it cannot be made. */
    private static boolean ensureSshKey() {
        try {
            SshKeyManager.ensureKeyPairExists();
            return true;
        } catch (RuntimeException e) {
            BuildOutput.warn("no SSH fields in the output: " + e.getMessage());
            return false;
        }
    }

    /** What the copy request stamps for {@code --owner} and {@code --owner-id}; BranchFlow adds the MCP grant. */
    private Map<String, String> stamps() {
        var stamps = new LinkedHashMap<String, String>();
        if (owner != null) stamps.put(Metadata.OWNER, owner);
        if (ownerId != null) stamps.put(Metadata.OWNER_ID, ownerId);
        return stamps;
    }

    /** Create (and unless {@code --no-start}, start) the branch; {@code null} once the error is reported. */
    private Created create() {
        var ownerProblem = Metadata.ownerProblem(owner, ownerId);
        if (ownerProblem != null) {
            BuildOutput.error(ownerProblem);
            return null;
        }
        var named = source != null ? source : detectSource();
        if (named == null) return null;
        // From the lookup through the branch's start, so a rebuild cannot swap the template away meanwhile (#1212)
        try (var held = TemplateLock.reading(named, BuildOutput.bound())) {
            return create(named);
        } catch (HostLock.HostLockException e) {
            BuildOutput.error(failure(e));
            return null;
        }
    }

    private Created create(String named) {
        var resolvedSource = resolveSource(named);
        if (resolvedSource == null) return null;

        if (airgap && proxyOnly) {
            BuildOutput.error("--airgap and --proxy-only are mutually exclusive.");
            return null;
        }
        var networkMode = airgap ? NetworkMode.AIRGAP
                : proxyOnly ? NetworkMode.PROXY_ONLY : NetworkMode.FULL;
        var unreachable = mcpClient ? BranchFlow.mcpClientUnreachable(networkMode) : null;
        if (unreachable != null) {
            BuildOutput.error(unreachable + "; --mcp-client needs --proxy-only or full network.");
            return null;
        }
        if (gui && noGui) {
            BuildOutput.error("--gui and --no-gui are mutually exclusive.");
            return null;
        }
        if (mcpClient && noMcpClient) {
            BuildOutput.error("--mcp-client and --no-mcp-client are mutually exclusive.");
            return null;
        }
        Boolean guiChoice = gui ? Boolean.TRUE : noGui ? Boolean.FALSE : null;
        Boolean kvmChoice = kvm ? Boolean.TRUE : noKvm ? Boolean.FALSE : null;
        var request = new BranchFlow.Request(resolvedSource, name, guiChoice, kvmChoice, networkMode,
                inbox, cpuLimit, memoryLimit, diskLimit, accounts, !noStart,
                stamps(), mcpClient ? Boolean.TRUE : noMcpClient ? Boolean.FALSE : null);

        BranchFlow.Preflight preflight;
        RuntimeSetup.RuntimeConfig prefetched;
        try {
            preflight = BranchFlow.preflight(incus, request);
            prefetched = BranchFlow.create(incus, preflight);
        } catch (BranchFlow.BranchException e) {
            if (!e.reported()) BuildOutput.error(failure(e));
            return null;
        }

        McpScope mcpScope = null;
        if (preflight.request().grantsMcpClient()) {
            mcpScope = McpClientGrant.scope(preflight);
            McpClientGrant.reportScope(mcpScope);
            if (!noStart) McpClientGrant.check(incus, name);
        }
        BuildOutput.success(name + " ready");
        return new Created(resolvedSource, preflight, prefetched, mcpScope);
    }

    /** {@code named}, the source given or {@linkplain #detectSource detected}, if it exists. */
    private String resolveSource(String named) {
        if (!incus.exists(named)) {
            BuildOutput.error((source != null ? "source instance" : "auto-detected source")
                    + " '" + named + "' does not exist.");
            return null;
        }
        if (source == null) BuildOutput.step("Auto-detected source: " + named);
        return named;
    }

    /** The source the incus-spawn.yaml in the working directory names, or null once that is reported. */
    private String detectSource() {
        var projectConfig = ProjectConfig.findInDirectory(Path.of("."));
        if (projectConfig != null && projectConfig.getName() != null) {
            return projectConfig.getName();
        }

        List<String> built = null;
        try {
            built = InstanceListing.builtTemplates(incus.listJsonConfig()).stream()
                    .map(TemplateStaleness.Built::name).toList();
        } catch (dev.incusspawn.incus.IncusException e) {
            // The error stands without the list: it is advice, not the failure
        }
        reportNoSource(built);
        return null;
    }

    /**
     * Why there is nothing to branch from, and what there is: the built templates, when Incus could
     * say ({@code built} is null when it could not), else how to build the first (#1441).
     */
    static void reportNoSource(List<String> built) {
        var details = new ArrayList<String>();
        details.add("Usage: isx branch <name> --from <template>");
        if (built != null) {
            details.add(built.isEmpty()
                    ? "No template is built yet: 'isx build tpl-minimal' builds the base one, and 'isx templates list' lists the others."
                    : "Built templates: " + String.join(", ", built.stream().map(OutputFormat::oneLine).toList()) + ".");
        }
        BuildOutput.error("no --from given, and no incus-spawn.yaml (a project's definition) in this directory names a template.",
                details.toArray(String[]::new));
    }
}
