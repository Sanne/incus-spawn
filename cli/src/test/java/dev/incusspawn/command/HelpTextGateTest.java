package dev.incusspawn.command;

import dev.incusspawn.IncusSpawn;
import org.aesh.command.CommandDefinition;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code --help} text of every command, hidden ones included, held to the mechanical rules of
 * .claude/rules/interface-text.md that a single description can break (#2542): one word for one
 * thing, and the verb negated rather than the noun. The text is read from the annotations aesh
 * renders, so the whole tree is read without running a command.
 */
class HelpTextGateTest {

    /**
     * Words of the vocabulary's "Never" column, the first run's word for the host, which
     * {@code --help} does not use (Sanne, #2673), and the proxy's old name: "MITM" says how the
     * proxy works, not what it does for the person.
     */
    private static final Pattern NEVER = Pattern.compile("(?i)\\b(machines?|box|boxes|this computer|MITM)\\b");
    /** "container" only where it contrasts with a VM (writing.md "One word per concept"). */
    private static final Pattern CONTAINER = Pattern.compile("(?i)\\bcontainers?\\b");
    /** A literal the person types, such as 'none' or '-': a value, not a negation. */
    private static final Pattern LITERAL = Pattern.compile("(?<!\\w)'[^']*'(?!\\w)");
    private static final Pattern VM = Pattern.compile("(?i)\\bVMs?\\b");
    /**
     * Texts that are meant as they are although a check flags them, each with why. A text that
     * reads clearer as it was is listed here rather than reworded to pass (interface-text.md
     * "Rewording a text").
     */
    private static final Map<String, String> MEANT = Map.of(
            "isx branch --gui", "a container template is the one GUI passthrough is a default for; naming the VM adds a clause and no meaning");

    @Test
    void everyCommandsHelpKeepsToTheVocabularyAndNegatesTheVerb() {
        var faults = new ArrayList<String>();
        help().forEach((where, text) -> {
            if (MEANT.containsKey(where)) return;
            if (NEVER.matcher(text).find()) faults.add(where + ": a word help never uses: " + text);
            if (CONTAINER.matcher(text).find() && !VM.matcher(text).find())
                faults.add(where + ": 'container' where no VM is contrasted: " + text);
            if (TranscriptGate.negatesANoun(LITERAL.matcher(text).replaceAll("x"))) faults.add(where + ": a negated noun, where the verb should be negated: " + text);
        });
        assertEquals(List.of(), faults, String.join("\n", faults));
    }

    /** The gate reads the vm commands too, which only macOS's tree holds, and the hidden ones. */
    @Test
    void theGateReadsTheWholeTree() {
        var help = help();
        assertTrue(help.containsKey("isx vm restart"), help.keySet().toString());
        assertTrue(help.containsKey("isx ssh-proxy --start"), help.keySet().toString());
        assertTrue(help.containsKey("isx branch --airgap"), help.keySet().toString());
    }

    /** Each description, keyed by where it is shown: {@code isx branch} or {@code isx branch --airgap}. */
    static Map<String, String> help() {
        var help = new LinkedHashMap<String, String>();
        read(IncusSpawn.IncusSpawnCommand.class, "", help);
        return help;
    }

    private static void read(Class<?> command, String parent, Map<String, String> help) {
        var definition = command.getAnnotation(CommandDefinition.class);
        var name = definition.name();
        var description = definition.description();
        var children = definition.groupCommands();
        var path = parent.isEmpty() ? name : parent + " " + name;
        help.put(path, description);
        for (var type = command; type != null && type != Object.class; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                for (var annotation : field.getAnnotations()) {
                    var text = descriptionOf(annotation);
                    if (text == null || text.isBlank()) continue;
                    var option = optionName(annotation);
                    help.put(path + " " + (option.isEmpty() ? "<" + field.getName() + ">" : "--" + option), text);
                }
            }
        }
        for (var child : children) read(child, path, help);
    }

    private static String descriptionOf(Annotation annotation) {
        return (String) attribute(annotation, "description");
    }

    private static String optionName(Annotation annotation) {
        var name = attribute(annotation, "name");
        return name instanceof String s ? s : "";
    }

    private static Object attribute(Annotation annotation, String attribute) {
        try {
            Method m = annotation.annotationType().getMethod(attribute);
            return m.getReturnType() == String.class ? m.invoke(annotation) : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
