package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code mcp.yaml}: what {@code isx mcp} lets an agent do, read but never written by it. */
class McpConfigTest {

    @TempDir
    Path dir;

    private static McpConfig parse(String yaml) {
        return McpConfig.parse(yaml, "mcp.yaml");
    }

    private static String invalid(String yaml) {
        return assertThrows(McpConfig.Invalid.class, () -> parse(yaml)).getMessage();
    }

    @Test
    void noFileApprovesNothing() {
        var mcp = McpConfig.load(dir.resolve("mcp.yaml"));
        assertEquals(List.of(), mcp.templates());
        assertEquals(McpConfig.DEFAULT_MAX_INSTANCES, mcp.maxInstances());
        assertEquals(McpConfig.DEFAULT_MAX_CONCURRENT_TASKS, mcp.maxConcurrentTasks());
        assertNull(mcp.delegateMaxTurns());
    }

    @Test
    void anEmptyDocumentOrListApprovesNothing() {
        for (var yaml : List.of("", "\n", "# nothing yet\n", "templates: []\n", "templates:\n", "max-instances: 2\n")) {
            assertEquals(List.of(), parse(yaml).templates(), yaml);
        }
    }

    @Test
    void theSettingsLoadFromTheTopLevelAndSurviveASave() {
        var yaml = """
                templates: [tpl-java, tpl-dev]
                max-instances: 5
                max-concurrent-tasks: 1
                delegate-max-turns: 40
                """;
        var mcp = parse(yaml);
        assertEquals(List.of("tpl-java", "tpl-dev"), mcp.templates());
        assertEquals(5, mcp.maxInstances());
        assertEquals(1, mcp.maxConcurrentTasks());
        assertEquals(40, mcp.delegateMaxTurns());

        var again = parse(Jjq.toYaml(mcp));
        assertEquals(List.of("tpl-java", "tpl-dev"), again.templates());
        assertEquals(5, again.maxInstances());
        assertEquals(40, again.delegateMaxTurns());
    }

    @Test
    void onlyTheLimitsSetAreWrittenBack() {
        var out = Jjq.toYaml(parse("templates: [tpl-dev]\n"));
        assertTrue(out.contains("tpl-dev"));
        assertFalse(out.contains("max-instances"), "an unset limit must stay unset:\n" + out);
        assertFalse(out.contains("mcp"), "no enclosing key:\n" + out);
    }

    @Test
    void aNegativeLimitIsZeroNotUnlimited() {
        assertEquals(0, parse("max-instances: -1\n").maxInstances());
    }

    /** The section people will paste across: refused, so its limits are not dropped in silence. */
    @Test
    void theOldEnclosingKeyIsRefusedNotIgnored() {
        var message = invalid("mcp:\n  templates: [tpl-java]\n  max-instances: 2\n");
        assertTrue(message.startsWith("mcp.yaml: the settings go at the top level"), message);
        assertTrue(message.contains("refuses everything that depends on it"), message);
    }

    @Test
    void anEmptyTemplateEntryIsRefusedNotAnErrorOnUse() {
        assertTrue(invalid("templates:\n  - tpl-dev\n  -\n").contains("templates has an empty entry"));
    }

    @Test
    void anUnknownKeyIsRefusedSoAMistypedLimitIsNeverDropped() {
        assertTrue(invalid("templates: [t]\nmax-instance: 2\n").contains("unknown key max-instance"));
        assertTrue(invalid("codex:\n  sandbox-mod: read-only\n").contains("unknown key codex.sandbox-mod"));
    }

    @Test
    void aMalformedFileNamesItselfAndTheLine() {
        var message = invalid("templates: [tpl-java\n");
        assertTrue(message.startsWith("mcp.yaml"), message);
        var typed = invalid("templates: [t]\norphan-grace-hours: abc\n");
        assertTrue(typed.startsWith("mcp.yaml:2: 'orphan-grace-hours' expects a whole number"), typed);
    }

    @Test
    void anUnreadableFileIsRefusedNotTreatedAsMissing() throws Exception {
        var file = dir.resolve("mcp.yaml");
        Files.createDirectory(file);
        var message = assertThrows(McpConfig.Invalid.class, () -> McpConfig.load(file)).getMessage();
        assertTrue(message.startsWith(file + ": cannot be read"), message);
    }

