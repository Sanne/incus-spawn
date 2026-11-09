package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code isx templates edit} and {@code new} use a template name as given: {@code tpl-} is a
 * convention, never added. Before, {@code edit isx-presentation} looked for {@code tpl-isx-presentation}.
 */
@ExtendWith(IsolatedHome.class)
class TemplatesEditByNameTest {

    private static Path userTemplate(String filename, String name) throws IOException {
        var images = Files.createDirectories(Environment.configDir().resolve("images"));
        return Files.writeString(images.resolve(filename), "name: " + name + "\nimage: images:fedora/44\n");
    }

    /** Runs {@code isx templates edit name} with an editor that records the files it opens. */
    private static int edit(String name, List<Path> opened) throws Exception {
        var edit = new TemplatesCommand.Edit();
        edit.name = name;
        edit.editor = file -> {
            opened.add(file);
            return 0;
        };
        edit.reEdit = () -> null;
        var exit = new int[1];
        RecordingOutput.during(() -> exit[0] = edit.doExecute().getResultValue());
        return exit[0];
    }

    @Test
    void aTemplateWithoutTheTplPrefixIsEditedByItsOwnName() throws Exception {
        var file = userTemplate("isx-presentation.yaml", "isx-presentation");
        var opened = new ArrayList<Path>();

        assertEquals(0, edit("isx-presentation", opened));
        assertEquals(List.of(file), opened);
    }

    @Test
    void aFileWithAnOverridesNameThatDefinesAnotherTemplateIsNotOpenedAsTheOverride() throws Exception {
        // dev.yaml is where tpl-dev's override lives: one defining 'dev' must not be opened for tpl-dev
        var file = userTemplate("dev.yaml", "dev");
        var opened = new ArrayList<Path>();

        assertEquals(1, edit("tpl-dev", opened));
        assertTrue(opened.isEmpty(), "opened " + opened);
        assertEquals("name: dev\nimage: images:fedora/44\n", Files.readString(file));
    }

    @Test
    void aBrokenOverrideIsStillOpenedToBeFixed() throws Exception {
        // An override that no longer parses names nothing: it is still the built-in's file to fix
        var images = Files.createDirectories(Environment.configDir().resolve("images"));
        var file = Files.writeString(images.resolve("dev.yaml"), "name: [tpl-dev\n");
        var opened = new ArrayList<Path>();

        edit("tpl-dev", opened);
        assertEquals(List.of(file), opened);
    }

    @Test
    void aNewTemplateMayNotTakeTheFileOfABuiltInsOverride() {
        for (var name : List.of("dev", "java", "minimal", "Dev", "JAVA")) {
            var problem = TemplatesCommand.newTemplateNameProblem(name);
            assertNotNull(problem, name);
            assertTrue(problem.contains(name + ".yaml"), problem);
        }
        assertNull(TemplatesCommand.newTemplateNameProblem("tpl-dev-custom"));
        assertThrows(IOException.class, () -> TemplatesCommand.createTemplateFile("dev", null,
                Environment.configDir().resolve("images")));
        assertFalse(Files.exists(Environment.configDir().resolve("images/dev.yaml")));
    }

    @Test
    void aNewTemplateNameMustBeShortEnoughToRebuild() {
        var longest = "t".repeat(BuildCommand.MAX_TEMPLATE_NAME_LENGTH);
        assertNull(TemplatesCommand.newTemplateNameProblem(longest));
        var problem = TemplatesCommand.newTemplateNameProblem(longest + "t");
        assertNotNull(problem);
        assertTrue(problem.contains("too long"), problem);
    }
}
