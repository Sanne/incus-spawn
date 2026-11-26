package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.Container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * PROTOTYPE (design/macos-and-vms.md "GUI on macOS: the waypipe prototype"): what {@code isx gui}
 * runs to show a guest's Wayland windows on a host that shares no kernel with it, which is every Mac.
 *
 * <p>{@link GuiPassthrough} bind-mounts the host compositor's socket, which needs one kernel on both
 * sides. Here the Wayland protocol is carried as a byte stream instead: {@code waypipe ssh} runs a
 * {@code waypipe client} beside the host's compositor (Cocoa-Way on a Mac) and a
 * {@code waypipe server} in the guest, joined by a Unix socket that ssh forwards through the
 * instance's own Host block. The guest holds no host socket and no GPU.
 *
 * <p>This class is the part with no side effect: where the host's compositor is, and the text of
 * every script and command line. {@code GuiCommand} runs them.
 */
public final class GuiBridge {

    private GuiBridge() {}

    /** The guest directory that holds the displays, agentuser's alone; on {@code /run}, so gone at a reboot. */
    public static final String GUEST_DIR = "/run/isx-gui";

    /** The display {@code isx gui <name>} holds open, which the guest's login shells then use. */
    public static final String HELD_DISPLAY = GUEST_DIR + "/wayland-0";

    /** The login script that points the guest's shells at {@link #HELD_DISPLAY} while it exists. */
    static final String PROFILE = "/etc/profile.d/isx-gui.sh";

    /** The prepare script's exit code when the guest has no waypipe. */
    public static final int NO_WAYPIPE = 3;

    /** Toolkit backends, as {@link GuiPassthrough} sets them: without these most toolkits try X11 first. */
    private static final Map<String, String> TOOLKIT_ENV = toolkitEnv();