    @Test
    void theCoordinatorSettingsHaveDefaultsAndLoad() {
        var defaults = parse("templates: [tpl-java]\n");
        assertEquals("bypassPermissions", defaults.delegatePermissionMode("tpl-java"));
        assertEquals(24, defaults.orphanGraceHours());
        assertEquals(24, defaults.dormantAfterHours());
        assertEquals(168, defaults.dormantGraceHours());
        assertEquals("haiku", defaults.summaryModel());

        var mcp = parse("""
                delegate-permission-mode: acceptEdits
                delegate-permission-modes:
                  tpl-review: plan
                orphan-grace-hours: 72
                dormant-after-hours: 6
                dormant-grace-hours: 48
                summary-model: claude-haiku-4-5
                """);
        assertEquals("acceptEdits", mcp.delegatePermissionMode("tpl-java"));
        assertEquals("plan", mcp.delegatePermissionMode("tpl-review"));
        assertEquals(72, mcp.orphanGraceHours());
        assertEquals(6, mcp.dormantAfterHours());
        assertEquals(48, mcp.dormantGraceHours());
        assertEquals("claude-haiku-4-5", mcp.summaryModel());
        var out = Jjq.toYaml(defaults);
        assertFalse(out.contains("grace") || out.contains("dormant") || out.contains("permission") || out.contains("summary"),
                "unset settings are not written back:\n" + out);
    }

    /** Checked when the file is read, so a bad value refuses every call, not only the one using it. */
    @Test
    void aModeOrModelThatIsNotAPlainNameIsRefused() {
        assertTrue(invalid("delegate-permission-mode: \"plan --dangerously\"\n").contains("is not a permission mode"));
        assertTrue(invalid("delegate-permission-modes:\n  t: \"plan --x\"\n").contains("delegate-permission-modes.t 'plan --x' is not"));
        assertTrue(invalid("summary-model: \"haiku; rm\"\n").contains("is not a model name"));
        var direct = new McpConfig();
        direct.setDelegatePermissionMode("plan --dangerously");
        assertThrows(IllegalArgumentException.class, () -> direct.delegatePermissionMode("t"));
    }

    @Test
    void aDelegatesOwnBudgetStaysWithinTheCeiling() {
        var unset = parse("templates: [t]\n");
        assertNull(unset.delegateMaxTurns(null));
        assertEquals(500, unset.delegateMaxTurns(500), "no ceiling: the task's own");
        var capped = parse("delegate-max-turns: 50\n");
        assertEquals(50, capped.delegateMaxTurns(null));
        assertEquals(7, capped.delegateMaxTurns(7));
        assertEquals(50, capped.delegateMaxTurns(80), "never above the user's");
    }

    @Test
    void aModelNameIsCheckedTheSameWayWhereverItComesFrom() {
        assertTrue(McpConfig.isModelName("claude-haiku-4-5"));
        assertTrue(McpConfig.isModelName("opus[1m]"));
        assertTrue(McpConfig.isModelName("claude-opus-4-6@20250101"));
        assertFalse(McpConfig.isModelName("haiku; rm -rf ~"));
        assertFalse(McpConfig.isModelName("haiku --dangerously-skip-permissions"));
        assertFalse(McpConfig.isModelName("--dangerously-skip-permissions"), "never read as an option");
        assertFalse(McpConfig.isModelName(""));
        assertFalse(McpConfig.isModelName(null));
    }

    @Test
    void piRunsWithEveryToolOnlyWhereClaudeWouldToo() {
        assertEquals(McpConfig.PI_PERMISSION_MODE, parse("templates: [tpl-pi]\n").piPermissionMode("tpl-pi"));
        assertEquals(McpConfig.PI_PERMISSION_MODE, parse("delegate-permission-mode: bypassPermissions\n").piPermissionMode("tpl-pi"));
        // Pi has no sandbox: any restriction, file-wide or the template's own, refuses it.
        assertThrows(IllegalArgumentException.class, () -> parse("delegate-permission-mode: plan\n").piPermissionMode("tpl-pi"));
        var own = parse("delegate-permission-modes:\n  tpl-review: plan\n");
        assertThrows(IllegalArgumentException.class, () -> own.piPermissionMode("tpl-review"));
        assertEquals(McpConfig.PI_PERMISSION_MODE, own.piPermissionMode("tpl-pi"));
    }

