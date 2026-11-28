package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.command.OverviewCommand;
import dev.incusspawn.command.PullRequestStates;
import dev.incusspawn.command.WorkState;
import dev.incusspawn.proxy.EventLogReader;
import dev.incusspawn.util.OutputFormat;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What the Work tab's table shows of an instance (design/terminal.md "The overview of what each
 * instance did"): the short cells it is scanned by (IDLE, WORK), the name cut in its middle, and
 * the second line under the row with its purpose and its pull requests and branches. Each is a
 * pure function of what the overview read, so a test asks it directly.
 */
final class WorkTable {
    private WorkTable() {}

    /** The most cells a name takes in the Work table, its glyph aside: past it, the middle is cut. */
    static final int NAME_MOST = 38;
    /** The least a name is cut to. */
    static final int NAME_LEAST = 12;
    /** Where the second line starts, from the table's left edge: past the cursor gutter and the state glyph. */
    static final int SECOND_LINE_INDENT = 6;
    /** IDLE's width: {@code 44m}, {@code 22h}, {@code 3d}, {@code 4w}, right-aligned. */
    static final int IDLE_WIDTH = 4;
    /** TOKENS' width: {@code 151.8k}, right-aligned. */
    static final int TOKENS_WIDTH = 6;
    /** The narrowest terminal with the preview beside the table; narrower, {@code p} shows it under the table. */
    static final int SIDE_PREVIEW_MIN_WIDTH = 110;
    /** The cells the table keeps beside the preview, until the preview reaches {@link #PREVIEW_MOST}. */
    static final int TABLE_BESIDE_PREVIEW = 64;
    static final int PREVIEW_LEAST = 44;
    static final int PREVIEW_MOST = 90;
    /** The rows the preview takes under the table, its rule included, at most. */
    static final int PREVIEW_BELOW_MOST = 15;

    /** The preview's width beside a panel {@code inner} cells wide; the table takes the rest, less the divider. */
    static int previewWidth(int inner) {
        return Math.min(PREVIEW_MOST, Math.max(PREVIEW_LEAST, inner - TABLE_BESIDE_PREVIEW));
    }

    /**
     * The time since {@code since}, compact: minutes below an hour ({@code 44m}), hours below a
     * day ({@code 22h}), days below two weeks ({@code 3d}), then weeks ({@code 4w}); {@code -}
     * when there is no time.
     */
    static String idle(Duration since) {
        if (since == null) return "-";
        long m = Math.max(0, since.toMinutes());
        if (m < 60) return m + "m";
        if (m < 1440) return Math.round(m / 60.0) + "h";
        if (m < 20160) return Math.round(m / 1440.0) + "d";
        return Math.round(m / 10080.0) + "w";
    }

    /** The same in words, for the preview: {@code 1 minute}, {@code 3 hours}, {@code 2 days}, {@code 5 weeks}. */
    static String idleWords(Duration since) {
        long m = Math.max(0, since.toMinutes());
        if (m < 60) return count(m, "minute");
        if (m < 1440) return count(Math.round(m / 60.0), "hour");
        if (m < 20160) return count(Math.round(m / 1440.0), "day");
        return count(Math.round(m / 10080.0), "week");
    }

