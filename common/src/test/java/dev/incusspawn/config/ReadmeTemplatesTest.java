package dev.incusspawn.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The templates README shows (#1435): the Quick Start ends with one a newcomer can save and
 * build as is, no other example takes its name, and no example, there or in the docs/ pages
 * (#1440), starts from the deprecated tpl-dev, so a copied one builds without its deprecation
 * warning.
 */
@Tag("docs")
class ReadmeTemplatesTest {

    private static final Pattern YAML_BLOCK = Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL);

    private static String readme() throws Exception {
        return Files.readString(Path.of("..", "README.md"));
    }

    /** README.md and every docs/*.md page, where the README's reference moved (#1440). */
    private static String readmeAndDocs() throws Exception {
        var docs = new StringBuilder(readme());
        try (var pages = Files.list(Path.of("..", "docs"))) {
            for (var page : pages.filter(p -> p.toString().endsWith(".md")).sorted().toList()) docs.append(Files.readString(page));
        }
        return docs.toString();
    }

    /** README's Quick Start section, from its heading to the next one. */
    static String quickStart(String readme) {
        var start = readme.indexOf("\n## Quick Start\n");
        assertTrue(start >= 0, "README has a Quick Start");
        var end = readme.indexOf("\n## ", start + 1);
        return readme.substring(start, end < 0 ? readme.length() : end);
    }

    /** The built-in templates alone: loadAll would add this machine's own definitions. */
    private static Map<String, ImageDef> builtins() throws Exception {
        var defs = new HashMap<String, ImageDef>();
        for (var file : ImageDef.BUILTIN_FILES) {
            try (var in = ImageDef.class.getClassLoader().getResourceAsStream("images/" + file)) {
                var def = ImageDef.parseYaml(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                defs.put(def.getName(), def);
            }
        }
        return defs;
    }

    /** The YAML blocks in {@code text} that define a template: they start with its name. */
    private static List<String> templates(String text) {
        var blocks = new ArrayList<String>();
        var m = YAML_BLOCK.matcher(text);
        while (m.find()) {
            var yaml = m.group(1).replaceFirst("^#[^\\n]*\\n", "");
            if (yaml.startsWith("name: tpl-")) blocks.add(yaml);
        }
        return blocks;
    }

    @Test
    void quickStartTemplateBuildsAsIsWithAnAgent(@TempDir Path dir) throws Exception {
        var quickStart = templates(quickStart(readme()));
        assertEquals(1, quickStart.size(), "the Quick Start shows one template");

        var file = dir.resolve("agent.yaml");
        Files.writeString(file, quickStart.get(0));
        var builtins = builtins();
        var result = TemplateValidator.validate(file, builtins);
        assertEquals(List.of(), result.errors());
        assertEquals(List.of(), result.warnings());

        var def = ImageDef.parseFile(file);
        assertTrue(builtins.containsKey(def.getParent()), "its parent is built in: " + def.getParent());
        assertEquals(Optional.empty(), ImageDef.deprecationWarning(def, builtins));
        assertNotNull(def.getDefaultAction(), "branching it opens an agent");
    }

    @Test
    void noOtherExampleTakesTheQuickStartName() throws Exception {
        // A newcomer keeps the Quick Start file; an example copied next to it under the same
        // name makes every build stop on conflicting definitions
        var name = ImageDef.parseYaml(templates(quickStart(readme())).get(0)).getName();
        var named = templates(readmeAndDocs()).stream()
                .filter(yaml -> yaml.startsWith("name: " + name + "\n")).count();
        assertEquals(1, named, name + " is defined once in README and docs/");
    }

    @Test
    void noExampleStartsFromTplDev() throws Exception {
        // tpl-dev only adds tools none of the examples need, so tpl-minimal replaces it as is.
        // A block that shows a built-in's own definition (tpl-java's) shows it as it is.
        var builtins = builtins();
        var offenders = new ArrayList<String>();
        for (var yaml : templates(readmeAndDocs())) {
            var def = ImageDef.parseYaml(yaml);
            if ("tpl-dev".equals(def.getParent()) && !builtins.containsKey(def.getName())) offenders.add(def.getName());
        }
        assertEquals(List.of(), offenders);
    }
}
