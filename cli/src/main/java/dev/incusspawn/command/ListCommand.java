package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.command.InstanceListing.collectEntries;

@CommandDefinition(
        name = "list",
        description = "List all isx instances and templates",
        generateHelp = true
)
public class ListCommand extends BaseCommand {

    // Output for scripts (#1036): see OutputFormat for what plain and json promise.
    @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
    String format;

    @Option(name = "plain", hasValue = false, description = "Same as --format=plain")
    boolean plain;

    @Option(shortName = 'q', name = "quiet", hasValue = false, description = "Print instance names only, one per line")
    boolean quiet;

    @Option(name = "status", completer = Completers.Status.class, description = "Only instances in this state: running or stopped")
    String status;

    /** On macOS, starts a stopped VM so Incus can answer (#1393); on Linux, nothing to do. */
    java.util.function.BooleanSupplier incusHost = ListCommand::startStoppedVm;

    /** When {@code isx vm reset} last wiped the data disk, or null. */
    java.util.function.Supplier<java.time.LocalDate> dataDiskResetOn = VmManager::dataDiskResetOn;

    @Override
    protected CommandResult doExecute() throws Exception {
        checkOptions();
        if (!incusHostReady()) return CommandResult.FAILURE;
        try {
            // Anything listed means the disk has been used since a reset: the reset no longer explains an empty list.
            if (printListing(RuntimeServices.incus(), BuildOutput.results(), java.time.ZoneId.systemDefault())) {
                VmManager.forgetDataDiskReset();
            }
        } catch (IncusException e) {
            BuildOutput.error(e.getMessage());
            return CommandResult.FAILURE;
        }
        return CommandResult.SUCCESS;
    }

    /**
     * A stopped VM cannot list anything, so it is started, except for {@code --quiet}: shell
     * completion runs that on every TAB, and a TAB must never boot a VM. The boot's progress goes
     * to stderr, so stdout holds only the listing a script reads.
     */
    boolean incusHostReady() throws Exception {
        return quiet || withStdoutOnStderr(incusHost::getAsBoolean);
    }

    /**
     * Starts a set-up VM that is stopped. A VM never set up (no data disk) is left alone: that
     * would download the appliance and first-boot it, which is 'isx init''s job, not a listing's.
     */
    private static boolean startStoppedVm() {
        if (!Platform.isMacOS() || VmManager.isRunning() || !Files.exists(Environment.vmDataImage())) return true;
        return VmManager.ensureRunning();
    }

    private void checkOptions() {
        OutputFormat.resolve(format, plain);
        if (quiet && (plain || format != null)) {
            throw new IllegalArgumentException("--quiet prints names only; it cannot be combined with --format or --plain");
        }
        statusFilter(status);
    }

    /**
     * {@code isx list} outside the TUI: one instance listing from Incus, in the format asked for.
     * Templates are told apart by the type every build stamps on them, never by a name prefix,
     * so this needs no definitions; Incus instances isx did not create are not listed.
     *
     * @return whether Incus holds any instance isx created, listed or filtered out
     */
    boolean printListing(IncusClient incus, java.io.PrintStream out, java.time.ZoneId zone) {
        checkOptions();
        var outputFormat = OutputFormat.resolve(format, plain);
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
            return !all.isEmpty();
        }
        switch (outputFormat) {
            case PLAIN, JSON -> outputFormat.print(out, listingRecords(instances, zone));
            case TABLE -> {
                if (all.isEmpty()) {
                    var resetOn = dataDiskResetOn.get();
                    if (resetOn != null) {
                        out.println("The VM's data disk was reset on " + resetOn + "; it holds no instances or templates.");
                        out.println("Recreate your templates with: isx build --all");
                    } else {
                        out.println("No isx instances or templates found.");
                        out.println("Run 'isx build tpl-minimal' to create your first template.");
                    }
                } else {
                    printTable(instances, out);
                }
            }
        }
        return !all.isEmpty();
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
            // #1657: the base image release it runs, which a rebuild from a newer one would change.
            record.put("base_image", BaseImageAge.tag(i.metadata()));
            // #1720: the external tool that controls it, and that tool's handle for it.
            record.put("owner", i.owner());
            record.put("owner_id", i.ownerId());
            // #1812: whether it may drive isx over MCP; what it may control needs definitions, which isx list loads none of
            record.put("mcp_client", i.mcpCaller());
            records.add(record);
        }
        return records;
    }

    public static void printTable(List<InstanceInfo> items, java.io.PrintStream out) {
        var nameWidth = Math.max(20, items.stream().mapToInt(e -> e.name().length()).max().orElse(20));
        // The OWNER column only for someone whose instances have one, an isx mcp session or another
        // tool (#1720): without it, the format has no conversion for the last argument, which
        // printf ignores. AGE is padded when OWNER follows.
        var owned = items.stream().anyMatch(e -> e.mcp() != null || e.owner() != null);
        var fmt = "  %-" + nameWidth + "s  %-10s  %-15s  %-20s  %-10s  " + (owned ? "%-13s  %s%n" : "%s%n");

        out.printf(fmt, "NAME", "STATUS", "IP", "PARENT", "RUNTIME", "AGE", "OWNER");
        out.printf(fmt, "-".repeat(nameWidth), "----------", "---------------",
                "--------------------", "----------", "---", "---");
        for (var entry : items) {
            var age = entry.created().isEmpty() ? "-" : Metadata.ageDescription(entry.created());
            var parent = entry.parent().isEmpty() ? "-" : OutputFormat.oneLine(entry.parent());
            var ip = entry.ipv4().isEmpty() ? "-" : entry.ipv4();
            out.printf(fmt, entry.name(), entry.status(), ip, parent, entry.runtime(), age, ownerCell(entry));
        }
        out.println();
    }

    /**
     * Who controls the instance: {@code mcp held: #870 implement} for an isx mcp instance, how it
     * stands and what it is for, or the external tool's name ({@code orca}). A stamp is shown
     * through {@link OutputFormat#oneLine}: isx refuses control characters in both, but a stamp
     * can be set by hand, escape sequence and all.
     */
    public static String ownerCell(InstanceInfo entry) {
        var mcp = entry.mcp();
        if (mcp != null) {
            return Metadata.OWNER_MCP + " " + mcp.state().label()
                    + (mcp.purpose() == null ? "" : ": " + OutputFormat.oneLine(mcp.purpose()));
        }
        return entry.owner() == null ? "-" : OutputFormat.oneLine(entry.owner());
    }
}
