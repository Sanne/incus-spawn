package dev.incusspawn.tool;

import jakarta.enterprise.context.Dependent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads YAML tool definitions from built-in resources and user-defined
 * files. User-defined tools (in {@code .incus-spawn/tools/}) take
 * priority over built-in ones.
 */
@Dependent
public class ToolDefLoader {

    private static final String RESOURCE_DIR = "tools/";
    private static final List<String> BUILTIN_TOOLS = List.of(
            "podman.yaml",
            "maven-3.yaml"
    );
    private Path userToolsDir = Path.of(".incus-spawn/tools");

    private Map<String, YamlToolSetup> tools;

    /** Override the user tools directory (for testing). */
    void setUserToolsDir(Path dir) {
        this.userToolsDir = dir;
        this.tools = null; // force reload
    }

    /**
     * Find a YAML-defined tool by name.
     * Checks user-defined tools first, then built-in YAML tools.
     */
    public ToolSetup find(String name) {
        return loadAll().get(name);
    }

    private Map<String, YamlToolSetup> loadAll() {
        if (tools == null) {
            tools = new LinkedHashMap<>();
            loadBuiltins();
            loadUserDefined(); // user tools override builtins
        }
        return tools;
    }

    private void loadBuiltins() {
        for (var filename : BUILTIN_TOOLS) {
            try (var is = getClass().getClassLoader().getResourceAsStream(RESOURCE_DIR + filename)) {
                if (is == null) continue;
                var def = ToolDef.loadFromStream(is);
                if (def.getName() != null) {
                    tools.put(def.getName(), new YamlToolSetup(def));
                }
            } catch (IOException e) {
                System.err.println("Warning: failed to load built-in tool: " + filename + ": " + e.getMessage());
            }
        }
    }

    private void loadUserDefined() {
        if (!Files.isDirectory(userToolsDir)) return;
        try (var stream = Files.list(userToolsDir)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .forEach(path -> {
                        try (var is = Files.newInputStream(path)) {
                            var def = ToolDef.loadFromStream(is);
                            if (def.getName() != null) {
                                tools.put(def.getName(), new YamlToolSetup(def));
                            }
                        } catch (IOException e) {
                            System.err.println("Warning: failed to load tool: " + path + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("Warning: failed to scan " + userToolsDir + ": " + e.getMessage());
        }
    }
}
