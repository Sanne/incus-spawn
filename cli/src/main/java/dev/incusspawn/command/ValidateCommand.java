package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.proxy.CachedRepositoryRules;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.tool.ToolDefLoader;
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
    private static final Pattern MODELINE = Pattern.compile("# yaml-language-server:\\s*\\$schema=(?:\\S*/)?(template|tool|config|mcp)\\.json");

    @Argument(required = true, description = "The file to check ('-' for stdin)")
    String target;

    @Option(name = "kind",
            description = "template, tool, config or mcp: what the file is, when neither its schema line nor its name says")
    String kind;

    // Output for scripts: see OutputFormat for what plain and json promise.
    @Option(name = "format", completer = Completers.Format.class, description = "Output format: table (default), plain or json")
    String format;

    /** Where {@code -} reads, which a test replaces. */
    java.util.function.Supplier<java.io.InputStream> stdin = () -> System.in;

    @Override
    protected CommandResult doExecute() throws Exception {
        var outputFormat = OutputFormat.parse(format);
        var fromStdin = "-".equals(target);
        Path file;
        String yaml;
        try {
            file = fromStdin ? null : Path.of(target);
            // Any file but a directory: process substitution's /dev/fd/63 is a pipe, read once, here
            if (file != null && Files.isDirectory(file)) throw new IOException("it is a directory");
            if (file != null && !Files.exists(file)) {
                BuildOutput.error("No file named '" + cell(target) + "'.",
                        "isx validate takes a file; 'isx templates validate' and 'isx tools validate' also take a name.");
                return CommandResult.valueOf(1);
            }
            yaml = TemplatesCommand.readDefinition(target, stdin);
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
        // Every check below takes the text read above, never the path again: a pipe has nothing left
        var fileName = file == null || file.getFileName() == null ? "stdin.yaml" : file.getFileName().toString();
        var findings = new ArrayList<ToolsCommand.Validate.Finding>();
        switch (chosen) {
            case TEMPLATE -> {
                var location = file == null ? ImageDef.userImagesDir().resolve("stdin.yaml") : file;
                return TemplatesCommand.Validate.check(yaml, location, ImageDef.loadAll(), outputFormat);
            }
            case TOOL -> {
                // Read now, not the process's cached tools, so a file just written counts
                var loader = new ToolDefLoader(SpawnConfig.load().getSearchPaths());
                var name = ToolsCommand.Validate.checkText(yaml, fileName, loader, findings);
                return ToolsCommand.Validate.report(outputFormat, name, target, findings, "Tool is valid");
            }
            case CONFIG -> checkConfig(yaml, fileName, findings);
            case MCP -> checkMcp(yaml, fromStdin ? fileName : target, findings);
        }
        var valid = findings.stream().noneMatch(ToolsCommand.Validate.Finding::error);
        if (outputFormat == OutputFormat.JSON) {
            outputFormat.printOne(BuildOutput.results(), record(chosen, target, valid, findings));
            return valid ? CommandResult.SUCCESS : CommandResult.valueOf(1);
        }
        return ToolsCommand.Validate.report(outputFormat, null, target, findings,
                cell(fromStdin ? "stdin" : target) + " is valid");
    }

    /**
     * The fields of {@code isx validate --format=json} for {@code config.yaml} and {@code mcp.yaml}, in order:
     * add to the end, never rename.
     */
    static Map<String, Object> record(Kind kind, String path, boolean valid, List<ToolsCommand.Validate.Finding> findings) {
        var record = new LinkedHashMap<String, Object>();
        record.put("kind", kind.id());
        record.put("path", path);
        record.put("valid", valid);
        record.put("findings", ToolsCommand.Validate.record(null, path, valid, findings).get("findings"));
        return record;
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
        var name = file == null || file.getFileName() == null ? "" : file.getFileName().toString();
        if (name.equals("config.yaml")) return Kind.CONFIG;
        if (name.equals("mcp.yaml")) return Kind.MCP;
        throw new IllegalArgumentException("Cannot tell what '" + (file == null ? "-" : name) + "' is: it has no yaml-language-server schema line"
                + " and is not named config.yaml or mcp.yaml. Pass --kind=template, tool, config or mcp.");
    }

    private static void checkConfig(String yaml, String fileName, List<ToolsCommand.Validate.Finding> findings) {
        SpawnConfig config;
        try {
            config = Jjq.spawnConfig(yaml);
        } catch (IOException e) {
            findings.add(error(YamlErrors.friendly(fileName, e)));
            return;
        } catch (RuntimeException e) {
            findings.add(error(fileName + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            return;
        }
        if (config == null) return; // an empty document reads as the defaults
        var tools = UnreadConfigKeys.trustedTools(config);
        // The proxy's own checks, against the domains it intercepts, so what it drops is said here
        var problems = new ArrayList<String>();
        config.gitIdentity(problems::add);
        var acrossAccounts = ToolProxyResolver.resolveAcrossAccounts(config, tools);
        ToolProxyResolver.hostRoutes(config, acrossAccounts, problems::add);
        config.exposedHostPorts().ports(problems::add);
        // The proxy's two passes: each entry's own shape, then against what the tools inject and isx caches
        var repositories = config.mavenRepositories().entries(
                ToolProxyResolver.repositoryDomainsTaken(ToolProxyResolver.handlerDomains(config.tree())), problems::add);
        var mirrors = config.mavenMirrors().entries(problems::add);
        CachedRepositoryRules.check(repositories, mirrors, acrossAccounts, problems::add);
        config.confirmedCache().maxSize(problems::add);
        ToolProxyResolver.servedConfirmedCacheDomains(config, acrossAccounts, problems::add);
        problems.forEach(p -> findings.add(warning(p)));
        var tree = config.tree();
        UnreadConfigKeys.misspelt(tree, tools).forEach(note -> findings.add(warning(note)));
        UnreadConfigKeys.all(tree, tools).forEach((namespace, unread) ->
                findings.add(warning(UnreadConfigKeys.note(namespace, unread, tools))));
        var legacy = McpConfig.legacySectionNotice(config);
        if (legacy != null) findings.add(warning(legacy));
    }

    private static void checkMcp(String yaml, String name, List<ToolsCommand.Validate.Finding> findings) {
        try {
            var policy = McpConfig.parse(yaml, name);
            var retired = policy.retiredKeysNotice(name);
            if (retired != null) findings.add(warning(retired));
            policy.hostTools(p -> findings.add(warning(p)));
        } catch (McpConfig.Invalid e) {
            // What refusing it means for isx mcp holds only for the file isx mcp reads, never a draft
            findings.add(error(isLive(name) ? e.getMessage() : e.why()));
        }
    }

    /** Whether {@code name} is the mcp.yaml that isx mcp reads. */
    private static boolean isLive(String name) {
        try {
            return Files.isSameFile(Path.of(name), McpConfig.file());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static ToolsCommand.Validate.Finding error(String message) {
        return new ToolsCommand.Validate.Finding(true, OutputFormat.oneLine(message));
    }

    private static ToolsCommand.Validate.Finding warning(String message) {
        return new ToolsCommand.Validate.Finding(false, OutputFormat.oneLine(message));
    }
}
