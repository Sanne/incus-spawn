package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import picocli.CommandLine.Command;

@Command(
        name = "templates",
        description = "List available template names",
        mixinStandardHelpOptions = true
)
public class TemplatesCommand implements Runnable {

    @Override
    public void run() {
        ImageDef.loadAll().keySet().forEach(System.out::println);
    }
}
