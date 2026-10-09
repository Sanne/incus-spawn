package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import io.hyperfoil.tools.jjq.yaml.JqYamlException;
import io.hyperfoil.tools.jjq.yaml.YamlOptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral gate for YAML definition parsing: union forms bind, and every
 * known error shape fails with a path. (The earlier Jackson-vs-jjq engine
 * comparison served the migration; Jackson databind is gone now.)
 */
class JjqYamlParityTest {

    private static final JqMapper MAPPER = JqMapper.create();

    private static String resource(String path) throws Exception {
        try (var is = JjqYamlParityTest.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("missing resource " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ImageDef parseImageJjq(String yaml) {
        return JqYaml.fromYaml(yaml, MAPPER, ImageDef.class, YamlOptions.STRICT);
    }

    private static ToolDef parseToolJjq(String yaml) {
        return JqYaml.fromYaml(yaml, MAPPER, ToolDef.class, YamlOptions.STRICT);
    }

    @Test
    void builtinImagesBind() throws Exception {
        for (var file : ImageDef.BUILTIN_FILES) {
            var yaml = resource("images/" + file);
            var def = parseImageJjq(yaml);
            assertNotNull(def.getName(), "image " + file);
        }
    }

    @Test
    void builtinToolsBind() throws Exception {
        var toolFiles = List.of("headroom.yaml", "perf.yaml", "podman.yaml", "maven-3.yaml",
                "mvnd.yaml", "sshd.yaml", "idea-backend.yaml", "vscode-remote.yaml",
                "starship.yaml", "tmux.yaml", "typesafe.yaml", "zmx.yaml");
        for (var file : toolFiles) {
            var yaml = resource("tools/" + file);
            var def = parseToolJjq(yaml);
            assertNotNull(def.getName(), "tool " + file);
        }
    }

    @Test
    void toolUnionForms() {
        var strings = parseImageJjq("name: t\ntools:\n  - maven-3\n  - sshd\n");
        assertEquals(List.of("maven-3", "sshd"), toolNames(strings));

        var mixed = parseImageJjq("name: t\ntools:\n  - maven-3\n  - idea-backend:\n      memory: \"8g\"\n");
        assertEquals(List.of("maven-3", "idea-backend"), toolNames(mixed));
        assertEquals("8g", mixed.getTools().get(1).getParams().get("memory"));

        var nullParams = parseImageJjq("name: t\ntools:\n  - idea-backend:\n");
        assertEquals(List.of("idea-backend"), toolNames(nullParams));
        assertTrue(nullParams.getTools().get(0).getParams().isEmpty());
    }

    @Test
    void skillUnionForms() {
        var list = parseImageJjq("name: t\nskills:\n  - xixu-me/skills@xget\n");
        assertEquals(List.of("xixu-me/skills@xget"), list.getSkills().getList());

        var obj = parseImageJjq("name: t\nskills:\n  repo: myorg/catalog\n  list:\n    - a\n");
        assertEquals("myorg/catalog", obj.getSkills().getRepo());
        assertEquals(List.of("a"), obj.getSkills().getList());
    }

    @Test
    void duplicateKeyFailsWithKeyAndLine() {
        var yaml = "name: t\ntools:\n  - a\ntools:\n  - b\n";
        assertThrows(Exception.class, () -> ImageDef.parseYaml(yaml));
        var ex = assertThrows(JqYamlException.class, () -> parseImageJjq(yaml));
        assertEquals("tools", ex.key());
        assertTrue(ex.line() > 0);
    }

    @Test
    void multiKeyToolEntryRejected() {
        var yaml = "name: t\ntools:\n  - idea-backend:\n      memory: \"8g\"\n    extra-key:\n      port: \"9090\"\n";
        assertThrows(Exception.class, () -> ImageDef.parseYaml(yaml));
        var ex = assertThrows(JqMapperException.class, () -> parseImageJjq(yaml));
        assertTrue(ex.getMessage().contains("single-key map"), ex.getMessage());
        assertTrue(ex.getMessage().contains("$.tools"), ex.getMessage());
    }

    @Test
    void envShellStringRejected() {
        var yaml = "name: t\nenv:\n  - export FOO=bar\n";
        assertThrows(Exception.class, () -> ImageDef.parseYaml(yaml));
        var ex = assertThrows(JqMapperException.class, () -> parseImageJjq(yaml));
        assertTrue(ex.getMessage().contains("shell string"), ex.getMessage());
    }

    @Test
    void scalarForListFailsWithPath() {
        var yaml = "name: t\npackages: git\n";
        assertThrows(Exception.class, () -> ImageDef.parseYaml(yaml));
        var ex = assertThrows(JqMapperException.class, () -> parseImageJjq(yaml));
        assertTrue(ex.getMessage().contains("$.packages"), ex.getMessage());
    }

    @Test
    void mappingForListFailsWithPath() {
        var yaml = "name: t\npackages:\n  kvm: yes\n";
        assertThrows(Exception.class, () -> ImageDef.parseYaml(yaml));
        var ex = assertThrows(JqMapperException.class, () -> parseImageJjq(yaml));
        assertTrue(ex.getMessage().contains("$.packages"), ex.getMessage());
    }

    @Test
    void listForScalarFailsWithPath() {
        var yaml = "name: t\ntype:\n  - kvm\n";
        assertThrows(Exception.class, () -> ImageDef.parseYaml(yaml));
        var ex = assertThrows(JqMapperException.class, () -> parseImageJjq(yaml));
        assertTrue(ex.getMessage().contains("$.type"), ex.getMessage());
    }

    @Test
    void nestedListForScalarFailsWithPath() {
        var yaml = "name: t\nrepos:\n  - url:\n      - https://example.com/a.git\n";
        assertThrows(Exception.class, () -> ImageDef.parseYaml(yaml));
        var ex = assertThrows(JqMapperException.class, () -> parseImageJjq(yaml));
        assertTrue(ex.getMessage().contains("$.repos[0].url"), ex.getMessage());
    }

    private static List<String> toolNames(ImageDef def) {
        var names = new ArrayList<String>();
        for (var tool : def.getTools()) {
            names.add(tool.getName());
        }
        return names;
    }
}
