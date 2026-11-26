package dev.incusspawn.command;

import dev.incusspawn.ai.AiHelpChoice;
import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What follows when the AI Help account asked failed before any answer came (#2605): a quota
 * used up (429), a key refused (401) or a permission denied (403). The error names the account,
 * and on a terminal isx asks before another account is used, since that bills an account the
 * person did not choose: another configured account when there is one, else the menu that adds
 * one. Off a terminal it names the {@code --account} to pass, or the commands that add one.
 * {@code isx ask} and {@code isx doctor --explain} share it; an account named with
 * {@code --account} is never replaced.
 */
final class AiHelpRecovery {

    static final String NOT_ASKED = "isx did not ask another account.";

    /** The account to ask next, with the config it is read from: read again when one was added. */
    record Next(SpawnConfig config, AiHelpClient.Target target) {}

    private AiHelpRecovery() {}

    /** Whether {@code e} says the account failed rather than the question, so another can answer it. */
    static boolean accountFailed(Exception e) {
        return e instanceof AiHelpClient.ApiError api && api.accountFailed();
    }

    /** The failure, naming the account that was asked: {@code the gemini account 'free' did not answer: ...}. */
    static String failed(AiHelpClient.Target asked, Exception e) {
        return asked.named() + " did not answer: " + BaseCommand.failure(e);
    }

    /** The other accounts to name after {@code asked} failed, less those that already failed this question. */
    static String[] others(SpawnConfig config, AiHelpClient.Target asked, Collection<AiHelpClient.Target> tried) {
        return AiHelpChoice.others(AiHelpClient.targets(config).stream()
                .filter(t -> !t.equals(asked) && !tried.contains(t)).toList());
    }

    /** {@code advice}, then {@link #others}. */
    static String[] withOthers(String[] advice, SpawnConfig config, AiHelpClient.Target asked,
                               Collection<AiHelpClient.Target> tried) {
        return AskCommand.joined(advice, others(config, asked, tried));
    }

    /**
     * After {@code asked} failed with {@code e}, which {@link #accountFailed} accepts, the account
     * the person agreed to ask instead, or the one they added. Empty once the failure and why
     * nothing else was asked are reported. {@code tried} holds every account that failed so far,
     * {@code asked} included, so none is offered twice. {@code prompts} is null off a terminal.
     */
    static Optional<Next> after(SpawnConfig config, AiHelpClient.Target asked, Exception e,
                                Collection<AiHelpClient.Target> tried, Prompts prompts,
                                Supplier<CredentialSetup> credentials) throws Exception {
        var advice = AiHelpClient.advice(e);
        var rest = AiHelpClient.targets(config).stream().filter(t -> !tried.contains(t)).toList();
        var instead = rest.isEmpty() ? new String[] {AiHelpClient.ADD_ONE} : AiHelpChoice.others(rest);
        if (prompts == null) {
            BuildOutput.error(failed(asked, e), AskCommand.joined(advice, instead));
            return Optional.empty();
        }
        // A warning starts a sentence of its own, where an error follows its "Error:" label
        var warning = failed(asked, e);
        BuildOutput.warn(Character.toUpperCase(warning.charAt(0)) + warning.substring(1), advice);
        if (!rest.isEmpty()) {
            var next = rest.getFirst();
            if (BaseCommand.askYesNo(prompts, "Ask " + next.label() + " instead?", false)) {
                return Optional.of(new Next(config, next));
            }
            BuildOutput.error(NOT_ASKED, instead);
            return Optional.empty();
        }
        if (!BaseCommand.askYesNo(prompts, "Add another account to answer?", false)) {
            BuildOutput.error(NOT_ASKED, instead);
            return Optional.empty();
        }
        var setup = credentials.get();
        try {
            // The offer goes to stderr, as progress does, so a capture of stdout holds only the answer
            var now = StdoutOnStderr.during(() -> AiHelpAccount.offer(config, prompts, setup, tried, NOT_ASKED).orElse(null));
            return now == null ? Optional.empty() : Optional.of(new Next(now, AiHelpClient.targets(now).stream()
                    .filter(t -> !tried.contains(t)).findFirst().orElseThrow()));
        } finally {
            setup.closeHttpClient();
        }
    }
}
