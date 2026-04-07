package dev.incusspawn.tool;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import jakarta.enterprise.context.Dependent;

@Dependent
public class GhSetup implements ToolSetup {

    @Override
    public String name() {
        return "gh";
    }

    @Override
    public void install(Container c) {
        System.out.println("Installing GitHub CLI...");
        c.dnfInstall("Failed to install GitHub CLI", "gh");

        var config = SpawnConfig.load();
        if (config.getGithub().getToken().isBlank()) {
            System.err.println("  Warning: no GitHub token configured. Run 'isx init' to set up authentication.");
        }
        if (!config.getGithub().getToken().isBlank()) {
            c.appendToProfile("export GH_TOKEN=" + config.getGithub().getToken());
        }
    }
}
