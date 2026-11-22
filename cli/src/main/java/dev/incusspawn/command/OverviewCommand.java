package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.EventLogReader;
import dev.incusspawn.proxy.EventLogReader.Effort;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.Report;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static dev.incusspawn.command.InstanceListing.collectEntries;

/**
 * {@code isx overview} (#2241): what was done in each instance, from the proxy's event log. Beside
 * the name, state, purpose and last use from the listing, it shows the tokens the instance's model
 * calls spent, the days it was active, the branches it pushed and the pull requests it opened.
 *
 * <p>The log is read only here and in the TUI's overview, on demand, never by {@code isx list},
 * which costs one Incus request. Beside the log it shows what only the instance knows, read in each
 * running guest ({@link WorkState}: changes not committed, commits not pushed, stashes), and asks
 * GitHub, with each instance's own account, whether its pull requests were merged
 * ({@link PullRequestStates}). A stopped instance is not read, and a read that fails says
 * {@code unknown}: the overview never says nothing would be lost when it could not look.
 */
@CommandDefinition(
        name = "overview",
        description = "Show what was done in each instance and what destroying it would lose",
        generateHelp = true
)
public class OverviewCommand extends BaseCommand {

    @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
    String format;

    @Option(name = "plain", hasValue = false, description = "Same as --format=plain")
    boolean plain;

    @Option(name = "status", completer = Completers.Status.class, description = "Only instances in this state: running or stopped")
    String status;

    /** On macOS, starts a stopped VM so Incus can answer, as {@code isx list} does (#1393). */
    java.util.function.BooleanSupplier incusHost = ListCommand::startStoppedVm;

    /** The event log; tests read one of their own. */
    Supplier<EventLogReader.Log> eventLog = EventLogReader::read;

    /** GitHub, asked whether the pull requests are still open; tests answer for it. */
    PullRequestStates.GitHub github = PullRequestStates.api();

    /** The host's config, for each instance's GitHub and Claude accounts. */
    Supplier<SpawnConfig> config = SpawnConfig::load;

    /** The model that merges several sessions' closing messages; tests answer for it. */
    SessionMerge.Model model = SessionMerge.api();

    /** Where merged summaries are kept on the host. */
    Supplier<SessionMerge.Cache> cache = SessionMerge.Cache::host;

    /**
     * Everything the overview shows beside the listing: the event log, each instance's work state
     * read in its guest, and what became of its pull requests.
     */
    public record Reading(EventLogReader.Log log, Map<String, GuestRead> guests,
                          Map<String, Map<EventLogReader.PullRequest, PullRequestStates.State>> pulls,
                          Map<String, SessionMerge.Merged> merged) {

        public Reading(EventLogReader.Log log, Map<String, GuestRead> guests,
                       Map<String, Map<EventLogReader.PullRequest, PullRequestStates.State>> pulls) {
            this(log, guests, pulls, Map.of());
        }

        /** This reading with the merged summaries {@link SessionMerge#mergeAll} made or found. */
        public Reading withMerged(Map<String, SessionMerge.Merged> merges) {
            return new Reading(log, guests, pulls, merges);
        }

        /**
         * Its work state; one the read did not cover (it appeared or started since) is not read
         * when stopped and unknown when running, never shown as stopped.
         */
        public WorkState workOf(InstanceInfo instance) {
            return guestOf(instance).work();
        }

        /** Its agent sessions, as {@link #workOf} for the work state. */
        public AgentSessions sessionsOf(InstanceInfo instance) {
            return guestOf(instance).sessions();
        }

        /**
         * What was done in it ({@link AgentSessions.Summary}): never {@code untouched} while the
         * event log saw it active, and the model's merge of its sessions when there is one.
         */
        public AgentSessions.Summary summaryOf(InstanceInfo instance) {
            var sessions = sessionsOf(instance);
            var summary = AgentSessions.Summary.of(sessions, workOf(instance), effortOf(log, instance).any());
            var merge = merged.get(instance.name());
            return merge == null ? summary : summary.withMerged(merge, LocalDateTime.now(), sessions.otherAgents());
        }

        private GuestRead guestOf(InstanceInfo instance) {
            var read = guests.get(instance.name());
            if (read != null) return read;
            return "running".equalsIgnoreCase(instance.status())
                    ? GuestRead.unknown("not read since it started") : GuestRead.NOT_READ;
        }

        /** What GitHub said of this pull request of the instance's; unknown when it was not asked or did not say. */
        public PullRequestStates.State stateOf(InstanceInfo instance, EventLogReader.PullRequest pull) {
            return pulls.getOrDefault(instance.name(), Map.of()).getOrDefault(pull, PullRequestStates.State.UNKNOWN);
        }
    }

