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

    /** The host's config, for each instance's GitHub account. */
    Supplier<SpawnConfig> config = SpawnConfig::load;

    /**
     * Everything the overview shows beside the listing: the event log, each instance's work state
     * read in its guest, and what became of its pull requests.
     */
    public record Reading(EventLogReader.Log log, Map<String, WorkState> work,
                          Map<String, Map<EventLogReader.PullRequest, PullRequestStates.State>> pulls) {
        /**
         * Its work state; one the read did not cover (it appeared or started since) is not read
         * when stopped and unknown when running, never shown as stopped.
         */
        public WorkState workOf(InstanceInfo instance) {
            var state = work.get(instance.name());
            if (state != null) return state;
            return "running".equalsIgnoreCase(instance.status())
                    ? WorkState.unknown("not read since it started") : WorkState.NOT_READ;
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
            var work = WorkState.readAll(incus, instances);
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
        var reading = read(incus, instances, eventLog.get(), config.get(), github, BuildOutput::warn);
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
        var nameWidth = Math.max(20, instances.stream().mapToInt(e -> e.name().length()).max().orElse(20));
        var fmt = "  %-" + nameWidth + "s  %-10s  %-14s  %-7s  %-15s  %-28s  %-40s  %s%n";
        out.printf(fmt, "NAME", "STATUS", "LAST USED", "TOKENS", "ACTIVE", "WORK", "GITHUB", "PURPOSE");
        out.printf(fmt, "-".repeat(nameWidth), "------", "---------", "------", "------", "----", "------", "-------");
        for (var i : instances) {
            var effort = effortOf(log, i);
            out.printf(fmt, i.name(), i.status(), lastUsedCell(i), tokensCell(effort), activeCell(effort, zone),
                    reading.workOf(i).cell(), githubCell(effort, statesOf(reading, i, effort), 40), ListCommand.purposeCell(i));
        }
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
     * The pull requests it opened, each with what became of it when GitHub said, then the branches
     * it pushed, cut to {@code max} code points: {@code PR o/r#12 (merged), pushed main fix-x}.
     * Names are the guest's text, shown on one line.
     */
    public static String githubCell(Effort effort, List<PullRequestStates.State> states, int max) {
        var parts = new ArrayList<String>();
        if (!effort.pullRequests().isEmpty()) {
            parts.add("PR " + String.join(" ", PullRequestStates.labelled(effort.pullRequests(), states)));
        }
        if (!effort.branches().isEmpty()) {
            parts.add("pushed " + String.join(" ", effort.branches().stream().map(EventLogReader.Branch::name).toList()));
        }
        if (parts.isEmpty()) return "-";
        var text = OutputFormat.oneLine(String.join(", ", parts));
        return text.codePointCount(0, text.length()) <= max ? text
                : text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
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
