package dev.incusspawn;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The formula rewrite of release.yml's tap step ({@code .github/scripts/update-homebrew-formula.sh}),
 * on copies of both of the tap's formulas as they were before the launcher (#2031): the release
 * that first ships {@code isx-vm} must add it as a resource and drop {@code depends_on "vfkit"},
 * and every later one must move it with the others.
 */
class HomebrewFormulaUpdateTest {

    private static final Path SCRIPT = Path.of("../.github/scripts/update-homebrew-formula.sh");
    private static final Path FIXTURES = Path.of("src/test/resources/homebrew");

    @TempDir
    Path dir;

    private Path formula(String name) throws Exception {
        var copy = dir.resolve(name);
        Files.copy(FIXTURES.resolve(name), copy);
        return copy;
    }

    private static String sum(char c) {
        return String.valueOf(c).repeat(64);
    }

    private static void update(Path formula, String version, char first) throws Exception {
        var process = new ProcessBuilder("bash", SCRIPT.toString(), formula.toString(), version,
                sum(first), sum((char) (first + 1)), sum((char) (first + 2)), sum((char) (first + 3)), sum((char) (first + 4)))
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "the script did not finish");
        var out = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), out);
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    @ParameterizedTest
    @ValueSource(strings = {"incus-spawn.rb", "incus-spawn-dev.rb"})
    void theFirstReleaseWithTheLauncherAddsItAndDropsVfkit(String name) throws Exception {
        assertTrue(Files.readString(FIXTURES.resolve(name)).contains("depends_on \"vfkit\""), "the fixture predates the launcher");
        var file = formula(name);

        update(file, "0.6.0", 'a');

        var text = Files.readString(file);
        assertFalse(text.contains("vfkit"), text);
        assertTrue(text.contains("""
                  resource "isx-vm" do
                    url "https://github.com/Sanne/incus-spawn/releases/download/v0.6.0/isx-vm-macos-aarch64"
                    sha256 "%s"
                  end
                """.formatted(sum('c'))), text);
        assertTrue(text.contains("""
                    resource("isx-vm").stage do
                      bin.install "isx-vm-macos-aarch64" => "isx-vm"
                    end
                """), text);
        assertTrue(text.contains("version \"0.6.0\""), text);
        assertTrue(text.contains("sha256 \"" + sum('a') + "\""), "the CLI's sum: " + text);
        assertTrue(text.contains("sha256 \"" + sum('b') + "\""), "the proxy's sum: " + text);
        assertTrue(text.contains("depends_on macos: :sequoia"), text);
        assertEquals(0, count(text, "/v0.5."), "an asset left at the old version: " + text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"incus-spawn.rb", "incus-spawn-dev.rb"})
    void aLaterReleaseMovesTheLauncherWithTheOthers(String name) throws Exception {
        var file = formula(name);
        update(file, "0.6.0", 'a');
        update(file, "0.6.1", 'k');

        var text = Files.readString(file);
        assertEquals(1, count(text, "resource \"isx-vm\""), text);
        assertEquals(1, count(text, "resource(\"isx-vm\").stage"), text);
        assertTrue(text.contains("v0.6.1/isx-vm-macos-aarch64\"\n    sha256 \"" + sum('m') + "\""), text);
        assertEquals(0, count(text, "v0.6.0"), text);
        assertEquals(0, count(text, sum('c')), text);
    }

    /**
     * The formula downloads isx-vm from the release, so the release must upload it, and it is
     * in the release's SHA256SUMS: one list feeds both (#2080).
     */
    @org.junit.jupiter.api.Test
    void theReleaseUploadsTheLauncherTheFormulaInstalls() throws Exception {
        var release = Files.readString(Path.of("../.github/workflows/release.yml"));
        var start = release.indexOf("- name: Create GitHub Release");
        var upload = release.substring(start, release.indexOf("gh release create", start));
        assertTrue(upload.contains("artifacts/native-macos-aarch64/isx-vm-macos-aarch64 \\"), upload);
        assertTrue(release.contains("\"$(sha artifacts/native-macos-aarch64/isx-vm-macos-aarch64)\""),
                "the tap step must give the formula the launcher's sum");
        assertTrue(release.contains("bin=$(launcher/build-isx-vm.sh)\n          cp \"$bin\" dist/isx-vm-macos-${{ matrix.arch }}"),
                "the macOS build must put the signed launcher in its artifact");
    }
}
