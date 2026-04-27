package dev.incusspawn.git;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

public final class AutoRemoteService {

    private AutoRemoteService() {}

    public static void addRemotes(IncusClient incus, String instanceName) {
        addRemotes(incus, instanceName, System.out::println);
    }

    public static void addRemotes(IncusClient incus, String instanceName, Consumer<String> output) {
        var config = SpawnConfig.load();
        if (config.getHostPaths().isEmpty() && config.getRepoPaths().isEmpty()) return;

        var repos = GitRemoteUtils.collectReposForInstance(instanceName, incus);
        if (repos.isEmpty()) return;

        for (var repo : repos) {
            try {
                addRemoteForRepo(config, instanceName, repo.getUrl(), repo.getPath(), output);
            } catch (Exception e) {
                System.err.println("Warning: could not set up git remote for " + repo.getUrl() + ": " + e.getMessage());
            }
        }
    }

    private static void addRemoteForRepo(SpawnConfig config, String instanceName,
                                          String repoUrl, String containerPath,
                                          Consumer<String> output) {
        var repoName = GitRemoteUtils.repoNameFromUrl(repoUrl);
        if (repoName.isEmpty()) return;

        var hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, config);
        if (hostPath == null || !Files.isDirectory(hostPath) || !GitRemoteUtils.isGitRepo(hostPath)) return;

        // Verify the host repo's origin matches the container repo's URL
        var originUrl = GitRemoteUtils.getHostRepoRemoteUrl(hostPath, "origin");
        if (originUrl == null || !GitRemoteUtils.urlsMatch(originUrl, repoUrl)) return;

        var isxUrl = containerPath.startsWith("/")
                ? "isx://" + instanceName + containerPath
                : "isx://" + instanceName + "/" + containerPath;

        // Check for name collision
        var existingUrl = GitRemoteUtils.getHostRepoRemoteUrl(hostPath, instanceName);
        if (existingUrl != null) {
            System.err.println("Warning: remote '" + instanceName + "' already exists in " + hostPath);
            System.err.println("  To add manually: git -C " + hostPath + " remote add <name> " + isxUrl);
            return;
        }

        if (GitRemoteUtils.hostGitExec(hostPath, "remote", "add", instanceName, isxUrl) != null) {
            output.accept("Added git remote '" + instanceName + "' in " + hostPath);
        }
    }

    public static void removeRemotes(String instanceName) {
        removeRemotes(instanceName, System.out::println);
    }

    public static void removeRemotes(String instanceName, Consumer<String> output) {
        var config = SpawnConfig.load();
        if (config.getHostPaths().isEmpty() && config.getRepoPaths().isEmpty()) return;

        var candidates = collectCandidateRepoDirs(config);
        var isxPrefix = "isx://" + instanceName + "/";

        for (var dir : candidates) {
            try {
                removeMatchingRemotes(dir, isxPrefix, output);
            } catch (Exception e) {
                System.err.println("Warning: remote cleanup failed for " + dir + ": " + e.getMessage());
            }
        }
    }

    private static List<Path> collectCandidateRepoDirs(SpawnConfig config) {
        var dirs = new ArrayList<Path>();
        var seen = new HashSet<Path>();

        // Add all explicit repo-paths
        for (var entry : config.getRepoPaths().entrySet()) {
            var path = Path.of(dev.incusspawn.config.HostResourceSetup.expandHostTilde(entry.getValue()));
            if (Files.isDirectory(path) && GitRemoteUtils.isGitRepo(path) && seen.add(path)) {
                dirs.add(path);
            }
        }

        // Scan all host-paths base directories
        for (var hostPath : config.getHostPaths()) {
            var basePath = Path.of(dev.incusspawn.config.HostResourceSetup.expandHostTilde(hostPath));
            if (Files.isDirectory(basePath)) {
                try (var stream = Files.list(basePath)) {
                    stream.filter(Files::isDirectory)
                          .filter(GitRemoteUtils::isGitRepo)
                          .filter(seen::add)
                          .forEach(dirs::add);
                } catch (IOException ignored) {}
            }
        }

        return dirs;
    }

    private static void removeMatchingRemotes(Path repoDir, String isxUrlPrefix, Consumer<String> output) {
        var remoteList = GitRemoteUtils.hostGitExec(repoDir, "remote", "-v");
        if (remoteList == null) return;

        for (var line : remoteList.lines().toList()) {
            // Format: <name>\t<url> (fetch|push)
            var parts = line.split("\\t", 2);
            if (parts.length < 2) continue;
            var remoteName = parts[0];
            var urlAndType = parts[1].split(" ", 2);
            if (urlAndType.length < 1) continue;
            var url = urlAndType[0];

            if (url.startsWith(isxUrlPrefix)) {
                GitRemoteUtils.hostGitExec(repoDir, "remote", "remove", remoteName);
                output.accept("Removed git remote '" + remoteName + "' from " + repoDir);
                break; // One remote per instance per repo
            }
        }
    }

}
