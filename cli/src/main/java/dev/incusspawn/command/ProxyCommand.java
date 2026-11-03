package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.proxy.ApiTrafficLog;
import dev.incusspawn.proxy.ProxyActivity;
import dev.incusspawn.proxy.DumpProxy;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyHolder;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.Platform;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

@CommandDefinition(
        name = "proxy",
        description = "Manage the MITM authentication proxy",
        generateHelp = true,
        groupCommands = {
                ProxyStartCommand.class,
                ProxyCommand.Stop.class,
                ProxyCommand.Restart.class,
                ProxyCommand.Status.class,
                ProxyCommand.Activity.class,
                ProxyCommand.Install.class,
                ProxyCommand.Uninstall.class,
                ProxyCommand.Logs.class,
                ProxyCommand.Dump.class
        }
)
public class ProxyCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        BuildOutput.results().println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    static Path logFile() { return Environment.proxyLogFile(); }

    @CommandDefinition(
            name = "status",
            description = "Check if the MITM TLS proxy is running",
            generateHelp = true
    )
    public static class Status extends BaseCommand {

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
        String format;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            var incus = RuntimeServices.incus();
            // On macOS a healthy proxy answers on loopback without asking Incus, so the gateway
            // is only looked up, and only fails, when the check actually needs it.
            ProxyHealthCheck.ProxyStatus status;
            String healthIp;
            try {
                status = ProxyHealthCheck.check(incus);
                healthIp = ProxyHealthCheck.healthAddress(incus);
            } catch (Exception e) {
                BuildOutput.error("Could not determine Incus bridge gateway IP: " + e.getMessage(),
                        ProxyConfig.gatewayUnavailableHint(Platform.isMacOS()));
                if (outputFormat != OutputFormat.TABLE) {
                    // Exit 1 as in the table, but the record says the state is unknown, so a
                    // script tells "could not check" from "not running".
                    var serviceInstalled = ProxyService.isInstalled();
                    outputFormat.printOne(BuildOutput.results(), record(null, null, null, serviceInstalled,
                            serviceInstalled && ProxyService.isActive() ? (Platform.isMacOS() ? "launchd" : "systemd") : null,
                            ("Could not determine Incus bridge gateway IP: " + e.getMessage()).strip()));
                }
                return CommandResult.valueOf(1);
            }
            var serviceInstalled = ProxyService.isInstalled();
            var serviceActive = serviceInstalled && ProxyService.isActive();
            var running = status == ProxyHealthCheck.ProxyStatus.RUNNING
                    || status == ProxyHealthCheck.ProxyStatus.WAITING_FOR_DNS;
            var proxyInfo = running ? ProxyHealthCheck.fetchProxyInfo(healthIp) : null;
            // Whether what answers is the service's own process (#1570). Asked of an installed
            // service, not only an active one: a unit that cannot bind waits in auto-restart.
            var holder = serviceInstalled ? ProxyService.foreignHolder(proxyInfo) : null;
            if (outputFormat != OutputFormat.TABLE) {
                var record = record(status, proxyInfo, healthIp, serviceInstalled,
                        managedBy(status, serviceActive, Platform.isMacOS()), null);
                addHolder(record, proxyInfo, serviceInstalled, holder);
                outputFormat.printOne(BuildOutput.results(), record);
                return CommandResult.valueOf(exitCode(status, holder));
            }
            return CommandResult.valueOf(switch (status) {
                case RUNNING, WAITING_FOR_DNS -> reportRunning(status, proxyInfo, healthIp, holder, serviceActive,
                        Platform.isMacOS(), BuildOutput.results());
                case NOT_RUNNING, STALE_DNS, STALE_GATEWAY -> reportDown(status, serviceInstalled, serviceActive,
                        BuildOutput.results());
            });
        }

        /** How far a field of the report sits under its state. */
        private static final String FIELD_INDENT = "  ";

        /** A field of the report: {@code "  Version:         1.2 (abc)"}. */
        static String field(String label, Object value) {
            return FIELD_INDENT + String.format("%-17s", label + ":") + value;
        }

        /** A line of the report that stands out under its state, such as a drift: {@code "  >>> ..."}. */
        private static String flagged(String style, String text) {
            return FIELD_INDENT + BuildOutput.styled(style, ">>> " + text);
        }

        /**
         * Report a proxy that answers, on {@code out}: its state, then what it says of itself and
         * who runs it, one field a line. Another user's drift and auth error are theirs to act on,
         * so a proxy another user holds reports neither.
         */
        static int reportRunning(ProxyHealthCheck.ProxyStatus status, ProxyHealthCheck.ProxyInfo proxyInfo,
                                 String healthIp, ProxyHolder holder, boolean serviceActive, boolean macOS,
                                 PrintStream out) {
            out.println(status == ProxyHealthCheck.ProxyStatus.RUNNING
                    ? "Proxy is running." : "Proxy is running (waiting for DNS configuration).");
            var urgent = BuildOutput.BOLD + BuildOutput.RED;
            if (holder != null) holder.lines().forEach(line -> out.println(flagged(urgent, line)));
            var ownInfo = holder != null && holder.otherUser() ? null : proxyInfo;
            if (ownInfo != null) {
                if (!ownInfo.isLegacy()) {
                    out.println(field("Version", ownInfo.displayVersion() + " (" + ownInfo.gitSha() + ")"));
                    if (ownInfo.runtime() != null && !ownInfo.runtime().isEmpty()) {
                        out.println(field("Runtime", ownInfo.runtime()));
                    }
                }
                out.println(field("DNS overrides", ownInfo.dnsConfigured() ? "active" : "pending"));
                var drift = ProxyHealthCheck.assessDrift(ownInfo);
                for (var d : drift.drifts()) out.println(flagged(BuildOutput.BOLD + BuildOutput.YELLOW, d));
                // Under the drift it explains, past its ">>> "
                if (drift.futileReason() != null) out.println(FIELD_INDENT.repeat(3) + drift.futileReason());
                if (ownInfo.hasAuthError()) out.println(flagged(urgent, "Auth error: " + ownInfo.authError()));
            }
            out.println(field("Health endpoint", "http://" + healthIp + ":" + ProxyConfig.DEFAULT_HEALTH_PORT + "/health"));
            out.println(field("MITM port", ProxyConfig.DEFAULT_MITM_PORT));
            out.println(field("Managed by", holder != null
                    ? (holder.otherUser() ? "user " + holder.user() : "manual (not the proxy service)")
                    : serviceActive ? (macOS ? "launchd (dev.incusspawn.proxy)" : "systemd (incus-spawn-proxy.service)")
                    : "manual (foreground process)"));
            return exitCode(status, holder);
        }

        /**
         * Report a proxy that instances cannot use, on {@code out} like a healthy one's: the
         * report is the command's result, and the exit code tells the states apart.
         */
        static int reportDown(ProxyHealthCheck.ProxyStatus status, boolean serviceInstalled,
                              boolean serviceActive, PrintStream out) {
            switch (status) {
                case NOT_RUNNING -> {
                    out.println("Proxy is not running.");
                    if (serviceInstalled) {
                        out.println("Service is installed but not active. Start it with: isx proxy install");
                    } else {
                        out.println("Start it with: isx proxy start");
                        out.println("Or install as a service: isx proxy install");
                    }
                    return exitCode(status);
                }
                case STALE_DNS -> {
                    out.println("Proxy is not running, but DNS overrides are still active.");
                    out.println("Start the proxy to restore connectivity: isx proxy start");
                    return exitCode(status);
                }
                case STALE_GATEWAY -> {
                    out.println("Proxy is running, but on an old address of incusbr0 that instances cannot reach.");
                    out.println("Restart it to bind the current address: "
                            + (serviceActive ? "isx proxy restart" : "isx proxy stop && isx proxy start"));
                    return exitCode(status);
                }
                default -> throw new IllegalArgumentException("not a down state: " + status);
            }
        }

        /**
         * Who runs the proxy: {@code launchd} or {@code systemd} whenever the service is active,
         * in any state; otherwise {@code manual} for a foreground process -- one on an old gateway
         * address included, which still has to be stopped -- and {@code null} when nothing runs it.
         */
        static String managedBy(ProxyHealthCheck.ProxyStatus status, boolean serviceActive, boolean macOS) {
            if (serviceActive) return macOS ? "launchd" : "systemd";
            return switch (status) {
                case RUNNING, WAITING_FOR_DNS, STALE_GATEWAY -> "manual";
                case NOT_RUNNING, STALE_DNS -> null;
            };
        }

        /** The exit code for a proxy held by another user's (#1570): instances reach its CA and credentials. */
        static final int EXIT_OTHER_USERS_PROXY = 4;

        /**
         * {@link #exitCode(ProxyHealthCheck.ProxyStatus)}, except that a running proxy owned by
         * another user is {@link #EXIT_OTHER_USERS_PROXY}: it answers, but not for this user.
         */
        static int exitCode(ProxyHealthCheck.ProxyStatus status, ProxyHolder holder) {
            var code = exitCode(status);
            return code == 0 && holder != null && holder.otherUser() ? EXIT_OTHER_USERS_PROXY : code;
        }

        /**
         * The last fields of {@link #record}: {@code pid} and {@code pid_owner} of the proxy that
         * answered ({@code null} when it did not say), and {@code foreign_holder}, whether the
         * installed service's process is not that proxy ({@code null} with no service).
         */
        static void addHolder(Map<String, Object> record, ProxyHealthCheck.ProxyInfo info,
                              boolean serviceInstalled, ProxyHolder holder) {
            var answering = holder != null ? holder : ProxyHolder.answering(info);
            record.put("pid", answering == null ? null : answering.pid());
            record.put("pid_owner", answering == null ? null : answering.user());
            record.put("foreign_holder", serviceInstalled && answering != null ? holder != null : null);
            if (holder != null && holder.otherUser()) {
                // Another user's drift and auth error are theirs to act on, as in the table:
                // restart_helps would invite a script to restart a service that cannot take the ports.
                record.put("drift", null);
                record.put("auth_error", null);
                record.put("restart_helps", null);
            }
        }

        /** The exit code of every format: 0 when instances can use the proxy, else 1, 2 or 3 by state. */
        static int exitCode(ProxyHealthCheck.ProxyStatus status) {
            return switch (status) {
                case RUNNING, WAITING_FOR_DNS -> 0;
                case NOT_RUNNING -> 1;
                case STALE_DNS -> 2;
                case STALE_GATEWAY -> 3;
            };
        }

        /**
         * The fields of {@code isx proxy status --format=plain|json}, in order: add to the end,
         * never rename. {@code status} is the state the exit code reports, in lower case; what
         * the running proxy says of itself is {@code null} when it is not running or too old to say.
         * {@code drift} is the drift sentences as one string ({@code null} for none): they
         * contain commas, so a list would not survive {@code plain}; {@code restart_helps} says
         * whether restarting the service clears it ({@code null} for none). A {@code null}
         * {@code status} is {@code unknown}: the state could not be checked, and
         * {@code check_error} says why.
         */
        static Map<String, Object> record(ProxyHealthCheck.ProxyStatus status, ProxyHealthCheck.ProxyInfo info,
                                          String healthIp, boolean serviceInstalled, String managedBy,
                                          String checkError) {
            var known = info != null && !info.isLegacy();
            var record = new LinkedHashMap<String, Object>();
            record.put("status", status == null ? "unknown" : status.name().toLowerCase(Locale.ROOT));
            record.put("version", known ? info.version() : null);
            record.put("git_sha", known ? info.gitSha() : null);
            record.put("runtime", known && info.runtime() != null && !info.runtime().isEmpty() ? info.runtime() : null);
            record.put("dns_overrides", info == null ? null : info.dnsConfigured());
            var drift = info == null ? null : ProxyHealthCheck.assessDrift(info);
            var drifted = drift != null && !drift.isEmpty();
            record.put("drift", drifted ? String.join(" ", drift.drifts()) : null);
            record.put("auth_error", info != null && info.hasAuthError() ? info.authError() : null);
            record.put("health_endpoint", healthIp == null ? null
                    : "http://" + healthIp + ":" + ProxyConfig.DEFAULT_HEALTH_PORT + "/health");
            record.put("mitm_port", ProxyConfig.DEFAULT_MITM_PORT);
            record.put("service_installed", serviceInstalled);
            record.put("managed_by", managedBy);
            record.put("restart_helps", drifted ? drift.restartHelps() : null);
            record.put("check_error", checkError);
            // Filled in by addHolder where the proxy answered; null otherwise.
            record.put("pid", null);
            record.put("pid_owner", null);
            record.put("foreign_holder", null);
            return record;
        }
    }

    @CommandDefinition(
            name = "activity",
            description = "Show what each instance has sent through the proxy, by domain and in model calls",
            generateHelp = true
    )
    public static class Activity extends BaseCommand {

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
        String format;

        @Option(name = "by-domain", hasValue = false, description = "One row per instance and domain")
        boolean byDomain;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            ProxyActivity activity;
            try {
                activity = ProxyActivity.fetch(ProxyHealthCheck.healthAddress(RuntimeServices.incus()));
            } catch (ProxyActivity.Unavailable e) {
                BuildOutput.error("Cannot read the proxy's activity: " + e.getMessage() + ".",
                        "Check it with: isx proxy status");
                return CommandResult.valueOf(1);
            }
            print(activity, outputFormat, byDomain, ZoneId.systemDefault(), Instant.now(), BuildOutput.results());
            return CommandResult.SUCCESS;
        }

        private static final String DOMAIN_ROW = "%-30s %-28s %9s %9s %10s %10s  %s%n";
        private static final String ROW = "%-30s %9s %9s %10s %10s  %-14s %11s %13s%n";

        static void print(ProxyActivity activity, OutputFormat format, boolean byDomain, ZoneId zone,
                          Instant now, PrintStream out) {
            var records = byDomain ? domainRecords(activity, zone) : records(activity, zone);
            if (format != OutputFormat.TABLE) {
                format.print(out, records);
                return;
            }
            if (byDomain) {
                out.printf(DOMAIN_ROW, "INSTANCE", "DOMAIN", "REQUESTS", "IN FLIGHT", "SENT", "RECEIVED", "LAST ACTIVITY");
                for (var r : records) {
                    out.printf(DOMAIN_ROW, name(r), r.get("domain"), r.get("requests"), r.get("in_flight"),
                            size(r.get("request_bytes")), size(r.get("response_bytes")), ago(r.get("last_activity_at"), now));
                }
                printPlacement(activity, out);
                return;
            }
            out.printf(ROW, "INSTANCE", "REQUESTS", "IN FLIGHT", "SENT", "RECEIVED", "LAST ACTIVITY", "MODEL CALLS",
                    "OUTPUT TOKENS");
            for (var r : records) {
                out.printf(ROW, name(r), r.get("requests"), r.get("in_flight"), size(r.get("request_bytes")),
                        size(r.get("response_bytes")), ago(r.get("last_activity_at"), now),
                        dash(r.get("model_requests")), dash(r.get("output_tokens")));
            }
            printPlacement(activity, out);
        }

        /** How well the proxy tells its callers apart (#1586), under the table; nothing when nothing is wrong. */
        private static void printPlacement(ProxyActivity activity, PrintStream out) {
            var placement = activity.placement();
            var lines = new ArrayList<String>();
            if (placement.refusedProofs() > 0 || placement.refusedAddressMisses() > 0) {
                lines.add("Refused proofs: %d matched no proof of their instance, %d came from an address that is no instance"
                        .formatted(placement.refusedProofs(), placement.refusedAddressMisses()));
            }
            // What isx doctor judges address misses by; an idle instance is listed too, so only beside them
            if (placement.refusedAddressMisses() > 0 && !placement.unplacedRunning().isEmpty()) {
                lines.add("Running, no traffic attributed: "
                        + String.join(", ", placement.unplacedRunning().stream().map(OutputFormat::oneLine).toList()));
            }
            if (placement.registryStale()) lines.add("Instance listing failed or not answering: proofs are refused until one succeeds");
            if (lines.isEmpty()) return;
            out.println();
            lines.forEach(out::println);
        }

        private static String name(Map<String, Object> record) {
            var instance = record.get("instance");
            return instance == null ? "(unattributed)" : OutputFormat.oneLine(instance.toString());
        }

        private static String size(Object bytes) {
            return CleanCommand.formatSize((long) bytes);
        }

        private static Object dash(Object value) {
            return value == null ? "-" : value;
        }

        /**
         * Seconds, not {@code Metadata.ageDescription}'s minutes: "just now" cannot tell an agent
         * that answered a second ago from one silent for most of a minute.
         */
        private static String ago(Object iso, Instant now) {
            if (iso == null) return "-";
            var seconds = Math.max(0, java.time.Duration.between(java.time.OffsetDateTime.parse(iso.toString()).toInstant(), now).toSeconds());
            if (seconds < 60) return seconds + "s ago";
            if (seconds < 3600) return seconds / 60 + "m ago";
            if (seconds < 86400) return seconds / 3600 + "h ago";
            return seconds / 86400 + "d ago";
        }

        /**
         * The fields of {@code isx proxy activity --format=plain|json}, one record per instance the
         * proxy knows, by name, then one whose {@code instance} is {@code null}: traffic from no
         * known instance (the host, or an address the proxy has not listed), whose model calls
         * are not counted ({@code null}). In order: add to the end, never rename. Counts run from
         * {@code counting_since}; two reads subtract only with the same one. The {@code requests}
         * to {@code response_bytes} fields are all traffic, model calls included; the
         * {@code model_} ones and the tokens are model calls alone. {@code identified_by_proof}: its
         * proof or secret alone named it, not its address (#1586). The proxy's {@code refused_proofs},
         * {@code refused_address_misses}, {@code registry_stale}, {@code unplaced_running} and {@code last_address_miss_at} are
         * on the unattributed record alone, {@code null} on the others (#1586).
         */
        static List<Map<String, Object>> records(ProxyActivity activity, ZoneId zone) {
            var records = new ArrayList<Map<String, Object>>();
            for (var name : new TreeSet<>(activity.instances().keySet())) {
                var i = activity.of(name);
                records.add(record(name, i.countingSince(), i.traffic().total(), i, null, zone));
            }
            records.add(record(null, activity.unattributedSince(), activity.unattributed().total(), null,
                    activity.placement(), zone));
            return records;
        }

        private static Map<String, Object> record(String instance, Instant since, ProxyActivity.Counts traffic,
                                                  ProxyActivity.Instance model, ProxyActivity.Placement placement,
                                                  ZoneId zone) {
            var record = traffic(instance, null, since, traffic, zone);
            record.put("model_requests", model == null ? null : model.requests());
            record.put("model_in_flight", model == null ? null : model.inFlight());
            record.put("last_model_request_at", model == null ? null : iso(model.lastRequestAt(), zone));
            record.put("last_model_response_at", model == null ? null : iso(model.lastResponseAt(), zone));
            record.put("input_tokens", model == null ? null : model.inputTokens());
            record.put("output_tokens", model == null ? null : model.outputTokens());
            record.put("cache_read_input_tokens", model == null ? null : model.cacheReadInputTokens());
            record.put("cache_creation_input_tokens", model == null ? null : model.cacheCreationInputTokens());
            record.put("identified_by_proof", model == null ? null : model.identifiedByProof());
            record.put("refused_proofs", placement == null ? null : placement.refusedProofs());
            record.put("refused_address_misses", placement == null ? null : placement.refusedAddressMisses());
            record.put("registry_stale", placement == null ? null : placement.registryStale());
            record.put("unplaced_running", placement == null ? null : placement.unplacedRunning());
            record.put("last_address_miss_at", placement == null ? null : iso(placement.lastAddressMissAt(), zone));
            return record;
        }

        /**
         * The fields of {@code --by-domain}: one record per instance and domain it reached, in the
         * order of {@link #records}, {@code domain} being an intercepted domain (a subdomain counts
         * under it) or {@code (other)}. In order: add to the end, never rename.
         */
        static List<Map<String, Object>> domainRecords(ProxyActivity activity, ZoneId zone) {
            var records = new ArrayList<Map<String, Object>>();
            for (var name : new TreeSet<>(activity.instances().keySet())) {
                var i = activity.of(name);
                addDomains(records, name, i.countingSince(), i.traffic(), zone);
            }
            addDomains(records, null, activity.unattributedSince(), activity.unattributed(), zone);
            return records;
        }

        private static void addDomains(List<Map<String, Object>> records, String instance, Instant since,
                                       ProxyActivity.Traffic traffic, ZoneId zone) {
            for (var domain : new TreeSet<>(traffic.byDomain().keySet())) {
                records.add(traffic(instance, domain, since, traffic.byDomain().get(domain), zone));
            }
        }

        /** {@code instance}, {@code domain} unless null, then the traffic fields. */
        private static Map<String, Object> traffic(String instance, String domain, Instant since,
                                                   ProxyActivity.Counts counts, ZoneId zone) {
            var record = new LinkedHashMap<String, Object>();
            record.put("instance", instance);
            if (domain != null) record.put("domain", domain);
            record.put("counting_since", iso(since, zone));
            record.put("requests", counts.requests());
            record.put("in_flight", counts.inFlight());
            record.put("last_activity_at", iso(counts.lastActivityAt(), zone));
            record.put("request_bytes", counts.requestBytes());
            record.put("response_bytes", counts.responseBytes());
            return record;
        }

        private static String iso(Instant instant, ZoneId zone) {
            return instant == null ? null : instant.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
    }

    @CommandDefinition(
            name = "stop",
            description = "Stop the proxy (handles both systemd service and manual processes)",
            generateHelp = true
    )
    public static class Stop extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            return ProxyService.stop() ? CommandResult.SUCCESS : CommandResult.FAILURE;
        }
    }

    @CommandDefinition(
            name = "restart",
            description = "Restart the proxy service",
            generateHelp = true
    )
    public static class Restart extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            if (!ProxyService.isInstalled() && !ProxyService.isActive()) {
                BuildOutput.step("Proxy is not installed or running. Use 'isx proxy start' or 'isx proxy install'.");
                return CommandResult.SUCCESS;
            }
            if (!ProxyService.restart()) return CommandResult.valueOf(1);
            // A healthy answer may be another proxy's, which this restart did not reach (#1570).
            if (ProxyService.awaitStarted(ProxyService.configuredStartWaitSeconds())
                    && !ProxyService.answeringIsTheService()) {
                return CommandResult.valueOf(1);
            }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "install",
            description = "Install the proxy as a systemd user service (auto-starts on boot)",
            generateHelp = true
    )
    public static class Install extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            // Before the running-service path too: after an upgrade that bumped INIT_VERSION,
            // restarting the proxy here would stop a working one for one that refuses to start.
            if (!ProxyService.initComplete()) return CommandResult.FAILURE;
            var incus = RuntimeServices.incus();
            if (ProxyService.isActive()) {
                var info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
                // Another user's proxy on the ports (#1570): its drift is not this service's, and
                // no restart of this service can take the ports from it.
                var holder = ProxyService.foreignHolder(info);
                if (holder != null && holder.otherUser()) {
                    holder.report(BuildOutput.bound());
                    return CommandResult.FAILURE;
                }
                // An upgrade restarts the service: the drift read above is then the old proxy's,
                // and acting on it would restart the new one a second time.
                if (ProxyService.upgradeIfNeeded()) {
                    info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
                }
                if (ProxyService.reinstallIfChanged(incus, info)) {
                    BuildOutput.success("Proxy service restarted with updated binary");
                } else {
                    BuildOutput.step("Proxy service is already installed and running.");
                    // Drift it declined to restart for would otherwise go unexplained here.
                    var futile = ProxyHealthCheck.assessDrift(info).futileReason();
                    if (futile != null) BuildOutput.note(futile);
                }
                if (!ProxyService.awaitStarted(ProxyService.configuredStartWaitSeconds())) {
                    BuildOutput.error("Proxy service is registered but not responding.", ProxyService.notResponding());
                    return CommandResult.FAILURE;
                }
                // Something answering is not enough: it may be another proxy on the ports (#1570).
                return ProxyService.answeringIsTheService()
                        ? CommandResult.SUCCESS : CommandResult.FAILURE;
            }
            if (!ProxyService.install()) {
                return CommandResult.FAILURE;
            }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "uninstall",
            description = "Stop and remove the systemd proxy service",
            generateHelp = true
    )
    public static class Uninstall extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            return ProxyService.uninstall(RuntimeServices.incus()) ? CommandResult.SUCCESS : CommandResult.FAILURE;
        }
    }

    @CommandDefinition(
            name = "logs",
            description = "Follow the proxy log file in real time (like tail -f)",
            generateHelp = true
    )
    public static class Logs extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            if (!Files.exists(logFile())) {
                var fallback = showFallbackLogs();
                if (fallback != null) return fallback;
                BuildOutput.error("No proxy log file found at " + logFile(),
                        "The proxy has not been started yet, or logs have been cleared.");
                return CommandResult.valueOf(1);
            }

            // Show version and runtime at the beginning
            var build = BuildInfo.instance();
            String gatewayIp = "(unknown)";
            try {
                gatewayIp = ProxyConfig.resolveGatewayIp(incus);
            } catch (Exception ignored) {}

            var out = BuildOutput.results();
            var row = "%-15s%s%n";
            out.printf(row, "Gateway IP:", gatewayIp);
            out.printf(row, "MITM port:", ProxyConfig.DEFAULT_MITM_PORT);
            out.printf(row, "Version:", build.displayVersion() + " (" + build.gitSha() + ")");
            out.printf(row, "Runtime:", build.runtime());
            out.println();

            return LogFollower.follow(List.of("tail", "-f", logFile().toString()), "Failed to tail log file");
        }

        /**
         * Follows the service's own log where there is no proxy log file; null when there is none
         * to show, else how following it ended.
         */
        private CommandResult showFallbackLogs() {
            String header;
            List<String> command;
            if (Platform.isMacOS()) {
                var serviceLog = Environment.proxyServiceLogFile();
                if (!Files.exists(serviceLog)) return null;
                header = "Showing launchd service log instead (" + serviceLog + "):";
                command = List.of("tail", "-f", serviceLog.toString());
            } else if (ProxyService.isInstalled()) {
                header = "Showing systemd journal instead:";
                command = List.of("journalctl", "--user", "-u",
                        Environment.PROXY_SERVICE_NAME, "--no-pager", "-n", "50", "-f");
            } else {
                return null;
            }
            BuildOutput.note("No proxy log file at " + logFile());
            BuildOutput.note(header);
            return LogFollower.follow(command, "Failed to read service logs");
        }
    }

    @CommandDefinition(
            name = "dump",
            description = "Run a local pass-through proxy to capture host-side API traffic for debugging",
            generateHelp = true
    )
    public static class Dump extends BaseCommand {

        @Option(name = "port", description = "Local HTTP port (default: 19080)",
                defaultValue = {"19080"})
        int port;

        @Override
        protected CommandResult doExecute() throws Exception {
            try {
                var debugLog = new ApiTrafficLog(Environment.apiDebugDir().resolve("host"));
                var proxy = new DumpProxy(port, debugLog);
                proxy.start();
            } catch (IOException e) {
                BuildOutput.error("Failed to start dump proxy: " + e.getMessage());
                return CommandResult.valueOf(1);
            }
            return CommandResult.SUCCESS;
        }
    }

}
