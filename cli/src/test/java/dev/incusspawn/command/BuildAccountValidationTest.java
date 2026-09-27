package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx build} checks template accounts only for the templates it is about to build, so a
 * typo in an unrelated definition cannot block every build (#805).
 */
@ExtendWith(IsolatedHome.class)
class BuildAccountValidationTest {

    private Map<String, ImageDef> defs;

    @BeforeEach
    void setUp() throws Exception {
        IsolatedHome.seed("""
                github:
                  token: "ghp_flat"
                """);
        defs = Map.of(
                "tpl-minimal", ImageDef.parseYaml("name: tpl-minimal\n"),
                "tpl-isx", ImageDef.parseYaml("name: tpl-isx\nparent: tpl-minimal\n"),
                "tpl-q-generic", ImageDef.parseYaml(
                        "name: tpl-q-generic\nparent: tpl-minimal\naccounts:\n  github: q\n"),
                "tpl-q-child", ImageDef.parseYaml("name: tpl-q-child\nparent: tpl-q-generic\n"));
    }

    @Test
    void unrelatedTemplateWithUnknownAccountDoesNotBlockTheBuild() {
        assertNull(BuildCommand.validateTemplateAccounts(List.of("tpl-minimal", "tpl-isx"), defs));
    }

    @Test
    void unknownAccountInATemplateBeingBuiltIsReported() {
        var error = BuildCommand.validateTemplateAccounts(List.of("tpl-minimal", "tpl-q-generic"), defs);
        assertNotNull(error);
        assertTrue(error.startsWith("template 'tpl-q-generic': "), error);
        assertTrue(error.contains("'q'"), error);
    }

    /** A descendant inherits the ancestor's selection, so building it alone is caught too. */
    @Test
    void unknownAccountInheritedFromAnAncestorIsReported() {
        var error = BuildCommand.validateTemplateAccounts(List.of("tpl-q-child"), defs);
        assertNotNull(error);
        assertTrue(error.startsWith("template 'tpl-q-child': "), error);
    }

    @Test
    void configuredAccountPasses() {
        assertNull(BuildCommand.validateTemplateAccounts(List.of("tpl-isx"),
                Map.of("tpl-isx", parse("name: tpl-isx\naccounts:\n  github: default\n"))));
    }

    private static ImageDef parse(String yaml) {
        try {
            return ImageDef.parseYaml(yaml);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
