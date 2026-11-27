package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The real TUI on a real terminal over a made-up listing, with no Incus and no isx setup: for
 * looking at a change to how the TUI looks or moves. {@code scripts/tui-demo.sh} runs it.
 *
 * <p>Everything it shows comes from a {@link FakeIncusDaemon} in a temporary home, so a key that
 * acts (stop, destroy, rename) changes only that. A key that would leave the TUI for a shell, a
 * build or an editor returns to it at once. {@code empty} as its argument starts with no instance.
 */
public final class TuiDemo {
    private TuiDemo() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static String ago(long minutes) {
        return LocalDateTime.now().minusMinutes(minutes).format(STAMP);
    }

    private static ImageDef def(String name, String parent, String description) {
        var def = new ImageDef();
        def.setName(name);
        def.setImage(parent == null ? "fedora-44" : null);
        def.setParent(parent);
        def.setDescription(description);
        return def;
    }

    private static Map<String, String> template(String name, String parent, long minutesAgo) {
        var config = new HashMap<String, String>();
        config.put(Metadata.TYPE, Metadata.TYPE_BASE);
        config.put(Metadata.PROFILE, name);
        config.put(Metadata.CREATED, ago(minutesAgo));
        config.put(Metadata.BUILD_VERSION, VERSION);
        if (parent != null) config.put(Metadata.PARENT, parent);
        return config;
    }

    private static Map<String, String> branch(String parent, long minutesAgo, String purpose, String pendingOp) {
        var config = new HashMap<String, String>();
        config.put(Metadata.TYPE, "branch");
        config.put(Metadata.PROFILE, parent);
        config.put(Metadata.PARENT, parent);
        config.put(Metadata.CREATED, ago(minutesAgo));
        config.put(Metadata.NETWORK_MODE, "proxy");
        if (purpose != null) config.put(Metadata.PURPOSE, purpose);
        if (pendingOp != null) config.put(Metadata.PENDING_OP, pendingOp);
        return config;
    }

    private static final String VERSION = "9.9.9";

    /** What GitHub says became of the demo's pull requests. */
    private static final Map<String, dev.incusspawn.command.PullRequestStates.State> STATES = new HashMap<>();

    private static String at(long minutesAgo) {
        return java.time.Instant.now().minus(java.time.Duration.ofMinutes(minutesAgo)).toString();
    }

    /** A Claude Code session closing with {@code closing}, as the guest's read prints it. */
    private static String session(long minutesAgo, String closing) {
        var json = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + closing + "\"}]}}";
        var seconds = java.time.Instant.now().minus(java.time.Duration.ofMinutes(minutesAgo)).getEpochSecond();
        return "session " + seconds + " YQ==\nclose " + java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\n";
    }

    /**
     * What an instance did, for the Work tab: its last start, a model call {@code idle} minutes ago
     * spending {@code tokens} (none when negative), its clones, its pull requests and pushed
     * branches in the event log, and its Claude Code sessions, the newest first.
     */
    private static void work(FakeIncusDaemon daemon, StringBuilder log, String name, long started, long idle, long tokens,
                             String repos, List<Object[]> pulls, List<String> branches, String... closings) {
        var created = at(started + 60);
        daemon.createdAt(name, created).lastUsedAt(name, at(started));
        if (idle >= 0) {
            log.append("{\"t\":\"").append(at(idle)).append("\",\"kind\":\"model\",\"instance\":\"").append(name)
                    .append("\",\"incarnation\":\"").append(created).append("\",\"input_tokens\":").append(tokens)
                    .append(",\"output_tokens\":0}\n");
        }
        pulls(log, name, idle >= 0 ? idle : started, pulls, created);
        for (var branch : branches) {
            log.append("{\"t\":\"").append(at(Math.max(0, idle))).append("\",\"kind\":\"github\",\"op\":\"push\",\"instance\":\"")
                    .append(name).append("\",\"incarnation\":\"").append(created).append("\",\"repo\":\"Sanne/incus-spawn\",")
                    .append("\"refs\":[\"refs/heads/").append(branch).append("\"]}\n");
        }
        var sessions = new StringBuilder("total " + closings.length + "\n");
        for (int i = 0; i < closings.length; i++) sessions.append(session(Math.max(0, idle) + i * 60L, closings[i]));
        var none = "\ntotal 0\nend\n";
        daemon.execOutputIn(name, dev.incusspawn.command.WorkState.HEADER, dev.incusspawn.command.WorkState.HEADER + "\n"
                + repos + "\nend\n"
                + "isx-sessions gemini-cli 1\nend\nisx-sessions antigravity 1\nend\nisx-sessions copilot 1\nend\n"
                + dev.incusspawn.command.ClaudeSessions.HEADER + "\n" + sessions + "end\n"
                + dev.incusspawn.command.CodexSessions.HEADER + none
                + dev.incusspawn.command.PiSessions.HEADER + none
                + dev.incusspawn.command.AntigravitySessions.HEADER + none
                + dev.incusspawn.command.GeminiSessions.HEADER + none
                + dev.incusspawn.command.CopilotSessions.HEADER + none
                + dev.incusspawn.command.BobSessions.HEADER + none
                + dev.incusspawn.command.OpencodeSessions.HEADER + none);
    }

