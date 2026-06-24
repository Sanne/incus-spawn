package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.GitRemoteUtils;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.nio.file.Files;
import java.nio.file.Path;

@CommandDefinition(
        name = "sync",
        description = "Sync repos between host and a running instance via auto-remotes",
        generateHelp = true
)
public class SyncCommand extends BaseCommand {

    @Argument(description = "Name of the instance to sync", required = true)
    String name;

    @Option(name = "push", hasValue = false, description = "Push only (host → container)")
    boolean pushOnly;

    @Option(name = "pull", hasValue = false, description = "Pull only (container → host)")
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
        var repos = GitRemoteUtils.collectReposForInstance(name, incus);
        if (repos.isEmpty()) {
            System.out.println("No repos found for instance '" + name + "'.");
            return CommandResult.SUCCESS;
        }

        boolean doPull = !pushOnly;
        boolean doPush = !pullOnly;

        int pulled = 0;
        int pushed = 0;
        int failed = 0;

        for (var repo : repos) {
            var repoName = GitRemoteUtils.repoNameFromUrl(repo.getUrl());
            if (repoName.isEmpty()) continue;

            var hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, config);
            if (hostPath == null || !Files.isDirectory(hostPath) || !GitRemoteUtils.isGitRepo(hostPath)) continue;

            var remoteUrl = GitRemoteUtils.getHostRepoRemoteUrl(hostPath, name);
            if (remoteUrl == null || !remoteUrl.startsWith("isx://")) continue;

            var branch = GitRemoteUtils.hostGitExec(hostPath, "rev-parse", "--abbrev-ref", "HEAD");
            if (branch == null) branch = "main";

            if (doPull) {
                System.out.print("  Fetching " + repoName + " (" + branch + ")... ");
                var fetchResult = GitRemoteUtils.hostGitExec(hostPath, "fetch", name, branch);
                if (fetchResult != null) {
                    var mergeResult = GitRemoteUtils.hostGitExec(hostPath, "merge", "--ff-only", "FETCH_HEAD");
                    if (mergeResult != null) {
                        System.out.println("done");
                        pulled++;
                    } else {
                        System.out.println("fetched (can't fast-forward — diverged)");
                        failed++;
                    }
                } else {
                    System.out.println("failed");
                    failed++;
                }
            }

            if (doPush) {
                System.out.print("  Pushing  " + repoName + " (" + branch + ")... ");
                var result = GitRemoteUtils.hostGitExec(hostPath, "push", name, branch);
                if (result != null) {
                    System.out.println("done");
                    pushed++;
                } else {
                    System.out.println("failed");
                    failed++;
                }
            }
        }

        var summary = new StringBuilder("Synced " + repos.size() + " repos with " + name + ":");
        if (doPull) summary.append(" ").append(pulled).append(" pulled");
        if (doPull && doPush) summary.append(",");
        if (doPush) summary.append(" ").append(pushed).append(" pushed");
        if (failed > 0) summary.append(", ").append(failed).append(" failed");
        summary.append(".");
        System.out.println(summary);

        return failed > 0 ? CommandResult.valueOf(1) : CommandResult.SUCCESS;
    }
}
