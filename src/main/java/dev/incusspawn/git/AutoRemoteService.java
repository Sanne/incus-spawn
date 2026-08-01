package dev.incusspawn.git;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;

import java.nio.file.Path;
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

        var candidateDirs = GitRemoteUtils.findAllCandidateRepoDirs(config);
        var urlIndex = GitRemoteUtils.buildUrlIndex(candidateDirs);

        for (var repo : repos) {
            try {
                var normalizedUrl = GitRemoteUtils.normalizeGitUrl(repo.getUrl());
                var matchingDirs = urlIndex.getOrDefault(normalizedUrl, java.util.List.of());
                for (var hostPath : matchingDirs) {
                    addRemoteInHostRepo(hostPath, instanceName, repo.getPath(), output);
                }
            } catch (Exception e) {
                System.err.println("Warning: could not set up git remote for " + repo.getUrl() + ": " + e.getMessage());
            }
        }
    }

    private static void addRemoteInHostRepo(Path hostPath, String instanceName,
                                             String containerPath, Consumer<String> output) {
        var isxUrl = containerPath.startsWith("/")
                ? "isx://" + instanceName + containerPath
                : "isx://" + instanceName + "/" + containerPath;

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

        var candidates = GitRemoteUtils.findAllCandidateRepoDirs(config);
        var isxPrefix = "isx://" + instanceName + "/";

        for (var dir : candidates) {
            try {
                removeMatchingRemotes(dir, isxPrefix, output);
            } catch (Exception e) {
                System.err.println("Warning: remote cleanup failed for " + dir + ": " + e.getMessage());
            }
        }
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
