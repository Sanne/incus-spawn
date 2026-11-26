package dev.incusspawn.tui;

import dev.incusspawn.command.AgentSessions;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Work tab's preview of the selected instance (design/terminal.md "The overview of what each
 * instance did"): everything the table's row shortens, in words and whole. Its state, the purpose,
 * how long it has been idle, the tokens, the clones' work, every pull request and pushed branch,
 * and what its agent sessions said they did, the closing message in full.
 */
final class WorkPreview {
    private WorkPreview() {}

    /** The cells of a row's label, {@code Purpose}, before its value. */
    static final int LABEL = 9;

    /**
     * What the preview is drawn from.
     *
     * @param effort what the event log holds of it, null until the log is read
     * @param reading the overview's last read, null before the first
     * @param awaits whether its guest is still being read with nothing to show yet
     * @param merging how many sessions are being merged for it, or 0 when none are
     */
    record Subject(InstanceInfo instance, Span glyph, String state, EventLogReader.Effort effort,
                   OverviewCommand.Reading reading, boolean awaits, int merging, String spinner,
                   Instant now, ZoneId zone) {}

    /** Coding agents by the name a session reader gives them, as a person knows them. */
    private static final Map<String, String> AGENTS = Map.of(
            "claude", "Claude Code", "codex", "Codex", "pi", "Pi", "opencode", "opencode",
            "antigravity", "Antigravity CLI", "gemini-cli", "Gemini CLI", "copilot", "Copilot", "bob", "Bob");

    static String agentName(String agent) {
        return AGENTS.getOrDefault(agent, OutputFormat.oneLine(agent));
    }

    /** The preview's lines, each within {@code width} cells; the caller shows as many as fit. */
    static List<Line> lines(Subject s, int width, TuiTheme theme) {
        var out = new ArrayList<Line>();
        var dim = Style.EMPTY.fg(theme.textDim());
        var faint = Style.EMPTY.fg(theme.textFaint());
        var instance = s.instance();
        boolean running = Tui.isRunning(instance) && instance.pendingOp().isEmpty();
        var head = new ArrayList<Span>(List.of(Span.raw(" "), s.glyph(),
                Span.styled(s.state(), running ? Style.EMPTY.fg(theme.statusRunning()) : dim)));
        if (!instance.parent().isEmpty() && !"-".equals(instance.parent())) {
            head.add(Span.styled("   from " + OutputFormat.oneLine(instance.parent()), dim));
        }
        if (!instance.ipv4().isEmpty()) head.add(Span.styled("   " + instance.ipv4(), dim));
        out.add(Line.from(head));
        out.add(Line.from(""));

        if (instance.purpose() != null && !instance.purpose().isBlank()) {
            row(out, "Purpose", OutputFormat.oneLine(instance.purpose()), Style.EMPTY, width, theme);
        }
        var effort = s.effort();
        row(out, "Idle", idle(instance, effort, s.now()), Style.EMPTY, width, theme);
        if (effort != null && effort.any()) {
            row(out, "Active", OverviewCommand.activeCell(effort, s.zone()), Style.EMPTY, width, theme);
        }
        if (effort == null) {
            row(out, "Tokens", ColumnFit.ELLIPSIS, faint, width, theme);
        } else if (effort.modelCalls() == 0) {
            row(out, "Tokens", "none counted", faint, width, theme);
        } else {
            row(out, "Tokens", OverviewCommand.tokensCell(effort) + " in " + effort.modelCalls()
                    + (effort.modelCalls() == 1 ? " model call" : " model calls"), Style.EMPTY, width, theme);
        }
        work(out, s, width, theme);

        if (effort != null) {
            var pulls = effort.pullRequests();
            var states = s.reading() == null ? List.<PullRequestStates.State>of()
                    : OverviewCommand.statesOf(s.reading(), instance, effort);
            for (int i = 0; i < pulls.size(); i++) {
                var pull = pulls.get(i);
                var state = i < states.size() ? states.get(i) : PullRequestStates.State.UNKNOWN;
                var spans = new ArrayList<Span>(label(i == 0 ? "GitHub" : "", theme));
                var link = Style.EMPTY.fg(state == PullRequestStates.State.OPEN || state == PullRequestStates.State.UNKNOWN
                        ? theme.accent() : theme.textFaint()).underlined();
                if (pull.url() != null) link = link.hyperlink(pull.url());
                spans.add(Span.styled(OutputFormat.oneLine(pull.toString()), link));
                if (state != PullRequestStates.State.UNKNOWN) {
                    spans.add(Span.styled("  " + state.field, Style.EMPTY.fg(switch (state) {
                        case OPEN -> theme.statusRunning();
                        case MERGED -> theme.accent();
                        default -> theme.textFaint();
                    })));
                }
                out.add(Line.from(spans));
            }
            var branches = effort.branches();
            for (int i = 0; i < branches.size(); i++) {
                row(out, i == 0 ? "Pushed" : "", "⇡ " + OutputFormat.oneLine(branches.get(i).name()), dim, width, theme);
            }
        }
        out.add(Line.from(""));
        done(out, s, width, theme);
        return out;
    }

