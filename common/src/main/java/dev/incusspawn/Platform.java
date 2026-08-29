package dev.incusspawn;

import java.io.IOException;
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

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase(Locale.ROOT);

    public static boolean isMacOS() {
        return OS_NAME.contains("mac");
    }

    public static boolean isLinux() {
        return OS_NAME.contains("linux");
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
