package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolDefValidator;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

@CommandDefinition(
        name = "tools",
        description = "List and inspect available tool definitions",
        generateHelp = true,
        groupCommands = {
                ToolsCommand.ListSub.class,
                ToolsCommand.Show.class,
                ToolsCommand.Validate.class
        }
)
public class ToolsCommand extends BaseCommand {

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

    @CommandDefinition(name = "list", description = "List available tools",
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
            var loader = RuntimeServices.toolDefLoader();
            var tools = new TreeMap<>(loader.allToolSetups());
            if (outputFormat != OutputFormat.TABLE) {
                outputFormat.print(BuildOutput.results(), records(tools, loader::getSource));
                return CommandResult.SUCCESS;
            }
            printTable(BuildOutput.results(), tools, loader::getSource, verbose);
            return CommandResult.SUCCESS;
        }

        /** The {@code table} format: the names, or with {@code verbose} a name, source and description per row. */
        static void printTable(PrintStream out, Map<String, ToolSetup> tools,
                               UnaryOperator<String> sourceOf, boolean verbose) {
            if (!verbose) {
                tools.keySet().forEach(name -> out.println(cell(name)));
                return;
            }
            int maxName = tools.keySet().stream().mapToInt(String::length).max().orElse(10);
            int maxSource = tools.keySet().stream()
                    .mapToInt(name -> sourceOf.apply(name).length())
                    .max().orElse(7);
            var fmt = "%-" + maxName + "s  %-" + maxSource + "s  %s%n";
            out.printf(fmt, "NAME", "SOURCE", "DESCRIPTION");
            for (var entry : tools.entrySet()) {
                out.printf(fmt, cell(entry.getKey()),
                        cell(sourceOf.apply(entry.getKey())), cell(entry.getValue().description()));
            }
        }

