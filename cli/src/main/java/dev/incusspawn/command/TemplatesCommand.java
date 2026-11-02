package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.Warnings;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.TemplateValidator;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
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
import java.util.regex.Pattern;
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
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Bare, the command is its list, so it takes list's --format.
    @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
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
        @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
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
                    Warnings.warn("Could not read which templates are built: " + e.getMessage());
                }
                outputFormat.print(BuildOutput.results(), records(defs, built,
                        () -> TemplateStaleness.toolFingerprints(defs.values(), RuntimeServices.toolDefLoader()),
                        BuildInfo.instance().version(), ZoneId.systemDefault()));
                return CommandResult.SUCCESS;
            }
            printTable(BuildOutput.results(), defs, verbose);
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

        @Argument(required = true, completer = Completers.Template.class, description = "Template name (e.g. tpl-minimal)")
        public String name;

        @Override
        public CommandResult doExecute() throws Exception {
            name = normalizeName(name);

            var defs = ImageDef.loadAll();
            var def = defs.get(name);
            if (def == null) {
                BuildOutput.error("Template '" + cell(name) + "' not found.",
                        cell("Available templates: " + String.join(", ", defs.keySet())));
                return CommandResult.valueOf(1);
            }

            boolean isBuiltinCopy = false;
            Path editPath;

            if (def.isBuiltIn()) {
                var filename = ImageDef.filenameForName(name);
                editPath = ImageDef.userImagesDir().resolve(filename);

                if (Files.exists(editPath)) {
                    BuildOutput.step("Editing existing user override: " + editPath);
                } else {
                    BuildOutput.note("Built-in template '" + name + "' cannot be edited directly.");
                    BuildOutput.step("Creating user-level override at " + editPath);
                    BuildOutput.note("This override will take precedence over the built-in"
                            + " and will not auto-update with isx upgrades.");
                    try {
                        Files.createDirectories(editPath.getParent());
                        copyBuiltinResource(filename, editPath);
                    } catch (IOException e) {
                        BuildOutput.error("Failed to copy built-in template: " + e.getMessage());
                        return CommandResult.valueOf(1);
                    }
                    isBuiltinCopy = true;
                }
            } else {
                editPath = Path.of(def.getSource());
            }

            return editLoop(editPath, name, isBuiltinCopy) ? CommandResult.SUCCESS : CommandResult.valueOf(1);
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
                BuildOutput.error("Template '" + cell(templateName) + "' already exists (source: "
                        + cell(defs.get(templateName).getSource()) + ").",
                        "Use 'isx templates edit " + templateName + "' to modify it.");
                return CommandResult.valueOf(1);
            }

            if (templateName == null) {
                var dir = project ? ImageDef.projectImagesDir() : ImageDef.userImagesDir();
                var targetPath = dir.resolve("new-template.yaml");
                if (Files.exists(targetPath)) {
                    BuildOutput.error("File already exists: " + targetPath);
                    return CommandResult.valueOf(1);
                }
                try {
                    Files.createDirectories(dir);
                    Files.writeString(targetPath, SKELETON);
                } catch (IOException e) {
                    BuildOutput.error("Failed to create template file: " + e.getMessage());
                    return CommandResult.valueOf(1);
                }
                BuildOutput.step("Created " + targetPath);
                return editLoop(targetPath, null, false) ? CommandResult.SUCCESS : CommandResult.valueOf(1);
            }

            try {
                var dir = project ? ImageDef.projectImagesDir() : ImageDef.userImagesDir();
                var targetPath = createTemplateFile(templateName, null, dir);
                BuildOutput.step("Created " + targetPath);
                if (!editLoop(targetPath, templateName, false)) return CommandResult.valueOf(1);
            } catch (IOException e) {
                BuildOutput.error("Failed to create template: " + e.getMessage());
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
            skeleton = skeleton.replace("# parent: tpl-minimal", "parent: " + parent);
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
        var parts = new ArrayList<>(List.of(WHITESPACE.split(editor)));
        parts.add(file.toString());
        var pb = new ProcessBuilder(parts);
        pb.inheritIO();
        return pb.start().waitFor();
    }

    /**
     * The findings quote the file's name, parent, host-resource modes and sources, and tools, and
     * a project-local file is checked even when the user leaves it unchanged, so nothing in them
     * reaches the terminal raw (#1133). A warning is one line; an error keeps the line breaks of
     * the advice isx writes into it, and each line is made safe. Each finding is its own error or
     * warning on {@code out}, and a file with none is an {@code ok}.
     * <p>
     * {@code loadWarnings} are what loading {@code defs} reported. Those about this file that the
     * report states again, such as an ignored key, are left to the report, so a key added in this
     * edit is named once; every other one is passed on to {@link Warnings}. They are matched as
     * text, on the {@code <file name>: } prefix {@code ImageDef} gives them: both sides take an
     * ignored key's warning from {@code ImageDef.unknownKeyWarnings()}.
     */
    static boolean validateAndReport(Path file, Map<String, ImageDef> defs, List<String> loadWarnings, Output out) {
        var result = TemplateValidator.validate(file, defs);
        var ownPrefix = OutputFormat.oneLine(file.getFileName().toString()) + ": ";
        for (var warning : loadWarnings) {
            var reported = warning.startsWith(ownPrefix)
                    && result.warnings().contains(warning.substring(ownPrefix.length()));
            if (!reported) Warnings.warn(warning);
        }
        result.errors().forEach(e -> out.error(e.lines().map(OutputFormat::oneLine)
                .collect(Collectors.joining(System.lineSeparator()))));
        result.warnings().forEach(w -> out.warn(cell(w)));
        if (!result.hasErrors() && !result.hasWarnings()) {
            out.ok("Template is valid");
        }
        return !result.hasErrors();
    }

    /** Edits {@code file} until it is valid or the user stops; whether it ended valid. */
    public static boolean editLoop(Path file, String originalName, boolean isBuiltinCopy) {
        return editLoop(file, originalName, isBuiltinCopy, TemplatesCommand::launchEditor, Prompts::terminal);
    }

    /** How a pass of {@link #editLoop} edits the file: the user's editor, or a test's. */
    @FunctionalInterface
    interface Editor {
        int edit(Path file) throws IOException, InterruptedException;
    }

    /** {@link #editLoop} with its editor and where it asks to re-edit, which a test supplies. */
    static boolean editLoop(Path file, String originalName, boolean isBuiltinCopy, Editor editor,
                            java.util.function.Supplier<Prompts> reEdit) {
        while (true) {
            try {
                int exitCode = editor.edit(file);
                if (exitCode != 0) {
                    BuildOutput.warn("Editor exited with code " + exitCode);
                }
            } catch (IOException e) {
                BuildOutput.error("Failed to launch editor '" + resolveEditor() + "': " + e.getMessage(),
                        "Set $EDITOR or $VISUAL to your preferred editor.");
                if (isBuiltinCopy) {
                    cleanup(file);
                }
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            var loadWarnings = new ArrayList<String>();
            var defs = ImageDef.loadAll(loadWarnings::add);
            // On the terminal alone: they are read at the re-edit prompt, and the TUI, which lends
            // the terminal to this loop, would keep every pass's findings, fixed ones included
            boolean valid = validateAndReport(file, defs, loadWarnings, BuildOutput.terminal());

            if (valid && originalName != null) {
                checkNameChange(file, originalName, isBuiltinCopy);
            }

            if (valid) return true;

            // Only on a terminal: piped stdin is not an answer. Its end, like Ctrl-D, stops editing.
            var prompts = reEdit.get();
            if (prompts == null || !askYesNo(prompts, "Re-edit?", true, false)) {
                if (isBuiltinCopy) {
                    cleanup(file);
                }
                return false;
            }
        }
    }

    private static void checkNameChange(Path file, String originalName, boolean isBuiltinCopy) {
        try {
            var def = ImageDef.parseFile(file);
            if (def.getName() != null && !def.getName().equals(originalName)) {
                var renamed = "Template name changed from '" + cell(originalName) + "' to '" + cell(def.getName()) + "'.";
                BuildOutput.warn(renamed, isBuiltinCopy
                        ? new String[] {"This file will no longer override the built-in '" + cell(originalName) + "' template."}
                        : new String[0]);
            }
        } catch (IOException ignored) {
            // validation already reported the parse error
        }
    }

    private static void cleanup(Path file) {
        try {
            Files.deleteIfExists(file);
            BuildOutput.step("Aborted. Override file removed.");
        } catch (IOException ignored) {
        }
    }

    static final String SKELETON = """
            # Template definition for incus-spawn: every field isx reads, one note each.
            # Only 'name' is required; uncomment what you need.

            # Required: unique name, conventionally prefixed with 'tpl-'
            name: tpl-CHANGEME

            # Human-readable description, shown in the TUI and 'isx templates list -v'
            # description: My custom template

            # Parent template to inherit from (packages, tools, repos are additive)
            # parent: tpl-minimal

            # Instance type: container (default), vm, or kvm (a container that branches get
            # /dev/kvm passed into). A template without it inherits its parent's.
            # type: container

            # Base image, for a root template only (one without a parent). The default is
            # images:fedora/44; the other fields fetch a pre-baked tarball instead.
            # image: images:fedora/44                  # Incus image, or the alias a download is stored under
            # image_url: https://example.com/{tag}/fedora-{arch}.tar.xz  # container tarball; {arch}, {tag} filled in
            # image_tag: v1                            # release tag that replaces {tag}
            # image_sha256:                            # SHA256 of the image_url download, per architecture
            #   x86_64: 0000000000000000000000000000000000000000000000000000000000000000
            #   aarch64: 0000000000000000000000000000000000000000000000000000000000000000
            # vm_image_url: https://example.com/{tag}/fedora-{arch}-vm.tar.xz  # VM image, used when type is vm
            # vm_image_sha256:                         # SHA256 of the vm_image_url download, per architecture
            #   x86_64: 0000000000000000000000000000000000000000000000000000000000000000
            #   aarch64: 0000000000000000000000000000000000000000000000000000000000000000
            # pinned: true                             # keep image_tag instead of tracking the newest release

            # Extra package repositories to enable before installing packages
            # package_repos:
            #   - type: copr                           # repository kind; copr is the only one
            #     name: owner/project                  # the repository to enable

            # System packages to install via dnf
            # packages:
            #   - htop
            #   - ripgrep

            # System packages to remove via dnf, before packages are installed
            # remove_packages:
            #   - nano

            # systemd units to mask in the image
            # mask_services:
            #   - packagekit.service

            # Tool definitions to set up (run 'isx tools list' for all available tools)
            # tools:
            #   - podman
            #   - idea-backend: {memory: "8g"}         # a tool with parameters

            # Git repositories to clone as agentuser
            # repos:
            #   - url: https://github.com/owner/repo   # clone URL (HTTPS)
            #     path: ~/repo                         # where to clone it; defaults to ~/<repo name>
            #     branch: main                         # branch or tag to check out; defaults to the repo's default
            #     depth: 1                             # keep only this many commits, on this branch only; omit for all
            #     prime: mvn -B install -DskipTests    # command, or a list of them, run in the clone once cloned
            #   - host-source: ~/work/private-repo      # instead of url; local branches/tags, including unpushed work
            #     path: ~/private-repo                  # trusted templates only; no depth or upstream access

            # Agent skills to bake into the image. Without bare names, a plain list of
            # sources works too: skills: [owner/repo@skill-name]
            # skills:
            #   repo: owner/skills-repo                # catalog that bare skill names are resolved in
            #   list:                                  # owner/repo@skill, owner/repo, ./local-path or a bare name
            #     - owner/skills-repo@skill-name

            # Host files and directories to share with instances
            # host-resources:
            #   - source: ~/.m2/repository             # host path, or a URL with mode copy
            #     path: /home/agentuser/.m2/repository # path in the instance; defaults to the same path under /home/agentuser
            #     mode: overlay                        # readonly (default), overlay or copy

            # Environment variables, written to /etc/profile.d/isx-env.sh
            # env:
            #   - name: MAVEN_OPTS                     # variable name
            #     value: "-Xmx2g"                      # its value
            #     strategy: prepend                    # set (default), set-if-unset, prepend or append
            #     separator: " "                       # between the value and the existing one, for prepend and append

            # Credential account per namespace, overriding that namespace's default in config.yaml
            # accounts:
            #   claude: work
            #   github: acme-bot

            # GUI passthrough by default for container branches in a Wayland session (never
            # for a project-local template); 'isx branch --no-gui' opts out
            # gui: true

            # Branches are coordinators that drive isx over MCP, controlling these templates of
            # those mcp.yaml approves (never for a project-local template). The capability is on
            # by default when control-over lists any; 'isx branch --no-mcp-client' opts out
            # coordinator:
            #   isx-control-capability: true
            #   control-over: [tpl-dev]

            # Directory a shell starts in; inherited from the parent, else the first repo
            # workdir: ~/repo

            # Command to run instead of the login shell
            # shell-command: claude

            # Tool action that Enter in the TUI and 'isx run' start: a tool, or tool:action-id
            # default-action: claude

            # Always-true fact an agent must know before acting. It costs tokens in
            # every session and the agent follows it in every session, including tasks
            # it wasn't written for, so add one only when leaving it out would cause a
            # wrong action. Descriptions, lists of installed tools (already generated)
            # and procedures don't belong here -- put procedures in a skill.
            # agent_note: |
            #   The build needs a boot JDK of 26/27/28; this box ships 26 and omits 25.

            # Marks the template as on its way out: true, or what to use instead.
            # It still builds, but builds of it and of its children warn.
            # deprecated: use tpl-other instead
            """;

    private static String cell(Object value) {
        return OutputFormat.oneLine(String.valueOf(value));
    }
}
