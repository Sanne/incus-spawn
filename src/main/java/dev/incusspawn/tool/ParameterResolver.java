package dev.incusspawn.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves and validates tool parameters.
 * Merges provided values with defaults and validates against parameter definitions.
 */
public class ParameterResolver {

    /**
     * Validation result containing errors, warnings, and resolved parameter values.
     */
    public record Result(
        List<String> errors,
        Map<String, String> resolvedValues
    ) {
        public boolean hasErrors() { return !errors.isEmpty(); }
    }

    /**
     * Resolve and validate parameters for a tool.
     * Unknown parameters trigger errors.
     *
     * @param definitions parameter definitions from the tool
     * @param providedValues parameter values provided by the user
     * @return validation result with errors, warnings, and resolved values (with defaults applied)
     */
    public static Result resolve(
            Map<String, ToolDef.ParameterDef> definitions,
            Map<String, String> providedValues) {

        var errors = new ArrayList<String>();
        var resolved = new LinkedHashMap<String, String>();

        // Check for unknown parameters (ERROR)
        for (var key : providedValues.keySet()) {
            if (!definitions.containsKey(key)) {
                errors.add("Unknown parameter: " + key);
            }
        }

        // Resolve each defined parameter
        for (var entry : definitions.entrySet()) {
            var name = entry.getKey();
            var def = entry.getValue();
            var provided = providedValues.get(name);
            var value = provided != null ? provided : def.getDefault();

            if (value == null) {
                errors.add("Required parameter '" + name + "' has no value and no default");
                continue;
            }

            // Validate based on type
            var validation = validateValue(name, value, def);
            if (!validation.isEmpty()) {
                errors.add(validation);
                continue;
            }

            var type = def.getType() != null ? def.getType() : "string";
            resolved.put(name, "boolean".equals(type) ? value.toLowerCase() : value);
        }

        return new Result(errors, resolved);
    }

    private static String validateValue(String name, String value, ToolDef.ParameterDef def) {
        var type = def.getType() != null ? def.getType() : "string";

        return switch (type) {
            case "string" -> validateString(name, value, def);
            case "integer" -> validateInteger(name, value, def);
            case "boolean" -> validateBoolean(name, value);
            case "enum" -> validateEnum(name, value, def);
            default -> "Unknown parameter type: " + type + " for parameter: " + name;
        };
    }

    private static String validateString(String name, String value, ToolDef.ParameterDef def) {
        if (def.getPattern() != null && !def.getPattern().isEmpty()) {
            try {
                if (!Pattern.matches(def.getPattern(), value)) {
                    return "Parameter '" + name + "' value '" + value
                        + "' does not match pattern: " + def.getPattern();
                }
            } catch (Exception e) {
                return "Invalid regex pattern for parameter '" + name + "': " + e.getMessage();
            }
        }
        return "";
    }

    private static String validateInteger(String name, String value, ToolDef.ParameterDef def) {
        try {
            int intValue = Integer.parseInt(value);
            if (def.getMin() != null && intValue < def.getMin()) {
                return "Parameter '" + name + "' value " + intValue
                    + " is less than minimum: " + def.getMin();
            }
            if (def.getMax() != null && intValue > def.getMax()) {
                return "Parameter '" + name + "' value " + intValue
                    + " is greater than maximum: " + def.getMax();
            }
            return "";
        } catch (NumberFormatException e) {
            return "Parameter '" + name + "' must be an integer, got: " + value;
        }
    }

    private static String validateBoolean(String name, String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            return "Parameter '" + name + "' must be 'true' or 'false', got: " + value;
        }
        return "";
    }

    private static String validateEnum(String name, String value, ToolDef.ParameterDef def) {
        if (def.getOptions() == null || def.getOptions().isEmpty()) {
            return "Parameter '" + name + "' is an enum but has no options defined";
        }
        if (!def.getOptions().contains(value)) {
            return "Parameter '" + name + "' value '" + value
                + "' not in allowed options: " + String.join(", ", def.getOptions());
        }
        return "";
    }
}
