package dev.incusspawn;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.TemplateValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A guide is one task on a page of its own, {@code docs/guides/<slug>.md} at
 * {@code isx.run/guides/<slug>} (site/render.js), and a landing page for a search (#2682). So each one
 * states the description a search result shows, the site's map for AI tools lists it, the deploy
 * runs when it changes, and the prose gates read it like every other user page.
 */
@Tag("docs")
class GuidesTest {

    private static final Path ROOT = Path.of("..");
    /** What a search result shows of a description before it cuts it. */
    private static final int DESCRIPTION_MAX = 160;
    private static final Pattern DESCRIPTION = Pattern.compile("(?m)^description:[ \\t]*(\\S.*)$");
    private static final Pattern TITLE = Pattern.compile("(?m)^# \\S");
    /** How many guides make them a section of the site: site/render.js's {@code SECTION_FROM}. */
    private static final Pattern SECTION_FROM = Pattern.compile("(?m)^const SECTION_FROM = (\\d+);");
    private static final Pattern NAV_LINK = Pattern.compile("<li><a href=\"/?guides\">Guides</a></li>");
    /** A page's list of site links: the nav's, and the home page's footer, which repeats it. */
    private static final Pattern LINK_LIST = Pattern.compile("(?s)<ul class=\"(nav|footer)-links\">.*?</ul>");
    /** A YAML code block in a guide. */
    private static final Pattern YAML_BLOCK = Pattern.compile("(?ms)^```yaml\n(.*?)^```");
    private static final Pattern TEMPLATE_NAME = Pattern.compile("(?m)^name:\\s");
    private static final Pattern QUOTED = Pattern.compile("^([\"'])(.*)\\1$");

    /** Every guide's source, sorted. */
    static List<Path> guides() throws IOException {
        var dir = ROOT.resolve("docs/guides");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.toString().endsWith(".md")).sorted().toList();
        }
    }

    private static String slug(Path guide) {
        var name = guide.getFileName().toString();
        return name.substring(0, name.length() - 3).toLowerCase();
    }

    /** The text without its fenced code blocks, where a line starting with # is a shell comment. */
    private static String outsideFences(String text) {
        var kept = new StringBuilder();
        var fenced = false;
        for (var line : text.lines().toList()) {
            if (line.startsWith("```")) fenced = !fenced;
            else if (!fenced) kept.append(line).append('\n');
        }
        return kept.toString();
    }

    /** Whether render.js writes the /guides index and the sidebar group, from how many guides there are. */
    private static boolean section() throws IOException {
        var from = SECTION_FROM.matcher(Files.readString(ROOT.resolve("site/render.js")));
        assertTrue(from.find(), "site/render.js does not declare const SECTION_FROM = <n>;");
        return guides().size() >= Integer.parseInt(from.group(1));
    }

    @Test
    void thereIsAGuide() throws IOException {
        assertFalse(guides().isEmpty(), "docs/guides/ holds no guide, so the site would publish none");
    }

    @Test
    void everyGuideStatesItsDescriptionAndTitle() throws IOException {
        var problems = new ArrayList<String>();
        for (var guide : guides()) {
            var text = Files.readString(guide);
            var name = ROOT.relativize(guide).toString();
            var file = guide.getFileName().toString();
            if (!file.equals(file.toLowerCase())) {
                problems.add(name + ": a guide's file name is lowercase, as its address /guides/" + slug(guide) + " is");
            }
            var end = text.startsWith("---\n") ? text.indexOf("\n---\n", 3) : -1;
            if (end < 0) {
                problems.add(name + ": no front matter; a guide opens with --- and its description:");
                continue;
            }
            var description = DESCRIPTION.matcher(text.substring(0, end));
            if (!description.find()) problems.add(name + ": its front matter has no description:");
            else {
                // site/render.js drops the quotes around a quoted value, so they are not counted
                var shown = QUOTED.matcher(description.group(1).strip()).replaceFirst("$2");
                if (shown.length() > DESCRIPTION_MAX) {
                    problems.add(name + ": its description has " + shown.length()
                            + " characters; a search result shows " + DESCRIPTION_MAX);
                }
            }
            if (TITLE.matcher(outsideFences(text.substring(end))).results().count() != 1) {
                problems.add(name + ": a guide has one # title, the phrase a person searches for");
            }
        }
        assertEquals(List.of(), problems);
    }

    /**
     * Every whole template a guide shows, a block with a {@code name:}, passes the check
     * {@code isx templates validate} runs, and names only tools isx ships, so a reader who copies
     * it gets a template that builds. Its parent may be a built-in, {@code tpl-agent} (which
     * {@code isx init} writes) or a template another guide shows.
     */
    @Test
    void everyTemplateAGuideShowsValidates() throws IOException {
        var templates = new ArrayList<Map.Entry<String, String>>();
        for (var guide : guides()) {
            var blocks = YAML_BLOCK.matcher(Files.readString(guide));
            while (blocks.find()) {
                if (TEMPLATE_NAME.matcher(blocks.group(1)).find()) {
                    templates.add(Map.entry(ROOT.relativize(guide).toString(), blocks.group(1)));
                }
            }
        }
        assertFalse(templates.isEmpty(), "no guide shows a template, so this test checks nothing");
        var known = new HashMap<>(ImageDef.loadBuiltins());
        known.put("tpl-agent", new ImageDef());
        for (var template : templates) known.put(ImageDef.parseYaml(template.getValue()).getName(), new ImageDef());
        var tools = DocsListsTest.builtInTools().keySet();
        var problems = new ArrayList<String>();
        for (var template : templates) {
            var result = TemplateValidator.validate(template.getValue(), Path.of("guide-template.yaml"), known);
            result.errors().forEach(e -> problems.add(template.getKey() + ": " + e));
            result.warnings().forEach(w -> problems.add(template.getKey() + ": " + w));
            for (var tool : ImageDef.parseYaml(template.getValue()).getTools()) {
                if (!tools.contains(tool.getName())) problems.add(template.getKey() + ": no built-in tool '" + tool.getName() + "'");
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void llmsTxtListsEveryGuide() throws IOException {
        var llms = Files.readString(ROOT.resolve("site/llms.txt"));
        assertEquals(section(), llms.contains("(https://isx.run/guides)"), section()
                ? "site/llms.txt does not link the guides index"
                : "site/llms.txt links /guides, which render.js writes only once the guides are a section");
        for (var guide : guides()) {
            assertTrue(llms.contains("(https://isx.run/guides/" + slug(guide) + ")"),
                    "site/llms.txt does not list " + ROOT.relativize(guide));
        }
    }

    /** The nav links Guides on every page, and the home page's footer too, exactly when the guides are a section with an index to link. */
    @Test
    void theNavLinksTheGuidesOnlyOnceTheyAreASection() throws IOException {
        var problems = new ArrayList<String>();
        var section = section();
        for (var page : List.of("index.html", "docs.html", "404.html")) {
            var lists = LINK_LIST.matcher(Files.readString(ROOT.resolve("site/" + page)));
            while (lists.find()) {
                var which = lists.group(1);
                if (which.equals("footer") && !page.equals("index.html")) continue;
                var links = NAV_LINK.matcher(lists.group()).find();
                if (links != section) problems.add("site/" + page + "'s " + which + (links
                        ? " links /guides, which is not written yet" : " does not link /guides"));
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void theSiteIsBuiltWhenAGuideChanges() throws IOException {
        for (var workflow : List.of("pages.yml", "preview.yml")) {
            var text = Files.readString(ROOT.resolve(".github/workflows/" + workflow));
            assertTrue(text.contains("- 'docs/guides/*.md'"),
                    workflow + " does not run when a guide changes, so the site would not show it");
        }
    }

    @Test
    void theProseGatesReadTheGuides() throws IOException {
        for (var guide : guides()) {
            assertTrue(DocsShapeTest.pages().contains(guide), "DocsShapeTest does not read " + guide);
            assertTrue(DocsOneSentencePerLineTest.documents().contains(guide),
                    "DocsOneSentencePerLineTest does not read " + guide);
        }
    }
}
