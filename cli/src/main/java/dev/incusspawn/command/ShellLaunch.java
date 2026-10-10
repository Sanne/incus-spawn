package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.StaticIpAllocator;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.lifecycle.ZmxSocketForward;
import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ShellMenu;
import com.fasterxml.jackson.databind.JsonNode;
import static dev.incusspawn.command.UsageFormat.bar;

/**
 * Opening a shell from the TUI: the repairs an instance may owe first (a static IP from an old
 * bridge, a stale CA, resolv.conf), the start, the shell itself with its auth-error title, and
 * creating a branch from the branch dialog before its shell. Everything here but the repairs runs
 * after {@link Tui} has released the terminal.
 */
final class ShellLaunch {

    private final Tui tui;

    ShellLaunch(Tui tui) {
        this.tui = tui;
    }

    private Thread startAuthTitleMonitor(String containerName) {
        var baseTitle = "isx:" + containerName;
        String healthAddr;
        try {
            healthAddr = ProxyHealthCheck.healthAddress(tui.incus);
        } catch (Exception e) {
            return Thread.currentThread(); // no-op: interrupt is harmless on current thread
        }
        var thread = new Thread(() -> {
            boolean wasError = false;
            while (!Thread.interrupted()) {
                try { Thread.sleep(Tui.PROXY_AUTH_CHECK_INTERVAL_MS); }
                catch (InterruptedException e) { break; }
                try {
                    var info = ProxyHealthCheck.fetchProxyInfo(healthAddr, 500);
                    boolean isError = info != null && info.hasAuthError();
                    if (isError && !wasError) {
                        setTerminalTitle("⚠ " + info.authRemediationHint() + " — " + baseTitle);
                    } else if (!isError && wasError) {
                        setTerminalTitle(baseTitle);
                    }
                    wasError = isError;
                } catch (Exception ignored) {}
            }
        }, "auth-title-monitor");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void setTerminalTitle(String title) {
        System.out.print("\033]0;" + title + "\007"); // raw ANSI: window title, set while the TUI owns the terminal
        System.out.flush();
    }

    private ShellMenu shellMenu(String instanceName, IncusClient.ShellPrep prep) {
        // Read fresh, as isx shell does, not from the list's cache: its entry can predate the
        // instance (a branch just made from the dialog) or its start (no IP yet), and its parent
        // is the direct one where the bar shows the leaf template.
        return new ActionResolver(tui.incus, tui.toolDefLoader, tui.cdiTools, tui.imageDefs)
                .shellMenu(instanceName, prep.templateName(), prep.workdir());
    }

    /**
     * Branch through {@link BranchFlow}, as {@code isx branch} does, so a TUI branch gets the same
     * account selection, proxy refresh, CA and identity repairs (#800). Only the inputs (the
     * modal's fields) and the shell that follows are the TUI's own.
     */
    void createBranchFromModal(String name) {
        var source = tui.branchSourceName;
        var request = tui.branch.request(name);

        BranchFlow.Preflight preflight;
        InstanceLifecycle.RuntimeConfig prefetched;
        // Held through the branch's start, so a rebuild cannot swap the template away meanwhile (#1212).
        // Plain stdout: the TUI has released the terminal for the branch and the shell after it.
        try (var held = TemplateLock.reading(source, System.out::println)) {
            preflight = BranchFlow.preflight(tui.incus, request, tui.imageDefs);
            prefetched = BranchFlow.create(tui.incus, preflight);
        }

        BuildOutput.success(name + " is ready.");
        var shellPrep = prefetched.toShellPrep();
        var defaultCmd = new ActionResolver(tui.incus, tui.toolDefLoader, tui.cdiTools, preflight.defs())
                .defaultCommandForBranch(preflight.template(), preflight.sourceInstance());
        if (defaultCmd != null) {
            shellPrep = shellPrep.withActionCommand(defaultCmd);
        }
        tui.incus.interactiveShell(name, "agentuser", shellPrep, shellMenu(name, shellPrep));
        System.out.println();
    }

    void fixStaticIpIfNeeded(String name, MachineType machineType) {
        if (!"Stopped".equalsIgnoreCase(tui.incus.getInstanceStatus(name))) return;
        // Runs on the TUI's own screen: printing would draw over it. A warning (spoofing
        // protection refused, an unusable allocation lock) goes to the warning log; progress
        // does not, since nothing can render until this returns.
        var output = new StaticIpAllocator.Output(msg -> {}, tui.warningLog::add);
        try {
            if (InstanceLifecycle.fixStaticIpIfNeeded(tui.incus, name, output, machineType)) {
                tui.statusMessage = "Static IP reassigned to current bridge subnet";
            }
        } catch (Exception ignored) {
        }
    }

    void fixResolvConfIfNeeded(String name) {
        if ("Stopped".equalsIgnoreCase(tui.incus.getInstanceStatus(name))) return;
        try {
            ProxyConfig.fixResolvConfIfNeeded(tui.incus, name);
        } catch (Exception ignored) {
        }
    }

    void fixCaMismatchIfNeeded(String containerName, MachineType machineType) {
        JsonNode started = null;
        if ("Stopped".equalsIgnoreCase(tui.incus.getInstanceStatus(containerName))) {
            // Runs on the TUI's own screen, where stderr would be drawn over: a mount dropped
            // because its host directory is gone must not go unannounced (#852), so it goes to
            // the warning log, which the status line announces when the shell returns to the TUI.
            // Through InstanceLifecycle, as isx shell's start is: the prep re-arms IP spoofing
            // protection (#905), the start falls back where the host cannot enforce it, and the
            // instance gets its new secret (#934).
            started = InstanceLifecycle.startForUse(tui.incus, containerName, machineType, tui.warningLog::add);
        }
        // The start's own read of the instance, when there was one: no need to read it again
        CertificateAuthority.fixContainerCaIfNeeded(tui.incus, containerName, started);
    }

    void shellInto(ActionContext target) {
        shellInto(target, null);
    }

    void shellInto(ActionContext target, String commandOverride) {
        var name = target.name();
        var machineType = target.machineType();
        // Read after fixStaticIpIfNeeded, so a VM it just reassigned is seen to owe its file
        var instance = tui.incus.instanceMetadata(name);
        var status = instance.path("status").asText("");
        // An empty status means any failed lookup, not just a missing instance, so confirm before
        // giving up: a daemon hiccup must not cancel a shell on an instance that's still there.
        if (status.isEmpty() && !tui.incus.exists(name)) {
            // Deleted between the TUI's check and now: report it back in the TUI (which reloads
            // on re-entry) instead of failing on a start or exec against a missing instance.
            tui.statusMessage = name + " no longer exists";
            return;
        }
        // Runs after the TUI has released the terminal, so plain stdout is safe here.
        InstanceLifecycle.ensureReady(tui.incus, name, instance, machineType, System.out::println);
        ZmxSocketForward.ensureSymlink(name);
        checkGuiHealth(name);
        System.out.println("Connecting to " + name + "...\n");
        var titleMonitor = startAuthTitleMonitor(name);
        try {
            var prep = IncusClient.ShellPrep.from(tui.incus, name);
            if (commandOverride != null) prep = prep.withActionCommand(commandOverride);
            tui.incus.interactiveShell(name, "agentuser", prep, shellMenu(name, prep));
        } finally {
            titleMonitor.interrupt();
        }
        System.out.println();
    }

    private void checkGuiHealth(String name) {
        GuiPassthrough.checkGuiHealth(tui.incus, name);
    }
}
