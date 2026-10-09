package dev.incusspawn.command;

import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;

/**
 * The system update and git fetch steps shared by {@code isx update-all} and
 * {@code isx project update}. Each returns false, after reporting it, when the step failed.
 */
final class GuestUpdate {

    /**
     * Fetches every non-host-only git repository directly under agentuser's home. Exits non-zero, naming each
     * repository, when any fetch failed. Nothing may prompt (there is nobody to answer), and
     * fetches are quiet so stderr holds only what went wrong.
     */
    static final String GIT_FETCH_SCRIPT = GitRemoteUtils.automaticFetchScript();

    private GuestUpdate() {
    }

    static boolean system(IncusClient incus, String name) {
        BuildOutput.stepStart("Running system updates...");
        return finish(incus.shellExec(name, "dnf", "update", "-y"), "dnf update -y");
    }

    static boolean gitRepos(IncusClient incus, String name) {
        BuildOutput.stepStart("Updating git repositories...");
        return finish(incus.execInContainer(name, "agentuser", GIT_FETCH_SCRIPT), "git fetch");
    }

    /** Lines of a failed step's stdout, and of its stderr, shown. */
    private static final int TAIL_LINES = 30;

    /**
     * Ends the step started for {@code what}: done, or failed with its exit code and the tails of
     * its stdout (build tools such as {@code mvn -q} and {@code npm ci} report errors there) and stderr.
     */
    static boolean finish(IncusClient.ExecResult result, String what) {
        if (!result.success()) {
            var output = new StringBuilder(what + " failed (exit code " + result.exitCode() + ")");
            for (var stream : new String[] {result.stdout(), result.stderr()}) {
                if (!stream.isBlank()) output.append('\n').append(BuildOutput.lastLines(stream, TAIL_LINES));
            }
            BuildOutput.stepFail(output.toString());
            return false;
        }
        BuildOutput.stepDone();
        return true;
    }
}
