package dev.incusspawn;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
        aliases.fieldNames().forEachRemaining(alias -> {
            var ref = aliases.get(alias).get("script-ref").asText();
            var asset = ref.substring(ref.lastIndexOf('/') + 1);
            assertTrue(uploads.contains(asset),
                    "alias '" + alias + "' points at " + asset
                            + ", which 'gh release create' never uploads");
        });
    }

    /**
     * The arguments of the workflow's {@code gh release create}, not the whole file: building the
     * jar and uploading it are separate steps, and a jar that is only built is exactly the state
     * this test exists to catch.
     */
    private static String releaseAssetArguments() throws IOException {
        var workflow = Files.readString(RELEASE_WORKFLOW);
        var start = workflow.indexOf("gh release create");
        assertTrue(start >= 0, "release.yml must create the GitHub release");
        // The command is one backslash-continued shell line; it ends at the step's env block.
        // Asserting the terminator is found matters: falling back to the rest of the file would
        // sweep in the COPR/APT/Homebrew steps, which name assets this command never uploads.
        var end = workflow.indexOf("\n        env:", start);
        assertTrue(end > start, "could not delimit the 'gh release create' step in release.yml");
        return workflow.substring(start, end);
    }

    private static com.fasterxml.jackson.databind.JsonNode aliases() throws IOException {
        var root = JSON.readTree(Files.readString(CATALOG));
        var aliases = root.get("aliases");
        assertNotNull(aliases, "jbang-catalog.json must declare aliases");
        return aliases;
    }
}
