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
     * ({@code open} on macOS, {@code xdg-open} on Linux). Returns true only if the opener
     * exited 0 within a short timeout; a missing opener or non-zero exit yields false.
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
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
