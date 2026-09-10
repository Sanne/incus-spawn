package dev.incusspawn.command;

import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.ai.HelpContext;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.TerminalLink;
import dev.incusspawn.util.TerminalProgress;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@CommandDefinition(
        name = "ask",
        description = "AI-powered help — ask any question about incus-spawn (uses AI tokens)",
        generateHelp = true
)
public class AskCommand extends BaseCommand {

    private static final String DIM = "\033[2m";
    private static final String RESET = "\033[0m";

    @Arguments(description = "Your question about incus-spawn")
    List<String> questionWords;

    @Option(name = "with-templates", hasValue = false,
            description = "Include template and tool definitions in the AI context")
    boolean withTemplates;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (questionWords == null || questionWords.isEmpty()) {
            printUsage();
            return CommandResult.SUCCESS;
        }

        var question = String.join(" ", questionWords);
        var config = SpawnConfig.load();
        var provider = AiHelpClient.detectProvider(config);

        if (provider == null) {
            System.err.println("No AI credentials configured.");
            System.err.println("Run 'isx init' to set up Anthropic, Vertex AI, or OpenAI credentials.");
            return CommandResult.valueOf(1);
        }

        var providerName = switch (provider) {
            case ANTHROPIC -> "Anthropic (" + AiHelpClient.ANTHROPIC_MODEL + ")";
            case VERTEX -> "Vertex AI (" + AiHelpClient.VERTEX_MODEL + ")";
            case OPENAI -> "OpenAI (" + AiHelpClient.OPENAI_MODEL + ")";
        };
        var label = "Asking " + providerName + "…" + (withTemplates ? " (with templates)" : "");

        var systemPrompt = HelpContext.buildSystemPrompt(withTemplates);
        var ansi = TerminalProgress.isAnsiTerminal();

        if (!ansi) {
            System.err.println(BuildOutput.STEP_INDENT + label);
        }

        var spinning = new AtomicBoolean(ansi);
        Thread spinner = null;
        if (ansi) {
            spinner = Thread.startVirtualThread(() -> {
                var frames = TerminalProgress.SPINNER;
                int i = 0;
                while (spinning.get()) {
                    System.err.print("\r" + BuildOutput.STEP_INDENT + DIM
                            + frames[i % frames.length] + " " + label + RESET + "  ");
                    System.err.flush();
                    i++;
                    try { Thread.sleep(80); } catch (InterruptedException e) { break; }
                }
            });
        }

        var firstChunk = new AtomicBoolean(true);
        var lineBuf = new StringBuilder();
        AiHelpClient.askStreaming(question, systemPrompt, config, chunk -> {
            if (firstChunk.getAndSet(false)) {
                spinning.set(false);
                if (ansi) {
                    System.err.print("\r\033[2K" + BuildOutput.STEP_INDENT
                            + DIM + "✓ " + label + RESET + "\n");
                }
                System.err.println();
            }
            if (ansi) {
                lineBuf.append(chunk);
                int nl;
                while ((nl = lineBuf.indexOf("\n")) >= 0) {
                    System.out.println(TerminalLink.linkify(lineBuf.substring(0, nl)));
                    lineBuf.delete(0, nl + 1);
                }
            } else {
                System.out.print(chunk);
            }
            System.out.flush();
        });

        if (ansi && !lineBuf.isEmpty()) {
            System.out.print(TerminalLink.linkify(lineBuf.toString()));
        }
        spinning.set(false);
        if (spinner != null) spinner.join(200);
        if (firstChunk.get() && ansi) {
            System.err.print("\r\033[2K");
        }
        System.out.println();
        return CommandResult.SUCCESS;
    }

    private void printUsage() {
        System.out.println("AI-powered help for incus-spawn. Tokens will be consumed.");
        System.out.println();
        System.out.println("Usage: isx ask <question>");
        System.out.println("       isx ask --with-templates <question>");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  isx ask how do I configure a custom base image?");
        System.out.println("  isx ask what proxy domains are intercepted?");
        System.out.println("  isx ask --with-templates why is my maven tool broken?");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --with-templates  Include tool and image definitions in the AI context");
        System.out.println();
        System.out.println("Credentials: Uses your configured Anthropic, Vertex AI, or OpenAI credentials.");
        System.out.println("Run 'isx init' to configure them.");
    }
}