        /** The fields of {@code isx tools list --format=plain|json}, in order: add to the end, never rename. */
        static List<Map<String, Object>> records(Map<String, ToolSetup> tools,
                                                 UnaryOperator<String> sourceOf) {
            var records = new ArrayList<Map<String, Object>>();
            tools.forEach((name, tool) -> {
                var record = new LinkedHashMap<String, Object>();
                record.put("name", name);
                record.put("source", sourceOf.apply(name));
                record.put("description", tool.description());
                records.add(record);
            });
            return records;
        }
    }

    // ── show ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "show", description = "Show details of a tool definition",
            generateHelp = true)
    public static class Show extends BaseCommand {

        @Argument(required = true, completer = Completers.Tool.class, description = "Tool name")
        String name;

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
        String format;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            var loader = RuntimeServices.toolDefLoader();
            var tools = loader.allToolSetups();
            var tool = tools.get(name);
            if (tool == null) {
                BuildOutput.error("Tool '" + cell(name) + "' not found.", cell("Available tools: " + String.join(", ",
                        new TreeMap<>(tools).keySet())));
                return CommandResult.valueOf(1);
            }
            if (outputFormat != OutputFormat.TABLE) {
                outputFormat.printOne(BuildOutput.results(), record(tool, loader.getSource(name)));
                return CommandResult.SUCCESS;
            }

            print(BuildOutput.results(), tool, loader.getSource(name));
            return CommandResult.SUCCESS;
        }

        /**
         * The fields of {@code isx tools show --format=plain|json}, in order: add to the end,
         * never rename. Lists name what the table details; {@code proxy_domains} are the
         * domains whose credentials the proxy injects for the tool.
         */
        static Map<String, Object> record(ToolSetup tool, String source) {
            var record = new LinkedHashMap<String, Object>();
            record.put("name", tool.name());
            record.put("description", tool.description());
            record.put("source", source);
            record.put("feature", tool.feature());
            record.put("requires", List.copyOf(tool.requires()));
            record.put("packages", List.copyOf(tool.packages()));
            record.put("parameters", List.copyOf(tool.parameters().keySet()));
            record.put("actions", tool.actions().stream().map(a -> a.getLabel()).toList());
            record.put("downloads", tool instanceof YamlToolSetup yaml
                    ? yaml.toolDef().getDownloads().stream().map(d -> d.getUrl()).toList() : List.of());
            var domains = new ArrayList<String>();
            var proxy = tool.proxy();
            if (proxy != null) {
                for (var auth : proxy.getAuth()) {
                    domains.addAll(auth.getDomains());
                }
            }
            record.put("proxy_domains", domains);
            return record;
        }

        /** The {@code table} format: the tool's details, one per line. */
        static void print(PrintStream out, ToolSetup tool, String source) {
            line(out, tool.name());
            line(out, "  Description:  " + tool.description());
            line(out, "  Source:        " + source);
            if (tool.feature() != null) {
                line(out, "  Feature gate:  " + tool.feature());
            }

            printRequires(out, tool);
            printPackages(out, tool);
            printParameters(out, tool);
            printActions(out, tool);
            printDownloads(out, tool);
            printProxy(out, tool);
        }

        private static void printRequires(PrintStream out, ToolSetup tool) {
            var requires = tool.requires();
            if (!requires.isEmpty()) {
                line(out, "  Requires:      " + String.join(", ", requires));
            }
        }

        private static void printPackages(PrintStream out, ToolSetup tool) {
            var packages = tool.packages();
            if (!packages.isEmpty()) {
                line(out, "  Packages:      " + String.join(", ", packages));
            }
        }

        private static void printParameters(PrintStream out, ToolSetup tool) {
            var params = tool.parameters();
            if (params.isEmpty()) return;
            line(out, "  Parameters:");
            for (var entry : params.entrySet()) {
                var p = entry.getValue();
                var sb = new StringBuilder("    ").append(entry.getKey());
                if (p.getType() != null) sb.append(" (").append(p.getType()).append(')');
                if (p.getDefault() != null) sb.append(" default=").append(p.getDefault());
                line(out, sb);
                if (p.getDescription() != null && !p.getDescription().isBlank()) {
                    line(out, "      " + p.getDescription());
                }
                if (!p.getOptions().isEmpty()) {
                    line(out, "      options: " + String.join(", ", p.getOptions()));
                }
            }
        }

        private static void printActions(PrintStream out, ToolSetup tool) {
            var actions = tool.actions();
            if (actions.isEmpty()) return;
            line(out, "  Actions:");
            for (var a : actions) {
                var sb = new StringBuilder("    ").append(a.getLabel());
                if (a.getType() != null) sb.append(" (").append(a.getType()).append(')');
                line(out, sb);
            }
        }

        private static void printDownloads(PrintStream out, ToolSetup tool) {
            if (!(tool instanceof YamlToolSetup yaml)) return;
            var downloads = yaml.toolDef().getDownloads();
            if (downloads.isEmpty()) return;
            line(out, "  Downloads:");
            for (var dl : downloads) {
                var sb = new StringBuilder("    ").append(dl.getUrl());
                if (dl.getArch() != null) sb.append(" [").append(dl.getArch()).append(']');
                line(out, sb);
            }
        }

        private static void printProxy(PrintStream out, ToolSetup tool) {
            var proxy = tool.proxy();
            if (proxy == null) return;
            var auth = proxy.getAuth();
            if (auth.isEmpty()) return;
            line(out, "  Proxy domains:");
            for (var a : auth) {
                for (var domain : a.getDomains()) {
                    line(out, "    " + domain + " (" + a.getType() + ")");
                }
            }
        }
    }

    // ── validate ────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "validate", description = "Check a tool definition without building it",
            generateHelp = true)
    public static class Validate extends BaseCommand {

        @Argument(required = true, completer = Completers.Tool.class,
                description = "A tool definition file, or a tool's name")
        String target;

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
        String format;

        /** One thing the check found: an error, which a build fails or skips on, or a warning. */
        record Finding(boolean error, String message) {
            String severity() { return error ? "error" : "warning"; }
        }

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            // Read now, not the process's cached tools, so a file just written counts
            var loader = new ToolDefLoader(SpawnConfig.load().getSearchPaths());
            var findings = new ArrayList<Finding>();
            String name = null;
            String path;
            Path file;
            try {
                file = Path.of(target);
            } catch (java.nio.file.InvalidPathException e) {
                file = null;
            }
            if (file != null && Files.exists(file) && !Files.isDirectory(file)) {
                path = file.toString();
                name = checkFile(file, loader, findings);
            } else {
                var tool = loader.allToolSetups().get(target);
                var failures = loader.loadFailures().stream()
                        .filter(f -> f.likelyName().equals(target)).toList();
                if (tool == null && failures.isEmpty()) {
                    BuildOutput.error("No tool or file named '" + cell(target) + "'.",
                            "Run 'isx tools list' to see the tools.");
                    return CommandResult.valueOf(1);
                }
                if (!failures.isEmpty()) {
                    // Files of this name the loader skipped, every one, since the build refuses on each;
                    // the tool listed, if any, is another layer's
                    path = failures.getFirst().file().toString();
                    for (var failure : failures) name = checkFile(failure.file(), loader, findings);
                } else {
                    path = loader.getSource(target);
                    name = tool.name();
                    if (!"built-in".equals(path)) {
                        name = checkFile(Path.of(path), loader, findings);
                    } else if (tool instanceof YamlToolSetup yaml) {
                        check(yaml.toolDef(), loader, findings);
                    }
                    // A tool in isx's own code has no definition to check
                }
            }
            var valid = findings.stream().noneMatch(Finding::error);
            if (outputFormat == OutputFormat.PLAIN) {
                var lines = new ArrayList<Map<String, Object>>();
                for (var f : findings) {
                    var line = new LinkedHashMap<String, Object>();
                    line.put("kind", f.severity());
                    line.put("text", f.message());
                    lines.add(line);
                }
                outputFormat.print(BuildOutput.results(), lines);
            } else if (outputFormat == OutputFormat.JSON) {
                outputFormat.printOne(BuildOutput.results(), record(name, path, valid, findings));
            } else {
                findings.stream().filter(Finding::error).forEach(f -> BuildOutput.error(cell(f.message())));
                findings.stream().filter(f -> !f.error()).forEach(f -> BuildOutput.warn(cell(f.message())));
                if (findings.isEmpty()) BuildOutput.ok("Tool is valid");
            }
            return valid ? CommandResult.SUCCESS : CommandResult.valueOf(1);
        }

        /**
         * The fields of {@code isx tools validate --format=json}, in order: add to the end, never
         * rename. {@code name} is the name the definition gives, or null when it gives none.
         */
        static Map<String, Object> record(String name, String path, boolean valid, List<Finding> findings) {
            var record = new LinkedHashMap<String, Object>();
            record.put("name", name);
            record.put("path", path);
            record.put("valid", valid);
            record.put("findings", findings.stream().map(f -> {
                var finding = new LinkedHashMap<String, Object>();
                finding.put("severity", f.severity());
                finding.put("message", f.message());
                return (Object) finding;
            }).toList());
            return record;
        }

        /** Checks the tool file {@code file}; the name it gives, or null. */
        private static String checkFile(Path file, ToolDefLoader loader, List<Finding> findings) {
            var result = ToolDefValidator.validate(file);
            result.errors().forEach(e -> findings.add(new Finding(true, e)));
            result.warnings().forEach(w -> findings.add(new Finding(false, w)));
            if (result.hasErrors()) return nameIn(file);
            try (var in = Files.newInputStream(file)) {
                var def = ToolDef.loadFromStream(in);
                checkRequires(def, loader, findings);
                ToolDefValidator.embeddedTokens(def).forEach(w -> findings.add(new Finding(false, w)));
                return def.getName();
            } catch (java.io.IOException e) {
                // The validator read it a moment ago, and reported what it found
                return null;
            }
        }

        /** The name {@code file} gives, or null when it does not parse or gives none. */
        private static String nameIn(Path file) {
            try (var in = Files.newInputStream(file)) {
                return ToolDef.loadFromStream(in).getName();
            } catch (java.io.IOException | RuntimeException e) {
                return null;
            }
        }

        /** Checks a built-in definition as {@link #checkFile} checks a file. */
        private static void check(ToolDef def, ToolDefLoader loader, List<Finding> findings) {
            var errors = new ArrayList<String>();
            var warnings = new ArrayList<String>();
            ToolDefValidator.validateDef(def, errors, warnings);
            errors.forEach(e -> findings.add(new Finding(true, e)));
            warnings.forEach(w -> findings.add(new Finding(false, w)));
            if (errors.isEmpty()) checkRequires(def, loader, findings);
        }

        /** An error for each tool {@code def} requires that isx does not know: a build skips it. */
        private static void checkRequires(ToolDef def, ToolDefLoader loader, List<Finding> findings) {
            var known = loader.allToolSetups().keySet();
            for (var required : def.getRequires()) {
                var name = required.getName();
                if (name == null || known.contains(name) || name.equals(def.getName())) continue;
                findings.add(new Finding(true, OutputFormat.oneLine("'" + def.getName() + "' requires '" + name
                        + "', which is not a tool isx knows, so isx build skips it; 'isx tools list' lists the tools")));
            }
        }
    }

    // Definition text is untrusted (a project-local tool ships with a cloned repository), so a
    // table shows it as --format=plain does: nothing in it can drive the terminal (#1133).
    private static String cell(Object value) {
        return OutputFormat.oneLine(String.valueOf(value));
    }

    private static void line(PrintStream out, Object text) {
        out.println(cell(text));
    }
}
