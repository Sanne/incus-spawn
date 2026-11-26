package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.GuiBridge;
import dev.incusspawn.lifecycle.HostCommands;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PROTOTYPE: {@code isx gui <instance> [-- <command> [args...]]} shows a running instance's
 * Wayland windows on this computer through waypipe, with no host socket and no GPU in the guest.
 * It is how a Mac shows them at all (through Cocoa-Way); see {@link GuiBridge} and
 * design/macos-and-vms.md "GUI on macOS: the waypipe prototype", which lists every shortcut taken.
 *
 * <p>With a command it runs that command and ends with it. Without one it holds a display open
 * until interrupted, which the instance's new login shells use.
 */
@CommandDefinition(
        name = "gui",
        description = "Experimental: show an instance's GUI windows on this computer through waypipe",
        generateHelp = true
)
public class GuiCommand extends BaseCommand {

    /** isx's exit code when it fails before the command runs, as {@code isx exec}'s. */
    static final int ISX_FAILED = 125;

    @Arguments(completer = Completers.Instance.class,
            description = "The instance; then, to run one application, -- and its command")
    List<String> args;

    @Override
    protected CommandResult doExecute() throws Exception {
        return run(RuntimeServices.incus(), HostCommands.SYSTEM, System.getenv());
    }

    CommandResult run(IncusClient incus, HostCommands host, Map<String, String> env) throws Exception {
        if (args == null || args.isEmpty()) {
            BuildOutput.error("name an instance.",
                    "Usage: isx gui <instance> [-- <command> [args...]]",
                    "Example: isx gui my-box -- firefox");
            return CommandResult.valueOf(ISX_FAILED);
        }
        var name = args.getFirst();
        var command = args.subList(1, args.size());
        var shown = OutputFormat.oneLine(name);

        if (!host.onPath("waypipe")) {
            BuildOutput.error("waypipe is not installed on this computer.",
                    "On a Mac: brew install J-x-Z/tap/cocoa-way J-x-Z/tap/waypipe-darwin",
                    "On Linux: install your distribution's waypipe package.");
            return CommandResult.valueOf(ISX_FAILED);
        }
        var display = GuiBridge.hostDisplay(env);
        if (display == null) {
            BuildOutput.error("found no Wayland compositor on this computer to show the windows.",
                    "On a Mac: start Cocoa-Way, then run this again.",
                    "If it is running, set COCOA_WAY_RUNTIME_DIR to the directory of its wayland-* socket.");
            return CommandResult.valueOf(ISX_FAILED);
        }

        String workdir;
        try {
            var instance = incus.instanceMetadataOrThrow(name);
            if (instance == null) {
                BuildOutput.error("no instance named '" + shown + "' found.", "Run 'isx list' to see available instances.");
                return CommandResult.valueOf(ISX_FAILED);
            }
            if (!"Running".equals(instance.path("status").asText(""))) {
                BuildOutput.error(shown + " is not running.", "Start it with 'isx start " + shown + "'.");
                return CommandResult.valueOf(ISX_FAILED);
            }
            var config = instance.path("config");
            if (command.isEmpty() && "true".equals(config.pathText(Metadata.GUI_ENABLED, ""))) {
                BuildOutput.error(shown + " already has GUI passthrough, so its shells have a display.",
                        "To show one application through waypipe as well: isx gui " + shown + " -- <command>");
                return CommandResult.valueOf(ISX_FAILED);
            }
            if (!SshKeyManager.hasHostEntry(name, config.pathText(Metadata.STATIC_IP, ""))) {
                BuildOutput.error("isx has no SSH entry for " + shown + ", which the windows travel through.",
                        "Open a shell in it once with 'isx shell " + shown + "', then run this again.");
                return CommandResult.valueOf(ISX_FAILED);
            }
            workdir = GuestProvisioning.expandHome(config.pathText(Metadata.WORKDIR, ""));

            var prepared = incus.shellExec(name, "sh", "-c", GuiBridge.prepareScript());
            if (prepared.exitCode() == GuiBridge.NO_WAYPIPE) {
                BuildOutput.error("waypipe is not installed in " + shown + ".",
                        "Install it with: isx exec " + shown + " -- sudo dnf install -y waypipe",
                        "To have it in every instance, add waypipe to the template's packages.");
                return CommandResult.valueOf(ISX_FAILED);
            }
            prepared.assertSuccess("could not prepare " + shown + " for GUI windows");
            // A hold that ended without its cleanup (isx killed) leaves its socket, and waypipe
            // refuses to bind over it: the newest hold takes the display, ending an older one.
            if (command.isEmpty()) incus.shellExec(name, "sh", "-c", GuiBridge.cleanupScript(GuiBridge.HELD_DISPLAY));
        } catch (RuntimeException e) {
            BuildOutput.abandonStep();
            BuildOutput.error("could not set up GUI windows for " + shown + ": " + OutputFormat.oneLine(failure(e)));
            return CommandResult.valueOf(ISX_FAILED);
        }

        var guestDisplay = command.isEmpty() ? GuiBridge.HELD_DISPLAY : GuiBridge.runDisplay(runId());
        var argv = GuiBridge.waypipeCommand("waypipe", Environment.sshConfigFile().toAbsolutePath(), name,
                guestDisplay, GuiBridge.guestScript(workdir, command));
        var builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.environment().put("XDG_RUNTIME_DIR", display.runtimeDir());
        builder.environment().put("WAYLAND_DISPLAY", display.name());

        if (command.isEmpty()) {
            BuildOutput.note("Showing the GUI windows of " + shown + " on this computer. Press Ctrl+C to stop.");
            BuildOutput.note("A shell opened in it from now on has the display: run an application there.");
        }
        Process process;
        try {
            process = builder.start();
            process.getOutputStream().close(); // the guest command reads no input
        } catch (IOException e) {
            BuildOutput.error("could not start waypipe: " + OutputFormat.oneLine(failure(e)));
            return CommandResult.valueOf(ISX_FAILED);
        }
        // Ending the host's waypipe leaves the guest's running (no terminal carries a hangup), so
        // every exit, an interrupt included, ends the guest's side by name.
        Runnable end = () -> {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            try {
                incus.shellExec(name, "sh", "-c", GuiBridge.cleanupScript(guestDisplay));
            } catch (RuntimeException e) {
                // The instance stopped or Incus is gone: nothing is left running to end
            }
        };
        var hook = new Thread(end, "isx-gui-end");
        Runtime.getRuntime().addShutdownHook(hook);
        int exit;
        try {
            // What waypipe, ssh and the application print is the guest's: one scrubbed note a line.
            try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) BuildOutput.note(OutputFormat.oneLine(line));
            } catch (IOException e) {
                // The pipe closed under the read: the process is ending
            }
            exit = process.waitFor();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
                end.run();
            } catch (IllegalStateException e) {
                // The JVM is already shutting down, and the hook is running
            }
        }
        return CommandResult.valueOf(exit);
    }

    /** A run id no other run in the guest has had, as {@code isx exec}'s. */
    private static String runId() {
        return ProcessHandle.current().pid() + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1);
    }
}
