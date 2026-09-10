package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerminalLinkTest {

    private static final String OSC8_OPEN = "\033]8;;";
    private static final String ST = "\033\\";
    private static final String OSC8_CLOSE = "\033]8;;" + ST;

    @Test
    void linkUsesUrlAsDisplayText() {
        var result = TerminalLink.link("https://example.com");
        assertEquals(OSC8_OPEN + "https://example.com" + ST + "https://example.com" + OSC8_CLOSE, result);
    }

    @Test
    void linkWithLabel() {
        var result = TerminalLink.link("https://example.com", "Example");
        assertEquals(OSC8_OPEN + "https://example.com" + ST + "Example" + OSC8_CLOSE, result);
    }

    @Test
    void parseSegmentsPlainText() {
        var segments = TerminalLink.parseSegments("no links here");
        assertEquals(1, segments.size());
        assertInstanceOf(TerminalLink.Segment.Text.class, segments.getFirst());
        assertEquals("no links here", ((TerminalLink.Segment.Text) segments.getFirst()).text());
    }

    @Test
    void parseSegmentsBareUrl() {
        var segments = TerminalLink.parseSegments("visit https://example.com for info");
        assertEquals(3, segments.size());
        assertEquals("visit ", ((TerminalLink.Segment.Text) segments.get(0)).text());
        var link = (TerminalLink.Segment.Link) segments.get(1);
        assertEquals("https://example.com", link.url());
        assertEquals("https://example.com", link.label());
        assertEquals(" for info", ((TerminalLink.Segment.Text) segments.get(2)).text());
    }

    @Test
    void parseSegmentsMarkdownLink() {
        var segments = TerminalLink.parseSegments("see [the docs](https://example.com/docs) here");
        assertEquals(3, segments.size());
        assertEquals("see ", ((TerminalLink.Segment.Text) segments.get(0)).text());
        var link = (TerminalLink.Segment.Link) segments.get(1);
        assertEquals("https://example.com/docs", link.url());
        assertEquals("the docs", link.label());
        assertEquals(" here", ((TerminalLink.Segment.Text) segments.get(2)).text());
    }

    @Test
    void parseSegmentsStripsTrailingPunctuation() {
        var segments = TerminalLink.parseSegments("Go to https://example.com.");
        assertEquals(3, segments.size());
        var link = (TerminalLink.Segment.Link) segments.get(1);
        assertEquals("https://example.com", link.url());
        assertEquals(".", ((TerminalLink.Segment.Text) segments.get(2)).text());
    }

    @Test
    void parseSegmentsMultipleLinks() {
        var segments = TerminalLink.parseSegments("https://a.com and https://b.com");
        assertEquals(3, segments.size());
        assertEquals("https://a.com", ((TerminalLink.Segment.Link) segments.get(0)).url());
        assertEquals(" and ", ((TerminalLink.Segment.Text) segments.get(1)).text());
        assertEquals("https://b.com", ((TerminalLink.Segment.Link) segments.get(2)).url());
    }

    @Test
    void parseSegmentsUrlAtStartAndEnd() {
        var segments = TerminalLink.parseSegments("https://start.com");
        assertEquals(1, segments.size());
        assertEquals("https://start.com", ((TerminalLink.Segment.Link) segments.getFirst()).url());
    }

    @Test
    void linkifyPlainTextUnchanged() {
        assertEquals("no links here", TerminalLink.linkify("no links here"));
    }

    @Test
    void linkifyWrapsUrls() {
        var result = TerminalLink.linkify("visit https://example.com now");
        var expected = "visit " + TerminalLink.link("https://example.com") + " now";
        assertEquals(expected, result);
    }

    @Test
    void linkifyConvertsMarkdownLinks() {
        var result = TerminalLink.linkify("see [docs](https://example.com/docs) here");
        var expected = "see " + TerminalLink.link("https://example.com/docs", "docs") + " here";
        assertEquals(expected, result);
    }

    @Test
    void displayLengthPlainText() {
        assertEquals(13, TerminalLink.displayLength("no links here"));
    }

    @Test
    void displayLengthMarkdownLink() {
        assertEquals("see docs here".length(),
                TerminalLink.displayLength("see [docs](https://example.com/docs) here"));
    }

    @Test
    void displayLengthBareUrl() {
        assertEquals("visit https://example.com now".length(),
                TerminalLink.displayLength("visit https://example.com now"));
    }

    @Test
    void linkSanitizesControlChars() {
        var result = TerminalLink.link("https://example.com/\033evil", "cl\033ick");
        assertEquals(OSC8_OPEN + "https://example.com/evil" + ST + "click" + OSC8_CLOSE, result);
    }

    @Test
    void parseSegmentsSanitizesControlChars() {
        var segments = TerminalLink.parseSegments("see [cl\033ick](https://example.com/\033x)");
        var link = (TerminalLink.Segment.Link) segments.get(1);
        assertEquals("https://example.com/x", link.url());
        assertEquals("click", link.label());
    }

    @Test
    void wrapSegmentsKeepsLinkAtomic() {
        var segments = List.<TerminalLink.Segment>of(
                new TerminalLink.Segment.Text("see "),
                new TerminalLink.Segment.Link("https://example.com", "docs"),
                new TerminalLink.Segment.Text(" for info"));
        // "see " (4) + "docs" (4) = 8 fits in 20; + " for info" (9) = 17 fits
        var wrapped = TerminalLink.wrapSegments(segments, 20);
        assertEquals(1, wrapped.size());

        // At width 6: "see " (4) + "docs" (4) > 6, link wraps to next line
        wrapped = TerminalLink.wrapSegments(segments, 6);
        assertTrue(wrapped.size() >= 2);
        assertInstanceOf(TerminalLink.Segment.Text.class, wrapped.get(0).getFirst());
        assertInstanceOf(TerminalLink.Segment.Link.class, wrapped.get(1).getFirst());
    }

    @Test
    void wrapSegmentsFitsOnOneLine() {
        var segments = List.<TerminalLink.Segment>of(
                new TerminalLink.Segment.Text("see "),
                new TerminalLink.Segment.Link("https://example.com", "docs"));
        var wrapped = TerminalLink.wrapSegments(segments, 80);
        assertEquals(1, wrapped.size());
        assertEquals(2, wrapped.getFirst().size());
    }

    @Test
    void parseSegmentsHttpUrl() {
        var segments = TerminalLink.parseSegments("http://insecure.com");
        assertEquals(1, segments.size());
        assertEquals("http://insecure.com", ((TerminalLink.Segment.Link) segments.getFirst()).url());
    }

    @Test
    void parseSegmentsUrlWithQueryAndFragment() {
        var segments = TerminalLink.parseSegments("https://example.com/path?q=1&a=2#section");
        assertEquals(1, segments.size());
        var link = (TerminalLink.Segment.Link) segments.getFirst();
        assertEquals("https://example.com/path?q=1&a=2#section", link.url());
    }
}
