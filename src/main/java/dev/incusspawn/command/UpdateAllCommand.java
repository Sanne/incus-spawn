package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.util.ArrayList;

@Command(
        name = "update-all",
        description = "Update all golden images (system packages, git repos, dependencies)",
        mixinStandardHelpOptions = true
)
public class UpdateAllCommand implements Runnable {

    @Inject
    IncusClient incus;

    @Inject
    picocli.CommandLine.IFactory factory;

    @Override
    public void run() {
        if (!InitCommand.requireInit(factory)) return;
        var instances = incus.list();
        var goldenImages = new ArrayList<String>();

        // Collect base images first, then project images (order matters for dependencies)
        for (var instance : instances) {
            var name = instance.get("name");
            var type = getType(name);
            if (Metadata.TYPE_BASE.equals(type)) {
                goldenImages.add(0, name); // bases first
            } else if (Metadata.TYPE_PROJECT.equals(type)) {
                goldenImages.add(name);
            }
        }

        if (goldenImages.isEmpty()) {
            System.out.println("No golden images found. Run 'incus-spawn build' first.");
            return;
        }

        System.out.println("Updating " + goldenImages.size() + " golden image(s)...\n");

        for (var name : goldenImages) {
            System.out.println("--- Updating " + name + " ---");
            updateImage(name);
            System.out.println();
        }

        System.out.println("All golden images updated.");
    }

    private void updateImage(String name) {
        incus.start(name);
        waitForReady(name);

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

    private String getType(String name) {
        try {
            return incus.configGet(name, Metadata.TYPE);
        } catch (Exception e) {
            return "";
        }
    }

    private void waitForReady(String container) {
        for (int i = 0; i < 30; i++) {
            var r = incus.shellExec(container, "true");
            if (r.success()) return;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }
}
