package dev.incusspawn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * On Linux, {@code install.sh --native} builds inside a cached builder image FROM
 * {@code native-image:latest}, and only rebuilds it when its local tag is missing. The tag is the
 * cache key, so a GraalVM upgrade that bumps the release workflow but not the tag leaves every
 * existing local build on the old GraalVM, silently: that is how the 25.3 -> 25.4 upgrade reached
 * CI but not {@code install.sh}.
 */
class InstallScriptGraalVmTest {

    private static final Path INSTALL_SCRIPT = Path.of("../install.sh");
    private static final Path RELEASE_WORKFLOW = Path.of("../.github/workflows/release.yml");

    private static final Pattern BUILDER_TAG =
            Pattern.compile("^\\s*BUILDER_TAG=\"incus-spawn-graalvm-builder:([^\"]+)\"", Pattern.MULTILINE);

    @Test
    void builderTagMatchesTheReleasedLinuxGraalVm() throws IOException {
        var matcher = BUILDER_TAG.matcher(Files.readString(INSTALL_SCRIPT));
        assertTrue(matcher.find(), "install.sh must define the cached GraalVM BUILDER_TAG");
        var builderTag = matcher.group(1);

        assertEquals(Set.of(builderTag), linuxReleaseGraalVmVersions(),
                "install.sh BUILDER_TAG must be bumped together with the Linux graalvm-version in "
                        + "release.yml, or cached local builders keep the old GraalVM");
    }

    private static Set<String> linuxReleaseGraalVmVersions() throws IOException {
        JsonNode include = new YAMLMapper().readTree(RELEASE_WORKFLOW.toFile())
                .path("jobs").path("build").path("strategy").path("matrix").path("include");
        var versions = new HashSet<String>();
        for (var entry : include) {
            if ("linux".equals(entry.path("os").asText())) {
                versions.add(entry.path("graalvm-version").asText());
            }
        }
        assertFalse(versions.isEmpty(), "release.yml must build Linux binaries with a graalvm-version");
        return versions;
    }
}
