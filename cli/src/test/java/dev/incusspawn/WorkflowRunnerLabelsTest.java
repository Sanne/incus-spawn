package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitHub retires macOS runner images, and a job on a removed label waits for a runner that never
 * comes. The release matrix only runs on {@code v*} tags, so a retired label there is found by the
 * next release, which then ships no macOS binaries (#1000). A pinned version, never
 * {@code macos-latest}, keeps the released binary's minimum macOS from moving silently.
 */
class WorkflowRunnerLabelsTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");

    /** Labels GitHub has retired or announced for retirement: {@code macos-14} and older. */
    private static final Pattern UNUSABLE = Pattern.compile("\\bmacos-(?:1[0-4]|latest)\\b");

    @Test
    void noWorkflowUsesARetiredOrMovingMacOsLabel() throws IOException {
        var offenders = new ArrayList<String>();
        try (var files = Files.list(WORKFLOWS)) {
            for (var file : files.filter(f -> f.toString().matches(".*\\.ya?ml")).sorted().toList()) {
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    var line = lines.get(i);
                    if (!line.strip().startsWith("#") && UNUSABLE.matcher(line).find()) {
                        offenders.add(file.getFileName() + ":" + (i + 1) + ": " + line.strip());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "use a pinned, supported macOS runner label such as macos-15 "
                + "(see .claude/rules/ci.md):\n" + String.join("\n", offenders));
    }
}
