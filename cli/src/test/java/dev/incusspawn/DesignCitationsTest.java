package dev.incusspawn;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code, rules and docs cite the design record by page and heading, as in
 * {@code design/proxy.md "Host routes"}, so a reader can find the reasoning (#1472).
 * DESIGN.md is an index of the {@code design/} pages, and a split or a renamed heading
 * silently strands every citation that named the old place.
 * This test resolves each citation of DESIGN.md, a {@code design/} page or the
 * measurements in {@code docs/dev/PERFORMANCE-NOTES.md} to a heading or a bold lead-in
 * of that file, which the citation may shorten to a prefix.
 */
@Tag("docs")
class DesignCitationsTest {

    private static final Path ROOT = Path.of("..");
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "node_modules", ".idea");
    /** A comment or string continued on the next line: a citation may wrap there. */
    private static final Pattern CONTINUATION = Pattern.compile("[ \\t]*\\R[ \\t]*(?:\\*(?!\\*)|//|#(?![#\\w]))?[ \\t]*");
    private static final Pattern ESCAPED_QUOTE = Pattern.compile("\\\\\"");
    private static final Pattern CITATION = Pattern.compile(
            "(?<![\\w/.-])(DESIGN\\.md|design/[\\w-]+\\.md|docs/dev/PERFORMANCE-NOTES\\.md)(?:'s)?,? \\(?"
                    + "(\"[^\"\\n]+\"(?:(?:,| and| or) \"[^\"\\n]+\")*)");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"\\n]+)\"");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} (.+)$");
    private static final Pattern BOLD_LEAD = Pattern.compile("(?m)^\\s*(?:- |\\d+\\. )?\\*\\*(.+?)\\*\\*");
    private static final Pattern LEADING_HASHES = Pattern.compile("^#+ ");
    private static final Pattern INDEX_ENTRY = Pattern.compile("(?m)^- \\[[^]]+]\\((design/[\\w-]+\\.md)\\)\\n((?:  - .+\\n?)*)");
    private static final Pattern INDEX_ITEM = Pattern.compile("(?m)^  - (.+)$");
    private static final Pattern SECTION = Pattern.compile("(?m)^## (.+)$");
    /** A Markdown link to a path, not to a URL or an anchor of the same page. */
    private static final Pattern RELATIVE_LINK = Pattern.compile("]\\((?![a-z][\\w+.-]*:|#)([^)#\\s]+)(?:#[^)]*)?\\)");

    @Test
    void everyCitationOfTheDesignRecordNamesAHeadingOfItsPage() throws IOException {
        var titles = new HashMap<String, List<String>>();
        var unresolved = new ArrayList<String>();
        for (var file : sourceFiles()) {
            var text = read(file);
            if (text == null) continue;
            var joined = CONTINUATION.matcher(ESCAPED_QUOTE.matcher(text).replaceAll("\"")).replaceAll(" ");
            var citations = CITATION.matcher(joined);
            while (citations.find()) {
                var page = citations.group(1);
                var quoted = QUOTED.matcher(citations.group(2));
                while (quoted.find()) {
                    var cited = LEADING_HASHES.matcher(quoted.group(1)).replaceFirst("");
                    var known = titles.computeIfAbsent(page, DesignCitationsTest::titlesOf);
                    if (known.stream().noneMatch(t -> t.startsWith(cited))) {
                        unresolved.add(ROOT.relativize(file) + ": " + page + " \"" + cited + "\"");
                    }
                }
            }
        }
        assertTrue(unresolved.isEmpty(), "Citations naming no heading or bold lead-in of their page, or a page that does not exist; "
                + "point each at the page that now holds it:\n" + String.join("\n", unresolved));
    }

    /** DESIGN.md is the index: a page it does not link is a page nobody finds. */
    @Test
    void theIndexLinksEveryDesignPage() throws IOException {
        var index = read(ROOT.resolve("DESIGN.md"));
        try (Stream<Path> pages = Files.list(ROOT.resolve("design"))) {
            for (var page : pages.map(p -> "design/" + p.getFileName()).filter(p -> p.endsWith(".md")).sorted().toList()) {
                assertTrue(index.contains("](" + page + ")"), "DESIGN.md does not link " + page);
            }
        }
    }

    /** The index lists each page's top-level sections, in order, so a new or renamed one is listed in the same change. */
    @Test
    void theIndexListsEveryPagesSections() throws IOException {
        var index = read(ROOT.resolve("DESIGN.md"));
        var entry = INDEX_ENTRY.matcher(index);
        while (entry.find()) {
            var page = entry.group(1);
            var listed = new ArrayList<String>();
            var item = INDEX_ITEM.matcher(entry.group(2));
            while (item.find()) listed.add(item.group(1).strip());
            var sections = new ArrayList<String>();
            var heading = SECTION.matcher(read(ROOT.resolve(page)));
            while (heading.find()) sections.add(heading.group(1).strip());
            assertEquals(sections, listed, "DESIGN.md's list for " + page + " does not match its ## sections");
        }
    }

    /** A design page moved out of the repository root, so its relative links must say where they lead from there. */
    @Test
    void relativeLinksInTheDesignRecordResolve() throws IOException {
        var pages = new ArrayList<Path>(List.of(ROOT.resolve("DESIGN.md"), ROOT.resolve("docs/dev/PERFORMANCE-NOTES.md")));
        try (Stream<Path> design = Files.list(ROOT.resolve("design"))) {
            design.filter(p -> p.toString().endsWith(".md")).sorted().forEach(pages::add);
        }
        var broken = new ArrayList<String>();
        for (var page : pages) {
            var link = RELATIVE_LINK.matcher(read(page));
            while (link.find()) {
                if (!Files.exists(page.getParent().resolve(link.group(1)))) {
                    broken.add(ROOT.relativize(page) + ": " + link.group(1));
                }
            }
        }
        assertTrue(broken.isEmpty(), "Relative links leading nowhere:\n" + String.join("\n", broken));
    }

    /** The citations this test sees, so a pattern that stopped matching cannot pass silently. */
    @Test
    void theCitationPatternSeesWrappedAndListedCitations() {
        var text = """
                // A start rotates it (design/accounts.md
                // "A per-start secret"), see DESIGN.md "Goals" and "Why not application containers?".
                + " (design/terminal.md \\"Terminal Output Visual Language\\")"
                """;
        var joined = CONTINUATION.matcher(ESCAPED_QUOTE.matcher(text).replaceAll("\"")).replaceAll(" ");
        var found = new ArrayList<String>();
        var citations = CITATION.matcher(joined);
        while (citations.find()) {
            var quoted = QUOTED.matcher(citations.group(2));
            while (quoted.find()) found.add(citations.group(1) + " " + quoted.group(1));
        }
        assertEquals(List.of("design/accounts.md A per-start secret", "DESIGN.md Goals",
                "DESIGN.md Why not application containers?",
                "design/terminal.md Terminal Output Visual Language"), found);
    }

    private static List<String> titlesOf(String page) {
        var file = ROOT.resolve(page);
        if (!Files.isRegularFile(file)) return List.of();
        var text = read(file);
        var titles = new ArrayList<String>();
        for (var pattern : List.of(HEADING, BOLD_LEAD)) {
            var m = pattern.matcher(text);
            while (m.find()) titles.add(m.group(1).strip());
        }
        return titles;
    }

    /** The repository's files, leaving out build output and any checkout nested in it, such as a worktree under .claude/. */
    private static List<Path> sourceFiles() throws IOException {
        var files = new ArrayList<Path>();
        Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (SKIPPED_DIRS.contains(String.valueOf(dir.getFileName()))) return FileVisitResult.SKIP_SUBTREE;
                if (!dir.equals(ROOT) && Files.exists(dir.resolve(".git"))) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);
        return files;
    }

    /** The file as UTF-8 text, or null for a binary file. */
    private static String read(Path file) {
        try {
            var bytes = Files.readAllBytes(file);
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