    private static Map<String, String> toolkitEnv() {
        var env = new LinkedHashMap<String, String>();
        env.put("GDK_BACKEND", "wayland");
        env.put("QT_QPA_PLATFORM", "wayland");
        env.put("SDL_VIDEODRIVER", "wayland");
        env.put("MOZ_ENABLE_WAYLAND", "1");
        env.put("ELECTRON_OZONE_PLATFORM_HINT", "wayland");
        env.put("NO_AT_BRIDGE", "1");
        return env;
    }

    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9-]+");

    /** The host compositor's socket: the two values a Wayland client is given. */
    public record HostDisplay(String runtimeDir, String name) {
        public String path() {
            return name.startsWith("/") ? name : runtimeDir + "/" + name;
        }
    }

    /**
     * The host compositor's socket, or null when none is found: the session's own
     * {@code WAYLAND_DISPLAY} first (a Linux desktop, or a Mac shell set up for Cocoa-Way), then
     * the first {@code wayland-*} socket in the directories Cocoa-Way's own wrapper searches.
     */
    public static HostDisplay hostDisplay(Map<String, String> env) {
        return hostDisplay(env, GuiBridge::isSocket, GuiBridge::names);
    }

    static HostDisplay hostDisplay(Map<String, String> env, Predicate<String> isSocket,
                                   Function<String, List<String>> names) {
        var xdg = blankToNull(env.get("XDG_RUNTIME_DIR"));
        var display = blankToNull(env.get("WAYLAND_DISPLAY"));
        if (display != null && (xdg != null || display.startsWith("/"))) {
            var found = new HostDisplay(xdg != null ? xdg : "/", display);
            if (isSocket.test(found.path())) return found;
        }
        var candidates = new ArrayList<String>();
        var cocoaWay = blankToNull(env.get("COCOA_WAY_RUNTIME_DIR"));
        if (cocoaWay != null) candidates.add(cocoaWay);
        if (xdg != null) candidates.add(xdg);
        var tmpdir = blankToNull(env.get("TMPDIR"));
        if (tmpdir != null) candidates.add(stripTrailingSlashes(tmpdir) + "/cocoa-way");
        candidates.add("/tmp/cocoa-way");
        for (var dir : candidates) {
            for (var name : names.apply(dir).stream().sorted().toList()) {
                if (name.startsWith("wayland-") && isSocket.test(dir + "/" + name)) return new HostDisplay(dir, name);
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String stripTrailingSlashes(String path) {
        var end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') end--;
        return path.substring(0, end);
    }

    private static boolean isSocket(String path) {
        try {
            // A socket is neither a file, a directory nor a link: Java names it "other"
            return Files.readAttributes(Path.of(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static List<String> names(String dir) {
        try (var entries = Files.list(Path.of(dir))) {
            return entries.map(entry -> entry.getFileName().toString()).toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** The guest display of one {@code isx gui <name> -- <command>} run, apart from every other run's. */
    public static String runDisplay(String runId) {
        if (!RUN_ID.matcher(runId).matches()) throw new IllegalArgumentException("not a run id: " + runId);
        return GUEST_DIR + "/wayland-" + runId;
    }

    /**
     * What root runs in the guest before a bridge: it exits {@link #NO_WAYPIPE} without waypipe,
     * makes {@link #GUEST_DIR} agentuser's alone, and writes {@link #PROFILE}.
     */
    public static String prepareScript() {
        return "command -v waypipe >/dev/null 2>&1 || exit " + NO_WAYPIPE + "\n"
                + "mkdir -p " + GUEST_DIR + " && chown agentuser: " + GUEST_DIR + " && chmod 700 " + GUEST_DIR + " || exit 1\n"
                + "cat > " + PROFILE + " <<'ISX_GUI_EOF'\n"
                + profileScript()
                + "ISX_GUI_EOF\n";
    }

    /**
     * {@link #PROFILE}: a login shell uses {@link #HELD_DISPLAY} while a bridge holds it open, and is
     * left alone otherwise, and whenever it already has a display (a run of its own, or
     * {@link GuiPassthrough}'s on Linux).
     */
    static String profileScript() {
        var script = new StringBuilder();
        script.append("# Written by 'isx gui' (prototype): use the display it holds open, while it does.\n");
        script.append("if [ -z \"${WAYLAND_DISPLAY:-}\" ] && [ -S ").append(HELD_DISPLAY).append(" ]; then\n");
        script.append("    export WAYLAND_DISPLAY=").append(HELD_DISPLAY).append('\n');
        script.append("    [ -n \"${XDG_RUNTIME_DIR:-}\" ] || export XDG_RUNTIME_DIR=").append(GUEST_DIR).append('\n');
        TOOLKIT_ENV.forEach((key, value) -> script.append("    export ").append(key).append('=').append(value).append('\n'));
        script.append("fi\n");
        return script.toString();
    }

    /**
     * The script the guest's {@code waypipe server} runs as agentuser, with {@code WAYLAND_DISPLAY}
     * already naming this run's display: {@code command} in {@code workdir}, or with no command a
     * wait that lasts as long as the display's socket does.
     */
    public static String guestScript(String workdir, List<String> command) {
        var script = new StringBuilder();
        script.append("[ -n \"${XDG_RUNTIME_DIR:-}\" ] || export XDG_RUNTIME_DIR=").append(GUEST_DIR).append('\n');
        TOOLKIT_ENV.forEach((key, value) -> script.append("export ").append(key).append('=').append(value).append('\n'));
        if (command.isEmpty()) {
            script.append("while [ -S \"$WAYLAND_DISPLAY\" ]; do sleep 2; done\n");
        } else {
            if (workdir != null && !workdir.isEmpty()) {
                script.append("cd -- ").append(Container.shellQuote(workdir)).append(" 2>/dev/null\n");
            }
            script.append("exec");
            command.forEach(word -> script.append(' ').append(Container.shellQuote(word)));
            script.append('\n');
        }
        return script.toString();
    }

    /**
     * The host command: {@code waypipe ssh} to the instance through isx's own ssh config, so it
     * works whether or not {@code ~/.ssh/config} includes it. {@code --no-gpu}: the guest has no
     * GPU to share buffers from. The guest script is one quoted word, since ssh joins its
     * arguments with spaces for the guest's shell to split again.
     */
    public static List<String> waypipeCommand(String waypipe, Path sshConfig, String instance, String guestDisplay,
                                              String guestScript) {
        return List.of(waypipe, "--no-gpu", "--display", guestDisplay,
                "ssh", "-F", sshConfig.toString(), "-o", "BatchMode=yes", instance,
                "bash", "-lc", Container.shellQuote(guestScript));
    }

    /**
     * What root runs in the guest when a bridge ends: it ends that display's {@code waypipe server}
     * and removes its socket, which ends a held display's wait. The {@code [-]} keeps the pattern
     * from matching the shell that runs this script.
     */
    public static String cleanupScript(String guestDisplay) {
        var quoted = Container.shellQuote(guestDisplay);
        return "pkill -f -- " + Container.shellQuote("[-]-display " + Pattern.quote(guestDisplay) + " server") + "\n"
                + "rm -f -- " + quoted + " " + quoted + ".lock\n"
                + "exit 0\n";
    }
}