    /** How long it has been idle, in words, and since what. */
    static String idle(InstanceInfo instance, EventLogReader.Effort effort, Instant now) {
        var started = OverviewCommand.lastUsed(instance);
        var active = WorkTable.lastActive(instance, effort);
        if (active == null) return "never started";
        var since = WorkTable.idleWords(Duration.between(active, now));
        if (started == null || active.isAfter(started)) {
            return since + " since its last logged activity"
                    + (started == null ? "" : ", last start " + WorkTable.idleWords(Duration.between(started, now)) + " ago");
        }
        return since + " since its last start";
    }

    private static void work(List<Line> out, Subject s, int width, TuiTheme theme) {
        var warn = Style.EMPTY.fg(theme.statusWarning());
        var faint = Style.EMPTY.fg(theme.textFaint());
        if (s.reading() == null || s.awaits()) {
            row(out, "Work", "reading the clones" + ColumnFit.ELLIPSIS, faint, width, theme);
            return;
        }
        var work = s.reading().workOf(s.instance());
        switch (work.state()) {
            case NOT_READ -> row(out, "Work", "not read: the instance is stopped", faint, width, theme);
            case UNKNOWN -> row(out, "Work", "unknown" + (work.why() == null ? "" : ": " + work.why()), warn, width, theme);
            case NO_REPOS -> row(out, "Work", "no Git clones", Style.EMPTY.fg(theme.textDim()), width, theme);
            case CLEAN -> row(out, "Work", "clean, " + work.repos().size()
                    + (work.repos().size() == 1 ? " clone" : " clones"), Style.EMPTY.fg(theme.statusSuccess()), width, theme);
            case UNSAVED -> {
                row(out, "Work", work.cell(), warn, width, theme);
                for (var repo : work.repos()) {
                    if (repo.uncommitted() == 0 && repo.unpushed() == 0 && repo.stashes() == 0) continue;
                    row(out, "", OutputFormat.oneLine(repo.path()) + ": " + counts(repo), Style.EMPTY.fg(theme.textDim()), width, theme);
                }
            }
        }
    }

    private static String counts(WorkState.Repo repo) {
        var parts = new ArrayList<String>();
        if (repo.uncommitted() != 0) parts.add(number(repo.uncommitted()) + " uncommitted");
        if (repo.unpushed() != 0) parts.add(number(repo.unpushed()) + " unpushed");
        if (repo.stashes() != 0) parts.add(number(repo.stashes()) + (repo.stashes() == 1 ? " stash" : " stashes"));
        return String.join(", ", parts);
    }

    private static String number(int n) {
        return n < 0 ? "unknown" : Integer.toString(n);
    }

