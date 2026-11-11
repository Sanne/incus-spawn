package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.ClientLog;
import dev.incusspawn.Environment;
import dev.incusspawn.OwnDirectory;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.Warnings;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.config.TemplateFinding;
import dev.incusspawn.config.TemplateReach;
import dev.incusspawn.config.TemplateValidator;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
                TemplatesCommand.New.class,
                TemplatesCommand.Validate.class
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

    @CommandDefinition(name = "list", description = "List available templates and what each is for",
            generateHelp = true)
    public static class ListSub extends BaseCommand {

        @Option(shortName = 'v', name = "verbose", description = "Also show where each definition comes from",
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
         * The {@code table} format: a name and description per row, which says which to build (#1441),
         * and with {@code verbose} the source as well. A project-local template ships with a cloned
         * repository, so its text is shown as {@code --format=plain} shows it: nothing in it can drive
         * the terminal (#1133).
         */
        static void printTable(PrintStream out, Map<String, ImageDef> defs, boolean verbose) {
            int maxName = Math.max(4, defs.keySet().stream().mapToInt(name -> cell(name).length()).max().orElse(0));
            int maxSource = Math.max(6, defs.values().stream().mapToInt(d -> cell(d.getSource()).length()).max().orElse(0));
            var fmt = verbose ? "%-" + maxName + "s  %-" + maxSource + "s  %s%n" : "%-" + maxName + "s  %s%n";
            if (verbose) out.printf(fmt, "NAME", "SOURCE", "DESCRIPTION");
            else out.printf(fmt, "NAME", "DESCRIPTION");
            for (var entry : defs.entrySet()) {
                var def = entry.getValue();
                var description = cell(java.util.Objects.toString(def.getDescription(), ""));
                if (verbose) out.printf(fmt, cell(entry.getKey()), cell(def.getSource()), description);
                else out.printf(fmt, cell(entry.getKey()), description);
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

        /** The editor and where it asks to re-edit, which a test replaces. */
        Editor editor = TemplatesCommand::launchEditor;
        Supplier<Prompts> reEdit = Prompts::terminal;

        @Override
        public CommandResult doExecute() throws Exception {
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
                // The override keeps the built-in's filename (tpl-java is java.yaml), as older isx wrote it
                var filename = ImageDef.builtinFilenameFor(name);
                if (filename == null) {
                    BuildOutput.error("Built-in template '" + cell(name) + "' has no definition file.");
                    return CommandResult.valueOf(1);
                }
                editPath = ImageDef.userImagesDir().resolve(filename);

                if (Files.exists(editPath)) {
                    // A file of that name that defines another template is not the override; one that
                    // cannot be read, or names nothing, is a broken override the user needs to fix
                    var overrides = overrideName(editPath);
                    if (overrides != null && !name.equals(overrides)) {
                        BuildOutput.error("'" + editPath + "' is not an override of '" + cell(name)
                                        + "': it defines '" + cell(overrides) + "'.",
                                "Rename that file to edit the built-in '" + cell(name) + "'.");
                        return CommandResult.valueOf(1);
                    }
                    BuildOutput.step("Editing existing user override: " + editPath);
                } else {
                    BuildOutput.note("Built-in template '" + name + "' cannot be edited directly.");
                    BuildOutput.step("Creating user-level override at " + editPath);
                    BuildOutput.note("This override will take precedence over the built-in"
                            + " and will not auto-update with isx upgrades.");
                    try {
                        OwnDirectory.create(editPath.getParent());
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

            return editLoop(editPath, name, isBuiltinCopy, editor, reEdit) ? CommandResult.SUCCESS : CommandResult.valueOf(1);
        }

        /** The name the file at {@code path} defines, or null when it cannot be read or names none. */
        private static String overrideName(Path path) {
            try {
                return ImageDef.parseFile(path).getName();
            } catch (IOException | RuntimeException e) {
                return null;
            }
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

        @Argument(required = false, description = "Template name, used as given (e.g. tpl-my-app)")
        String name;

        @Option(name = "project",
                description = "Create in project-local directory (.incus-spawn/images/)",
                hasValue = false)
        boolean project;

        @Option(name = "from",
                description = "Take the whole definition from this file ('-' for stdin): check it and store it, with no editor")
        String from;

        @Option(name = "replace", hasValue = false,
                description = "With --from, replace the template's file when it already has one")
        boolean replace;

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", completer = Completers.Format.class,
                description = "With --from, output format: table (default), plain or json, with the check's findings")
        String format;

        /** Where {@code --from -} reads, which a test replaces. */
        Supplier<InputStream> stdin = () -> System.in;

        @Override
        protected CommandResult doExecute() throws Exception {
            var templateName = name;
            if (from == null && (replace || format != null)) {
                BuildOutput.error((replace ? "--replace" : "--format") + " needs --from.");
                return CommandResult.valueOf(1);
            }
            if (from != null) {
                if (templateName == null) {
                    BuildOutput.error("--from needs a template name.",
                            "For example: isx templates new tpl-agent --from tpl-agent.yaml");
                    return CommandResult.valueOf(1);
                }
                return fromDefinition(templateName);
            }
            var invalid = templateName == null ? null : newTemplateNameProblem(templateName);
            if (invalid != null) {
                BuildOutput.error(invalid);
                return CommandResult.valueOf(1);
            }

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
                    makeDirectory(dir);
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

        /**
         * {@code --from}: the whole definition, checked by the editor's check, stored only when it
         * has no error, never with a prompt or an editor (#2076).
         */
        private CommandResult fromDefinition(String templateName) throws IOException {
            var outputFormat = OutputFormat.parse(format);
            String yaml;
            try {
                yaml = readDefinition(from, stdin);
            } catch (IOException e) {
                BuildOutput.error("Could not read " + ("-".equals(from) ? "stdin" : "'" + cell(from) + "'")
                        + ": " + cell(e.getMessage()));
                return CommandResult.valueOf(1);
            }
            var dir = project ? ImageDef.projectImagesDir() : ImageDef.userImagesDir();
            var target = dir.resolve(ImageDef.filenameForName(templateName));
            var defs = ImageDef.loadAll();
            var check = new ArrayList<TemplateFinding>();
            var refused = placeProblem(templateName, target, defs, replace);
            if (refused != null) check.add(TemplateFinding.error("name", refused));
            var result = TemplateValidator.validate(yaml, target, defs);
            check.addAll(result.findings());
            var named = result.name();
            if (named != null && !named.equals(templateName)) {
                check.add(TemplateFinding.error("name", "The definition is named '" + named
                        + "', not '" + templateName + "': the two must agree"));
            }
            var valid = check.stream().noneMatch(TemplateFinding::isError);
            if (valid) check.addAll(credentialFindings(() -> ImageDef.parseYaml(yaml), defs));
            var written = false;
            if (valid) {
                try {
                    store(target, yaml, replace);
                    written = true;
                } catch (IOException e) {
                    check.add(TemplateFinding.error(null, "Could not write " + target + ": " + e.getMessage()));
                    valid = false;
                }
            }
            if (outputFormat != OutputFormat.TABLE) {
                printCheck(outputFormat, result.name(), target, valid, written, check, result.reach());
            } else {
                reportCheck(check, result.reach(), BuildOutput.bound());
                if (written) {
                    BuildOutput.step("Created " + target);
                    BuildOutput.note("Build it with 'isx build " + templateName + "'");
                }
            }
            return written ? CommandResult.SUCCESS : CommandResult.valueOf(1);
        }
    }

    // ── validate ────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "validate", description = "Check a template definition without storing or building it",
            generateHelp = true)
    public static class Validate extends BaseCommand {

        @Argument(required = true, completer = Completers.Template.class,
                description = "A definition file ('-' for stdin), or a template's name")
        String target;

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
        String format;

        /** Where {@code -} reads, which a test replaces. */
        Supplier<InputStream> stdin = () -> System.in;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            var defs = ImageDef.loadAll();
            Path location;
            String yaml;
            try {
                // Any file but a directory: process substitution's /dev/fd/63 is a pipe
                var path = "-".equals(target) ? null : Path.of(target);
                if (path == null || Files.exists(path) && !Files.isDirectory(path)) {
                    yaml = readDefinition(target, stdin);
                    location = path == null ? ImageDef.userImagesDir().resolve("stdin.yaml") : path;
                } else if (defs.containsKey(target)) {
                    var def = defs.get(target);
                    if (def.isBuiltIn()) {
                        var filename = ImageDef.builtinFilenameFor(target);
                        try (var is = ImageDef.class.getClassLoader().getResourceAsStream("images/" + filename)) {
                            yaml = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                        location = Path.of(filename);
                    } else {
                        location = Path.of(def.getSource());
                        yaml = readDefinition(def.getSource(), stdin);
                    }
                } else {
                    BuildOutput.error("No template or file named '" + cell(target) + "'.",
                            "Run 'isx templates list' to see the templates.");
                    return CommandResult.valueOf(1);
                }
            } catch (IOException | java.nio.file.InvalidPathException e) {
                BuildOutput.error("Could not read '" + cell(target) + "': " + cell(e.getMessage()));
                return CommandResult.valueOf(1);
            }
            var result = TemplateValidator.validate(yaml, location, defs);
            var valid = !result.hasErrors();
            var findings = new ArrayList<>(result.findings());
            if (valid) findings.addAll(credentialFindings(() -> ImageDef.parseYaml(yaml), defs));
            if (outputFormat != OutputFormat.TABLE) {
                printCheck(outputFormat, result.name(), location, valid, false, findings, result.reach());
            } else {
                reportCheck(findings, result.reach(), BuildOutput.bound());
            }
            return valid ? CommandResult.SUCCESS : CommandResult.valueOf(1);
        }
    }

    // ── the shared check and write (#2076) ──────────────────────────────────────

    /** The most a definition read by {@code --from} or {@code validate} may hold. */
    static final int MAX_DEFINITION_BYTES = 1 << 20;

    /** A definition's text, from a file or ({@code -}) stdin, refused when it is larger than any template. */
    static String readDefinition(String from, Supplier<InputStream> stdin) throws IOException {
        try (var in = "-".equals(from) ? stdin.get() : Files.newInputStream(Path.of(from))) {
            var bytes = in.readNBytes(MAX_DEFINITION_BYTES + 1);
            if (bytes.length > MAX_DEFINITION_BYTES) {
                throw new IOException("larger than " + (MAX_DEFINITION_BYTES >> 10) + " KiB, which no template is");
            }
            try {
                // Strict, as the loader is: a lenient decode would store U+FFFD in place of what was given
                return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new IOException(TemplateValidator.NOT_UTF8, e);
            }
        }
    }

    /**
     * A warning, on {@code tools}, for each credential the definition's tools need that is not
     * configured: the definition is taken, but {@code isx branch} refuses its instances until the
     * command it names has run, as the build also warns (#1438). So the commands run before the
     * branch name the gap themselves (#2163). It reads the host's config, not the definition
     * alone, so it is added here rather than in {@link TemplateValidator}; and only to a
     * definition with no error, which parses and has a name. An account pin with no such account
     * is instead one warning on {@code accounts}, since the build refuses it. Only advice: whatever
     * stops it, it says nothing.
     */
    static List<TemplateFinding> credentialFindings(ImageDefSource definition, Map<String, ImageDef> defs) {
        try {
            var def = definition.read();
            var chain = new java.util.HashMap<>(defs);
            chain.put(def.getName(), def);
            // An account pin with no such account, which the build refuses and the credential check skips
            var unknownAccount = BuildCommand.validateTemplateAccounts(List.of(def.getName()), chain);
            if (unknownAccount != null) return List.of(TemplateFinding.warning("accounts", unknownAccount));
            var config = SpawnConfig.load();
            return CredentialCheck.missingByCredential(config, List.of(def.getName()), chain,
                            new ToolDefLoader(config.getSearchPaths()))
                    .keySet().stream()
                    .map(missing -> {
                        var said = new ArrayList<String>();
                        said.add(BuildCommand.unbranchable(missing, "this template"));
                        said.addAll(missing.notes());
                        return TemplateFinding.warning("tools", String.join(" ", said));
                    })
                    .toList();
        } catch (IOException | RuntimeException e) {
            ClientLog.warn("Missing-credential check skipped: " + e);
            return List.of();
        }
    }

    /** Where {@link #credentialFindings} reads the definition it checks. */
    @FunctionalInterface
    interface ImageDefSource {
        ImageDef read() throws IOException;
    }

    /**
     * Why {@code name} cannot be stored at {@code target}, or null when it can: the name must be
     * one a new template may have, and a template or file already there is replaced only with
     * {@code replace}, and only when it is that file. A template defined elsewhere (a built-in,
     * the other layer) is never shadowed by accident.
     */
    static String placeProblem(String name, Path target, Map<String, ImageDef> defs, boolean replace) {
        var existing = defs.get(name);
        var here = existing != null && existing.getSource() != null && !existing.isBuiltIn()
                && Path.of(existing.getSource()).toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize());
        if (existing != null && !here) {
            return "Template '" + name + "' already exists (source: " + existing.getSource() + ")"
                    + (replace ? "; --replace replaces only " + target : "");
        }
        if ((here || Files.exists(target)) && !replace) {
            return "File already exists: " + target + "; add --replace to replace it";
        }
        var absolute = target.toAbsolutePath().normalize();
        var other = defs.values().stream()
                .filter(d -> d.getSource() != null && !d.isBuiltIn() && !name.equals(d.getName())
                        && Path.of(d.getSource()).toAbsolutePath().normalize().equals(absolute))
                .findFirst();
        if (other.isPresent()) {
            return "File " + target + " defines template '" + other.get().getName()
                    + "', which --replace would remove";
        }
        if (here) return null;
        return newTemplateNameProblem(name);
    }

    /**
     * Makes {@code dir}: in the config directory without group write whatever the umask, as every
     * writer there does (#2136); in a project, as its umask says, since its group may share it.
     */
    private static void makeDirectory(Path dir) throws IOException {
        if (dir.toAbsolutePath().normalize().startsWith(Environment.configDir())) {
            OwnDirectory.create(dir);
        } else {
            Files.createDirectories(dir);
        }
    }

    /**
     * Writes {@code yaml} as {@code target}, the one way a new definition is stored: created
     * exclusively, so nothing is overwritten, or with {@code replace} swapped in whole through a
     * file the loader never reads, so no reader sees half a definition.
     */
    static void store(Path target, String yaml, boolean replace) throws IOException {
        makeDirectory(target.getParent());
        if (!replace) {
            Files.writeString(target, yaml, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return;
        }
        var partial = target.resolveSibling("." + target.getFileName() + ".new-" + ProcessHandle.current().pid());
        try {
            // Exclusively: a link planted at this name (a project's directory is a clone's) is never followed
            Files.writeString(partial, yaml, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /**
     * The check for a person: each finding as the editor reports it, then what the definition
     * reaches beyond its instance, which the person reviews.
     */
    static boolean reportCheck(List<TemplateFinding> findings, List<TemplateReach> reach, Output out) {
        var valid = reportFindings(findings, out);
        if (!reach.isEmpty()) {
            out.warn("It reaches beyond its instance; review before building:",
                    reach.stream().map(r -> cell(r.field() + ": " + r.detail())).toArray(String[]::new));
        }
        return valid;
    }

    /** Errors, then warnings, or that it is valid; whether it has no error. */
    private static boolean reportFindings(List<TemplateFinding> findings, Output out) {
        var errors = findings.stream().filter(TemplateFinding::isError).toList();
        var warnings = findings.stream().filter(f -> !f.isError()).toList();
        errors.forEach(e -> out.error(e.message().lines().map(OutputFormat::oneLine)
                .collect(Collectors.joining(System.lineSeparator()))));
        warnings.forEach(w -> out.warn(cell(w.message())));
        if (errors.isEmpty() && warnings.isEmpty()) {
            out.ok("Template is valid");
        }
        return errors.isEmpty();
    }

    /**
     * The check for a script. {@code json}: one object, its fields in order (add to the end, never
     * rename): {@code name}, {@code path}, {@code valid}, {@code written}, {@code findings} (each
     * {@code severity}, {@code field}, {@code message}) and {@code reach} (each {@code kind},
     * {@code field}, {@code detail}). {@code plain}: one line per finding, {@code <severity> <field>
     * <message>}, then one per reach, {@code reach <field> <detail>}, tab-separated.
     */
    static void printCheck(OutputFormat format, String name, Path path, boolean valid, boolean written,
                           List<TemplateFinding> findings, List<TemplateReach> reach) {
        if (format == OutputFormat.PLAIN) {
            var lines = new ArrayList<Map<String, Object>>();
            for (var f : findings) {
                var line = new LinkedHashMap<String, Object>();
                line.put("kind", f.severity().label());
                line.put("field", f.field());
                line.put("text", f.message());
                lines.add(line);
            }
            for (var r : reach) {
                var line = new LinkedHashMap<String, Object>();
                line.put("kind", "reach");
                line.put("field", r.field());
                line.put("text", r.detail());
                lines.add(line);
            }
            format.print(BuildOutput.results(), lines);
            return;
        }
        var record = new LinkedHashMap<String, Object>();
        record.put("name", name);
        record.put("path", path.toString());
        record.put("valid", valid);
        record.put("written", written);
        record.put("findings", findings.stream().map(TemplateFinding::record).toList());
        record.put("reach", reach.stream().map(TemplateReach::record).toList());
        format.printOne(BuildOutput.results(), record);
    }

    // ── shared helpers ──────────────────────────────────────────────────────────

    public static Path createTemplateFile(String name, String parent, Path dir) throws IOException {
        // The name becomes the filename, so it must be one a template may have before it touches the disk
        var invalid = newTemplateNameProblem(name);
        if (invalid != null) throw new IOException(invalid);
        var targetPath = dir.resolve(ImageDef.filenameForName(name));
        if (Files.exists(targetPath)) {
            throw new IOException("File already exists: " + targetPath);
        }
        var skeleton = SKELETON.replace("tpl-CHANGEME", name);
        if (parent != null && !parent.isEmpty()) {
            skeleton = skeleton.replace("# parent: tpl-minimal", "parent: " + parent);
        }
        try {
            store(targetPath, skeleton, false);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new IOException("File already exists: " + targetPath);
        }
        return targetPath;
    }

    /**
     * Why {@code name} cannot be a new template's, or null when it can: it must be a valid instance
     * name short enough to rebuild, and its file must not be the one a built-in's override uses
     * ({@code dev} would be {@code dev.yaml}, which {@code isx templates edit tpl-dev} opens).
     */
    public static String newTemplateNameProblem(String name) {
        var invalid = ImageDef.notAnInstanceName("Template name", name);
        if (invalid != null) return invalid;
        if (name.length() > BuildCommand.MAX_TEMPLATE_NAME_LENGTH) {
            return "Template name '" + name + "' is too long: a template is rebuilt as '<name>"
                    + BuildCommand.REBUILDING_SUFFIX + "', so its name may have at most "
                    + BuildCommand.MAX_TEMPLATE_NAME_LENGTH + " characters";
        }
        var filename = ImageDef.filenameForName(name);
        // Case-blind: on a case-insensitive filesystem (macOS by default) Dev.yaml is dev.yaml
        if (ImageDef.BUILTIN_FILES.stream().anyMatch(filename::equalsIgnoreCase)) {
            return "Template name '" + name + "' would be written to " + filename
                    + ", the file a built-in template's override uses: choose another name";
        }
        return null;
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
            var own = warning.startsWith(ownPrefix) ? warning.substring(ownPrefix.length()) : null;
            // A refusal the loader warned about is the check's error too (a repo it skips the file for)
            var reported = own != null && (result.warnings().contains(own)
                    || own.endsWith(dev.incusspawn.config.LayeredDefinitions.REFUSED)
                    && result.errors().contains(own.substring(0, own.length() - dev.incusspawn.config.LayeredDefinitions.REFUSED.length())));
            if (!reported) Warnings.warn(warning);
        }
        var findings = new ArrayList<>(result.findings());
        if (!result.hasErrors()) findings.addAll(credentialFindings(() -> ImageDef.parseFile(file), defs));
        return reportFindings(findings, out);
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

            # Human-readable description, shown in the TUI and 'isx templates list'
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

            # Tool action that Enter in the TUI and 'isx run' start: a tool, or tool:action-id,
            # or - for a shell instead of the parent's
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
