package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildSourceTest {

    @Test
    void roundTripSerialization() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setDescription("Test template");
        imageDef.setImage("images:fedora/43");
        imageDef.setPackages(List.of("git", "curl"));
        imageDef.setTools(List.of(new ToolDef.ToolRef("maven-3")));
        imageDef.setSource("built-in");

        var toolDef = new ToolDef();
        toolDef.setName("maven-3");
        toolDef.setDescription("Maven 3");
        toolDef.setPackages(List.of("maven"));

        var definitions = new LinkedHashMap<String, ImageDef>();
        definitions.put("tpl-test", imageDef);

        var tools = new LinkedHashMap<String, ToolDef>();
        tools.put("maven-3", toolDef);

        var sources = new LinkedHashMap<String, String>();
        sources.put("tpl-test", "built-in");

        var original = new BuildSource(definitions, tools, new LinkedHashMap<>(), sources);
        var json = original.toJson();

        assertNotNull(json);
        assertFalse(json.isBlank());

        var restored = BuildSource.fromJson(json);
        assertNotNull(restored);

        assertEquals(1, restored.getDefinitions().size());
        var restoredDef = restored.getDefinitions().get("tpl-test");
        assertNotNull(restoredDef);
        assertEquals("tpl-test", restoredDef.getName());
        assertEquals("Test template", restoredDef.getDescription());
        assertEquals("images:fedora/43", restoredDef.getImage());
        assertEquals(List.of("git", "curl"), restoredDef.getPackages());
        assertEquals(1, restoredDef.getTools().size());
        assertEquals("maven-3", restoredDef.getTools().get(0).getName());
        var params = restoredDef.getTools().get(0).getParams();
        assertNotNull(params, "params should not be null after deserialization");
        assertTrue(params.isEmpty(), "params should be empty, but was: " + params);
        assertEquals("built-in", restoredDef.getSource());

        assertEquals(1, restored.getTools().size());
        var restoredTool = restored.getTools().get("maven-3");
        assertNotNull(restoredTool);
        assertEquals("maven-3", restoredTool.getName());
        assertEquals(List.of("maven"), restoredTool.getPackages());
    }

    @Test
    void parentChainPreserved() {
        var root = new ImageDef();
        root.setName("tpl-minimal");
        root.setDescription("Minimal");
        root.setImage("images:fedora/43");
        root.setSource("/path/to/minimal.yaml");

        var child = new ImageDef();
        child.setName("tpl-dev");
        child.setDescription("Dev");
        child.setParent("tpl-minimal");
        child.setTools(List.of(new ToolDef.ToolRef("podman")));
        child.setSource("/path/to/dev.yaml");

        var definitions = new LinkedHashMap<String, ImageDef>();
        definitions.put("tpl-dev", child);
        definitions.put("tpl-minimal", root);

        var sources = new LinkedHashMap<String, String>();
        sources.put("tpl-dev", "/path/to/dev.yaml");
        sources.put("tpl-minimal", "/path/to/minimal.yaml");

        var bs = new BuildSource(definitions, new LinkedHashMap<>(), new LinkedHashMap<>(), sources);
        var json = bs.toJson();
        var restored = BuildSource.fromJson(json);

        assertNotNull(restored);
        assertEquals(2, restored.getDefinitions().size());

        var restoredChild = restored.getDefinitions().get("tpl-dev");
        assertEquals("tpl-minimal", restoredChild.getParent());
        assertEquals("/path/to/dev.yaml", restoredChild.getSource());

        var restoredRoot = restored.getDefinitions().get("tpl-minimal");
        assertTrue(restoredRoot.isRoot());
        assertEquals("/path/to/minimal.yaml", restoredRoot.getSource());
    }

    @Test
    void fromJsonReturnsNullForInvalidInput() {
        assertNull(BuildSource.fromJson(null));
        assertNull(BuildSource.fromJson(""));
        assertNull(BuildSource.fromJson("   "));
        assertNull(BuildSource.fromJson("not-json"));
    }

    @Test
    void descriptionForReturnsDescription() {
        var def = new ImageDef();
        def.setName("tpl-test");
        def.setDescription("My template");

        var definitions = new LinkedHashMap<String, ImageDef>();
        definitions.put("tpl-test", def);

        var bs = new BuildSource(definitions, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        assertEquals("My template", bs.descriptionFor("tpl-test"));
        assertEquals("", bs.descriptionFor("nonexistent"));
    }

    @Test
    void toolWithDependenciesPreserved() {
        var tool = new ToolDef();
        tool.setName("maven-3");
        tool.setPackages(List.of("maven"));
        tool.setRequires(List.of(new ToolDef.ToolRef("java-sdk")));

        var depTool = new ToolDef();
        depTool.setName("java-sdk");
        depTool.setPackages(List.of("java-17-openjdk-devel"));

        var tools = new LinkedHashMap<String, ToolDef>();
        tools.put("maven-3", tool);
        tools.put("java-sdk", depTool);

        var bs = new BuildSource(new LinkedHashMap<>(), tools, new LinkedHashMap<>(), new LinkedHashMap<>());
        var json = bs.toJson();
        var restored = BuildSource.fromJson(json);

        assertNotNull(restored);
        assertEquals(2, restored.getTools().size());
        assertEquals(1, restored.getTools().get("maven-3").getRequires().size());
        assertEquals("java-sdk", restored.getTools().get("maven-3").getRequires().get(0).getName());
    }

    @Test
    void constructorHandlesNullMaps() {
        var bs = new BuildSource(null, null, null, null);
        assertNotNull(bs.getDefinitions());
        assertNotNull(bs.getTools());
        assertNotNull(bs.getToolInstances());
        assertNotNull(bs.getSources());
        assertTrue(bs.getDefinitions().isEmpty());
        assertTrue(bs.getTools().isEmpty());
        assertTrue(bs.getToolInstances().isEmpty());
        assertTrue(bs.getSources().isEmpty());
    }

    @Test
    void fromJsonHandlesNullFields() {
        var json = "{\"definitions\":null,\"tools\":null,\"sources\":null}";
        var bs = BuildSource.fromJson(json);
        assertNotNull(bs);
        assertNotNull(bs.getDefinitions());
        assertNotNull(bs.getTools());
        assertNotNull(bs.getSources());
    }
}
