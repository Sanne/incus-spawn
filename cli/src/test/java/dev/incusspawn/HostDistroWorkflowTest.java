package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The host-distro jobs (#1972) boot a distribution's cloud image, pinned by URL and the
 * publisher's SHA-256 in {@code scripts/distro-images.env} (not in the workflow, which the upstream
 * watch's app may not change, #2210), each leg naming its image by prefix. A pin is only as good as
 * its pairing: a dated URL (a moving "latest" would change under the checksum), one checksum per
 * image, and every leg on the native binaries the build jobs upload.
 */
class HostDistroWorkflowTest {

    private static final Path WORKFLOW = Path.of("../.github/workflows/test-integration.yml");
    private static final Path PINS = Path.of("../scripts/distro-images.env");

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** Arch's dated image directory, whose version the file name repeats. */
    private static final Pattern ARCH_IMAGE = Pattern.compile(
            "https://geo\\.mirror\\.pkgbuild\\.com/images/v(\\d{8}\\.\\d+)/Arch-Linux-x86_64-cloudimg-\\1\\.qcow2");
    /** Ubuntu 24.04's dated release directory. */
    private static final Pattern UBUNTU_IMAGE = Pattern.compile(
            "https://cloud-images\\.ubuntu\\.com/releases/noble/release-\\d{8}/ubuntu-24\\.04-server-cloudimg-amd64\\.img");

    private static JqValue job() throws IOException {
        var job = JqYaml.parse(Files.readString(WORKFLOW)).path("jobs").path("host-distro");
        assertFalse(job.isNull(), "test-integration.yml has no host-distro job");
        return job;
    }

    private static List<JqValue> legs() throws IOException {
        return job().path("strategy").path("matrix").path("include").asList();
    }

    private static Map<String, String> pins() throws IOException {
        var pins = new HashMap<String, String>();
        for (var line : Files.readAllLines(PINS)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var eq = line.indexOf('=');
            assertTrue(eq > 0, "not a NAME=value line in distro-images.env: " + line);
            assertNull(pins.put(line.substring(0, eq), line.substring(eq + 1)), "pinned twice: " + line);
        }
        return pins;
    }

    @Test
    void theThreeHostShapesAreLegs() throws IOException {
        var shapes = legs().stream().map(leg -> leg.pathText("shape", "")).toList();
        assertEquals(List.of("arch", "arch-ufw", "ubuntu-ufw"), shapes);
    }

    @Test
    void everyLegPinsADatedImageToOneChecksum() throws IOException {
        var pins = pins();
        var shaByUrl = new HashMap<String, String>();
        for (var leg : legs()) {
            var shape = leg.pathText("shape", "");
            var image = leg.pathText("image", "");
            assertFalse(image.isEmpty(), shape + " names no image");
            var url = pins.get(image + "_IMAGE_URL");
            var sha = pins.get(image + "_IMAGE_SHA256");
            assertNotNull(url, shape + ": no " + image + "_IMAGE_URL in distro-images.env");
            assertNotNull(sha, shape + ": no " + image + "_IMAGE_SHA256 in distro-images.env");
            assertTrue(SHA256.matcher(sha).matches(), shape + ": image_sha256 is not 64 hex digits: " + sha);
            var expected = shape.startsWith("arch") ? ARCH_IMAGE : UBUNTU_IMAGE;
            assertTrue(expected.matcher(url).matches(), shape + ": not a dated image URL of its distribution: " + url);
            var previous = shaByUrl.putIfAbsent(url, sha);
            assertTrue(previous == null || previous.equals(sha), url + " is pinned to two checksums");
        }
    }

    @Test
    void theImageCacheIsKeyedByItsChecksum() throws IOException {
        var keys = new ArrayList<String>();
        for (var step : job().path("steps").asList()) {
            if (step.pathText("uses", "").startsWith("actions/cache@")) keys.add(step.path("with").pathText("key", ""));
        }
        assertEquals(List.of("distro-image-${{ env.IMAGE_SHA256 }}"), keys);
    }

    @Test
    void theLegsRunTheNativeBinariesTheBuildJobsUpload() throws IOException {
        var jobs = JqYaml.parse(Files.readString(WORKFLOW)).path("jobs");
        var uploaded = new ArrayList<String>();
        for (var build : List.of("build-native-cli", "build-native-proxy")) {
            for (var step : jobs.path(build).path("steps").asList()) {
                var name = step.path("with").pathText("name", "");
                if (step.pathText("uses", "").contains("upload-artifact")) uploaded.add(name);
            }
        }
        var downloaded = new ArrayList<String>();
        for (var step : job().path("steps").asList()) {
            if (step.pathText("uses", "").startsWith("actions/download-artifact@")) {
                downloaded.add(step.path("with").pathText("name", ""));
            }
        }
        assertEquals(List.of("isx-native-cli", "isx-native-proxy"), downloaded);
        assertTrue(uploaded.containsAll(downloaded), "the build jobs upload " + uploaded + ", not " + downloaded);
        var needs = job().path("needs").asList().stream().map(JqValue::asText).toList();
        assertEquals(List.of("build-native-cli", "build-native-proxy"), needs);
    }
}
