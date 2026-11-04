package dev.incusspawn.command;

import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.TerminalOutput;
import dev.incusspawn.util.TerminalStreams;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.mcp.StdioGuard;
import dev.incusspawn.tui.InstanceLockManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

@CommandDefinition(
        name = "ssh-proxy",
        description = "SSH ProxyCommand that tunnels through the Incus exec API",
        generateHelp = true
)
public class SshProxyCommand extends BaseCommand {

    @Argument(description = "Instance name", required = true)
    String instance;

    @Option(name = "start", hasValue = false,
            description = "Start the instance first if it is stopped, as isx shell does")
    boolean start;

    /** The start {@code --start} makes; {@link InstanceStart.Preparer#FOR_USE} but in tests. */
    InstanceStart.Preparer preparer = InstanceStart.Preparer.FOR_USE;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = dev.incusspawn.RuntimeServices.incus();

        if (!start) {
            if (!checkInstanceRunning(incus)) {
                return CommandResult.valueOf(1);
            }
            // ssh's connection: stdin and stdout are the SSH stream, and stderr is what ssh shows its user
            return CommandResult.valueOf(relay(incus, TerminalStreams.in(), TerminalStreams.out(), TerminalStreams.err()));
        }
        // A start prints progress, and stdout is the SSH stream: the guard takes the real stdin and
        // stdout for the connection and sends everything else isx says to stderr (#1720)
        var guard = StdioGuard.install();
        if (!ensureRunning(incus, dev.incusspawn.RuntimeServices.lockManager())) {
            return CommandResult.valueOf(1);
        }
        return CommandResult.valueOf(relay(incus, guard.protocolIn, guard.protocolOut, TerminalStreams.err()));
    }

    /**
     * Runs {@link #guestCommand} with the connection on {@code in} and {@code out}, and shows its
     * stderr on {@code err} as text: ssh passes a ProxyCommand's stderr to the terminal untouched,
     * and whatever runs in the instance decides it.
     */
    int relay(dev.incusspawn.incus.IncusClient incus, InputStream in, OutputStream out, PrintStream err) {
        var guestErr = new GuestStderr(err);
        try {
            return incus.execBidirectional(instance, 0, 0, "/", guestCommand(), in, out, guestErr);
        } finally {
            guestErr.close();  // a last line without a break, even when the exec fails
        }
    }

    /**
     * Whether the instance runs, starting a stopped one with {@code --start}: the ProxyCommand
     * {@code isx branch --format=json} hands an orchestrator (#1720), which reconnects to an
     * instance it believes awake after a host restart or a stop. The Host block a plain {@code ssh}
     * reads has no {@code --start}, so {@code ssh <name>} never starts anything. Why not is an
     * error: ssh shows the ProxyCommand's stderr, and stdout is the connection's stream.
     */
    boolean ensureRunning(IncusClient incus, InstanceLockManager locks) throws InterruptedException {
        if (!start) return checkInstanceRunning(incus);
        var shown = OutputFormat.oneLine(instance);
        try {
            // One read: whether it exists, runs, and holds this boot's secret
            var read = incus.instanceMetadataOrThrow(instance);
            if (read == null) {
                BuildOutput.error("instance '" + shown + "' not found or not accessible.");
                return false;
            }
            // Every connection runs this, git and scp included: one already up with this boot's secret,
            // which no isx process is starting or stopping, is used as it is. One Incus restored running
            // after a host reboot has an empty /run/isx, so it is prepared like a stopped one
            var running = "Running".equalsIgnoreCase(read.path("status").asText(""));
            if (running && !locks.isHeldByOther(instance) && InstanceLifecycle.readyForThisBoot(read)) return true;
            // Running but held: a start delivering its proofs, or a stop. Waiting out a stop must not
            // undo it, so an instance that was up when this connection came and is down once it may
            // act is refused, never started again.
            InstanceStart.start(incus, locks, instance, BuildOutput.bound(), !running ? preparer : (i, n, out) -> {
                if (!"Running".equalsIgnoreCase(i.getInstanceStatus(n))) {
                    throw new InstancePrep.Refused("'" + n + "' was stopped while this connection waited for it.", false);
                }
                preparer.prepare(i, n, out);
            }, InstanceStart.WAIT);
            return true;
        } catch (InstancePrep.Refused e) {
            if (!e.reported()) InstancePrep.reportRefusal(e.getMessage());
            return false;
        } catch (RuntimeException e) {
            BuildOutput.error("could not start '" + shown + "': " + OutputFormat.oneLine(failure(e)));
            return false;
        }
    }

    /** A socket listening on port 22 (hex {@code 0016}), as a line of {@code /proc/net/tcp} or {@code tcp6} shows it. */
    static final String SSHD_LISTENING = ":0016 [0-9A-F]*:0000 0A";
    /** At most how many seconds the guest waits for sshd to listen before connecting anyway. */
    static final int SSHD_WAIT_SECONDS = 20;

    /**
     * What the exec runs in the instance: the end of the ssh connection.
     * <p>
     * A start returns once the guest answers an exec, about a second before sshd listens, so an
     * immediate {@code ssh} would be refused (#1699). The wait is here rather than on every start:
     * only an SSH connection pays it. It has to happen before {@code nc} runs, since ssh has already
     * written its banner to stdin and a retried exec would lose it. The probe reads the kernel's
     * socket table, so it costs one {@code grep} when sshd is up and logs no connection in sshd.
     * It waits only while systemd is still starting the guest, which includes the moment right
     * after a start when {@code systemctl} cannot reach the system bus yet and prints nothing; a
     * guest without {@code systemctl} is not waited for. After that a stopped or failed sshd
     * will not come, and {@code nc} reports the refusal at once, as before. The deadline is wall
     * clock, since early in boot a {@code systemctl} call to a busy PID 1 can take far longer than
     * a pass's sleep; it is set on the first miss, so a ready instance forks no {@code date}.
     */
    static String[] guestCommand() {
        return new String[]{"sh", "-c",
                "end=; until grep -qs '" + SSHD_LISTENING + "' /proc/net/tcp6 /proc/net/tcp; do"
                        + " case $(systemctl is-system-running 2>/dev/null) in initializing|starting) ;;"
                        + " '') command -v systemctl >/dev/null || break ;; *) break ;; esac;"
                        + " now=$(date +%s); : ${end:=$((now + " + SSHD_WAIT_SECONDS + "))}; [ $now -ge $end ] && break;"
                        + " sleep 0.1; done;"
                        + " exec nc localhost 22"};
    }

    /** Where giving a missed boot its secret reports: stderr only, as stdout is the connection's stream. */
    Output secretOutput() {
        return TerminalOutput.stderrOnly();
    }

    /**
     * Whether the instance runs, saying why not as an error: ssh shows the ProxyCommand's stderr,
     * and an error writes nothing to stdout, which is the connection's stream. A running one whose
     * boot lacks its secret is given it, as {@code isx mcp} gives one (#1975); one read when it holds it.
     */
    boolean checkInstanceRunning(dev.incusspawn.incus.IncusClient incus) {
        var shown = OutputFormat.oneLine(instance);
        try {
            var read = incus.instanceMetadataOrThrow(instance);
            var status = read == null ? "" : read.path("status").asText("");
            if (status.isEmpty()) {
                BuildOutput.error("instance '" + shown + "' not found or not accessible.");
                return false;
            }
            if (!"Running".equalsIgnoreCase(status)) {
                BuildOutput.error("instance '" + shown + "' is not running (status: " + OutputFormat.oneLine(status) + ").",
                        "Start it first: isx start " + shown);
                return false;
            }
            // Restored running by Incus after a host reboot, it has an empty /run/isx: give it its
            // secret and proofs, or its shell's agent gets a 401. Never a start. Free for a container
            // whose boot holds them; stdout is the connection's stream, so what it says goes to stderr.
            // A delivery that fails is warned, and never refuses the connection. One isx did not make
            // (no parent stamp, which isx shell refuses) is never written isx's secret
            if (read.path("config").pathText(Metadata.PARENT, "").isEmpty()) return true;
            var out = secretOutput();
            try {
                InstanceLifecycle.giveSecretIfMissing(incus, instance, read, out);
            } catch (RuntimeException e) {
                out.warn("could not give " + shown + " a new instance secret: " + OutputFormat.oneLine(failure(e)));
            }
            return true;
        } catch (Exception e) {
            BuildOutput.error("could not check instance status: " + OutputFormat.oneLine(failure(e)));
            return false;
        }
    }
}
