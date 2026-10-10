package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.mcp.McpStanding;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.command.InstanceListing.collectEntries;

@CommandDefinition(
        name = "list",
        description = "List all incus-spawn environments",
        generateHelp = true
)
public class ListCommand extends BaseCommand {

    // Output for scripts (#1036): see OutputFormat for what plain and json promise.
    @Option(name = "format", description = "Output format: table (default), plain or json")
    String format;

    @Option(name = "plain", hasValue = false, description = "Same as --format=plain")
    boolean plain;

    @Option(shortName = 'q', name = "quiet", hasValue = false, description = "Print instance names only, one per line")
    boolean quiet;

    @Option(name = "status", description = "Only instances in this state: running or stopped")
    String status;

    @Override
    protected CommandResult doExecute() {
        try {
            printListing(RuntimeServices.incus(), System.out, java.time.ZoneId.systemDefault());
        } catch (IncusException e) {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
        return CommandResult.SUCCESS;
    }

    /**
     * {@code isx list} outside the TUI: one instance listing from Incus, in the format asked for.
     * Templates are told apart by the type every build stamps on them, never by a name prefix,
     * so this needs no definitions; Incus instances isx did not create are not listed.
     */
    void printListing(IncusClient incus, java.io.PrintStream out, java.time.ZoneId zone) {
        var outputFormat = OutputFormat.resolve(format, plain);
        if (quiet && (plain || format != null)) {
            throw new IllegalArgumentException("--quiet prints names only; it cannot be combined with --format or --plain");
        }
        var wanted = statusFilter(status);

        // Names and states need no live state: recursion=1 spares Incus gathering network and
        // disk usage for every running instance, on every TAB the completion scripts answer.
        var all = collectEntries(quiet ? incus.listJsonConfig() : incus.listJson());
        var instances = all.stream()
                .filter(i -> !Metadata.TYPE_BASE.equals(i.type()))
                .filter(i -> wanted == null || wanted.equalsIgnoreCase(i.status()))
                .toList();
        if (quiet) {
            instances.forEach(i -> out.println(i.name()));
            return;
        }
        switch (outputFormat) {
            case PLAIN, JSON -> outputFormat.print(out, listingRecords(instances, zone));
            case TABLE -> {
                if (all.isEmpty()) {
                    out.println("No incus-spawn environments found.");
                    out.println("Run 'isx build tpl-java' to create your first template.");
                } else {
                    printTable(instances, out);
                }
            }
        }
    }

    private static String statusFilter(String status) {
        if (status == null) return null;
        var value = status.strip().toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("running") && !value.equals("stopped")) {
            throw new IllegalArgumentException("unknown status '" + status + "': expected running or stopped");
        }
        return value;
    }

    /** The fields of {@code isx list --format=plain|json}, in order: add to the end, never rename. */
    private static List<Map<String, Object>> listingRecords(List<InstanceInfo> instances, java.time.ZoneId zone) {
        var records = new ArrayList<Map<String, Object>>();
        for (var i : instances) {
            var record = new java.util.LinkedHashMap<String, Object>();
            record.put("name", i.name());
            record.put("status", i.status().toLowerCase(java.util.Locale.ROOT));
            record.put("ipv4", i.ipv4().isEmpty() ? null : i.ipv4());
            record.put("parent", i.parent().isEmpty() ? null : i.parent());
            record.put("runtime", i.runtime());
            record.put("created", Metadata.createdIso(i.created(), zone));
            // #1053: which instances an isx mcp session made, how they stand, and what for.
            record.put("mcp_state", i.mcp() == null ? null : i.mcp().state().label());
            record.put("mcp_purpose", i.mcp() == null ? null : i.mcp().purpose());
            records.add(record);
        }
        return records;
    }

    static void printTable(List<InstanceInfo> items, java.io.PrintStream out) {
        var nameWidth = Math.max(20, items.stream().mapToInt(e -> e.name().length()).max().orElse(20));
        // The MCP column only for someone who uses isx mcp: without it, the format has no
        // conversion for the last argument, which printf ignores. AGE is padded when MCP follows.
        var mcp = items.stream().anyMatch(e -> e.mcp() != null);
        var fmt = "  %-" + nameWidth + "s  %-10s  %-15s  %-20s  %-10s  " + (mcp ? "%-13s  %s%n" : "%s%n");

        out.printf(fmt, "NAME", "STATUS", "IP", "PARENT", "RUNTIME", "AGE", "MCP");
        out.printf(fmt, "-".repeat(nameWidth), "----------", "---------------",
                "--------------------", "----------", "---", "---");
        for (var entry : items) {
            var age = entry.created().isEmpty() ? "-" : Metadata.ageDescription(entry.created());
            var parent = entry.parent().isEmpty() ? "-" : OutputFormat.oneLine(entry.parent());
            var ip = entry.ipv4().isEmpty() ? "-" : entry.ipv4();
            out.printf(fmt, entry.name(), entry.status(), ip, parent, entry.runtime(), age, mcpCell(entry.mcp()));
        }
        out.println();
    }

    /**
     * {@code held: #870 implement}: how an isx mcp instance stands, and what it is for.
     * A stamp is shown through {@link OutputFormat#oneLine}: isx mcp refuses control characters
     * in a purpose, but a stamp can be set by hand, escape sequence and all.
     */
    private static String mcpCell(McpStanding mcp) {
        if (mcp == null) return "-";
        return mcp.state().label() + (mcp.purpose() == null ? "" : ": " + OutputFormat.oneLine(mcp.purpose()));
    }
}
