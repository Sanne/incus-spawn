package dev.incusspawn;

import java.util.Locale;

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
}
