package dev.incusspawn;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every page of isx.run names the brand last in its title, {@code "<what the page is> — isx"}, and
 * only the home page starts with it, so a search result leads with the words a person searched for.
 * site/render.js writes the titles of the docs and guide pages and fails the site's build on one
 * that breaks this; this test holds what it can check without Node: the hand-written heads, the
 * guides' {@code title:} front matter, and render.js's own suffix and limit.
 */
@Tag("docs")
class SiteTitlesTest {

    private static final Path ROOT = Path.of("..");
    private static final String SUFFIX = " — isx";
    private static final Pattern RENDER_SUFFIX = Pattern.compile("(?m)^const TITLE_SUFFIX = '([^']*)';");
    private static final Pattern RENDER_MAX = Pattern.compile("(?m)^const TITLE_MAX = (\\d+);");
    private static final Pattern HEAD_TITLES = Pattern.compile(
            "<title>(.*?)</title>|<meta (?:property=\"og|name=\"twitter):title\" content=\"([^\"]*)\"");
    private static final Pattern FRONT_TITLE = Pattern.compile("(?m)^title:[ \\t]*(\\S.*)$");
    private static final Pattern QUOTED = Pattern.compile("^([\"'])(.*)\\1$");
    private static final Pattern BRAND = Pattern.compile("(?i)\\bisx\\b");

    private static String renderJs() throws IOException {
        return Files.readString(ROOT.resolve("site/render.js"));
    }

    /** The longest title render.js lets through: what a search result shows before it cuts it. */
    private static int titleMax() throws IOException {
        var max = RENDER_MAX.matcher(renderJs());
        assertTrue(max.find(), "site/render.js does not declare const TITLE_MAX = <n>;");
        return Integer.parseInt(max.group(1));
    }

    /** The {@code <title>} of a page, and its {@code og:title} and {@code twitter:title} where it has them, decoded. */
    private static List<String> headTitles(String page) throws IOException {
        var titles = new ArrayList<String>();
        var m = HEAD_TITLES.matcher(Files.readString(ROOT.resolve("site/" + page)));
        while (m.find()) {
            var raw = m.group(1) != null ? m.group(1) : m.group(2);
            titles.add(raw.replace("&mdash;", "—").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\""));
        }
        assertFalse(titles.isEmpty(), "site/" + page + " has no <title>");
        return titles;
    }

    @Test
    void renderJsEndsEveryTitleWithTheBrand() throws IOException {
        var suffix = RENDER_SUFFIX.matcher(renderJs());
        assertTrue(suffix.find(), "site/render.js does not declare const TITLE_SUFFIX = '...';");
        assertEquals(SUFFIX, suffix.group(1));
    }

    @Test
    void theHomePageStartsWithTheBrand() throws IOException {
        for (var title : headTitles("index.html")) {
            assertTrue(title.startsWith("isx: "), "site/index.html's title \"" + title + "\" starts with \"isx: \"");
            assertTrue(title.length() <= titleMax(), "site/index.html's title \"" + title + "\" is longer than " + titleMax());
        }
    }

    @Test
    void everyOtherHandWrittenHeadEndsWithTheBrand() throws IOException {
        var problems = new ArrayList<String>();
        try (var files = Files.list(ROOT.resolve("site"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".html")).sorted().toList()) {
                var page = file.getFileName().toString();
                if (page.equals("index.html")) continue;
                for (var title : headTitles(page)) {
                    if (!title.endsWith(SUFFIX)) problems.add("site/" + page + ": \"" + title + "\" does not end with \"" + SUFFIX + "\"");
                    else if (title.length() > titleMax()) problems.add("site/" + page + ": \"" + title + "\" is longer than " + titleMax());
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    /**
     * A guide states the title a search result shows in its front matter, since its heading names
     * isx where a reader needs it and render.js adds the brand at the end.
     */
    @Test
    void everyGuideStatesItsSearchTitle() throws IOException {
        var problems = new ArrayList<String>();
        for (var guide : GuidesTest.guides()) {
            var text = Files.readString(guide);
            var name = ROOT.relativize(guide).toString();
            var end = text.startsWith("---\n") ? text.indexOf("\n---\n", 3) : -1;
            var title = end < 0 ? null : FRONT_TITLE.matcher(text.substring(0, end));
            if (title == null || !title.find()) {
                problems.add(name + ": its front matter has no title:, the title a search result shows");
                continue;
            }
            // site/render.js drops the quotes around a quoted value, so they are not counted
            var shown = QUOTED.matcher(title.group(1).strip()).replaceFirst("$2");
            if (BRAND.matcher(shown).find()) {
                problems.add(name + ": its title: names isx, which render.js adds at the end");
            }
            if ((shown + SUFFIX).length() > titleMax()) {
                problems.add(name + ": \"" + shown + SUFFIX + "\" has " + (shown + SUFFIX).length()
                        + " characters; a search result shows " + titleMax());
            }
        }
        assertEquals(List.of(), problems);
    }
}
