package dev.incusspawn;

import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * On Linux, {@code install.sh --native} builds inside a cached builder image FROM Oracle's
 * {@code native-image} image. That image's {@code :latest} is the 25.0 LTS line, not the innovation
 * line the release builds with, so local builds once ran a different GraalVM from CI and releases.
 * The base must be the floating tag of the Linux {@code graalvm-version} in release.yml
 * ({@code 25.4} is {@code 25i4}), which also names install.sh's cached builder, and the CLI's
 * and the proxy's default builder image must be the same: a bump that reaches the workflows but not these leaves
 * local builds on the old GraalVM, silently, as the 25.3 -> 25.4 upgrade did.
 * Every {@code setup-graalvm} step in the workflows follows the same version, so CI tests the
 * toolchain releases ship, with no exemption since the Intel macOS build was dropped (#1693).
 */
class InstallScriptGraalVmTest {

    private static final Path INSTALL_SCRIPT = Path.of("../install.sh");
    private static final Path WORKFLOWS = Path.of("../.github/workflows");
    private static final Path RELEASE_WORKFLOW = WORKFLOWS.resolve("release.yml");
    private static final String MATRIX_VERSION = "${{ matrix.graalvm-version }}";
    private static final List<Path> BUILDER_DEFAULTS = List.of(
            Path.of("src/main/resources-filtered/application.properties"),
            Path.of("../proxy/src/main/resources-filtered/application.properties"));

    private static final String NATIVE_IMAGE = "container-registry.oracle.com/graalvm/native-image";
    /** Oracle Linux 10, as {@code :latest} is: the bare {@code 25i4} tag is amd64 only, this one multi-arch. */
    private static final String OS = "-ol10";

    private static final Pattern GRAALVM_BASE =
            Pattern.compile("^\\s*GRAALVM_BASE=\"([^\"]+)\"", Pattern.MULTILINE);

    @Test
    void builderImagesFollowTheReleasedLinuxGraalVm() throws IOException {
        var image = NATIVE_IMAGE + ":" + imageTag(linuxReleaseGraalVmVersion()) + OS;

        var matcher = GRAALVM_BASE.matcher(Files.readString(INSTALL_SCRIPT));
        assertTrue(matcher.find(), "install.sh must define GRAALVM_BASE");
        assertEquals(image, matcher.group(1),
                "install.sh must build FROM the image of the Linux graalvm-version in release.yml");

        for (var path : BUILDER_DEFAULTS) {
            var properties = new Properties();
            try (var in = Files.newBufferedReader(path)) {
                properties.load(in);
            }
            assertEquals(image, properties.getProperty("quarkus.native.builder-image"),
                    path + ": the default builder image must be the released Linux GraalVM");
        }
    }

    @Test
    void everyWorkflowSetsUpTheReleasedLinuxGraalVm() throws IOException {
        var linux = linuxReleaseGraalVmVersion();
        var checked = new HashSet<String>();
        try (var files = Files.list(WORKFLOWS)) {
            for (var file : files.filter(f -> f.toString().endsWith(".yml")).sorted().toList()) {
                var jobs = JqYaml.parse(Files.readString(file)).path("jobs").asMap();
                for (var job : jobs.entrySet()) {
                    var where = file.getFileName() + " job " + job.getKey();
                    for (var step : job.getValue().path("steps").asList()) {
                        if (!step.pathText("uses", "").startsWith("graalvm/setup-graalvm@")) {
                            continue;
                        }
                        var version = step.path("with").pathText("version", "");
                        if (!MATRIX_VERSION.equals(version)) {
                            assertEquals(linux, version, where + ": setup-graalvm must install the Linux"
                                    + " graalvm-version of release.yml");
                            checked.add(where);
                            continue;
                        }
                        var include = job.getValue().path("strategy").path("matrix").path("include").asList();
                        assertFalse(include.isEmpty(), where + ": " + MATRIX_VERSION + " needs a matrix include");
                        for (var entry : include) {
                            var runner = entry.pathText("runner", "");
                            var entryVersion = entry.pathText("graalvm-version", "");
                            assertEquals(linux, entryVersion, where + " " + runner
                                    + ": graalvm-version must be the Linux one of release.yml");
                            checked.add(where + " " + runner);
                        }
                    }
                }
            }
        }
        for (var workflow : List.of("test-integration.yml job ", "macos-keychain-trust.yml job ", "release.yml job profiles",
                "release.yml job build ")) {
            assertTrue(checked.stream().anyMatch(c -> c.startsWith(workflow)),
                    "no setup-graalvm step checked in " + workflow + ": " + checked);
        }
    }

    @Test
    void innovationReleasesMapToTheirFloatingTag() {
        assertEquals("25i4", imageTag("25.4"));
        assertEquals("25i12", imageTag("25.12"));
        assertEquals("25", imageTag("25.0"));
    }

    /** setup-graalvm's {@code X.Y} as Oracle's floating image tag: {@code XiY}, or {@code X} for the LTS. */
    static String imageTag(String graalVmVersion) {
        var parts = parts(graalVmVersion);
        return "0".equals(parts[1]) ? parts[0] : parts[0] + "i" + parts[1];
    }

    private static String[] parts(String graalVmVersion) {
        var parts = graalVmVersion.split("\\.");
        assertEquals(2, parts.length, "graalvm-version must be X.Y, was " + graalVmVersion);
        return parts;
    }

    private static String linuxReleaseGraalVmVersion() throws IOException {
        var include = JqYaml.parse(Files.readString(RELEASE_WORKFLOW))
                .path("jobs").path("build").path("strategy").path("matrix").path("include");
        var versions = new HashSet<String>();
        for (var entry : include.arrayValue()) {
            if ("linux".equals(entry.path("os").asText())) {
                versions.add(entry.path("graalvm-version").asText());
            }
        }
        assertFalse(versions.isEmpty(), "release.yml must build Linux binaries with a graalvm-version");
        assertEquals(1, versions.size(), "release.yml must build every Linux binary with one graalvm-version");
        return versions.iterator().next();
    }
}
