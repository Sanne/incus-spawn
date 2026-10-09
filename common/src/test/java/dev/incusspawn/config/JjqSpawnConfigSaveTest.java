package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code save()} output round-trips: re-parsing yields the same tree,
 * top-level key order is stable across saves, and no internal keys
 * ({@code extras}, write-only {@code host-path}) leak into the file.
 *
 * <p>Served-equality across save/reload is covered by
 * {@link PreviousReleaseConfigCompatTest}; this pins the file shape.
 */
class JjqSpawnConfigSaveTest {

    @TempDir
    Path home;
    private String savedHome;

    @BeforeEach
    void pointHomeAtATempDir() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", savedHome);
    }

    private String jjqSaved(String fixture) throws Exception {
        var dir = home.resolve(".config/incus-spawn");
        Files.createDirectories(dir);
        try (var in = getClass().getResourceAsStream("/config-compat/" + fixture + ".yaml")) {
            assertNotNull(in, "missing fixture " + fixture);
            Files.write(dir.resolve("config.yaml"), in.readAllBytes());
        }
        var config = SpawnConfig.load();
        return Jjq.toYaml(config);
    }

    private static List<String> topLevelKeys(JqValue tree) {
        var names = new ArrayList<String>();
        if (tree instanceof JqObject object) object.forEach((key, value) -> names.add(key));
        return names;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"v0.3.8-oauth", "v0.3.8-full", "v0.3.7-full", "v0.3.7-host-path"})
    void jjqSaveReparsesToAnEqualTreeWithStableOrder(String fixture) throws Exception {
        var saved = jjqSaved(fixture);
        var reparsed = JqYaml.parse(saved);
        var dir = home.resolve(".config/incus-spawn");
        var config = SpawnConfig.load();
        var expected = Jjq.mapper().toJqValue(config);
        assertEquals(expected, reparsed, "jjq save must re-parse to the loaded tree for " + fixture);
        var resaved = Jjq.toYaml(Jjq.spawnConfig(saved));
        assertEquals(topLevelKeys(reparsed), topLevelKeys(JqYaml.parse(resaved)),
                "jjq save must keep a stable top-level key order for " + fixture);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"v0.3.8-oauth", "v0.3.8-full"})
    void jjqSaveLeaksNoInternalKeys(String fixture) throws Exception {
        var saved = jjqSaved(fixture);
        var lines = saved.lines().filter(l -> !l.startsWith(" ") && !l.startsWith("\t")).toList();
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("extras:")),
                "any-getter must flatten, not nest:\n" + saved);
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("host-path:")),
                "write-only host-path must not be written back:\n" + saved);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"v0.3.8-oauth", "v0.3.8-full"})
    void jjqSaveIsAFixedPoint(String fixture) throws Exception {
        var saved = jjqSaved(fixture);
        var resaved = Jjq.toYaml(Jjq.spawnConfig(saved));
        assertEquals(JqYaml.parse(saved), JqYaml.parse(resaved),
                "jjq save must be a fixed point for " + fixture);
    }
}
