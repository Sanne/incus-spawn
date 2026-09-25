import dev.incusspawn.config.SpawnConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Writes config.yaml files with a released isx's own {@code SpawnConfig.save()}, the way that
 * release's {@code isx init} would have left them. Run by {@code generate-fixtures.sh} against
 * the release's classes -- never against the working tree, whose serializer is the thing under test.
 *
 * <p>Uses only setters every release since v0.3.8 has, so the same source produces each new
 * release's fixture set. The credential values are recognisable placeholders, never real ones;
 * {@code PreviousReleaseConfigCompatTest} asserts on them.
 *
 * <p>Args: output directory, file-name prefix (the release tag).
 */
public class GenerateConfigFixtures {

    private interface Fill { void fill(SpawnConfig config) throws Exception; }

    public static void main(String[] args) throws Exception {
        var out = Path.of(args[0]);
        var prefix = args[1];

        // Every credential isx knows, plus a YAML tool's namespace and every non-credential field.
        write(out, prefix, "full", c -> {
            c.getClaude().setApiKey("sk-ant-api03-fixture");
            c.getGithub().setToken("ghp_fixture");
            c.getGithub().setEmail("me@example.com");
            c.getBob().setApiKey("bob-fixture");
            c.getBob().setLicenseConsent(true);
            c.getOpenai().setApiKey("sk-openai-fixture");
            // How a YAML tool's credential was saved: setupToolConfig -> setConfigByPath.
            c.setConfigByPath("testProxyTool.token", "tpt-fixture");
            c.setFeatures(List.of("openai"));
            c.setSearchPaths(List.of("~/my-templates"));
            c.setHostPaths(List.of("~/src"));
            c.setRepoPaths(Map.of("quarkus", "~/quarkus"));
            c.setIncusBridgeGateway("10.0.0.1");
            c.setAutoCloneRepos("always");
        });
        write(out, prefix, "oauth", c -> {
            c.getClaude().setOauthToken("sk-ant-oat01-fixture");
            c.getGithub().setToken("ghp_fixture");
        });
        write(out, prefix, "vertex", c -> {
            c.getClaude().setUseVertex(true);
            c.getClaude().setCloudMlRegion("us-east5");
            c.getClaude().setVertexProjectId("my-project");
        });
        // Vertex chosen but never finished: must still be reported, not read as "no credentials".
        write(out, prefix, "vertex-incomplete", c -> c.getClaude().setUseVertex(true));
        // The single-path 'host-path' key from before 'host-paths', which releases up to v0.3.7
        // read and wrote back as-is (v0.3.8 migrates it into 'host-paths' on save).
        write(out, prefix, "host-path", c -> {
            c.getGithub().setToken("ghp_fixture");
            c.setHostPath("~/src");
        });
        // What a first 'isx init' that skipped every credential left behind.
        write(out, prefix, "empty", c -> { });
    }

    private static void write(Path out, String prefix, String name, Fill fill) throws Exception {
        // save() writes under user.home, so give each file a home of its own.
        var home = Files.createTempDirectory("isx-config-fixture");
        System.setProperty("user.home", home.toString());
        var config = new SpawnConfig();
        fill.fill(config);
        config.save();
        Files.copy(home.resolve(".config/incus-spawn/config.yaml"),
                out.resolve(prefix + "-" + name + ".yaml"), StandardCopyOption.REPLACE_EXISTING);
    }
}
