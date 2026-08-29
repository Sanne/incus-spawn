package dev.incusspawn.tool;

import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.LayeredDefinitions;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.config.YamlErrors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads YAML tool definitions from built-in resources, user-level, and
 * project-local files.
 * <p>
 * Resolution order: built-in (classpath) → user ({@code ~/.config/incus-spawn/tools/})
 * → project-local ({@code .incus-spawn/tools/}). Later definitions with the
 * same name override earlier ones.
 * <p>
 * Overriding across layers is intentional and supported. Two files that declare
 * the same {@code name:} <em>within a single directory</em>, however, are always a
 * mistake; those are reported via {@link #conflicts()} so callers (e.g. {@code isx
 * build}) can refuse to build and insist the user disambiguate. See
 * {@link LayeredDefinitions} for the shared collision/override bookkeeping.
 */
public class ToolDefLoader {

    private static final String RESOURCE_DIR = "tools/";
    private static final List<String> BUILTIN_TOOLS = List.of(
            "headroom.yaml",
            "podman.yaml",
            "maven-3.yaml",
            "sshd.yaml",
            "idea-backend.yaml",
            "vscode-remote.yaml",
            "starship.yaml",
            "tmux.yaml",
            "zmx.yaml"
    );
    private static Path userToolsDir() { return SpawnConfig.configDir().resolve("tools"); }
    private Path projectToolsDir = Path.of(".incus-spawn/tools");
    private List<String> searchPaths;

    private LayeredDefinitions<YamlToolSetup> defs;

    /** Override the project tools directory (for testing). */
    void setProjectToolsDir(Path dir) {
        this.projectToolsDir = dir;
        reload();
    }

    /** Override the search paths (for testing). */
    void setSearchPaths(List<String> searchPaths) {
        this.searchPaths = searchPaths;
        reload();
    }

    /**
     * Discard cached definitions so the next access re-reads tool YAML from disk.
     * The loader is a process-lifetime singleton, so a long-running process that reloads
     * on-disk state (e.g. the TUI, or an in-process build) must call this to observe edits
     * to tool YAML — otherwise fingerprints stay frozen at first load.
     */
    public void reload() {
        this.defs = null;
    }

    /** Same-directory name collisions found during the last load (always a mistake). */
    public List<LayeredDefinitions.NameConflict> conflicts() {
        return load().conflicts();
    }

    /** Cross-layer overrides found during the last load (intentional; for diagnostics). */
    public List<LayeredDefinitions.LayerOverride> overrides() {
        return load().overrides();
    }

    /**
     * Find a YAML-defined tool by name.
     * Checks user-defined tools first, then built-in YAML tools.
     */
    public ToolSetup find(String name) {
        return load().defs().get(name);
    }

    /**
     * Register stored tool definitions as fallbacks. Tools already
     * in scope (from the normal resolution chain) are not overridden.
     */
    public void addFallbacks(Map<String, ToolDef> fallbacks) {
        if (fallbacks == null || fallbacks.isEmpty()) return;
        var map = load().defs();
        for (var entry : fallbacks.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (!map.containsKey(entry.getKey())) {
                map.put(entry.getKey(), new YamlToolSetup(entry.getValue()));
            }
        }
    }

    private LayeredDefinitions<YamlToolSetup> load() {
        if (defs == null) {
            defs = new LayeredDefinitions<>("tool");
            loadBuiltins();
            loadFromDirectory(userToolsDir());
            var paths = searchPaths != null ? searchPaths : SpawnConfig.load().getSearchPaths();
            for (var searchPath : paths) {
                var expandedPath = HostResourceSetup.expandHostTilde(searchPath);
                loadFromDirectory(Path.of(expandedPath).resolve("tools"));
            }
            loadFromDirectory(projectToolsDir);
        }
        return defs;
    }

    private void loadBuiltins() {
        // Built-ins are the first layer with unique names by construction, so no
        // conflict/override tracking is needed here.
        for (var filename : BUILTIN_TOOLS) {
            try (var is = getClass().getClassLoader().getResourceAsStream(RESOURCE_DIR + filename)) {
                if (is == null) continue;
                var def = ToolDef.loadFromStream(is);
                if (def.getName() != null) {
                    defs.putBuiltin(def.getName(), new YamlToolSetup(def));
                }
            } catch (IOException e) {
                System.err.println("Warning: " + YamlErrors.friendly(filename, e));
            }
        }
    }

    private void loadFromDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return;
        defs.beginDirectory();
        try (var stream = Files.list(dir)) {
            var paths = stream
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
            for (var path : paths) {
                try (var is = Files.newInputStream(path)) {
                    var def = ToolDef.loadFromStream(is);
                    if (def.getName() != null) {
                        var source = path.toAbsolutePath().normalize();
                        defs.put(def.getName(), new YamlToolSetup(def), source);
                    }
                } catch (IOException e) {
                    System.err.println("Warning: " + YamlErrors.friendly(
                            path.getFileName().toString(), e));
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to scan " + dir + ": " + e.getMessage());
        }
        defs.endDirectory();
    }
}
