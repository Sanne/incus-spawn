package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplatesNameResolutionTest {

    private static final Set<String> KNOWN = Set.of("tpl-java", "isx-presentation", "web", "tpl-web");

    @Test
    void aTemplateWithoutThePrefixIsFoundByItsOwnName() {
        // 'tpl-' is a convention: editing 'isx-presentation' must not look for 'tpl-isx-presentation'
        assertEquals("isx-presentation", TemplatesCommand.resolveExisting("isx-presentation", KNOWN));
    }

    @Test
    void theShorthandStillFindsThePrefixedTemplate() {
        assertEquals("tpl-java", TemplatesCommand.resolveExisting("java", KNOWN));
        assertEquals("tpl-java", TemplatesCommand.resolveExisting("tpl-java", KNOWN));
    }

    @Test
    void anExactNameWinsOverTheShorthand() {
        assertEquals("web", TemplatesCommand.resolveExisting("web", KNOWN));
    }

    @Test
    void anUnknownNameIsReportedInItsPrefixedForm() {
        assertEquals("tpl-nope", TemplatesCommand.resolveExisting("nope", KNOWN));
    }
}
