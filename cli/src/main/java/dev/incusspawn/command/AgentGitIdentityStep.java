package dev.incusspawn.command;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.GitIdentity;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@code isx init}'s offer of this host's Git identity to agents that no GitHub account gives one
 * (#2410).
 *
 * <p>Committing under a person's own name is acceptable only as a choice they made and saw, so
 * the {@code git-identity:} key of config.yaml is written only after an explicit yes: a person
 * typing it at the question, which shows the identity, or the {@code --git-identity-from-host}
 * flag. Enter is No, and without a terminal and the flag nothing is written.
 */
final class AgentGitIdentityStep {

    /** The flag of {@code isx init} that answers yes without asking. */
    static final String FLAG = "git-identity-from-host";

    private AgentGitIdentityStep() {}

    /**
     * Offers {@code host} as the agents' identity, unless a GitHub account gives them one or
     * config.yaml already names one.
     *
     * @param prompts  the terminal, or null without one
     * @param fromHost whether {@link #FLAG} was given
     * @param host     this host's Git identity, read only when it is needed; null when it has none
     * @return whether config.yaml was written
     */
    static boolean offer(SpawnConfig config, Prompts prompts, boolean fromHost, Supplier<GitIdentity> host) {
        return offer(config, prompts, fromHost, host, "instances", true);
    }

    /** {@link #offer}, naming what the agent runs in as {@code machines}: "instances", the word every flow uses (#2542). */
    static boolean offer(SpawnConfig config, Prompts prompts, boolean fromHost, Supplier<GitIdentity> host, String machines,
                         boolean agent) {
        if (!AccountResolver.usableAccountNames(config, dev.incusspawn.tool.GhSetup.NAMESPACE).isEmpty()) {
            if (fromHost) BuildOutput.note("A GitHub account gives agents their Git identity, so --" + FLAG + " set none.");
            return false;
        }
        if (config.gitIdentity() != null) {
            if (fromHost) BuildOutput.note("config.yaml already names a git-identity, so --" + FLAG + " kept it.");
            return false;
        }
        if (prompts == null && !fromHost) return false;
        var identity = host.get();
        if (identity == null) {
            if (fromHost) {
                BuildOutput.warn("Git on " + dev.incusspawn.Platform.thisComputer() + " has no identity to give agents, so --" + FLAG + " set none.",
                        "Set user.name and user.email with 'git config --global', then run 'isx init --" + FLAG + "' again.");
            }
            return false;
        }
        if (!fromHost) {
            // What is asked first, then what each answer does, in one question (#2528)
            var here = dev.incusspawn.Platform.thisComputer();
            BuildOutput.gap();
            prompts.say(agent ? "Should the agent's commits carry your name and email?"
                    : "Should commits in " + machines + " carry your name and email?");
            for (var line : Lines.wrap("Git on " + here + " uses " + identity + ".")) prompts.say(line);
            prompts.say("");
            prompts.say("  y  yes: they carry it, and look like your own commits");
            prompts.say("  N  no: Git in " + machines + " refuses to commit until it");
            prompts.say("     has a name. 'isx account add github' gives it one.");
            // What "no" means was said with the question, and the first run's closing box repeats it once
            if (!yes(prompts)) return false;
        }
        config.useGitIdentity(identity);
        config.save();
        var said = Lines.wrap((agent ? "Agents with no GitHub account commit as " : "Commits in " + machines + " carry ")
                + identity, Lines.WIDTH - 2);
        BuildOutput.ok(said.getFirst());
        // Under the text of the ✓, not under the ✓
        for (var line : said.subList(1, said.size())) BuildOutput.stepNote(line);
        return true;
    }

    /** The answer to the identity question: y or yes; Enter, n and end of input are No, anything else is asked again. */
    private static boolean yes(Prompts prompts) {
        while (true) {
            var answer = prompts.ask(Prompts.choiceEnterTakes("N"));
            if (answer == null) return false;
            switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "y", "yes" -> { return true; }
                case "", "n", "no" -> { return false; }
                default -> prompts.say("Please answer y or n.");
            }
        }
    }

    /**
     * This host's {@code user.name} and {@code user.email}, as plain {@code git config} reads them,
     * or null when Git is missing, either is unset, or they cannot be an identity.
     */
    static GitIdentity fromHost() {
        var name = hostGitConfig("user.name");
        var email = name == null ? null : hostGitConfig("user.email");
        if (email == null || GitIdentity.problem(name, email) != null) return null;
        return new GitIdentity(name, email);
    }

    private static String hostGitConfig(String key) {
        try {
            // From /, never the directory init runs in: a repository's own user.name there is not
            // this host's identity
            var process = new ProcessBuilder("git", "config", "--get", key)
                    .directory(new java.io.File("/"))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .start();
            // The wait first, so a hung git cannot block the read; one line fits the pipe
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (java.io.IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
