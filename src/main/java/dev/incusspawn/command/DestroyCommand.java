package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "destroy",
        description = "Destroy a clone environment",
        mixinStandardHelpOptions = true
)
public class DestroyCommand implements Runnable {

    @Parameters(index = "0", description = "Name of the environment to destroy")
    String name;

    @Option(names = "--force", description = "Force destruction, even for golden images")
    boolean force;

    @Inject
    IncusClient incus;

    @Override
    public void run() {
        if (!incus.exists(name)) {
            System.err.println("Error: no instance named '" + name + "' found.");
            return;
        }

        // Safety check: refuse to destroy golden images without --force
        var type = getType(name);
        if ((Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) && !force) {
            System.err.println("Error: '" + name + "' is a golden image (type: " + type + ").");
            System.err.println("Destroying golden images affects all clones derived from them.");
            System.err.println("Use --force if you really want to destroy it.");
            return;
        }

        System.out.println("Destroying " + name + "...");
        incus.delete(name, true);
        System.out.println("Destroyed " + name + ".");
    }

    private String getType(String name) {
        try {
            return incus.configGet(name, Metadata.TYPE);
        } catch (Exception e) {
            return "";
        }
    }
}
