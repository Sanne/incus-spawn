package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.IncusClient;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@CommandDefinition(
        name = "sync",
        description = "Sync repos between host and a running instance via auto-remotes",
        generateHelp = true
)
public class SyncCommand extends BaseCommand {

    @Argument(description = "Name of the instance to sync", required = true)
    String name;

    @Option(name = "push", hasValue = false, description = "Push only (host to container)")
    boolean pushOnly;

    @Option(name = "pull", hasValue = false, description = "Pull only (container to host)")
    boolean pullOnly;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (pushOnly && pullOnly) {
            System.err.println("Error: --push and --pull are mutually exclusive. Omit both for bidirectional sync.");
            return CommandResult.valueOf(1);
        }

        var incus = RuntimeServices.incus();

        if (!incus.exists(name)) {
            System.err.println("Error: no instance named '" + name + "' found.");
            return CommandResult.valueOf(1);
        }

        var config = SpawnConfig.load();
        var repos = collectAllSyncTargets(incus, config);
        if (repos.isEmpty()) {
            System.out.println("No repos found for instance '" + name + "'.");
            return CommandResult.SUCCESS;
        }

        boolean doPull = !pushOnly;
        boolean doPush = !pullOnly;

        if (doPush) {
            incus.execInContainer(name, "agentuser",
                    "git", "config", "--global", "receive.denyCurrentBranch", "updateInstead");
        }

        int pulled = 0;
        int pushed = 0;
        int skipped = 0;
        int failed = 0;

        for (var target : repos) {
            var hostPath = target.hostPath;
            var repoName = target.name;

            var remoteUrl = GitRemoteUtils.getHostRepoRemoteUrl(hostPath, name);
            if (remoteUrl == null || !remoteUrl.startsWith("isx://")) {
                skipped++;
                continue;
            }

            // Verify container path exists; fix stale remote if repo was renamed
            var parsed = GitRemoteUtils.parseIsxUrl(remoteUrl);
            if (parsed != null) {
                var check = incus.execInContainer(name, "agentuser", "test", "-d", parsed.path());
                if (!check.success()) {
                    var fixedUrl = findRepoInContainer(incus, repoName, parsed.instance(), hostPath);
                    if (fixedUrl != null) {
                        GitRemoteUtils.hostGitExec(hostPath, "remote", "set-url", name, fixedUrl);
                        remoteUrl = fixedUrl;
                        parsed = GitRemoteUtils.parseIsxUrl(fixedUrl);
                        System.out.println("  Fixed stale remote for " + repoName + " -> " + fixedUrl);
                    } else {
                        System.out.println("  Skipping " + repoName + " (not found in container)");
                        skipped++;
                        continue;
                    }
                }
            }

            var hostBranch = GitRemoteUtils.hostGitExec(hostPath, "rev-parse", "--abbrev-ref", "HEAD");
            if (hostBranch == null) hostBranch = "main";

            if (doPull) {
                var containerBranch = parsed != null
                        ? getContainerBranch(incus, parsed.path()) : null;
                if (containerBranch == null) containerBranch = hostBranch;

                System.out.print("  Fetching " + repoName + " (" + containerBranch + ")... ");
                var fetchResult = GitRemoteUtils.hostGitExec(hostPath, "fetch", name, containerBranch);
                if (fetchResult != null) {
                    if (containerBranch.equals(hostBranch)) {
                        var mergeResult = GitRemoteUtils.hostGitExec(hostPath, "merge", "--ff-only", "FETCH_HEAD");
                        if (mergeResult != null) {
                            System.out.println("done");
                            pulled++;
                        } else {
                            System.out.println("fetched (can't fast-forward — diverged)");
                            failed++;
                        }
                    } else {
                        System.out.println("fetched (container on " + containerBranch + ", host on " + hostBranch + ")");
                        pulled++;
                    }
                } else {
                    System.out.println("failed");
                    failed++;
                }
            }

            if (doPush) {
                System.out.print("  Pushing  " + repoName + " (" + hostBranch + ")... ");
                var result = GitRemoteUtils.hostGitExec(hostPath, "push", "--no-verify", name, hostBranch);
                if (result != null) {
                    System.out.println("done");
                    pushed++;
                } else {
                    System.out.println("failed");
                    failed++;
                }
            }
        }

