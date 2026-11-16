package dev.incusspawn.command;

import dev.incusspawn.config.SecretRedactor;
import dev.incusspawn.util.OutputFormat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The coding agent sessions found in an instance (#2241): for each, what the person asked first
 * and the agent's closing message, its own report of what it did. They describe an instance; they
 * never decide whether destroying it loses anything, which is {@link WorkState}'s.
 *
 * <p>A {@link GuestReader} reads one agent's sessions in the guest, as a section of the overview's
 * one script ({@link GuestRead}); Claude Code's is {@link ClaudeSessions}. What the summary is made
 * of is this record, whatever read it, so a later reader (a content log in the proxy) feeds the
 * same {@link Summary}.
 *
 * <p>All of it is the guest's text and untrusted: {@link #text} scrubs it as a guest's output is
 * scrubbed and bounds it, and it is shown on one line.
 */
public record AgentSessions(State state, int total, List<Session> sessions, List<String> otherAgents, String why) {

    public enum State {
        /** Stopped: nothing starts an instance to read it. */
        NOT_READ,
        /** The read failed or was cut short: there may be sessions. */
        UNKNOWN,
        READ
    }

    /**
     * One session, newest first in {@link #sessions}: the agent, its file in the guest, when it was
     * last written, where it ran, what the person asked first (null when no line said), and its
     * closing message. A closing message that could not be read is {@code closingReadable} false,
     * never an older message shown in its place.
     */
    public record Session(String agent, String file, Instant modified, String cwd, String ask, String closing,
                          boolean closingReadable) {}

    /** One agent's sessions, read by a section of the guest's script. */
    public interface GuestReader {
        /** The line that opens its section of the output. */
        String header();

        /** Its section of the script, run as agentuser with {@code HOME} set; it prints {@link #header()} first and {@code end} last. */
        String script();

        /** Its section, header and {@code end} included; anything not exactly as the script writes is unknown. */
        AgentSessions parse(List<String> section);
    }

    public static final AgentSessions NOT_READ = new AgentSessions(State.NOT_READ, 0, List.of(), List.of(), "stopped");

    static AgentSessions unknown(String why) {
        return new AgentSessions(State.UNKNOWN, 0, List.of(), List.of(), why);
    }

    /** Several readers' sessions as one: newest first, unknown when any one is. */
    static AgentSessions merge(List<AgentSessions> all) {
        if (all.isEmpty()) return unknown("no reader answered");
        var sessions = new ArrayList<Session>();
        var others = new ArrayList<String>();
        int total = 0;
        for (var one : all) {
            if (one.state != State.READ) return one;
            total += one.total;
            sessions.addAll(one.sessions);
            others.addAll(one.otherAgents);
        }
        sessions.sort(Comparator.comparing(Session::modified).reversed());
        return new AgentSessions(State.READ, total, List.copyOf(sessions), List.copyOf(others), null);
    }

    /** The longest closing message kept, in code points, beyond what any summary shows. */
    static final int MAX_CLOSING = 4000;
    /** The longest first ask kept. */
    static final int MAX_ASK = 500;
    /** The longest headline, in code points. */
    static final int MAX_HEADLINE = 160;

    /**
     * Guest text as the overview keeps it: credential shapes redacted
     * ({@link SecretRedactor#scrubGuest}), stripped, and cut to {@code max} code points.
     */
    static String text(String raw, int max) {
        if (raw == null) return null;
        var scrubbed = SecretRedactor.scrubGuest(raw).strip();
        if (scrubbed.isEmpty()) return null;
        return cut(scrubbed, max);
    }

    /** {@code text} cut to {@code max} code points, its last one an ellipsis when cut. */
    static String cut(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text
                : text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }

    /** A heading's, a quote's or a list item's marker: what a line starts with, not what it says. */
    private static final Pattern MARKDOWN_LEAD = Pattern.compile("^(?:#{1,6}\\s+|>\\s*|[-*+]\\s+)+");

    /** The first line of a message that says something, without its Markdown markers, on one line. */
    static String firstLine(String message) {
        if (message == null) return null;
        for (var line : message.lines().toList()) {
            var plain = MARKDOWN_LEAD.matcher(line.strip()).replaceFirst("").replace("**", "").strip();
            if (!plain.isEmpty()) return cut(OutputFormat.oneLine(plain), MAX_HEADLINE);
        }
        return null;
    }

    /**
     * What the overview says was done, without a model: {@code untouched} only when no session, no
     * Git work and no activity in the event log say otherwise; one session's closing message; or
     * the newest of several, labelled as such. Merging several is not done here.
     *
     * @param from {@code untouched}, {@code session}, {@code newest_session}, {@code merged} (a
     *     model's merge of several, {@link #withMerged}), or null when there is no headline: the
     *     {@code headline_from} field
     */
    public record Summary(String from, String headline, String cell, Integer sessions, Instant newest,
                          String closing, String ask, SessionMerge.Merged merged) {

        public Summary(String from, String headline, String cell, Integer sessions, Instant newest,
                       String closing, String ask) {
            this(from, headline, cell, sessions, newest, closing, ask, null);
        }

        static Summary none(String cell) {
            return new Summary(null, null, cell, null, null, null, null);
        }

        /**
         * This summary with the model's merge of its sessions as the headline ({@code merged}),
         * its session count and age before it, so a cut cell keeps them; the newest session's message stays in {@link #closing}.
         * {@code otherAgents} are named as not read, as beside a session's message, since they may
         * have worked since.
         */
        public Summary withMerged(SessionMerge.Merged merge, java.time.LocalDateTime now, List<String> otherAgents) {
            var others = otherAgents.isEmpty() ? "" : " (" + String.join(", ", otherAgents) + " not read)";
            return new Summary("merged", merge.headline(), merge.label(now) + ": " + merge.headline() + others,
                    sessions, newest, closing, ask, merge);
        }

        /**
         * The summary of one instance. It fails closed: a read that did not cover the guest's
         * sessions or its clones, or another agent's sessions that were not read, never yields
         * {@code untouched}.
         */
        public static Summary of(AgentSessions read, WorkState work, boolean logSawActivity) {
            return switch (read.state()) {
                case NOT_READ -> none("not read");
                case UNKNOWN -> none("unknown");
                case READ -> {
                    if (read.total() == 0) {
                        boolean noWork = work.state() == WorkState.State.CLEAN || work.state() == WorkState.State.NO_REPOS;
                        if (noWork && !logSawActivity && read.otherAgents().isEmpty()) {
                            yield new Summary("untouched", "untouched", "untouched", 0, null, null, null);
                        }
                        yield new Summary(null, null, read.otherAgents().isEmpty() ? "no sessions"
                                : "sessions of " + String.join(", ", read.otherAgents()) + " not read", 0, null, null, null);
                    }
                    if (read.sessions().isEmpty()) yield new Summary(null, null, "unknown", read.total(), null, null, null);
                    var newest = read.sessions().getFirst();
                    var line = newest.closingReadable() ? firstLine(newest.closing()) : null;
                    var said = line != null ? line
                            : newest.closingReadable() ? "(no closing message found)" : "(closing message not readable)";
                    boolean one = read.total() == 1;
                    // Another agent may have worked since: say its sessions were not read
                    var others = read.otherAgents().isEmpty() ? ""
                            : " (" + String.join(", ", read.otherAgents()) + " not read)";
                    yield new Summary(line == null ? null : one ? "session" : "newest_session", line,
                            (one ? said : "newest of " + read.total() + ": " + said) + others,
                            read.total(), newest.modified(), newest.closing(), newest.ask());
                }
            };
        }
    }
}
