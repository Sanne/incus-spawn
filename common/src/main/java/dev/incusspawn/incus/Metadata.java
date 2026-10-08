package dev.incusspawn.incus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

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

    /**
     * The leaf template of an instance, from its full config: {@link #PROFILE}, else {@link #PARENT}
     * (which names a clone when the instance was branched from one). Null when neither is set.
     */
    public static String templateOf(Map<String, String> config) {
        var profile = config.getOrDefault(PROFILE, "");
        var template = profile.isBlank() ? config.getOrDefault(PARENT, "") : profile;
        return template.isBlank() ? null : template;
    }
    public static final String NETWORK_MODE = PREFIX + "network-mode";
    public static final String PROXY_GATEWAY = PREFIX + "proxy-gateway";
    public static final String BUILD_VERSION = PREFIX + "build-version";
    public static final String BUILD_SHA = PREFIX + "build-sha";
    public static final String CA_FINGERPRINT = PREFIX + "ca-fingerprint";
    public static final String HOST_CA_FINGERPRINT = PREFIX + "host-ca-fingerprint";
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

    /** The operation another isx process has under way on an instance, from its config; "" for none. */
    public static String pendingOp(java.util.Map<String, String> config) {
        return config.getOrDefault(PENDING_OP, "");
    }
    public static final String STATIC_IP = PREFIX + "static-ip";
    public static final String STATIC_GATEWAY = PREFIX + "static-gateway";
    /**
     * Set on a VM whose address was reassigned while its {@code .network} file could not be
     * pushed (that needs the running agent); cleared once it is. Until then the guest would come
     * up on the old address, which the NIC's IP filtering drops.
     */
    public static final String NETWORK_PUSH_PENDING = PREFIX + "network-push-pending";
    /**
     * SHA-256 of the secret the instance was given at its last start by isx; see
     * {@code InstanceSecret}. Never the secret itself: the guest can read its own {@code user.*}
     * keys through {@code /dev/incus}.
     */
    public static final String INSTANCE_SECRET_SHA256 = PREFIX + "instance-secret-sha256";
    /**
     * The {@code last_used_at} of the container boot {@link #INSTANCE_SECRET_SHA256} was made for.
     * Incus sets it on every start, a reboot isx did not do included, which empties the guest's
     * {@code /run} and the secret with it: a running container whose value differs needs a new
     * one (#1024). VMs are not stamped, since QEMU can reboot one in place without a new start.
     */
    public static final String INSTANCE_SECRET_BOOT = PREFIX + "instance-secret-boot";
    /**
     * The {@link IncusClient#pid} of the boot isx last restarted a VM into because its agent did
     * not answer. Unresponsive again on that same boot, the VM is reported rather than restarted
     * a second time (#843).
     */
    public static final String AGENT_RESTART_BOOT = PREFIX + "agent-restart-boot";
    /** Prefix of the per-namespace credential account selection; see {@link #accountKey}. */
    public static final String ACCOUNT_PREFIX = PREFIX + "account.";
    /** Prefix of who chose each namespace's pinned account; see {@link #accountOriginKey}. */
    public static final String ACCOUNT_ORIGIN_PREFIX = PREFIX + "account-origin.";
    /** Prefix of what the build derived from each namespace's account; see {@link #accountIdentityKey}. */
    public static final String ACCOUNT_IDENTITY_PREFIX = PREFIX + "account-identity.";
    // Referenced (rfer) bytes of a built template's btrfs subvolume, stamped once at build time.
    // Templates are immutable and rfer is stable, so this cached value stays correct; the TUI uses
    // it to show each template as a delta from its parent (see BtrfsUsage / ListCommand). Not part
    // of contentFingerprint(), so stamping it never triggers a rebuild.
    public static final String DISK_REFERENCED = PREFIX + "disk-referenced";

    /**
     * MCP ownership (see {@code isx mcp}). An instance belongs to the host user in
     * {@link #MCP_OWNER}, and is held by one session at a time: {@link #MCP_SESSION} is
     * {@code <pid>-<processStartMillis>} of the {@code isx mcp} process holding it, which tells
     * whether the holder is still alive. Any session of the same user may adopt it once its
     * holder is gone. The rest are for display.
     */
    public static final String MCP_PREFIX = PREFIX + "mcp-";
    public static final String MCP_SESSION = MCP_PREFIX + "session";
    public static final String MCP_OWNER = MCP_PREFIX + "owner";
    /** Free text the agent gave at creation, e.g. {@code #870 implement}. */
    public static final String MCP_PURPOSE = MCP_PREFIX + "purpose";
    /**
     * When the instance lost its session (an ISO-8601 instant): stamped when the session ends,
     * or by the first later session to notice it died. Orphans are destroyed only once
     * {@code mcp.orphan-grace-hours} have passed since; adoption clears it.
     */
    public static final String MCP_ORPHANED = MCP_PREFIX + "orphaned";
    public static final String MCP_CLIENT = MCP_PREFIX + "client";
    public static final String MCP_CLIENT_PID = MCP_PREFIX + "client-pid";
    public static final String MCP_CWD = MCP_PREFIX + "cwd";
    /** Set by {@code keep_instance}: the instance now belongs to the user and outlives the session. */
    public static final String MCP_KEPT = MCP_PREFIX + "kept";
    /**
     * Set by {@code isx branch --mcp-client}, and only there: this instance may call
     * {@code isx mcp} over the network, through the proxy at {@link
     * dev.incusspawn.proxy.ProxyConfig#MCP_DOMAIN} (#915). The value is a random grant id
     * ({@link #newMcpCallerGrant}), part of the coordinator's session id: a coordinator destroyed
     * and recreated under the same name is another grant, so never the holder of the old one's
     * workers. Not a secret -- the guest can read it; who calls is checked by address and secret. An
     * {@code mcp-} key, so no copy ever carries it ({@code configureBranch}), and the MCP
     * create path refuses it: an agent cannot make another coordinator.
     */
    public static final String MCP_CALLER = MCP_PREFIX + "caller";
    /**
     * The {@code idempotency_key} the create that made the instance was given: a later create
     * with the same key returns this instance instead of making another. Written by the copy
     * request itself; like every {@code mcp-} key, never carried by a copy, not even before the
     * copy is configured ({@code BranchFlow} unsets the source's in the copy request).
     */
    public static final String MCP_IDEMPOTENCY_KEY = MCP_PREFIX + "idempotency-key";

    public static final String TYPE_BASE = "base";
    public static final String TYPE_PROJECT = "project";
    public static final String TYPE_CLONE = "clone";
    public static final String TYPE_FAILED_BUILD = "failed-build";

    public static final String OP_STOPPING = "stopping";
    public static final String OP_RESTARTING = "restarting";
    public static final String OP_DELETING = "deleting";

    private Metadata() {}

    /** Whether an instance's config grants it {@link #MCP_CALLER}: a well-formed grant id does. */
    public static boolean isMcpCaller(Map<String, String> config) {
        return isMcpCallerGrant(config == null ? null : config.get(MCP_CALLER));
    }

    /**
     * Whether {@code value} is a grant id {@link #newMcpCallerGrant} could have made. The one
     * definition the proxy and {@code isx mcp} both judge a stamp by: a value one accepts and the
     * other refuses would pass the proxy and then end the session unexplained.
     */
    public static boolean isMcpCallerGrant(String value) {
        return value != null && MCP_CALLER_GRANT.matcher(value).matches();
    }

    /** What a {@link #MCP_CALLER} grant id looks like. */
    public static final java.util.regex.Pattern MCP_CALLER_GRANT = java.util.regex.Pattern.compile("[0-9a-f]{32}");

    /** A new {@link #MCP_CALLER} grant id: 128 random bits, hex. */
    public static String newMcpCallerGrant() {
        // A SecureRandom per call: a static one would be seeded at native-image build time.
        var bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    public static boolean isMcpKey(String key) {
        return key.startsWith(MCP_PREFIX);
    }

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

    /**
     * Key recording who chose the account pinned under {@link #accountKey} -- a template's
     * {@code accounts:}, or an explicit choice for an instance -- so it can be reported as a
     * fact rather than guessed from whether the pin happens to match the template.
     *
     * @see dev.incusspawn.config.AccountOrigin
     */
    public static String accountOriginKey(String namespace) {
        return ACCOUNT_ORIGIN_PREFIX + namespace;
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

    /**
     * A {@link #now()} stamp, which is local time, as ISO-8601 with the offset that makes it an
     * instant, to the second: what machine-readable output shows. A legacy date-only stamp (isx
     * before time-of-day stamps) is an ISO-8601 date, {@code 2026-09-01}, since it never recorded
     * a time to put an offset on. An empty stamp or one that does not parse is {@code null}.
     */
    public static String createdIso(String created, java.time.ZoneId zone) {
        if (created.isEmpty()) return null;
        try {
            if (!created.contains("T")) {
                LocalDate.parse(created, DateTimeFormatter.ISO_LOCAL_DATE);
                return created;
            }
            return LocalDateTime.parse(created, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .truncatedTo(ChronoUnit.SECONDS).atZone(zone)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
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
