package dev.incusspawn.command;

import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.IncusClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
        var command = incus.buildDirectExecCommand(instance, 1000, "/home/agentuser",
                service, resolvedPath);

        try {
            var pb = new ProcessBuilder(command);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            var process = pb.start();

            var stderrCapture = new StringBuilder();
            var stderrThread = Thread.startVirtualThread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println(line);
                        stderrCapture.append(line).append('\n');
                    }
                } catch (IOException ignored) {}
            });

            int exitCode = process.waitFor();
            stderrThread.join(2000);

            if (exitCode != 0 && stderrCapture.toString().contains("not a git repository")) {
                printRepoHints();
            }

            return exitCode;
        } catch (IOException | InterruptedException e) {
            System.err.println("Error: failed to execute git service in container: " + e.getMessage());
            return 1;
        }
    }

    private boolean checkInstanceRunning() {
        try {
            if (!incus.exists(instance)) {
                System.err.println("Error: instance '" + instance + "' does not exist.");
                return false;
            }
            var result = incus.exec("list", instance, "--format=csv", "--columns=s");
            var status = result.success() ? result.stdout().strip() : "UNKNOWN";
            if (!"RUNNING".equalsIgnoreCase(status)) {
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
