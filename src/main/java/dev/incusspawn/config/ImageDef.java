package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
@RegisterForReflection
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

    private static final Path PROJECT_IMAGES_DIR = Path.of(".incus-spawn/images");

    public static Path userImagesDir() { return SpawnConfig.configDir().resolve("images"); }

    public static Path projectImagesDir() { return PROJECT_IMAGES_DIR; }

    /**
     * Parse an image definition from a YAML file on disk.
     */
    public static ImageDef parseFile(Path file) throws IOException {
        return YAML.readValue(file.toFile(), ImageDef.class);
    }

    /**
     * Derive the YAML filename from a template name (e.g. {@code tpl-java} -> {@code java.yaml}).
     */
    public static String filenameForName(String name) {
        return (name.startsWith("tpl-") ? name.substring(4) : name) + ".yaml";
    }

    private String name;
    private String description = "";
    private String image = "images:fedora/43";
    private String parent;
    private List<String> packages = List.of();
    private List<String> tools = List.of();
    private List<RepoEntry> repos = List.of();
    @JsonDeserialize(using = SkillsDef.Deserializer.class)
    private SkillsDef skills = SkillsDef.EMPTY;

    @JsonIgnore
    private String source = "unknown";

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
    public List<RepoEntry> getRepos() { return repos; }
    public void setRepos(List<RepoEntry> repos) { this.repos = repos; }
    public SkillsDef getSkills() { return skills; }
    public void setSkills(SkillsDef skills) { this.skills = skills; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    /**
     * Groups the skills catalog repo and skill list under a single {@code skills} key.
     * <p>
     * Supports two YAML forms:
     * <pre>
     * # Object form (with optional repo):
     * skills:
     *   repo: myorg/claude-skills
     *   list:
     *     - security-review
     *     - xixu-me/skills@xget
     *
     * # List shorthand (no repo):
     * skills:
     *   - xixu-me/skills@xget
     *   - myorg/catalog
     * </pre>
     */
    public static class SkillsDef {
        static final SkillsDef EMPTY = new SkillsDef(null, List.of());

        private final String repo;
        private final List<String> list;

        public SkillsDef(String repo, List<String> list) {
            this.repo = repo;
            this.list = list != null ? list : List.of();
        }

        /** Default skills catalog, used to resolve bare skill names (e.g. {@code security-review}). */
        public String getRepo() { return repo; }

        /** Skill sources declared in this image. */
        public List<String> getList() { return list; }

        public static class Deserializer extends StdDeserializer<SkillsDef> {
            public Deserializer() { super(SkillsDef.class); }

            @Override
            public SkillsDef deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                if (p.currentToken() == JsonToken.START_ARRAY) {
                    // List shorthand: skills: [...]
                    var items = new ArrayList<String>();
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        items.add(p.getText());
                    }
                    return new SkillsDef(null, items);
                }
                // Object form: skills: { repo: ..., list: [...] }
                String repo = null;
                List<String> list = List.of();
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    var field = p.currentName();
                    p.nextToken();
                    switch (field) {
                        case "repo" -> repo = p.getText();
                        case "list" -> {
                            var items = new ArrayList<String>();
                            while (p.nextToken() != JsonToken.END_ARRAY) {
                                items.add(p.getText());
                            }
                            list = items;
                        }
                        default -> p.skipChildren();
                    }
                }
                return new SkillsDef(repo, list);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepoEntry {
        private String url;
        private String path;
        private String branch;
        private String prime;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }
        public String getPrime() { return prime; }
        public void setPrime(String prime) { this.prime = prime; }
    }

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
                def.setSource("built-in");
                defs.put(def.getName(), def);
            }
        }
    }

    private static void loadUserDefined(Map<String, ImageDef> defs) {
        loadFromDirectory(userImagesDir(), defs);
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
                                def.setSource(path.toString());
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
