package dev.incusspawn.incus;

import java.io.IOException;

public final class FirewalldCheck {

    private FirewalldCheck() {}

    public static boolean isInstalled() {
        try {
            var pb = new ProcessBuilder("which", "firewall-cmd");
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean isActive() {
        try {
            var pb = new ProcessBuilder("firewall-cmd", "--state");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            return process.waitFor() == 0 && "running".equals(output);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static String detectDiagnostic() {
        try {
            if (!isInstalled() || isActive()) return null;
            return "Possible cause: firewalld is installed but not running.\n"
                    + "Firewall rules (masquerading, FORWARD, PREROUTING redirect) are not\n"
                    + "loaded into the kernel, so containers cannot reach the internet.\n\n"
                    + "Fix:\n"
                    + "  sudo systemctl enable --now firewalld\n"
                    + "  isx init";
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean warnIfNotRunning() {
        try {
            var diagnostic = detectDiagnostic();
            if (diagnostic == null) return false;
            System.err.println("\033[33m" + "─".repeat(60) + "\033[0m");
            System.err.println("\033[1;33mfirewalld is not running:\033[0m");
            System.err.println(diagnostic);
            System.err.println("\033[33m" + "─".repeat(60) + "\033[0m");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
