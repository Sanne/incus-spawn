package dev.incusspawn.config;

import dev.incusspawn.util.OutputFormat;

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
        return validate(file, knownTemplates, ImageDef.projectImagesDir());
    }

    /** {@code projectImagesDir} is where project-local templates live: the working directory's. */
    static ValidationResult validate(Path file, Map<String, ImageDef> knownTemplates, Path projectImagesDir) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();

        ImageDef def;
        try {
            def = ImageDef.parseFile(file);
        } catch (IOException e) {
            errors.add(YamlErrors.friendly(file.getFileName().toString(), e));
            return new ValidationResult(errors, warnings);
        }

        // Before the refusals: a misspelled key may be why one applies ('nmae:').
        warnings.addAll(def.unknownKeyWarnings());
        if (def.getName() == null || def.getName().isBlank()) {
            errors.add("'name' field is required and must not be blank");
            return new ValidationResult(errors, warnings);
        }

        var invalidName = ImageDef.notAnInstanceName("template name", def.getName());
        if (invalidName != null) {
            errors.add(invalidName);
        }

        if (!def.isRoot()) {
            var invalidParent = ImageDef.notAnInstanceName("parent", def.getParent());
            if (invalidParent != null) {
                errors.add(invalidParent);
            } else if (!knownTemplates.containsKey(def.getParent())) {
                warnings.add("Parent '" + def.getParent()
                        + "' not found among known templates");
            }
        }

        if (def.isRoot() && (def.getImage() == null || def.getImage().isBlank())) {
            warnings.add("Root template (no parent) should specify an 'image' field");
        }

        if (def.getCoordinator() != null && projectLocal(file, projectImagesDir)) {
            warnings.add(ImageDef.PROJECT_LOCAL_COORDINATOR);
        } else if (def.getCoordinator() != null) {
            var chain = new java.util.HashMap<>(knownTemplates);
            chain.put(def.getName(), def);
            warnings.addAll(ImageDef.coordinatorProblems(def, chain));
        }
        warnings.addAll(ImageDef.controlOverProblems(def,
                named -> named.equals(def.getName()) || knownTemplates.containsKey(named)));
        validateHostResources(file, def, projectImagesDir, warnings, errors);
        validateDuplicateTools(def, warnings);

        return new ValidationResult(errors, warnings);
    }

    /** Whether {@code file} is a project-local definition: one in {@code projectImagesDir}. */
    private static boolean projectLocal(Path file, Path projectImagesDir) {
        return projectImagesDir.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize().getParent());
    }

    private static final Set<String> VALID_HR_MODES = Set.of("readonly", "overlay", "copy");

    /**
     * Each host resource on its own, through what {@code isx build} runs on it, so every refused
     * one is reported, once: its target, and for a project-local template its confinement to the
     * project, while the file is still being edited.
     */
    private static void validateHostResources(Path file, ImageDef def, Path projectImagesDir,
                                              List<String> warnings, List<String> errors) {
        var imagesDir = projectImagesDir.toAbsolutePath().normalize();
        var projectLocal = projectLocal(file, projectImagesDir);
        for (var hr : def.getHostResources()) {
            if (hr.getMode() != null && !VALID_HR_MODES.contains(hr.getMode())) {
                warnings.add("host-resource mode '" + hr.getMode()
                        + "' is not valid — must be one of: readonly, overlay, copy");
            }
            if (hr.getSource() == null || hr.getSource().isBlank()) {
                errors.add("a host-resource has no 'source'");
                continue;
            }
            var single = new ImageDef();
            single.setName(def.getName());
            single.setHostResources(List.of(hr));
            if (projectLocal) {
                single.setSource(file.toAbsolutePath().normalize().toString());
                single.setProjectRoot(imagesDir.getParent().getParent());
            }
            try {
                HostResourceSetup.collectEffective(single, Map.of());
            } catch (HostResourceSetup.ForbiddenMountTargetException
                     | HostResourceSetup.HostPathOutsideProjectException e) {
                // isx's multi-line advice, the values it quotes already made safe.
                errors.add(e.getMessage());
            } catch (IllegalArgumentException e) {
                // Path.of's refusal quotes the raw path.
                errors.add(OutputFormat.oneLine(e.getMessage()));
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