    @Test
    void codexPoliciesRoundTripAndDoNotSilentlyWidenClaudeRestrictions() {
        var config = parse("""
                delegate-permission-mode: plan
                codex:
                  sandbox-mode: workspace-write
                  sandbox-modes:
                    tpl-review: read-only
                """);
        var again = parse(Jjq.toYaml(config));
        assertEquals("read-only", again.codexSandboxMode("tpl-review"));
        assertEquals("workspace-write", again.codexSandboxMode("tpl-dev"));
        var restricted = parse("delegate-permission-mode: plan\n");
        assertThrows(IllegalArgumentException.class, () -> restricted.codexSandboxMode("tpl-dev"));
        var ownRestriction = parse("""
                delegate-permission-modes:
                  prod: plan
                codex:
                  sandbox-mode: danger-full-access
                  sandbox-modes:
                    review: read-only
                """);
        assertThrows(IllegalArgumentException.class, () -> ownRestriction.codexSandboxMode("prod"));
        assertEquals("danger-full-access", ownRestriction.codexSandboxMode("tpl-dev"));
        var ownRestrictionCovered = parse("""
                delegate-permission-modes:
                  prod: plan
                codex:
                  sandbox-mode: danger-full-access
                  sandbox-modes:
                    prod: read-only
                """);
        assertEquals("read-only", ownRestrictionCovered.codexSandboxMode("prod"));
        var emptyEntry = parse("""
                delegate-permission-mode: plan
                delegate-permission-modes:
                  prod: ~
                codex:
                  sandbox-mode: read-only
                """);
        assertEquals("read-only", emptyEntry.codexSandboxMode("prod"), "an empty entry is the file's restriction");
        assertTrue(invalid("codex:\n  sandbox-mode: arbitrary\n").contains("codex.sandbox-mode 'arbitrary'"));
        assertTrue(invalid("codex:\n  sandbox-modes:\n    t: arbitrary\n").contains("codex.sandbox-modes.t 'arbitrary'"));
        assertEquals("danger-full-access", new McpConfig().codexSandboxMode("tpl-dev"));
        assertFalse(Jjq.mapper().toJqValue(new McpConfig()).toJsonString().contains("codex"));
    }

    /** The old section is kept like any unknown key, and approves nothing anywhere. */
    @Test
    void aLegacySectionInConfigYamlIsKeptButNeverRead() throws Exception {
        var config = Jjq.spawnConfig("github:\n  token: ghp_x\nmcp:\n  templates: [tpl-java]\n");
        assertTrue(config.hasLegacyMcpSection());
        assertTrue(Jjq.toYaml(config).contains("tpl-java"), "a save does not drop what the user wrote");
        assertFalse(Jjq.spawnConfig("github:\n  token: ghp_x\n").hasLegacyMcpSection());
        assertFalse(Jjq.spawnConfig("mcp:\n").hasLegacyMcpSection(), "an empty section set nothing");
        assertFalse(Jjq.spawnConfig("mcp: {}\n").hasLegacyMcpSection(), "an empty section set nothing");
    }

