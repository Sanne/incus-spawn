package dev.incusspawn.command;

import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.IncusClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "git-remote-helper",
        description = "Git remote helper for isx:// URLs (invoked by git, not directly)",
        mixinStandardHelpOptions = true
)
public class GitRemoteHelperCommand implements Callable<Integer> {

    private static final Set<String> ALLOWED_SERVICES = Set.of("git-upload-pack", "git-receive-pack");

    @Parameters(index = "0", description = "Instance name")
    String instance;

    @Parameters(index = "1", description = "Git service (git-upload-pack or git-receive-pack)")
    String service;

    @Parameters(index = "2", description = "Repository path inside the container")
    String path;

    @Inject
    IncusClient incus;

    @Override
    public Integer call() {
        if (!ALLOWED_SERVICES.contains(service)) {
            System.err.println("Error: unknown git service: " + service);
            return 1;
        }

        if (!checkInstanceRunning()) {
            return 1;
        }

        var resolvedPath = GitRemoteUtils.expandContainerTilde(path);

        var stderrCapture = new StringBuilder();
        var stderrOut = new OutputStream() {
            public void write(int b) {
                var c = (char) b;
                stderrCapture.append(c);
                System.err.print(c);
            }
            public void write(byte[] b, int off, int len) {
                var s = new String(b, off, len, StandardCharsets.UTF_8);
                stderrCapture.append(s);
                System.err.print(s);
            }
        };

        int exitCode = incus.execBidirectional(instance, 1000, 1000, "/home/agentuser",
                new String[]{service, resolvedPath}, System.in, System.out, stderrOut);
        if (exitCode != 0 && stderrCapture.toString().contains("not a git repository")) {
            printRepoHints();
        }
        return exitCode;
    }

    private boolean checkInstanceRunning() {
        try {
            if (!incus.exists(instance)) {
                System.err.println("Error: instance '" + instance + "' does not exist.");
                return false;
            }
            var status = incus.getInstanceStatus(instance);
            if (!"Running".equalsIgnoreCase(status)) {
                System.err.println("Error: instance '" + instance + "' is not running (status: " + status + ").");
                System.err.println("Start it first: isx shell " + instance);
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("Error: could not check instance status: " + e.getMessage());
            return false;
        }
    }

    private void printRepoHints() {
        var repos = GitRemoteUtils.collectReposForInstance(instance, incus);
        if (repos.isEmpty()) return;

        System.err.println();
        System.err.println("The path '" + path + "' is not a git repository in instance '" + instance + "'.");
        System.err.println("Known repositories:");
        for (var repo : repos) {
            System.err.println("  " + repo.getPath() + "  (" + repo.getUrl() + ")");
        }
    }
}