    private static String count(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    /**
     * When the instance was last at work: the newer of its last start and the last line the
     * proxy's event log holds of it (a model call, a push or a pull request). Null when neither.
     */
    static Instant lastActive(InstanceInfo instance, EventLogReader.Effort effort) {
        var started = OverviewCommand.lastUsed(instance);
        var logged = effort == null ? null : effort.last();
        if (started == null) return logged;
        if (logged == null) return started;
        return logged.isAfter(started) ? logged : started;
    }

    /**
     * {@code text} within {@code width} terminal cells, cut in its middle with {@code …}: the
     * start and the distinguishing end stay, as in {@code mcp-isx-ag…2458-companion-y27vl}.
     */
    static String middleCut(String text, int width) {
        if (CharWidth.of(text) <= width) return text;
        if (width <= 1) return width == 1 ? ColumnFit.ELLIPSIS : "";
        int head = (int) Math.ceil((width - 1) * 0.4);
        int tail = width - 1 - head;
        var start = CharWidth.substringByWidth(text, head);
        // The end: as many whole code points from the right as fit in the tail's cells
        int from = text.length();
        int used = 0;
        while (from > 0) {
            int previous = text.offsetByCodePoints(from, -1);
            int w = CharWidth.of(text.substring(previous, from));
            if (used + w > tail) break;
            used += w;
            from = previous;
        }
        return start + ColumnFit.ELLIPSIS + text.substring(from);
    }

    /** A query that is a number alone, {@code 2728} or {@code #2728}: it names an issue or a pull request. */
    private static final java.util.regex.Pattern NUMBER_QUERY = java.util.regex.Pattern.compile("#?0*(\\d+)");
    /** A run of digits in a name, a branch or a purpose, each run a whole number. */
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("0*(\\d+)");

    /**
     * Whether the Work tab's search {@code query} finds {@code instance} (#2736): what the tab shows
     * of its work as well as what the Instances tab searches, that is its name, parent and address,
     * its purpose and {@code isx mcp}'s, the pull requests it opened, by {@code owner/repo#12} and by
     * address, and the branches it pushed. A number alone, with or without {@code #}, names an issue
     * or a pull request: it finds a pull request of that number and a whole number in the name, a
     * branch or a purpose, never part of a longer one nor of an address, so {@code 27} does not find
     * {@code 2728} or {@code 10.0.0.27}. {@code effort} is null until the event log is read.
     */
    static boolean matchesSearch(String query, InstanceInfo instance, EventLogReader.Effort effort) {
        if (query == null || query.isBlank()) return true;
        var pulls = effort == null ? List.<EventLogReader.PullRequest>of() : effort.pullRequests();
        var branches = effort == null ? List.<EventLogReader.Branch>of() : effort.branches();
        var mcpPurpose = instance.mcp() == null ? null : instance.mcp().purpose();
        var number = NUMBER_QUERY.matcher(query.strip());
        if (number.matches()) {
            var wanted = number.group(1);
            for (var pull : pulls) {
                if (String.valueOf(pull.number()).equals(wanted)) return true;
            }
            var texts = new ArrayList<String>(List.of(instance.name()));
            if (instance.purpose() != null) texts.add(instance.purpose());
            if (mcpPurpose != null) texts.add(mcpPurpose);
            for (var branch : branches) texts.add(branch.name());
            for (var text : texts) {
                var runs = DIGITS.matcher(text);
                while (runs.find()) {
                    if (runs.group(1).equals(wanted)) return true;
                }
            }
            return false;
        }
        var fields = new ArrayList<String>(List.of(instance.name()));
        java.util.Collections.addAll(fields, instance.parent(), instance.ipv4(), instance.purpose(), mcpPurpose);
        for (var pull : pulls) {
            fields.add(pull.toString());
            fields.add(pull.url());
        }
        for (var branch : branches) fields.add(branch.name());
        return Tui.matchesSearch(query, fields.toArray(String[]::new));
    }

    /** How a WORK cell reads: its text and the role of its colour. */
    enum Tone { OK, WARN, FAINT, DIM }

    /** A WORK cell: what it shows and in which tone. */
    record Work(String text, Tone tone) {}

    /**
     * The WORK cell: {@code ✓} clean, {@code ±3} changes not committed, {@code ↑1} commits not
     * pushed, both as {@code ±3↑1}, {@code ≡2} stashes alone, a trailing {@code ?} where a clone
     * could not be read, {@code ?} unknown, {@code –} not read (stopped), {@code …} while its
     * guest is read. An instance with no clone shows a dim {@code ✓}: there is nothing to lose.
     */
    static Work work(WorkState state, boolean awaits) {
        if (awaits) return new Work(ColumnFit.ELLIPSIS, Tone.DIM);
        return switch (state.state()) {
            case NOT_READ -> new Work(Glyphs.NOT_READ, Tone.FAINT);
            case UNKNOWN -> new Work("?", Tone.WARN);
            case NO_REPOS -> new Work(Glyphs.DONE, Tone.DIM);
            case CLEAN -> new Work(Glyphs.DONE, Tone.OK);
            case UNSAVED -> {
                var text = new StringBuilder();
                if (state.uncommitted() > 0) text.append(Glyphs.UNCOMMITTED).append(state.uncommitted());
                if (state.unpushed() > 0) text.append(Glyphs.UNPUSHED).append(state.unpushed());
                // Stashes are lost with the instance too: never shown as clean
                if (text.isEmpty()) text.append(Glyphs.STASHED).append(state.stashes());
                if (state.why() != null || state.repos().stream().anyMatch(r -> r.uncommitted() < 0
                        || r.unpushed() < 0 || r.stashes() < 0)) text.append('?');
                yield new Work(text.toString(), Tone.WARN);
            }
        };
    }

    /** What a pull request became, as far as GitHub said: open and unknown are shown alike. */
    enum PullState { OPEN, DONE }

    /**
     * One reference of the second line: its text, the page it links to (null for none), whether
     * it is a pushed branch, and what became of a pull request.
     */
    record Ref(String text, String url, boolean branch, PullState state) {}

    /**
     * The instance's references, pull requests first: the first of a repository as
     * {@code owner/repo#12}, the later ones of the same repository as {@code #14}, then each
     * pushed branch as {@code ⇡ name}. Names are the guest's text, shown on one line.
     */
    static List<Ref> references(EventLogReader.Effort effort, List<PullRequestStates.State> states) {
        var refs = new ArrayList<Ref>();
        var seen = new java.util.HashSet<String>();
        var pulls = effort.pullRequests();
        for (int i = 0; i < pulls.size(); i++) {
            var pull = pulls.get(i);
            var repo = OutputFormat.oneLine(pull.repo());
            var text = (seen.add(repo) ? repo : "") + "#" + pull.number();
            var state = i < states.size() ? states.get(i) : PullRequestStates.State.UNKNOWN;
            boolean done = state == PullRequestStates.State.MERGED || state == PullRequestStates.State.CLOSED;
            refs.add(new Ref(text, pull.url(), false, done ? PullState.DONE : PullState.OPEN));
        }
        for (var branch : effort.branches()) {
            refs.add(new Ref(Glyphs.PUSHED + " " + OutputFormat.oneLine(branch.name()), null, true, null));
        }
        return refs;
    }

    /** The references shown in {@code room} cells, and how many more there are ({@code +3}). */
    record Fitted(List<Ref> shown, int more) {
        /** The cells they take, one between each, the count included. */
        int width() {
            int w = 0;
            for (var ref : shown) w += (w == 0 ? 0 : 1) + CharWidth.of(ref.text());
            if (more > 0) w += (w == 0 ? 0 : 1) + 1 + String.valueOf(more).length();
            return w;
        }
    }

    /**
     * {@code refs} within {@code room} cells, each whole (Sanne on #2292), the rest counted as
     * {@code +N}. Only a pushed branch that comes first and alone does not fit is cut at its end.
     */
    static Fitted fitReferences(List<Ref> refs, int room) {
        var shown = new ArrayList<Ref>();
        int used = 0;
        for (int k = 0; k < refs.size(); k++) {
            var ref = refs.get(k);
            int add = (shown.isEmpty() ? 0 : 1) + CharWidth.of(ref.text());
            // Room for the count this reference leaves: those after it, or, when it is the last, none
            int after = refs.size() - k - 1;
            int reserve = after > 0 ? (" +" + after).length() : 0;
            if (used + add + reserve > room) {
                if (shown.isEmpty() && ref.branch() && room - reserve > 2) {
                    shown.add(new Ref(endCut(ref.text(), room - reserve), null, true, null));
                }
                return counted(shown, refs.size(), room);
            }
            shown.add(ref);
            used += add;
        }
        return new Fitted(List.copyOf(shown), 0);
    }

    /**
     * {@code shown} with the count of the rest, the count reported as it will be drawn: a
     * reference gives way to it when both do not fit, so the line never runs past {@code room}.
     */
    private static Fitted counted(List<Ref> shown, int total, int room) {
        var kept = new ArrayList<>(shown);
        var fitted = new Fitted(List.copyOf(kept), total - kept.size());
        while (!kept.isEmpty() && fitted.width() > room) {
            kept.removeLast();
            fitted = new Fitted(List.copyOf(kept), total - kept.size());
        }
        return fitted;
    }

    /** {@code text} within {@code width} cells, its last one an ellipsis when cut. */
    static String endCut(String text, int width) {
        if (CharWidth.of(text) <= width) return text;
        if (width <= 0) return "";
        return CharWidth.substringByWidth(text, width - 1) + ColumnFit.ELLIPSIS;
    }

    /** The least a purpose is given beside references, when it is that long. */
    static final int PURPOSE_LEAST = 24;
    private static final String SEPARATOR = " " + Glyphs.BETWEEN_PARTS + " ";

    /**
     * The second line of a row, {@code width} cells from its indent: the purpose, dim, then
     * {@code ·} and the references, each pull request a link. Null when the instance has neither.
     */
    static Line secondLine(String purpose, List<Ref> refs, int width, TuiTheme theme) {
        boolean hasPurpose = purpose != null && !purpose.isBlank();
        if (!hasPurpose && refs.isEmpty()) return null;
        var text = hasPurpose ? OutputFormat.oneLine(purpose) : null;
        int refRoom = hasPurpose ? width - Math.min(CharWidth.of(text), PURPOSE_LEAST) - SEPARATOR.length() : width;
        var fitted = fitReferences(refs, Math.max(0, refRoom));
        var spans = new ArrayList<Span>();
        boolean anyRef = !fitted.shown().isEmpty() || fitted.more() > 0;
        if (hasPurpose) {
            int room = anyRef ? width - fitted.width() - SEPARATOR.length() : width;
            spans.add(Span.styled(endCut(text, Math.max(4, room)), Style.EMPTY.fg(theme.textDim())));
            if (anyRef) spans.add(Span.styled(SEPARATOR, Style.EMPTY.fg(theme.textFaint())));
        }
        boolean first = true;
        for (var ref : fitted.shown()) {
            if (!first) spans.add(Span.raw(" "));
            first = false;
            spans.add(Span.styled(ref.text(), refStyle(ref, theme)));
        }
        if (fitted.more() > 0) {
            if (!first) spans.add(Span.raw(" "));
            spans.add(Span.styled("+" + fitted.more(), Style.EMPTY.fg(theme.textFaint())));
        }
        return Line.from(spans);
    }

    /** An open pull request in the link colour, a merged or closed one dimmed, each underlined and linked; a branch quiet. */
    static Style refStyle(Ref ref, TuiTheme theme) {
        if (ref.branch()) return Style.EMPTY.fg(theme.textDim());
        var style = Style.EMPTY.fg(ref.state() == PullState.DONE ? theme.textFaint() : theme.accent()).underlined();
        return ref.url() == null ? style : style.hyperlink(ref.url());
    }
}
