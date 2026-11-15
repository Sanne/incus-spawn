package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every GitHub Action a workflow uses is pinned to a commit, with the release tag it was read from
 * beside it ({@code actions/checkout@<40 hex> # v6.1.0}, #2210). A tag can be moved to other code;
 * a commit cannot, and {@code setup-graalvm} runs in the job that builds what users install while
 * {@code wrangler-action} holds the Cloudflare token.
 * <p>
 * The upstream watch's {@code github-actions} item ({@code source-github-action.sh}) reads exactly
 * these lines from the files it lists, and {@code upstream-verify} checks each commit against its tag
 * on a PR that changes one. So a file with a use must be one of the item's files, or a new workflow
 * would be verified by nothing, and every action used must be a member, or the watch would fail.
 * A local action ({@code ./}) and a {@code docker://} image are not a repository's action.
 */
class WorkflowActionsPinnedTest {

    private static final Path ROOT = Path.of("..");
    private static final Path GITHUB = ROOT.resolve(".github");
    private static final String ITEM = "github-actions";

    private static final Pattern YAML_FILE = Pattern.compile(".*\\.ya?ml");
    /** The engine's own reading of a line ({@code _uses}): what follows {@code uses:} up to a space or {@code #}. */
    private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s+)?uses:\\s*([^\\s#]+)(.*)$");
    private static final Pattern PINNED = Pattern.compile(
            "([A-Za-z0-9-]+/[A-Za-z0-9._-]+)(?:/[A-Za-z0-9._/-]+)?@[0-9a-f]{40}");
    private static final Pattern TAG_COMMENT = Pattern.compile("\\s+#\\s+v[0-9]+(?:\\.[0-9]+)+\\s*");

    private record Use(String file, int line, String ref, String rest) {}

    private static List<Use> uses() throws IOException {
        var found = new ArrayList<Use>();
        try (var files = Files.walk(GITHUB)) {
            for (var file : files.filter(f -> YAML_FILE.matcher(f.getFileName().toString()).matches()).sorted().toList()) {
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    var m = USES.matcher(lines.get(i));
                    if (!m.matches() || m.group(1).startsWith("./") || m.group(1).startsWith("docker://")) continue;
                    found.add(new Use(ROOT.relativize(file).toString(), i + 1, m.group(1), m.group(2)));
                }
            }
        }
        assertFalse(found.isEmpty(), "no uses: line found under " + GITHUB);
        return found;
    }

    private static JqValue item() throws IOException {
        var item = JqYaml.parse(Files.readString(ROOT.resolve("upstream/manifest.yaml"))).path("items").path(ITEM);
        assertTrue(item.isObject(), "upstream/manifest.yaml has no item " + ITEM);
        return item;
    }

    @Test
    void everyActionIsPinnedToACommitWithItsTag() throws IOException {
        var offenders = new ArrayList<String>();
        for (var use : uses()) {
            if (!PINNED.matcher(use.ref()).matches() || !TAG_COMMENT.matcher(use.rest()).matches()) {
                offenders.add(use.file() + ":" + use.line() + ": " + use.ref() + use.rest());
            }
        }
        assertEquals(List.of(), offenders, "pin each action to the commit its release tag names, as "
                + "owner/repo@<sha> # vX.Y.Z (git ls-remote --tags https://github.com/<owner>/<repo>)");
    }

    @Test
    void theWatchReadsEveryFileAndEveryActionTheWorkflowsUse() throws IOException {
        var item = item();
        var listed = new TreeSet<String>();
        item.path("files").asList().forEach(f -> listed.add(f.asText()));
        var members = new TreeSet<String>();
        item.path("source").path("members").asMap().values().forEach(r -> members.add(r.asText()));

        var withUses = new TreeSet<String>();
        var used = new TreeSet<String>();
        for (var use : uses()) {
            withUses.add(use.file());
            var m = PINNED.matcher(use.ref());
            if (m.matches()) used.add(m.group(1));
        }
        assertEquals(withUses, listed, "the files of item " + ITEM + " in upstream/manifest.yaml must be "
                + "exactly the files that use an action, or upstream-verify does not check a new one");
        assertEquals(used, members, "the members of item " + ITEM + " must be exactly the actions used");
    }

    /** The app has no workflows permission, so it could never push the family's bump (#2210). */
    @Test
    void theFamilyIsAnIssueForABox() throws IOException {
        var item = item();
        assertEquals("judgement", item.pathText("class", ""));
        assertEquals("github-action", item.path("source").pathText("type", ""));
        assertTrue(Set.of("72h", "3d").contains(item.pathText("cooling", "")), "Actions cool for 72h (#2210)");
    }
}
