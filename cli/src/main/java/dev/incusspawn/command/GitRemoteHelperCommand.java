package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.TerminalStreams;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@CommandDefinition(
        name = "git-remote-helper",
        description = "Git remote helper for isx:// URLs (invoked by git, not directly)",
        generateHelp = true
)
public class GitRemoteHelperCommand extends BaseCommand {

    private static final Set<String> ALLOWED_SERVICES = Set.of("git-upload-pack", "git-receive-pack");

    @Argument(index = "0", description = "Instance name", required = true)
    String instance;

    @Argument(index = "1", description = "Git service (git-upload-pack or git-receive-pack)", required = true)
    String service;

    @Argument(index = "2", description = "Repository path inside the instance", required = true)
    String path;


    @Override
    public CommandResult execute(CommandInvocation invocation) throws InterruptedException {
        var incus = dev.incusspawn.RuntimeServices.incus();

        if (!ALLOWED_SERVICES.contains(service)) {
            BuildOutput.error("unknown git service: " + OutputFormat.oneLine(service));
            return CommandResult.valueOf(1);
        }

        if (!checkInstanceRunning(incus)) {
            return CommandResult.valueOf(1);
        }

        var resolvedPath = GitRemoteUtils.containerPath(path);

        // git's protocol: stdin and stdout are git's, and stderr is what git shows its user
        var stderrOut = new GuestStderr(TerminalStreams.err(), GitRemoteHelperCommand::saysNotARepository);
        int exitCode;
        try {
            exitCode = incus.execBidirectional(instance, 1000, 1000, "/home/agentuser",
                    new String[]{service, resolvedPath}, TerminalStreams.in(), TerminalStreams.out(), stderrOut);
        } finally {
            stderrOut.close();  // a last line without a break, even when the exec fails
        }
        if (exitCode != 0 && stderrOut.sawWatched()) {
            printRepoHints(incus, resolvedPath);
        }
        return CommandResult.valueOf(exitCode);
    }

    private boolean checkInstanceRunning(dev.incusspawn.incus.IncusClient incus) {
        try {
            if (!incus.exists(instance)) {
                BuildOutput.error("instance '" + OutputFormat.oneLine(instance) + "' does not exist.");
                return false;
            }
            var status = incus.getInstanceStatus(instance);
            if (!"Running".equalsIgnoreCase(status)) {
                BuildOutput.error("instance '" + OutputFormat.oneLine(instance) + "' is not running (status: " + status + ").",
                        "Start it first: isx shell " + OutputFormat.oneLine(instance));
                return false;
            }
            return true;
        } catch (Exception e) {
            BuildOutput.error("could not check instance status: " + OutputFormat.oneLine(BaseCommand.failure(e)));
            return false;
        }
    }

    private void printRepoHints(dev.incusspawn.incus.IncusClient incus, String resolvedPath) {
        var repos = GitRemoteUtils.collectReposForInstance(instance, incus);
        if (repos.isEmpty()) return;

        // An error, which writes nothing to stdout: that is git's protocol stream, which a
        // warning's closing blank line would reach
        var hints = repoHints(instance, resolvedPath, repos);
        BuildOutput.error(hints.getFirst(), hints.subList(1, hints.size()).toArray(String[]::new));
    }

    /**
     * The hint for a path that is not a repository, as text: a template, project-local included,
     * decides its repositories' paths and URLs, and a clone's remote URL the requested path (#1624).
     */
    static List<String> repoHints(String instance, String path, List<ImageDef.RepoEntry> repos) {
        var lines = new ArrayList<String>();
        lines.add("The path '" + OutputFormat.oneLine(path) + "' is not a git repository in instance '"
                + OutputFormat.oneLine(instance) + "'.");
        lines.add("Known repositories:");
        for (var repo : repos) {
            lines.add("  " + OutputFormat.oneLine(String.valueOf(repo.getPath()))
                    + "  (" + OutputFormat.oneLine(String.valueOf(repo.sourceDescription())) + ")");
        }
        return lines;
    }

    /** Whether a line of a git service's stderr says the path is not a repository. */
    static boolean saysNotARepository(String line) {
        // "does not appear to be a git repository" from upload-pack and receive-pack.
        return line.contains("not a git repository") || line.contains("not appear to be a git repository");
    }
}
