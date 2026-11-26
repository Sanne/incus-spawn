package dev.incusspawn.ai;

import dev.incusspawn.Environment;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.proxy.GoogleAccounts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which AI Help account a question goes to (#2607): the one a person names with
 * {@code --account <namespace>=<account>}, else the one that last answered, else the first of
 * {@link AiHelpClient#targets}' fixed order.
 *
 * <p>The account that last answered is state, not configuration: one line under the state
 * directory ({@link Environment#aiHelpAccountFile()}), never {@code config.yaml}. A recorded
 * account that is gone or can no longer answer matches no target, so the fixed order applies.
 */
public final class AiHelpChoice {

    /** The namespaces whose accounts can answer, in {@link AiHelpClient#targets}' order. */
    static final List<String> NAMESPACES = List.of(
            SpawnConfig.ClaudeConfig.NAMESPACE, AiHelpClient.OPENAI_NAMESPACE, GoogleAccounts.NAMESPACE);

    private AiHelpChoice() {}

    /** A named account that cannot answer: why, and what can, one line each. */
    public static final class Refused extends Exception {
        private final List<String> advice;

        Refused(String message, List<String> advice) {
            super(message);
            this.advice = List.copyOf(advice);
        }

        public String[] advice() {
            return advice.toArray(String[]::new);
        }
    }

    /** The account asked when none is named: the recorded one if it can still answer, else the first; empty when none can. */
    public static Optional<AiHelpClient.Target> preferred(List<AiHelpClient.Target> targets) {
        return preferred(targets, recorded(Environment.aiHelpAccountFile()));
    }

    static Optional<AiHelpClient.Target> preferred(List<AiHelpClient.Target> targets, String recorded) {
        return targets.stream().filter(t -> t.selection().equals(recorded)).findFirst()
                .or(() -> targets.stream().findFirst());
    }

    /**
     * The target {@code selection} names among {@code config}'s targets. It never falls back to
     * another account: a bare name, an account that is missing or one that cannot answer here is
     * refused, with the accounts that can.
     */
    public static AiHelpClient.Target pinned(SpawnConfig config, String selection) throws Refused {
        var sel = selection.strip();
        // An unreadable file reads as empty: every account would look missing
        if (config.unreadableReason() != null) {
            throw new Refused(config.unreadableReason(), List.of("Fix config.yaml, then run the command again."));
        }
        var targets = AiHelpClient.targets(config);
        for (var target : targets) {
            if (target.selection().equals(sel)) return target;
        }
        int eq = sel.indexOf('=');
        var advice = new ArrayList<String>();
        if (targets.isEmpty()) {
            advice.addAll(AiHelpClient.noProvider(config).advice());
        } else {
            advice.add("Accounts that can answer: " + selections(targets, "and") + ".");
        }
        if (eq <= 0 || eq == sel.length() - 1) {
            throw new Refused("--account takes <namespace>=<account>, such as claude=work, not '" + sel + "'", advice);
        }
        var namespace = sel.substring(0, eq);
        var account = sel.substring(eq + 1);
        if (!NAMESPACES.contains(namespace)) {
            throw new Refused("'" + namespace + "' accounts cannot answer: AI Help asks a claude, openai or gemini account",
                    advice);
        }
        // The reserved account is never among a namespace's accounts, yet it is configured
        if (!AccountResolver.INHERIT.equals(account)
                && !AccountResolver.accountNames(config, namespace).contains(account)) {
            throw new Refused("there is no " + namespace + " account '" + account + "'", advice);
        }
        throw new Refused("the " + namespace + " account '" + account + "' cannot answer: "
                + whyNot(config, namespace, account), advice);
    }

    /** Why an account that exists is not one of {@link AiHelpClient#targets}, as the rest of a sentence. */
    static String whyNot(SpawnConfig config, String namespace, String account) {
        if (AccountResolver.INHERIT.equals(account)) {
            return "it takes its credential from the instance this isx runs in";
        }
        if (namespace.equals(SpawnConfig.ClaudeConfig.NAMESPACE)) {
            // Every configured account, incomplete ones included, typed as the proxy reads it:
            // an explicit type first, and a flat useVertex as the account default
            var claude = config.getClaude().allAccounts().get(account);
            var resolved = claude == null ? null : claude.effectiveType();
            if (resolved == SpawnConfig.ClaudeAccountType.VERTEX) return "its Vertex AI region or project is not set";
            if (resolved == SpawnConfig.ClaudeAccountType.OAUTH) {
                return "it is a Claude subscription, whose token works only in Claude Code";
            }
            return "it has no API key";
        }
        // Read as AiHelpClient.apiKeyAccounts reads it, unnormalized: 'API-Key' is not an api-key account there
        var raw = AccountResolver.value(config.tree(), namespace, account, "type");
        var type = raw.isBlank() ? "" : raw;
        return switch (type) {
            case "chatgpt" -> "it is a ChatGPT sign-in, which serves only Codex";
            case "google" -> "it is a Google sign-in, which serves only its own agent";
            case "", "api-key" -> "it has no API key";
            // An API key account has no type or api-key; any other one is skipped, key or not
            default -> "its type is '" + type + "', and only an api-key account answers";
        };
    }

    /**
     * What to try when {@code asked} failed and other accounts can answer: the option and the
     * accounts to pass it, one line; else none.
     */
    public static String[] others(List<AiHelpClient.Target> targets, AiHelpClient.Target asked) {
        return others(targets.stream().filter(t -> !t.equals(asked)).toList());
    }

    /** The option and {@code rest}, the accounts still to try, one line; none when {@code rest} is empty. */
    public static String[] others(List<AiHelpClient.Target> rest) {
        return rest.isEmpty() ? new String[0]
                : new String[] {"Ask another account with --account " + selections(rest, "or") + "."};
    }

    private static String selections(List<AiHelpClient.Target> targets, String conjunction) {
        var names = targets.stream().map(AiHelpClient.Target::selection).toList();
        return names.size() == 1 ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " " + conjunction + " " + names.getLast();
    }

    /** Records {@code target} as the account that last answered; best effort, since it only orders the next question. */
    public static void answered(AiHelpClient.Target target) {
        answered(Environment.aiHelpAccountFile(), target);
    }

    static void answered(Path file, AiHelpClient.Target target) {
        var selection = target.selection();
        if (selection.equals(recorded(file))) return;
        try {
            Files.createDirectories(file.getParent());
            // Replaced whole, so a concurrent reader never sees it half-written
            var tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tmp, selection + "\n", StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            // Next time the fixed order applies, as it would without the record
        }
    }

    /** The recorded selection, or empty when there is none or it cannot be read. */
    static String recorded(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
