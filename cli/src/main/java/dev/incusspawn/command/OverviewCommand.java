package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
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
 * which costs one Incus request. What the log does not record (commits not pushed, changes not
 * committed, a pull request merged since) is not shown as a state: the columns say what the proxy
 * saw, never that nothing would be lost.
 */
@CommandDefinition(
        name = "overview",
        description = "Show what was done in each instance: tokens, active days, pushes and pull requests",
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
        var log = eventLog.get();
        switch (outputFormat) {
            case PLAIN, JSON -> {
                outputFormat.print(out, records(instances, log, zone));
                var note = coverage(log, zone);
                if (note != null) BuildOutput.note(note);
            }
            case TABLE -> printTable(instances, log, out, zone);
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
    static List<Map<String, Object>> records(List<InstanceInfo> instances, EventLogReader.Log log, ZoneId zone) {
        var records = new ArrayList<Map<String, Object>>();
        for (var i : instances) {
            var effort = effortOf(log, i);
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
            records.add(record);
        }
        return records;
    }

    private static String iso(Instant at, ZoneId zone) {
        return at == null ? null : at.atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static void printTable(List<InstanceInfo> instances, EventLogReader.Log log, PrintStream out, ZoneId zone) {
        if (instances.isEmpty()) {
            out.println("No isx instances found.");
            return;
        }
        var nameWidth = Math.max(20, instances.stream().mapToInt(e -> e.name().length()).max().orElse(20));
        var fmt = "  %-" + nameWidth + "s  %-10s  %-14s  %-7s  %-15s  %-40s  %s%n";
        out.printf(fmt, "NAME", "STATUS", "LAST USED", "TOKENS", "ACTIVE", "GITHUB", "PURPOSE");
        out.printf(fmt, "-".repeat(nameWidth), "------", "---------", "------", "------", "------", "-------");
        for (var i : instances) {
            var effort = effortOf(log, i);
            out.printf(fmt, i.name(), i.status(), lastUsedCell(i), tokensCell(effort), activeCell(effort, zone),
                    githubCell(effort, 40), ListCommand.purposeCell(i));
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
     * The pull requests it opened, then the branches it pushed, cut to {@code max} code points:
     * {@code PR o/r#12, pushed main, fix-x}. Names are the guest's text, shown on one line.
     */
    public static String githubCell(Effort effort, int max) {
        var parts = new ArrayList<String>();
        if (!effort.pullRequests().isEmpty()) {
            parts.add("PR " + String.join(" ", effort.pullRequests().stream().map(Object::toString).toList()));
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
