package dev.incusspawn.tui;

import org.aesh.terminal.detect.ProbeDeadline;
import org.aesh.terminal.detect.ProbePermissions;
import org.aesh.terminal.detect.TerminalCapabilities;
import org.aesh.terminal.detect.TerminalTheme;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Reads what the theme is made from: dark or light, the terminal's background and its colour depth. */
public final class TerminalThemeDetector {

    private TerminalThemeDetector() {
    }

    public static TuiTheme detect() {
        TerminalCapabilities caps = null;
        try {
            caps = TerminalCapabilities.detect();
        } catch (Exception ignored) {
            // No terminal to ask: the defaults below
        }
        return TuiTheme.of(isDark(caps), background(caps), depth(caps, System.getenv(ColorDepth.OVERRIDE)));
    }

    /** The deadline given to aesh's probe. A terminal that answers does so in a few milliseconds. */
    static final long PROBE_MILLIS = 100;

    /** The variables that, with {@code TERM} and whether the session is over SSH, name a kind of terminal. */
    private static final String[] KIND = {"TERM_PROGRAM", "VTE_VERSION", "TERMINAL_EMULATOR"};

    /** How long a kind of terminal that did not answer is not asked again. */
    static final Duration SILENCE_REMEMBERED = Duration.ofDays(1);

    /**
     * Whether the terminal's background is dark, asked of the terminal itself (OSC 11) for a
     * command's report: null when it does not say. Only the terminal is asked, never an IDE's
     * files or a subprocess, which the TUI's fuller detection may use.
     *
     * <p>A terminal that answers costs under 10 ms. One that does not costs about half a second
     * whatever the deadline, since aesh waits that long for a reply, so its silence is remembered
     * for {@link #SILENCE_REMEMBERED} under the cache directory and it is not asked meanwhile.
     */
    public static Boolean probeDark() {
        return probeDark(System::getenv, Environment.cacheDir().resolve("terminal-theme"), Instant.now(), () -> {
            var theme = TerminalCapabilities.detectBounded(ProbeDeadline.after(PROBE_MILLIS, TimeUnit.MILLISECONDS),
                    ProbePermissions.of(true, false, false)).theme();
            return theme == TerminalTheme.DARK ? Boolean.TRUE : theme == TerminalTheme.LIGHT ? Boolean.FALSE : null;
        });
    }

    static Boolean probeDark(UnaryOperator<String> env, Path dir, Instant now, Supplier<Boolean> ask) {
        var term = env.apply("TERM");
        // The Linux console has no colour to report, and a dumb terminal no escapes at all
        if (term == null || term.equals("linux") || term.equals("dumb")) return null;
        // What tells one kind of terminal from another, so a silent one does not speak for the rest
        var kind = new StringBuilder(term);
        for (var name : KIND) kind.append('-').append(Objects.toString(env.apply(name), ""));
        kind.append(env.apply("SSH_CONNECTION") == null ? "" : "-ssh");
        var silent = dir.resolve("unanswered-" + fileName(kind.toString()));
        try {
            if (Files.getLastModifiedTime(silent).toInstant().isAfter(now.minus(SILENCE_REMEMBERED))) return null;
        } catch (IOException notRemembered) {
            // No such file: this kind of terminal has not been silent lately
        }
        Boolean dark = null;
        try {
            dark = ask.get();
        } catch (RuntimeException e) {
            // A terminal that cannot be asked is one that did not say
        }
        try {
            if (dark == null) {
                Files.createDirectories(dir);
                Files.write(silent, new byte[0]);
                Files.setLastModifiedTime(silent, FileTime.from(now));
            } else {
                Files.deleteIfExists(silent);
            }
        } catch (IOException e) {
            // Not remembered: the next command asks again
        }
        return dark;
    }

    /** {@code key} as a file name: letters, digits, dots and dashes, anything else an underscore. */
    private static String fileName(String key) {
        var name = new StringBuilder(key.length());
        key.chars().limit(80).forEach(c -> name.append(c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '-') ? (char) c : '_'));
        return name.toString();
    }

    static boolean isDark() {
        try {
            return isDark(TerminalCapabilities.detect());
        } catch (Exception ignored) {
            return isDark(null);
        }
    }

    private static boolean isDark(TerminalCapabilities caps) {
        var isxTheme = System.getenv("ISX_THEME");
        if (isxTheme != null && !isxTheme.isBlank()) {
            return !"light".equalsIgnoreCase(isxTheme.strip());
        }
        try {
            var theme = caps == null ? null : caps.theme();
            if (theme == TerminalTheme.LIGHT) return false;
            if (theme == TerminalTheme.DARK) return true;
        } catch (Exception ignored) {
        }
        return true;
    }

    /** The terminal's own background, or null when it did not answer. */
    private static int[] background(TerminalCapabilities caps) {
        try {
            return caps == null ? null : caps.backgroundRGB();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * The depth to draw at: what {@code ISX_COLORS} names, else truecolor only where the terminal
     * is known to draw it, else the 256-colour palette. SSH does not pass {@code COLORTERM} on, so
     * most remote sessions get the palette, which is the safe answer (#1681).
     */
    static ColorDepth depth(TerminalCapabilities caps, String override) {
        var named = ColorDepth.named(override);
        if (named != null) return named;
        try {
            if (caps != null && caps.supportsTrueColor()) return ColorDepth.TRUECOLOR;
            if (caps != null && !caps.supports256Colors()) return ColorDepth.ANSI;
        } catch (Exception ignored) {
        }
        return ColorDepth.INDEXED;
    }
}
