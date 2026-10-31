package dev.incusspawn.command;

import dev.incusspawn.IncusSpawn;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@code isx --aesh-complete -- <words>} answers, the request every TAB of a dynamic
 * completion script makes (#1744). Each line is a whole word, then a TAB and a description.
 */
@ExtendWith(IsolatedHome.class)
class CompletersTest {

    /** The candidates, without their descriptions, for the words typed after {@code isx}. */
    private static List<String> complete(Class<? extends org.aesh.command.Command> top, String... words) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            Completers.answer(top, Arrays.asList(words), out);
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().map(l -> l.split("\t", 2)[0]).toList();
    }

    private static List<String> linux(String... words) {
        return complete(IncusSpawn.IncusSpawnLinuxCommand.class, words);
    }

    @Test
    void offersTheCommandsOfThisPlatformsTreeOnly() {
        var linux = linux("");
        assertTrue(linux.containsAll(List.of("branch", "shell", "start", "init", "update-all", "mcp")), linux.toString());
        assertFalse(linux.contains("vm"), "vm is macOS only: " + linux);
        assertTrue(complete(IncusSpawn.IncusSpawnCommand.class, "").contains("vm"));
    }

    @Test
    void neverOffersTheHiddenCommandsOrCompletionItself() {
        var offered = linux("");
        for (var name : IncusSpawn.HIDDEN_COMMANDS) assertFalse(offered.contains(name), name);
        assertFalse(offered.contains("completion"), offered.toString());
        assertEquals(List.of(), linux("g"));
        // Still runnable: hidden from completion only.
        assertTrue(linux("completion", "").contains("pwsh"));
    }

    @Test
    void describesCommandsAndOptions() {
        var bytes = new ByteArrayOutputStream();
        Completers.answer(IncusSpawn.IncusSpawnLinuxCommand.class, List.of("sh"),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertEquals("shell\tOpen a shell in an existing clone", bytes.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void completesTemplateDefinitionsForBuild() {
        assertEquals(List.of("tpl-minimal"), linux("build", "tpl-mini"));
        assertTrue(linux("templates", "edit", "").contains("tpl-minimal"));
    }

    @Test
    void completesFixedValues() {
        assertEquals(List.of("json", "plain", "table"), linux("list", "--format", ""));
        assertEquals(List.of("--format=json"), linux("list", "--format=j"), "the whole word, as fish, zsh and pwsh replace it");
        assertEquals(List.of("running", "stopped"), linux("list", "--status", ""));
        assertEquals(List.of("container", "kvm", "vm"), linux("build", "--type", ""));
        assertEquals(List.of("bash", "fish", "pwsh", "zsh"), linux("completion", ""));
    }

    @Test
    void completesCredentialsByNamespaceAndTool() {
        var offered = linux("account", "add", "g");
        assertTrue(offered.containsAll(List.of("gh", "github")), offered.toString());
    }

    @Test
    void completesAccountSelectionsAfterTheInstance() throws Exception {
        IsolatedHome.seed("""
                github:
                  accounts:
                    personal:
                      token: "xxxxxxxxxxxx"
                    acme:
                      token: "yyyyyyyyyyyy"
                """);
        assertEquals(List.of("github=", "github=acme", "github=personal"), linux("account", "set", "box", "gi"));
        assertEquals(List.of("github=acme"), linux("branch", "x", "--account", "github=a"));
        // A list option keeps the selections typed before the comma.
        assertEquals(List.of("github=acme,github=personal"), linux("branch", "x", "--account", "github=acme,github=p"));
        assertTrue(linux("account", "unset", "box", "").contains("github"));
    }

    /** A completer's own description reaches the answer: PowerShell showed no default marker (#1842). */
    @Test
    void describesTheDefaultAccountAndOffersNoBareNamespaceOnceOneIsTyped() throws Exception {
        IsolatedHome.seed("""
                github:
                  default: personal
                  accounts:
                    personal:
                      token: "xxxxxxxxxxxx"
                    acme:
                      token: "yyyyyyyyyyyy"
                """);
        var bytes = new ByteArrayOutputStream();
        Completers.answer(IncusSpawn.IncusSpawnLinuxCommand.class, List.of("branch", "x", "--account", "github="),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertEquals(List.of("github=acme", "github=personal\tdefault"), bytes.toString(StandardCharsets.UTF_8).lines().toList());
        assertEquals(List.of("github=", "github=acme", "github=personal"), linux("account", "set", "box", "github"));
    }

    @Test
    void completesPathsWithoutAShellFallbackMarker() {
        // aesh's own handler threw here (no working directory), and printed __aesh_file__ after commands.
        assertFalse(linux("branch", "x", "--inbox", "").isEmpty());
        for (var words : List.of(List.of(""), List.of("clean", ""), List.of("shell", ""), List.of("branch", "x", "--inbox", ""))) {
            var offered = linux(words.toArray(String[]::new));
            assertFalse(offered.stream().anyMatch(c -> c.startsWith("__aesh")), words + ": " + offered);
        }
    }

    @Test
    void instanceNamesAreThoseListQuietPrintsAndSourcesAddTemplates() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-java", Map.of(Metadata.TYPE, Metadata.TYPE_BASE))
                .container("app", Map.of(Metadata.TYPE, Metadata.TYPE_PROJECT))
                .container("dev-1", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-java"))
                .container("not-ours", Map.of());
        var quiet = new ListCommand();
        quiet.quiet = true;
        var listed = new ByteArrayOutputStream();
        quiet.printListing(daemon.client(), new PrintStream(listed, true, StandardCharsets.UTF_8), java.time.ZoneOffset.UTC);
        assertEquals(listed.toString(StandardCharsets.UTF_8).lines().sorted().toList(),
                sorted(Completers.instanceNames(daemon.client(), Completers.NOT_A_TEMPLATE)));
        assertEquals(List.of("app", "dev-1", "tpl-java"), sorted(Completers.instanceNames(daemon.client(), t -> true)));
        assertEquals(List.of("app"), Completers.instanceNames(daemon.client(), Metadata.TYPE_PROJECT::equals));
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted().toList();
    }

    @Test
    void keepsWhatTheWordHoldsBeforeAeshsOffset(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.createDirectory(dir.resolve("inbox-a"));
        assertEquals(List.of(dir + "/inbox-a/"), linux("branch", "x", "--inbox", dir + "/inbox"));
        assertEquals(List.of("--inbox=" + dir + "/inbox-a/"), linux("branch", "x", "--inbox=" + dir + "/inbox"));
    }

    @Test
    void pathsAreWholePlainWordsWhateverTheyHold(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("my dir"));
        java.nio.file.Files.createFile(dir.resolve("my dir").resolve("inner"));
        java.nio.file.Files.createDirectory(dir.resolve("it's \"q\""));
        java.nio.file.Files.createFile(dir.resolve("tab\tname"));
        // A lone candidate: aesh's own file completer answered "my\ dir/", which no shell but aesh's reads back.
        assertEquals(List.of(dir + "/my dir/"), linux("branch", "x", "--inbox", dir + "/my"));
        assertEquals(List.of("--inbox=" + dir + "/my dir/"), linux("branch", "x", "--inbox=" + dir + "/my"));
        assertEquals(List.of(dir + "/my dir/inner"), linux("branch", "x", "--inbox", dir + "/my dir/in"));
        // Several, and quotes: still the plain path. A TAB in a name would split the line, so it is not offered.
        assertEquals(List.of(dir + "/it's \"q\"/", dir + "/my dir/"), linux("branch", "x", "--inbox", dir + "/"));
        assertEquals(List.of(dir + "/my dir/"), linux("project", "update", "--config", dir + "/m"));
    }

    @Test
    void powerShellScriptAsksIsxOnEveryTab() {
        var script = CompletionCommand.script(CompletionCommand.Shell.pwsh);
        assertTrue(script.contains("Register-ArgumentCompleter -Native -CommandName 'isx'"), script);
        assertTrue(script.contains("--aesh-complete -- @cmdArgs"), script);
        // A word's value goes to isx without its quotes, and a candidate PowerShell would split comes back quoted.
        assertTrue(script.contains("$cmdArgs += $elem.Value"), script);
        assertTrue(script.contains("$insert = \"'\" + ($text -replace"), script);
    }
}
