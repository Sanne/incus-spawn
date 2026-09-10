package dev.incusspawn.ai;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Platform;
import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class HelpContext {

    private static final List<String> HELP_RESOURCES = List.of("help/README.md", "help/DESIGN.md");
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

                The project website is https://isx.run and the main documentation \
                page is https://isx.run/docs.html — it is generated from the \
                README.md provided below. When answering, link to relevant \
                documentation sections using anchor fragments derived from the \
                README headings: lowercase the heading, replace spaces with \
                hyphens, strip punctuation except hyphens. For example, \
                "## Custom Tools" becomes \
                [custom tools](https://isx.run/docs.html#custom-tools) and \
                "## Credential Isolation" becomes \
                [credential isolation](https://isx.run/docs.html#credential-isolation). \
                Prefer deep links to specific sections over linking the top-level page.

                """);
        for (var resource : HELP_RESOURCES) {
            var content = loadResource(resource);
            if (content != null) {
                var label = resource.substring(resource.lastIndexOf('/') + 1);
                sb.append("--- ").append(label).append(" ---\n");
                sb.append(content).append("\n\n");
            }
        }
        cached = sb.toString();
        cachedBasePrompt = cached;
        return cached;
    }

    public static String buildSystemPrompt(boolean includeTemplates) {
        var sb = new StringBuilder(basePrompt());
        sb.append("The user is running on ").append(Platform.isMacOS() ? "macOS" : "Linux");
        sb.append(" (").append(System.getProperty("os.arch", "unknown")).append(").\n\n");

        if (includeTemplates) {
            sb.append("--- Available Tools ---\n");
            sb.append("YAML tool definitions (fields using ${...} are credential placeholders):\n\n");
            for (var file : ToolDefLoader.BUILTIN_TOOLS) {
                appendResource(sb, "tools/" + file, file);
            }
            appendUserToolFiles(sb, SpawnConfig.configDir().resolve("tools"));
            for (var searchPath : SpawnConfig.load().getSearchPaths()) {
                var toolsDir = Path.of(searchPath).resolve("tools");
                appendUserToolFiles(sb, toolsDir);
            }
            sb.append("Java-based tools (always available, installed when a template references them):\n");
            for (var tool : RuntimeConstants.CDI_TOOLS) {
                sb.append("- ").append(tool.name());
                var desc = tool.description();
                if (desc != null && !desc.isBlank()) sb.append(": ").append(desc);
                sb.append("\n");
            }
            sb.append("\n");

            sb.append("--- Image Templates ---\n");
            sb.append("All resolved templates (built-in, user, search paths, project):\n\n");
            var allImages = ImageDef.loadAll(w -> {});
            for (var entry : allImages.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                var img = entry.getValue();
                sb.append("# ").append(img.getName());
                if (img.getDescription() != null && !img.getDescription().isBlank()) {
                    sb.append(" — ").append(img.getDescription());
                }
                sb.append("\n");
                if (img.getParent() != null) sb.append("  parent: ").append(img.getParent()).append("\n");
                if (img.getType() != null) sb.append("  type: ").append(img.getType()).append("\n");
                if (img.getPackages() != null && !img.getPackages().isEmpty()) {
                    sb.append("  packages: ").append(String.join(", ", img.getPackages())).append("\n");
                }
                if (img.getTools() != null && !img.getTools().isEmpty()) {
                    sb.append("  tools: ").append(img.getTools().stream()
                            .map(t -> t.getName()).collect(Collectors.joining(", "))).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
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
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
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
