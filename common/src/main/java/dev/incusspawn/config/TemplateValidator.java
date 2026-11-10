package dev.incusspawn.config;

import dev.incusspawn.util.OutputFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TemplateValidator {

    /**
     * What the check found, in the order it found them, what the definition reaches beyond its
     * instance (empty when it does not parse), and the {@code name:} it gives (null when it gives
     * none or does not parse).
     */
    public record ValidationResult(List<TemplateFinding> findings, List<TemplateReach> reach, String name) {
        public ValidationResult {
            findings = List.copyOf(findings);
            reach = List.copyOf(reach);
        }

        /** The errors' messages, in order: what the edit loop reports. */
        public List<String> errors() { return messages(TemplateFinding.Severity.ERROR); }

        /** The warnings' messages, in order. */
        public List<String> warnings() { return messages(TemplateFinding.Severity.WARNING); }

        public boolean hasErrors() { return findings.stream().anyMatch(TemplateFinding::isError); }
        public boolean hasWarnings() { return findings.stream().anyMatch(f -> !f.isError()); }

        private List<String> messages(TemplateFinding.Severity severity) {
            return findings.stream().filter(f -> f.severity() == severity).map(TemplateFinding::message).toList();
        }
    }

    public static ValidationResult validate(Path file, Map<String, ImageDef> knownTemplates) {
        return validate(file, knownTemplates, ImageDef.projectImagesDir());
    }

    /** {@code projectImagesDir} is where project-local templates live: the working directory's. */
    static ValidationResult validate(Path file, Map<String, ImageDef> knownTemplates, Path projectImagesDir) {
        String yaml;
        try {
            yaml = Files.readString(file);
        } catch (java.nio.charset.CharacterCodingException e) {
            return new ValidationResult(List.of(TemplateFinding.error(null,
                    OutputFormat.oneLine(file.getFileName().toString()) + ": " + NOT_UTF8)), List.of(), null);
        } catch (IOException e) {
            return new ValidationResult(List.of(TemplateFinding.error(null,
                    YamlErrors.friendly(file.getFileName().toString(), e))), List.of(), null);
        }
        return validate(yaml, file, knownTemplates, projectImagesDir);
    }

    /**
     * The check of a definition not yet on disk: {@code yaml}, as if it were the file at
     * {@code location}. Where it would live decides whether it is project-local, and so confined
     * to its project. The one check the editor, {@code isx templates new --from} and
     * {@code isx templates validate} share (#2076).
     */
    public static ValidationResult validate(String yaml, Path location, Map<String, ImageDef> knownTemplates) {
        return validate(yaml, location, knownTemplates, ImageDef.projectImagesDir());
    }

    static ValidationResult validate(String yaml, Path file, Map<String, ImageDef> knownTemplates, Path projectImagesDir) {
        var findings = new ArrayList<TemplateFinding>();

        ImageDef def;
        try {
            def = ImageDef.parseYaml(yaml);
        } catch (IOException e) {
            findings.add(TemplateFinding.error(null, YamlErrors.friendly(file.getFileName().toString(), e)));
            return new ValidationResult(findings, List.of(), null);
        }

        var projectLocal = projectLocal(file, projectImagesDir);
        // Before the refusals: a misspelled key may be why one applies ('nmae:').
        def.unknownKeyWarnings().forEach(w -> findings.add(TemplateFinding.warning(TemplateFinding.fieldOf(w, null), w)));
        if (def.getName() == null || def.getName().isBlank()) {
            findings.add(TemplateFinding.error("name", "'name' field is required and must not be blank"));
            return new ValidationResult(findings, TemplateReach.of(def, projectLocal), def.getName());
        }

        var invalidName = ImageDef.notAnInstanceName("template name", def.getName());
        if (invalidName != null) {
            findings.add(TemplateFinding.error("name", invalidName));
        }

        if (!def.isRoot()) {
            var invalidParent = ImageDef.notAnInstanceName("parent", def.getParent());
            if (invalidParent != null) {
                findings.add(TemplateFinding.error("parent", invalidParent));
            } else if (!knownTemplates.containsKey(def.getParent())) {
                findings.add(TemplateFinding.warning("parent", "Parent '" + def.getParent()
                        + "' not found among known templates"));
            }
        }

        if (def.isRoot() && (def.getImage() == null || def.getImage().isBlank())) {
            findings.add(TemplateFinding.warning("image", "Root template (no parent) should specify an 'image' field"));
        }

        if (def.getCoordinator() != null && projectLocal) {
            findings.add(TemplateFinding.warning("coordinator", ImageDef.PROJECT_LOCAL_COORDINATOR));
        } else if (def.getCoordinator() != null) {
            var chain = new java.util.HashMap<>(knownTemplates);
            chain.put(def.getName(), def);
            ImageDef.coordinatorProblems(def, chain)
                    .forEach(w -> findings.add(TemplateFinding.warning("coordinator", w)));
        }
        ImageDef.controlOverProblems(def, named -> named.equals(def.getName()) || knownTemplates.containsKey(named))
                .forEach(w -> findings.add(TemplateFinding.warning("coordinator.control-over", w)));
        validateRepos(def, projectLocal, findings);
        validateHostResources(file, def, projectImagesDir, findings);
        validateBaseImages(file, def, projectImagesDir, findings);
        validateDuplicateTools(def, findings);

        return new ValidationResult(findings, TemplateReach.of(def, projectLocal), def.getName());
    }

    /** Whether {@code file} is a project-local definition: one in {@code projectImagesDir}. */
    private static boolean projectLocal(Path file, Path projectImagesDir) {
        return projectImagesDir.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize().getParent());
    }

    /** Why a definition that is not UTF-8 is refused: the loader reads it as UTF-8, or not at all. */
    public static final String NOT_UTF8 = "not valid UTF-8 text";

    /** Each repo the loader would refuse, which would skip the whole definition. */
    private static void validateRepos(ImageDef def, boolean projectLocal, List<TemplateFinding> findings) {
        var repos = def.getRepos();
        for (int i = 0; i < repos.size(); i++) {
            var repo = repos.get(i);
            var problem = ImageDef.repoProblem(repo, projectLocal);
            if (problem == null) continue;
            findings.add(TemplateFinding.error("repos[" + i + "]" + repoKeyAtFault(repo, projectLocal), problem));
        }
    }

    /**
     * The key {@link ImageDef#repoProblem} refuses {@code repo} for, as {@code .key}, in the order
     * it checks them, so a form marks the key that is wrong: a project-local or blank or unreadable
     * {@code host-source}, a {@code url} beside one, or a {@code depth} beside one or below 1.
     */
    private static String repoKeyAtFault(ImageDef.RepoEntry repo, boolean projectLocal) {
        if (!repo.isHostSource()) return repo.getDepth() != null ? ".depth" : "";
        if (projectLocal || repo.getHostSource().isBlank()) return ".host-source";
        if (repo.getUrl() != null) return ".url";
        if (repo.getDepth() != null) return ".depth";
        return ".host-source";
    }

    private static final Set<String> VALID_HR_MODES = Set.of("readonly", "overlay", "copy");

    /**
     * Each host resource on its own, through what {@code isx build} runs on it, so every refused
     * one is reported, once: its target, and for a project-local template its confinement to the
     * project, while the file is still being edited.
     */
    private static void validateHostResources(Path file, ImageDef def, Path projectImagesDir,
                                              List<TemplateFinding> findings) {
        var projectLocal = projectLocal(file, projectImagesDir);
        var resources = def.getHostResources();
        for (int i = 0; i < resources.size(); i++) {
            var hr = resources.get(i);
            var field = "host-resources[" + i + "]";
            if (hr.getMode() != null && !VALID_HR_MODES.contains(hr.getMode())) {
                findings.add(TemplateFinding.warning(field + ".mode", "host-resource mode '" + hr.getMode()
                        + "' is not valid — must be one of: readonly, overlay, copy"));
            }
            if (hr.getSource() == null || hr.getSource().isBlank()) {
                findings.add(TemplateFinding.error(field + ".source", "a host-resource has no 'source'"));
                continue;
            }
            var single = new ImageDef();
            single.setName(def.getName());
            single.setHostResources(List.of(hr));
            if (projectLocal) locateInProject(single, file, projectImagesDir);
            try {
                HostResourceSetup.collectEffective(single, Map.of());
            } catch (HostResourceSetup.ForbiddenMountTargetException
                     | HostResourceSetup.HostPathOutsideProjectException e) {
                // isx's multi-line advice, the values it quotes already made safe.
                findings.add(TemplateFinding.error(field, e.getMessage()));
            } catch (IllegalArgumentException e) {
                // Path.of's refusal quotes the raw path.
                findings.add(TemplateFinding.error(field, OutputFormat.oneLine(e.getMessage())));
            }
        }
    }

    /**
     * A project-local template's {@code file://} base images, through the confinement
     * {@code isx build} applies before it creates anything (#2095), each on its own field.
     */
    private static void validateBaseImages(Path file, ImageDef def, Path projectImagesDir,
                                           List<TemplateFinding> findings) {
        if (!projectLocal(file, projectImagesDir)) return;
        var located = new ImageDef();
        located.setName(def.getName());
        located.setImageTag(def.getImageTag());
        locateInProject(located, file, projectImagesDir);
        validateBaseImage(located, "image_url", def.getImageUrl(), findings);
        validateBaseImage(located, "vm_image_url", def.getVmImageUrl(), findings);
    }

    /** Stamps {@code def} as the loader stamps a project-local definition read from {@code file}. */
    private static void locateInProject(ImageDef def, Path file, Path projectImagesDir) {
        def.setSource(file.toAbsolutePath().normalize().toString());
        def.setProjectRoot(projectImagesDir.toAbsolutePath().normalize().getParent().getParent());
    }

    private static void validateBaseImage(ImageDef located, String field, String url, List<TemplateFinding> findings) {
        try {
            HostResourceSetup.requireConfinedBaseImage(located, url);
        } catch (HostResourceSetup.HostPathOutsideProjectException e) {
            // isx's multi-line advice, the values it quotes already made safe.
            findings.add(TemplateFinding.error(field, e.getMessage()));
        }
    }

    private static void validateDuplicateTools(ImageDef def, List<TemplateFinding> findings) {
        var seen = new HashSet<String>();
        var tools = def.getTools();
        for (int i = 0; i < tools.size(); i++) {
            if (!seen.add(tools.get(i).getName())) {
                findings.add(TemplateFinding.warning("tools[" + i + "]",
                        "tool '" + tools.get(i).getName() + "' is listed more than once"));
            }
        }
    }
}
