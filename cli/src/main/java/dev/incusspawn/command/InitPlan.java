package dev.incusspawn.command;

import dev.incusspawn.util.OutputFormat;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What {@code isx init --plan} reports (#2073): each step init would take on this host, read
 * without changing anything, and who must do what is left of it.
 * <p>
 * A step's entry comes from the same {@link InitCommand.InitStep} the run takes its header and
 * work from, so the plan cannot list a step the run does not take, or miss one it does.
 */
final class InitPlan {

    private InitPlan() {}

    /** Where a step stands on this host. */
    enum State {
        /** Nothing left to change. */
        DONE,
        /** The run changes something. */
        PENDING,
        /** The run only offers a change a person may decline; nothing needs it. */
        OPTIONAL,
        /** What the run would change cannot be read without doing more than reading. */
        UNKNOWN;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Who has to make a change: in order, so a step needs the most any of its changes needs. */
    enum Who {
        /** No privilege, no secret, no approval: what an agent may do for the person. */
        NONE,
        /** A command run through sudo. */
        SUDO,
        /** A secret typed by a person at a terminal. */
        SECRET,
        /** A choice that is the person's alone: which templates agents may branch. */
        APPROVAL;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One change: what it is, who makes it, and the command it runs or the file it writes, if any. */
    record Change(String what, Who who, String command, String file) {
        Change {
            Objects.requireNonNull(what);
            Objects.requireNonNull(who);
        }

        static Change run(String what, Who who, String command) {
            return new Change(what, who, command, null);
        }

        static Change write(String what, Who who, String file) {
            return new Change(what, who, null, file);
        }

        static Change sudo(String what, String command) {
            return run(what, Who.SUDO, command);
        }
    }

    /**
     * A step's standing on this host: its state, whether the run asks a person a question there,
     * what it would change and a note on why, such as what could not be read.
     */
    record Assessment(State state, boolean asks, List<Change> changes, String note, List<String> checks) {
        Assessment {
            changes = List.copyOf(changes);
            checks = List.copyOf(checks);
        }

        Assessment(State state, boolean asks, List<Change> changes, String note) {
            this(state, asks, changes, note, List.of());
        }

        /**
         * What the plan did not check because only root can read it (Sanne, #2091): unknown, with
         * {@code why} saying what was skipped and why, the change the run may make there, and the
         * commands a person runs, with sudo, to check it themselves.
         */
        static Assessment notChecked(String why, Change change, List<String> checks) {
            return new Assessment(State.UNKNOWN, false, List.of(change), why, checks);
        }

        static Assessment done() {
            return new Assessment(State.DONE, false, List.of(), null);
        }

        static Assessment done(String note) {
            return new Assessment(State.DONE, false, List.of(), note);
        }

        static Assessment unknown(String note) {
            return new Assessment(State.UNKNOWN, false, List.of(), note);
        }

        /** Pending with {@code changes}, done without any. */
        static Assessment of(List<Change> changes) {
            return new Assessment(changes.isEmpty() ? State.DONE : State.PENDING, false, changes, null);
        }

        Assessment asking() {
            return new Assessment(state, true, changes, note, checks);
        }

        Assessment noting(String note) {
            return new Assessment(state, asks, changes, note, checks);
        }

        /** Who must make what is left: the most any change needs, nobody when nothing is left. */
        Who who() {
            if (state == State.DONE) return Who.NONE;
            return changes.stream().map(Change::who).max(Comparator.naturalOrder()).orElse(Who.NONE);
        }
    }

    /** One step of the plan. */
    record Entry(String step, String title, String purpose, Assessment assessment) {}

    /**
     * Combines the standings of a step's parts: pending when any part certainly changes something,
     * else unknown when any part was not checked, else optional when one only offers, else done.
     * Every part's changes, notes and checks are kept, so neither hides what the other knows.
     */
    static Assessment combine(List<Assessment> parts) {
        var changes = new ArrayList<Change>();
        var checks = new LinkedHashSet<String>();
        var notes = new LinkedHashSet<String>();
        var asks = false;
        var state = State.DONE;
        for (var part : parts) {
            changes.addAll(part.changes());
            checks.addAll(part.checks());
            if (part.note() != null) notes.add(part.note());
            asks |= part.asks();
            state = worse(state, part.state());
        }
        return new Assessment(state, asks, changes, notes.isEmpty() ? null : String.join(" ", notes), List.copyOf(checks));
    }

    private static State worse(State a, State b) {
        return rank(a) >= rank(b) ? a : b;
    }

    /**
     * Pending outranks unknown: a step with work it will certainly do reads as to do, and what it
     * could not check stays in its note and checks, so neither hides the other.
     */
    private static int rank(State state) {
        return switch (state) {
            case DONE -> 0;
            case OPTIONAL -> 1;
            case UNKNOWN -> 2;
            case PENDING -> 3;
        };
    }

    /**
     * The fields of {@code isx init --plan --format=plain|json}, in order: add to the end, never
     * rename, remove or reorder. {@code changes}, {@code commands} and {@code files} list each of
     * the step's changes, the commands they run and the files they write; {@code sudo_commands}
     * is the part of {@code commands} a person runs with sudo. {@code check_commands} are what a
     * person runs, with sudo, to check what the plan did not: it never runs sudo itself.
     */
    static Map<String, Object> record(Entry entry) {
        var a = entry.assessment();
        var record = new LinkedHashMap<String, Object>();
        record.put("step", entry.step());
        record.put("title", entry.title());
        record.put("state", a.state().value());
        record.put("who", a.who().value());
        record.put("asks", a.asks());
        record.put("changes", a.changes().stream().map(Change::what).toList());
        record.put("commands", a.changes().stream().map(Change::command).filter(Objects::nonNull).toList());
        record.put("sudo_commands", a.changes().stream().filter(c -> c.who() == Who.SUDO)
                .map(Change::command).filter(Objects::nonNull).toList());
        record.put("files", a.changes().stream().map(Change::file).filter(Objects::nonNull).distinct().toList());
        record.put("note", a.note());
        record.put("purpose", entry.purpose());
        record.put("check_commands", a.checks());
        return record;
    }

    /** What the {@code table} format says of who must do a step. */
    private static String whoLabel(Who who) {
        return switch (who) {
            case NONE -> "no privilege needed";
            case SUDO -> "needs sudo";
            case SECRET -> "needs a secret typed by you";
            case APPROVAL -> "needs your approval";
        };
    }

    private static String stateLabel(Assessment a) {
        return switch (a.state()) {
            case DONE -> "done";
            case PENDING -> "to do, " + whoLabel(a.who());
            case OPTIONAL -> "optional, " + whoLabel(a.who());
            case UNKNOWN -> a.checks().isEmpty()
                    ? (a.changes().isEmpty() ? "unknown" : "unknown, " + whoLabel(a.who()))
                    : "not checked: it needs root";
        };
    }

    /**
     * The {@code table} format: each step with its state, then what it would change and how. For
     * people, and free to change; every line is shown as {@code plain} shows a value, since
     * host files and definitions supply some of it.
     */
    static void printTable(PrintStream out, List<Entry> entries) {
        var width = entries.stream().mapToInt(e -> e.title().length()).max().orElse(0);
        for (var entry : entries) {
            var a = entry.assessment();
            var mark = switch (a.state()) {
                case DONE -> "✓";
                case PENDING -> "•";
                case OPTIONAL -> "○";
                case UNKNOWN -> "?";
            };
            line(out, mark + " " + entry.title() + " ".repeat(width - entry.title().length() + 2) + stateLabel(a)
                    + (a.asks() ? "; asks you" : ""));
            for (var change : a.changes()) {
                var who = change.who() == Who.NONE ? "" : " (" + whoLabel(change.who()) + ")";
                line(out, "    " + change.what() + who);
                if (change.command() != null) line(out, "      $ " + change.command());
                if (change.file() != null) line(out, "      writes " + change.file());
            }
            if (a.note() != null) line(out, "    " + a.note());
            if (!a.checks().isEmpty()) line(out, "    To check it yourself:");
            for (var check : a.checks()) line(out, "      $ " + check);
        }
        var left = entries.stream().filter(e -> e.assessment().state() == State.PENDING).count();
        var unknown = entries.stream().filter(e -> e.assessment().state() == State.UNKNOWN).count();
        line(out, "");
        if (left == 0 && unknown == 0) {
            line(out, "Nothing to do: every step is done or only offers a choice.");
        } else {
            var sudo = entries.stream().anyMatch(e -> e.assessment().state() != State.DONE
                    && e.assessment().changes().stream().anyMatch(c -> c.who() == Who.SUDO));
            line(out, left + " step" + (left == 1 ? "" : "s") + " to do"
                    + (unknown == 0 ? "" : ", " + unknown + " that the plan cannot read")
                    + (sudo ? "; sudo is needed" : "") + ".");
        }
    }

    private static void line(PrintStream out, String text) {
        out.println(OutputFormat.oneLine(text));
    }
}
