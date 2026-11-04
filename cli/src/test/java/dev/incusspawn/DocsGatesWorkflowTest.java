package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code test-integration.yml} skips a PR that changes only Markdown, {@code docs/} or
 * {@code .claude/} (#1960), so the tests guarding exactly those files would never run on one. The
 * {@code docs-gates} workflow runs them on those paths: every test tagged {@value #TAG}. This holds
 * both halves: the workflow triggers on whatever the main one ignores, and every test that reads the
 * repository's docs carries the tag.
 */
class DocsGatesWorkflowTest {

    static final String TAG = "docs";

    private static final Path ROOT = Path.of("..");
    private static final Path MAIN = ROOT.resolve(".github/workflows/test-integration.yml");
    private static final Path GATES = ROOT.resolve(".github/workflows/docs-gates.yml");

    /** A doc of the repository: a root Markdown file, docs/, design/, site/ or .claude/. */
    private static final String REPO_DOC = "(docs|design|site|\\.claude|[A-Z][A-Z_-]*\\.md)";
    /** A test source naming a doc of the repository, the ways the tests reach the repository root. */
    private static final Pattern READS_REPO_DOCS = Pattern.compile(
            "Path\\.of\\(\"\\.\\.\",\\s*\"" + REPO_DOC + "[\"/]"
                    + "|Path\\.of\\(\"\\.\\./" + REPO_DOC
                    + "|Path\\.of\\(\"\\.\\.\"\\)\\.resolve\\(\"" + REPO_DOC
                    + "|ROOT\\.resolve\\(\"" + REPO_DOC);
    private static final Pattern TAGGED = Pattern.compile("@Tag\\((\"" + TAG + "\"|DocsGatesWorkflowTest\\.TAG)\\)");
    private static final Pattern CLASS_DECLARATION = Pattern.compile("(?m)^(public )?(final )?class \\w+");
    /** The Maven goal {@code test} as a word of its own, not {@code test-compile}. */
    private static final Pattern TEST_GOAL = Pattern.compile("(^|\\s)test(\\s|$)");
    /** The modules docs-gates.yml runs, the ones a tagged test must live in. */
    private static final List<String> GATED_MODULES = List.of("common", "cli");

    private static JqValue on(Path workflow) throws IOException {
        var on = JqYaml.parse(Files.readString(workflow)).path("on");
        assertFalse(on.isNull(), workflow + " has no triggers");
        return on;
    }

    private static List<String> strings(JqValue list) {
        return list.asList().stream().map(JqValue::asText).toList();
    }

    @Test
    void theGatesRunOnEveryPathTheMainWorkflowIgnores() throws IOException {
        assertTrue(Files.exists(GATES), "no workflow runs the docs gates on a docs-only change");
        var main = on(MAIN);
        var gates = on(GATES);
        for (var event : List.of("push", "pull_request")) {
            var ignored = strings(main.path(event).path("paths-ignore"));
            assertFalse(ignored.isEmpty(), "test-integration.yml's " + event + " ignores no paths");
            assertEquals(ignored, strings(gates.path(event).path("paths")),
                    "docs-gates.yml's " + event + " paths are test-integration.yml's paths-ignore");
        }
    }

    @Test
    void theGatesRunTheTaggedTestsOfCommonAndCli() throws IOException {
        var commands = new ArrayList<String>();
        for (var job : JqYaml.parse(Files.readString(GATES)).path("jobs").asMap().values()) {
            for (var step : job.path("steps").asList()) commands.add(step.pathText("run", ""));
        }
        assertTrue(commands.stream().anyMatch(run -> run.contains("mvn ")
                        && run.contains("-pl " + String.join(",", GATED_MODULES) + " -am")
                        && TEST_GOAL.matcher(run).find()
                        && run.contains("-Dgroups=" + TAG)),
                "docs-gates.yml runs the tests tagged " + TAG + ": " + commands);
    }

    @Test
    void everyTestReadingTheRepositorysDocsIsTagged() throws IOException {
        var untagged = new ArrayList<String>();
        var ungated = new ArrayList<String>();
        var found = new ArrayList<String>();
        for (var module : List.of("common", "cli", "proxy")) {
            try (Stream<Path> files = Files.walk(ROOT.resolve(module).resolve("src/test/java"))) {
                for (var file : files.filter(f -> f.toString().endsWith("Test.java")).toList()) {
                    var source = Files.readString(file);
                    var reads = READS_REPO_DOCS.matcher(source);
                    if (!reads.find()) continue;
                    var relative = ROOT.relativize(file).toString();
                    found.add(file.getFileName().toString());
                    if (!GATED_MODULES.contains(module)) ungated.add(relative);
                    do {
                        if (!tagged(source, reads.start())) {
                            untagged.add(relative);
                            break;
                        }
                    } while (reads.find());
                }
            }
        }
        assertTrue(found.containsAll(List.of("DocsOneSentencePerLineTest.java", "DocsShapeTest.java",
                "DocsListsTest.java", "DesignCitationsTest.java", "DocsLinksTest.java")),
                "the scan finds the docs gates: " + found);
        assertEquals(List.of(), untagged, "tests reading the repository's docs, not tagged @Tag(\"" + TAG + "\")");
        assertEquals(List.of(), ungated, "tests reading the repository's docs in a module docs-gates.yml does not run");
    }

    /**
     * Whether the doc named at {@code at} is read under the tag: the class carries it, or the test
     * method the name sits in does. A name outside any test method, such as a field, needs the class's.
     */
    private static boolean tagged(String source, int at) {
        var declaration = CLASS_DECLARATION.matcher(source);
        var classStart = declaration.find() ? declaration.start() : source.length();
        if (TAGGED.matcher(source).region(0, Math.min(classStart, source.length())).find()) return true;
        var test = source.lastIndexOf("@Test", at);
        if (test < classStart) return false;
        var memberStart = Math.max(source.lastIndexOf("\n    }", test), classStart);
        return TAGGED.matcher(source).region(memberStart, at).find();
    }
}
