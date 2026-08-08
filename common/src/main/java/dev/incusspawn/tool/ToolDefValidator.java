package dev.incusspawn.tool;

import dev.incusspawn.config.YamlErrors;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ToolDefValidator {

    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    private static final Set<String> VALID_PARAM_TYPES = Set.of(
            "string", "integer", "boolean", "enum");

    public static ValidationResult validate(Path file) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();

        ToolDef def;
        try (var is = java.nio.file.Files.newInputStream(file)) {
            def = ToolDef.loadFromStream(is);
        } catch (IOException e) {
            errors.add(YamlErrors.friendly(file.getFileName().toString(), e));
            return new ValidationResult(errors, warnings);
        }

        validateDef(def, errors, warnings);
        return new ValidationResult(errors, warnings);
    }

    public static void validateDef(ToolDef def, List<String> errors, List<String> warnings) {
        if (def.getName() == null || def.getName().isBlank()) {
            errors.add("'name' field is required and must not be blank");
            return;
        }

        for (var dl : def.getDownloads()) {
            if (dl.getUrl() == null || dl.getUrl().isBlank()) {
                warnings.add("download entry in '" + def.getName() + "' is missing a 'url'");
            }
        }

        for (var entry : def.getParameters().entrySet()) {
            var name = entry.getKey();
            var param = entry.getValue();
            if (param.getType() != null && !VALID_PARAM_TYPES.contains(param.getType())) {
                warnings.add("parameter '" + name + "' has invalid type '" + param.getType()
                        + "' — must be one of: string, integer, boolean, enum");
            }
            if ("enum".equals(param.getType())
                    && (param.getOptions() == null || param.getOptions().isEmpty())) {
                warnings.add("parameter '" + name + "' is type 'enum' but has no 'options' defined");
            }
            if ("integer".equals(param.getType())
                    && param.getMin() != null && param.getMax() != null
                    && param.getMin() > param.getMax()) {
                warnings.add("parameter '" + name + "' has min (" + param.getMin()
                        + ") greater than max (" + param.getMax() + ")");
            }
        }
    }
}
