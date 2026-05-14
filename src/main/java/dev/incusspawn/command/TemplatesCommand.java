package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.TemplateValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Command(
        name = "templates",
        description = "Manage template definitions",
        mixinStandardHelpOptions = true,
        subcommands = {
                TemplatesCommand.ListSub.class,
                TemplatesCommand.Edit.class,
                TemplatesCommand.New.class
        }
)
public class TemplatesCommand implements Runnable {

    @Override
    public void run() {
        new ListSub().run();
    }

    // ── list ────────────────────────────────────────────────────────────────────

    @Command(name = "list", description = "List available template names",
            mixinStandardHelpOptions = true)
    public static class ListSub implements Runnable {

        @Option(names = {"-v", "--verbose"}, description = "Show source and description")
        boolean verbose;

        @Override
        public void run() {
            var defs = ImageDef.loadAll();
            if (!verbose) {
                defs.keySet().forEach(System.out::println);
                return;
            }
            int maxName = defs.keySet().stream().mapToInt(String::length).max().orElse(10);
            int maxSource = defs.values().stream().mapToInt(d -> d.getSource().length()).max().orElse(7);
            var fmt = "%-" + maxName + "s  %-" + maxSource + "s  %s%n";
            System.out.printf(fmt, "NAME", "SOURCE", "DESCRIPTION");
            for (var entry : defs.entrySet()) {
                var def = entry.getValue();
                System.out.printf(fmt, entry.getKey(), def.getSource(), def.getDescription());
            }
        }
    }

    // ── edit ────────────────────────────────────────────────────────────────────

    @Command(name = "edit", description = "Edit a template definition in your editor",
            mixinStandardHelpOptions = true)
    public static class Edit implements Runnable {

        @Parameters(index = "0", description = "Template name (e.g. tpl-java)")
        String name;

        @Override
        public void run() {
            name = normalizeName(name);

            var defs = ImageDef.loadAll();
            var def = defs.get(name);
            if (def == null) {
                System.err.println("Template '" + name + "' not found.");
                System.err.println("Available templates: " + String.join(", ", defs.keySet()));
                return;
            }

            boolean isBuiltinCopy = false;
            Path editPath;

            if ("built-in".equals(def.getSource())) {
                var filename = ImageDef.filenameForName(name);
                editPath = ImageDef.userImagesDir().resolve(filename);

                if (Files.exists(editPath)) {
                    System.out.println("Editing existing user override: " + editPath);
                } else {
                    System.out.println("Note: Built-in template '" + name
                            + "' cannot be edited directly.");
                    System.out.println("Creating user-level override at " + editPath);
                    System.out.println("This override will take precedence over the built-in"
                            + " and will not auto-update with isx upgrades.");
                    try {
                        Files.createDirectories(editPath.getParent());
                        copyBuiltinResource(filename, editPath);
                    } catch (IOException e) {
                        System.err.println("Failed to copy built-in template: " + e.getMessage());
                        return;
                    }
                    isBuiltinCopy = true;
                }
            } else {
                editPath = Path.of(def.getSource());
            }

            editLoop(editPath, name, isBuiltinCopy);
        }

        private void copyBuiltinResource(String filename, Path target) throws IOException {
            try (InputStream is = ImageDef.class.getClassLoader()
                    .getResourceAsStream("images/" + filename)) {
                if (is == null) {
                    throw new IOException("Built-in resource not found: images/" + filename);
                }
                Files.write(target, is.readAllBytes());
            }
        }
    }

    // ── new ─────────────────────────────────────────────────────────────────────

    @Command(name = "new", description = "Create a new template definition",
            mixinStandardHelpOptions = true)
    public static class New implements Runnable {

        @Parameters(index = "0", description = "Template name (e.g. my-app or tpl-my-app)",
                arity = "0..1")
        String name;

        @Option(names = "--project",
                description = "Create in project-local directory (.incus-spawn/images/)")
        boolean project;

        @Override
        public void run() {
            var templateName = name != null ? normalizeName(name) : null;

            var defs = ImageDef.loadAll();
            if (templateName != null && defs.containsKey(templateName)) {
                System.err.println("Template '" + templateName + "' already exists (source: "
                        + defs.get(templateName).getSource() + ").");
                System.err.println("Use 'isx templates edit " + templateName + "' to modify it.");
                return;
            }

            var dir = project ? ImageDef.projectImagesDir() : ImageDef.userImagesDir();
            var filename = templateName != null
                    ? ImageDef.filenameForName(templateName)
                    : "new-template.yaml";
            var targetPath = dir.resolve(filename);

            if (Files.exists(targetPath)) {
                System.err.println("File already exists: " + targetPath);
                return;
            }

            try {
                Files.createDirectories(dir);
                var skeleton = SKELETON.replace("tpl-CHANGEME",
                        templateName != null ? templateName : "tpl-CHANGEME");
                Files.writeString(targetPath, skeleton);
            } catch (IOException e) {
                System.err.println("Failed to create template file: " + e.getMessage());
                return;
            }

            System.out.println("Created " + targetPath);
            editLoop(targetPath, templateName, false);
        }
    }

