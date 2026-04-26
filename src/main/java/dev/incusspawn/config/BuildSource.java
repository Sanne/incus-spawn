package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.tool.ToolDef;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures the full build context of a template: all image definitions
 * in the parent chain, all YAML tool definitions used, and the original
 * source locations. Stored as JSON in Incus container metadata so that
 * out-of-scope templates can be displayed and rebuilt.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BuildSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Map<String, ImageDef> definitions = new LinkedHashMap<>();
    private Map<String, ToolDef> tools = new LinkedHashMap<>();
    private Map<String, String> sources = new LinkedHashMap<>();

    public BuildSource() {}

    public BuildSource(Map<String, ImageDef> definitions, Map<String, ToolDef> tools,
                       Map<String, String> sources) {
        this.definitions = definitions != null ? definitions : new LinkedHashMap<>();
        this.tools = tools != null ? tools : new LinkedHashMap<>();
        this.sources = sources != null ? sources : new LinkedHashMap<>();
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

    public String descriptionFor(String templateName) {
        var def = definitions.get(templateName);
        return def != null && def.getDescription() != null ? def.getDescription() : "";
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize build source", e);
        }
    }

    public static BuildSource fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            var bs = JSON.readValue(json, BuildSource.class);
            // Restore source locations on deserialized ImageDefs
            for (var entry : bs.definitions.entrySet()) {
                var source = bs.sources.get(entry.getKey());
                entry.getValue().setSource(source != null ? source : "stored");
            }
            return bs;
        } catch (Exception e) {
            return null;
        }
    }
}
