package dev.incusspawn.incus;

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

    public static String detectDiagnostic() {
        if (FirewalldCheck.isActive() || UfwCheck.isActive()) return null;
        if (FirewalldCheck.isInstalled()) return FirewalldCheck.detectDiagnostic();
        if (UfwCheck.isInstalled()) return UfwCheck.detectDiagnostic();
        return null;
    }

    public static boolean warnIfNotRunning() {
        if (FirewalldCheck.isActive() || UfwCheck.isActive()) return false;
        if (FirewalldCheck.isInstalled()) return FirewalldCheck.warnIfNotRunning();
        if (UfwCheck.isInstalled()) return UfwCheck.warnIfNotRunning();
        return false;
    }
}
