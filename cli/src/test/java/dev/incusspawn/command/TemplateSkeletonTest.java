package dev.incusspawn.command;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.EnvEntryListConverter;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SkillsDefConverter;
import dev.incusspawn.config.TemplateValidator;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqName;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skeleton {@code isx templates new} and the TUI write is the template format's reference
 * (#1386): every field the parser reads is in it, and its examples, uncommented, are a valid
 * template. The field names are those the generated mappings bind ({@code @JqName}, else the
 * field's own, never {@code @JqIgnore}) and the env and skills converters' keys, so a field
 * added to {@link ImageDef} or one of its entries without a line in the skeleton fails here.
 * Tool parameters are each tool's own and have no fixed keys.
 */
class TemplateSkeletonTest {

    /** Bound for isx's own records, never read from what a user writes. */
    private static final Set<Field> INTERNAL = Set.of(field(ImageDef.HostResource.class, "confinedTo"));

    /** The first line of an example: a commented-out top-level key. Prose before it in its block stays a comment. */
    private static final Pattern EXAMPLE_START = Pattern.compile("# [a-z][a-z0-9_-]*:( .*)?");

    @Test
    void everyFieldTheParserReadsIsInTheSkeleton() {
        var tree = JqYaml.parse(uncommented(TemplatesCommand.SKELETON));
        var missing = new ArrayList<String>();
        absentFields(ImageDef.class, tree, "", missing);
        // Fields bound by a converter name their entries' keys there, not in Java fields.
        absent(keys(new EnvEntryListConverter().toJqValue(List.of(EnvEntry.set("A", "b")))),
                keys(tree.asMap().get("env"))).forEach(key -> missing.add("env." + key));
        absent(keys(new SkillsDefConverter().toJqValue(new ImageDef.SkillsDef("r", List.of()))),
                keys(tree.asMap().get("skills"))).forEach(key -> missing.add("skills." + key));
        assertEquals(List.of(), missing, "fields the parser reads that the new-template skeleton does not show");
    }

    @Test
    void theSkeletonsExamplesUncommentedAreAValidTemplate(@TempDir Path dir) throws Exception {
        var file = dir.resolve("all.yaml");
        Files.writeString(file, uncommented(TemplatesCommand.SKELETON));
        var result = TemplateValidator.validate(file, Map.of("tpl-minimal", new ImageDef()));
        assertEquals(List.of(), result.errors());
        assertFalse(ImageDef.parseFile(file).getRepos().isEmpty());
    }

    @Test
    void aNewTemplateKeepsTheNameItWasGiven(@TempDir Path dir) throws Exception {
        // 'tpl-' is a convention, never added: the name and the file are what was typed
        var file = TemplatesCommand.createTemplateFile("isx-presentation", null, dir);
        assertEquals(dir.resolve("isx-presentation.yaml"), file);
        assertEquals("isx-presentation", ImageDef.parseFile(file).getName());
    }

    @Test
    void aNewTemplateNeedsANameThatIsAlsoAFilename(@TempDir Path dir) {
        // The name becomes the filename: one that is not a valid template name never touches the disk
        var e = assertThrows(java.io.IOException.class,
                () -> TemplatesCommand.createTemplateFile("../escape", null, dir));
        assertTrue(e.getMessage().contains("not a valid Incus instance name"), e.getMessage());
        assertFalse(Files.exists(dir.getParent().resolve("escape.yaml")));
    }

    @Test
    void aNewTemplateNamesItsParent(@TempDir Path dir) throws Exception {
        var file = TemplatesCommand.createTemplateFile("tpl-demo", "tpl-java", dir);
        var def = ImageDef.parseFile(file);
        assertEquals("tpl-demo", def.getName());
        assertEquals("tpl-java", def.getParent());
        assertEquals(List.of(), TemplateValidator.validate(file, Map.of("tpl-java", new ImageDef())).errors());
    }

    /**
     * The skeleton with its examples uncommented: in each blank-line-separated block, every line
     * from the first commented-out top-level key on loses its {@code "# "}.
     */
    static String uncommented(String skeleton) {
        var out = new StringBuilder();
        var example = false;
        for (var line : skeleton.split("\n", -1)) {
            if (line.isBlank()) example = false;
            else if (EXAMPLE_START.matcher(line).matches()) example = true;
            if (example && line.startsWith("#")) line = line.startsWith("# ") ? line.substring(2) : line.substring(1);
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * The YAML names of {@code type}'s bound fields, as its generated mapping names them, that
     * {@code tree} (a mapping, or a list of them) lacks; recurses into fields holding
     * {@code @JqMapped} entries, so a new nested key is caught as well as a new field.
     */
    private static void absentFields(Class<?> type, JqValue tree, String prefix, List<String> missing) {
        var present = keys(tree);
        for (var field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(JqIgnore.class)
                    || INTERNAL.contains(field)) continue;
            var jqName = field.getAnnotation(JqName.class);
            var name = jqName != null ? jqName.value() : field.getName();
            if (!present.contains(name)) {
                missing.add(prefix + name);
            } else if (mappedType(field.getGenericType()) instanceof Class<?> entry) {
                var entries = tree.isArray()
                        ? JqArray.of(tree.asList().stream()
                                .map(item -> item.asMap().get(name)).filter(Objects::nonNull).toList())
                        : tree.asMap().get(name);
                absentFields(entry, entries, prefix + name + ".", missing);
            }
        }
    }

    /** The {@code @JqMapped} type a field holds, itself or as a list's element or a map's value; null otherwise. */
    private static Class<?> mappedType(Type type) {
        if (type instanceof ParameterizedType p) {
            var args = p.getActualTypeArguments();
            return mappedType(args[args.length - 1]);
        }
        return type instanceof Class<?> c && c.isAnnotationPresent(JqMapped.class) ? c : null;
    }

    private static List<String> absent(Set<String> expected, Set<String> actual) {
        return expected.stream().filter(k -> !actual.contains(k)).toList();
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The keys of a mapping, or of every mapping in a list; none for anything else. */
    private static Set<String> keys(JqValue value) {
        var keys = new LinkedHashSet<String>();
        if (value == null) return keys;
        if (value.isObject()) keys.addAll(value.asMap().keySet());
        if (value.isArray()) value.asList().forEach(item -> keys.addAll(keys(item)));
        return keys;
    }
}
