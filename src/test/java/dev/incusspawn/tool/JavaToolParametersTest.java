package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parameter support in ToolSetup implementations.
 * Verifies that parameter substitution works and that Java tools
 * default to no parameters.
 */
class JavaToolParametersTest {

    @Test
    void testParameterSubstitutorStaticMethod() {
        var template = "Hello ${param_name}, your value is ${param_value}";
        var params = Map.of("name", "World", "value", "42");

        var result = ParameterSubstitutor.substitute(template, params);

        assertEquals("Hello World, your value is 42", result);
    }

    @Test
    void testParameterSubstitutorWithEmptyParams() {
        var template = "No params here";
        var result = ParameterSubstitutor.substitute(template, Map.of());

        assertEquals("No params here", result);
    }

    @Test
    void testParameterSubstitutorWithNullParams() {
        var template = "Test ${param_x}";
        var result = ParameterSubstitutor.substitute(template, null);

        // Should return unchanged when params is null
        assertEquals("Test ${param_x}", result);
    }

    @Test
    void testJavaToolsDefaultToNoParameters() {
        var claude = new ClaudeSetup();
        var gh = new GhSetup();

        assertTrue(claude.parameters().isEmpty(), "ClaudeSetup should have no parameters by default");
        assertTrue(gh.parameters().isEmpty(), "GhSetup should have no parameters by default");
    }
}
