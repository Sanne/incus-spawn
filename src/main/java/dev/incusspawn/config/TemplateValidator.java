package dev.incusspawn.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TemplateValidator {

    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    public static ValidationResult validate(Path file, Map<String, ImageDef> knownTemplates) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();

        ImageDef def;
        try {
            def = ImageDef.parseFile(file);
        } catch (IOException e) {
            errors.add("YAML parse error: " + e.getMessage());
            return new ValidationResult(errors, warnings);
        }

        if (def.getName() == null || def.getName().isBlank()) {
            errors.add("'name' field is required and must not be blank");
            return new ValidationResult(errors, warnings);
        }

        if (!def.getName().startsWith("tpl-")) {
            warnings.add("Template name '" + def.getName()
                    + "' does not follow the 'tpl-' prefix convention");
        }

        if (def.getParent() != null && !def.getParent().isBlank()) {
            if (!knownTemplates.containsKey(def.getParent())) {
                warnings.add("Parent '" + def.getParent()
                        + "' not found among known templates");
            }
        }

        if (def.isRoot() && (def.getImage() == null || def.getImage().isBlank())) {
            warnings.add("Root template (no parent) should specify an 'image' field");
        }

        return new ValidationResult(errors, warnings);
    }
}
