package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

@Command(
        name = "instances",
        description = "List connectable instance names (excludes templates)",
        mixinStandardHelpOptions = true
)
public class InstancesCommand implements Runnable {

    @Inject
    IncusClient incus;

    @Override
    public void run() {
        incus.list().stream()
                .map(m -> m.get("name"))
                .filter(name -> !name.startsWith("tpl-"))
                .forEach(System.out::println);
    }
}
