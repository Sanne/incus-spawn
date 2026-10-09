package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures the full build context of a template: all image definitions
 * in the parent chain, all YAML tool definitions used, and the original
 * source locations. Stored as JSON in Incus container metadata so that
 * out-of-scope templates can be displayed and rebuilt.
 */
@JqMapped
public class BuildSource {

    private Map<String, ImageDef> definitions = new LinkedHashMap<>();
    private Map<String, ToolDef> tools = new LinkedHashMap<>();
    private Map<String, ToolInstance> toolInstances = new LinkedHashMap<>();
    private Map<String, String> sources = new LinkedHashMap<>();
    /** Project directory of each project-local definition; see {@link ImageDef#getProjectRoot()}. */
    private Map<String, String> projectRoots = new LinkedHashMap<>();
    /**
     * The newer base image release a build from scratch tracked in place of its root's
     * {@code image_tag}, which {@code definitions} keeps as loaded (#1244); null if it used that tag.
     */
    @JqInclude(JqInclude.Include.NON_NULL)
    private String trackedImageTag;
    /** Original allowed roots; old snapshots without this provenance fail closed for host Git sources. */
    private Map<String, String> hostRepoRoots = new LinkedHashMap<>();

    public BuildSource() {}

    public BuildSource(Map<String, ImageDef> definitions, Map<String, ToolDef> tools,
                       Map<String, ToolInstance> toolInstances, Map<String, String> sources) {
        this.definitions = definitions != null ? definitions : new LinkedHashMap<>();
        this.tools = tools != null ? tools : new LinkedHashMap<>();
        this.toolInstances = toolInstances != null ? toolInstances : new LinkedHashMap<>();
        this.sources = sources != null ? sources : new LinkedHashMap<>();
        this.definitions.forEach((name, def) -> {
            if (def.getHostRepoRoot() != null) hostRepoRoots.put(name, def.getHostRepoRoot().toString());
        });
    }

    public Map<String, ImageDef> getDefinitions() { return definitions; }
    public void setDefinitions(Map<String, ImageDef> definitions) {
        this.definitions = definitions != null ? definitions : new LinkedHashMap<>();
    }

    public Map<String, ToolDef> getTools() { return tools; }
    public void setTools(Map<String, ToolDef> tools) {
        this.tools = tools != null ? tools : new LinkedHashMap<>();
    }

    public Map<String, String> getSources() { return sources; }
    public void setSources(Map<String, String> sources) {
        this.sources = sources != null ? sources : new LinkedHashMap<>();
    }

    public Map<String, String> getProjectRoots() { return projectRoots; }
    public void setProjectRoots(Map<String, String> projectRoots) {
        this.projectRoots = projectRoots != null ? projectRoots : new LinkedHashMap<>();
    }

    public String getTrackedImageTag() { return trackedImageTag; }
    public void setTrackedImageTag(String trackedImageTag) { this.trackedImageTag = trackedImageTag; }
    public Map<String, String> getHostRepoRoots() { return hostRepoRoots; }
    public void setHostRepoRoots(Map<String, String> hostRepoRoots) {
        this.hostRepoRoots = hostRepoRoots != null ? hostRepoRoots : new LinkedHashMap<>();
    }

    public Map<String, ToolInstance> getToolInstances() { return toolInstances; }
    public void setToolInstances(Map<String, ToolInstance> instances) {
        this.toolInstances = instances != null ? instances : new LinkedHashMap<>();
    }

    @JqMapped
    public static class ToolInstance {
        private String name;
        private Map<String, String> parameterValues = Map.of();

        public ToolInstance() {}

        public ToolInstance(String name, Map<String, String> parameterValues) {
            this.name = name;
            this.parameterValues = parameterValues != null ? parameterValues : Map.of();
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Map<String, String> getParameterValues() { return parameterValues; }
        public void setParameterValues(Map<String, String> values) {
            this.parameterValues = values != null ? values : Map.of();
        }
    }

    public String descriptionFor(String templateName) {
        var def = definitions.get(templateName);
        return def != null && def.getDescription() != null ? def.getDescription() : "";
    }

    public String toJson() {
        return Jjq.mapper().toJson(this);
    }

    /**
     * Metadata written before {@code projectRoots} existed only has the definition's file,
     * {@code <project>/.incus-spawn/images/<name>.yaml} for a project-local one.
     */
    private static String legacyProjectRoot(String source) {
        if (source == null || !source.startsWith("/")) return null;
        var images = Path.of(source).getParent();
        if (images == null || !images.endsWith(ImageDef.projectImagesDir())) return null;
        var root = images.getParent().getParent();
        return root != null ? root.toString() : null;
    }

    /** Whether any definition this image was built from was project-local. */
    public boolean usedProjectLocal() {
        return definitions.values().stream().anyMatch(def -> def.getProjectRoot() != null);
    }

    /**
     * {@link #usedProjectLocal()} of the stamped JSON, as {@link #fromJson} reads it. No stamp is
     * false; one that cannot be read is true, since it cannot show otherwise.
     */
    public static boolean usedProjectLocal(String json) {
        if (json == null || json.isBlank()) return false;
        // The same reading as a rebuild's, so the two can never disagree about a definition
        var source = fromJson(json);
        // A project root with no definition beside it still says a project was there
        return source == null || source.usedProjectLocal() || !source.getProjectRoots().isEmpty();
    }

    /**
     * The file {@code template} was built from, read from the stamped JSON without binding the
     * whole build context; null if the stamp is missing, unreadable or does not name it.
     */
    public static String sourceOf(String json, String template) {
        if (json == null || json.isBlank()) return null;
        try {
            var node = JqValues.parseStrict(json).path("sources").path(template);
            return node.isString() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static BuildSource fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            var parsed = io.hyperfoil.tools.jjq.value.JqValues.parseStrict(json);
            // Jackson refuses a scalar or array for a POJO; a lenient bind would
            // answer an empty snapshot instead of reporting the corrupt metadata.
            if (!(parsed instanceof io.hyperfoil.tools.jjq.value.JqObject)) return null;
            var bs = Jjq.mapper().fromJqValue(parsed, BuildSource.class);
            // Restore source locations on deserialized ImageDefs
            for (var entry : bs.definitions.entrySet()) {
                var source = bs.sources.get(entry.getKey());
                entry.getValue().setSource(source != null ? source : "stored");
                // A rebuild from stored metadata must stay as confined as the original build.
                var projectRoot = bs.projectRoots.get(entry.getKey());
                if (projectRoot == null) projectRoot = legacyProjectRoot(source);
                if (projectRoot != null) entry.getValue().setProjectRoot(Path.of(projectRoot));
                var hostRepoRoot = bs.hostRepoRoots.get(entry.getKey());
                entry.getValue().setHostRepoRoot(hostRepoRoot == null ? null : Path.of(hostRepoRoot));
            }
            return bs;
        } catch (Exception e) {
            return null;
        }
    }
}
