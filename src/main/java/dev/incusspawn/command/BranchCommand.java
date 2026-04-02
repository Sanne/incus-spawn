package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "branch",
        description = "Create an independent branch from a base image",
        mixinStandardHelpOptions = true
)
public class BranchCommand implements Runnable {

    @Parameters(index = "0", description = "Source base image to branch from")
    String source;

    @Parameters(index = "1", description = "Name for the new base image")
    String target;

    @Inject
    IncusClient incus;

    @Override
    public void run() {
        if (!incus.exists(source)) {
            System.err.println("Error: source image '" + source + "' does not exist.");
            return;
        }
        if (incus.exists(target)) {
            System.err.println("Error: '" + target + "' already exists.");
            return;
        }

        System.out.println("Branching '" + source + "' to '" + target + "'...");
        incus.copy(source, target);

        // Copy profile from source, set as independent base image
        var profile = getConfigOrDefault(source, Metadata.PROFILE, "minimal");
        incus.configSet(target, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(target, Metadata.PROFILE, profile);
        incus.configSet(target, Metadata.PARENT, source);
        incus.configSet(target, Metadata.CREATED, Metadata.today());

        System.out.println("Branch '" + target + "' created.");
    }

    private String getConfigOrDefault(String name, String key, String defaultValue) {
        try {
            var value = incus.configGet(name, key);
            return value.isEmpty() ? defaultValue : value;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
