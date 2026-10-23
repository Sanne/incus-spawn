package dev.incusspawn;

import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import org.aesh.command.CommandDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lists in the user pages mirror lists in the code, and nothing failed when the code's list
 * grew: a tool or a command was added and the page kept the old inventory. So every top-level
 * command is in the README's CLI table and has a section in docs/CLI.md, every built-in tool is a
 * row of the Tools page's table, and every tool that declares a proxy credential is named in the
 * README's credential table. A feature-gated tool is left out of the pages on purpose.
 */
@ExtendWith(IsolatedHome.class)
class DocsListsTest {

    private static final Path ROOT = Path.of("..");

    @Test
    void everyCommandIsInTheReadmeTableAndTheCliReference() throws IOException {
        var readme = Files.readString(ROOT.resolve("README.md"));
        var cli = Files.readString(ROOT.resolve("docs/CLI.md"));
        var missing = new ArrayList<String>();
        for (var name : commands()) {
            if (IncusSpawn.HIDDEN_COMMANDS.contains(name)) continue;
            if (!readme.contains("[`isx " + name + "`](docs/CLI.md#isx-" + name + ")")) {
                missing.add("README.md CLI table: isx " + name);
            }
            if (!cli.contains("\n## `isx " + name + "`\n")) missing.add("docs/CLI.md: ## `isx " + name + "`");
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everyBuiltInToolIsInTheToolsTable() throws IOException {
        var tools = Files.readString(ROOT.resolve("docs/TOOLS.md"));
        var table = section(tools, "## Built-in Tools");
        var missing = new ArrayList<String>();
        for (var tool : builtInTools().values()) {
            if (tool.feature() != null) continue;
            if (!table.contains("| `" + tool.name() + "`")) missing.add(tool.name());
        }
        assertEquals(List.of(), missing, "built-in tools without a row in docs/TOOLS.md \"Built-in Tools\"");
    }

    @Test
    void everyCredentialedToolIsInTheReadmeCredentialTable() throws IOException {
        var readme = Files.readString(ROOT.resolve("README.md"));
        var table = section(readme, "What the proxy can sign in as:");
        var missing = new ArrayList<String>();
        for (var tool : builtInTools().values()) {
            var proxy = tool.proxy();
            if (proxy == null || proxy.getConfigNamespace().isEmpty() || tool.feature() != null) continue;
            if (!table.contains("`" + tool.name() + "`")) missing.add(tool.name() + " (" + proxy.getConfigNamespace() + ")");
        }
        assertEquals(List.of(), missing, "credentialed tools not named in the README's credential table");
    }

    /** The names of the top-level commands of both platforms' top commands. */
    static TreeSet<String> commands() {
        var names = new TreeSet<String>();
        for (var top : List.of(IncusSpawn.IncusSpawnCommand.class, IncusSpawn.IncusSpawnLinuxCommand.class)) {
            for (var c : top.getAnnotation(CommandDefinition.class).groupCommands()) {
                names.add(c.getAnnotation(CommandDefinition.class).name());
            }
        }
        return names;
    }

    /** The CDI tools and the YAML tools shipped as resources, and nothing from the home directory. */
    static Map<String, ToolSetup> builtInTools() {
        var shipped = new TreeSet<String>();
        for (var file : ToolDefLoader.BUILTIN_TOOLS) shipped.add(file.replace(".yaml", ""));
        var tools = new LinkedHashMap<String, ToolSetup>();
        for (var tool : RuntimeConstants.CDI_TOOLS) tools.put(tool.name(), tool);
        for (var e : new ToolDefLoader().allToolSetups().entrySet()) {
            if (shipped.contains(e.getKey())) tools.put(e.getKey(), e.getValue());
        }
        return tools;
    }

    /** The text from the marker to the next blank line that follows a non-blank one. */
    private static String section(String text, String marker) {
        int start = text.indexOf(marker);
        assertTrue(start >= 0, "marker not found: " + marker);
        int body = text.indexOf("\n\n", start);
        if (marker.startsWith("#")) {
            int next = text.indexOf("\n## ", start + marker.length());
            return next < 0 ? text.substring(start) : text.substring(start, next);
        }
        int end = text.indexOf("\n\n", body + 2);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }
}
