package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JBang channel is assembled from two files that nothing else ties together: the catalog names
 * the assets, and the release workflow uploads them. When they drift, `jbang app install` fails on
 * a 404 — or, worse, installs a CLI whose proxy was never published, which is issue #701: the proxy
 * cannot run in-process, so an isx-only installation has no working proxy at all.
 */
class JbangCatalogTest {

    private static final Path CATALOG = Path.of("../jbang-catalog.json");
    private static final Path RELEASE_WORKFLOW = Path.of("../.github/workflows/release.yml");

    @Test
    void catalogPublishesBothCliAndProxyAliases() throws IOException {
        // The alias names double as the installed filenames: JBang puts every wrapper in the same
        // ~/.jbang/bin directory, which is what lets ProxyService.resolveProxyBinaryPath() find
        // isx-proxy as a sibling of isx. Renaming either alias breaks that lookup silently.
        var aliases = aliases();
        assertTrue(aliases.has("isx"), "the CLI alias is the documented install command");
        assertTrue(aliases.has("isx-proxy"),
                "without the proxy alias a JBang install cannot start the proxy at all");
    }

    @Test
    void everyAliasAssetIsUploadedByTheRelease() throws IOException {
        var uploads = releaseAssetArguments();
        var aliases = aliases();
        aliases.keys().forEach(alias -> {
            var ref = aliases.path(alias).path("script-ref").asText();
            var asset = ref.substring(ref.lastIndexOf('/') + 1);
            assertTrue(uploads.contains(asset),
                    "alias '" + alias + "' points at " + asset
                            + ", which 'gh release create' never uploads");
        });
    }

    /**
     * The assets of the workflow's {@code gh release create} step, not the whole file: building the
     * jar and uploading it are separate steps, and a jar that is only built is exactly the state
     * this test exists to catch.
     */
    private static String releaseAssetArguments() throws IOException {
        var workflow = Files.readString(RELEASE_WORKFLOW);
        // The step lists the assets once, for the SHA256SUMS and the upload alike (#2080)
        var start = workflow.indexOf("- name: Create GitHub Release");
        assertTrue(start >= 0 && workflow.indexOf("gh release create", start) > start,
                "release.yml must create the GitHub release");
        // The command is one backslash-continued shell line; it ends at the step's env block.
        // Asserting the terminator is found matters: falling back to the rest of the file would
        // sweep in the COPR/APT/Homebrew steps, which name assets this command never uploads.
        var end = workflow.indexOf("\n        env:", start);
        assertTrue(end > start, "could not delimit the 'gh release create' step in release.yml");
        return workflow.substring(start, end);
    }

    private static JqObject aliases() throws IOException {
        var root = JqValues.parse(Files.readString(CATALOG));
        assertTrue(root.path("aliases").isObject(), "jbang-catalog.json must declare aliases");
        return (JqObject) root.path("aliases");
    }
}
