package dev.incusspawn.tool;

import dev.incusspawn.RuntimeConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolDescriptionTest {

    @Test
    void allBuiltinYamlToolsHaveDescriptions() throws Exception {
        for (var filename : ToolDefLoader.BUILTIN_TOOLS) {
            try (var is = getClass().getClassLoader().getResourceAsStream("tools/" + filename)) {
                assertNotNull(is, "Missing resource: tools/" + filename);
                var def = ToolDef.loadFromStream(is);
                assertNotNull(def.getName(), filename + " has no name");
                assertFalse(def.getDescription().isBlank(),
                        "Tool '" + def.getName() + "' (" + filename + ") must have a non-empty description");
            }
        }
    }

    @Test
    void allCdiToolsHaveDescriptions() {
        for (var tool : RuntimeConstants.CDI_TOOLS) {
            assertFalse(tool.description().isBlank(),
                    "CDI tool '" + tool.name() + "' must have a non-empty description");
        }
    }
}