    private static void pulls(StringBuilder log, String name, long minutesAgo, List<Object[]> pulls) {
        pulls(log, name, minutesAgo, pulls, "");
    }

    private static void pulls(StringBuilder log, String name, long minutesAgo, List<Object[]> pulls, String incarnation) {
        for (var pull : pulls) {
            STATES.put(pull[0] + "#" + pull[1], dev.incusspawn.command.PullRequestStates.State.valueOf(
                    ((String) pull[2]).toUpperCase(java.util.Locale.ROOT)));
            log.append("{\"t\":\"").append(at(minutesAgo)).append("\",\"kind\":\"github\",\"op\":\"pull_request\",\"instance\":\"")
                    .append(name).append("\",\"incarnation\":\"").append(incarnation).append("\",\"repo\":\"").append(pull[0])
                    .append("\",\"number\":").append(pull[1]).append("}\n");
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("isx-demo-home").toString());
        boolean empty = args.length > 0 && args[0].equals("empty");

        var defs = new LinkedHashMap<String, ImageDef>();
        defs.put("tpl-minimal", def("tpl-minimal", null, "Minimal Fedora"));
        defs.put("tpl-java", def("tpl-java", "tpl-minimal", "JDK 25, Maven and GraalVM"));
        defs.put("tpl-quarkus", def("tpl-quarkus", "tpl-java", "Quarkus checkout, primed"));
        defs.put("tpl-claude", def("tpl-claude", "tpl-java", "Claude Code with the isx tools"));
        defs.put("tpl-gui", def("tpl-gui", "tpl-minimal", "Desktop passthrough"));

        var daemon = new FakeIncusDaemon();
        daemon.container("tpl-minimal", template("tpl-minimal", null, 9 * 24 * 60 + 30));
        daemon.container("tpl-java", template("tpl-java", "tpl-minimal", 3 * 24 * 60 + 30));
        daemon.container("tpl-quarkus", template("tpl-quarkus", "tpl-java", 26 * 60 + 30));
        daemon.container("tpl-claude", template("tpl-claude", "tpl-java", 5 * 60 + 30));
        var log = new StringBuilder();
        if (!empty) {
            daemon.instance("quarkus-42871", "container", "Running", branch("tpl-quarkus", 95, "Fix the dev-mode reload race", null))
                    .instance("review-pr-2301", "container", "Running", branch("tpl-claude", 3 * 60 + 30, "Review of the proxy cache change", null))
                    .instance("bench-native", "container", "Running", branch("tpl-java", 40, null, Metadata.OP_RESTARTING))
                    .instance("docs-site", "container", "Stopped", branch("tpl-minimal", 2 * 24 * 60 + 30, null, null))
                    .instance("gui-test", "virtual-machine", "Stopped", branch("tpl-minimal", 6 * 24 * 60 + 30, "Wayland passthrough check", null))
                    .instance("scratch", "container", "Stopped", branch("tpl-java", 12 * 24 * 60 + 30, null, null))
                    .instance("mcp-isx-agent-2458-companion-y27vl", "container", "Running", branch("tpl-claude", 4 * 24 * 60, null, null))
                    .instance("isx-coordinator-1", "container", "Running", branch("tpl-claude", 8 * 24 * 60, null, null));
            work(daemon, log, "quarkus-42871", 95, 30, 4_200_000, "repo 3 1 0 quarkus", List.of(),
                    List.of("fix-reload-race"), "Fixed the race: the reloader now waits for the class loader to close. 3 changes are not committed yet.");
            work(daemon, log, "review-pr-2301", 210, 120, 48_100_000, "repo 0 0 0 incus-spawn\nrepo 0 0 0 incus-spawn-images",
                    List.of(new Object[] {"Sanne/incus-spawn", 2457, "merged"}, new Object[] {"Sanne/incus-spawn", 2471, "open"},
                            new Object[] {"Sanne/incus-spawn", 2480, "open"}, new Object[] {"Sanne/incus-spawn", 2490, "closed"}),
                    List.of(), "Both review comments are addressed: the column widths now come from ColumnFit, and the header keeps the gauge at 80 columns.",
                    "PR #2471 holds the colour-depth part and waits for CI.");
            work(daemon, log, "bench-native", 40, -1, 0, "repo 0 0 2 bench", List.of(), List.of());
            work(daemon, log, "mcp-isx-agent-2458-companion-y27vl", 4 * 24 * 60, 2 * 24 * 60, 16_800_000, "repo 0 22 0 incus-spawn",
                    List.of(), List.of("chatgpt-workspace-backfill"), "Mostly yes: main is merged in and the companion change builds, but 22 commits are still local.");
            work(daemon, log, "isx-coordinator-1", 8 * 24 * 60, 120, 1_300_000_000, "repo 1 0 0 isx-workflow",
                    List.of(new Object[] {"Sanne/isx-workflow", 132, "open"}, new Object[] {"Sanne/isx-workflow", 135, "open"},
                            new Object[] {"Sanne/incus-spawn", 2650, "merged"}),
                    List.of(), "Quiet for two hours: every delegated task ended, and the last two pull requests wait for your review.",
                    "Delegated the Linux part of #2650 and reviewed its pull request.", "Planned the work on #2650 with you.");
            pulls(log, "docs-site", 2 * 24 * 60 + 30, List.<Object[]>of(new Object[] {"Sanne/incus-spawn", 2301, "merged"}));
        }

        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }

