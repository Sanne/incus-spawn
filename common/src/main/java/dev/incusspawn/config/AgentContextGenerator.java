package dev.incusspawn.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the managed-policy CLAUDE.md written to {@code /etc/claude-code/CLAUDE.md}
 * at the end of every template build.
 *
 * <p>Claude Code loads that path as its <em>managed policy</em> memory layer: it is read
 * before the user layer ({@code ~/.claude/CLAUDE.md}) and the project layer
 * ({@code ./CLAUDE.md}), all layers are concatenated rather than overriding each other,
 * and it cannot be suppressed via {@code claudeMdExcludes}. Writing there means isx never
 * has to merge with, or overwrite, a file a user or template owns.
 *
 * <h3>What belongs in this file</h3>
 * One test decides every line: <em>would omitting it cause a wrong action?</em> The
 * preamble earns its place by preventing four specific mistakes (working around a
 * missing tool instead of installing it; hunting for API keys that are held by the
 * proxy; acting outside the requested scope; reinstalling or re-cloning what the
 * template already provides). Anything the agent would treat identically whether or not
 * it was told — template descriptions, env vars already exported into every login shell,
 * host-resource mount semantics — is deliberately absent.
 *
 * <p>The tool and repo lists carry names and paths only, never descriptions.
 * {@link ImageDef#contentFingerprint} covers tool names and repo url/path, so those
 * cannot drift away from the built image; {@code description} is deliberately not
 * fingerprinted, so rendering it could assert something that stopped being true.
 */
public final class AgentContextGenerator {

    /**
     * The fixed part of the file, the same in every image. Each bullet earns its place
     * by preventing a specific wrong action, so nothing here is decorative.
     *
     * <p>Passwordless sudo is unconditional — {@code BuildCommand} writes an
     * {@code /etc/sudoers.d/agentuser} NOPASSWD rule during {@code buildFromScratch}.
     * The autonomy bullet deliberately does not name {@code bypassPermissions}: that
     * mode comes from {@code ClaudeSetup}'s managed settings and only when the
     * {@code claude} tool is installed, whereas this file is written for every image.
     */
    private static final String PREAMBLE = """
            # incus-spawn environment

            You are in an isx instance built from the `%s` template: a disposable, copy-on-write Linux box dedicated to this work.

            - Passwordless `sudo` is available. If a tool you need is missing, install it
              rather than working around its absence — the box is disposable, so a bad
              install costs nothing.
            - Credentials for the services isx proxies are injected by a host-side TLS proxy
              and are not stored here. Don't hunt for those API keys, and don't change
              endpoints or TLS verification to work around auth that looks missing.
            - You are running autonomously here. Use that for investigation, not for scope or
              outward-facing action: ask when the request is ambiguous, plan non-trivial work
              before starting it, and don't push branches, open PRs, or comment on issues or
              external services unless asked. Local commits are fine.
            """;

    /** A repository the build already cloned into the image. */
    public record Repo(String path, String url) {}

    private AgentContextGenerator() {}

    /**
     * Render the file.
     *
     * @param templateName canonical template name, e.g. {@code tpl-openjdk}
     * @param toolNames    names of every tool present in the final image, in install order
     * @param repos        repositories cloned by any layer of the chain, root-first
     * @param notes        {@code agent_note} blocks, root-first (ancestors, image, then tools)
     */
    public static String generate(String templateName, List<String> toolNames,
                                  List<Repo> repos, List<String> notes) {
        var sb = new StringBuilder(PREAMBLE.formatted(templateName));

        var tools = distinct(toolNames);
        if (!tools.isEmpty()) {
            sb.append("\nAlready installed, don't reinstall: ").append(String.join(", ", tools)).append('\n');
        }

        if (!repos.isEmpty()) {
            sb.append("\nAlready cloned, work in these rather than cloning again:\n");
            for (var repo : repos) {
                // Phrased as a statement, url first: "<url> is checked out at <path>".
                // `<path> — <url>` reads like a `git clone` with its arguments swapped,
                // i.e. as an instruction to do the very thing this list exists to stop.
                if (repo.url() != null && !repo.url().isBlank()) {
                    sb.append("- ").append(repo.url()).append(" is checked out at `")
                            .append(repo.path()).append("`\n");
                } else {
                    sb.append("- `").append(repo.path()).append("`\n");
                }
            }
        }

        var cleaned = distinct(notes.stream().map(n -> n == null ? null : n.strip()).toList());
        if (!cleaned.isEmpty()) {
            sb.append("\n## Notes\n\n");
            sb.append(String.join("\n\n", cleaned)).append('\n');
        }

        return sb.toString();
    }

    /**
     * Drop blanks and repeats while preserving order — a tool can be reached from
     * several layers, and two layers can carry the same note.
     */
    private static List<String> distinct(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
    }
}