    /**
     * Reads what the overview shows of {@code instances}: the log, then the guests' work states and
     * GitHub's answer at once. {@code failed} hears what could not be read, as a warning.
     */
    public static Reading read(IncusClient incus, List<InstanceInfo> instances, EventLogReader.Log log,
                               SpawnConfig config, PullRequestStates.GitHub github,
                               java.util.function.Consumer<String> failed) {
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pulls = pool.submit(() -> PullRequestStates.read(instances, log, config, github, failed));
            var work = GuestRead.readAll(incus, instances);
            try {
                return new Reading(log, work, pulls.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Reading(log, work, Map.of());
            } catch (java.util.concurrent.ExecutionException e) {
                failed.accept("Could not ask GitHub whether pull requests were merged: " + e.getCause().getMessage());
                return new Reading(log, work, Map.of());
            }
        }
    }

    @Override
    protected CommandResult doExecute() throws Exception {
        OutputFormat.resolve(format, plain);
        ListCommand.statusFilter(status);
        if (!withStdoutOnStderr(incusHost::getAsBoolean)) return CommandResult.FAILURE;
        try {
            print(RuntimeServices.incus(), BuildOutput.results(), ZoneId.systemDefault());
        } catch (IncusException e) {
            BuildOutput.error(e.getMessage());
            return CommandResult.FAILURE;
        }
        return CommandResult.SUCCESS;
    }

    void print(IncusClient incus, PrintStream out, ZoneId zone) {
        var outputFormat = OutputFormat.resolve(format, plain);
        var wanted = ListCommand.statusFilter(status);
        var instances = collectEntries(incus.listJsonConfig()).stream()
                .filter(i -> !Metadata.TYPE_BASE.equals(i.type()))
                .filter(i -> wanted == null || wanted.equalsIgnoreCase(i.status()))
                .toList();
        var host = config.get();
        var summaries = cache.get();
        var reading = read(incus, instances, eventLog.get(), host, github, BuildOutput::warn);
        reading = reading.withMerged(SessionMerge.mergeAll(instances, reading.guests(), host, model, summaries, BuildOutput::warn));
        // Only a listing of every instance says which were destroyed
        if (wanted == null) summaries.prune(instances);
        switch (outputFormat) {
            case PLAIN, JSON -> {
                outputFormat.print(out, records(instances, reading, zone));
                var note = coverage(reading.log(), zone);
                if (note != null) BuildOutput.note(note);
            }
            case TABLE -> printTable(instances, reading, out, zone);
        }
    }

    /** What the instance of this row did, as the log saw it. */
    public static Effort effortOf(EventLogReader.Log log, InstanceInfo instance) {
        return log.of(instance.name(), incarnation(instance));
    }

    /** The listing's {@code created_at}, which names the instance in the event log (#1063). */
    static String incarnation(InstanceInfo instance) {
        return instance.metadata() == null ? "" : instance.metadata().pathText("created_at", "").strip();
    }

