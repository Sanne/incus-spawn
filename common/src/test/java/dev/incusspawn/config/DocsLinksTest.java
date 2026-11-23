package dev.incusspawn.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The README is the newcomer's page and the reference lives in docs/ (#1440): the README stays
 * short, and every link between them, which site/build.sh turns into a link between isx.run
 * pages, reaches a file and a heading that exist.
 */
@Tag("docs")
public class DocsLinksTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private final Map<Path, Set<String>> anchorsByFile = new HashMap<>();

    /** A heading's id the way site/render.js makes it from the heading's text. */
    public static String anchor(String heading) {
        return heading.replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .toLowerCase().replaceAll("[^\\w\\s-]", "").replaceAll("\\s+", "-").replaceAll("-+$", "");
    }

    /** The file's lines, numbered from 1, with those inside fenced code blocks left out. */
    private static Map<Integer, String> prose(Path file) throws IOException {
        var lines = new TreeMap<Integer, String>();
        var fenced = false;
        var n = 0;
        for (var line : Files.readAllLines(file)) {
            n++;
            if (line.startsWith("```")) fenced = !fenced;
            else if (!fenced) lines.put(n, line);
        }
        return lines;
    }

    /** The ids of every heading in a Markdown file. */
    public static Set<String> anchors(Path file) throws IOException {
        var ids = new HashSet<String>();
        for (var line : prose(file).values()) {
            if (line.matches("#{1,6} .*")) ids.add(anchor(line.replaceFirst("^#+ ", "")));
        }
        return ids;
    }

    private Set<String> cachedAnchors(Path file) throws IOException {
        var ids = anchorsByFile.get(file);
        if (ids == null) anchorsByFile.put(file, ids = anchors(file));
        return ids;
    }

    private static List<Path> docs() throws IOException {
        var files = new ArrayList<Path>();
        files.add(ROOT.resolve("README.md"));
        try (Stream<Path> s = Files.walk(ROOT.resolve("docs"))) {
            s.filter(p -> p.toString().endsWith(".md")).sorted().forEach(files::add);
        }
        return files;
    }

    @Test
    void readmeStaysAQuickRead() throws IOException {
        var lines = Files.readAllLines(ROOT.resolve("README.md")).size();
        assertTrue(lines <= 450, "README.md has " + lines + " lines; move reference material to docs/");
    }

    @Test
    void everyLinkBetweenDocsReachesAFileAndHeading() throws IOException {
        var broken = new ArrayList<String>();
        for (var file : docs()) {
            for (var line : prose(file).entrySet()) {
                var m = LINK.matcher(line.getValue());
                while (m.find()) {
                    var href = m.group(1);
                    if (href.matches("^[a-z]+:.*") || href.startsWith("/")) continue;
                    var hash = href.indexOf('#');
                    var path = hash < 0 ? href : href.substring(0, hash);
                    var target = path.isEmpty() ? file : file.getParent().resolve(path).normalize();
                    var where = ROOT.relativize(file) + ":" + line.getKey() + " " + href;
                    if (!target.startsWith(ROOT) || !Files.exists(target)) broken.add(where + " (no such file)");
                    else if (hash >= 0 && target.toString().endsWith(".md")
                            && !cachedAnchors(target).contains(href.substring(hash + 1))) broken.add(where + " (no such heading)");
                }
            }
        }
        assertEquals(List.of(), broken);
    }

    @Test
    void anchorsMatchTheSite() {
        assertEquals("isx-build", anchor("`isx build`"));
        assertEquals("claude-codes-sandbox-runtime", anchor("Claude Code's sandbox runtime"));
        assertEquals("delegating-from-an-agent-on-your-host-mcp", anchor("Delegating from an agent on your host (MCP)"));
    }
}