    @Test
    void savingCreatesTheFileOwnerOnly(@TempDir Path home) throws Exception {
        var previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var mcp = new McpConfig();
            mcp.setTemplates(List.of("tpl-dev"));
            mcp.save();
            var file = home.resolve(".config/incus-spawn/mcp.yaml");
            assertEquals(McpConfig.file(), file);
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
            mcp.save();
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                    "a save leaves it owner-only, whoever loosened it");
            try (var left = Files.list(file.getParent())) {
                assertEquals(List.of(file), left.toList(), "no temporary file is left behind");
            }
            assertEquals(List.of("tpl-dev"), McpConfig.load().templates());
            assertFalse(Files.exists(home.resolve(".config/incus-spawn/config.yaml")), "config.yaml is not touched");
        } finally {
            System.setProperty("user.home", previous);
        }
    }

    // --- host-tools ---

    private static Map<String, McpConfig.HostTool> hostTools(String yaml) {
        var warnings = new ArrayList<String>();
        var tools = parse(yaml).hostTools(warnings::add);
        assertTrue(warnings.isEmpty(), "unexpected warnings: " + warnings);
        return tools;
    }

    private static List<String> hostToolWarnings(String yaml) {
        var warnings = new ArrayList<String>();
        parse(yaml).hostTools(warnings::add);
        return warnings;
    }

    @Test
    void hostToolsLoadFromConfig() {
        var tools = hostTools("""
                host-tools:
                  instance-inventory:
                    command: ~/scripts/inventory.sh
                    description: Read-only inventory
                    timeout: 300
                    min-interval: 5
                """);
        assertEquals(1, tools.size());
        var tool = tools.get("instance-inventory");
        assertEquals("instance-inventory", tool.name());
        assertEquals("instance_inventory", tool.toolName());
        assertEquals("~/scripts/inventory.sh", tool.command());
        assertEquals("Read-only inventory", tool.description());
        assertEquals(300, tool.timeout());
        assertEquals(5, tool.minInterval());
    }

    @Test
    void hostToolsUseDefaults() {
        var tools = hostTools("""
                host-tools:
                  check:
                    command: echo ok
                    description: A check
                """);
        var tool = tools.get("check");
        assertEquals(McpConfig.HostTool.DEFAULT_TIMEOUT, tool.timeout());
        assertEquals(McpConfig.HostTool.DEFAULT_MIN_INTERVAL, tool.minInterval());
    }

    @Test
    void noHostToolsSectionReturnsEmpty() {
        assertEquals(Map.of(), hostTools("templates: [tpl-dev]\n"));
    }

    @Test
    void hostToolMissingCommandIsDropped() {
        var warnings = hostToolWarnings("""
                host-tools:
                  broken:
                    description: No command
                """);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("command:"), warnings.get(0));
    }

    @Test
    void hostToolMissingDescriptionIsDropped() {
        var warnings = hostToolWarnings("""
                host-tools:
                  broken:
                    command: echo ok
                """);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("description:"), warnings.get(0));
    }

    @Test
    void hostToolUnknownKeyDropsTheEntry() {
        var warnings = hostToolWarnings("""
                host-tools:
                  broken:
                    command: echo ok
                    description: A tool
                    runs-as: root
                """);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("unknown key 'runs-as'"), warnings.get(0));
    }

    @Test
    void hostToolBadNameIsDropped() {
        var warnings = hostToolWarnings("""
                host-tools:
                  9-starts-with-digit:
                    command: echo ok
                    description: A tool
                """);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("letter"), warnings.get(0));
    }

    @Test
    void hostToolBadTimeoutIsDropped() {
        var warnings = hostToolWarnings("""
                host-tools:
                  slow:
                    command: sleep 1000
                    description: A slow tool
                    timeout: 9999
                """);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("timeout"), warnings.get(0));
    }

    @Test
    void hostToolsDoNotMakeUnknownKeysRefuseTheFile() {
        var mcp = parse("""
                templates: [tpl-dev]
                host-tools:
                  check:
                    command: echo ok
                    description: A check
                """);
        assertEquals(List.of("tpl-dev"), mcp.templates());
    }

    @Test
    void hostToolsSurviveASave() {
        var yaml = """
                host-tools:
                  check:
                    command: echo ok
                    description: A check
                    timeout: 120
                """;
        var mcp = parse(yaml);
        var out = Jjq.toYaml(mcp);
        assertTrue(out.contains("host-tools"), out);
        assertTrue(out.contains("echo ok"), out);
    }

    @Test
    void savingWritesThroughASymlinkedFile(@TempDir Path home) throws Exception {
        var previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var dotfiles = Files.createDirectories(home.resolve("dotfiles"));
            var target = Files.writeString(dotfiles.resolve("mcp.yaml"), "templates: [tpl-old]\n");
            Files.createDirectories(McpConfig.file().getParent());
            Files.createSymbolicLink(McpConfig.file(), target);
            var mcp = McpConfig.load();
            mcp.setTemplates(List.of("tpl-new"));
            mcp.save();
            assertTrue(Files.isSymbolicLink(McpConfig.file()), "the link stays a link");
            assertTrue(Files.readString(target).contains("tpl-new"), Files.readString(target));
            try (var left = Files.list(dotfiles)) {
                assertEquals(List.of(target), left.toList(), "no temporary file is left behind");
            }
            // Dotfiles not checked out yet: the dangling link is written through, not replaced.
            Files.delete(target);
            mcp.save();
            assertTrue(Files.isSymbolicLink(McpConfig.file()), "a dangling link stays a link");
            assertTrue(Files.readString(target).contains("tpl-new"));
        } finally {
            System.setProperty("user.home", previous);
        }
    }
}
