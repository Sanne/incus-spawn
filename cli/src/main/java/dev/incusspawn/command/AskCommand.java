package dev.incusspawn.command;

import dev.incusspawn.ai.AiHelpChoice;
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
        description = "Ask an AI model any question about isx (uses AI tokens from your account)",
        generateHelp = true
)
public class AskCommand extends BaseCommand {


    @Arguments(description = "Your question about isx")
    List<String> questionWords;

    @Option(name = "with-templates", hasValue = false,
            description = "Include your template and tool definitions in what the AI model reads")
    boolean withTemplates;

    @Option(name = "account", completer = Completers.AiHelpAccount.class,
            description = "The account that answers, as <namespace>=<account>, such as claude=work."
                    + " Without it, the account that last answered is asked.")
    String account;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (questionWords == null || questionWords.isEmpty()) {
            printUsage();
            return CommandResult.SUCCESS;
        }

        var question = String.join(" ", questionWords);
        AiHelpClient.Target target;
        SpawnConfig config;
        if (account != null) {
            // A named account is never replaced by another, nor offered to be added
            config = SpawnConfig.load();
            try {
                target = AiHelpChoice.pinned(config, account);
            } catch (AiHelpChoice.Refused e) {
                BuildOutput.error(e.getMessage(), e.advice());
                return CommandResult.valueOf(1);
            }
        } else {
            config = answering(SpawnConfig.load(), prompts());
            if (config == null) return CommandResult.valueOf(1);
            target = AiHelpChoice.preferred(AiHelpClient.targets(config)).orElseThrow();
        }
        var systemBlocks = HelpContext.systemBlocks(withTemplates);
        // Every account that failed before it answered, so none is offered twice (#2605)
        var tried = new java.util.ArrayList<AiHelpClient.Target>();
        while (true) {
            var label = "Asking " + target.label() + (withTemplates ? " (with templates)" : "");
            // Only an answer with text makes this the account asked first next time
            var answered = new boolean[1];
            var asked = target;
            var with = config;
            try {
                answer(label, chunks -> send(question, systemBlocks, with, asked, chunks.andThen(chunk -> {
                    if (!chunk.isBlank()) answered[0] = true;
                })).note());
            } catch (AiHelpClient.ApiError e) {
                if (account != null || answered[0] || !AiHelpRecovery.accountFailed(e)) {
                    // A rejected key says what replaces it
                    BuildOutput.error(AiHelpRecovery.failed(target, e), AiHelpRecovery.withOthers(AiHelpClient.advice(e), config, target, tried));
                    return CommandResult.valueOf(1);
                }
                // The account failed, not the question: ask before another one is billed for it
                tried.add(target);
                var next = AiHelpRecovery.after(config, target, e, tried, prompts(), this::credentials);
                if (next.isEmpty()) return CommandResult.valueOf(1);
                config = next.get().config();
                target = next.get().target();
                continue;
            }
            if (answered[0]) AiHelpChoice.answered(target);
            return CommandResult.SUCCESS;
        }
    }

    /** What adds an account when the one asked failed; overridden by tests. */
    CredentialSetup credentials() {
        return new CredentialSetup();
    }

    /** Sends the question; overridden by tests. */
    AiHelpClient.StreamEnd send(String question, List<String> systemBlocks, SpawnConfig config,
                                AiHelpClient.Target target, Consumer<String> chunks) throws Exception {
        return AiHelpClient.askStreaming(question, systemBlocks, config, target, chunks);
    }

    static String[] joined(String[] first, String[] then) {
        var all = java.util.Arrays.copyOf(first, first.length + then.length);
        System.arraycopy(then, 0, all, first.length, then.length);
        return all;
    }

    /** Where this command asks, overridden by tests: {@link Prompts#terminal()}. */
    Prompts prompts() {
        return Prompts.terminal();
    }

    /**
     * {@code config} when an account can answer, else the config once {@link AiHelpAccount}
     * added one, or null (#2541). The offer goes to stderr, as progress does, so a capture of
     * stdout still holds only the answer.
     */
    public static SpawnConfig answering(SpawnConfig config, Prompts prompts) throws Exception {
        if (!AiHelpClient.targets(config).isEmpty()) return config;
        var credentials = new CredentialSetup();
        try {
            return withStdoutOnStderr(() -> AiHelpAccount.ask(config, prompts, credentials).orElse(null));
        } finally {
            credentials.closeHttpClient();
        }
    }

    /**
     * The config once {@link AiHelpAccount} added an account other than {@code failed}, which
     * all failed a question in the TUI, or null (#2605).
     */
    public static SpawnConfig answeringInstead(SpawnConfig config, Prompts prompts,
                                               java.util.Collection<AiHelpClient.Target> failed) throws Exception {
        if (prompts == null) return null;
        var credentials = new CredentialSetup();
        try {
            return withStdoutOnStderr(() -> {
                Lines.step("Add another account to answer the question.");
                return AiHelpAccount.offer(config, prompts, credentials, failed, AiHelpRecovery.NOT_ASKED).orElse(null);
            });
        } finally {
            credentials.closeHttpClient();
        }
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
                Ask an AI model any question about isx. It uses AI tokens from your account.

                Usage: isx ask <question>
                       isx ask --with-templates <question>
                       isx ask --account <namespace>=<account> <question>

                Examples:
                  isx ask how do I configure a custom base image?
                  isx ask what proxy domains are intercepted?
                  isx ask --with-templates why is my maven tool broken?

                Options:
                  --with-templates  Include your template and tool definitions in what the AI model reads
                  --account         The account that answers, such as claude=work

                Accounts: an Anthropic API key, Claude on Vertex AI, an OpenAI API key or a
                Gemini API key answers. Without one, isx asks for one on a terminal.
                The account that last answered is asked first.
                """);
    }
}
