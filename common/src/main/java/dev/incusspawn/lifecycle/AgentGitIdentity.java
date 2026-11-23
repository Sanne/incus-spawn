package dev.incusspawn.lifecycle;

import dev.incusspawn.config.GitIdentity;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.Output;

/**
 * The Git identity an agent commits under, and where it came from (#2410).
 *
 * <p>A GitHub account's identity is what the {@code gh} tool writes into the agent's
 * {@code ~/.gitconfig}. When no account gives one, {@code git-identity:} in config.yaml is written
 * into the system file {@code /etc/gitconfig} instead, which Git reads before the user's. So the
 * account's identity always wins where there is one, and the fallback changes nothing the account
 * code decides by: {@code GhSetup.lacksBakedIdentity} reads only {@code ~/.gitconfig}, so a
 * template built with the fallback is still stamped as having no account identity, and an
 * account configured later is still derived into its branches. It also reaches a template without
 * {@code gh}, which no account identity reaches.
 *
 * <p>The system file's lines are marked {@code isx.git-identity}, so a build after the key is
 * removed takes them out of the image again. A system identity a template wrote itself, unmarked,
 * is never overwritten or removed, and is named as another file's, not config.yaml's.
 */
public final class AgentGitIdentity {

    /** The user whose commits the identity is for, as {@code GhSetup} writes it. */
    static final String AGENT_HOME = "/home/agentuser";

    /** The file the fallback is written to. */
    public static final String SYSTEM_FILE = "/etc/gitconfig";

    /** The marker isx writes beside the fallback, naming its source. */
    public static final String MARKER_KEY = "isx.git-identity";
    static final String MARKER_VALUE = "config.yaml";

    /** The line the script prints when it left a template's own name or email in place of config.yaml's. */
    static final String KEPT = "kept";

    private AgentGitIdentity() {}

    /** Where an agent's identity came from. */
    public enum Source {
        /** {@code git-identity:} in config.yaml, written to {@link #SYSTEM_FILE}. */
        CONFIG("the git-identity in config.yaml"),
        /** The agent's own {@code ~/.gitconfig}, which the gh tool writes from the GitHub account. */
        USER("~/.gitconfig, which the gh tool writes from the GitHub account"),
        /** Any other Git config file, such as one a template wrote. */
        OTHER("another Git config file");

        private final String description;

        Source(String description) { this.description = description; }

        public String description() { return description; }
    }

    /** The identity commits get, and its source; {@code name}'s source decides. */
    public record Effective(GitIdentity identity, Source source) {}

    /**
     * Writes {@code identity} as the fallback (or, when null, removes a fallback isx wrote before),
     * then says in the build's output whom the agent commits as and why ({@code gitHubAccount}: whether
     * config.yaml holds a GitHub account, which this template may not use). One exec, as root;
     * nothing at all when the image has no Git. A failed write fails the build.
     */
    public static void applyAndReport(Container container, GitIdentity identity, boolean gitHubAccount, Output out) {
        var result = container.sh(script(identity));
        // Never a template that silently commits under another identity than config.yaml names
        result.assertSuccess("Could not write the git-identity of config.yaml to " + SYSTEM_FILE);
        if (result.stdout().isBlank()) return;
        var effective = parse(result.stdout());
        if (effective == null) out.note(report(null, identity, keptTemplatesOwn(result.stdout()), gitHubAccount));
        else out.step(report(effective));
    }

    /** The build report's line for {@code effective}, null meaning no identity. */
    public static String report(Effective effective) {
        return report(effective, null, false, false);
    }

    /**
     * The build report's line for {@code effective}, null meaning no identity, when config.yaml's
     * git-identity is {@code configured} (null for none) and {@code kept} says the script left a
     * template's own name or email in its place. The identity is the guest's text, shown on one line.
     * With a GitHub account in config.yaml that this template does not use, the line says so rather
     * than that no account exists.
     */
    public static String report(Effective effective, GitIdentity configured, boolean kept, boolean gitHubAccount) {
        if (effective == null) {
            if (configured == null) {
                return gitHubAccount
                        ? "Agents in this template have no Git identity to commit under: your GitHub account"
                                + " gave it none (it may not use the account, or the account has no name and email),"
                                + " and config.yaml has no git-identity"
                        : "Agents have no Git identity to commit under: there is no GitHub account, and"
                                + " config.yaml has no git-identity";
            }
            return kept
                    ? "Agents have no Git identity to commit under: config.yaml's git-identity was not written,"
                            + " since " + SYSTEM_FILE + " holds a name or email of the template's own"
                    : "Agents have no Git identity to commit under: config.yaml's git-identity was written to "
                            + SYSTEM_FILE + ", but another Git config file sets an empty name or email";
        }
        return "Agents commit as " + OutputFormat.oneLine(effective.identity().toString())
                + ", from " + effective.source().description();
    }

