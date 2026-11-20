package dev.incusspawn.command;

import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.SpawnConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Doctor names a step a person has not run as a note, never as a problem (#2407). */
@ExtendWith(IsolatedHome.class)
class DoctorNotSetUpTest {

    @Test
    void eachStepNotSetUpIsANote() {
        var findings = DoctorCommand.notSetUp(new SpawnConfig());
        assertEquals(List.of("Template search paths", "Code directories", "Agent access over MCP"),
                findings.stream().map(DoctorCommand.Finding::label).toList());
        assertTrue(findings.stream().noneMatch(f -> f.status().isProblem()), findings.toString());
    }

    @Test
    void aStepSetUpIsNotNamed() throws Exception {
        var config = new SpawnConfig();
        config.setSearchPaths(List.of("/srv/templates"));
        config.setHostPaths(List.of("/srv/code"));
        Files.createDirectories(McpConfig.file().getParent());
        Files.writeString(McpConfig.file(), "templates: [tpl-agent]\n");
        assertEquals(List.of(), DoctorCommand.notSetUp(config));
    }
}
