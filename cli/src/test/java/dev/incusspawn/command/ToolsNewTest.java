package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.SchemaModeline;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDefLoader;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code isx tools new} writes a tool where isx finds the user's tools and checks it as
 * {@code isx tools validate} does (#2764): in the editor from a short skeleton, or whole from
 * {@code --from}, stored only when it has no error. A guide hands the reader a tool this way
 * rather than a path to save it at.
 */
@ExtendWith(IsolatedHome.class)
class ToolsNewTest {

    static final String OLLAMA = """
            name: ollama-models
            description: Ollama on the host, as a provider in Pi's models.json
            files:
              - path: /home/agentuser/.pi/agent/models.json
                merge: json
                owner: agentuser:agentuser
                content: |
                  {"providers": {}}
            """;

    @TempDir Path dir;

    record Run(int exit, RecordingOutput out) {
        JqValue json() throws Exception {
            return JqValues.parseStrict(String.join("\n", out.resultLines()));
        }
    }

    private static Run run(ToolsCommand.New cmd) {
        var exit = new int[1];
        var out = RecordingOutput.during(() -> exit[0] = cmd.doExecute().getResultValue());
        return new Run(exit[0], out);
    }

    private static Path stored(String name) {
        return Environment.configDir().resolve("tools").resolve(name + ".yaml");
    }

    private ToolsCommand.New from(String name, String yaml) throws Exception {
        var cmd = new ToolsCommand.New();
        cmd.name = name;
        cmd.from = Files.writeString(dir.resolve("in.yaml"), yaml).toString();
        cmd.editor = file -> { throw new AssertionError("--from opened an editor"); };
        return cmd;
    }

    private static ToolsCommand.New edited(String name, TemplatesCommand.Editor editor) {
        var cmd = new ToolsCommand.New();
        cmd.name = name;
        cmd.editor = editor;
        cmd.reEdit = ScriptedPrompts::new; // nothing scripted: the first read is the end of input
        return cmd;
    }

    @Test
    void fromStoresTheToolWhereIsxFindsItUnderTheSchemaLine() throws Exception {
        var run = run(from("ollama-models", OLLAMA));
        assertEquals(0, run.exit(), run.out().events().toString());
        assertEquals(SchemaModeline.TOOL.line() + "\n" + OLLAMA, Files.readString(stored("ollama-models")));
        assertTrue(run.out().lines().contains("Created " + stored("ollama-models")), run.out().lines().toString());
        var loader = new ToolDefLoader(SpawnConfig.load().getSearchPaths());
        assertTrue(loader.allToolSetups().containsKey("ollama-models"), "a build finds the tool it wrote");
    }

    @Test
    void aDashReadsTheDefinitionFromStdin() {
        var cmd = new ToolsCommand.New();
        cmd.name = "ollama-models";
        cmd.from = "-";
        cmd.stdin = () -> new ByteArrayInputStream(OLLAMA.getBytes(StandardCharsets.UTF_8));
        assertEquals(0, run(cmd).exit());
        assertTrue(Files.exists(stored("ollama-models")));
    }

    @Test
    void anInvalidDefinitionWritesNothingAndItsJsonSaysWhy() throws Exception {
        var cmd = from("mine", "name: mine\ndownloads:\n  - extract: /opt\n");
        cmd.format = "json";
        var run = run(cmd);
        assertEquals(1, run.exit());
        assertFalse(Files.exists(stored("mine")));
        var json = run.json();
        assertFalse(json.path("valid").asBoolean(true));
        assertFalse(json.path("written").asBoolean(true));
        assertEquals("download entry in 'mine' is missing a 'url'",
                json.path("findings").asList().getFirst().pathText("message", ""));
    }

    @Test
    void theDefinitionMustNameTheToolGiven() throws Exception {
        var run = run(from("other", OLLAMA));
        assertEquals(1, run.exit());
        assertFalse(Files.exists(stored("other")));
        assertFalse(Files.exists(stored("ollama-models")));
    }

    @Test
    void aBuiltInToolIsNeverShadowed() throws Exception {
        var cmd = from("podman", "name: podman\n");
        cmd.replace = true;
        var run = run(cmd);
        assertEquals(1, run.exit());
        assertFalse(Files.exists(stored("podman")));
        assertTrue(run.out().events(RecordingOutput.Kind.ERROR).getFirst().msg().startsWith("Tool 'podman' already exists"),
                run.out().events().toString());

        // Nor in the editor, which never opens for it
        assertEquals(1, run(edited("podman", file -> { throw new AssertionError("editor opened"); })).exit());
    }

    @Test
    void itsOwnFileIsReplacedOnlyWithReplace() throws Exception {
        assertEquals(0, run(from("ollama-models", OLLAMA)).exit());
        var changed = OLLAMA.replace("as a provider", "a provider");
        assertEquals(1, run(from("ollama-models", changed)).exit());
        var cmd = from("ollama-models", changed);
        cmd.replace = true;
        assertEquals(0, run(cmd).exit());
        assertEquals(SchemaModeline.TOOL.line() + "\n" + changed, Files.readString(stored("ollama-models")));
    }

    @Test
    void anInvalidNameIsRefusedBeforeAnythingIsWritten() {
        var run = run(edited("../escape", file -> { throw new AssertionError("editor opened"); }));
        assertEquals(1, run.exit());
        assertFalse(Files.exists(Environment.configDir().resolve("escape.yaml")));
        assertFalse(Files.isDirectory(Environment.configDir().resolve("tools")));
    }

    @Test
    void theSkeletonIsAValidToolOfTheGivenName() throws Exception {
        var seen = new String[1];
        var run = run(edited("mine", file -> {
            seen[0] = Files.readString(file);
            return 0;
        }));
        assertEquals(0, run.exit(), run.out().events().toString());
        assertTrue(seen[0].startsWith(SchemaModeline.TOOL.line() + "\n"), seen[0]);
        assertTrue(seen[0].contains("\nname: mine\n"), seen[0]);
        assertEquals("Tool is valid", run.out().events(RecordingOutput.Kind.OK).getFirst().msg());
    }

    @Test
    void anEditLeftInvalidStopsAtTheEndOfInputAndKeepsTheFile() throws Exception {
        var passes = new int[1];
        var run = run(edited("mine", file -> {
            if (++passes[0] > 3) throw new AssertionError("the editor reopened " + passes[0] + " times");
            Files.writeString(file, "name: mine\ndownloads:\n  - extract: /opt\n");
            return 0;
        }));
        assertEquals(1, run.exit());
        assertEquals(1, passes[0], "one pass, then the end of input stops it");
        assertTrue(Files.exists(stored("mine")), "what was typed is kept for the person to fix");
        assertEquals(List.of("download entry in 'mine' is missing a 'url'"),
                run.out().events(RecordingOutput.Kind.ERROR).stream().map(e -> e.msg()).toList());
    }

    @Test
    void anEditThatRenamesTheToolIsNotValid() throws Exception {
        var run = run(edited("mine", file -> {
            Files.writeString(file, "name: podman\n");
            return 0;
        }));
        assertEquals(1, run.exit());
        assertEquals(List.of("The definition is named 'podman', not 'mine': the two must agree"),
                run.out().events(RecordingOutput.Kind.ERROR).stream().map(e -> e.msg()).toList());
    }
}
