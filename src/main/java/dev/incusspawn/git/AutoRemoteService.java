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
        var cache = new GitRemoteUtils.ConfigCache();
        var urlIndex = GitRemoteUtils.buildUrlIndex(candidateDirs, cache);

        for (var repo : repos) {
            try {
                var normalizedUrl = GitRemoteUtils.normalizeGitUrl(repo.getUrl());
                var matchingDirs = urlIndex.getOrDefault(normalizedUrl, java.util.List.of());
                for (var hostPath : matchingDirs) {
                    addRemoteInHostRepo(hostPath, instanceName, repo.getPath(), output, cache);
                }
            } catch (Exception e) {
                System.err.println("Warning: could not set up git remote for " + repo.getUrl() + ": " + e.getMessage());
            }
        }
    }

    private static void addRemoteInHostRepo(Path hostPath, String instanceName,
                                             String containerPath, Consumer<String> output,
                                             GitRemoteUtils.ConfigCache cache) {
        var isxUrl = containerPath.startsWith("/")
                ? "isx://" + instanceName + containerPath
                : "isx://" + instanceName + "/" + containerPath;

        var hasRemote = cache.remotes(hostPath).stream()
                .anyMatch(r -> r.name().equals(instanceName));
        if (hasRemote) {
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
        for (var remote : GitRemoteUtils.parseGitRemotes(repoDir)) {
            if (remote.url().startsWith(isxUrlPrefix)) {
                GitRemoteUtils.hostGitExec(repoDir, "remote", "remove", remote.name());
                output.accept("Removed git remote '" + remote.name() + "' from " + repoDir);
                break;
            }
        }
    }

}
