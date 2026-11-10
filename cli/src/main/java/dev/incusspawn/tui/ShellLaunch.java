package dev.incusspawn.tui;

import dev.incusspawn.command.InstancePrep;
import dev.incusspawn.command.McpClientGrant;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.ShellSession;
import dev.incusspawn.lifecycle.ZmxSocketForward;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.lifecycle.RuntimeSetup;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ShellMenu;
import static dev.incusspawn.tui.UsageFormat.bar;

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
                        BuildOutput.windowTitle("⚠ " + info.authRemediationHint() + " — " + baseTitle);
                    } else if (!isError && wasError) {
                        BuildOutput.windowTitle(baseTitle);
                    }
                    wasError = isError;
                } catch (Exception ignored) {}
            }
        }, "auth-title-monitor");
        thread.setDaemon(true);
        thread.start();
        return thread;
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
        var request = tui.branch.request(name);
        var source = request.source();

        BranchFlow.Preflight preflight;
        RuntimeSetup.RuntimeConfig prefetched;
        // Held through the branch's start, so a rebuild cannot swap the template away meanwhile (#1212).
        // The bound output: the TUI has released the terminal for the branch and the shell after it.
        // The shell draws over what the branch printed; the TUI's binding keeps its warnings in the log.
        try (var held = TemplateLock.reading(source, BuildOutput.bound())) {
            preflight = BranchFlow.preflight(tui.incus, request, tui.imageDefs);
            prefetched = BranchFlow.create(tui.incus, preflight);
        }

        // As isx branch --mcp-client does: the terminal is released, so the user sees what it found
        if (preflight.request().grantsMcpClient()) {
            McpClientGrant.reportScope(McpClientGrant.scope(preflight));
            McpClientGrant.check(tui.incus, name);
        }
        BuildOutput.success(name + " ready");
        var shellPrep = prefetched.toShellPrep();
        var defaultCmd = new ActionResolver(tui.incus, tui.toolDefLoader, tui.cdiTools, preflight.defs())
                .defaultCommandForBranch(preflight.template(), preflight.sourceInstance());
        if (defaultCmd != null) {
            shellPrep = shellPrep.withActionCommand(defaultCmd);
        }
        tui.incus.interactiveShell(name, "agentuser", shellPrep, shellMenu(name, shellPrep));
    }

    /**
     * The repairs before a shell, through {@link InstancePrep#repairBeforeUse} as {@code isx shell}
     * makes them. Runs on the TUI's own screen, where printing would draw over it: a warning (a
     * mount dropped because its host directory is gone, #852; spoofing protection refused; an
     * unusable allocation lock) goes to the warning log, which the status line announces when
     * the shell returns to the TUI, and progress goes nowhere, since nothing can render until
     * this returns.
     */
    void repairBeforeUse(String name, MachineType machineType) {
        InstancePrep.repairBeforeUse(tui.incus, name, machineType, new InstancePrep.Notices(tui.drawing,
                (title, detail) -> tui.statusMessage = title + ": " + detail));
    }

    void shellInto(ActionContext target) {
        shellInto(target, null, false, ShellSession.DEFAULT);
    }

    /** A login shell in {@code session} rather than the instance's default one (#2059). */
    void shellInto(ActionContext target, ShellSession session) {
        shellInto(target, null, false, session);
    }

    void shellInto(ActionContext target, String commandOverride, boolean commandOnly) {
        shellInto(target, commandOverride, commandOnly, ShellSession.DEFAULT);
    }

    /**
     * A shell running {@code commandOverride} instead of the login shell when not null; with
     * {@code commandOnly} outside the instance's tmux or zmx session, which would otherwise be
     * entered instead (a joined task's conversation, #1509).
     */
    private void shellInto(ActionContext target, String commandOverride, boolean commandOnly, ShellSession session) {
        var name = target.name();
        var machineType = target.machineType();
        // Read after repairBeforeUse, so a VM it just reassigned is seen to owe its file
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
        // Runs after the TUI has released the terminal, so the reconcile prints its own warnings,
        // and the TUI's binding keeps a copy in the log: a failed one would otherwise scroll away
        // under the shell.
        InstancePrep.readyForUse(tui.incus, name, instance, machineType, BuildOutput.bound());
        checkGuiHealth(name);
        BuildOutput.step("Connecting to " + name);
        var titleMonitor = startAuthTitleMonitor(name);
        try {
            var prep = IncusClient.ShellPrep.from(tui.incus, name);
            if (commandOverride != null) {
                prep = commandOnly ? prep.withCommandOnly(commandOverride) : prep.withActionCommand(commandOverride);
            } else {
                prep = prep.withSession(session);
            }
            tui.incus.interactiveShell(name, "agentuser", prep, shellMenu(name, prep));
            // A session the shell made, as a new one the guest named, now has a socket to link
            ZmxSocketForward.relinkAfterShell(name);
        } finally {
            titleMonitor.interrupt();
        }
    }

    private void checkGuiHealth(String name) {
        // The released terminal: the TUI's binding keeps the warning in its log
        GuiPassthrough.checkGuiHealth(tui.incus, name, BuildOutput.bound());
    }
}
