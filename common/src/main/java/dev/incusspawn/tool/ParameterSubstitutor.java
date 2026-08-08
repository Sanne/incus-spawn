package dev.incusspawn.tool;

import java.util.List;
import java.util.Map;

/**
 * Handles parameter substitution in tool definitions.
 * Replaces ${param_name} placeholders with actual values.
 */
public class ParameterSubstitutor {

    /**
     * Substitute ${param_name} placeholders in a string.
     */
    public static String substitute(String template, Map<String, String> parameters) {
        if (template == null || parameters == null || parameters.isEmpty()) return template;
        var result = template;
        for (var entry : parameters.entrySet()) {
            result = result.replace("${param_" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * Substitute ${param_name} placeholders in a list of strings.
     */
    public static List<String> substitute(List<String> templates, Map<String, String> parameters) {
        if (templates == null || parameters == null || parameters.isEmpty()) return templates;
        return templates.stream()
            .map(t -> substitute(t, parameters))
            .toList();
    }
}
