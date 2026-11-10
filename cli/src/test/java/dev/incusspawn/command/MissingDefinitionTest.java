package dev.incusspawn.command;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Why a built template has no loaded definition (#2057), from the file its stamp records. */
class MissingDefinitionTest {

    private static final String FILE = "/home/u/.config/incus-spawn/images/presentation.yaml";

    private static String stamp(String name, String file) {
        var def = new ImageDef();
        def.setName(name);
        return new BuildSource(Map.of(name, def), Map.of(), Map.of(), file == null ? Map.of() : Map.of(name, file))
                .toJson();
    }

    private static ImageDef loaded(String name, String source) {
        var def = new ImageDef();
        def.setName(name);
        def.setSource(source);
        return def;
    }

    @Test
    void aTemplateWithADefinitionIsNotMissingOne() {
        var defs = Map.of("tpl-a", loaded("tpl-a", FILE));
        assertNull(MissingDefinition.of("tpl-a", stamp("tpl-a", FILE), defs, p -> true));
    }

    @Test
    void theFileNowDefiningAnotherNameIsARename() {
        var defs = Map.of("tpl-new", loaded("tpl-new", FILE));

        var why = MissingDefinition.of("tpl-old", stamp("tpl-old", FILE), defs, p -> true);

        assertEquals(MissingDefinition.Kind.RENAMED, why.kind());
        assertEquals("tpl-new", why.renamedTo());
        assertEquals(FILE + " now defines tpl-new", why.reason());
        assertTrue(why.advice().startsWith("Build tpl-new instead"), why.advice());
    }

    @Test
    void aFileThatExistsButIsNotLoadedIsOutOfScope() {
        var project = Path.of("/work/proj");
        var def = new ImageDef();
        def.setName("tpl-p");
        def.setProjectRoot(project);
        var file = "/work/proj/.incus-spawn/images/p.yaml";
        var source = new BuildSource(Map.of("tpl-p", def), Map.of(), Map.of(), Map.of("tpl-p", file));
        source.setProjectRoots(Map.of("tpl-p", project.toString()));

        var why = MissingDefinition.of("tpl-p", source.toJson(), Map.of(), Set.of(Path.of(file))::contains);

        assertEquals(MissingDefinition.Kind.OUT_OF_SCOPE, why.kind());
        assertEquals(file + " is not loaded here", why.reason());
        assertEquals("Run isx build tpl-p in /work/proj.", why.advice());
    }

    @Test
    void aMissingFileIsGone() {
        var why = MissingDefinition.of("tpl-a", stamp("tpl-a", FILE), Map.of(), p -> false);

        assertEquals(MissingDefinition.Kind.GONE, why.kind());
        assertEquals(FILE + " no longer exists", why.reason());
    }

    @Test
    void aBuiltInThisIsxNoLongerShipsIsGoneEvenWhenOtherBuiltInsAreLoaded() {
        var defs = Map.of("tpl-minimal", loaded("tpl-minimal", "built-in"));

        var why = MissingDefinition.of("tpl-retired", stamp("tpl-retired", "built-in"), defs, p -> true);

        assertEquals(MissingDefinition.Kind.GONE, why.kind());
        assertEquals("this isx has no built-in of that name", why.reason());
    }

    @Test
    void aStampThatRecordsNoFileIsGone() {
        var why = MissingDefinition.of("tpl-a", stamp("tpl-a", null), Map.of(), p -> true);
        assertEquals(MissingDefinition.Kind.GONE, why.kind());
        assertEquals("its build recorded no definition file", why.reason());
    }

    /** No stamp, or one that defines only the templates an instance was branched from: no template of that name. */
    @Test
    void anInstanceWhoseStampDoesNotDefineItIsNoTemplate() {
        assertNull(MissingDefinition.of("tpl-a", null, Map.of(), p -> true));
        assertNull(MissingDefinition.of("my-branch", stamp("tpl-a", FILE), Map.of(), p -> true));
    }
}
