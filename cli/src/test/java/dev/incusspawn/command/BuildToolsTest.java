package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuildToolsTest {

    // --- collectEffectiveTools ---

    @Test
    void collectEffectiveToolsNoTools() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-empty");

        var toolDefLoader = mock(ToolDefLoader.class);
        var result = BuildTools.collectEffectiveTools(imageDef, java.util.Map.of(),
                toolDefLoader, java.util.List.of());
        assertTrue(result.effective().isEmpty());
        assertTrue(result.ancestors().isEmpty());
    }

    @Test
    void collectEffectiveToolsNoParent() {
        var tool = BuildCommandTest.simpleToolSetup("maven");
        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("maven")).thenReturn(tool);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-root");
        imageDef.setTools(List.of(new ToolDef.ToolRef("maven")));

        var result = BuildTools.collectEffectiveTools(imageDef, java.util.Map.of(),
                toolDefLoader, java.util.List.of());
        assertEquals(1, result.effective().size());
        assertEquals("maven", result.effective().get(0).name());
        assertTrue(result.ancestors().isEmpty());
    }

    @Test
    void collectEffectiveToolsDeduplicatesSameParams() {
        var tool = BuildCommandTest.simpleToolSetup("maven");
        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("maven")).thenReturn(tool);

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setTools(List.of(new ToolDef.ToolRef("maven")));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setTools(List.of(new ToolDef.ToolRef("maven")));

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var result = BuildTools.collectEffectiveTools(child, defs,
                toolDefLoader, java.util.List.of());
        assertTrue(result.effective().isEmpty(),
                "Tool 'maven' already in parent should be excluded");
        assertEquals(1, result.ancestors().size());
        assertEquals("maven", result.ancestors().get(0).name());
    }

    @Test
    void collectEffectiveToolsDeduplicatesAcrossGrandparent() {
        var tool1 = BuildCommandTest.simpleToolSetup("maven");
        var tool2 = BuildCommandTest.simpleToolSetup("gh");
        var tool3 = BuildCommandTest.simpleToolSetup("podman");
        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("maven")).thenReturn(tool1);
        when(toolDefLoader.find("gh")).thenReturn(tool2);
        when(toolDefLoader.find("podman")).thenReturn(tool3);

        var grandparent = new ImageDef();
        grandparent.setName("tpl-grandparent");
        grandparent.setTools(List.of(new ToolDef.ToolRef("maven")));

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setParent("tpl-grandparent");
        parent.setTools(List.of(new ToolDef.ToolRef("gh")));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setTools(List.of(new ToolDef.ToolRef("maven"), new ToolDef.ToolRef("gh"),
                new ToolDef.ToolRef("podman")));

        var defs = java.util.Map.of(
                "tpl-grandparent", grandparent,
                "tpl-parent", parent,
                "tpl-child", child);
        var result = BuildTools.collectEffectiveTools(child, defs,
                toolDefLoader, java.util.List.of());
        assertEquals(1, result.effective().size());
        assertEquals("podman", result.effective().get(0).name(),
                "Only podman should remain after deduplication");
        assertEquals(2, result.ancestors().size());
    }

    @Test
    void collectEffectiveToolsErrorsOnDifferentParams() {
        var memParam = new ToolDef.ParameterDef();
        memParam.setType("string");

        var tool = new ToolSetup() {
            @Override public String name() { return "idea-backend"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.Map<String, ToolDef.ParameterDef> parameters() {
                return java.util.Map.of("memory", memParam);
            }
        };
        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("idea-backend")).thenReturn(tool);

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setTools(List.of(new ToolDef.ToolRef("idea-backend",
                java.util.Map.of("memory", "4g"))));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setTools(List.of(new ToolDef.ToolRef("idea-backend",
                java.util.Map.of("memory", "8g"))));

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> BuildTools.collectEffectiveTools(child, defs,
                        toolDefLoader, java.util.List.of()));
        assertTrue(ex.getMessage().contains("idea-backend"));
        assertTrue(ex.getMessage().contains("tpl-parent"));
        assertTrue(ex.getMessage().contains("different parameters"));
    }

    @Test
    void collectEffectiveToolsAllowsReconfigurableParamOverride() {
        var modelParam = new ToolDef.ParameterDef();
        modelParam.setType("string");
        modelParam.setOptional(true);
        modelParam.setReconfigurable(true);

        var tool = new ToolSetup() {
            @Override public String name() { return "claude"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.Map<String, ToolDef.ParameterDef> parameters() {
                return java.util.Map.of("model", modelParam);
            }
        };
        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("claude")).thenReturn(tool);

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setTools(List.of(new ToolDef.ToolRef("claude")));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setTools(List.of(new ToolDef.ToolRef("claude",
                java.util.Map.of("model", "claude-sonnet-4-6"))));

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var result = BuildTools.collectEffectiveTools(child, defs,
                toolDefLoader, java.util.List.of());
        assertEquals(1, result.effective().size());
        assertEquals("claude", result.effective().get(0).name());
        assertTrue(result.effective().get(0).reconfigureOnly(),
                "Override of reconfigurable param should be marked reconfigureOnly");
        assertEquals("claude-sonnet-4-6", result.effective().get(0).parameters().get("model"));
    }

    @Test
    void collectEffectiveToolsErrorsOnMixedReconfigurableParams() {
        var memParam = new ToolDef.ParameterDef();
        memParam.setType("string");

        var modelParam = new ToolDef.ParameterDef();
        modelParam.setType("string");
        modelParam.setReconfigurable(true);

        var tool = new ToolSetup() {
            @Override public String name() { return "my-tool"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.Map<String, ToolDef.ParameterDef> parameters() {
                return java.util.Map.of("memory", memParam, "model", modelParam);
            }
        };
        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("my-tool")).thenReturn(tool);

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setTools(List.of(new ToolDef.ToolRef("my-tool",
                java.util.Map.of("memory", "4g", "model", "a"))));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setTools(List.of(new ToolDef.ToolRef("my-tool",
                java.util.Map.of("memory", "8g", "model", "b"))));

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> BuildTools.collectEffectiveTools(child, defs,
                        toolDefLoader, java.util.List.of()));
        assertTrue(ex.getMessage().contains("different parameters"));
    }

    @Test
    void collectEffectiveToolsNewToolPassesThrough() {
        var tool1 = BuildCommandTest.simpleToolSetup("maven");
        var tool2 = BuildCommandTest.simpleToolSetup("podman");
        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("maven")).thenReturn(tool1);
        when(toolDefLoader.find("podman")).thenReturn(tool2);

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setTools(List.of(new ToolDef.ToolRef("maven")));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setTools(List.of(new ToolDef.ToolRef("podman")));

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var result = BuildTools.collectEffectiveTools(child, defs,
                toolDefLoader, java.util.List.of());
        assertEquals(1, result.effective().size());
        assertEquals("podman", result.effective().get(0).name());
        assertEquals(1, result.ancestors().size());
        assertEquals("maven", result.ancestors().get(0).name());
    }

    // --- resolveTools ---

    @Test
    void resolveToolsFindsCdiTools() {
        var cdiTool = BuildCommandTest.simpleToolSetup("gh");

        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("gh")).thenReturn(cdiTool);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setTools(List.of(new ToolDef.ToolRef("gh")));

        var resolved = BuildTools.resolveTools(imageDef, toolDefLoader, true);
        assertEquals(1, resolved.size(), "CDI tool 'gh' should be resolved");
        assertEquals("gh", resolved.get(0).name());
    }

    @Test
    void resolveToolsFindsYamlTools() {
        var yamlTool = BuildCommandTest.simpleToolSetup("podman");

        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("podman")).thenReturn(yamlTool);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setTools(List.of(new ToolDef.ToolRef("podman")));

        var resolved = BuildTools.resolveTools(imageDef, toolDefLoader, true);
        assertEquals(1, resolved.size(), "YAML tool 'podman' should be resolved");
        assertEquals("podman", resolved.get(0).name());
    }

    @Test
    void resolveToolsFindsMixOfYamlAndCdiTools() {
        var cdiTool = BuildCommandTest.simpleToolSetup("claude");
        var yamlTool = BuildCommandTest.simpleToolSetup("sshd");

        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("claude")).thenReturn(cdiTool);
        when(toolDefLoader.find("sshd")).thenReturn(yamlTool);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setTools(List.of(new ToolDef.ToolRef("sshd"), new ToolDef.ToolRef("claude")));

        var resolved = BuildTools.resolveTools(imageDef, toolDefLoader, true);
        assertEquals(2, resolved.size(), "Both YAML and CDI tools should be resolved");
        var names = resolved.stream().map(r -> r.name()).toList();
        assertTrue(names.contains("sshd"), "YAML tool 'sshd' should be present");
        assertTrue(names.contains("claude"), "CDI tool 'claude' should be present");
    }

    @Test
    void resolveToolsExplicitParamsWinOverTransitiveDep() {
        var modelParam = new ToolDef.ParameterDef();
        modelParam.setType("string");
        modelParam.setDefault("default-model");

        var claude = new ToolSetup() {
            @Override public String name() { return "claude"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.Map<String, ToolDef.ParameterDef> parameters() {
                return java.util.Map.of("model", modelParam);
            }
        };
        var headroom = new ToolSetup() {
            @Override public String name() { return "headroom"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.List<String> requires() { return java.util.List.of("claude"); }
        };

        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("claude")).thenReturn(claude);
        when(toolDefLoader.find("headroom")).thenReturn(headroom);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setTools(List.of(
            new ToolDef.ToolRef("claude", java.util.Map.of("model", "claude-opus-4-6")),
            new ToolDef.ToolRef("headroom")
        ));

        var resolved = BuildTools.resolveTools(imageDef, toolDefLoader, true);
        assertEquals(2, resolved.size());
        var claudeResolved = resolved.stream().filter(r -> r.name().equals("claude")).findFirst().orElseThrow();
        assertEquals("claude-opus-4-6", claudeResolved.parameters().get("model"));
    }

    @Test
    void resolveToolsExplicitParamsWinOverTransitiveDepReversedOrder() {
        var modelParam = new ToolDef.ParameterDef();
        modelParam.setType("string");
        modelParam.setDefault("default-model");

        var claude = new ToolSetup() {
            @Override public String name() { return "claude"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.Map<String, ToolDef.ParameterDef> parameters() {
                return java.util.Map.of("model", modelParam);
            }
        };
        var headroom = new ToolSetup() {
            @Override public String name() { return "headroom"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.List<String> requires() { return java.util.List.of("claude"); }
        };

        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("claude")).thenReturn(claude);
        when(toolDefLoader.find("headroom")).thenReturn(headroom);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setTools(List.of(
            new ToolDef.ToolRef("headroom"),
            new ToolDef.ToolRef("claude", java.util.Map.of("model", "claude-opus-4-6"))
        ));

        var resolved = BuildTools.resolveTools(imageDef, toolDefLoader, true);
        assertEquals(2, resolved.size());
        var claudeResolved = resolved.stream().filter(r -> r.name().equals("claude")).findFirst().orElseThrow();
        assertEquals("claude-opus-4-6", claudeResolved.parameters().get("model"),
            "Explicit params should win even when transitive dep is resolved first");
    }

    @Test
    void resolveToolsExplicitWinsOverYamlTransitiveDepWithParams() {
        var modelParam = new ToolDef.ParameterDef();
        modelParam.setType("string");
        modelParam.setDefault("default-model");

        var claude = new ToolSetup() {
            @Override public String name() { return "claude"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.Map<String, ToolDef.ParameterDef> parameters() {
                return java.util.Map.of("model", modelParam);
            }
        };

        var headroomDef = new ToolDef();
        headroomDef.setName("headroom");
        headroomDef.setRequires(java.util.List.of(
            new ToolDef.ToolRef("claude", java.util.Map.of("model", "dep-model"))
        ));
        var headroom = new YamlToolSetup(headroomDef);

        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("claude")).thenReturn(claude);
        when(toolDefLoader.find("headroom")).thenReturn(headroom);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setTools(List.of(
            new ToolDef.ToolRef("headroom"),
            new ToolDef.ToolRef("claude", java.util.Map.of("model", "claude-opus-4-6"))
        ));

        var resolved = BuildTools.resolveTools(imageDef, toolDefLoader, true);
        assertEquals(2, resolved.size());
        var claudeResolved = resolved.stream().filter(r -> r.name().equals("claude")).findFirst().orElseThrow();
        assertEquals("claude-opus-4-6", claudeResolved.parameters().get("model"),
            "Explicit params should win over YAML transitive dep params");
    }

    @Test
    void resolveToolsWarnsWhenExplicitOverridesTransitiveDepWithParams() {
        var modelParam = new ToolDef.ParameterDef();
        modelParam.setType("string");
        modelParam.setDefault("default-model");

        var claude = new ToolSetup() {
            @Override public String name() { return "claude"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.Map<String, ToolDef.ParameterDef> parameters() {
                return java.util.Map.of("model", modelParam);
            }
        };

        var headroomDef = new ToolDef();
        headroomDef.setName("headroom");
        headroomDef.setRequires(java.util.List.of(
            new ToolDef.ToolRef("claude", java.util.Map.of("model", "dep-model"))
        ));
        var headroom = new YamlToolSetup(headroomDef);

        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("claude")).thenReturn(claude);
        when(toolDefLoader.find("headroom")).thenReturn(headroom);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setTools(List.of(
            new ToolDef.ToolRef("headroom"),
            new ToolDef.ToolRef("claude", java.util.Map.of("model", "claude-opus-4-6"))
        ));

        var oldErr = System.err;
        var errContent = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(errContent));
        try {
            var resolved = BuildTools.resolveTools(imageDef, toolDefLoader, false);
            assertEquals(2, resolved.size());
            assertTrue(errContent.toString().contains("overriding"),
                "Should warn when explicit overrides transitive dep with params");
        } finally {
            System.setErr(oldErr);
        }
    }

    @Test
    void resolveToolsNoWarningWhenTransitiveDepHasNoExplicitParams() {
        var modelParam = new ToolDef.ParameterDef();
        modelParam.setType("string");
        modelParam.setDefault("default-model");

        var claude = new ToolSetup() {
            @Override public String name() { return "claude"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.Map<String, ToolDef.ParameterDef> parameters() {
                return java.util.Map.of("model", modelParam);
            }
        };
        var headroom = new ToolSetup() {
            @Override public String name() { return "headroom"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.List<String> requires() { return java.util.List.of("claude"); }
        };

        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("claude")).thenReturn(claude);
        when(toolDefLoader.find("headroom")).thenReturn(headroom);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setTools(List.of(
            new ToolDef.ToolRef("headroom"),
            new ToolDef.ToolRef("claude", java.util.Map.of("model", "claude-opus-4-6"))
        ));

        var oldErr = System.err;
        var errContent = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(errContent));
        try {
            var resolved = BuildTools.resolveTools(imageDef, toolDefLoader, false);
            assertEquals(2, resolved.size());
            assertFalse(errContent.toString().contains("overriding"),
                "Should not warn when transitive dep has no explicit params");
        } finally {
            System.setErr(oldErr);
        }
    }

    @Test
    void resolveToolsDuplicateExplicitWithDifferentParamsStillErrors() {
        var modelParam = new ToolDef.ParameterDef();
        modelParam.setType("string");

        var tool = new ToolSetup() {
            @Override public String name() { return "my-tool"; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
            @Override public java.util.Map<String, ToolDef.ParameterDef> parameters() {
                return java.util.Map.of("model", modelParam);
            }
        };

        var toolDefLoader = mock(ToolDefLoader.class);
        when(toolDefLoader.find("my-tool")).thenReturn(tool);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setTools(List.of(
            new ToolDef.ToolRef("my-tool", java.util.Map.of("model", "a")),
            new ToolDef.ToolRef("my-tool", java.util.Map.of("model", "b"))
        ));

        var ex = assertThrows(IllegalArgumentException.class,
            () -> BuildTools.resolveTools(imageDef, toolDefLoader, true));
        assertTrue(ex.getMessage().contains("my-tool"));
        assertTrue(ex.getMessage().contains("different parameters"));
    }

}
