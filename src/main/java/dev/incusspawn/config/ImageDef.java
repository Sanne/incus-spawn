package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.tool.ToolDef;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

    public static ImageDef parseYaml(String yaml) throws IOException {
        return YAML.readValue(yaml, ImageDef.class);
    }

    /**
     * Derive the YAML filename from a template name (e.g. {@code tpl-java} -> {@code java.yaml}).
     */
    public static String filenameForName(String name) {
        return (name.startsWith("tpl-") ? name.substring(4) : name) + ".yaml";
    }

    private String name;
    private String description = "";
    private String image = "images:fedora/44";
    private String parent;
    private List<String> packages = List.of();
    @JsonProperty("remove_packages")
    private List<String> removePackages = List.of();
    @JsonDeserialize(using = ImageDef.ToolsDeserializer.class)
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = ToolDef.ToolRef.Serializer.class)
    private List<ToolDef.ToolRef> tools = List.of();
    private List<RepoEntry> repos = List.of();
    @JsonDeserialize(using = SkillsDef.Deserializer.class)
    private SkillsDef skills = SkillsDef.EMPTY;
    @JsonProperty("package_repos")
    private List<PackageRepo> packageRepos = List.of();
    @JsonProperty("host-resources")
    private List<HostResource> hostResources = List.of();
    @JsonProperty("mask_services")
    private List<String> maskServices = List.of();
    private boolean gui;
    private String workdir;
    @JsonProperty("shell-command")
    private String shellCommand;

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
    public List<String> getRemovePackages() { return removePackages; }
    public void setRemovePackages(List<String> removePackages) { this.removePackages = removePackages; }
    public List<ToolDef.ToolRef> getTools() { return tools; }
    public void setTools(List<ToolDef.ToolRef> tools) { this.tools = tools; }
    public List<RepoEntry> getRepos() { return repos; }
    public void setRepos(List<RepoEntry> repos) { this.repos = repos; }
    public SkillsDef getSkills() { return skills; }
    public void setSkills(SkillsDef skills) { this.skills = skills; }
    public List<PackageRepo> getPackageRepos() { return packageRepos; }
    public void setPackageRepos(List<PackageRepo> packageRepos) { this.packageRepos = packageRepos; }
    public List<HostResource> getHostResources() { return hostResources; }
    public void setHostResources(List<HostResource> hostResources) { this.hostResources = hostResources; }
    public List<String> getMaskServices() { return maskServices; }
    public void setMaskServices(List<String> maskServices) { this.maskServices = maskServices; }
    public boolean isGui() { return gui; }
    public void setGui(boolean gui) { this.gui = gui; }
    public String getWorkdir() { return workdir; }
    public void setWorkdir(String workdir) { this.workdir = workdir; }
    public String getShellCommand() { return shellCommand; }
    public void setShellCommand(String shellCommand) { this.shellCommand = shellCommand; }
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

    public static class ToolsDeserializer extends StdDeserializer<List<ToolDef.ToolRef>> {
        public ToolsDeserializer() { super(List.class); }

        @Override
        public List<ToolDef.ToolRef> deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            return ToolDef.ToolRef.deserializeList(p, "tools");
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PackageRepo {
        private String type;
        private String name;

        public PackageRepo() {}

        public PackageRepo(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HostResource {
        private String source;
        private String path;
        private String mode = "readonly";

        public HostResource() {}

        public HostResource(String source, String path, String mode) {
            this.source = source;
            this.path = path;
            this.mode = mode != null ? mode : "readonly";
        }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }

    public String contentFingerprint(Map<String, String> toolFingerprints) {
        var sb = new StringBuilder();
        sb.append("image=").append(image).append('\n');
        sb.append("parent=").append(parent != null ? parent : "").append('\n');
        packages.stream().sorted().forEach(p -> sb.append("pkg=").append(p).append('\n'));
        for (var t : tools.stream().sorted(java.util.Comparator.comparing(ToolDef.ToolRef::getName)).toList()) {
            sb.append("tool=").append(t.getName());
            var fp = toolFingerprints.get(t.getName());
            if (fp != null && !fp.isEmpty()) sb.append(':').append(fp);
            // Include parameter values in fingerprint
            if (!t.getParams().isEmpty()) {
                t.getParams().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(',').append(e.getKey()).append('=').append(e.getValue()));
            }
            sb.append('\n');
        }
        packageRepos.stream()
                .sorted(java.util.Comparator.<PackageRepo, String>comparing(r -> String.valueOf(r.getType()))
                        .thenComparing(r -> String.valueOf(r.getName())))
                .forEach(r -> sb.append("pkgrepo=").append(r.getType()).append(',').append(r.getName()).append('\n'));
        repos.stream()
                .sorted(java.util.Comparator.<RepoEntry, String>comparing(r -> String.valueOf(r.getUrl()))
                        .thenComparing(r -> String.valueOf(r.getPath())))
                .forEach(r -> sb.append("repo=").append(r.getUrl()).append(',').append(r.getPath())
                        .append(',').append(r.getBranch()).append(',').append(r.getPrime()).append('\n'));
        if (skills.getRepo() != null) sb.append("skills-repo=").append(skills.getRepo()).append('\n');
        skills.getList().stream().sorted().forEach(s -> sb.append("skill=").append(s).append('\n'));
        for (var hr : hostResources) {
            sb.append("hr=").append(hr.getSource()).append(',').append(hr.getPath())
                    .append(',').append(hr.getMode()).append('\n');
        }
        if (gui) sb.append("gui=true\n");
        if (workdir != null && !workdir.isEmpty()) sb.append("workdir=").append(workdir).append('\n');
        if (shellCommand != null && !shellCommand.isEmpty()) sb.append("shell-command=").append(shellCommand).append('\n');
        return sha256hex(sb.toString());
    }

    private static String sha256hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Return the ordered list of ancestor images, starting from the immediate
     * parent and walking up to the root. Stops if a name is missing from
     * {@code defs}.
     */
    public static List<ImageDef> ancestors(ImageDef start, Map<String, ImageDef> defs) {
        var result = new ArrayList<ImageDef>();
        var parentName = start.getParent();
        while (parentName != null && !parentName.isBlank()) {
            var parent = defs.get(parentName);
            if (parent == null) break;
            result.add(parent);
            parentName = parent.getParent();
        }
        return result;
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
     * Load all image definitions, collecting parse warnings via the given consumer
     * instead of printing to stderr. Use this from the TUI to avoid corrupting
     * the terminal with raw error output.
     */
    public static Map<String, ImageDef> loadAll(Consumer<String> warnings) {
        return loadAll(SpawnConfig.load().getSearchPaths(), warnings);
    }

    /**
     * Load all image definitions with explicit search paths.
     */
    static Map<String, ImageDef> loadAll(List<String> searchPaths) {
        return loadAll(searchPaths, msg -> System.err.println(msg));
    }

    static Map<String, ImageDef> loadAll(List<String> searchPaths, Consumer<String> warnings) {
        var defs = new LinkedHashMap<String, ImageDef>();
        loadBuiltins(defs, warnings);
        loadUserDefined(defs, warnings);
        for (var searchPath : searchPaths) {
            var expandedPath = HostResourceSetup.expandHostTilde(searchPath);
            loadFromDirectory(Path.of(expandedPath).resolve("images"), defs, warnings);
        }
        loadFromDirectory(PROJECT_IMAGES_DIR, defs, warnings);
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

    private static void loadBuiltins(Map<String, ImageDef> defs, Consumer<String> warnings) {
        for (var filename : BUILTIN_FILES) {
            var def = loadResource(RESOURCE_DIR + filename, warnings);
            if (def != null) {
                def.setSource("built-in");
                defs.put(def.getName(), def);
            }
        }
    }

    private static void loadUserDefined(Map<String, ImageDef> defs, Consumer<String> warnings) {
        loadFromDirectory(userImagesDir(), defs, warnings);
    }

    private static void loadFromDirectory(Path dir, Map<String, ImageDef> defs, Consumer<String> warnings) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .forEach(path -> {
                        try (var is = Files.newInputStream(path)) {
                            var def = YAML.readValue(is, ImageDef.class);
                            if (def.getName() != null) {
                                def.setSource(path.toAbsolutePath().normalize().toString());
                                defs.put(def.getName(), def);
                            }
                        } catch (IOException e) {
                            warnings.accept("Failed to load " + path.getFileName()
                                    + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            warnings.accept("Warning: failed to scan " + dir + ": " + e.getMessage());
        }
    }

    private static ImageDef loadResource(String path, Consumer<String> warnings) {
        try (InputStream is = ImageDef.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return YAML.readValue(is, ImageDef.class);
        } catch (IOException e) {
            warnings.accept("Failed to load built-in " + path + ": " + e.getMessage());
            return null;
        }
    }
}
