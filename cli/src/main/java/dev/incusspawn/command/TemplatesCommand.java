package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.TemplateValidator;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@CommandDefinition(
        name = "templates",
        description = "Manage template definitions",
        generateHelp = true,
        groupCommands = {
                TemplatesCommand.ListSub.class,
                TemplatesCommand.Edit.class,
                TemplatesCommand.New.class
        }
)
public class TemplatesCommand extends BaseCommand {

    // Bare, the command is its list, so it takes list's --format.
    @Option(name = "format", description = "Output format: table (default), plain or json")
    String format;

    @Override
    protected CommandResult doExecute() throws Exception {
        var list = new ListSub();
        list.format = format;
        return list.doExecute();
    }

    // ── list ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "list", description = "List available template names",
            generateHelp = true)
    public static class ListSub extends BaseCommand {

        @Option(shortName = 'v', name = "verbose", description = "Show source and description",
                hasValue = false)
        boolean verbose;

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", description = "Output format: table (default), plain or json")
        String format;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            var defs = ImageDef.loadAll();
            if (outputFormat != OutputFormat.TABLE) {
                // The definitions are listed even when Incus cannot say which are built: those
                // fields are then null, never "not built", and the reason goes to stderr.
                List<TemplateStaleness.Built> built = null;
                try {
                    built = InstanceListing.builtTemplates(RuntimeServices.incus().listJsonConfig());
                } catch (IncusException e) {
                    // Only Incus failing to answer is "unknown"; anything else is a bug, reported as one.
                    System.err.println("Could not read which templates are built: " + e.getMessage());
                }
                outputFormat.print(System.out, records(defs, built,
                        () -> TemplateStaleness.toolFingerprints(defs.values(), RuntimeServices.toolDefLoader()),
                        BuildInfo.instance().version(), ZoneId.systemDefault()));
                return CommandResult.SUCCESS;
            }
            printTable(System.out, defs, verbose);
            return CommandResult.SUCCESS;
        }

        /**
         * The {@code table} format: the names, or with {@code verbose} a name, source and
         * description per row. A project-local template ships with a cloned repository, so its
         * text is shown as {@code --format=plain} shows it: nothing in it can drive the terminal (#1133).
         */
        static void printTable(PrintStream out, Map<String, ImageDef> defs, boolean verbose) {
            if (!verbose) {
                defs.keySet().forEach(name -> out.println(cell(name)));
                return;
            }
            int maxName = defs.keySet().stream().mapToInt(String::length).max().orElse(10);
            int maxSource = defs.values().stream().mapToInt(d -> d.getSource().length()).max().orElse(7);
            var fmt = "%-" + maxName + "s  %-" + maxSource + "s  %s%n";
            out.printf(fmt, "NAME", "SOURCE", "DESCRIPTION");
            for (var entry : defs.entrySet()) {
                var def = entry.getValue();
                out.printf(fmt, cell(entry.getKey()), cell(def.getSource()), cell(def.getDescription()));
            }
        }

        /**
         * The fields of {@code isx templates --format=plain|json}, in order: add to the end, never
         * rename. {@code built_at} is ISO-8601; the three staleness flags are the TUI's
         * {@code ! △ ↑} ({@link TemplateStaleness}) and {@code null} for a template not built.
         * Everything from {@code built} on is {@code null} when {@code built} (the built templates
         * from one Incus listing) is: Incus could not be asked.
         */
        static List<Map<String, Object>> records(Map<String, ImageDef> defs, List<TemplateStaleness.Built> built,
                                                 Supplier<Map<String, String>> toolFingerprints,
                                                 String currentVersion, ZoneId zone) {
            var builtByName = new LinkedHashMap<String, TemplateStaleness.Built>();
            if (built != null) built.forEach(b -> builtByName.put(b.name(), b));
            var staleness = TemplateStaleness.assess(builtByName.values(), defs, Set.of(),
                    toolFingerprints, currentVersion);
            var records = new ArrayList<Map<String, Object>>();
            defs.forEach((name, def) -> {
                var record = new LinkedHashMap<String, Object>();
                record.put("name", name);
                record.put("parent", def.getParent());
                record.put("source", def.getSource());
                record.put("description", def.getDescription());
                var b = builtByName.get(name);
                var stale = staleness.get(name);
                record.put("built", built == null ? null : b != null);
                record.put("built_at", b == null ? null : Metadata.createdIso(b.created(), zone));
                record.put("version_outdated", stale == null ? null : stale.versionOutdated());
                record.put("definition_changed", stale == null ? null : stale.definitionChanged());
                record.put("parent_rebuilt", stale == null ? null : stale.parentRebuilt());
                records.add(record);
            });
            return records;
        }
    }

    // ── edit ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "edit", description = "Edit a template definition in your editor",
            generateHelp = true)
    public static class Edit extends BaseCommand {

        @Argument(required = true, description = "Template name (e.g. tpl-java)")
        public String name;

        @Override
        public CommandResult doExecute() throws Exception {
            name = normalizeName(name);

            var defs = ImageDef.loadAll();
            var def = defs.get(name);
            if (def == null) {
                System.err.println("Template '" + name + "' not found.");
                System.err.println(cell("Available templates: " + String.join(", ", defs.keySet())));
                return CommandResult.valueOf(1);
            }

            boolean isBuiltinCopy = false;
            Path editPath;

            if (def.isBuiltIn()) {
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
                        return CommandResult.valueOf(1);
                    }
                    isBuiltinCopy = true;
                }
            } else {
                editPath = Path.of(def.getSource());
            }

            editLoop(editPath, name, isBuiltinCopy);
            return CommandResult.SUCCESS;
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

    @CommandDefinition(name = "new", description = "Create a new template definition",
            generateHelp = true)
    public static class New extends BaseCommand {

        @Argument(required = false, description = "Template name (e.g. my-app or tpl-my-app)")
        String name;

        @Option(name = "project",
                description = "Create in project-local directory (.incus-spawn/images/)",
                hasValue = false)
        boolean project;

        @Override
        protected CommandResult doExecute() throws Exception {
            var templateName = name != null ? normalizeName(name) : null;

            var defs = ImageDef.loadAll();
            if (templateName != null && defs.containsKey(templateName)) {
                System.err.println("Template '" + templateName + "' already exists (source: "
                        + cell(defs.get(templateName).getSource()) + ").");
                System.err.println("Use 'isx templates edit " + templateName + "' to modify it.");
                return CommandResult.valueOf(1);
            }

            if (templateName == null) {
                var dir = project ? ImageDef.projectImagesDir() : ImageDef.userImagesDir();
                var targetPath = dir.resolve("new-template.yaml");
                if (Files.exists(targetPath)) {
                    System.err.println("File already exists: " + targetPath);
                    return CommandResult.valueOf(1);
                }
                try {
                    Files.createDirectories(dir);
                    Files.writeString(targetPath, SKELETON);
                } catch (IOException e) {
                    System.err.println("Failed to create template file: " + e.getMessage());
                    return CommandResult.valueOf(1);
                }
                System.out.println("Created " + targetPath);
                editLoop(targetPath, null, false);
                return CommandResult.SUCCESS;
            }

            try {
                var dir = project ? ImageDef.projectImagesDir() : ImageDef.userImagesDir();
                var targetPath = createTemplateFile(templateName, null, dir);
                System.out.println("Created " + targetPath);
                editLoop(targetPath, templateName, false);
            } catch (IOException e) {
                System.err.println("Failed to create template: " + e.getMessage());
                return CommandResult.valueOf(1);
            }
            return CommandResult.SUCCESS;
        }
    }

    // ── shared helpers ──────────────────────────────────────────────────────────

    public static Path createTemplateFile(String name, String parent, Path dir) throws IOException {
        var targetPath = dir.resolve(ImageDef.filenameForName(name));
        if (Files.exists(targetPath)) {
            throw new IOException("File already exists: " + targetPath);
        }
        Files.createDirectories(dir);
        var skeleton = SKELETON.replace("tpl-CHANGEME", name);
        if (parent != null && !parent.isEmpty()) {
            skeleton = skeleton.replace("# parent: tpl-dev", "parent: " + parent);
        }
        Files.writeString(targetPath, skeleton);
        return targetPath;
    }

    public static String normalizeName(String input) {
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

    /**
     * The findings quote the file's name, parent, host-resource modes and sources, and tools, and
     * a project-local file is checked even when the user leaves it unchanged, so nothing in them
     * reaches the terminal raw (#1133). A warning is one line; an error keeps the line breaks of
     * the advice isx writes into it, and each line is made safe.
     */
    static boolean validateAndReport(Path file, Map<String, ImageDef> defs, PrintStream out, PrintStream err) {
        var result = TemplateValidator.validate(file, defs);
        if (result.hasErrors()) {
            err.println("Validation errors:");
            result.errors().forEach(e -> err.println("  ERROR: " + e.lines().map(OutputFormat::oneLine)
                    .collect(Collectors.joining(System.lineSeparator()))));
        }
        if (result.hasWarnings()) {
            result.warnings().forEach(w -> out.println("  WARNING: " + cell(w)));
        }
        if (!result.hasErrors() && !result.hasWarnings()) {
            out.println("Template is valid.");
        }
        return !result.hasErrors();
    }

    public static void editLoop(Path file, String originalName, boolean isBuiltinCopy) {
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

            boolean valid = validateAndReport(file, ImageDef.loadAll(), System.out, System.err);

            if (valid && originalName != null) {
                checkNameChange(file, originalName, isBuiltinCopy);
            }

            if (valid) return;

            var console = System.console();
            if (console == null) return;
            if (!askConfirmation(console, "Re-edit?", true, true)) {
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
                System.out.println("WARNING: Template name changed from '" + cell(originalName)
                        + "' to '" + cell(def.getName()) + "'.");
                if (isBuiltinCopy) {
                    System.out.println("  This file will no longer override the built-in '"
                            + cell(originalName) + "' template.");
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

            # Tool definitions to set up (run 'isx tools list' for all available tools)
            # tools:
            #   - podman

            # Git repositories to clone
            # repos:
            #   - url: https://github.com/owner/repo
            #     path: ~/repo
            #     branch: main
            #     prime: mvn -B install -DskipTests

            # Claude Code skills
            # skills:
            #   - owner/skills-repo@skill-name

            # Always-true fact an agent must know before acting. It costs tokens in
            # every session and the agent follows it in every session, including tasks
            # it wasn't written for, so add one only when leaving it out would cause a
            # wrong action. Descriptions, lists of installed tools (already generated)
            # and procedures don't belong here -- put procedures in a skill.
            # agent_note: |
            #   The build needs a boot JDK of 26/27/28; this box ships 26 and omits 25.
            """;

    private static String cell(Object value) {
        return OutputFormat.oneLine(String.valueOf(value));
    }
}
