package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code isx templates new <name> --from <file>} checks a whole definition with the editor's
 * check and stores it with no editor and no prompt, and {@code isx templates validate} is the same
 * check alone (#2076). An agent hands isx a template and learns from the findings, field by field,
 * what to correct; a person reads what it reaches beyond its instance.
 */
@ExtendWith(IsolatedHome.class)
class TemplatesNewFromTest {

    static final String QUICK_START = """
            name: tpl-agent
            description: Claude Code on the base OS
            parent: tpl-minimal
            tools:
              - claude
            default-action: claude
            """;

    @TempDir Path dir;

    private static Path stored(String name) {
        return Environment.configDir().resolve("images").resolve(name + ".yaml");
    }

    /** What a run printed and how it ended. */
    record Run(int exit, RecordingOutput out) {
        JqValue json() {
            try {
                return JqValues.parseStrict(String.join("\n", out.resultLines()));
            } catch (Exception e) {
                throw new AssertionError(out.resultLines().toString(), e);
            }
        }
    }

    private static Run run(BaseCommand cmd) {
        var exit = new int[1];
        var out = RecordingOutput.during(() -> exit[0] = cmd.doExecute().getResultValue());
        return new Run(exit[0], out);
    }

    private Run newFrom(String name, String yaml, String... flags) throws Exception {
        var file = Files.writeString(dir.resolve("in.yaml"), yaml);
        var cmd = new TemplatesCommand.New();
        cmd.name = name;
        cmd.from = file.toString();
        // Reaching the edit loop would wait on an editor: none is set up, so it would fail
        for (var flag : flags) {
            if (flag.equals("--replace")) cmd.replace = true;
            else if (flag.equals("--project")) cmd.project = true;
            else cmd.format = flag.substring("--format=".length());
        }
        return run(cmd);
    }

    private static Run validate(String target, String format) {
        var cmd = new TemplatesCommand.Validate();
        cmd.target = target;
        cmd.format = format;
        return run(cmd);
    }

    @Test
    void theQuickStartTemplateIsStoredAsGivenWithNoEditor() throws Exception {
        var run = newFrom("tpl-agent", QUICK_START);
        assertEquals(0, run.exit(), run.out().lines().toString());
        assertEquals(QUICK_START, Files.readString(stored("tpl-agent")));
        assertTrue(run.out().lines().contains("Created " + stored("tpl-agent")), run.out().lines().toString());
    }

    @Test
    void aDashReadsTheDefinitionFromStdin() {
        var cmd = new TemplatesCommand.New();
        cmd.name = "tpl-agent";
        cmd.from = "-";
        cmd.stdin = () -> new ByteArrayInputStream(QUICK_START.getBytes(StandardCharsets.UTF_8));
        assertEquals(0, run(cmd).exit());
        assertTrue(Files.exists(stored("tpl-agent")));
    }

    @Test
    void anInvalidDefinitionWritesNothingAndItsJsonNamesTheField() throws Exception {
        var run = newFrom("tpl-bad", "name: tpl-bad\nparent: 'not a name!'\nrepo:\n  - url: x\n", "--format=json");
        assertEquals(1, run.exit());
        assertFalse(Files.exists(stored("tpl-bad")));
        var json = run.json();
        assertEquals("tpl-bad", json.pathText("name", null));
        assertFalse(json.path("valid").asBoolean(true));
        assertFalse(json.path("written").asBoolean(false));
        var findings = json.path("findings").asList();
        var byField = findings.stream().collect(java.util.stream.Collectors.toMap(
                f -> f.pathText("field", ""), f -> f.pathText("severity", "")));
        assertEquals("error", byField.get("parent"), findings.toString());
        assertEquals("warning", byField.get("repo"), "an unknown key is named as the field: " + findings);
        // Nothing but the JSON on the results stream, and no human report beside it
        assertTrue(run.out().events(RecordingOutput.Kind.ERROR).isEmpty(), run.out().events().toString());
    }

    @Test
    void aRepoTheLoaderWouldRefuseIsAnErrorAndWritesNothing() throws Exception {
        // The loader skips a definition with this repo, so storing it as valid would lose the template
        var run = newFrom("tpl-bad", "name: tpl-bad\nparent: tpl-minimal\nrepos:\n  - url: https://example.com/a.git\n    depth: 0\n",
                "--format=json");
        assertEquals(1, run.exit());
        assertFalse(Files.exists(stored("tpl-bad")));
        var finding = run.json().path("findings").asList().getFirst();
        assertEquals("repos[0].depth", finding.pathText("field", null));
        assertEquals("error", finding.pathText("severity", null));
    }

    @Test
    void aDefinitionThatIsNotUtf8IsRefusedRatherThanAltered() throws Exception {
        var file = Files.write(dir.resolve("latin1.yaml"),
                "name: tpl-agent\nparent: tpl-minimal\n# caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));
        var cmd = new TemplatesCommand.New();
        cmd.name = "tpl-agent";
        cmd.from = file.toString();
        assertEquals(1, run(cmd).exit());
        assertFalse(Files.exists(stored("tpl-agent")));
    }

    @Test
    void accountReachIsInNamespaceOrder() throws Exception {
        var run = newFrom("tpl-agent", QUICK_START + "accounts:\n  openai: work\n  claude: work\n  github: work\n  gemini: work\n",
                "--format=json");
        var fields = run.json().path("reach").asList().stream().map(r -> r.pathText("field", "")).toList();
        assertEquals(List.of("accounts.claude", "accounts.gemini", "accounts.github", "accounts.openai"), fields);
    }

    @Test
    void replaceNeverRemovesAnotherTemplatesFile() throws Exception {
        Files.createDirectories(stored("tpl-agent").getParent());
        Files.writeString(stored("tpl-agent"), "name: tpl-elsewhere\nparent: tpl-minimal\n");
        var run = newFrom("tpl-agent", QUICK_START, "--replace");
        assertEquals(1, run.exit());
        assertEquals("name: tpl-elsewhere\nparent: tpl-minimal\n", Files.readString(stored("tpl-agent")));
    }

    /**
     * A template whose tools need a credential that is not configured is stored, and the check
     * says so, as {@code isx branch} would later refuse it: the commands an agent runs before the
     * branch name the gap themselves (#2163).
     */
    @Test
    void aMissingCredentialIsAWarningThatNamesItsCommand() throws Exception {
        var run = newFrom("tpl-agent", QUICK_START, "--format=json");
        assertEquals(0, run.exit());
        var json = run.json();
        assertTrue(json.path("written").asBoolean(false));
        assertTrue(json.path("valid").asBoolean(false));
        var finding = json.path("findings").asList().stream()
                .filter(f -> "tools".equals(f.pathText("field", null))).findFirst().orElseThrow(
                        () -> new AssertionError("no credential finding: " + json.path("findings").asList()));
        assertEquals("warning", finding.pathText("severity", null));
        var message = finding.pathText("message", "");
        assertTrue(message.contains("Anthropic API key") && message.contains("'isx account add claude'"), message);

        var validated = validate(stored("tpl-agent").toString(), "json");
        assertEquals(0, validated.exit());
        assertEquals(List.of(finding), validated.json().path("findings").asList());
    }

    @Test
    void aMissingCredentialIsShownToAPersonToo() throws Exception {
        var run = newFrom("tpl-agent", QUICK_START);
        assertEquals(0, run.exit());
        assertTrue(run.out().events(RecordingOutput.Kind.WARN).stream()
                        .anyMatch(w -> w.msg().contains("'isx account add claude'")),
                run.out().events().toString());
    }

    /** An account pin with no such account is said too: the build refuses it, and so would the branch. */
    @Test
    void anUnknownAccountPinIsAWarningOnAccounts() throws Exception {
        IsolatedHome.seed("claude:\n  apiKey: \"sk-ant-api-x\"\n");
        var run = newFrom("tpl-agent", QUICK_START + "accounts:\n  claude: work\n", "--format=json");
        assertEquals(0, run.exit());
        var findings = run.json().path("findings").asList();
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("accounts", findings.getFirst().pathText("field", null));
        assertEquals("warning", findings.getFirst().pathText("severity", null));
        assertTrue(findings.getFirst().pathText("message", "").contains("work"), findings.toString());
    }

    @Test
    void aConfiguredCredentialIsNotReported() throws Exception {
        IsolatedHome.seed("claude:\n  apiKey: \"sk-ant-api-x\"\n");
        var run = newFrom("tpl-agent", QUICK_START, "--format=json");
        assertEquals(0, run.exit());
        assertEquals(List.of(), run.json().path("findings").asList());
    }

    @Test
    void theJsonFieldsAreInTheirContractOrder() throws Exception {
        var json = newFrom("tpl-agent", QUICK_START, "--format=json").json();
        assertEquals(List.of("name", "path", "valid", "written", "findings", "reach"),
                List.copyOf(json.asMap().keySet()));
        assertTrue(json.path("written").asBoolean(false));
        assertEquals(stored("tpl-agent").toString(), json.pathText("path", null));
    }

    @Test
    void aNameThatDisagreesWithTheFilesIsRefused() throws Exception {
        var run = newFrom("tpl-other", QUICK_START, "--format=json");
        assertEquals(1, run.exit());
        assertFalse(Files.exists(stored("tpl-other")));
        // 'name' is the name the definition gives, as validate prints it for the same file
        assertEquals("tpl-agent", run.json().pathText("name", null));
        assertEquals("tpl-agent", validate(dir.resolve("in.yaml").toString(), "json").json().pathText("name", null));
        var finding = run.json().path("findings").asList().getLast();
        assertEquals("name", finding.pathText("field", null));
        assertTrue(finding.pathText("message", "").contains("named 'tpl-agent', not 'tpl-other'"), finding.toString());
    }

    @Test
    void anExistingTemplateIsReplacedOnlyWithReplace() throws Exception {
        assertEquals(0, newFrom("tpl-agent", QUICK_START).exit());
        var changed = QUICK_START.replace("Claude Code on the base OS", "Mine");
        var refused = newFrom("tpl-agent", changed);
        assertEquals(1, refused.exit());
        assertEquals(QUICK_START, Files.readString(stored("tpl-agent")));
        assertTrue(refused.out().events(RecordingOutput.Kind.ERROR).getFirst().msg().contains("already exists"));

        assertEquals(0, newFrom("tpl-agent", changed, "--replace").exit());
        assertEquals(changed, Files.readString(stored("tpl-agent")));
        try (var left = Files.list(stored("tpl-agent").getParent())) {
            assertEquals(List.of(stored("tpl-agent")), left.toList(), "no partial file is left beside it");
        }
    }

    @Test
    void replaceNeverShadowsATemplateDefinedElsewhere() throws Exception {
        var run = newFrom("tpl-minimal", "name: tpl-minimal\nimage: images:fedora/44\n", "--replace");
        assertEquals(1, run.exit());
        assertFalse(Files.exists(Environment.configDir().resolve("images")));
        assertTrue(run.out().events(RecordingOutput.Kind.ERROR).getFirst().msg().contains("built-in"),
                run.out().events().toString());
    }

    @Test
    void whatTheTemplateReachesIsReported() throws Exception {
        var yaml = """
                name: tpl-reach
                parent: tpl-minimal
                gui: true
                host-resources:
                  - source: ~/data
                    path: /data
                repos:
                  - host-source: ~/work/app
                accounts:
                  claude: work
                coordinator:
                  control-over:
                    - tpl-minimal
                """;
        var json = newFrom("tpl-reach", yaml, "--format=json").json();
        var reach = json.path("reach").asList().stream()
                .map(r -> r.pathText("kind", "") + " " + r.pathText("field", "")).toList();
        assertEquals(List.of("host-resource host-resources[0]", "host-source repos[0].host-source", "gui gui",
                "account accounts.claude", "coordinator coordinator.control-over"), reach);

        var table = validate(stored("tpl-reach").toString(), null);
        var warned = table.out().events(RecordingOutput.Kind.WARN).stream()
                .filter(e -> e.msg().startsWith("It reaches beyond its instance")).findFirst().orElseThrow();
        assertTrue(warned.details().contains("host-resources[0]: mounts host ~/data at /data (readonly)"), warned.details().toString());
    }

    @Test
    void validateChecksAFileAStdinOrANameAndWritesNothing() throws Exception {
        // Configured, so the template is valid with nothing to say
        IsolatedHome.seed("claude:\n  apiKey: \"sk-ant-api-x\"\n");
        var file = Files.writeString(dir.resolve("agent.yaml"), QUICK_START);
        var ok = validate(file.toString(), null);
        assertEquals(0, ok.exit());
        assertEquals("Template is valid", ok.out().events(RecordingOutput.Kind.OK).getFirst().msg());
        assertFalse(Files.exists(Environment.configDir().resolve("images")));

        assertEquals(0, validate("tpl-minimal", null).exit(), "a built-in is checked by its name");

        var bad = Files.writeString(dir.resolve("bad.yaml"), "description: no name\n");
        var run = validate(bad.toString(), "json");
        assertEquals(1, run.exit());
        assertEquals("name", run.json().path("findings").asList().getFirst().pathText("field", null));
        assertFalse(run.json().path("written").asBoolean(false));

        var plain = validate(bad.toString(), "plain");
        assertEquals(List.of("error\tname\t'name' field is required and must not be blank"), plain.out().resultLines());

        var cmd = new TemplatesCommand.Validate();
        cmd.target = "-";
        cmd.stdin = () -> new ByteArrayInputStream(QUICK_START.getBytes(StandardCharsets.UTF_8));
        assertEquals(0, run(cmd).exit());

        assertEquals(1, validate("no-such-thing", null).exit());
    }

    @Test
    void aFileThatDoesNotParseIsAFindingWithNoField() throws Exception {
        var json = newFrom("tpl-x", "name: [unclosed\n", "--format=json").json();
        var finding = json.path("findings").asList().getFirst();
        assertEquals("error", finding.pathText("severity", null));
        assertTrue(finding.path("field").isNull(), finding.toString());
        assertEquals(List.of(), json.path("reach").asList());
    }

    @Test
    void replaceAndFormatNeedFrom() {
        var cmd = new TemplatesCommand.New();
        cmd.name = "tpl-x";
        cmd.replace = true;
        assertEquals(1, run(cmd).exit());
        var noName = new TemplatesCommand.New();
        noName.from = "x.yaml";
        assertEquals(1, run(noName).exit());
    }

    @Test
    void anOversizedDefinitionIsRefusedUnread() {
        var cmd = new TemplatesCommand.New();
        cmd.name = "tpl-x";
        cmd.from = "-";
        var big = new byte[TemplatesCommand.MAX_DEFINITION_BYTES + 1];
        java.util.Arrays.fill(big, (byte) '#');
        cmd.stdin = () -> new ByteArrayInputStream(big);
        var run = run(cmd);
        assertEquals(1, run.exit());
        assertTrue(run.out().events(RecordingOutput.Kind.ERROR).getFirst().msg().contains("KiB"));
    }

    @Test
    void theStoredQuickStartLoadsAsATemplate() throws Exception {
        assertEquals(0, newFrom("tpl-agent", QUICK_START).exit());
        var def = ImageDef.loadAll().get("tpl-agent");
        assertEquals("claude", def.getDefaultAction());
        assertEquals(Map.of(), def.getAccounts());
    }

    @Test
    void aFileBaseImageIsAReach() throws Exception {
        var file = Files.writeString(dir.resolve("root.yaml"), """
                name: tpl-root
                image: local/root
                image_url: file:///srv/images/root.tar.xz
                """);
        var reach = validate(file.toString(), "json").json().path("reach").asList();
        assertEquals("host-image image_url imports host file file:///srv/images/root.tar.xz as the base image",
                reach.getFirst().pathText("kind", "") + " " + reach.getFirst().pathText("field", "")
                        + " " + reach.getFirst().pathText("detail", ""));
    }

    @Test
    void aRefusedHostSourceRepoNamesTheKeyAtFault() throws Exception {
        var fields = new ArrayList<String>();
        for (var repo : List.of("host-source: /tmp/r\n    depth: 1", "host-source: /tmp/r\n    url: https://example.com/r.git",
                "host-source: ''", "url: https://example.com/r.git\n    depth: 0")) {
            var file = Files.writeString(dir.resolve("r.yaml"), "name: tpl-r\nparent: tpl-minimal\nrepos:\n  - " + repo + "\n");
            var errors = validate(file.toString(), "json").json().path("findings").asList().stream()
                    .filter(f -> "error".equals(f.pathText("severity", ""))).toList();
            assertEquals(1, errors.size(), repo + ": " + errors);
            fields.add(errors.getFirst().pathText("field", null));
        }
        assertEquals(List.of("repos[0].depth", "repos[0].url", "repos[0].host-source", "repos[0].depth"), fields);
    }
}
