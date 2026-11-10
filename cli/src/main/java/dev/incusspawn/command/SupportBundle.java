package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;
import dev.incusspawn.Platform;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.SecretRedactor;
import dev.incusspawn.config.SecretRegistry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the {@code isx doctor --bundle} support archive.
 *
 * <p>Every file goes in through {@link #add}, which redacts before keeping it and tallies what
 * it removed. That single funnel is the design: a collector added later cannot leak a
 * credential by forgetting to scrub, because there is no other way into the archive, nor into
 * what {@code isx doctor --explain} sends to the AI Help account.
 *
 * <p>The config is redacted structurally by {@link SecretRedactor#redactConfig} -- the
 * secret locations come from the tool declarations, so a new credential-bearing tool is
 * covered the day it is declared. The values that redaction removed are then scrubbed out
 * of every other file, which is how a token that leaked into a log line gets caught.
 */
final class SupportBundle {

    /** Where a user should send the archive, printed after it is written. */
    static final String ISSUE_URL = "https://github.com/Sanne/incus-spawn/issues";

    private static final int LOG_TAIL_LINES = 1000;

    record Result(Path archive, List<String> redactedPaths, int scrubCount, int fileCount) {}

    private final Map<String, String> secretValues;
    private final Map<String, Integer> scrubHits = new LinkedHashMap<>();
    private final Map<String, String> files = new LinkedHashMap<>();
    private List<String> redactedPaths = List.of();

    SupportBundle(Map<String, String> secretValues) {
        this.secretValues = secretValues;
    }

    /**
     * Collect diagnostics, every file scrubbed by {@link #add}, and hold them in memory: the
     * archive and {@code isx doctor --explain} both read {@link #files}, so the model is sent
     * exactly what an issue attachment would carry.
     *
     * @param config the loaded config, redacted structurally into the bundle
     * @param findingsText the rendered doctor findings
     * @param findingsJson the same findings, machine-readable
     */
    static SupportBundle collect(SpawnConfig config, String findingsText, String findingsJson) {
        // The CLI already holds a loaded tool set; going through it avoids a second
        // scan of every tool directory just to learn where secrets live.
        var redaction = SecretRedactor.redactConfig(config,
                SecretRegistry.locations(RuntimeServices.toolDefLoader().allToolSetups()));
        var bundle = new SupportBundle(redaction.secretValues());
        bundle.redactedPaths = redaction.redactedPaths();

        bundle.add("findings.txt", findingsText);
        bundle.add("findings.json", findingsJson);
        bundle.add("versions.txt", collectVersions());
        bundle.add("proxy.log", logTail(Environment.proxyLogFile()));
        bundle.add("client.log", logTail(Environment.clientLogFile()));
        if (Platform.isMacOS()) {
            bundle.add("vm.log", logTail(Environment.vmLogFile()));
            bundle.add("vfkit.log", logTail(Environment.vfkitLogFile()));
            bundle.add("proxy-service.log", logTail(Environment.proxyServiceLogFile()));
        }
        bundle.add("proxy-status.txt", collectProxyStatus());
        bundle.add("instances.json", collectInstances());
        bundle.add("service-status.txt", collectServiceStatus());
        // The config is already redacted structurally; it still goes through the funnel
        // so its own markers are counted consistently with everything else.
        bundle.add("config-sanitized.yaml", redaction.yaml());
        bundle.add("mcp.yaml", collectMcpPolicy());

        bundle.add("REDACTIONS.txt", bundle.redactionManifest(redaction.redactedPaths()));
        bundle.add(README, readme());
        return bundle;
    }

    /** The name of the archive's guide for a person, which {@code --explain} leaves out. */
    static final String README = "README.txt";

    /** Write the files into a tar.gz and return what was written and what was withheld. */
    Result writeArchive() throws Exception {
        var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var dir = Files.createTempDirectory("isx-doctor-");
        try {
            for (var file : files.entrySet()) {
                Files.writeString(dir.resolve(file.getKey()), file.getValue());
            }
            var archive = Environment.vmStateDir().resolve("isx-doctor-" + timestamp + ".tar.gz");
            Files.createDirectories(archive.getParent());
            archiveDirectory(dir, archive);
            return new Result(archive, redactedPaths, scrubCount(), files.size());
        } finally {
            FileTrees.deleteQuietly(dir);
        }
    }

    /** The only way into the bundle: scrub, tally, keep. */
    void add(String name, String content) {
        var scrubbed = SecretRedactor.scrubText(content, secretValues);
        scrubbed.hits().forEach((label, count) -> scrubHits.merge(label, count, Integer::sum));
        files.put(name, scrubbed.text());
    }

    /** Every file as {@link #add} scrubbed it, by name, in the order they were added. */
    Map<String, String> files() {
        return Collections.unmodifiableMap(files);
    }

    Map<String, Integer> scrubHits() {
        return Map.copyOf(scrubHits);
    }

    private int scrubCount() {
        return scrubHits.values().stream().mapToInt(Integer::intValue).sum();
    }

    String redactionManifest(List<String> redactedPaths) {
        var sb = new StringBuilder();
        sb.append("""
                Redactions applied to this bundle
                ================================

                Credentials are removed two ways. config-sanitized.yaml is redacted
                structurally: the config is serialized and the values at known secret
                locations are replaced before anything is written, so it does not depend on a
                secret looking the way we expected. Every other file is scrubbed for the
                exact values that redaction removed, plus well-known credential shapes.

                A removed value is replaced by a marker naming the key, for example
                <isx:redacted:github.token>. An empty value in config-sanitized.yaml means
                the key was genuinely unset, not that it was redacted.

                """);

        sb.append("Config keys redacted (").append(redactedPaths.size()).append("):\n");
        if (redactedPaths.isEmpty()) {
            sb.append("  (none were configured)\n");
        } else {
            redactedPaths.forEach(path -> sb.append("  ").append(path).append('\n'));
        }

        sb.append("\nValues scrubbed from the other files:\n");
        if (scrubHits.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            scrubHits.forEach((label, count) -> sb.append("  ").append(label)
                    .append(": ").append(count).append('\n'));
        }

        sb.append("""

                Scrubbing the logs is a second line of defence, not the mechanism, and it can
                only catch what it recognises. Please skim the archive before sharing it.
                """);
        return sb.toString();
    }

    private static String readme() {
        return """
                isx doctor support bundle
                =========================

                Generated by 'isx doctor --bundle'. Contents:

                  findings.txt          doctor findings, as printed in the terminal
                  findings.json         the same findings, machine-readable
                  versions.txt          isx, Incus, OS and Java versions
                  proxy.log             tail of the MITM proxy log
                  client.log            tail of the isx client log
                  vm.log                tail of the appliance VM console (macOS only)
                  proxy-service.log     tail of the proxy service log (macOS only)
                  proxy-status.txt      proxy health, CA fingerprint, DNS state
                  instances.json        instance list and configuration
                  service-status.txt    platform and proxy service state
                  config-sanitized.yaml config.yaml with every credential redacted
                  mcp.yaml              the isx mcp policy as isx reads it, or why it cannot
                  REDACTIONS.txt        what was removed, and how

                Credentials have been removed; REDACTIONS.txt says what was taken out and
                how. Host paths, instance names and log lines remain, and may name your
                projects -- so skim this before attaching it to an issue at
                %s.
                """.formatted(ISSUE_URL);
    }

    // ---- Collectors ----

    /** The policy as parsed, so only the keys isx knows reach the bundle; it holds no credentials. */
    static String collectMcpPolicy() {
        try {
            var policy = McpConfig.load();
            var legacy = McpConfig.legacySectionNotice(SpawnConfig.load());
            return (legacy == null ? "" : "# " + legacy + "\n") + (Files.notExists(McpConfig.file())
                    ? "# no " + McpConfig.FILE_NAME + ": isx mcp approves no templates\n"
                    : Jjq.toYaml(policy));
        } catch (McpConfig.Invalid e) {
            return "# " + e.getMessage() + "\n";
        } catch (RuntimeException e) {
            // One optional file must not cost the user the whole bundle.
            return "# could not read " + McpConfig.FILE_NAME + ": " + e.getMessage() + "\n";
        }
    }

    private static String collectVersions() {
        var info = BuildInfo.instance();
        var sb = new StringBuilder();
        sb.append("isx version: ").append(info.displayVersion()).append("\n");
        sb.append("isx git SHA: ").append(info.gitSha()).append("\n");
        sb.append("isx runtime: ").append(info.runtime()).append("\n");
        sb.append("Incus server: ").append(info.incusServer()).append("\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append(" ")
                .append(System.getProperty("os.version")).append("\n");
        sb.append("Arch: ").append(System.getProperty("os.arch")).append("\n");
        sb.append("Java: ").append(System.getProperty("java.version", "n/a")).append("\n");
        return sb.toString();
    }

    private static String collectProxyStatus() {
        var sb = new StringBuilder();
        try {
            var incus = RuntimeServices.incus();
            var status = ProxyHealthCheck.check(incus);
            sb.append("Status: ").append(status.name()).append("\n");
            var info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
            if (info != null) {
                sb.append("Version: ").append(info.displayVersion()).append("\n");
                sb.append("Git SHA: ").append(info.gitSha()).append("\n");
                sb.append("Runtime: ").append(info.runtime()).append("\n");
                sb.append("CA fingerprint: ").append(info.caFingerprint()).append("\n");
                sb.append("DNS configured: ").append(info.dnsConfigured()).append("\n");
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    private static String collectInstances() {
        try {
            return RuntimeServices.incus().listJsonConfig().toJsonString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String collectServiceStatus() {
        var sb = new StringBuilder();
        sb.append("Platform: ").append(Platform.isMacOS() ? "macOS" : "Linux").append("\n");
        sb.append("Service installed: ").append(ProxyService.isInstalled()).append("\n");
        sb.append("Service active: ").append(ProxyService.isActive()).append("\n");
        return sb.toString();
    }

    /**
     * Last {@value #LOG_TAIL_LINES} lines of a log. {@code proxy.log} is never rotated, so on
     * a long-lived install it is an unbounded append log -- reading it whole to keep the last
     * thousand lines costs hundreds of milliseconds per file and scales with uptime, exactly
     * for the user most likely to be filing a bundle. {@code tail} seeks instead, and the
     * archive already depends on {@code tar}; the in-process read stays as the fallback.
     */
    private static String logTail(Path src) {
        try {
            if (!Files.exists(src)) {
                return "(file not found: " + src + ")\n";
            }
            var tailed = runTail(src);
            return tailed != null ? tailed : readTail(src);
        } catch (Exception e) {
            return "(could not read: " + e.getMessage() + ")\n";
        }
    }

    /** Null when {@code tail} is unavailable or fails, so the caller can fall back. */
    private static String runTail(Path src) {
        try {
            var pb = new ProcessBuilder("tail", "-n", String.valueOf(LOG_TAIL_LINES), src.toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readTail(Path src) throws IOException {
        var tail = new ArrayDeque<String>(LOG_TAIL_LINES);
        try (var reader = Files.newBufferedReader(src)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == LOG_TAIL_LINES) tail.removeFirst();
                tail.addLast(line);
            }
        }
        return String.join("\n", tail) + "\n";
    }

    // ---- Archive ----

    private static void archiveDirectory(Path dir, Path archive) throws Exception {
        var pb = new ProcessBuilder("tar", "czf", archive.toString(), "-C", dir.toString(), ".");
        pb.redirectErrorStream(true);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("tar failed: " + output.strip());
        }
    }
}