        int synced = repos.size() - skipped;
        var summary = new StringBuilder("Synced " + synced + " repos with " + name + ":");
        if (doPull) summary.append(" ").append(pulled).append(" pulled");
        if (doPull && doPush) summary.append(",");
        if (doPush) summary.append(" ").append(pushed).append(" pushed");
        if (failed > 0) summary.append(", ").append(failed).append(" failed");
        if (skipped > 0) summary.append(", ").append(skipped).append(" skipped");
        summary.append(".");
        System.out.println(summary);

        return failed > 0 ? CommandResult.valueOf(1) : CommandResult.SUCCESS;
    }

    private String findRepoInContainer(IncusClient incus, String repoName, String instanceName, Path hostPath) {
        var hostHead = GitRemoteUtils.hostGitExec(hostPath, "rev-parse", "HEAD");
        if (hostHead == null) return null;

        var staleUrl = GitRemoteUtils.getHostRepoRemoteUrl(hostPath, instanceName);
        if (staleUrl == null) return null;
        var staleIsxUrl = GitRemoteUtils.parseIsxUrl(staleUrl);
        if (staleIsxUrl == null) return null;
        var parentDir = staleIsxUrl.path().substring(0, staleIsxUrl.path().lastIndexOf('/'));

        var result = incus.execInContainer(name, "agentuser",
                "find", parentDir, "-maxdepth", "2", "-mindepth", "1",
                "-type", "d", "-name", ".git");
        if (!result.success()) return null;

        for (var line : result.stdout().strip().split("\n")) {
            if (line.isBlank()) continue;
            var repoDir = line.replace("/.git", "");
            var catResult = incus.execInContainer(name, "agentuser",
                    "git", "-C", repoDir, "cat-file", "-t", hostHead);
            if (catResult.success() && "commit".equals(catResult.stdout().strip())) {
                return "isx://" + instanceName + repoDir;
            }
        }
        return null;
    }

    record SyncTarget(String name, Path hostPath) {}

    private List<SyncTarget> collectAllSyncTargets(IncusClient incus, SpawnConfig config) {
        var targets = new ArrayList<SyncTarget>();
        var seen = new HashSet<String>();

        // Template-declared repos
        var templateRepos = GitRemoteUtils.collectReposForInstance(name, incus);
        for (var repo : templateRepos) {
            var repoName = GitRemoteUtils.repoNameFromUrl(repo.getUrl());
            if (repoName.isEmpty() || !seen.add(repoName)) continue;
            var hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, config);
            if (hostPath != null && Files.isDirectory(hostPath) && GitRemoteUtils.isGitRepo(hostPath)) {
                targets.add(new SyncTarget(repoName, hostPath));
            }
        }

        // All repos under host-paths that have an isx:// remote for this instance
        for (var hp : config.getHostPaths()) {
            var basePath = Path.of(HostResourceSetup.expandHostTilde(hp));
            if (!Files.isDirectory(basePath)) continue;
            try (var stream = Files.list(basePath)) {
                stream.filter(Files::isDirectory)
                      .filter(GitRemoteUtils::isGitRepo)
                      .forEach(repoPath -> {
                          var repoName = repoPath.getFileName().toString();
                          if (!seen.add(repoName)) return;
                          var remoteUrl = GitRemoteUtils.getHostRepoRemoteUrl(repoPath, name);
                          if (remoteUrl != null && remoteUrl.startsWith("isx://")) {
                              targets.add(new SyncTarget(repoName, repoPath));
                          }
                      });
            } catch (java.io.IOException ignored) {}
        }

        return targets;
    }

    private String getContainerBranch(IncusClient incus, String containerRepoPath) {
        try {
            var path = containerRepoPath;
            if (path.startsWith("~/")) path = "/home/agentuser/" + path.substring(2);
            var result = incus.execInContainer(name, "agentuser",
                    "git", "-C", path, "rev-parse", "--abbrev-ref", "HEAD");
            if (result.success()) {
                var branch = result.stdout().strip();
                return branch.isEmpty() || "HEAD".equals(branch) ? null : branch;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