    /** When the instance last started, from the listing's {@code last_used_at}; null when never. */
    static Instant lastUsed(InstanceInfo instance) {
        if (instance.metadata() == null) return null;
        try {
            var at = Instant.parse(instance.metadata().pathText("last_used_at", ""));
            return at.getEpochSecond() <= 0 ? null : at;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** The fields of {@code isx overview --format=plain|json}, in order: add to the end, never rename. */
    static List<Map<String, Object>> records(List<InstanceInfo> instances, Reading reading, ZoneId zone) {
        var records = new ArrayList<Map<String, Object>>();
        for (var i : instances) {
            var effort = effortOf(reading.log(), i);
            var work = reading.workOf(i);
            var record = new LinkedHashMap<String, Object>();
            record.put("name", i.name());
            record.put("status", i.status().toLowerCase(Locale.ROOT));
            record.put("purpose", i.purpose());
            record.put("last_used", iso(lastUsed(i), zone));
            record.put("model_calls", effort.modelCalls());
            record.put("tokens", effort.tokens());
            record.put("input_tokens", effort.inputTokens());
            record.put("output_tokens", effort.outputTokens());
            record.put("cache_read_input_tokens", effort.cacheReadTokens());
            record.put("cache_creation_input_tokens", effort.cacheCreationTokens());
            record.put("first_active", iso(effort.first(), zone));
            record.put("last_active", iso(effort.last(), zone));
            record.put("pushed_branches", effort.branches().stream().map(Object::toString).toList());
            record.put("pull_requests", effort.pullRequests().stream().map(Object::toString).toList());
            record.put("pull_request_states", PullRequestStates.fields(statesOf(reading, i, effort)));
            record.put("work_state", work.state().field);
            record.put("uncommitted_changes", work.uncommitted());
            record.put("unpushed_commits", work.unpushed());
            record.put("stashes", work.stashes());
            record.put("repos", work.repos().stream().map(WorkState.Repo::path).toList());
            record.put("unsaved_repos", work.unsavedRepos());
            var summary = reading.summaryOf(i);
            record.put("agent_sessions", summary.sessions());
            record.put("headline", summary.headline());
            record.put("headline_from", summary.from());
            record.put("last_session", iso(summary.newest(), zone));
            record.put("first_ask", summary.ask());
            record.put("closing_message", summary.closing());
            var merge = summary.merged();
            record.put("merged_done", merge == null ? null : merge.done());
            record.put("merged_state", merge == null ? null : merge.current());
            record.put("merged_pending", merge == null ? null : merge.pending());
            record.put("merged_sessions", merge == null ? null : merge.sessions());
            record.put("merged_at", merge == null ? null : iso(merge.made(), zone));
            records.add(record);
        }
        return records;
    }

    /**
     * One state per pull request of {@code effort}, in its order, unknown where GitHub was not
     * asked: matched by pull request, so a reading of an older log never labels another one.
     */
    public static List<PullRequestStates.State> statesOf(Reading reading, InstanceInfo instance, Effort effort) {
        return effort.pullRequests().stream().map(pull -> reading.stateOf(instance, pull)).toList();
    }

    private static String iso(Instant at, ZoneId zone) {
        return at == null ? null : at.atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static void printTable(List<InstanceInfo> instances, Reading reading, PrintStream out, ZoneId zone) {
        var log = reading.log();
        if (instances.isEmpty()) {
            out.println("No isx instances found.");
            return;
        }
        var table = Report.table("NAME", "STATUS", "LAST USED", "TOKENS", "ACTIVE", "WORK", "GITHUB", "PURPOSE", "DONE");
        for (var i : instances) {
            var effort = effortOf(log, i);
            table.row(i.name(), ListCommand.statusCell(i.status()), lastUsedCell(i), tokensCell(effort),
                    activeCell(effort, zone), reading.workOf(i).cell(), githubCell(effort, statesOf(reading, i, effort), 40),
                    ListCommand.purposeCell(i), doneCell(reading.summaryOf(i), 80));
        }
        table.print(out);
        out.println();
        var note = coverage(log, zone);
        if (note != null) out.println(note);
    }

    /** {@link Metadata#ageDescription} of the last start, or {@code never}: elapsed time, against this host's clock and zone. */
    public static String lastUsedCell(InstanceInfo instance) {
        var at = lastUsed(instance);
        return at == null ? "never" : Metadata.ageDescription(
                LocalDateTime.ofInstant(at, ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    /** The tokens spent, compact: {@code 812}, {@code 12.4k}, {@code 3.1M}; {@code -} with no model call. */
    public static String tokensCell(Effort effort) {
        if (effort.modelCalls() == 0) return "-";
        var n = effort.tokens();
        if (n < 1_000) return Long.toString(n);
        // Below what rounds to the next unit, so 999,960 shows as 1.0M, never 1000.0k
        if (n < 999_950) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        if (n < 999_950_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        return String.format(Locale.ROOT, "%.1fG", n / 1_000_000_000.0);
    }

    /**
     * The DONE cell: what the newest agent session said it did, {@code newest of 3: …} when there
     * were several, {@code untouched}, or why there is nothing to say; on one line, cut to
     * {@code max} code points.
     */
    public static String doneCell(AgentSessions.Summary summary, int max) {
        return AgentSessions.cut(OutputFormat.oneLine(summary.cell()), max);
    }

    /** How the overview names a day, in the table, its note and the TUI's title. */
    public static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);

    /** The first and last day the log saw it active, {@code Nov 1–Nov 3}, one day alone; {@code -} for none. */
    public static String activeCell(Effort effort, ZoneId zone) {
        if (!effort.any()) return "-";
        var first = DAY.format(effort.first().atZone(zone));
        var last = DAY.format(effort.last().atZone(zone));
        return first.equals(last) ? first : first + "–" + last;
    }

    /**
     * One reference of the GITHUB cell: what it shows, the shorter form it falls back to when the
     * cell has no room for that, the page it links to (null for none), and whether it opens a new
     * group, the branches after the pull requests.
     */
    public record GitHubPart(String text, String brief, String url, boolean opensGroup) {}

    /**
     * The GITHUB cell's references, in order: the pull requests it opened, each with what became
     * of it when GitHub said, {@code o/r#12 (merged)}, then the branches it pushed,
     * {@code pushed main}, {@code fix-x}. Names are the guest's text, shown on one line, and only
     * a pull request of GitHub's shape links to its page.
     */
    public static List<GitHubPart> githubParts(Effort effort, List<PullRequestStates.State> states) {
        var parts = new ArrayList<GitHubPart>();
        var pulls = effort.pullRequests();
        for (int i = 0; i < pulls.size(); i++) {
            var pull = pulls.get(i);
            var name = OutputFormat.oneLine(pull.toString());
            parts.add(new GitHubPart(PullRequestStates.labelled(name, states, i), name, pull.url(), false));
        }
        var branches = effort.branches();
        for (int i = 0; i < branches.size(); i++) {
            var name = OutputFormat.oneLine(branches.get(i).name());
            parts.add(new GitHubPart(i == 0 ? "pushed " + name : name, i == 0 ? "pushed " + name : name, null,
                    i == 0 && !parts.isEmpty()));
        }
        return parts;
    }

    /** A piece of a GITHUB cell as shown: its text, and the page it links to, or null. */
    public record Shown(String text, String url) {}

    /**
     * {@code parts} within {@code max} terminal cells, each reference whole (#2292): what does not
     * fit is counted as {@code +3}. A pull request without room for its state shows without it;
     * only a first reference wider than the whole cell is cut.
     */
    public static List<Shown> fitGithub(List<GitHubPart> parts, int max) {
        var shown = new ArrayList<Shown>();
        int used = 0;
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            var separator = i == 0 ? "" : part.opensGroup() ? ", " : " ";
            int after = parts.size() - i - 1;
            int reserve = after == 0 ? 0 : (" +" + after).length();
            int room = max - used - separator.length() - reserve;
            var text = width(part.text()) <= room ? part.text() : width(part.brief()) <= room ? part.brief() : null;
            if (text == null && i == 0) {
                var brief = part.brief();
                int end = 0;
                while (end < brief.length()) {
                    int next = brief.offsetByCodePoints(end, 1);
                    if (width(brief.substring(0, next)) > Math.max(1, room) - 1) break;
                    end = next;
                }
                text = brief.substring(0, end) + "…";
            }
            if (text == null) {
                shown.add(new Shown(" +" + (parts.size() - i), null));
                break;
            }
            if (!separator.isEmpty()) shown.add(new Shown(separator, null));
            shown.add(new Shown(text, part.url()));
            used += separator.length() + width(text);
        }
        return shown;
    }

    /** The terminal cells {@code text} takes: a CJK ideograph or an emoji takes two. */
    public static int width(String text) {
        return dev.tamboui.text.CharWidth.of(text);
    }

    /**
     * The pull requests it opened, each with what became of it when GitHub said, then the branches
     * it pushed, each whole within {@code max} terminal cells and the rest counted:
     * {@code o/r#12 (merged), pushed main fix-x +2}.
     */
    public static String githubCell(Effort effort, List<PullRequestStates.State> states, int max) {
        var parts = githubParts(effort, states);
        if (parts.isEmpty()) return "-";
        var text = new StringBuilder();
        for (var piece : fitGithub(parts, max)) text.append(piece.text());
        return text.toString();
    }

    /**
     * What the counts cover, or null when there is nothing to say: the log's first line, and the
     * lines the proxy lost or could not write whole.
     */
    public static String coverage(EventLogReader.Log log, ZoneId zone) {
        if (!log.files() || log.since() == null) {
            return "The proxy's event log has no lines yet, so no tokens, pushes or pull requests are shown.";
        }
        var note = new StringBuilder("Counted from the proxy's event log since "
                + DAY.format(log.since().atZone(zone)) + ".");
        var lost = log.droppedLines() + log.unreadableLines();
        if (lost > 0) note.append(" ").append(lost).append(lost == 1 ? " line was" : " lines were").append(" lost, so counts may be low.");
        return note.toString();
    }
}
