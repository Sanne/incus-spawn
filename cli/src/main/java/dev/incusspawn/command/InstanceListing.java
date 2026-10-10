package dev.incusspawn.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.mcp.McpStanding;

import java.util.ArrayList;
import java.util.List;

/**
 * The instance listing as isx reads it from Incus: one {@link InstanceInfo} per instance isx made,
 * and the {@link TemplateInfo} rows the TUI shows for templates. Shared by {@code isx list}, the
 * TUI, and the commands that ask which templates are built.
 */
final class InstanceListing {

    private InstanceListing() {}

    /** Whether {@code parent} is the "no template parent" sentinel (null, empty, or "-"). */
    static boolean isRootParent(String parent) {
        return parent == null || parent.isEmpty() || "-".equals(parent);
    }

    /**
     * The built templates in an instance listing (recursion=1 is enough: only config is read),
     * told apart by the {@code base} type every build stamps, as {@code isx list} does. A
     * template mid-rebuild (its temporary {@code -rebuilding} copy) is not one.
     */
    static List<TemplateStaleness.Built> builtTemplates(String listingJson) {
        return collectEntries(listingJson).stream()
                .filter(i -> Metadata.TYPE_BASE.equals(i.type()))
                .filter(i -> !i.name().endsWith(BuildCommand.REBUILDING_SUFFIX))
                .map(i -> new TemplateStaleness.Built(i.name(), i.created(), i.buildVersion(), i.definitionSha(), i.machineType()))
                .toList();
    }

    static java.time.LocalDateTime parseTimestamp(String ts) {
        try {
            if (ts.contains("T")) {
                return java.time.LocalDateTime.parse(ts, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            return java.time.LocalDate.parse(ts, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The isx-managed instances in an instance listing, templates included. A listing that cannot
     * be read is an error, never an empty list: a script would take that for "no instances".
     */
    static List<InstanceInfo> collectEntries(String listingJson) {
        try {
            var nodes = JSON.readTree(listingJson);
            if (!nodes.isArray()) {
                throw new IncusException("Cannot read the instance listing from Incus: expected a JSON array");
            }
            // Whether a coordinator instance still holds its workers is read from this listing.
            var callerGrants = McpStanding.callerGrants(nodes);
            var entryList = new ArrayList<InstanceInfo>();
            for (var node : nodes) {
                var config = node.path("config");
                // Include any instance that has incus-spawn metadata
                var parent = configVal(config, Metadata.PARENT, "");
                var created = configVal(config, Metadata.CREATED, "");
                var type = configVal(config, Metadata.TYPE, "");
                // Only show instances managed by incus-spawn (have any metadata)
                if (type.isEmpty() && parent.isEmpty() && created.isEmpty()) continue;

                var expandedDevices = node.path("expanded_devices");
                var rootSize = expandedDevices.path("root").path("size").asText("");

                var extracted = IncusClient.extractIpv4(node.path("state").path("network"));
                var ipv4 = extracted != null ? extracted
                        : configVal(config, Metadata.STATIC_IP, "");

                var diskUsage = sumDiskUsage(node.path("state").path("disk"));

                long referencedBytes = -1;
                var referencedStr = configVal(config, Metadata.DISK_REFERENCED, "");
                if (!referencedStr.isEmpty()) {
                    try { referencedBytes = Long.parseLong(referencedStr); }
                    catch (NumberFormatException ignored) {}
                }

                entryList.add(new InstanceInfo(
                        node.path("name").asText(),
                        node.path("status").asText(),
                        configVal(config, Metadata.PROJECT, "-"),
                        configVal(config, Metadata.PROFILE, "-"),
                        created,
                        node.path("type").asText(),
                        parent,
                        configVal(config, "limits.cpu", ""),
                        configVal(config, "limits.memory", ""),
                        rootSize,
                        ipv4,
                        configVal(config, Metadata.NETWORK_MODE, ""),
                        node.path("architecture").asText(""),
                        configVal(config, Metadata.BUILD_VERSION, ""),
                        configVal(config, Metadata.DEFINITION_SHA, ""),
                        type,
                        configVal(config, Metadata.BUILD_SOURCE, ""),
                        configVal(config, Metadata.PENDING_OP, ""),
                        configVal(config, Metadata.DEFAULT_ACTION, ""),
                        diskUsage, referencedBytes,
                        configVal(config, Metadata.INSTANCE_MODE, ""),
                        config.has(Metadata.KVM_ENABLED),
                        McpStanding.fromListing(node, callerGrants),
                        Metadata.isMcpCaller(node)));
            }
            return entryList;
        } catch (JsonProcessingException e) {
            throw new IncusException("Cannot read the instance listing from Incus: " + e.getOriginalMessage(), e);
        }
    }

    private static String configVal(JsonNode config, String key, String defaultValue) {
        var val = config.path(key).asText("");
        return val.isEmpty() ? defaultValue : val;
    }

    /**
     * Sum the {@code usage} bytes across every disk device in an instance's
     * {@code state.disk} node (from the recursion=2 listing). Returns -1 when no
     * usage is reported — btrfs/zfs/lvm pools report per-volume usage, but a
     * {@code dir} pool does not, and stopped instances may report nothing. A -1
     * renders as "-" rather than a misleading zero.
     */
    static long sumDiskUsage(JsonNode diskNode) {
        if (diskNode == null || !diskNode.isObject() || diskNode.isEmpty()) return -1;
        long total = 0;
        boolean any = false;
        for (var devices = diskNode.elements(); devices.hasNext(); ) {
            var usage = devices.next().path("usage").asLong(-1);
            if (usage >= 0) {
                total += usage;
                any = true;
            }
        }
        return any ? total : -1;
    }

    // Package-private so canUseReferencedModel(...) can be unit-tested with hand-built rows.
    record TemplateInfo(String name, String description,
                                String buildStatus, String runtime, String buildVersion,
                                String definitionSha, String pendingOp, String parent, long diskUsage,
                                long referencedBytes, String instanceMode) {
        static final String NOT_BUILT = "not built";

        /** Whether this template has been built (has a subvolume/stamp), vs. definition-only. */
        boolean isBuilt() {
            return !NOT_BUILT.equals(buildStatus);
        }

        /** Whether this is a definitional root (no template parent) — where the base weight lands. */
        boolean isRoot() {
            return isRootParent(parent);
        }

        /** Copy with a substituted disk weight — used by the two disk-attribution models. */
        TemplateInfo withDiskUsage(long newDiskUsage) {
            return new TemplateInfo(name, description, buildStatus, runtime, buildVersion,
                    definitionSha, pendingOp, parent, newDiskUsage, referencedBytes, instanceMode);
        }

        /** Copy with a substituted referenced size — used to backfill a missing stamp live. */
        TemplateInfo withReferencedBytes(long newReferencedBytes) {
            return new TemplateInfo(name, description, buildStatus, runtime, buildVersion,
                    definitionSha, pendingOp, parent, diskUsage, newReferencedBytes, instanceMode);
        }
    }

    record InstanceInfo(String name, String status,
                                String project, String profile, String created,
                                String runtime, String parent,
                                String limitsCpu, String limitsMemory, String rootSize,
                                String ipv4, String networkMode, String architecture,
                                String buildVersion, String definitionSha,
                                String type, String buildSourceJson, String pendingOp,
                                String defaultAction, long diskUsage, long referencedBytes,
                                String instanceMode, boolean kvmEnabled,
                                McpStanding mcp, boolean mcpCaller) {
        MachineType machineType() { return MachineType.fromIncus(runtime); }
    }
}
