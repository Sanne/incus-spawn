package dev.incusspawn;

import dev.incusspawn.util.BuildOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Build-time platform constants.
 * <p>
 * Separated from {@link Environment} (which is {@code --initialize-at-run-time} for
 * {@code user.home}) so GraalVM evaluates these at native-image build time, constant-folds
 * the results, and eliminates dead platform branches across all 50+ call sites.
 */
public final class Platform {

    private Platform() {}

    // One line: scripts/macos-role-build.sh replaces it whole
    private static final String OS_NAME = osName(System.getProperty("os.name"), Path.of("/proc/sys/kernel/ostype"), Path.of("/System/Library/CoreServices/SystemVersion.plist"));

    /**
     * The OS isx runs on, lower case: {@code os.name}, unless it claims macOS. A JVM takes
     * {@code os.name} from its command line ({@code -Dos.name}, {@code JAVA_TOOL_OPTIONS}), and
     * that must not put a released isx in macOS's role elsewhere, where the proxy identifies a
     * caller by its credential proof without its address (#1800). So the claim stands only on a
     * host with macOS's own {@code SystemVersion.plist}: a Linux kernel makes it Linux, and with
     * neither isx refuses to start, since half the code would run as Linux and half as nothing.
     * A native image reads none of this when it runs: this class is initialized when the image
     * is built, and {@code PlatformRoleFeature} fails a build whose role is not its target's.
     */
    static String osName(String property, Path linuxOsType, Path macOsVersion) {
        var named = property.toLowerCase(Locale.ROOT);
        // Only a claim of macOS is worth checking, so Linux and macOS starts read little or nothing
        if (!named.contains("mac")) return named;
        try {
            if (Files.readString(linuxOsType).strip().equals("Linux")) return "linux";
        } catch (IOException | RuntimeException notLinux) {
            // No such file is every other OS.
        }
        if (Files.isRegularFile(macOsVersion)) return named;
        throw new IllegalStateException("os.name says '" + property + "', but this host is neither macOS ("
                + macOsVersion + " is missing) nor Linux: isx refuses to guess its platform role");
    }

    public static boolean isMacOS() {
        return OS_NAME.contains("mac");
    }

    public static boolean isLinux() {
        return OS_NAME.contains("linux");
    }

    /**
     * The computer isx is installed on, as text a person reads: "this Mac", else "this computer",
     * never "host" (#2528).
     */
    public static String thisComputer() {
        return isMacOS() ? "this Mac" : "this computer";
    }

    /**
     * Best-effort desktop notification. Fire-and-forget: failures are silently ignored.
     * Uses {@code osascript} on macOS, {@code notify-send} on Linux.
     */
    public static void sendNotification(String title, String message) {
        try {
            ProcessBuilder pb;
            if (isMacOS()) {
                var script = "display notification " + osascriptQuote(message)
                        + " with title " + osascriptQuote(title)
                        + " sound name \"Ping\"";
                pb = new ProcessBuilder("osascript", "-e", script);
            } else if (isLinux()) {
                pb = new ProcessBuilder("notify-send", "--app-name=isx", "--urgency=critical", title, message);
            } else {
                return;
            }
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (IOException e) {
            BuildOutput.warn("Desktop notification failed: " + e.getMessage());
        }
    }

    private static String osascriptQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Best-effort open of a URL in the host's default browser via the platform opener
     * ({@code open} on macOS, {@code xdg-open} on Linux). A missing opener yields false.
     *
     * <p>Both openers are fire-and-forget launchers: they normally hand the URL to the browser
     * and exit promptly. If the opener exits within a short window we trust its exit code. But
     * some {@code xdg-open} setups block until the spawned handler exits — so once it is still
     * running past that window we assume the launch succeeded and leave it alone, rather than
     * force-killing a browser that did open and falsely reporting failure.
     */
    public static boolean openUrl(String url) {
        String opener = isMacOS() ? "open" : isLinux() ? "xdg-open" : null;
        if (opener == null) {
            return false;
        }
        try {
            var pb = new ProcessBuilder(opener, url);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var p = pb.start();
            if (p.waitFor(3, TimeUnit.SECONDS)) {
                return p.exitValue() == 0;
            }
            // Still running: the handler launched but the opener is blocking on it. Treat as success.
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
