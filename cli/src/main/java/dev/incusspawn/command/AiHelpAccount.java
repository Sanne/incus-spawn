package dev.incusspawn.command;

import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.Platform;
import dev.incusspawn.util.TerminalLink;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Asks for an account that can answer AI Help where it is first needed (#2541): {@code isx ask},
 * {@code ?} in the TUI and {@code isx doctor --explain}, when no configured account can answer.
 * The first run does not ask, since it cannot know which accounts the person will have by then.
 *
 * <p>Each kind is added beside the accounts already saved, never in their place, and the
 * namespace's {@code default:} is left alone, so instances keep the account they use: a Claude
 * subscription stays the account Claude Code spends, and the key added here answers questions.
 * Only a namespace with no account at all gets one as its default, the key being its first
 * account, as {@code isx account add} makes it.
 * Only a key is offered, never a sign-in: a subscription, a ChatGPT session or a Google sign-in
 * cannot answer (see {@link AiHelpClient#targets}).
 */
final class AiHelpAccount {

    static final String NOT_SENT = "isx did not send the request: no account that can answer was added.";

    private AiHelpAccount() {}

    /**
     * Without a terminal, reports why nothing can answer and the commands that add an account,
     * and returns empty. On one, says why and asks which kind to add, until one is added or the
     * person declines. Returns the config that can now answer, read again from disk.
     */
    static Optional<SpawnConfig> ask(SpawnConfig config, Prompts prompts, CredentialSetup credentials) {
        var why = AiHelpClient.noProvider(config);
        if (prompts == null) {
            BuildOutput.error(why.cause(), why.advice().toArray(String[]::new));
            return Optional.empty();
        }
        var subscription = !AiHelpClient.subscriptionAccounts(config).isEmpty();
        Lines.step(why.cause());
        while (true) {
            var answer = prompts.choose("Which kind of account should answer?", List.of(
                    "An Anthropic API key\nFrom the Claude Console, billed by use."
                            + (subscription ? "\nMax and Team plans include API credits for one." : ""),
                    "Claude on Google Cloud Vertex AI\nBilled to your Google Cloud project.",
                    "An OpenAI API key\nFrom platform.openai.com, billed by use.",
                    "A Gemini API key\nFrom Google AI Studio.",
                    // The bare command shows its menu of every credential: one line fits the frame
                    "None for now\nAdd one later with 'isx account add'."), null, null);
            // End of input is no answer at all: nothing is added
            var choice = answer == null ? "5" : answer.strip();
            var outcome = switch (choice) {
                case "1" -> claude(config, prompts, credentials, subscription, false);
                case "2" -> claude(config, prompts, credentials, subscription, true);
                case "3" -> key(config, prompts, credentials, AiHelpClient.Provider.OPENAI, "Create a key at "
                        + TerminalLink.link("https://platform.openai.com/api-keys") + ".");
                case "4" -> key(config, prompts, credentials, AiHelpClient.Provider.GEMINI, "Create a key at "
                        + TerminalLink.link("https://aistudio.google.com/apikey") + ".");
                case "5" -> CredentialSetup.Outcome.DONE;
                default -> CredentialSetup.Outcome.BACK;
            };
            if (outcome == CredentialSetup.Outcome.BACK) continue;
            var now = SpawnConfig.load();
            if (!AiHelpClient.targets(now).isEmpty()) return Optional.of(now);
            // One error, whatever ended it: the callers report nothing more (doctor --explain
            // returns false once a failure is reported), and a skipped step said why already
            BuildOutput.error(NOT_SENT);
            return Optional.empty();
        }
    }

    /** An Anthropic API key or a Vertex AI account, beside the Claude accounts already saved. */
    private static CredentialSetup.Outcome claude(SpawnConfig config, Prompts prompts, CredentialSetup credentials,
                                                  boolean subscription, boolean vertex) {
        if (subscription && !vertex) {
            BuildOutput.step(AiHelpClient.API_CREDITS_NOTE);
            BuildOutput.step(TerminalLink.link(AiHelpClient.API_CREDITS_URL));
        }
        var target = target(prompts, config.getClaude().allAccounts().keySet());
        if (target.isEmpty()) return CredentialSetup.Outcome.BACK;
        return vertex ? credentials.setupClaudeVertex(config, prompts, target.get())
                : credentials.setupClaudeApiKey(config, prompts, target.get(), null);
    }

    /**
     * An API key of {@code provider}'s namespace, checked before it is saved (#2606): a short
     * question goes to the provider with it, so a wrong key, or one with no quota left, is not
     * kept as the account that answers, which nothing would offer to replace. A key that fails
     * gets the menu every credential step shows ({@link CredentialSetup#askVerificationFailureAction}):
     * try again, or go back to the kinds, and save it unverified only when the check could not run.
     */
    private static CredentialSetup.Outcome key(SpawnConfig config, Prompts prompts, CredentialSetup credentials,
                                               AiHelpClient.Provider provider, String help) {
        var namespace = AiHelpClient.namespace(provider);
        var target = target(prompts, new HashSet<>(AccountResolver.accountNames(config, namespace)));
        if (target.isEmpty()) return CredentialSetup.Outcome.BACK;
        var name = target.get().name();
        var here = Platform.thisComputer();
        BuildOutput.gap();
        BuildOutput.step(help);
        while (true) {
            var key = CredentialSetup.askSecret(prompts, "Paste the API key, or Enter to go back: ");
            if (key.isBlank()) return CredentialSetup.Outcome.BACK;
            // The check reads the key from a copy, so a key nobody keeps never reaches config.yaml
            var trial = SpawnConfig.load();
            NamespaceAccounts.put(trial, namespace, name, "apiKey", key);
            var checked = AiHelpClient.targets(trial).stream()
                    .filter(t -> t.provider() == provider && t.accountName().equals(name)).findFirst();
            // A namespace-wide type: in a hand-edited config.yaml can keep the new account from answering
            var result = checked.isPresent() ? credentials.checkAiHelpKey(trial, checked.get())
                    : new CredentialSetup.AuthResult(false, "The " + namespace
                            + " section of config.yaml sets a type: other than an API key for every account, so account '"
                            + name + "' cannot answer. Remove that type: line, or choose another kind of account.");
            if (result.verified()) {
                save(config, namespace, name, key, "API key verified and saved on " + here);
                return CredentialSetup.Outcome.DONE;
            }
            switch (CredentialSetup.askVerificationFailureAction(prompts, "The key", result.message(), result.reached())) {
                case RETRY -> { }
                case SAVE_UNVERIFIED -> {
                    save(config, namespace, name, key, "API key saved on " + here + ", unverified");
                    BuildOutput.stepNote("If it fails, 'isx account add " + namespace + "' replaces it.");
                    return CredentialSetup.Outcome.DONE;
                }
                // Skipping this key leaves the other kinds to choose from
                case SKIP -> {
                    return CredentialSetup.Outcome.BACK;
                }
            }
        }
    }

    private static void save(SpawnConfig config, String namespace, String name, String key, String saved) {
        NamespaceAccounts.put(config, namespace, name, "apiKey", key);
        config.save();
        BuildOutput.ok(saved);
    }

    /**
     * Where the new account goes: the namespace's first account when it has none, else a new one
     * under a name the person gives. Empty when they go back.
     */
    private static Optional<CredentialSetup.AccountTarget> target(Prompts prompts, java.util.Set<String> taken) {
        if (taken.isEmpty()) return Optional.of(CredentialSetup.AccountTarget.FRESH);
        var name = CredentialSetup.askAccountName(prompts, taken,
                "Name for the new account (such as api), or Enter to go back: ");
        return name.isEmpty() ? Optional.empty() : Optional.of(new CredentialSetup.AccountTarget(name, false));
    }
}