    /**
     * The shell script {@link #applyAndReport} runs. It prints {@code git} when Git is there, then the
     * {@code --show-origin} lines of the agent's {@code user.name} and {@code user.email}.
     */
    static String script(GitIdentity identity) {
        var file = Container.shellQuote(SYSTEM_FILE);
        var write = identity == null
                // Removed only where isx wrote it: a template's own system identity stays
                ? "if [ \"$(" + markerQuery() + ")\" = " + MARKER_VALUE + " ]; then"
                        + " git config --file " + file + " --unset user.name || true;"
                        + " git config --file " + file + " --unset user.email || true;"
                        + " git config --file " + file + " --unset " + MARKER_KEY + "; fi"
                // Written only over isx's own or none: a template's own name or email stays.
                // Each write exits on failure: set -e does not stop at a failed command inside an && list
                : "if { [ -z \"$(git config --file " + file + " --get user.name)\" ]"
                        + " && [ -z \"$(git config --file " + file + " --get user.email)\" ]; }"
                        + " || [ \"$(" + markerQuery() + ")\" = " + MARKER_VALUE + " ]; then\n"
                        + "  git config --file " + file + " user.name " + Container.shellQuote(identity.name()) + " || exit 1\n"
                        + "  git config --file " + file + " user.email " + Container.shellQuote(identity.email()) + " || exit 1\n"
                        + "  git config --file " + file + " " + MARKER_KEY + " " + MARKER_VALUE + " || exit 1\n"
                        + "else echo " + KEPT + "\n"
                        + "fi";
        return "command -v git >/dev/null || exit 0\n"
                + "set -e\n"
                + write + "\n"
                + "echo git\n"
                + "HOME=" + AGENT_HOME + " git config --show-origin --get user.name || true\n"
                + "HOME=" + AGENT_HOME + " git config --show-origin --get user.email || true\n"
                + markerQuery() + " || true\n";
    }

    /** Whether the script's output says it left a template's own name or email in place of config.yaml's. */
    static boolean keptTemplatesOwn(String stdout) {
        return stdout.lines().anyMatch(KEPT::equals);
    }

    /**
     * The command that prints {@link #MARKER_VALUE} when isx wrote the identity in
     * {@link #SYSTEM_FILE}, and nothing when a template did.
     */
    private static String markerQuery() {
        return "git config --file " + Container.shellQuote(SYSTEM_FILE) + " --get " + MARKER_KEY;
    }

    /**
     * Reads what {@link #script} printed: the identity and its source, or null when the name or
     * the email is unset. {@code --show-origin} prints {@code file:<path>}, a tab, and the value;
     * a line {@link #MARKER_VALUE} alone says isx wrote {@link #SYSTEM_FILE}'s identity, without
     * which that file's identity is a template's own ({@link Source#OTHER}).
     */
    public static Effective parse(String stdout) {
        String name = null, email = null, origin = null;
        boolean marked = false;
        for (var line : stdout.lines().toList()) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                if (line.strip().equals(MARKER_VALUE)) marked = true;
                continue;
            }
            var value = line.substring(tab + 1);
            if (name == null) {
                name = value;
                origin = line.substring(0, tab);
            } else if (email == null) {
                email = value;
            }
        }
        if (name == null || email == null || name.isBlank() || email.isBlank()) return null;
        var source = sourceOf(origin);
        if (source == Source.CONFIG && !marked) source = Source.OTHER;
        return new Effective(new GitIdentity(name, email), source);
    }

    /** The source an origin of {@code --show-origin} names. */
    public static Source sourceOf(String origin) {
        if (origin.equals("file:" + SYSTEM_FILE)) return Source.CONFIG;
        if (origin.equals("file:" + AGENT_HOME + "/.gitconfig") || origin.equals("file:~/.gitconfig")) {
            return Source.USER;
        }
        return Source.OTHER;
    }
}
