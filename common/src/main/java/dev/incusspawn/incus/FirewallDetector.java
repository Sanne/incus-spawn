package dev.incusspawn.incus;

import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.util.function.BiConsumer;

public final class FirewallDetector {

    private FirewallDetector() {}

    public sealed interface DetectionResult {
        record UseFirewalld(boolean needsStart) implements DetectionResult {}
        record UseUfw() implements DetectionResult {}
        record NeitherInstalled() implements DetectionResult {}
    }

    public static DetectionResult detect() {
        return decide(
                FirewalldCheck.isInstalled(), FirewalldCheck.isActive(),
                UfwCheck.isActive());
    }

    public static DetectionResult decide(boolean fwdInstalled, boolean fwdActive,
                                         boolean ufwActive) {
        if (fwdActive) return new DetectionResult.UseFirewalld(false);
        if (ufwActive) return new DetectionResult.UseUfw();
        if (fwdInstalled) return new DetectionResult.UseFirewalld(true);
        return new DetectionResult.NeitherInstalled();
    }

    /** A firewall that is installed but not running: the banner's title and why it matters. */
    record Stopped(String title, String diagnostic) {}

    /** What the check asks the host. A probe that could not run throws rather than answering. */
    interface Probes {
        boolean firewalldActive() throws IOException, InterruptedException;
        boolean ufwActive() throws IOException;
        boolean firewalldInstalled();
        boolean ufwInstalled();
    }

    private static final Probes HOST = new Probes() {
        @Override public boolean firewalldActive() throws IOException, InterruptedException {
            return FirewalldCheck.probeActive();
        }
        @Override public boolean ufwActive() throws IOException { return UfwCheck.probeActive(); }
        @Override public boolean firewalldInstalled() { return FirewalldCheck.isInstalled(); }
        @Override public boolean ufwInstalled() { return UfwCheck.isInstalled(); }
    };

    /** The firewall that is installed but not running, or null when there is nothing to warn about. */
    static Stopped findStopped(Probes probes) throws IOException, InterruptedException {
        if (probes.firewalldActive() || probes.ufwActive()) return null;
        if (probes.firewalldInstalled()) return FirewalldCheck.STOPPED;
        if (probes.ufwInstalled()) return UfwCheck.STOPPED;
        return null;
    }

    public static String detectDiagnostic() {
        try {
            var stopped = findStopped(HOST);
            return stopped == null ? null : stopped.diagnostic();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Warns when a firewall is installed but not running. It runs on every branch and start and
     * costs a {@code systemctl is-active firewalld}, so a check that found nothing to warn about is
     * not repeated for the life of the process (#870); a firewall that stops later goes unreported
     * until the next process. One that warned runs again, so a user who starts the firewall from a
     * long-running TUI stops being told it is stopped, and so does one whose probe could not run.
     */
    public static boolean warnIfNotRunning() {
        return warnIfNotRunning(BuildOutput::warnBanner);
    }

    /**
     * {@link #warnIfNotRunning()} with the banner's title and diagnostic going to {@code banner}
     * instead of the terminal: the TUI's, which cannot print while it draws, puts them in its
     * warning log. It shares the process's memory of a check that found nothing.
     */
    public static boolean warnIfNotRunning(BiConsumer<String, String> banner) {
        return BRANCH_CHECK.warnIfNotRunning(banner);
    }

    private static final Check BRANCH_CHECK = new Check(HOST);

    /** Remembers a check that found nothing to warn about; any other outcome runs again next time. */
    static final class Check {
        private final Probes probes;
        private volatile boolean quiet;

        Check(Probes probes) {
            this.probes = probes;
        }

        boolean warnIfNotRunning(BiConsumer<String, String> banner) {
            if (quiet) return false;
            Stopped stopped;
            try {
                stopped = findStopped(probes);
            } catch (IOException e) {
                return false; // no answer, so nothing to remember
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (stopped == null) {
                quiet = true;
                return false;
            }
            banner.accept(stopped.title(), stopped.diagnostic());
            return true;
        }
    }
}
