package dev.incusspawn;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitHub retires macOS runner images, and a job on a removed label waits for a runner that never
 * comes. The release matrix only runs on {@code v*} tags, so a retired label there is found by the
 * next release, which then ships no macOS binaries (#1000). A pinned version, never
 * {@code macos-latest}, keeps the released binary's minimum macOS from moving silently, and the
 * Homebrew formula declares that minimum, so the two have to move together.
 */
class WorkflowRunnerLabelsTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");

    /** Labels GitHub has retired or announced for retirement: {@code macos-14} and older. */
    private static final Pattern UNUSABLE = Pattern.compile("\\bmacos-(?:1[0-4]|latest)\\b");

    private static final Pattern MACOS_RUNNER = Pattern.compile("macos-(\\d+)(?:-.*)?");
    private static final Pattern MACOS_MIN = Pattern.compile("(?m)^\\s*MACOS_MIN=(\\w+)$");

    /** Homebrew's names for the macOS versions a runner label can carry. */
    private static final Map<Integer, String> HOMEBREW_MACOS = Map.of(
            15, "sequoia",
            26, "tahoe");

    @Test
    void noWorkflowUsesARetiredOrMovingMacOsLabel() throws IOException {
        var offenders = new ArrayList<String>();
        try (var files = Files.list(WORKFLOWS)) {
            for (var file : files.filter(f -> f.toString().matches(".*\\.ya?ml")).sorted().toList()) {
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    var line = lines.get(i);
                    if (!line.strip().startsWith("#") && UNUSABLE.matcher(line).find()) {
                        offenders.add(file.getFileName() + ":" + (i + 1) + ": " + line.strip());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "use a pinned, supported macOS runner label such as macos-15 "
                + "(see .claude/rules/ci.md):\n" + String.join("\n", offenders));
    }

    /**
     * Nothing sets {@code MACOSX_DEPLOYMENT_TARGET}, so a release binary needs the macOS it was built
     * on, and the newest runner in the release matrix is the minimum for every Mac the formula serves.
     * The tap step writes that minimum as {@code depends_on macos:}; this fails when a runner label
     * moves and it does not.
     */
    @Test
    void homebrewFormulaDeclaresTheReleaseRunnersMacOs() throws IOException {
        var release = WORKFLOWS.resolve("release.yml");
        int newest = 0;
        for (var entry : new YAMLMapper().readTree(release.toFile())
                .path("jobs").path("build").path("strategy").path("matrix").path("include")) {
            var runner = MACOS_RUNNER.matcher(entry.path("runner").asText());
            if (runner.matches()) {
                newest = Math.max(newest, Integer.parseInt(runner.group(1)));
            }
        }
        assertNotEquals(0, newest, "no macOS runner found in release.yml's build matrix");
        var expected = HOMEBREW_MACOS.get(newest);
        assertNotNull(expected, "add macOS " + newest + "'s Homebrew name to HOMEBREW_MACOS");

        var declared = MACOS_MIN.matcher(Files.readString(release));
        assertTrue(declared.find(), "release.yml's Homebrew step no longer sets MACOS_MIN");
        assertEquals(expected, declared.group(1), "release binaries are built on macos-" + newest
                + ", so the formula must declare depends_on macos: :" + expected);
    }
}
