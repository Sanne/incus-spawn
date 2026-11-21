package dev.incusspawn.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The docs page a new template's first comment links to holds the full skeleton, line for line. */
@Tag("docs")
class TemplateExamplePageTest {

    @Test
    void theDocsPageHoldsTheFullSkeletonAsItIs() throws Exception {
        var page = Files.readString(Path.of("../docs/TEMPLATE-EXAMPLE.md"));
        var start = page.indexOf("```yaml\n") + "```yaml\n".length();
        var block = page.substring(start, page.indexOf("```", start));
        assertEquals(TemplatesCommand.FULL_SKELETON.replace("tpl-CHANGEME", "tpl-example"), block);
    }
}
