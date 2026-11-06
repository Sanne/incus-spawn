package dev.incusspawn.command;

import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.ai.HelpContext;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.TerminalLink;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;

import java.io.PrintStream;
import java.util.List;
import java.util.function.Consumer;

@CommandDefinition(
        name = "ask",
        description = "AI-powered help — ask any question about isx (uses AI tokens)",
        generateHelp = true
)
public class AskCommand extends BaseCommand {


    @Arguments(description = "Your question about isx")
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
        var targets = AiHelpClient.targets(config);

        if (targets.isEmpty()) {
            var why = AiHelpClient.noProvider(config);
            BuildOutput.error(why.cause(), why.advice().toArray(String[]::new));
            return CommandResult.valueOf(1);
        }

        var target = targets.getFirst();
        var label = "Asking " + target.label() + (withTemplates ? " (with templates)" : "");
        var systemBlocks = HelpContext.systemBlocks(withTemplates);
        answer(label,
                chunks -> AiHelpClient.askStreaming(question, systemBlocks, config, target, chunks).note());
        return CommandResult.SUCCESS;
    }

    /**
     * A streamed answer: hands each chunk to its consumer as it comes, then returns why the
     * answer ended early ({@link AiHelpClient.StreamEnd#note()}), or null when it is whole.
     */
    @FunctionalInterface
    interface Answer {
        String stream(Consumer<String> chunks) throws Exception;
    }

    /**
     * Asks, as a live step that ends when the first chunk comes, then prints the answer as the
     * command's result (#1703). The step goes to stderr, so stdout holds only the answer, as for
     * any query. A stream that fails part way still shows what came, on a line of its own; one
     * that fails before anything came breaks the step, and the error follows it. Why an answer
     * ended early is isx's word, not the answer's, so it is a warning after it, on stderr, and
     * so is the {@link AiHelpClient#DISCLAIMER} that closes every answer.
     */
    static void answer(String label, Answer answer) throws Exception {
        // Taken before the progress moves to stderr: the answer is what stdout is for
        var streamed = new Streamed(BuildOutput.results(), BuildOutput.ansi());
        withStdoutOnStderr(() -> {
            BuildOutput.stepStart(label);
            try {
                String note;
                try {
                    note = answer.stream(streamed);
                } catch (Exception e) {
                    if (!streamed.started()) BuildOutput.stepBreak();
                    throw e;
                } finally {
                    streamed.finish();
                }
                if (!streamed.started()) BuildOutput.stepDone("no answer");
                if (note != null) BuildOutput.warn(note);
            } finally {
                // What came is the model's word, also when the stream failed part way
                if (streamed.started()) BuildOutput.note(AiHelpClient.DISCLAIMER);
            }
            return null;
        });
    }

    /** The answer as it streams: the first chunk with text ends the step, and each is written. */
    private static final class Streamed implements Consumer<String> {
        private final PrintStream results;
        private final boolean ansi;
        private AnswerWriter writer;

        Streamed(PrintStream results, boolean ansi) {
            this.results = results;
            this.ansi = ansi;
        }

        boolean started() {
            return writer != null;
        }

        @Override
        public void accept(String chunk) {
            if (chunk.isEmpty()) return;
            if (writer == null) {
                BuildOutput.stepDone();
                // A blank line after the step, on its stream as it is drawn, so a script's capture
                // of stdout still starts with the answer
                BuildOutput.frame("");
                writer = new AnswerWriter(results, ansi);
            }
            writer.accept(chunk);
        }

        /** Ends what came on a line of its own, also when the stream failed part way. */
        void finish() {
            if (writer == null) return;
            writer.finish();
            results.println();
            results.flush();
        }
    }

    /**
     * Writes a streamed answer, which the model wrote, so none of it can drive the terminal
     * ({@link TerminalLink#plainText}, #1413). On an ANSI terminal each line goes out once whole,
     * with its links made clickable; otherwise each chunk goes out as it comes.
     */
    static final class AnswerWriter {
        private final PrintStream out;
        private final boolean ansi;
        private final StringBuilder lineBuf = new StringBuilder();

        AnswerWriter(PrintStream out, boolean ansi) {
            this.out = out;
            this.ansi = ansi;
        }

        void accept(String chunk) {
            if (ansi) {
                lineBuf.append(chunk);
                int nl;
                while ((nl = lineBuf.indexOf("\n")) >= 0) {
                    out.println(TerminalLink.linkify(lineBuf.substring(0, nl)));
                    lineBuf.delete(0, nl + 1);
                }
            } else {
                out.print(TerminalLink.plainText(chunk));
            }
            out.flush();
        }

        /** Writes what is left of the last line. */
        void finish() {
            if (!lineBuf.isEmpty()) out.print(TerminalLink.linkify(lineBuf.toString()));
        }
    }

    private void printUsage() {
        BuildOutput.results().print("""
                AI-powered help for incus-spawn. Tokens will be consumed.

                Usage: isx ask <question>
                       isx ask --with-templates <question>

                Examples:
                  isx ask how do I configure a custom base image?
                  isx ask what proxy domains are intercepted?
                  isx ask --with-templates why is my maven tool broken?

                Options:
                  --with-templates  Include tool and image definitions in the AI context

                Credentials: Uses your configured Anthropic, Vertex AI, or OpenAI credentials.
                Run 'isx init' to configure them.
                """);
    }
}
