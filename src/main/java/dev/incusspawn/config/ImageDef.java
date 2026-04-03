package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition of a golden image, loaded from YAML resource files.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageDef {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String RESOURCE_DIR = "images/";

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
     * Load all built-in image definitions from classpath resources.
     * Returns a map keyed by image name (e.g. "golden-minimal").
     */
    public static Map<String, ImageDef> loadBuiltins() {
        var defs = new LinkedHashMap<String, ImageDef>();
        // Load known built-in image definitions
        for (var filename : List.of("minimal.yaml", "dev.yaml", "java.yaml")) {
            var def = loadResource(RESOURCE_DIR + filename);
            if (def != null) {
                defs.put(def.getName(), def);
            }
        }
        return defs;
    }

    /**
     * Find an image definition by name. Searches built-in definitions.
     */
    public static ImageDef findByName(String name, Map<String, ImageDef> defs) {
        return defs.get(name);
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
