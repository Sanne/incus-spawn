package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition of a template image, loaded from YAML.
 * <p>
 * Resolution order: built-in (classpath) → user ({@code ~/.config/incus-spawn/images/})
 * → project-local ({@code .incus-spawn/images/}). Later definitions with the
 * same name override earlier ones.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageDef {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String RESOURCE_DIR = "images/";

    // Hardcoded list of built-in image filenames. Classpath directory scanning
    // is unreliable in GraalVM native image, so we enumerate explicitly.
    // Update this list when adding a new built-in image definition.
    private static final List<String> BUILTIN_FILES = List.of(
            "minimal.yaml", "dev.yaml", "java.yaml"
    );

    private static final Path USER_IMAGES_DIR = SpawnConfig.configDir().resolve("images");
    private static final Path PROJECT_IMAGES_DIR = Path.of(".incus-spawn/images");

    private String name;
    private String description = "";
    private String image = "images:fedora/43";
    private String parent;
    private List<String> packages = List.of();
    private List<String> tools = List.of();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
    public List<String> getPackages() { return packages; }
    public void setPackages(List<String> packages) { this.packages = packages; }
    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }

    /** Whether this image is built from scratch (no parent). */
    public boolean isRoot() {
        return parent == null || parent.isBlank();
    }

    /**
     * Load all image definitions: built-in first, then user-defined overrides.
     * Returns a map keyed by image name (e.g. "tpl-minimal").
     */
    public static Map<String, ImageDef> loadAll() {
        return loadAll(SpawnConfig.load().getSearchPaths());
    }

    /**
     * Load all image definitions with explicit search paths.
     */
    static Map<String, ImageDef> loadAll(List<String> searchPaths) {
        var defs = new LinkedHashMap<String, ImageDef>();
        loadBuiltins(defs);
        loadUserDefined(defs);
        for (var searchPath : searchPaths) {
            loadFromDirectory(Path.of(searchPath).resolve("images"), defs);
        }
        loadFromDirectory(PROJECT_IMAGES_DIR, defs);
        return defs;
    }

    /**
     * @deprecated Use {@link #loadAll()} instead.
     */
    @Deprecated
    public static Map<String, ImageDef> loadBuiltins() {
        return loadAll();
    }

    /**
     * Find an image definition by name.
     */
    public static ImageDef findByName(String name, Map<String, ImageDef> defs) {
        return defs.get(name);
    }

    private static void loadBuiltins(Map<String, ImageDef> defs) {
        for (var filename : BUILTIN_FILES) {
            var def = loadResource(RESOURCE_DIR + filename);
            if (def != null) {
                defs.put(def.getName(), def);
            }
        }
    }

    private static void loadUserDefined(Map<String, ImageDef> defs) {
        loadFromDirectory(USER_IMAGES_DIR, defs);
    }

    private static void loadFromDirectory(Path dir, Map<String, ImageDef> defs) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .forEach(path -> {
                        try (var is = Files.newInputStream(path)) {
                            var def = YAML.readValue(is, ImageDef.class);
                            if (def.getName() != null) {
                                defs.put(def.getName(), def);
                            }
                        } catch (IOException e) {
                            System.err.println("Warning: failed to load image definition: "
                                    + path + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("Warning: failed to scan " + dir + ": " + e.getMessage());
        }
    }

    private static ImageDef loadResource(String path) {
        try (InputStream is = ImageDef.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return YAML.readValue(is, ImageDef.class);
        } catch (IOException e) {
            System.err.println("Warning: failed to load image definition: " + path + ": " + e.getMessage());
            return null;
        }
    }
}
