package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParameterSubstitutorTest {

    @Test
    void testSimpleSubstitution() {
        assertEquals("-Xmx8g",
            ParameterSubstitutor.substitute("-Xmx${param_memory}", Map.of("memory", "8g")));
    }

    @Test
    void testMultipleSubstitutions() {
        var result = ParameterSubstitutor.substitute(
            "Memory: ${param_memory}, Port: ${param_port}",
            Map.of("memory", "8g", "port", "9000"));
        assertEquals("Memory: 8g, Port: 9000", result);
    }

    @Test
    void testNoSubstitution() {
        assertEquals("No params here",
            ParameterSubstitutor.substitute("No params here", Map.of("memory", "8g")));
    }

    @Test
    void testNullInput() {
        assertNull(ParameterSubstitutor.substitute((String) null, Map.of("memory", "8g")));
    }

    @Test
    void testEmptyParameters() {
        assertEquals("${param_memory}",
            ParameterSubstitutor.substitute("${param_memory}", Map.of()));
    }

    @Test
    void testListSubstitution() {
        var result = ParameterSubstitutor.substitute(
            List.of("export MEM=${param_memory}", "echo ${param_memory}"),
            Map.of("memory", "8g"));

        assertEquals(2, result.size());
        assertEquals("export MEM=8g", result.get(0));
        assertEquals("echo 8g", result.get(1));
    }
}
