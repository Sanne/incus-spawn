package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.HostRepoRefresh;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static dev.incusspawn.incus.Container.shellQuote;

@CommandDefinition(
        name = "update-all",
        description = "Update system packages, npm globals, and git-fetch repos in all templates."
                + " Does not re-clone repos, reinstall tools, or re-run prime commands (use --prime for that)."
                + " For a full rebuild from the template definition, use 'isx build'.",
        generateHelp = true
)
public class UpdateAllCommand extends BaseCommand {

    @Option(name = "prime", hasValue = false, description = "Re-run prime commands (e.g. 'mvn install -DskipTests') for repositories that define one")
    private boolean prime;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (!InitCommand.requireInit()) return CommandResult.valueOf(1);
        var incus = RuntimeServices.incus();
        var instances = incus.list();
        var templates = new ArrayList<String>();

        // Collect base images first, then project images (order matters for dependencies)
        for (var instance : instances) {
            var name = instance.get("name");
            var type = Metadata.getType(incus, name);
            if (Metadata.TYPE_BASE.equals(type)) {
                templates.add(0, name); // bases first
            } else if (Metadata.TYPE_PROJECT.equals(type)) {
                templates.add(name);
            }
        }

        if (templates.isEmpty()) {
            System.out.println("No templates found. Run 'isx build' first.");
            return CommandResult.valueOf(1);
        }

        var defs = ImageDef.loadAll();
        var resolved = new LinkedHashMap<String, ImageDef>();
        for (var name : templates) {
            var profile = incus.configGet(name, Metadata.PROFILE);
            var templateName = (profile != null && !profile.isEmpty()) ? profile : name;
            resolved.put(name, defs.get(templateName));
        }

        refreshHostRepos(resolved, defs);

        return CommandResult.valueOf(updateTemplates(incus, resolved));
    }

    /** Updates each template in {@code resolved} and returns the command's exit code. */
    int updateTemplates(IncusClient incus, Map<String, ImageDef> resolved) {
        BuildOutput.section("Updating " + resolved.size() + " template(s).");

        boolean primesSkipped = false;
        var failedSteps = new LinkedHashSet<String>();
        for (var entry : resolved.entrySet()) {
            BuildOutput.header("Updating " + entry.getKey());
            var result = updateImage(incus, entry.getKey(), entry.getValue());
            primesSkipped |= result.primesSkipped();
            failedSteps.addAll(result.failedSteps());
        }

        if (!failedSteps.isEmpty()) {
            BuildOutput.warn("Some " + String.join(", ", failedSteps)
                    + " failed; re-run 'isx update-all" + (prime ? " --prime" : "") + "' to retry them.");
            return 1;
        }
        BuildOutput.success("All templates updated.");
        if (primesSkipped) {
            BuildOutput.note("Use --prime to re-run prime commands.");
        }
        return 0;
    }

    private void refreshHostRepos(Map<String, ImageDef> resolved, Map<String, ImageDef> defs) {
        var config = SpawnConfig.load();
        if (config.getHostPaths().isEmpty() && config.getRepoPaths().isEmpty()) return;

        var repos = new ArrayList<ImageDef.RepoEntry>();
        for (var imageDef : resolved.values()) {
            if (imageDef != null) {
                repos.addAll(HostRepoRefresh.collectAllRepos(imageDef, defs));
            }
        }
        if (repos.isEmpty()) return;

        HostRepoRefresh.refresh(repos, config, false, System.out::println);
    }

    private record PrimeResult(boolean skipped, boolean failed) {
        static final PrimeResult NONE = new PrimeResult(false, false);
    }

    /** {@code failedSteps} names each failed step in plural, for one summary line. */
    private record UpdateResult(boolean primesSkipped, Set<String> failedSteps) {}

    private UpdateResult updateImage(IncusClient incus, String name, ImageDef imageDef) {
        var machineType = incus.machineType(name);
        incus.start(name);
        incus.waitForReady(name, machineType);

        var failedSteps = new LinkedHashSet<String>();
        if (!GuestUpdate.system(incus, name)) failedSteps.add("system updates");
        // Update globally installed npm packages (coding tools, etc.)
        if (!NpmUpdate.run(incus, name)) failedSteps.add("npm updates");
        // Git fetch in all repos (for project images)
        if (!GuestUpdate.gitRepos(incus, name)) failedSteps.add("git fetches");
        var primeResult = handlePrimeCommands(incus, name, imageDef);
        if (primeResult.failed) failedSteps.add("prime commands");

        incus.stop(name);
        return new UpdateResult(primeResult.skipped, failedSteps);
    }

    private PrimeResult handlePrimeCommands(IncusClient incus, String name, ImageDef imageDef) {
        if (imageDef == null) return PrimeResult.NONE;

        var repos = imageDef.getRepos();
        if (repos.isEmpty()) return PrimeResult.NONE;

        boolean skipped = false;
        boolean failed = false;
        for (var repo : repos) {
            if (!repo.hasPrime()) continue;
            if (prime) {
                var expanded = GuestProvisioning.expandHome(repo.getPath());
                BuildOutput.stepStart("Priming " + repo.getPath() + "...");
                var result = incus.execInContainer(name, "agentuser",
                        "cd " + shellQuote(expanded) + " && " + repo.getPrime());
                if (result.success()) {
                    BuildOutput.stepDone();
                } else {
                    BuildOutput.stepFail("Prime command failed for " + repo.getPath() + ": " + repo.getPrime());
                    failed = true;
                }
            } else {
                BuildOutput.note("Skipping prime for " + repo.getPath() + ": " + repo.getPrime());
                skipped = true;
            }
        }
        return new PrimeResult(skipped, failed);
    }

}
