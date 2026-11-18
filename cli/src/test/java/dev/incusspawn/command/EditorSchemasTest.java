package dev.incusspawn.command;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolDefValidator;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqName;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON Schemas editors use, {@code site/schema/{template,tool,config,mcp}.json}, published at
 * {@code https://isx.run/schema/}. The template schema is named by the modeline of the skeleton
 * {@code isx templates new} writes. They are written by hand, so this test is what keeps them true to the
 * loader: their keys are those the generated mappings bind, each has a description, their value sets are
 * the code's, and the built-in templates and tools and the skeleton's examples, uncommented, validate.
 * <p>
 * The schema is stricter than the loader in one way, on purpose: the loader warns about an unknown key
 * and goes on, and the editor underlines it.
 */
@Tag("docs")
class EditorSchemasTest {

    private static final Path SCHEMAS = Path.of("..", "site", "schema");
    private static final Path SCHEMA = SCHEMAS.resolve("template.json");

    /** Bound for isx's own records, never read from what a user writes. */
    private static final Set<String> INTERNAL = Set.of("HostResource.confinedTo");

    private static final Schema COMPILED = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
            .getSchema(read(), InputFormat.JSON);

    private static String read() {
        try {
            return Files.readString(SCHEMA);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theSchemaNamesEveryKeyTheLoaderBindsAndNoOther() {
        var problems = new ArrayList<String>();
        compare(ImageDef.class, JqYaml.parse(read()), "", true, Set.of(), problems);
        assertEquals(List.of(), problems);
    }

    @Test
    void theToolSchemaNamesEveryKeyTheLoaderBindsAndNoOther() throws IOException {
        var problems = new ArrayList<String>();
        compare(ToolDef.class, JqYaml.parse(Files.readString(SCHEMAS.resolve("tool.json"))), "", true, Set.of(), problems);
        assertEquals(List.of(), problems);
    }

    @Test
    void theMcpSchemaNamesEveryKeyTheLoaderBindsAndNoOther() throws IOException {
        var problems = new ArrayList<String>();
        // The retired keys load through the any-setter and have no field
        compare(McpConfig.class, JqYaml.parse(Files.readString(SCHEMAS.resolve("mcp.json"))), "", false,
                privateSet(McpConfig.class, "RETIRED"), problems);
        assertEquals(List.of(), problems);
    }

    @Test
    void theConfigSchemaNamesEveryTopLevelKeyTheLoaderBinds() throws IOException {
        var problems = new ArrayList<String>();
        // Namespaces and sections are open: a tool adds its own, and the typed ones hold raw values
        compare(SpawnConfig.class, JqYaml.parse(Files.readString(SCHEMAS.resolve("config.json"))), "", false,
                Set.of(), problems);
        assertEquals(List.of(), problems);
    }

    private static void compare(Class<?> type, JqValue schema, String prefix, boolean recurse,
                                Set<String> exempt, List<String> problems) {
        var properties = schema.path("properties");
        var bound = new LinkedHashSet<String>();
        for (var field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(JqIgnore.class)
                    || INTERNAL.contains(type.getSimpleName() + "." + field.getName())) continue;
            var jqName = field.getAnnotation(JqName.class);
            var name = jqName != null ? jqName.value() : field.getName();
            bound.add(name);
            var property = properties.path(name);
            if (property.isMissingNode()) {
                problems.add(prefix + name + ": bound by the loader, absent from the schema");
                continue;
            }
            if (property.path("description").isMissingNode()) problems.add(prefix + name + ": no description");
            if (recurse && mappedType(field.getGenericType()) instanceof Class<?> entry) {
                var inner = !property.path("items").isMissingNode() ? property.path("items")
                        : property.path("additionalProperties").isObject() ? property.path("additionalProperties") : property;
                compare(entry, inner, prefix + name + ".", true, Set.of(), problems);
            }
        }
        if (properties.isObject()) {
            for (var key : properties.asMap().keySet()) {
                if (!bound.contains(key) && !exempt.contains(key)) problems.add(prefix + key + ": in the schema, not bound by the loader");
            }
        }
    }

    private static Class<?> mappedType(Type type) {
        if (type instanceof ParameterizedType p) {
            var args = p.getActualTypeArguments();
            return mappedType(args[args.length - 1]);
        }
        return type instanceof Class<?> c && c.isAnnotationPresent(JqMapped.class) ? c : null;
    }

    @Test
    void theValueSetsAreTheCodes() {
        var schema = JqYaml.parse(read());
        assertEquals(Set.of("container", "vm", "kvm"), enumOf(schema.path("properties").path("type")));
        assertEquals(Arrays.stream(EnvEntry.Strategy.class.getEnumConstants())
                        .map(s -> enumName(s)).collect(java.util.stream.Collectors.toSet()),
                enumOf(schema.path("properties").path("env").path("items").path("properties").path("strategy")));
        assertEquals(hostResourceModes(),
                enumOf(schema.path("properties").path("host-resources").path("items").path("properties").path("mode")));
    }

    private static String enumName(EnvEntry.Strategy strategy) {
        try {
            return EnvEntry.Strategy.class.getField(strategy.name()).getAnnotation(JqName.class).value();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> hostResourceModes() {
        return privateSet(ImageDef.class.getClassLoader(), "dev.incusspawn.config.TemplateValidator", "VALID_HR_MODES");
    }

    private static Set<String> privateSet(Class<?> owner, String name) {
        return privateSet(owner.getClassLoader(), owner.getName(), name);
    }

    /** A constant the code validates against, so the schema's value set is compared with it, never retyped. */
    @SuppressWarnings("unchecked")
    private static Set<String> privateSet(ClassLoader loader, String owner, String name) {
        try {
            var field = Class.forName(owner, true, loader).getDeclaredField(name);
            field.setAccessible(true);
            return (Set<String>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theToolSchemasValueSetsAreTheCodes() throws IOException {
        var schema = JqYaml.parse(Files.readString(SCHEMAS.resolve("tool.json")));
        var proxy = schema.path("properties").path("proxy").path("properties");
        assertEquals(privateSet(ToolDefValidator.class, "VALID_AUTH_TYPES"),
                enumOf(proxy.path("auth").path("items").path("properties").path("type")));
        assertEquals(privateSet(ToolDefValidator.class, "VALID_PARAM_TYPES"),
                enumOf(schema.path("properties").path("parameters").path("additionalProperties")
                        .path("properties").path("type")));
        var architectures = privateSet(ToolDefValidator.class, "VALID_ARCHITECTURES");
        assertEquals(architectures, enumOf(schema.path("properties").path("architectures").path("items")));
        assertEquals(architectures, enumOf(schema.path("properties").path("downloads").path("items")
                .path("properties").path("arch")));
    }

    @Test
    void theBuiltInToolsValidateAgainstTheToolSchema() throws IOException {
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
                .getSchema(Files.readString(SCHEMAS.resolve("tool.json")), InputFormat.JSON);
        for (var file : ToolDefLoader.BUILTIN_TOOLS) {
            var path = Path.of("..", "common", "src", "main", "resources", "tools", file);
            assertTrue(Files.exists(path), path.toString());
            var errors = schema.validate(JqYaml.parse(Files.readString(path)).toJsonString(), InputFormat.JSON);
            assertTrue(errors.isEmpty(), file + ": " + errors);
        }
    }

    @Test
    void theMcpAndConfigSchemasTakeTheirDocumentedExamples() throws IOException {
        var mcp = compiled("mcp.json");
        var config = compiled("config.json");
        assertEquals(List.of(), errors(mcp, """
                templates: [tpl-java, tpl-dev]
                max-instances: 8
                summary-model: haiku
                host-tools:
                  instance-inventory:
                    command: ~/incus-spawn/scripts/instance-inventory.sh
                    description: Read-only inventory of stopped isx instances
                    timeout: 300
                    min-interval: 10
                """));
        assertFalse(errors(mcp, "templatez: [a]\n").isEmpty(), "a mistyped key makes mcp.yaml invalid");
        assertFalse(errors(mcp, "host-tools: {x: {description: d}}\n").isEmpty(), "a host tool needs a command");
        assertEquals(List.of(), errors(config, """
                claude:
                  accounts:
                    acme: {type: vertex, cloudMlRegion: europe-west1, vertexProjectId: acme-prod}
                  default: acme
                github: {accounts: {bot: {token: t, email: b@example.com}}, default: bot}
                git-cache: {snapshot-max-mib: 16384, max-age-days: 7}
                git-transfer: {stall: 5m, timeout: 3h}
                host-routes:
                  work.isx.internal: {target: "127.0.0.1:7070", templates: [tpl-isx-agent]}
                exposed-host-ports: [11434]
                maven-mirrors: [{repository: "https://a/", mirror: "https://b/"}]
                drivers: {orca: {destroy: true}}
                host-ca: {propagate: true}
                artifactory: {token: x}
                """));
        assertFalse(errors(config, "host-ca: {propagate: maybe}\n").isEmpty(), "a wrong value is flagged");
    }

    private static Schema compiled(String file) throws IOException {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
                .getSchema(Files.readString(SCHEMAS.resolve(file)), InputFormat.JSON);
    }

    private static List<String> errors(Schema schema, String yaml) {
        return schema.validate(JqYaml.parse(yaml).toJsonString(), InputFormat.JSON).stream()
                .map(Object::toString).toList();
    }

    private static Set<String> enumOf(JqValue property) {
        var values = new LinkedHashSet<String>();
        property.path("enum").asList().forEach(v -> values.add(v.asText()));
        return values;
    }

    @Test
    void theBuiltInTemplatesValidate() throws Exception {
        for (var file : ImageDef.BUILTIN_FILES) {
            try (var in = ImageDef.class.getResourceAsStream("/images/" + file)) {
                assertValid(file, new String(in.readAllBytes()));
            }
        }
    }

    @Test
    void theSkeletonsExamplesUncommentedValidate() {
        assertValid("skeleton", TemplateSkeletonTest.uncommented(TemplatesCommand.SKELETON));
    }

    @Test
    void anUnknownKeyAndAWrongValueAreRefused() {
        assertFalse(errors("name: tpl-x\npackges: [a]\n").isEmpty(), "a misspelled key is flagged");
        assertFalse(errors("name: tpl-x\ntype: lxc\n").isEmpty(), "a type outside the set is flagged");
        assertFalse(errors("description: no name\n").isEmpty(), "name is required");
        assertTrue(errors("name: tpl-x\ndeprecated: true\ntools: [a, {b: {c: d}}]\n").isEmpty());
    }

    private static void assertValid(String what, String yaml) {
        assertEquals(List.of(), errors(yaml), what + " against the template schema");
    }

    private static List<String> errors(String yaml) {
        return COMPILED.validate(JqYaml.parse(yaml).toJsonString(), InputFormat.JSON).stream()
                .map(Object::toString).toList();
    }
}
