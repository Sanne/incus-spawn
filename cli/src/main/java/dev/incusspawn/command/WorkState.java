package dev.incusspawn.command;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a running instance's Git clones hold that destroying it would lose (#2241): changes not
 * committed, commits on no remote-tracking branch, and stashes. Read by one fixed script run in
 * the guest as agentuser (the first section of {@link GuestRead}'s), which only reads: it
 * fetches nothing, and {@code GIT_OPTIONAL_LOCKS=0} keeps {@code git status} from rewriting the
 * index under an agent at work.
 *
 * <p>The answer is the guest's own and it fails closed: a stopped instance is not read (nothing
 * starts one for this), and a read that times out, fails, is cut short or does not parse says
 * {@link State#UNKNOWN}, never {@link State#CLEAN}. A clone the script could not read, or one past
 * the {@link #MAX_REPOS} it reads, makes the instance unknown too, unless another clone already
 * holds work.
 *
 * <p>The clones read are the Git repositories directly under the home directory and one level
 * below, where templates clone theirs; one cloned elsewhere is not seen.
 */
public record WorkState(State state, List<Repo> repos, String why) {

    /** What destroying the instance would cost, as far as its clones say. */
    public enum State {
        /** Stopped: not read, since nothing starts an instance to look at it. */
        NOT_READ("not_read"),
        /** The read failed or did not cover every clone: the instance may hold work. */
        UNKNOWN("unknown"),
        /** No Git repository where the read looks. */
        NO_REPOS("no_repos"),
        /** Every clone read has nothing uncommitted, unpushed or stashed. */
        CLEAN("clean"),
        /** Some clone holds work that is not on a remote. */
        UNSAVED("unsaved");

        /** The {@code work_state} value of {@code isx overview --format=plain|json}. */
        public final String field;

        State(String field) {
            this.field = field;
        }
    }

    /**
     * One clone: its path under the home directory, and its counts, each {@code -1} when the
     * script could not read it.
     */
    public record Repo(String path, int uncommitted, int unpushed, int stashes) {
        boolean readable() {
            return uncommitted >= 0 && unpushed >= 0 && stashes >= 0;
        }

        boolean holdsWork() {
            return uncommitted > 0 || unpushed > 0 || stashes > 0;
        }
    }

    public static final WorkState NOT_READ = new WorkState(State.NOT_READ, List.of(), "stopped");

    static WorkState unknown(String why) {
        return new WorkState(State.UNKNOWN, List.of(), why);
    }

    /** The changes not committed in the clones read; with {@link #unpushed} and {@link #stashes}, null when not read. */
    public Integer uncommitted() {
        return sum(Repo::uncommitted);
    }

    /**
     * The commits on the local branches and HEAD that no remote-tracking branch holds, nor a tag a
     * remote's fetch refspec names: a shallow clone isx pinned to a tag keeps no remote-tracking
     * branch, only that refspec, and its tag is not work. A tag made in the guest is no remote's,
     * so the commits under it still count; a named tag the clone no longer has is skipped rather
     * than failing the count. Nor are the commits the clone checked out when it was made, from
     * the first {@code clone:} entry of HEAD's reflog: a full clone pinned to a tag keeps the broad
     * refspec and no remote-tracking branch, and its tag's history is no work either. A clone whose
     * reflog no longer says (expired, or rewritten) counts that history, which errs on unsaved.
     * A clone copied from a host checkout holds the tips it was given under {@code refs/isx/host/}
     * (#2273), which the host still holds, so they count as saved like a remote-tracking branch.
     */
    public Integer unpushed() {
        return sum(Repo::unpushed);
    }

    public Integer stashes() {
        return sum(Repo::stashes);
    }

    private Integer sum(java.util.function.ToIntFunction<Repo> count) {
        if (state == State.NOT_READ || state == State.UNKNOWN && repos.isEmpty()) return null;
        // Per count, not per clone: a clone whose stashes Git refused still has its changes counted
        return repos.stream().mapToInt(count).filter(c -> c > 0).sum();
    }

    /** The clones that hold work, by path. */
    public List<String> unsavedRepos() {
        return repos.stream().filter(Repo::holdsWork).map(Repo::path).toList();
    }

    /** At most this many clones are read in one instance; one more makes the read incomplete. */
    static final int MAX_REPOS = 50;
    /** At most this much of the script's output is read: {@link #MAX_REPOS} lines with long paths fit well inside. */
    static final int MAX_OUTPUT = 64 * 1024;
    /** How long one instance's read may take before it is given up as unknown. */
    static final Duration LIMIT = Duration.ofSeconds(10);
    /** agentuser, in every isx base image (as {@code IncusInstanceBackend} assumes). */
    static final int AGENT_UID = 1000;
    static final String AGENT_HOME = "/home/agentuser";

    public static final String HEADER = "isx-work-state 1";

    /** At most this many clones are read at once, fewer when the guest has fewer CPUs. */
    static final int PARALLEL = 4;

    /**
     * The script the guest runs. One line per clone, {@code repo <uncommitted> <unpushed> <stashes>
     * <path>}, {@code x} for a count Git would not give, then {@code more} if there were more
     * clones than it reads, then {@code end}: a read without it was cut short.
     *
     * <p>Every branch of a template has its clones' files on another device than the one their
     * index recorded, so {@code core.checkStat=minimal} keeps {@code git status} from hashing each
     * file again on every read, which it cannot write back (#2627). The clones are read
     * {@link #PARALLEL} at a time, each line tagged with its clone's place and put back in order;
     * a line lost on the way makes the answer one {@link #parse} refuses. A clone whose {@code .git}
     * is a plain directory, not a worktree's and not reftable, is read without asking Git where its
     * directory is, and asked for its stashes only when it holds a {@code refs/stash}.
     */
    static final String SCRIPT = """
            export GIT_OPTIONAL_LOCKS=0 GIT_TERMINAL_PROMPT=0 LC_ALL=C
            echo '%s'
            probe() {
              if s=$(git -C "$2" -c core.checkStat=minimal status --porcelain 2>/dev/null); then c=$(printf '%%s' "$s" | grep -c '^'); else c=x; fi
              h=; git -C "$2" rev-parse -q --verify HEAD >/dev/null 2>&1 && h=HEAD
              t=; for r in $(git -C "$2" config --get-regexp '^remote[.].*[.]fetch$' 2>/dev/null | sed -n 's|.*:\\(refs/tags/[^*?[[:space:]]*\\)$|\\1|p'); do
                git -C "$2" rev-parse -q --verify "$r" >/dev/null 2>&1 && t="$t $r"
              done
              if [ -d "$2.git" ] && [ ! -e "$2.git/commondir" ] && [ ! -e "$2.git/reftable" ]; then g=$2.git f=1; else f=; g=$(git -C "$2" rev-parse --absolute-git-dir 2>/dev/null) || g=; fi
              o=; [ -n "$g" ] && o=$(sed -n '1s/^[0-9a-f]* \\([0-9a-f]\\{40,64\\}\\) .*[[:blank:]]clone: .*/\\1/p' "$g/logs/HEAD" 2>/dev/null) || o=
              [ -n "$o" ] && git -C "$2" cat-file -e "$o^{commit}" 2>/dev/null && t="$t $o"
              u=$(git -C "$2" rev-list --count $h --branches --not --remotes --glob=refs/isx/host/\\* $t 2>/dev/null) || u=x
              if [ -n "$f" ] && [ ! -e "$g/refs/stash" ] && ! grep -q ' refs/stash$' "$g/packed-refs" 2>/dev/null; then k=0
              elif s=$(git -C "$2" stash list 2>/dev/null); then k=$(printf '%%s' "$s" | grep -c '^'); else k=x; fi
              p=${2#"$HOME"/}
              printf '%%s repo %%s %%s %%s %%s\\n' "$1" "$c" "$u" "$k" "${p%%/}"
            }
            set --
            for d in "$HOME"/*/; do [ -e "$d.git" ] && set -- "$@" "$d"; done
            for d in "$HOME"/*/*/; do [ -e "$d.git" ] && [ ! -e "${d%%/*/}/.git" ] && set -- "$@" "$d"; done
            m=$#; [ "$m" -gt %d ] && m=%d
            j=$(nproc 2>/dev/null) || j=1; [ "$j" -ge 1 ] 2>/dev/null || j=1; [ "$j" -gt %d ] && j=%d
            r=$(i=0; for d; do
                  i=$((i+1)); [ "$i" -gt "$m" ] && break
                  probe "$i" "$d" &
                  [ $((i %% j)) -eq 0 ] && wait
                done; wait) || r=
            r=$(printf '%%s\\n' "$r" | sort -n -k1,1 | sed 's/^[0-9]* //')
            if [ "$m" -gt 0 ]; then
              [ "$(printf '%%s\\n' "$r" | grep -c '^repo ')" -eq "$m" ] && printf '%%s\\n' "$r" || echo lost
            fi
            [ "$#" -gt "$m" ] && echo more
            echo end
            """.formatted(HEADER, MAX_REPOS, MAX_REPOS, PARALLEL, PARALLEL);

    private static final Pattern REPO_LINE = Pattern.compile("repo (\\d{1,9}|x) (\\d{1,9}|x) (\\d{1,9}|x) (.+)");

    /** How the script's exit and output read; anything not exactly as it writes is unknown. */
    static WorkState parse(int exit, String output, boolean cut) {
        if (cut) return unknown("its answer was too long");
        if (exit != 0) return unknown("the read exited " + exit);
        var lines = output.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) return unknown("its answer was not the script's");
        if (!lines.getLast().equals("end")) return unknown("its answer was cut short");
        var repos = new ArrayList<Repo>();
        boolean more = false;
        for (var line : lines.subList(1, lines.size() - 1)) {
            if (line.equals("more")) {
                more = true;
                continue;
            }
            var m = REPO_LINE.matcher(line);
            if (more || !m.matches()) return unknown("its answer was not the script's");
            repos.add(new Repo(m.group(4), count(m.group(1)), count(m.group(2)), count(m.group(3))));
        }
        var held = repos.stream().anyMatch(Repo::holdsWork);
        boolean complete = !more && repos.stream().allMatch(Repo::readable);
        State state = held ? State.UNSAVED
                : !complete ? State.UNKNOWN
                : repos.isEmpty() ? State.NO_REPOS : State.CLEAN;
        String why = more ? "more than " + MAX_REPOS + " clones"
                : complete ? null : "Git could not read a clone";
        return new WorkState(state, List.copyOf(repos), why);
    }

    private static int count(String text) {
        return text.equals("x") ? -1 : Integer.parseInt(text);
    }

    /**
     * The table's WORK cell: {@code clean}, {@code 3 uncommitted, 1 unpushed, 1 stash},
     * {@code no repos}, {@code unknown}, or {@code not read} for a stopped instance.
     */
    public String cell() {
        return switch (state) {
            case NOT_READ -> "not read";
            case UNKNOWN -> "unknown";
            case NO_REPOS -> "no repos";
            case CLEAN -> "clean";
            case UNSAVED -> {
                var parts = new ArrayList<String>();
                if (uncommitted() > 0) parts.add(uncommitted() + " uncommitted");
                if (unpushed() > 0) parts.add(unpushed() + " unpushed");
                if (stashes() > 0) parts.add(stashes() + (stashes() == 1 ? " stash" : " stashes"));
                if (repos.stream().anyMatch(r -> !r.readable()) || why != null) parts.add("more unknown");
                yield String.join(", ", parts);
            }
        };
    }

    /** Collects at most {@code max} bytes, and says whether there were more. */
    static final class Bounded extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int max;
        boolean cut;

        Bounded(int max) {
            this.max = max;
        }

        @Override
        public synchronized void write(int b) {
            if (bytes.size() < max) bytes.write(b);
            else cut = true;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int room = Math.max(0, max - bytes.size());
            bytes.write(b, off, Math.min(room, len));
            if (len > room) cut = true;
        }

        synchronized String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
