package dev.incusspawn.command;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The template {@code TemplatesNewFromTest} hands {@code isx templates new --from} is the README
 * Quick Start's own (#2076), so what that test shows working is what a reader saves.
 */
@Tag("docs")
class QuickStartFromFileDocsTest {

    @Test
    void theTestsTemplateIsTheQuickStarts() throws IOException {
        var readme = Files.readString(Path.of("..", "README.md"));
        var block = "```yaml\n" + TemplatesNewFromTest.QUICK_START + "```\n";
        assertTrue(readme.contains(block), "README.md's Quick Start no longer shows:\n" + block);
    }
}
