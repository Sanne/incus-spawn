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
        if (!empty) {
            daemon.instance("quarkus-42871", "container", "Running", branch("tpl-quarkus", 95, "Fix the dev-mode reload race", null))
                    .instance("review-pr-2301", "container", "Running", branch("tpl-claude", 3 * 60 + 30, "Review of the proxy cache change", null))
                    .instance("bench-native", "container", "Running", branch("tpl-java", 40, null, Metadata.OP_RESTARTING))
                    .instance("docs-site", "container", "Stopped", branch("tpl-minimal", 2 * 24 * 60 + 30, null, null))
                    .instance("gui-test", "virtual-machine", "Stopped", branch("tpl-minimal", 6 * 24 * 60 + 30, "Wayland passthrough check", null))
                    .instance("scratch", "container", "Stopped", branch("tpl-java", 12 * 24 * 60 + 30, null, null));
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