    private static void done(List<Line> out, Subject s, int width, TuiTheme theme) {
        var faint = Style.EMPTY.fg(theme.textFaint());
        var dim = Style.EMPTY.fg(theme.textDim());
        var reading = s.reading();
        var summary = reading == null || s.awaits() ? null : reading.summaryOf(s.instance());
        // Only the event log can say an instance with no session was not untouched
        if (summary == null || s.effort() == null && "untouched".equals(summary.from())) {
            row(out, "Done", "reading the sessions" + ColumnFit.ELLIPSIS, faint, width, theme);
            return;
        }
        if ("untouched".equals(summary.from())) {
            row(out, "Done", "untouched: no agent session", faint, width, theme);
            return;
        }
        var sessions = reading.sessionsOf(s.instance());
        var merge = summary.merged();
        if (summary.sessions() == null) {
            // A stopped instance may still have its last merged summary on the host
            if (merge == null) {
                row(out, "Done", "not read".equals(summary.cell()) ? "not read: the instance is stopped" : summary.cell(),
                        faint, width, theme);
                return;
            }
        } else if (summary.sessions() == 0) {
            row(out, "Done", summary.cell(), faint, width, theme);
            return;
        }
        int total = summary.sessions() == null ? merge.total() : summary.sessions();
        var head = (total == 1 ? "1 session" : total + " sessions");
        if (!sessions.sessions().isEmpty()) head += ", " + agentName(sessions.sessions().getFirst().agent());
        row(out, "Done", head, dim, width, theme);
        if (!sessions.otherAgents().isEmpty()) {
            row(out, "", String.join(", ", sessions.otherAgents().stream().map(WorkPreview::agentName).toList())
                    + " not read", Style.EMPTY.fg(theme.statusWarning()), width, theme);
        }
        if (s.merging() > 0) {
            out.add(Line.from(Span.raw("   "), Span.styled(s.spinner() + " merging " + s.merging() + " sessions",
                    Style.EMPTY.fg(theme.statusWarning()))));
            return;
        }
        if (merge != null) {
            paragraph(out, merge.label(LocalDateTime.ofInstant(s.now(), ZoneId.systemDefault())), dim, width);
            paragraph(out, merge.headline(), Style.EMPTY.bold(), width);
            paragraph(out, merge.done(), Style.EMPTY, width);
            paragraph(out, "Now: " + merge.current(), Style.EMPTY, width);
            paragraph(out, "Left: " + merge.pending(), Style.EMPTY, width);
            return;
        }
        var newest = sessions.sessions().isEmpty() ? null : sessions.sessions().getFirst();
        if (newest == null || !newest.closingReadable() || newest.closing() == null || newest.closing().isBlank()) {
            var why = newest != null && !newest.closingReadable() ? "closing message not readable" : "no closing message found";
            paragraph(out, why, faint, width);
            return;
        }
        if (total > 1) paragraph(out, "the newest of " + total + " said:", dim, width);
        for (var line : newest.closing().lines().toList()) {
            if (line.isBlank()) continue;
            paragraph(out, OutputFormat.oneLine(line), Style.EMPTY, width);
        }
    }

    /** Text wrapped under the labels' column, from the third cell. */
    private static void paragraph(List<Line> out, String text, Style style, int width) {
        for (var line : wrap(text, width - 4)) out.add(Line.from(Span.raw("   "), Span.styled(line, style)));
    }

    /** A labelled row, its value wrapped under the value's start. */
    private static void row(List<Line> out, String label, String value, Style style, int width, TuiTheme theme) {
        var wrapped = wrap(value, width - LABEL - 1);
        for (int i = 0; i < wrapped.size(); i++) {
            var spans = new ArrayList<Span>(label(i == 0 ? label : "", theme));
            spans.add(Span.styled(wrapped.get(i), style));
            out.add(Line.from(spans));
        }
    }

    private static List<Span> label(String label, TuiTheme theme) {
        var padded = label + " ".repeat(Math.max(0, LABEL - 1 - label.length()));
        return List.of(Span.raw(" "), Span.styled(padded, Style.EMPTY.fg(theme.textFaint())), Span.raw(" "));
    }

    /** {@code text} wrapped to {@code width} cells; a word wider than the line is cut into pieces. */
    static List<String> wrap(String text, int width) {
        var result = new ArrayList<String>();
        if (width <= 0) return result;
        for (var line : ModalRenderer.wrapWords(text, width)) {
            while (CharWidth.of(line) > width) {
                var piece = CharWidth.substringByWidth(line, width);
                if (piece.isEmpty()) break;
                result.add(piece);
                line = line.substring(piece.length());
            }
            result.add(line);
        }
        return result;
    }

    /** The cells {@code line} takes. */
    static int width(Line line) {
        int w = 0;
        for (var span : line.spans()) w += CharWidth.of(span.content());
        return w;
    }
}
