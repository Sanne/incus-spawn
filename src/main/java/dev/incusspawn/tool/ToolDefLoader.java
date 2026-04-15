package dev.incusspawn.tool;

import dev.incusspawn.config.SpawnConfig;
import jakarta.enterprise.context.Dependent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads YAML tool definitions from built-in resources, user-level, and
 * project-local files.
 * <p>
 * Resolution order: built-in (classpath) → user ({@code ~/.config/incus-spawn/tools/})
 * → project-local ({@code .incus-spawn/tools/}). Later definitions with the
 * same name override earlier ones.
 */
@Dependent
public class ToolDefLoader {

    private static final String RESOURCE_DIR = "tools/";
    private static final List<String> BUILTIN_TOOLS = List.of(
            "podman.yaml",
            "maven-3.yaml"
    );
    private static final Path USER_TOOLS_DIR = SpawnConfig.configDir().resolve("tools");
    private Path projectToolsDir = Path.of(".incus-spawn/tools");
    private List<String> searchPaths;

    private Map<String, YamlToolSetup> tools;

    /** Override the project tools directory (for testing). */
    void setProjectToolsDir(Path dir) {
        this.projectToolsDir = dir;
        this.tools = null; // force reload
    }

    /** Override the search paths (for testing). */
    void setSearchPaths(List<String> searchPaths) {
        this.searchPaths = searchPaths;
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
            loadFromDirectory(USER_TOOLS_DIR);
            var paths = searchPaths != null ? searchPaths : SpawnConfig.load().getSearchPaths();
            for (var searchPath : paths) {
                loadFromDirectory(Path.of(searchPath).resolve("tools"));
            }
            loadFromDirectory(projectToolsDir);
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

    private void loadFromDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
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
            System.err.println("Warning: failed to scan " + dir + ": " + e.getMessage());
        }
    }
}
