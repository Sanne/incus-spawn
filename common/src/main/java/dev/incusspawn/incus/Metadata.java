package dev.incusspawn.incus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Constants and helpers for incus-spawn metadata stored on containers.
 */
public final class Metadata {

    public static final String PREFIX = "user.incus-spawn.";
    public static final String TYPE = PREFIX + "type";
    public static final String PROJECT = PREFIX + "project";
    public static final String CREATED = PREFIX + "created";
    public static final String PARENT = PREFIX + "parent";
    public static final String PROFILE = PREFIX + "profile";
    public static final String NETWORK_MODE = PREFIX + "network-mode";
    public static final String PROXY_GATEWAY = PREFIX + "proxy-gateway";
    public static final String BUILD_VERSION = PREFIX + "build-version";
    public static final String BUILD_SHA = PREFIX + "build-sha";
    public static final String CA_FINGERPRINT = PREFIX + "ca-fingerprint";
    public static final String HOST_RESOURCES = PREFIX + "host-resources";
    public static final String DEFINITION_SHA = PREFIX + "definition-sha";
    public static final String BUILD_SOURCE = PREFIX + "build-source";
    public static final String GUI_ENABLED = PREFIX + "gui-enabled";
    public static final String INSTANCE_MODE = PREFIX + "instance-mode";
    public static final String KVM_ENABLED = PREFIX + "kvm-enabled";
    public static final String WORKDIR = PREFIX + "workdir";
    public static final String SHELL_COMMAND = PREFIX + "shell-command";
    public static final String DEFAULT_ACTION = PREFIX + "default-action";
    public static final String PENDING_OP = PREFIX + "pending-op";
    public static final String STATIC_IP = PREFIX + "static-ip";
    public static final String STATIC_GATEWAY = PREFIX + "static-gateway";
    /** Prefix of the per-namespace credential account selection; see {@link #accountKey}. */
    public static final String ACCOUNT_PREFIX = PREFIX + "account.";
    /** Prefix of what the build derived from each namespace's account; see {@link #accountIdentityKey}. */
    public static final String ACCOUNT_IDENTITY_PREFIX = PREFIX + "account-identity.";
    // Referenced (rfer) bytes of a built template's btrfs subvolume, stamped once at build time.
    // Templates are immutable and rfer is stable, so this cached value stays correct; the TUI uses
    // it to show each template as a delta from its parent (see BtrfsUsage / ListCommand). Not part
    // of contentFingerprint(), so stamping it never triggers a rebuild.
    public static final String DISK_REFERENCED = PREFIX + "disk-referenced";

    public static final String TYPE_BASE = "base";
    public static final String TYPE_PROJECT = "project";
    public static final String TYPE_CLONE = "clone";
    public static final String TYPE_FAILED_BUILD = "failed-build";

    public static final String OP_STOPPING = "stopping";
    public static final String OP_RESTARTING = "restarting";
    public static final String OP_DELETING = "deleting";

    private Metadata() {}

    /**
     * Key holding which named credential account this instance uses for a config
     * namespace ({@code claude}, {@code github}, ... -- the {@code config-namespace}
     * a tool declares). The value is an account <em>name</em>, never a credential:
     * secrets stay on the host, which is the whole point of the proxy.
     */
    public static String accountKey(String namespace) {
        return ACCOUNT_PREFIX + namespace;
    }

    /**
     * Key holding what the build derived from this namespace's account -- Claude's auth mode,
     * GitHub's identity. A later re-point compares against it and either asks the tool to
     * bring the instance in line or refuses the swap, rather than half-applying it.
     *
     * <p>Absent for namespaces whose credential is pure header substitution, where nothing in
     * the image depends on which account is in use and every account is interchangeable.
     *
     * @see dev.incusspawn.tool.ToolSetup#bakedAccountIdentity
     */
    public static String accountIdentityKey(String namespace) {
        return ACCOUNT_IDENTITY_PREFIX + namespace;
    }

    public static String getType(IncusClient incus, String name) {
        try {
            return incus.configGet(name, TYPE);
        } catch (Exception e) {
            return "";
        }
    }

    public static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** @deprecated Use {@link #now()} instead. */
    @Deprecated
    public static String today() {
        return now();
    }

    public static String ageDescription(String created) {
        return ageDescription(created, LocalDateTime.now());
    }

    /**
     * How long ago {@code created} was, relative to {@code now}. Elapsed-time wording ("3h ago")
     * rather than a wall-clock time ("today 21:20"), so the text stays true for as long as a
     * long-lived view keeps re-rendering it, and only changes at minute granularity -- see
     * {@link #ageRefreshKey}. A legacy date-only stamp has no time of day, so it is described in
     * calendar days rather than implying an hour it never recorded.
     */
    public static String ageDescription(String created, LocalDateTime now) {
        // Count whole minutes between the two stamps' minutes, ignoring seconds, so the text
        // changes exactly when the refresh key does -- not at whatever second the stamp carried.
        now = ageRefreshKey(now);
        try {
            if (!created.contains("T")) {
                var date = LocalDate.parse(created, DateTimeFormatter.ISO_LOCAL_DATE);
                var days = ChronoUnit.DAYS.between(date, now.toLocalDate());
                if (days <= 0) return "today";
                if (days == 1) return "yesterday";
                return coarseAge(days);
            }
            var createdTime = ageRefreshKey(LocalDateTime.parse(created, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            var minutes = ChronoUnit.MINUTES.between(createdTime, now);
            if (minutes < 1) return "just now"; // includes a stamp slightly ahead of this clock
            if (minutes < 60) return minutes + " min ago";
            if (minutes < 24 * 60) return (minutes / 60) + "h ago";
            return coarseAge(minutes / (24 * 60));
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * The instant granularity of {@link #ageDescription}: its output for any timestamp can only
     * change when this value does, so a view re-renders ages when the key moves on and not
     * otherwise.
     */
    public static LocalDateTime ageRefreshKey(LocalDateTime now) {
        return now.truncatedTo(ChronoUnit.MINUTES);
    }

    private static String coarseAge(long days) {
        if (days < 7) return plural(days, "day");
        if (days < 30) return plural(days / 7, "week");
        return plural(days / 30, "month");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s") + " ago";
    }
}
