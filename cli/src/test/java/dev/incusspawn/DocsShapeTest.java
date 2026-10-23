package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The README and the docs/ pages are read by people with little patience, so their prose keeps to
 * budgets: a paragraph or list item of at most four sentences and 60 words, a sentence of at most 40
 * words, no dash joining two parts of a sentence, and no issue number outside a link
 * (.claude/rules/writing.md "Shape" and "Voice"). Headings, tables, code, HTML and front matter are
 * exempt from the budgets; the dash rule holds in headings and table cells too.
 */
class DocsShapeTest {

    private static final Path ROOT = Path.of("..");

    static final int MAX_SENTENCES = 4;
    static final int MAX_WORDS = 60;
    static final int MAX_SENTENCE_WORDS = 40;

    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})");
    private static final Pattern RULE = Pattern.compile("^\\s*(?:[-*_]\\s*){3,}$|^\\s*=+\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}");
    private static final Pattern INLINE_CODE = Pattern.compile("(`+).+?\\1");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern BARE_URL = Pattern.compile("<https?://[^>]*>|https?://\\S+");
    private static final Pattern LINK_DEFINITION = Pattern.compile("^\\s*\\[[^]]+]:\\s");
    /** An em-dash, an en-dash, or a hyphen run standing between words. */
    private static final Pattern DASH = Pattern.compile("[–—]|(?<=\\s)-{1,3}(?=\\s)");
    /** An issue number, outside a link: {@code #1243} but not {@code a#b} or a path. */
    private static final Pattern ISSUE = Pattern.compile("(?<![\\w/\\[])#\\d+\\b");

    @Test
    void theUserPagesKeepToTheBudgets() throws IOException {
        var offenders = new ArrayList<String>();
        for (var file : pages()) {
            var name = ROOT.relativize(file).toString();
            for (var problem : problems(Files.readAllLines(file))) offenders.add(name + ":" + problem);
        }
        assertTrue(offenders.isEmpty(), "keep to the shape of a user page (.claude/rules/writing.md):\n"
                + String.join("\n", offenders));
    }

    @Test
    void aLongParagraphIsFound() {
        assertEquals(List.of("1: 5 sentences in one paragraph, at most 4"),
                problems(List.of("One.", "Two.", "Three.", "Four.", "Five.")));
        var words = "word ".repeat(61).strip() + ".";
        assertEquals(List.of("1: 61 words in one sentence, at most 40", "1: 61 words in one paragraph, at most 60"),
                problems(List.of(words)));
        assertEquals(List.of("2: 5 sentences in one paragraph, at most 4"),
                problems(List.of("- An item.", "- One.", "  Two.", "  Three.", "  Four.", "  Five.", "- Short.")));
    }

    @Test
    void aLongSentenceIsFound() {
        var sentence = "word ".repeat(41).strip() + ".";
        assertEquals(List.of("2: 41 words in one sentence, at most 40"), problems(List.of("Fine.", sentence)));
    }

    @Test
    void aDashIsFound() {
        assertEquals(List.of("1: a dash joins the sentence"), problems(List.of("One thing — another.")));
        assertEquals(List.of("1: a dash joins the sentence"), problems(List.of("One thing -- another.")));
        assertEquals(List.of("1: a dash joins the sentence"), problems(List.of("Pages 9–10.")));
        assertEquals(List.of("1: a dash joins the sentence"), problems(List.of("## Heading — with a dash")));
        assertEquals(List.of("1: a dash joins the sentence"), problems(List.of("| cell -- cell | b |")));
    }

    @Test
    void anIssueNumberIsFound() {
        assertEquals(List.of("1: an issue number outside a link"), problems(List.of("Tracked in #1243.")));
        assertEquals(List.of(), problems(List.of("Tracked in [#1243](https://x.y/1243).")));
        assertEquals(List.of(), problems(List.of("A heading [like this](#quick-start) or `#1` in code.")));
    }

    @Test
    void whatIsAllowedPasses() {
        assertEquals(List.of(), problems(List.of(
                "A paragraph of one idea.",
                "Its second sentence, with `--flags --in-code` and copy-on-write words.",
                "Its third, with a [link](https://x.y/a--b) and https://x.y/c--d in it.",
                "",
                "- An item with a hyphenated-word.",
                "  Its second sentence.",
                "- Another.",
                "",
                "| Flag | What |",
                "|------|------|",
                "| `--vm` | a VM |",
                "",
                "```",
                "code -- with dashes — and #12",
                "```",
                "<!-- a comment -- with #12 -->",
                "---",
                "[ref]: https://x.y \"Title -- two\"")));
    }

    private static List<Path> pages() throws IOException {
        var files = new ArrayList<Path>();
        files.add(ROOT.resolve("README.md"));
        try (Stream<Path> docs = Files.list(ROOT.resolve("docs"))) {
            docs.filter(f -> f.toString().endsWith(".md")).sorted().forEach(files::add);
        }
        return files;
    }

    /** Each problem as {@code <line>: <what>}, with the block's first line, 1-based. */
    static List<String> problems(List<String> lines) {
        var problems = new ArrayList<String>();
        var block = new Block(0);
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
                block = block.close(problems);
                fence = opens.group(1);
                continue;
            }
            if (fence != null) {
                var s = line.strip();
                char mark = fence.charAt(0);
                if (s.length() >= fence.length() && s.chars().allMatch(c -> c == mark)) fence = null;
                continue;
            }
            if (comment || line.strip().startsWith("<!--")) {
                block = block.close(problems);
                comment = !line.contains("-->");
                continue;
            }
            var s = line.strip();
            if (s.isEmpty() || s.startsWith("<") || LINK_DEFINITION.matcher(line).find() || RULE.matcher(line).find()) {
                block = block.close(problems);
                continue;
            }
            var text = text(line);
            if (DASH.matcher(text).find()) problems.add((i + 1) + ": a dash joins the sentence");
            if (s.startsWith("#") || s.startsWith("|")) {
                block = block.close(problems);
                continue;
            }
            if (ISSUE.matcher(withoutLinks(line)).find()) problems.add((i + 1) + ": an issue number outside a link");
            int words = words(text);
            if (words > MAX_SENTENCE_WORDS) {
                problems.add((i + 1) + ": " + words + " words in one sentence, at most " + MAX_SENTENCE_WORDS);
            }
            if (LIST_ITEM.matcher(line).find()) block = block.close(problems);
            if (block.start == 0) block = new Block(i + 1);
            block.sentences++;
            block.words += words;
        }
        block.close(problems);
        return problems;
    }

    /** The line's prose: no list or quote markers, link targets, bare URLs or code span contents. */
    private static String text(String line) {
        var s = line.strip().replaceFirst("^(>\\s*)+", "");
        s = LIST_ITEM.matcher(s).replaceFirst("");
        if (TABLE_SEPARATOR.matcher(s).find()) return "";
        s = INLINE_CODE.matcher(s).replaceAll("code");
        s = LINK.matcher(s).replaceAll("$1");
        return BARE_URL.matcher(s).replaceAll("url");
    }

    /** The line with code spans and whole links taken out, where an issue number may not stand. */
    private static String withoutLinks(String line) {
        var s = INLINE_CODE.matcher(line).replaceAll("code");
        return LINK.matcher(s).replaceAll("link");
    }

    private static int words(String text) {
        var s = text.strip();
        return s.isEmpty() ? 0 : s.split("\\s+").length;
    }

    private static final class Block {
        final int start;
        int sentences;
        int words;

        Block(int start) {
            this.start = start;
        }

        /** Reports the block's problems, if any, and returns an empty one. */
        Block close(List<String> problems) {
            if (start > 0) {
                if (sentences > MAX_SENTENCES) {
                    problems.add(start + ": " + sentences + " sentences in one paragraph, at most " + MAX_SENTENCES);
                }
                if (words > MAX_WORDS) {
                    problems.add(start + ": " + words + " words in one paragraph, at most " + MAX_WORDS);
                }
            }
            return new Block(0);
        }
    }
}
