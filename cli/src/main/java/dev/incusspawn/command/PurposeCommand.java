package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;

import java.util.List;

/**
 * {@code isx purpose <name> [text]} (#2239): show or change what an instance is for. A person's
 * note on one instance only, kept apart from the purpose an {@code isx mcp} session gave
 * ({@link Metadata#MCP_PURPOSE}), so a guest-driven coordinator never rewrites it, and never
 * inherited by a branch of the instance.
 * It works on a running or a stopped instance: one metadata write, and no start.
 */
@CommandDefinition(
        name = "purpose",
        description = "Show or change what an instance is for",
        generateHelp = true
)
public class PurposeCommand extends BaseCommand {

    @Argument(completer = Completers.Instance.class, description = "Name of the instance", required = true)
    String name;

    @Arguments(description = "The new purpose, as one line of text (several words need no quotes)")
    List<String> text;

    @Option(name = "clear", description = "Remove the purpose", hasValue = false)
    boolean clear;

    @Override
    protected CommandResult doExecute() {
        return purpose(RuntimeServices.incus());
    }

    CommandResult purpose(IncusClient incus) {
        var shown = OutputFormat.oneLine(name);
        var hasText = text != null && !text.isEmpty();
        if (clear && hasText) {
            BuildOutput.error("--clear takes no text.");
            return CommandResult.valueOf(1);
        }
        if (!incus.exists(name)) {
            BuildOutput.error("no instance named '" + shown + "' found.");
            return CommandResult.valueOf(1);
        }
        var config = incus.readInstance(name).metadata().path("config");
        var type = config.pathText(Metadata.TYPE, "");
        if (Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) {
            BuildOutput.error(shown + " is a template, and a purpose is for instances only.");
            return CommandResult.valueOf(1);
        }
        if (!clear && !hasText) {
            var current = config.pathText(Metadata.PURPOSE, "");
            BuildOutput.results().println(OutputFormat.oneLine(current));
            return CommandResult.SUCCESS;
        }
        if (clear) {
            incus.updateFresh(name, new InstanceUpdate().unset(Metadata.PURPOSE));
            BuildOutput.success("Cleared the purpose of " + shown);
            return CommandResult.SUCCESS;
        }
        var purpose = String.join(" ", text).strip();
        var problem = Metadata.purposeProblem(purpose);
        if (problem != null) {
            BuildOutput.error(problem + "; to remove the purpose, use --clear");
            return CommandResult.valueOf(1);
        }
        incus.updateFresh(name, new InstanceUpdate().config(Metadata.PURPOSE, purpose));
        BuildOutput.success("Set the purpose of " + shown);
        return CommandResult.SUCCESS;
    }
}
