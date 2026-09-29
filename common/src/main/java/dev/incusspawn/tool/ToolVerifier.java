package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;

import java.util.List;

/**
 * Runs the tools' verify commands once the build has installed them all and written
 * {@code /etc/profile.d/isx-env.sh}, with that file sourced.
 *
 * <p>Verifying each tool right after its own install judged it in an environment the image
 * never has: Maven's {@code mvn --version} ran before the template's JDK was installed, and
 * without the {@code JAVA_HOME} the JDK declares, so it failed in an image where Maven works.
 */
public final class ToolVerifier {

    static final String ENV_FILE = "/etc/profile.d/isx-env.sh";

    /** Longest failure reason shown: enough for a multi-line message, not a stack trace. */
    static final int MAX_REASON = 200;

    /** One tool to verify: its name and its (substituted) verify command. */
    public record Check(String toolName, String command, boolean asRoot) {
        public Check(String toolName, String command) {
            this(toolName, command, false);
        }
    }

    /** The image's user: the one whose environment a verify should judge. */
    static final String USER = "agentuser";

    private ToolVerifier() {}

    public static void verifyAll(Container container, List<Check> checks) {
        if (checks.isEmpty()) return;
        var names = String.join(", ", checks.stream().map(Check::toolName).toList());
        container.sh("touch " + MARKER);
        try (var group = BuildOutput.group("Verifying", names)) {
            for (var check : checks) {
                verify(container, check);
            }
        }
        // Best-effort: a failed repair must not fail a build that verified fine.
        container.sh(REPAIR_HOME_OWNERSHIP + "; rm -f " + MARKER);
    }

    /** Created before the first verify: only what appeared after it is a verify's doing. */
    static final String MARKER = "/run/isx-verify-start";

    /**
     * Verify commands run as root, and a tool may create state on any invocation -- in the home
     * of the user the image is for, when the image's env points there (zmx's {@code ZMX_DIR}). A
     * root-owned {@code ~/.zmx/logs} made every later zmx run as agentuser fail with "error:
     * AccessDenied". So hand back whatever root left under agentuser's home. {@code -xdev} keeps
     * the walk out of mounted host resources, and {@code -newer} the marker leaves alone whatever
     * was root-owned on purpose before any verify ran; {@code mountpoint} is asked only about the few
     * root-owned entries, never every file of a cloned repo, and skips a mount point itself,
     * which belongs to the host.
     */
    static final String REPAIR_HOME_OWNERSHIP =
            "find /home/agentuser -xdev -user root -newer " + MARKER + " ! -exec mountpoint -q {} \\; "
            + "-exec chown -h agentuser:agentuser {} +";

    /**
     * The step is not done until the command returns: a first {@code mx version} can take
     * seconds, and a line already saying "done" would leave that time unexplained.
     */
    static void verify(Container container, Check check) {
        BuildOutput.stepStart(check.toolName() + "...");
        BuildOutput.stepProgress(check.command());
        // As the user through a login shell, which sources /etc/profile.d (isx-env.sh included):
        // the image as the person using it sees it. Root gets the file sourced by hand.
        var result = check.asRoot()
                ? container.exec("sh", "-c", ". " + ENV_FILE + " && " + check.command())
                : container.shAsUser(USER, check.command());
        BuildOutput.stepDone();
        if (result.success()) {
            BuildOutput.stepNote(result.stdout().lines().findFirst().orElse(""));
        } else {
            BuildOutput.stepWarn(failure(check.command(), result));
        }
    }

    /**
     * Word a failed verify with its reason, so the warning says what to fix. The reason keeps
     * every line of the output, joined: messages wrap across lines ("...not defined correctly,"
     * / "this environment variable is needed..."), and the first alone ends mid-sentence.
     */
    static String failure(String command, IncusClient.ExecResult result) {
        var reason = joinedLines(result.stderr());
        if (reason.isEmpty()) reason = joinedLines(result.stdout());
        if (reason.isEmpty()) reason = "exit code " + result.exitCode();
        return "Verification failed (" + command + "): " + reason;
    }

    private static String joinedLines(String text) {
        if (text == null) return "";
        var joined = String.join(" ", text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList());
        return joined.length() > MAX_REASON ? joined.substring(0, MAX_REASON - 1) + "…" : joined;
    }
}
