package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.HostRepoRefresh;
import dev.incusspawn.incus.Metadata;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.util.ArrayList;

@CommandDefinition(
        name = "update-all",
        description = "Update all templates (system packages, git repos, dependencies)",
        generateHelp = true
)
public class UpdateAllCommand extends BaseCommand {

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

        refreshHostRepos(incus, templates);

        System.out.println("Updating " + templates.size() + " template(s)...\n");

        for (var name : templates) {
            System.out.println("--- Updating " + name + " ---");
            updateImage(incus, name);
            System.out.println();
        }

        System.out.println("All templates updated.");
        return CommandResult.SUCCESS;
    }

    private void refreshHostRepos(dev.incusspawn.incus.IncusClient incus, java.util.List<String> templateNames) {
        var config = SpawnConfig.load();
        if (config.getHostPaths().isEmpty() && config.getRepoPaths().isEmpty()) return;

        var defs = ImageDef.loadAll();
        var repos = new ArrayList<ImageDef.RepoEntry>();
        for (var name : templateNames) {
            var profile = incus.configGet(name, Metadata.PROFILE);
            var templateName = (profile != null && !profile.isEmpty()) ? profile : name;
            var imageDef = defs.get(templateName);
            if (imageDef != null) {
                repos.addAll(HostRepoRefresh.collectAllRepos(imageDef, defs));
            }
        }
        if (repos.isEmpty()) return;

        HostRepoRefresh.refresh(repos, config, false, false, System.out::println);
    }

    private void updateImage(dev.incusspawn.incus.IncusClient incus, String name) {
        incus.start(name);
        incus.waitForReady(name);

        // System updates
        System.out.println("  Running system updates...");
        incus.shellExec(name, "dnf", "update", "-y");

        // Update globally installed npm coding tools (if any)
        if (incus.shellExec(name, "npm", "list", "-g", "@anthropic-ai/claude-code").success()) {
            System.out.println("  Updating Claude Code...");
            incus.shellExec(name, "npm", "update", "-g", "@anthropic-ai/claude-code");
        }
        if (incus.shellExec(name, "npm", "list", "-g", "@openai/codex").success()) {
            System.out.println("  Updating Codex CLI...");
            incus.shellExec(name, "npm", "update", "-g", "@openai/codex");
        }

        // Git fetch in all repos (for project images)
        System.out.println("  Updating git repositories...");
        incus.execInContainer(name, "agentuser",
                "sh", "-c", "for d in ~/*/; do if [ -d \"$d/.git\" ]; then echo \"  Fetching $d\" && cd \"$d\" && git fetch --all && cd ~; fi; done");

        incus.stop(name);
        System.out.println("  Done.");
    }

}
