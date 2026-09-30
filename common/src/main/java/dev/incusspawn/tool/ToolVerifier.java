package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;

import java.util.List;

/**
 * Runs the tools' verify commands once the build has installed them all and written
 * {@code /etc/profile.d/isx-env.sh}: as agentuser in a login shell, which loads that file, or,
 * for a {@code verify_as_root} check, as root without it.
 *
 * <p>Verifying each tool right after its own install judged it in an environment the image
 * never has: Maven's {@code mvn --version} ran before the template's JDK was installed, and
 * without the {@code JAVA_HOME} the JDK declares, so it failed in an image where Maven works.
 */
public final class ToolVerifier {

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

    /** A {@code verify_as_root} check in root's own environment, and nothing else. */
    static String[] asRoot(String command) {
        return new String[] {"env", "-i", "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "sh", "-c", command};
    }

    private ToolVerifier() {}

    public static void verifyAll(Container container, List<Check> checks) {
        if (checks.isEmpty()) return;
        var names = String.join(", ", checks.stream().map(Check::toolName).toList());
        try (var group = BuildOutput.group("Verifying", names)) {
            for (var check : checks) {
                verify(container, check);
            }
        }
    }

    /**
     * The step is not done until the command returns: a first {@code mx version} can take
     * seconds, and a line already saying "done" would leave that time unexplained.
     */
    static void verify(Container container, Check check) {
        BuildOutput.stepStart(check.toolName() + "...");
        BuildOutput.stepProgress(check.command());
        // As the user through a login shell, which sources /etc/profile.d (isx-env.sh included):
        // the image as the person using it sees it. A root check gets root's own environment
        // instead: isx-env.sh points tools into agentuser's home (zmx's ZMX_DIR), where a root
        // run leaves root-owned files. env -i also drops what the exec itself carries, such as
        // an instance's environment.* keys (GUI passthrough sets agentuser's XDG_RUNTIME_DIR).
        var result = check.asRoot()
                ? container.exec(asRoot(check.command()))
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
