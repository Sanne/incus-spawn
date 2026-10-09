package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.YamlMapper;
import io.hyperfoil.tools.jjq.yaml.YamlOptions;

import java.io.IOException;
import java.io.InputStream;

/**
 * Shared jjq entry points for YAML definitions (Phase 1 of
 * incus-spawn-jjq-migration).
 *
 * <p>One process-wide {@link JqMapper} (thread-safe; construction does
 * {@code ServiceLoader} discovery, so it is not built per parse). All
 * methods take {@link YamlOptions#STRICT} (duplicate-key detection, matching
 * the old Jackson {@code STRICT_DUPLICATE_DETECTION}) and translate jjq's
 * unchecked failures to {@link IOException}, preserving every caller's
 * contract (warnings, {@code parseFailed}, {@code throws} signatures).
 * {@link YamlErrors#friendly} unwraps the cause again for rendering.
 */
public final class Jjq {

    /**
     * Explicitly wired mapper: generated mappings pre-registered. No Jackson
     * bridge (main is annotation-free since the removal slices) and no
     * {@code ServiceLoader} scan or {@code Class.forName()} discovery at
     * runtime — the form native-image needs (see Phase 4).
     * Behavior-identical to {@code JqMapper.create()} on the JVM; the suites
     * pin that.
     */
    private static final JqMapper MAPPER = buildMapper();

    /**
     * Checked-exception databind over {@link #mapper()} with process-wide
     * {@link YamlOptions#STRICT}. Replaces eight hand-rolled overloads that
     * each repeated the same try/catch (jjq#117); cause chains are preserved
     * for {@link YamlErrors#friendly}.
     */
    private static final YamlMapper YAML = new YamlMapper(MAPPER, YamlOptions.STRICT);

    private static JqMapper buildMapper() {
        var builder = JqMapper.builder();
        dev.incusspawn.config.JqMappingRegistry.registerAll(builder);
        dev.incusspawn.tool.JqMappingRegistry.registerAll(builder);
        return builder.build();
    }

    private Jjq() {}

    public static JqMapper mapper() {
        return MAPPER;
    }

    public static ImageDef imageDef(String yaml) throws IOException {
        return YAML.readValue(yaml, ImageDef.class);
    }

    public static ImageDef imageDef(InputStream in) throws IOException {
        return YAML.readValue(in, ImageDef.class);
    }

    public static ImageDef imageDef(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, ImageDef.class);
    }

    public static ToolDef toolDef(String yaml) throws IOException {
        return YAML.readValue(yaml, ToolDef.class);
    }

    public static ToolDef toolDef(InputStream in) throws IOException {
        return YAML.readValue(in, ToolDef.class);
    }

    public static ProjectConfig projectConfig(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, ProjectConfig.class);
    }

    public static SpawnConfig spawnConfig(String yaml) throws IOException {
        return YAML.readValue(yaml, SpawnConfig.class);
    }

    public static SpawnConfig spawnConfig(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, SpawnConfig.class);
    }

    /** Serialize a config for {@code save()}: block-style YAML, same shape Jackson wrote. */
    public static String toYaml(SpawnConfig config) {
        return YAML.writeValueAsString(config);
    }

    /** Write a config file straight from the tree: no intermediate String. */
    public static void writeYaml(java.nio.file.Path file, SpawnConfig config) throws IOException {
        YAML.writeValue(file, config);
    }

    // ---- JSON reads (Incus API responses) ----
    //
    // Jackson parity notes: daemon responses are machine-generated and never carry
    // explicit nulls (Incus omits rather than nulls), so unlike
    // {@link AccountResolver#navigate} — which distinguishes missing ("") from
    // explicit null ("null") because user files can mean both — these treat a null
    // like a missing node. Containers read as "" (Jackson's asText on a container).

    /** Jackson's {@code asLong(dflt)}. */
    public static long longOr(JqValue value, long dflt) {
        return value != null && value.isIntegralNumber() ? value.longValue() : dflt;
    }

    /** Jackson's {@code isMissingNode()}: absent (or, for responses, null). */
    public static boolean isMissing(JqValue value) {
        return value == null || value.isNull();
    }
}
