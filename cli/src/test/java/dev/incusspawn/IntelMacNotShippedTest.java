package dev.incusspawn;

import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Intel Macs are not shipped since Quarkus 4 (#1693): it needs GraalVM 25.0.4, and Oracle GraalVM
 * has no macOS x64 build after 25.0.1 (design/native-image.md "Intel Macs are not shipped").
 * So no release build targets macOS x86_64, no release uploads such a binary, and the Homebrew tap
 * step refuses a formula that still names an Intel asset: rewritten, it would point an Intel Mac at
 * a missing binary beside the previous release's isx-proxy.
 */
class IntelMacNotShippedTest {

    private static final Path RELEASE = Path.of("../.github/workflows/release.yml");
    private static final Pattern INTEL_MAC_ASSET = Pattern.compile("(?:incus-spawn|isx-proxy)-macos-x86_64");

    @Test
    void noReleaseBuildTargetsAnIntelMac() throws IOException {
        var include = JqYaml.parse(Files.readString(RELEASE))
                .path("jobs").path("build").path("strategy").path("matrix").path("include").asList();
        assertFalse(include.isEmpty(), "release.yml has no build matrix");
        for (var entry : include) {
            var runner = entry.pathText("runner", "");
            assertFalse("macos".equals(entry.pathText("os", "")) && !"aarch64".equals(entry.pathText("arch", "")),
                    runner + ": no macOS build but Apple Silicon is shipped");
            assertFalse(runner.contains("intel"), runner + ": an Intel macOS runner builds a release binary");
        }
    }

    @Test
    void noReleaseUploadsOrPublishesAnIntelMacBinary() throws IOException {
        var release = Files.readString(RELEASE);
        assertFalse(INTEL_MAC_ASSET.matcher(release).find(), "release.yml still names an Intel macOS binary");
    }

    /** The guard the tap step runs before it rewrites a formula, taken from release.yml itself. */
    private static String tapGuard() throws IOException {
        for (var step : JqYaml.parse(Files.readString(RELEASE)).path("jobs").path("release").path("steps").asList()) {
            if (!"Update Homebrew tap".equals(step.pathText("name", ""))) continue;
            for (var line : step.pathText("run", "").split("\n")) {
                if (line.strip().startsWith("if grep -q 'macos-x86_64")) {
                    return line.strip() + "\n  echo refused; exit 1\nfi\necho accepted\n";
                }
            }
        }
        return fail("the tap step no longer refuses a formula that still serves Intel Macs");
    }

    private static String runGuard(Path dir, String formula) throws Exception {
        var file = dir.resolve("incus-spawn.rb");
        Files.writeString(file, formula);
        var script = "FORMULA=" + file + "\n" + tapGuard();
        var process = new ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the guard did not finish");
        return new String(process.getInputStream().readAllBytes()).strip();
    }

    @Test
    void theTapStepRefusesAFormulaThatStillServesIntelMacs(@TempDir Path dir) throws Exception {
        var intel = """
                  on_macos do
                    if Hardware::CPU.arm?
                      url "https://example/v1/incus-spawn-macos-aarch64"
                    else
                      url "https://example/v1/incus-spawn-macos-x86_64"
                    end
                  end
                  depends_on macos: :sequoia
                """;
        assertEquals("refused", runGuard(dir, intel), "a formula with an Intel binary must be refused");
        var intelBesideArm = """
                  depends_on arch: :arm64
                  resource "isx-proxy" do
                    on_intel do
                      url "https://example/v1/isx-proxy-macos-x86_64"
                    end
                  end
                """;
        assertEquals("refused", runGuard(dir, intelBesideArm),
                "a formula that still names an Intel asset must be refused, even beside depends_on arch: :arm64");
        var noArch = """
                  url "https://example/v1/incus-spawn-macos-aarch64"
                  depends_on macos: :sequoia
                """;
        assertEquals("refused", runGuard(dir, noArch), "a formula without depends_on arch: :arm64 must be refused");
        var appleSilicon = """
                  url "https://example/v1/incus-spawn-macos-aarch64"
                  depends_on arch: :arm64
                  depends_on macos: :sequoia
                """;
        assertEquals("accepted", runGuard(dir, appleSilicon), "an Apple Silicon only formula must be accepted");
    }
}