    // ── shared helpers ──────────────────────────────────────────────────────────

    static String normalizeName(String input) {
        return input.startsWith("tpl-") ? input : "tpl-" + input;
    }

    private static String resolveEditor() {
        var editor = System.getenv("EDITOR");
        if (editor != null && !editor.isBlank()) return editor;
        editor = System.getenv("VISUAL");
        if (editor != null && !editor.isBlank()) return editor;
        return "vi";
    }

    private static int launchEditor(Path file) throws IOException, InterruptedException {
        var editor = resolveEditor();
        var parts = new ArrayList<>(List.of(editor.split("\\s+")));
        parts.add(file.toString());
        var pb = new ProcessBuilder(parts);
        pb.inheritIO();
        return pb.start().waitFor();
    }

    private static boolean validateAndReport(Path file) {
        var defs = ImageDef.loadAll();
        var result = TemplateValidator.validate(file, defs);
        if (result.hasErrors()) {
            System.err.println("Validation errors:");
            result.errors().forEach(e -> System.err.println("  ERROR: " + e));
        }
        if (result.hasWarnings()) {
            result.warnings().forEach(w -> System.out.println("  WARNING: " + w));
        }
        if (!result.hasErrors() && !result.hasWarnings()) {
            System.out.println("Template is valid.");
        }
        return !result.hasErrors();
    }

    private static void editLoop(Path file, String originalName, boolean isBuiltinCopy) {
        while (true) {
            try {
                int exitCode = launchEditor(file);
                if (exitCode != 0) {
                    System.err.println("Warning: editor exited with code " + exitCode);
                }
            } catch (IOException e) {
                System.err.println("Failed to launch editor '" + resolveEditor()
                        + "': " + e.getMessage());
                System.err.println("Set $EDITOR or $VISUAL to your preferred editor.");
                if (isBuiltinCopy) {
                    cleanup(file);
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            boolean valid = validateAndReport(file);

            if (valid && originalName != null) {
                checkNameChange(file, originalName, isBuiltinCopy);
            }

            if (valid) return;

            var console = System.console();
            if (console == null) return;
            System.out.print("Re-edit? (Y/n): ");
            var answer = console.readLine();
            if (answer != null && answer.strip().equalsIgnoreCase("n")) {
                if (isBuiltinCopy) {
                    cleanup(file);
                }
                return;
            }
        }
    }

    private static void checkNameChange(Path file, String originalName, boolean isBuiltinCopy) {
        try {
            var def = ImageDef.parseFile(file);
            if (def.getName() != null && !def.getName().equals(originalName)) {
                System.out.println("WARNING: Template name changed from '" + originalName
                        + "' to '" + def.getName() + "'.");
                if (isBuiltinCopy) {
                    System.out.println("  This file will no longer override the built-in '"
                            + originalName + "' template.");
                }
            }
        } catch (IOException ignored) {
            // validation already reported the parse error
        }
    }

    private static void cleanup(Path file) {
        try {
            Files.deleteIfExists(file);
            System.out.println("Aborted. Override file removed.");
        } catch (IOException ignored) {
        }
    }

    private static final String SKELETON = """
            # Template definition for incus-spawn

            # Required: unique name, conventionally prefixed with 'tpl-'
            name: tpl-CHANGEME

            # Optional: human-readable description
            # description: My custom template

            # Parent template to inherit from (packages, tools, repos are additive)
            # Common parents: tpl-minimal, tpl-dev, tpl-java
            # parent: tpl-dev

            # Base image (only for root templates without a parent)
            # Default: images:fedora/44
            # image: images:fedora/44

            # System packages to install via dnf
            # packages:
            #   - htop
            #   - ripgrep

            # Tool definitions to set up
            # Built-in tools: podman, maven-3, gh, claude
            # tools:
            #   - podman

            # Git repositories to clone
            # repos:
            #   - url: https://github.com/owner/repo
            #     path: ~/repo
            #     branch: main
            #     prime: mvn -B dependency:go-offline

            # Claude Code skills
            # skills:
            #   - owner/skills-repo@skill-name
            """;
}
