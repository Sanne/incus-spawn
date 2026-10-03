package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an agent can never do through {@code isx mcp}, pinned at the source level: nothing in the
 * package may write {@code config.yaml} or template definitions, build templates, change
 * credential accounts, or run anything on the host. A change that needs one of these is a change
 * to the trust model, and should have to delete a line here to happen.
 */
class McpNoWritePathTest {

    private static final Path PACKAGE = Path.of("src/main/java/dev/incusspawn/mcp");

    /** Forbidden text, and the one file (if any) allowed to contain it. */
    private static final Map<String, Set<String>> FORBIDDEN = Map.ofEntries(
            Map.entry(".save(", Set.of()),
            Map.entry("setConfigByPath", Set.of()),
            Map.entry("removeConfigPath", Set.of()),
            Map.entry("NamespaceAccounts", Set.of()),
            Map.entry("AccountSelection.stamp", Set.of()),
            Map.entry("BuildCommand", Set.of()),
            Map.entry("TemplatesCommand", Set.of()),
            Map.entry("setTemplates(", Set.of()),
            Map.entry("setMaxInstances(", Set.of()),
            Map.entry("setMcp(", Set.of()),
            Map.entry("ProcessBuilder", Set.of()),
            Map.entry("Runtime.getRuntime().exec", Set.of()),
            Map.entry("filePush", Set.of()),
            Map.entry("Files.write", Set.of("McpAuditLog.java")),
            Map.entry("Files.newOutputStream", Set.of()),
            Map.entry("Files.copy", Set.of()),
            Map.entry("Files.delete", Set.of()));

    @Test
    void theMcpPackageHasNoWritePathToConfigTemplatesOrTheHost() throws IOException {
        var violations = new ArrayList<String>();
        List<Path> sources;
        try (var files = Files.list(PACKAGE)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (var source : sources) {
            var text = Files.readString(source);
            var file = source.getFileName().toString();
            FORBIDDEN.forEach((needle, allowedIn) -> {
                if (text.contains(needle) && !allowedIn.contains(file)) {
                    violations.add(file + " contains " + needle);
                }
            });
        }
        assertEquals(List.of(), violations);
    }
}
