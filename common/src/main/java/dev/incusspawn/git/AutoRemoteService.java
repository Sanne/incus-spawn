package dev.incusspawn.git;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.nio.file.Path;
import java.util.function.Consumer;

public final class AutoRemoteService {

    private AutoRemoteService() {}

    public static void addRemotes(IncusClient incus, String instanceName) {
        addRemotes(incus, instanceName, System.out::println);
    }

    public static void addRemotes(IncusClient incus, String instanceName, Consumer<String> output) {
        addRemotes(instanceName, incus.instanceMetadataOrThrow(instanceName), output);
    }

    public static void addRemotes(String instanceName, JqValue instance, Consumer<String> output) {
        var config = SpawnConfig.load();
        var cache = new GitRemoteUtils.ConfigCache();
        var metadata = instance.path("config");
        var sources = HostRepoSources.parse(metadata.path(Metadata.HOST_REPOSITORIES).asText(""));
        for (var source : sources.entrySet()) {
            try {
                var host = Path.of(source.getValue());
                HostRepoSources.remember(instanceName, host);
                addRemoteInHostRepo(host, instanceName, source.getKey(), output, cache);
            } catch (Exception e) {
                output.accept("Warning: could not set up host-source remote: " + e.getMessage());
            }
        }
        var template = metadata.path(Metadata.PROFILE).asText("");
        if (template.isEmpty()) template = metadata.path(Metadata.PARENT).asText("");
        var repos = GitRemoteUtils.collectReposForTemplate(template);
        if (repos.isEmpty()) return;

        var candidateDirs = GitRemoteUtils.findAllCandidateRepoDirs(config);
        var urlIndex = GitRemoteUtils.buildUrlIndex(candidateDirs, cache);

        for (var repo : repos) {
            if (repo.isHostSource()) continue;
            try {
                if (HostRepoSource.isHostOnly(repo.getUrl())) {
                    var source = HostRepoSource.gitDirectory(repo);
                    HostRepoSources.remember(instanceName, source);
                    addRemoteInHostRepo(source, instanceName, dev.incusspawn.config.ImageDef.repoDestination(repo), output, cache);
                    continue;
                }
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
        var candidates = new java.util.LinkedHashSet<>(GitRemoteUtils.findAllCandidateRepoDirs(config));
        boolean failed = false;
        try {
            HostRepoSources.remembered(instanceName).values().stream().map(Path::of).forEach(candidates::add);
        } catch (Exception e) {
            failed = true;
            output.accept("Warning: could not read host-source remote cleanup record: " + e.getMessage());
        }
        var isxPrefix = "isx://" + instanceName + "/";

        for (var dir : candidates) {
            try {
                removeMatchingRemotes(dir, isxPrefix, output);
            } catch (Exception e) {
                failed = true;
                output.accept("Warning: remote cleanup failed for " + dir + ": " + e.getMessage());
            }
        }
        if (!failed) {
            try { java.nio.file.Files.deleteIfExists(HostRepoSources.cleanupFile(instanceName)); }
            catch (java.io.IOException e) { output.accept("Warning: could not remove remote cleanup record: " + e.getMessage()); }
        }
    }

    private static void removeMatchingRemotes(Path repoDir, String isxUrlPrefix, Consumer<String> output) {
        for (var remote : GitRemoteUtils.parseGitRemotes(repoDir)) {
            if (remote.url().startsWith(isxUrlPrefix)) {
                if (GitRemoteUtils.hostGitExec(repoDir, "remote", "remove", remote.name()) == null) {
                    throw new IllegalStateException("could not remove remote '" + remote.name() + "'");
                }
                output.accept("Removed git remote '" + remote.name() + "' from " + repoDir);
            }
        }
    }

}
