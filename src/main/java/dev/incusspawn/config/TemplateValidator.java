package dev.incusspawn.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            errors.add(YamlErrors.friendly(file.getFileName().toString(), e));
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

        validateHostResources(def, warnings);
        validateDuplicateTools(def, warnings);

        return new ValidationResult(errors, warnings);
    }

    private static final Set<String> VALID_HR_MODES = Set.of("readonly", "overlay", "copy");

    private static void validateHostResources(ImageDef def, List<String> warnings) {
        for (var hr : def.getHostResources()) {
            if (hr.getMode() != null && !VALID_HR_MODES.contains(hr.getMode())) {
                warnings.add("host-resource mode '" + hr.getMode()
                        + "' is not valid — must be one of: readonly, overlay, copy");
            }
        }
    }

    private static void validateDuplicateTools(ImageDef def, List<String> warnings) {
        var seen = new HashSet<String>();
        for (var tool : def.getTools()) {
            if (!seen.add(tool.getName())) {
                warnings.add("tool '" + tool.getName() + "' is listed more than once");
            }
        }
    }
}
