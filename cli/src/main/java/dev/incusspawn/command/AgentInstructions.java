package dev.incusspawn.command;

import dev.incusspawn.config.AgentContextGenerator;
import dev.incusspawn.incus.Container;
import dev.incusspawn.tool.GeminiSetup;
import dev.incusspawn.util.BuildOutput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Stores the build's generated agent instructions once and puts them where each installed
 * agent reads them (#1788), in one guest exec.
 *
 * <p>The one copy is {@link #STORE}, root-owned and 0644. A location outside the agent user's
 * home is a hardlink to it, so every agent reads the same inode; one inside the home (Gemini's
 * {@code ~/.gemini/extensions/isx/GEMINI.md}) is a copy owned by agentuser, since all links
 * share one owner and mode, and a root-owned file there would be one the user cannot edit. A
 * link that cannot be made, as when a mount puts the location on another filesystem, falls
 * back to a copy.
 *
 * <p>An agent that reads another format (Codex's {@code /etc/codex/requirements.toml}, #1783)
 * gets the instructions rendered into it, root-owned and 0644 like the store. Such a file holds
 * other settings too, so one a template wrote itself is kept, with a warning, rather than
 * replaced: isx writes it only where there is none, an empty one or the last build's.
 *
 * <p>The store is replaced by a rename and every location is linked again on each build, so a
 * location a tool replaced with a file of its own is relinked rather than left stale. A location
 * no installed tool declares any more is removed when isx wrote it: a link to the store, or a
 * file that starts with the generator's heading (after a YAML front matter block, for an agent
 * whose rule files need one, as Antigravity's do), which also covers the copies older builds left
 * at {@link #LEGACY} (the managed policy was written whether or not claude was installed, and
 * Gemini's went to the user's own {@code ~/.gemini/GEMINI.md} before #1784). A file
 * a template put there itself is kept.
 */
final class AgentInstructions {

    /** The one stored copy of the generated instructions. */
    static final String STORE = "/etc/isx/agent-instructions.md";

    /** The locations the last build linked or copied the store to, one per line. */
    static final String RECORD = "/etc/isx/agent-instructions.paths";

    /**
     * Locations isx owned before {@link #RECORD} could name them: the ones builds wrote before the
     * record existed, and Gemini's {@code ~/.gemini/GEMINI.md}, which builds wrote until #1784. Each
     * is checked as a stale location on every build, so an entry is only ever added.
     */
    static final List<String> LEGACY = List.of("/etc/claude-code/CLAUDE.md", "/AGENTS.md",
            GeminiSetup.LEGACY_CONTEXT_PATH);

    /** What the script ({@code isx_render}) prints for a rendered location it kept, followed by the path. */
    static final String KEPT = "isx-kept ";

    /**
     * Where an agent reads the instructions, and what it reads there: null for the stored copy
     * itself, else the file {@link dev.incusspawn.tool.ToolSetup#renderEnvironmentContext} made.
     */
    record Location(String path, String rendered) {
        Location {
            if (rendered != null && !startsWithHeading(rendered)) {
                throw new IllegalArgumentException(path + " must start with the line " + AgentContextGenerator.HEADING
                        + ", after a front matter block if it has one");
            }
        }
    }

    /**
     * Whether {@code rendered} opens with the generator's heading, or with a YAML front matter
     * block and the heading straight after it: how {@code isx_heading} in the script tells a file
     * isx wrote from one a template wrote.
     */
    static boolean startsWithHeading(String rendered) {
        var lines = rendered.split("\n", FRONT_MATTER_LINES + 2);
        if (!lines[0].equals(FRONT_MATTER)) return lines[0].equals(AgentContextGenerator.HEADING) && lines.length > 1;
        // As isx_heading reads it: the block closes by line FRONT_MATTER_LINES, the heading next
        for (int i = 1; i < Math.min(lines.length - 1, FRONT_MATTER_LINES); i++) {
            if (lines[i].equals(FRONT_MATTER)) return lines[i + 1].equals(AgentContextGenerator.HEADING) && i + 2 < lines.length;
        }
        return false;
    }

    /** The last line a front matter block may close on: {@code isx_heading}'s {@code [ "$n" -lt 40 ]} too. */
    static final int FRONT_MATTER_LINES = 40;

    /** The line that opens and closes a YAML front matter block. */
    static final String FRONT_MATTER = "---";

    private AgentInstructions() {
    }

    /** Every installed agent's location for {@code content}, the first tool declaring a path winning. */
    static List<Location> locations(List<BuildTools.ResolvedTool> tools, String content) {
        var byPath = new LinkedHashMap<String, Location>();
        for (var resolved : tools) {
            var path = resolved.setup().environmentContextPath();
            if (path != null && !byPath.containsKey(path)) {
                byPath.put(path, new Location(path, resolved.setup().renderEnvironmentContext(content)));
            }
        }
        return new ArrayList<>(byPath.values());
    }

    /** Store {@code content} and place it at every declared location, cleaning up stale ones. */
    static void install(Container container, String content, Collection<Location> declared) {
        var result = container.sh(script("", content, declared))
                .assertSuccess("Failed to write the agent instructions");
        for (var line : result.stdout().split("\n")) {
            if (line.startsWith(KEPT)) {
                BuildOutput.warn("Kept " + line.substring(KEPT.length())
                                + ", which the template wrote: its agent gets no environment instructions",
                        "Add them to that file in the template, or remove it there so isx writes its own.");
            }
        }
    }

    /**
     * The script {@link #install} runs, with every path under {@code root} ({@code ""} in a
     * guest), so a test can run it against a scratch directory. Starts with the store's heredoc.
     */
    static String script(String root, String content, Collection<Location> declared) {
        var store = Container.shellQuote(root + STORE);
        var sb = new StringBuilder();
        sb.append(Container.heredoc("set -e; mkdir -p \"$(dirname " + store + ")\"; cat > " + store + ".new",
                content.strip())).append('\n');
        sb.append("store=").append(store).append('\n');
        sb.append("r=").append(Container.shellQuote(root)).append('\n');
        sb.append("heading=").append(Container.shellQuote(AgentContextGenerator.HEADING)).append('\n');
        sb.append("""
                isx_heading() {
                  l=$(head -n 1 "$1" 2>/dev/null) || return 0
                  if [ "$l" != '---' ]; then printf '%s\\n' "$l"; return 0; fi
                  # The shell alone: macOS's sed refuses GNU's grouping, and the base image has no awk
                  n=1
                  { IFS= read -r _
                    while [ "$n" -lt 40 ] && IFS= read -r x; do
                      n=$((n + 1))
                      if [ "$x" = '---' ]; then IFS= read -r h && printf '%s\\n' "$h"; return 0; fi
                    done; } 2>/dev/null < "$1"
                }
                isx_stale() {
                  { [ -f "$r$1" ] || [ -L "$r$1" ]; } || return 0
                  if [ "$r$1" -ef "$store" ] || [ "$(isx_heading "$r$1")" = "$heading" ]; then rm -f "$r$1"; fi
                }
                isx_place() {
                  if [ -d "$r$1" ]; then echo "$1 is a directory, not the agent's file" >&2; return 1; fi
                  mkdir -p "$(dirname "$r$1")"
                }
                isx_link() {
                  isx_place "$1"
                  ln -f "$store" "$r$1" 2>/dev/null || { rm -f "$r$1" && cp "$store" "$r$1"; }
                }
                isx_copy() {
                  isx_place "$1"
                  rm -f "$r$1" && cp "$store" "$r$1" && chown agentuser:agentuser "$r$1"
                }
                isx_render() {
                  isx_place "$1"
                  if [ -s "$r$1" ] && [ "$(isx_heading "$r$1")" != "$heading" ]; then
                    echo "isx-kept $1"; return 0
                  fi
                  cat > "$r$1.isx-new" && chmod 0644 "$r$1.isx-new" && mv -f "$r$1.isx-new" "$r$1"
                  if [ -n "$2" ]; then chown agentuser:agentuser "$r$1"; fi
                }
                """);

        // Stale locations first, while a link isx made still names the store's inode.
        var kept = new StringBuilder("''");
        var paths = declared.stream().map(Location::path).toList();
        for (var path : paths) {
            kept.append('|').append(Container.shellQuote(path));
        }
        sb.append("{ cat \"$r").append(RECORD).append("\" 2>/dev/null || true; printf '%s\\n'");
        for (var path : LEGACY) {
            sb.append(' ').append(Container.shellQuote(path));
        }
        sb.append("; } | while IFS= read -r p; do\n")
                .append("  case \"$p\" in ").append(kept).append(") ;; *) isx_stale \"$p\" ;; esac\n")
                .append("done\n");

        // Recorded before any is placed, so a build that fails partway still leaves the next
        // one a record of every location it may have touched.
        sb.append(Container.heredoc("cat > \"$r" + RECORD + "\"", String.join("\n", paths))).append('\n');

        // A new inode for the new content, then every location linked to it again.
        sb.append("chmod 0644 \"$store.new\"\n");
        sb.append("mv -f \"$store.new\" \"$store\"\n");
        for (var location : declared) {
            var path = Container.shellQuote(location.path());
            if (location.rendered() != null) {
                // A file in agentuser's home is agentuser's, as a copy there is
                var owned = location.path().startsWith(GuestProvisioning.AGENTUSER_HOME + "/") ? " own" : "";
                sb.append(Container.heredoc("isx_render " + path + owned, location.rendered().strip()));
            } else {
                sb.append(location.path().startsWith(GuestProvisioning.AGENTUSER_HOME + "/") ? "isx_copy " : "isx_link ")
                        .append(path);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
