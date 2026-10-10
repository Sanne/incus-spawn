package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The design documents are reviewed as diffs, so their Markdown source has one sentence per line:
 * a changed sentence is then one changed line, not a rewrapped paragraph (#1243). Headings, tables,
 * code, HTML and front matter are exempt; CLAUDE.md "Markdown source" has the rule.
 */
class DocsOneSentencePerLineTest {

    private static final Path ROOT = Path.of("..");

    /** A sentence end: {@code .!?}, any closing marks, whitespace, then what can start a sentence. */
    private static final Pattern BOUNDARY = Pattern.compile("([.!?])[)\"'*_]*\\s+(?=[A-Z(*\\[\"`])");
    /** Ends that are not a sentence end: the abbreviation is the word before the full stop. */
    private static final Set<String> ABBREVIATIONS = Set.of("e.g", "i.e", "vs", "etc", "cf");
    private static final Pattern LAST_WORD = Pattern.compile("(\\S+)$");
    /** A line that ends a sentence, or introduces a list, a table or code. */
    private static final Pattern ENDS_SENTENCE = Pattern.compile("[.!?:][)\"'*_]*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})");
    /** A thematic break or a setext heading's underline: not prose, and the line above it ends there. */
    private static final Pattern RULE = Pattern.compile("^\\s*(?:[-*_]\\s*){3,}$|^\\s*=+\\s*$");
    private static final Pattern INLINE_CODE = Pattern.compile("(`+).+?\\1");
    private static final Pattern LINK_TARGET =
            Pattern.compile("\\]\\([^)]*\\)|<https?://[^>]*>|https?://\\S+?(?=[.!?,;:)]*(?:\\s|$))");
    private static final Pattern LINK_DEFINITION = Pattern.compile("^\\s*\\[[^]]+]:\\s");

    @Test
    void theDesignDocumentsHaveOneSentencePerLine() throws IOException {
        var offenders = new ArrayList<String>();
        for (var file : documents()) {
            var name = ROOT.relativize(file).toString();
            for (var problem : problems(Files.readAllLines(file))) {
                offenders.add(name + ":" + problem);
            }
        }
        assertTrue(offenders.isEmpty(), "put each sentence on its own line, and never wrap one "
                + "(CLAUDE.md \"Markdown source\"):\n" + String.join("\n", offenders));
    }

    @Test
    void twoSentencesOnALineAreFound() {
        assertEquals(List.of("1: two sentences on one line"), problems(List.of("One here. Two here.")));
        assertEquals(List.of("1: two sentences on one line"), problems(List.of("- **Bold.** Then more.")));
        assertEquals(List.of("1: two sentences on one line"), problems(List.of("> Quoted. Also quoted.")));
        assertEquals(List.of("1: two sentences on one line"), problems(List.of("Ends here. `code` starts one.")));
        assertEquals(List.of("1: two sentences on one line"), problems(List.of("See https://x.y. Then more.")));
    }

    @Test
    void aWrappedSentenceIsFound() {
        assertEquals(List.of("1: a sentence wrapped onto the next line"),
                problems(List.of("A sentence that goes", "on here.")));
        assertEquals(List.of("1: a sentence wrapped onto the next line"),
                problems(List.of("- an item that goes", "  on here.")));
    }

    @Test
    void aTrailingHardBreakIsFound() {
        assertEquals(List.of("1: trailing hard line break"), problems(List.of("Ends here.  ", "Next one.")));
    }

    @Test
    void whatIsNotASentenceEndIsAllowed() {
        assertEquals(List.of(), problems(List.of(
                "Abbreviations, e.g. Fedora, i.e. This, vs. That, etc. Done.",
                "Code `like. This` and [links](https://x.y/a. B) and https://x.y/a.b are fine.",
                "File names like DESIGN.md and versions like 3.x are fine.",
                "First sentence.",
                "Second sentence on its own line.",
                "- An item without a full stop",
                "- Another item.",
                "  Its second sentence, indented into the item.",
                "> A quote.",
                "> Its second sentence.",
                "Introduces a list:",
                "1. First.")));
    }

    @Test
    void exemptBlocksAreSkipped() {
        assertEquals(List.of(), problems(List.of(
                "---",
                "paths:",
                "  - \"A. B\"",
                "---",
                "# A heading. With two parts",
                "| A cell. Another sentence | and a wrapped",
                "| row |",
                "````md",
                "```",
                "code. Code",
                "wrapped code",
                "````",
                "A heading over an underline",
                "---",
                "Text before a rule",
                "***",
                "<!-- a comment. Two sentences",
                "wrapped -->",
                "<details><summary>Html. Html</summary>",
                "[ref]: https://x.y \"Title. Two\"")));
    }

    /** DESIGN.md is converted in its own pull request and joins this list there. */
    private static List<Path> documents() throws IOException {
        var files = new ArrayList<Path>();
        files.add(ROOT.resolve("CLAUDE.md"));
        try (Stream<Path> rules = Files.list(ROOT.resolve(".claude/rules"))) {
            rules.filter(f -> f.toString().endsWith(".md")).sorted().forEach(files::add);
        }
        return files;
    }

    /** Each problem as {@code <line>: <what>}, with 1-based line numbers. */
    static List<String> problems(List<String> lines) {
        var problems = new ArrayList<String>();
        boolean frontMatter = !lines.isEmpty() && lines.get(0).equals("---");
        String fence = null;
        boolean comment = false;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (frontMatter) {
                if (i > 0 && line.equals("---")) frontMatter = false;
                continue;
            }
            var opens = FENCE.matcher(line);
            if (fence == null && opens.find()) {
                fence = opens.group(1);
                continue;
            }
            if (fence != null) {
                // A fence closes on a line of the same character, at least as long, and nothing else.
                var s = line.strip();
                char mark = fence.charAt(0);
                if (s.length() >= fence.length() && s.chars().allMatch(c -> c == mark)) fence = null;
                continue;
            }
            if (comment || line.strip().startsWith("<!--")) {
                comment = !line.contains("-->");
                continue;
            }
            if (!isProse(line)) continue;
            if (line.endsWith("  ")) problems.add((i + 1) + ": trailing hard line break");
            var text = prose(line);
            if (hasTwoSentences(text)) problems.add((i + 1) + ": two sentences on one line");
            if (i + 1 < lines.size() && continues(lines.get(i + 1)) && !ENDS_SENTENCE.matcher(text.strip()).find()) {
                problems.add((i + 1) + ": a sentence wrapped onto the next line");
            }
        }
        return problems;
    }

    private static boolean isProse(String line) {
        var s = line.strip();
        return !s.isEmpty() && !s.startsWith("#") && !s.startsWith("|") && !s.startsWith("<")
                && !LINK_DEFINITION.matcher(line).find() && !RULE.matcher(line).find();
    }

    /** The next line carries on the paragraph, item or quote of the line before it. */
    private static boolean continues(String next) {
        return isProse(next) && !LIST_ITEM.matcher(next).find() && !FENCE.matcher(next).find();
    }

    /** The line's text without quote and list markers or link targets; a code span can start a sentence. */
    private static String prose(String line) {
        var s = line.strip().replaceFirst("^(>\\s*)+", "");
        s = LIST_ITEM.matcher(s).replaceFirst("");
        s = INLINE_CODE.matcher(s).replaceAll("Code");
        return LINK_TARGET.matcher(s).replaceAll("]");
    }

    private static boolean hasTwoSentences(String text) {
        var m = BOUNDARY.matcher(text);
        while (m.find()) {
            var before = LAST_WORD.matcher(text.substring(0, m.start(1)));
            var word = before.find() ? before.group(1).replaceAll("^[(\"'*_]+", "").toLowerCase() : "";
            if (!ABBREVIATIONS.contains(word)) return true;
        }
        return false;
    }
}