            @Override
            void warnIfFirewallStopped(BiConsumer<String, String> banner) {
            }

            @Override
            boolean bridgeSubnetConflicts() {
                return false;
            }

            @Override
            String currentVersion() {
                return VERSION;
            }

            @Override
            String displayVersion() {
                return VERSION;
            }
        };
        var events = Files.createTempFile("isx-demo-events", ".jsonl");
        Files.writeString(events, log);
        tui.eventLogReader = () -> dev.incusspawn.proxy.EventLogReader.read(events);
        tui.overviewConfig = () -> {
            var config = new dev.incusspawn.config.SpawnConfig();
            config.getClaude().putAccount("work", dev.incusspawn.config.SpawnConfig.ClaudeAccount.ofApiKey("demo-key"));
            config.getClaude().setDefaultAccount("work");
            config.getGithub().setToken("demo-token");
            return config;
        };
        // GitHub answers what the demo says became of each pull request
        tui.overviewGitHub = (token, asked) -> {
            var answer = new HashMap<dev.incusspawn.proxy.EventLogReader.PullRequest, dev.incusspawn.command.PullRequestStates.State>();
            for (var pull : asked) {
                var state = STATES.get(pull.repo() + "#" + pull.number());
                if (state != null) answer.put(pull, state);
            }
            return answer;
        };
        // The model takes its time, so the preview says it is merging
        tui.overviewModel = (config, target, system, question) -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Headline: Coordinated the work on #2650 and reviewed it\n"
                    + "Done: The Linux part is merged and the pull requests for the workflow are open.\n"
                    + "State: Every delegated task ended; two pull requests wait for review.\n"
                    + "Pending: The macOS part needs the Mac Mini.";
        };
        tui.useDefinitions(defs, new ToolDefLoader(List.of()), List.of());
        while (true) {
            tui.startSession(daemon.client(), new BackgroundTaskManager(), new FlockInstanceLockManager(),
                    InstanceListing.collectEntries(daemon.client().listJson()));
            tui.poolUsage = new IncusClient.PoolUsage(63L << 30, 100L << 30);
            tui.drawUntilQuit(new LegacyKeyboardBackend());
            // A shell, a build or an editor would take the terminal: here it comes straight back
            if (tui.pendingAction() == Tui.PendingAction.NONE) break;
        }
    }
}
