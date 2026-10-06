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
 * next release, which then ships no macOS binaries (#1000). The released binaries' minimum macOS is
 * set by the build rather than the runner (#1086), and the Homebrew formula and a release check
 * both follow it.
 */
class WorkflowRunnerLabelsTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");

    /** Labels GitHub has retired or announced for retirement: {@code macos-14} and older. */
    private static final Pattern UNUSABLE = Pattern.compile("\\bmacos-(?:1[0-4]|latest)\\b");

    private static final Pattern MACOS_RUNNER = Pattern.compile("macos-(\\d+)(?:-.*)?");
    private static final Pattern MACOS_MIN = Pattern.compile("(?m)^\\s*MACOS_MIN=(\\w+)$");
    private static final Pattern DEPLOYMENT_TARGET =
            Pattern.compile("<macos\\.deployment\\.target>(\\d+)\\.0</macos\\.deployment\\.target>");

    /** Homebrew's names for the macOS versions the binaries can target. */
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
     * The minimum macOS is pom.xml's {@code macos.deployment.target}, which every macOS native build
     * links with (#1086). The tap step declares it as {@code depends_on macos:}, which may not drift
     * from it, a release step checks every shipped macOS binary against it, and no release runner
     * may be older than the macOS it targets.
     */
    @Test
    void releaseDeclaresAndChecksTheDeploymentTarget() throws IOException {
        var pom = DEPLOYMENT_TARGET.matcher(Files.readString(Path.of("../pom.xml")));
        assertTrue(pom.find(), "pom.xml no longer sets macos.deployment.target");
        int target = Integer.parseInt(pom.group(1));

        var release = WORKFLOWS.resolve("release.yml");
        var jobs = new YAMLMapper().readTree(release.toFile()).path("jobs");
        for (var entry : jobs.path("build").path("strategy").path("matrix").path("include")) {
            var runner = MACOS_RUNNER.matcher(entry.path("runner").asText());
            if (runner.matches()) {
                assertTrue(Integer.parseInt(runner.group(1)) >= target, entry.path("runner").asText()
                        + " is older than the macOS " + target + " the release targets");
            }
        }

        var expected = HOMEBREW_MACOS.get(target);
        assertNotNull(expected, "add macOS " + target + "'s Homebrew name to HOMEBREW_MACOS");
        var declared = MACOS_MIN.matcher(Files.readString(release));
        assertTrue(declared.find(), "release.yml's Homebrew step no longer sets MACOS_MIN");
        assertEquals(expected, declared.group(1), "the binaries target macOS " + target
                + ", so the formula must declare depends_on macos: :" + expected);

        assertTrue(Files.readString(release).contains("scripts/check-macos-minos.py artifacts/native-macos-*/*"),
                "release.yml must check the minimum macOS of every macOS binary it uploads");
    }
}
