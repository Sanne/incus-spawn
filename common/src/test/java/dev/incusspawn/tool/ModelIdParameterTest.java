package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model parameter of each agent that can reach an OpenAI-compatible gateway takes a model ID
 * that holds a {@code /}, as gateways name theirs ({@code gateway/zai-org/glm-5-3}), and still
 * refuses what would end the JSON string the setup writes it into. Codex, Gemini CLI and Copilot
 * reach only their vendor's API, whose IDs hold no {@code /}, and keep refusing one.
 */
class ModelIdParameterTest {

    static Stream<Arguments> modelParameters() {
        return Stream.of(
                Arguments.of(new PiSetup(), "model"),
                Arguments.of(new OpencodeSetup(), "model"),
                Arguments.of(new OpencodeSetup(), "small-model"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("modelParameters")
    void acceptsModelIdWithSlashes(ToolSetup tool, String param) {
        var id = "gateway/zai-org/glm-5-3";
        var result = ParameterResolver.resolve(tool.parameters(), Map.of(param, id));
        assertTrue(result.errors().isEmpty(), () -> String.join("; ", result.errors()));
        assertEquals(id, result.resolvedValues().get(param));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("modelParameters")
    void refusesWhatWouldEndTheString(ToolSetup tool, String param) {
        for (var bad : new String[] {"/leading", "a\"b", "a\\b", "a b", "a\nb", "a'b"}) {
            var result = ParameterResolver.resolve(tool.parameters(), Map.of(param, bad));
            assertFalse(result.errors().isEmpty(), tool.name() + " " + param + " took " + bad);
        }
    }

    @Test
    void vendorOnlyAgentsKeepNativeIds() {
        for (var tool : new ToolSetup[] {new CodexSetup(), new GeminiSetup(), new CopilotSetup()}) {
            var result = ParameterResolver.resolve(tool.parameters(), Map.of("model", "gateway/zai-org/glm-5-3"));
            assertFalse(result.errors().isEmpty(), tool.name() + " took a model ID with a /");
        }
    }
}
