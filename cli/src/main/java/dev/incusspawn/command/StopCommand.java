package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.HostIntegration;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;

import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * {@code isx stop <name>}: stop a running instance (#1720), for a script or an orchestrator
 * suspending a workspace. As the TUI and {@code isx mcp}'s {@code stop_instance} do it: under the
 * instance's lock and its {@link Metadata#OP_STOPPING} mark, so another isx process sees the stop
 * in flight, waiting for another isx process as {@code isx start} does. One already stopped is
 * success, so a repeated suspend is harmless.
 */
@CommandDefinition(
        name = "stop",
        description = "Stop a running instance",
        generateHelp = true
)
public class StopCommand extends BaseCommand {

    @Argument(completer = Completers.Instance.class, description = "Name of the instance to stop", required = true)
    String name;

    @Override
    protected CommandResult doExecute() throws Exception {
        return stop(RuntimeServices.incus(), RuntimeServices.lockManager());
    }

    /** How long a stop waits for another isx process holding the instance, as a start does. */
    Duration wait = InstanceStart.WAIT;

    CommandResult stop(IncusClient incus, InstanceLockManager locks) throws InterruptedException {
        var shown = OutputFormat.oneLine(name);
        // Before a lock file is made for the name, as isx destroy checks
        if (!incus.exists(name)) {
            BuildOutput.error("no instance named '" + shown + "' found.");
            return CommandResult.valueOf(1);
        }
        try (var held = InstanceStart.lock(locks, name, Metadata.OP_STOPPING, BuildOutput.bound(), wait)) {
            BuildOutput.stepStart("Stopping " + shown);
            boolean stopped;
            // The status as the marked read saw it: no request of its own
            var status = new java.util.concurrent.atomic.AtomicReference<String>("");
            try {
                stopped = InstanceDestroyer.stopMarkedIf(incus, name, instance -> {
                    status.set(instance.path("status").asText(""));
                    return "Running".equals(status.get());
                    // A tool that would start it again is told first, under the mark (#2041)
                }, () -> HostIntegration.stoppingAll(name, BuildOutput.bound()),
                        () -> HostIntegration.stopFailedAll(incus, name, BuildOutput.bound()));
            } catch (RuntimeException e) {
                BuildOutput.stepBreak();
                BuildOutput.error("could not stop " + shown + ": " + failure(e));
                return CommandResult.valueOf(1);
            }
            if (!stopped && !"Stopped".equals(status.get())) {
                // A frozen guest cannot shut down, and isx does not decide to force it
                BuildOutput.stepBreak();
                BuildOutput.error(shown + " is " + OutputFormat.oneLine(status.get()).toLowerCase(java.util.Locale.ROOT)
                        + ", not running, so it cannot shut down.", "Resume it first, then stop it.");
                return CommandResult.valueOf(1);
            }
            BuildOutput.stepDone();
            if (!stopped) {
                BuildOutput.success(shown + " is already stopped");
                return CommandResult.SUCCESS;
            }
        } catch (InstancePrep.Refused e) {
            InstancePrep.reportRefusal(e.getMessage());
            return CommandResult.valueOf(1);
        } catch (UncheckedIOException e) {
            BuildOutput.error("could not lock " + shown + ": " + failure(e.getCause()));
            return CommandResult.valueOf(1);
        }
        BuildOutput.success("Stopped " + shown);
        return CommandResult.SUCCESS;
    }
}
