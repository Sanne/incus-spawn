package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParameterResolverTest {

    @Test
    void testResolveWithDefaults() {
        var def = new ToolDef.ParameterDef();
        def.setType("string");
        def.setDefault("2g");

        var result = ParameterResolver.resolve(
            Map.of("memory", def),
            Map.of()
        );

        assertFalse(result.hasErrors());
        assertEquals("2g", result.resolvedValues().get("memory"));
    }

    @Test
    void testResolveWithProvidedValue() {
        var def = new ToolDef.ParameterDef();
        def.setType("string");
        def.setDefault("2g");

        var result = ParameterResolver.resolve(
            Map.of("memory", def),
            Map.of("memory", "8g")
        );

        assertFalse(result.hasErrors());
        assertEquals("8g", result.resolvedValues().get("memory"));
    }

    @Test
    void testUnknownParameterError() {
        var def = new ToolDef.ParameterDef();
        def.setType("string");
        def.setDefault("2g");

        var result = ParameterResolver.resolve(
            Map.of("memory", def),
            Map.of("memory", "8g", "unknown", "value")
        );

        assertTrue(result.hasErrors());
        assertTrue(result.errors().get(0).contains("Unknown parameter: unknown"));
    }

    @Test
    void testMissingRequiredParameter() {
        var def = new ToolDef.ParameterDef();
        def.setType("string");
        // No default

        var result = ParameterResolver.resolve(
            Map.of("required", def),
            Map.of()
        );

        assertTrue(result.hasErrors());
        assertTrue(result.errors().get(0).contains("Required parameter"));
    }

    @Test
    void testStringPatternValidation() {
        var def = new ToolDef.ParameterDef();
        def.setType("string");
        def.setPattern("^[0-9]+[gGmM]$");
        def.setDefault("2g");

        var validResult = ParameterResolver.resolve(
            Map.of("memory", def),
            Map.of("memory", "8g")
        );
        assertFalse(validResult.hasErrors());

        var invalidResult = ParameterResolver.resolve(
            Map.of("memory", def),
            Map.of("memory", "invalid")
        );
        assertTrue(invalidResult.hasErrors());
        assertTrue(invalidResult.errors().get(0).contains("does not match pattern"));
    }

    @Test
    void testIntegerValidation() {
        var def = new ToolDef.ParameterDef();
        def.setType("integer");
        def.setMin(1024);
        def.setMax(65535);
        def.setDefault("8080");

        var validResult = ParameterResolver.resolve(
            Map.of("port", def),
            Map.of("port", "9000")
        );
        assertFalse(validResult.hasErrors());
        assertEquals("9000", validResult.resolvedValues().get("port"));

        var tooLowResult = ParameterResolver.resolve(
            Map.of("port", def),
            Map.of("port", "100")
        );
        assertTrue(tooLowResult.hasErrors());
        assertTrue(tooLowResult.errors().get(0).contains("less than minimum"));

        var tooHighResult = ParameterResolver.resolve(
            Map.of("port", def),
            Map.of("port", "99999")
        );
        assertTrue(tooHighResult.hasErrors());
        assertTrue(tooHighResult.errors().get(0).contains("greater than maximum"));

        var notIntResult = ParameterResolver.resolve(
            Map.of("port", def),
            Map.of("port", "abc")
        );
        assertTrue(notIntResult.hasErrors());
        assertTrue(notIntResult.errors().get(0).contains("must be an integer"));
    }

    @Test
    void testBooleanValidation() {
        var def = new ToolDef.ParameterDef();
        def.setType("boolean");
        def.setDefault("false");

        var trueResult = ParameterResolver.resolve(
            Map.of("debug", def),
            Map.of("debug", "true")
        );
        assertFalse(trueResult.hasErrors());

        var falseResult = ParameterResolver.resolve(
            Map.of("debug", def),
            Map.of("debug", "false")
        );
        assertFalse(falseResult.hasErrors());

        var invalidResult = ParameterResolver.resolve(
            Map.of("debug", def),
            Map.of("debug", "yes")
        );
        assertTrue(invalidResult.hasErrors());
        assertTrue(invalidResult.errors().get(0).contains("must be 'true' or 'false'"));
    }

    @Test
    void testEnumValidation() {
        var def = new ToolDef.ParameterDef();
        def.setType("enum");
        def.setOptions(java.util.List.of("production", "development", "testing"));
        def.setDefault("production");

        var validResult = ParameterResolver.resolve(
            Map.of("mode", def),
            Map.of("mode", "development")
        );
        assertFalse(validResult.hasErrors());

        var invalidResult = ParameterResolver.resolve(
            Map.of("mode", def),
            Map.of("mode", "invalid")
        );
        assertTrue(invalidResult.hasErrors());
        assertTrue(invalidResult.errors().get(0).contains("not in allowed options"));
    }

    @Test
    void testMultipleParameters() {
        var memoryDef = new ToolDef.ParameterDef();
        memoryDef.setType("string");
        memoryDef.setDefault("2g");

        var debugDef = new ToolDef.ParameterDef();
        debugDef.setType("boolean");
        debugDef.setDefault("false");

        var result = ParameterResolver.resolve(
            Map.of("memory", memoryDef, "debug", debugDef),
            Map.of("memory", "8g", "debug", "true")
        );

        assertFalse(result.hasErrors());
        assertEquals("8g", result.resolvedValues().get("memory"));
        assertEquals("true", result.resolvedValues().get("debug"));
    }
}
