package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
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

        System.out.println("Updating " + templates.size() + " template(s)...\n");

        for (var name : templates) {
            System.out.println("--- Updating " + name + " ---");
            updateImage(incus, name);
            System.out.println();
        }

        System.out.println("All templates updated.");
        return CommandResult.SUCCESS;
    }

    private void updateImage(dev.incusspawn.incus.IncusClient incus, String name) {
        incus.start(name);
        incus.waitForReady(name);

        // System updates
        System.out.println("  Running system updates...");
        incus.shellExec(name, "dnf", "update", "-y");

        // Update Claude Code
        System.out.println("  Updating Claude Code...");
        incus.shellExec(name, "npm", "update", "-g", "@anthropic-ai/claude-code");

        // Git fetch in all repos (for project images)
        System.out.println("  Updating git repositories...");
        incus.execInContainer(name, "agentuser",
                "sh", "-c", "for d in ~/*/; do if [ -d \"$d/.git\" ]; then echo \"  Fetching $d\" && cd \"$d\" && git fetch --all && cd ~; fi; done");

        incus.stop(name);
        System.out.println("  Done.");
    }

}
