package dev.incusspawn.command;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.config.UnreadConfigKeys;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.config.YamlErrors;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code isx validate <file>} (#2464): checks any of the four YAML files isx reads with the loader that
 * reads it, so its errors are the ones a build or a start reports. A template and a tool go to
 * {@code isx templates validate} and {@code isx tools validate}, whose output they keep; {@code config.yaml}
 * and {@code mcp.yaml} are checked here.
 */
@CommandDefinition(name = "validate",
        description = "Check a template, tool, config.yaml or mcp.yaml file the way isx reads it",
        generateHelp = true)
public class ValidateCommand extends BaseCommand {

    /** The kinds of file, named as the schemas in site/schema/ are. */
    enum Kind {
        TEMPLATE, TOOL, CONFIG, MCP;

        String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** The schema a file's yaml-language-server modeline names, in the comment lines the file opens with. */
    private static final Pattern MODELINE = Pattern.compile("# yaml-language-server:\\s*\\$schema=\\S*/(template|tool|config|mcp)\\.json");

    @Argument(required = true, description = "The file to check")
    String target;

    @Option(name = "kind",
            description = "template, tool, config or mcp: what the file is, when neither its schema line nor its name says")
    String kind;

    // Output for scripts: see OutputFormat for what plain and json promise.
    @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
    String format;

    @Override
    protected CommandResult doExecute() throws Exception {
        Path file;
        String yaml;
        try {
            file = Path.of(target);
            // Any file but a directory: process substitution's /dev/fd/63 is a pipe
            if (Files.isDirectory(file)) throw new IOException("it is a directory");
            yaml = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            BuildOutput.error("Could not read '" + cell(target) + "': " + cell(String.valueOf(e.getMessage())));
            return CommandResult.valueOf(1);
        }
        Kind chosen;
        try {
            chosen = kind != null ? parseKind(kind) : detect(file, yaml);
        } catch (IllegalArgumentException e) {
            BuildOutput.error(cell(e.getMessage()));
            return CommandResult.valueOf(1);
        }
        switch (chosen) {
            case TEMPLATE -> {
                var cmd = new TemplatesCommand.Validate();
                cmd.target = target;
                cmd.format = format;
                return cmd.doExecute();
            }
            case TOOL -> {
                var cmd = new ToolsCommand.Validate();
                cmd.target = target;
                cmd.format = format;
                return cmd.doExecute();
            }
            default -> {
            }
        }
        var outputFormat = OutputFormat.parse(format);
        var findings = new ArrayList<ToolsCommand.Validate.Finding>();
        if (chosen == Kind.CONFIG) checkConfig(yaml, file, findings); else checkMcp(file, findings);
        var valid = findings.stream().noneMatch(ToolsCommand.Validate.Finding::error);
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
            var record = new LinkedHashMap<String, Object>();
            record.put("kind", chosen.id());
            record.put("path", file.toString());
            record.put("valid", valid);
            record.put("findings", ToolsCommand.Validate.record(null, "", valid, findings).get("findings"));
            outputFormat.printOne(BuildOutput.results(), record);
        } else {
            findings.stream().filter(ToolsCommand.Validate.Finding::error).forEach(f -> BuildOutput.error(cell(f.message())));
            findings.stream().filter(f -> !f.error()).forEach(f -> BuildOutput.warn(cell(f.message())));
            if (findings.isEmpty()) BuildOutput.ok(chosen == Kind.CONFIG ? "config.yaml is valid" : "mcp.yaml is valid");
        }
        return valid ? CommandResult.SUCCESS : CommandResult.valueOf(1);
    }

    private static String cell(Object value) {
        return OutputFormat.oneLine(String.valueOf(value));
    }

    static Kind parseKind(String name) {
        for (var k : Kind.values()) if (k.id().equals(name)) return k;
        throw new IllegalArgumentException("Unknown kind '" + name + "': use template, tool, config or mcp.");
    }

    /** The kind the file's schema line names, else its name; an error saying how to name it, when neither does. */
    static Kind detect(Path file, String yaml) {
        var head = new StringBuilder();
        for (var line : yaml.split("\n", -1)) {
            var trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) break;
            head.append(line).append('\n');
        }
        var matcher = MODELINE.matcher(head);
        if (matcher.find()) return parseKind(matcher.group(1));
        var name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (name.equals("config.yaml")) return Kind.CONFIG;
        if (name.equals("mcp.yaml")) return Kind.MCP;
        throw new IllegalArgumentException("Cannot tell what '" + name + "' is: it has no yaml-language-server schema line"
                + " and is not named config.yaml or mcp.yaml. Pass --kind=template, tool, config or mcp.");
    }

    private static void checkConfig(String yaml, Path file, List<ToolsCommand.Validate.Finding> findings) {
        SpawnConfig config;
        try {
            config = Jjq.spawnConfig(yaml);
        } catch (IOException e) {
            findings.add(error(YamlErrors.friendly(file.getFileName().toString(), e)));
            return;
        } catch (RuntimeException e) {
            findings.add(error(file.getFileName() + ": " + e.getMessage()));
            return;
        }
        if (config == null) return; // an empty document reads as the defaults
        var problems = new ArrayList<String>();
        config.gitIdentity(problems::add);
        config.hostRoutes().routes(dev.incusspawn.proxy.ProxyConfig.interceptedDomains(), problems::add);
        config.exposedHostPorts().ports(problems::add);
        config.mavenRepositories().entries(dev.incusspawn.config.MavenRepositories.builtInTaken(), problems::add);
        config.mavenMirrors().entries(problems::add);
        problems.forEach(p -> findings.add(warning(p)));
        UnreadConfigKeys.all(config.tree(), UnreadConfigKeys.trustedTools(config)).forEach((namespace, unread) ->
                findings.add(warning("Unread keys in config.yaml: " + String.join(", ", unread)
                        + ", which no tool reads")));
        var legacy = McpConfig.legacySectionNotice(config);
        if (legacy != null) findings.add(warning(legacy));
    }

    private static void checkMcp(Path file, List<ToolsCommand.Validate.Finding> findings) {
        try {
            var policy = McpConfig.load(file);
            var retired = policy.retiredKeysNotice();
            if (retired != null) findings.add(warning(retired));
            policy.hostTools(p -> findings.add(warning(p)));
        } catch (McpConfig.Invalid e) {
            findings.add(error(e.getMessage()));
        }
    }

    private static ToolsCommand.Validate.Finding error(String message) {
        return new ToolsCommand.Validate.Finding(true, OutputFormat.oneLine(message));
    }

    private static ToolsCommand.Validate.Finding warning(String message) {
        return new ToolsCommand.Validate.Finding(false, OutputFormat.oneLine(message));
    }
}
