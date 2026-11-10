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
    record Change(String what, Who who, String command, String file, List<String> script, boolean checked) {
        Change {
            Objects.requireNonNull(what);
            Objects.requireNonNull(who);
            script = List.copyOf(script);
        }

        /**
         * A change whose root lines for {@code isx init --no-sudo}'s script (#2074) are its command
         * without {@code sudo}, when it is one; a sudo change with neither has none, and a person
         * must make it by hand.
         */
        Change(String what, Who who, String command, String file) {
            this(what, who, command, file, who == Who.SUDO && command != null && command.startsWith("sudo ")
                    ? List.of(command.substring("sudo ".length())) : List.of(), true);
        }

        /** This change with {@code lines} for the script, shell run as root. */
        Change scripted(List<String> lines) {
            return new Change(what, who, command, file, lines, checked);
        }

        /** This change, for a state the plan could not read: only the script's record says it was made. */
        Change unchecked() {
            return new Change(what, who, command, file, script, false);
        }

        /**
         * A file root writes, as {@code isx init} writes it: {@code content} with {@code mode},
         * owned by root, then {@code after}, as the script's lines.
         */
        static Change rootFile(String what, String path, String mode, String content, String... after) {
            var lines = new ArrayList<String>();
            lines.add("install -m " + mode + " -o root -g root /dev/stdin " + path + " <<'" + delimiter(content) + "'");
            lines.addAll(content.lines().toList());
            lines.add(delimiter(content));
            lines.addAll(List.of(after));
            return new Change(what, Who.SUDO, null, path, lines, true);
        }

        /** The here-document delimiter for {@code content}: one none of its lines is. */
        static String delimiter(String content) {
            var delimiter = "ISX_EOF";
            while (content.lines().anyMatch(delimiter::equals)) delimiter += "_";
            return delimiter;
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
            return new Assessment(State.UNKNOWN, false, List.of(change.unchecked()), why, checks);
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
     * {@code sudo_script} is the step's part of the script {@code isx init --no-sudo} writes.
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
        record.put("sudo_script", a.state() == State.DONE ? List.of()
                : a.changes().stream().filter(c -> c.who() == Who.SUDO).flatMap(c -> c.script().stream()).toList());
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

    // --- The privileged script of isx init --no-sudo (#2074) ---

    /** The record the script leaves, readable by the user: which changes it applied, by key. */
    static final String APPLIED_RECORD = "/var/lib/isx/init-applied";

    /**
     * The script's first command: no command reads the person's input. incus storage create and
     * network create read a YAML config from a stdin that is not a terminal, so run from an agent's
     * shell they would wait forever. The files the script writes come from here-documents, which
     * bring their own stdin.
     */
    static final String DETACH_STDIN = "exec </dev/null";

    /** A change's key in {@link #APPLIED_RECORD}: what its lines do, so a different script is a different key. */
    static String key(Change change) {
        return key(change.script());
    }

    /** The key of a change whose script is {@code lines}. */
    static String key(List<String> lines) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A privileged change left on this host, in the step it belongs to. */
    record Privileged(Entry entry, Change change) {}

    /**
     * The privileged changes {@code entries} leave, in their order: each sudo change of a step
     * that is not done, but one the plan could not check that {@code applied} says the script made.
     * An optional step's are kept too: the run makes them when the person agrees, as by default.
     */
    static List<Privileged> privileged(List<Entry> entries, java.util.Set<String> applied) {
        var left = new ArrayList<Privileged>();
        for (var entry : entries) {
            if (entry.assessment().state() == State.DONE) continue;
            for (var change : entry.assessment().changes()) {
                if (change.who() != Who.SUDO) continue;
                if (!change.checked() && made(change, applied)) continue;
                left.add(new Privileged(entry, change));
            }
        }
        return left;
    }

    /**
     * Whether the script recorded {@code change} as made: as the one change, or line by line as
     * the single-line changes a plan that could read the state scripted, such as the firewall
     * rules a fresh host's plan lists one by one and a later plan that cannot read them lists
     * as one.
     */
    static boolean made(Change change, java.util.Set<String> applied) {
        if (change.script().isEmpty()) return false;
        return applied.contains(key(change))
                || change.script().stream().allMatch(line -> applied.contains(key(List.of(line))));
    }

    /**
     * The script {@code isx init --no-sudo} hands the person: exactly the root commands of
     * {@code changes}, in order, each step under a comment saying why, then the record of what
     * it applied. Every change has lines; one without is refused before this.
     */
    static String script(List<Privileged> changes, String rerun) {
        var out = new StringBuilder();
        out.append("#!/bin/sh\n");
        out.append("# The privileged steps of 'isx init' on this host, written by 'isx init --no-sudo'.\n");
        out.append("# isx runs none of them itself: read this, then run it as root with: sudo sh <this file>\n");
        out.append("# Afterwards run '").append(rerun).append("' again, as yourself, to finish the setup.\n");
        out.append("set -eu\n");
        out.append("[ \"$(id -u)\" = 0 ] || { echo \"Run this as root: sudo sh $0\" >&2; exit 1; }\n");
        out.append(DETACH_STDIN).append("\n");
        Entry step = null;
        for (var p : changes) {
            if (p.entry() != step) {
                step = p.entry();
                out.append("\n# ").append(OutputFormat.oneLine(step.title())).append(": ")
                        .append(OutputFormat.oneLine(step.purpose())).append("\n");
            }
            out.append("# - ").append(OutputFormat.oneLine(p.change().what())).append("\n");
            for (var line : p.change().script()) out.append(line).append("\n");
        }
        out.append("\n# Record what ran, so that '").append(rerun).append("' knows what only root can check.\n");
        out.append("install -d -m 0755 ").append(APPLIED_RECORD, 0, APPLIED_RECORD.lastIndexOf('/')).append("\n");
        out.append("cat >> ").append(APPLIED_RECORD).append(" <<'ISX_EOF'\n");
        for (var p : changes) out.append(key(p.change())).append("\n");
        out.append("ISX_EOF\n");
        out.append("chmod 0644 ").append(APPLIED_RECORD).append("\n");
        out.append("echo \"Done. Now run '").append(rerun).append("' as yourself.\"\n");
        return out.toString();
    }
}
