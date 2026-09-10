package dev.incusspawn.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Surface-agnostic link detection and OSC 8 terminal hyperlink generation.
 */
public final class TerminalLink {

    private TerminalLink() {}

    private static final String OSC8_OPEN = "\033]8;;";
    private static final String ST = "\033\\";
    private static final String OSC8_CLOSE = "\033]8;;" + ST;

    // Markdown link [text](url), or bare https?:// URL.
    // Markdown link group: (1)=label, (2)=url.  Bare URL group: (3)=url.
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "\\[([^\\]]+)]\\((https?://[^)]+)\\)"
                    + "|(https?://\\S+)");

    public sealed interface Segment {
        record Text(String text) implements Segment {}
        record Link(String url, String label) implements Segment {}
    }

    /** Wrap a URL as an OSC 8 clickable hyperlink (URL shown as display text). */
    public static String link(String url) {
        return link(url, url);
    }

    /** Wrap a URL as an OSC 8 clickable hyperlink with custom display text. */
    public static String link(String url, String label) {
        return OSC8_OPEN + sanitize(url) + ST + sanitize(label) + OSC8_CLOSE;
    }

    /**
     * Parse text into {@link Segment}s, detecting markdown links {@code [text](url)}
     * and bare {@code https://} URLs.
     */
    public static List<Segment> parseSegments(String text) {
        var segments = new ArrayList<Segment>();
        var matcher = LINK_PATTERN.matcher(text);
        int pos = 0;
        while (matcher.find()) {
            if (matcher.start() > pos) {
                segments.add(new Segment.Text(text.substring(pos, matcher.start())));
            }
            if (matcher.group(1) != null) {
                segments.add(new Segment.Link(
                        sanitize(matcher.group(2)), sanitize(matcher.group(1))));
                pos = matcher.end();
            } else {
                var rawUrl = matcher.group(3);
                var url = sanitize(stripTrailingPunctuation(rawUrl));
                segments.add(new Segment.Link(url, url));
                pos = matcher.start() + stripTrailingPunctuation(rawUrl).length();
            }
        }
        if (pos < text.length()) {
            segments.add(new Segment.Text(text.substring(pos)));
        }
        return segments;
    }

    /** Visible character count after links are resolved to their display text. */
    public static int displayLength(String text) {
        return displayLength(parseSegments(text));
    }

    /** Visible character count from pre-parsed segments. */
    public static int displayLength(List<Segment> segments) {
        int len = 0;
        for (var seg : segments) {
            len += switch (seg) {
                case Segment.Text t -> t.text().length();
                case Segment.Link l -> l.label().length();
            };
        }
        return len;
    }

    /**
     * Word-wrap pre-parsed segments at {@code width} display columns,
     * treating links as unbreakable tokens.
     */
    public static List<List<Segment>> wrapSegments(List<Segment> segments, int width) {
        var result = new ArrayList<List<Segment>>();
        var currentLine = new ArrayList<Segment>();
        int col = 0;

        for (var seg : segments) {
            switch (seg) {
                case Segment.Link l -> {
                    int linkWidth = l.label().length();
                    if (col + linkWidth > width && col > 0) {
                        result.add(currentLine);
                        currentLine = new ArrayList<>();
                        col = 0;
                    }
                    currentLine.add(l);
                    col += linkWidth;
                }
                case Segment.Text t -> {
                    var text = t.text();
                    int textPos = 0;
                    while (textPos < text.length()) {
                        int remaining = width - col;
                        if (textPos + remaining >= text.length()) {
                            currentLine.add(new Segment.Text(text.substring(textPos)));
                            col += text.length() - textPos;
                            break;
                        }
                        int breakAt = text.lastIndexOf(' ', textPos + remaining);
                        if (breakAt <= textPos) breakAt = textPos + remaining;
                        currentLine.add(new Segment.Text(text.substring(textPos, breakAt)));
                        result.add(currentLine);
                        currentLine = new ArrayList<>();
                        col = 0;
                        textPos = breakAt;
                        if (textPos < text.length() && text.charAt(textPos) == ' ') textPos++;
                    }
                }
            }
        }
        if (!currentLine.isEmpty()) {
            result.add(currentLine);
        }
        return result;
    }

    /**
     * Replace all links in {@code text} with OSC 8 clickable hyperlinks.
     * For markdown links, the display text is the link label; for bare URLs
     * the URL itself is shown.
     */
    public static String linkify(String text) {
        var segments = parseSegments(text);
        if (segments.size() == 1 && segments.getFirst() instanceof Segment.Text) {
            return text;
        }
        var sb = new StringBuilder();
        for (var seg : segments) {
            switch (seg) {
                case Segment.Text t -> sb.append(t.text());
                case Segment.Link l -> sb.append(link(l.url(), l.label()));
            }
        }
        return sb.toString();
    }

    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;!?".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return end > 0 ? url.substring(0, end) : url;
    }

    private static String sanitize(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                var sb = new StringBuilder(s.length());
                for (int j = 0; j < s.length(); j++) {
                    c = s.charAt(j);
                    if (c >= 0x20 && c != 0x7f) sb.append(c);
                }
                return sb.toString();
            }
        }
        return s;
    }
}
