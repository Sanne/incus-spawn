package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The template layer of account selection: {@code accounts:} merged down the chain. */
class TemplateAccountsTest {

    private static ImageDef def(String yaml) throws Exception {
        return ImageDef.parseYaml(yaml);
    }

    @Test
    void templateWithoutAccountsSelectsNothing() throws Exception {
        var d = def("name: tpl-dev\n");
        assertTrue(ImageDef.resolveAccounts(d, Map.of()).isEmpty());
    }

    @Test
    void templateAccountsAreRead() throws Exception {
        var d = def("""
                name: tpl-dev
                accounts:
                  claude: work
                  github: acme-bot
                """);
        assertEquals(Map.of("claude", "work", "github", "acme-bot"),
                ImageDef.resolveAccounts(d, Map.of()));
    }

    /**
     * The merge is per key, not wholesale: a child that re-points one namespace must keep the
     * parent's choice for the others, or every leaf template would have to restate the lot.
     */
    @Test
    void childOverridesOneNamespaceAndInheritsTheRest() throws Exception {
        var parent = def("""
                name: tpl-dev
                accounts:
                  claude: personal
                  github: personal-bot
                """);
        var child = def("""
                name: tpl-java
                parent: tpl-dev
                accounts:
                  claude: work
                """);
        var defs = Map.of("tpl-dev", parent, "tpl-java", child);
        assertEquals(Map.of("claude", "work", "github", "personal-bot"),
                ImageDef.resolveAccounts(child, defs));
    }

    @Test
    void grandchildWinsOverBothAncestors() throws Exception {
        var root = def("name: tpl-minimal\naccounts:\n  claude: a\n");
        var mid = def("name: tpl-dev\nparent: tpl-minimal\naccounts:\n  claude: b\n");
        var leaf = def("name: tpl-java\nparent: tpl-dev\naccounts:\n  claude: c\n");
        var defs = Map.of("tpl-minimal", root, "tpl-dev", mid, "tpl-java", leaf);
        assertEquals(Map.of("claude", "c"), ImageDef.resolveAccounts(leaf, defs));
    }

    @Test
    void blankEntriesAreIgnoredRatherThanPinningToNothing() throws Exception {
        var d = def("""
                name: tpl-dev
                accounts:
                  claude: ""
                  github: acme
                """);
        assertEquals(Map.of("github", "acme"), ImageDef.resolveAccounts(d, Map.of()));
    }

    /**
     * Re-pointing a template at another account changes what gets baked -- the Claude auth mode
     * in isx-env.sh, the git identity in .gitconfig -- so it has to force a rebuild.
     */
    @Test
    void accountSelectionIsPartOfTheContentFingerprint() throws Exception {
        var a = def("name: tpl-dev\naccounts:\n  claude: personal\n");
        var b = def("name: tpl-dev\naccounts:\n  claude: work\n");
        var none = def("name: tpl-dev\n");
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
        assertNotEquals(a.contentFingerprint(Map.of()), none.contentFingerprint(Map.of()));
    }

    @Test
    void overridesBeatTheTemplate() throws Exception {
        var d = def("name: tpl-dev\naccounts:\n  claude: personal\n  github: bot\n");
        assertEquals(Map.of("claude", "work", "github", "bot"),
                AccountSelection.resolve(d, Map.of(), Map.of("claude", "work")));
    }
}
