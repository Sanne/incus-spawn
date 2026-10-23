package dev.incusspawn.ai;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Platform;
import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.util.OutputFormat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class HelpContext {

    /** README.md, the docs/ pages isx.run publishes beside it, and DESIGN.md. Listed: a native image cannot list its resources. */
    static final List<String> HELP_RESOURCES = List.of("help/README.md",
            "help/docs/AGENTS.md", "help/docs/CLI.md", "help/docs/CONFIGURATION.md",
            "help/docs/CREDENTIAL-PROOFS.md", "help/docs/FEDORA.md", "help/docs/MCP.md", "help/docs/SANDBOXES.md",
            "help/docs/TEMPLATES.md", "help/docs/TOOLS.md", "help/docs/VISION.md", "help/DESIGN.md");
    private static volatile String cachedBasePrompt;

    private static String basePrompt() {
        var cached = cachedBasePrompt;
        if (cached != null) return cached;
        var sb = new StringBuilder();
        sb.append("You are the built-in help assistant for incus-spawn (isx) version ")
          .append(BuildInfo.instance().version()).append(".\n\n");
        sb.append("""
                Output renders in a plain-text terminal. Do not use markdown \
                formatting: no **bold**, no *italic*, no # headings. Use UPPERCASE \
                or `backticks` for emphasis, dashes for lists, blank lines for \
                structure. For links, use [display text](url) syntax — the terminal \
                renders these as clickable hyperlinks showing only the display text. \
                Use bare URLs only when the URL itself is the important information \
                (e.g. a URL the user should copy). Be tutorial-style: explain the why, \
                show exact commands, describe what each step does. Only answer from the \
                documentation below — if the answer is not covered, say so rather than \
                guessing commands, flags, or config fields. For bugs or feature requests, \
                direct the user to [open an issue]\
                (https://github.com/Sanne/incus-spawn/issues) with `isx --version` \
                output and steps to reproduce. If the question is unrelated to \
                incus-spawn, you can still help, but note that this channel consumes \
                AI tokens.

                The project website is https://isx.run. Its documentation is \
                generated from the files provided below: README.md is \
                https://isx.run/docs, and each docs/NAME.md is \
                https://isx.run/docs/name (docs/CLI.md is https://isx.run/docs/cli). \
                When answering, link to relevant documentation sections using \
                anchor fragments derived from the headings: lowercase the \
                heading, replace spaces with hyphens, strip punctuation except \
                hyphens. For example, "## Custom Tools" in docs/TOOLS.md becomes \
                [custom tools](https://isx.run/docs/tools#custom-tools) and \
                "## Credential Isolation" in README.md becomes \
                [credential isolation](https://isx.run/docs#credential-isolation). \
                Prefer deep links to specific sections over linking a whole page.

                """);
        for (var resource : HELP_RESOURCES) {
            var content = loadResource(resource);
            if (content != null) {
                var label = resource.substring("help/".length());
                sb.append("--- ").append(label).append(" ---\n");
                sb.append(content).append("\n\n");
            }
        }
        cached = sb.toString();
        cachedBasePrompt = cached;
        return cached;
    }

    /**
     * The system prompt as separately cacheable blocks: the fixed documentation first, then the
     * user's definitions when attached. Keeping the attachment in its own block means ticking
     * it still reuses the cached documentation. Nothing that varies per request may go in the
     * first block -- any byte change there invalidates the provider's cache for every question.
     */
    public static List<String> systemBlocks(boolean includeTemplates) {
        return includeTemplates ? List.of(documentation(), definitions()) : List.of(documentation());
    }

    /** The fixed first block: instructions, the documentation, and the host platform. */
    public static String documentation() {
        return basePrompt() + "The user is running on " + (Platform.isMacOS() ? "macOS" : "Linux")
                + " (" + System.getProperty("os.arch", "unknown") + ").\n\n";
    }

    /**
     * The optional second block: every tool and template definition, including the user's own.
     * Reads and parses them all, so callers that need it more than once should keep the result.
     */
    public static String definitions() {
        var sb = new StringBuilder();
        sb.append("--- Available Tools ---\n");
        sb.append("YAML tool definitions (fields using ${...} are credential placeholders):\n\n");
        for (var file : ToolDefLoader.BUILTIN_TOOLS) {
            appendResource(sb, "tools/" + file, file);
        }
        // The loader's own list, so this lists the same directories the loader reads (#1173). It leaves
        // out the project's .incus-spawn/tools because the flag is named --with-templates; it may later
        // be widened to project tools.
        ToolDefLoader.userAndSearchPathToolDirs(SpawnConfig.load().getSearchPaths())
                .forEach(toolsDir -> appendUserToolFiles(sb, toolsDir));
        sb.append("Java-based tools (always available, installed when a template references them):\n");
        for (var tool : RuntimeConstants.CDI_TOOLS) {
            sb.append("- ").append(tool.name());
            var desc = tool.description();
            if (desc != null && !desc.isBlank()) sb.append(": ").append(desc);
            sb.append("\n");
        }
        sb.append("\n");

        // Every layer, the project's included: the flag asks for templates (#1454).
        sb.append("--- Image Templates ---\n");
        sb.append("All resolved templates (built-in, user, search paths, project):\n\n");
        var allImages = ImageDef.loadAll(w -> {});
        for (var entry : allImages.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            var img = entry.getValue();
            sb.append("# ").append(field(img.getName()));
            var description = field(img.getDescription());
            if (!description.isEmpty()) sb.append(" — ").append(description);
            sb.append("\n");
            appendField(sb, "parent", img.getParent());
            appendField(sb, "type", img.getType());
            if (!img.getPackages().isEmpty()) {
                sb.append("  packages: ").append(field(String.join(", ", img.getPackages()))).append("\n");
            }
            if (!img.getTools().isEmpty()) {
                sb.append("  tools: ").append(field(img.getTools().stream()
                        .map(t -> t.getName()).collect(Collectors.joining(", ")))).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * A template field on one line, whitespace runs as one space, null as empty: a project's
     * templates are whatever a cloned repository ships, and a line break would let their text pass
     * for a {@code --- ... ---} section of the prompt.
     */
    private static String field(String value) {
        if (value == null) return "";
        return WHITESPACE_RUN.matcher(OutputFormat.oneLine(value)).replaceAll(" ").strip();
    }

    private static final Pattern WHITESPACE_RUN = Pattern.compile("(?U)\\s+");

    /** {@code "  label: value"} on its own line, or nothing when the value is empty. */
    private static void appendField(StringBuilder sb, String label, String value) {
        var text = field(value);
        if (!text.isEmpty()) sb.append("  ").append(label).append(": ").append(text).append("\n");
    }

    private static void appendResource(StringBuilder sb, String path, String label) {
        var content = loadResource(path);
        if (content != null) {
            sb.append("# ").append(label).append("\n");
            sb.append(content).append("\n\n");
        }
    }

    private static String loadResource(String path) {
        try (var is = HelpContext.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void appendUserToolFiles(StringBuilder sb, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            var paths = stream
                    .filter(ToolDefLoader::isToolFile)
                    .sorted()
                    .toList();
            if (paths.isEmpty()) return;
            sb.append("\n--- User Tool Definitions ---\n\n");
            for (var path : paths) {
                try {
                    sb.append("# ").append(path.getFileName()).append(" (user-defined)\n");
                    sb.append(sanitizeToolYaml(path)).append("\n\n");
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    /**
     * Structured sanitization for tool YAMLs: parse with ToolDef, redact
     * ConfigEntry values marked secret and AuthDef credential fields that
     * aren't ${...} template references, then return the original text with
     * those values replaced.
     */
    static String sanitizeToolYaml(Path path) throws IOException {
        var bytes = Files.readAllBytes(path);
        var content = new String(bytes, StandardCharsets.UTF_8);
        try (var is = new ByteArrayInputStream(bytes)) {
            var def = ToolDef.loadFromStream(is);
            var proxy = def.getProxy();
            if (proxy == null) return content;

            // Redact secret configuration values
            for (var entry : proxy.getConfiguration().values()) {
                // A bare key ('token:') arrives as null and holds nothing to redact
                if (entry == null) continue;
                if (!entry.getValue().isBlank() && (entry.isSecret() || isLikelyCredential(entry.getValue()))) {
                    content = redactYamlValue(content, entry.getValue());
                }
            }

            // Redact AuthDef credential fields that hold literals (not ${...} templates)
            for (var auth : proxy.getAuth()) {
                content = redactIfLiteral(content, auth.getToken());
                content = redactIfLiteral(content, auth.getPassword());
                content = redactIfLiteral(content, auth.getUsername());
                content = redactIfLiteral(content, auth.getValue());
            }
        } catch (IOException e) {
            // Parse failed — fall back to regex sanitization
            return sanitizeImageYaml(content);
        }
        return content;
    }

    private static String redactIfLiteral(String content, String fieldValue) {
        if (fieldValue == null || fieldValue.isBlank()) return content;
        if (fieldValue.contains("${")) return content;
        if (isLikelyCredential(fieldValue)) {
            return redactYamlValue(content, fieldValue);
        }
        return content;
    }

    private static boolean isLikelyCredential(String value) {
        return value.length() > 8 && !value.startsWith("$");
    }

    private static String redactYamlValue(String content, String value) {
        return content.replace(value, "<redacted>");
    }

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(?:key|token|password|secret|credential|auth|bearer)\\s*:.*");

    /**
     * Regex-based fallback for image YAMLs which don't have the isSecret annotation.
     */
    static String sanitizeImageYaml(String yaml) {
        return yaml.lines()
                .map(line -> {
                    if (SENSITIVE_KEY.matcher(line).matches()
                            && !line.stripTrailing().endsWith(":")) {
                        int colonIdx = line.indexOf(':');
                        if (colonIdx >= 0) {
                            var value = line.substring(colonIdx + 1).strip();
                            if (value.startsWith("${") || value.startsWith("\"${")) return line;
                            if (value.length() > 8 && !value.startsWith("$")) {
                                return line.substring(0, colonIdx + 1) + " <redacted>";
                            }
                        }
                    }
                    return line;
                })
                .collect(Collectors.joining("\n"));
    }
}
