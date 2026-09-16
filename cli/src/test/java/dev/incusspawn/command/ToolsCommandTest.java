package dev.incusspawn.command;

import org.aesh.command.CommandResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class ToolsCommandTest {

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void captureOutput() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void listPrintsSortedToolNames() throws Exception {
        var cmd = new ToolsCommand.ListSub();
        cmd.verbose = false;
        assertEquals(CommandResult.SUCCESS, cmd.doExecute());

        var lines = out.toString().lines().toList();
        assertTrue(lines.size() >= 10, "should list at least 10 built-in tools");
        var sorted = lines.stream().sorted().toList();
        assertEquals(sorted, lines, "tool names must be sorted alphabetically");
    }

    @Test
    void listVerbosePrintsTable() throws Exception {
        var cmd = new ToolsCommand.ListSub();
        cmd.verbose = true;
        assertEquals(CommandResult.SUCCESS, cmd.doExecute());

        var output = out.toString();
        var lines = output.lines().toList();
        assertTrue(lines.getFirst().contains("NAME"), "header must contain NAME");
        assertTrue(lines.getFirst().contains("SOURCE"), "header must contain SOURCE");
        assertTrue(lines.getFirst().contains("DESCRIPTION"), "header must contain DESCRIPTION");
        assertTrue(output.contains("built-in"), "built-in tools must show 'built-in' source");
    }

    @Test
    void showPrintsToolDetails() throws Exception {
        var cmd = new ToolsCommand.Show();
        cmd.name = "claude";
        assertEquals(CommandResult.SUCCESS, cmd.doExecute());

        var output = out.toString();
        assertTrue(output.contains("claude"), "must show tool name");
        assertTrue(output.contains("Description:"), "must show description");
        assertTrue(output.contains("Source:"), "must show source");
    }

    @Test
    void showPrintsYamlToolDownloads() throws Exception {
        var cmd = new ToolsCommand.Show();
        cmd.name = "maven-3";
        assertEquals(CommandResult.SUCCESS, cmd.doExecute());

        var output = out.toString();
        assertTrue(output.contains("Downloads:"), "YAML tools must show downloads");
        assertTrue(output.contains("apache-maven"), "must show Maven download URL");
    }

    @Test
    void showPrintsParameters() throws Exception {
        var cmd = new ToolsCommand.Show();
        cmd.name = "idea-backend";
        assertEquals(CommandResult.SUCCESS, cmd.doExecute());

        var output = out.toString();
        assertTrue(output.contains("Parameters:"), "must show parameters section");
        assertTrue(output.contains("memory"), "must show memory parameter");
        assertTrue(output.contains("Requires:"), "must show dependencies");
        assertTrue(output.contains("sshd"), "idea-backend requires sshd");
    }

    @Test
    void showNotFoundReturnsError() throws Exception {
        var cmd = new ToolsCommand.Show();
        cmd.name = "nonexistent-tool";
        assertEquals(CommandResult.valueOf(1), cmd.doExecute());

        var errOutput = err.toString();
        assertTrue(errOutput.contains("not found"), "must report tool not found");
        assertTrue(errOutput.contains("Available tools:"), "must list available tools");
    }

    @Test
    void bareToolsDelegatesToList() throws Exception {
        var cmd = new ToolsCommand();
        assertEquals(CommandResult.SUCCESS, cmd.doExecute());

        var lines = out.toString().lines().toList();
        assertTrue(lines.size() >= 10, "bare 'tools' must delegate to list");
    }
}
