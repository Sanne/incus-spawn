package dev.incusspawn.tui;

import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Which tools' actions the TUI offers on F9, and picks Enter's default action from: through
 * {@code ActionResolver}, so the same as {@code isx run} (#1158). A template uses its YAML, unless
 * that is gone, when the tools it was built with stand in (#868).
 */
@ExtendWith(IsolatedHome.class)
class InstanceActionsInstalledToolsTest {

    private static final String TEMPLATE = "tpl-agent";

    /** A tool with one shell action. */
    private record Tool(String name) implements ToolSetup {
        @Override
        public List<ToolDef.ActionEntry> actions() {
            var entry = new ToolDef.ActionEntry();
            entry.setType("shell");
            entry.setCommand("run-" + name);
            return List.of(entry);
        }

        @Override
        public void install(Container container, Map<String, String> resolvedParams) {}
    }

    private static ImageDef template(String tool) {
        var def = new ImageDef();
        def.setName(TEMPLATE);
        def.setTools(List.of(new ToolDef.ToolRef(tool)));
        return def;
    }

    /** The tool names whose actions the TUI offers for a built template that recorded {@code built}. */
    private static List<String> offeredTools(Map<String, ImageDef> defs, String built) {
        var daemon = new FakeIncusDaemon().container(TEMPLATE, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.PROFILE, TEMPLATE,
                Metadata.BUILD_SOURCE, new BuildSource(Map.of(TEMPLATE, template(built)),
                        Map.of(), Map.of(), Map.of()).toJson()));
        var instance = InstanceListing.collectEntries(daemon.client().listJson()).getFirst();

        var loader = new ToolDefLoader(List.of());
        List<ToolSetup> cdiTools = List.of(new Tool("test-agent-868"), new Tool("test-other-868"));
        var actions = new InstanceActions(() -> defs, () -> loader, () -> cdiTools);
        return actions.resolveActionsForInstance(instance).stream().map(ToolAction::toolName).toList();
    }

    @Test
    void aTemplateWhoseYamlWasDeletedOffersTheToolsItWasBuiltWith() {
        assertEquals(List.of("test-agent-868"), offeredTools(Map.of(), "test-agent-868"));
    }

    @Test
    void aTemplateOnDiskOffersTheToolsItsDefinitionNames() {
        assertEquals(List.of("test-other-868"),
                offeredTools(Map.of(TEMPLATE, template("test-other-868")), "test-agent-868"));
    }

    @ParameterizedTest
    @CsvSource({
            "vscode-remote, VS Code, vscode://vscode-remote/ssh-remote+tpl-agent/home/agentuser/local",
            "idea-backend, Gateway, jetbrains-gateway://connect#idePath=%2Fopt%2Fidea&host=tpl-agent&port=22&user=agentuser&type=ssh&deploy=false&projectPath=/home/agentuser/local"
    })
    void hostSourcesOfferWorkingEditorActions(String tool, String editor, String expectedUrl) {
        var def = template(tool);
        var repo = new ImageDef.RepoEntry();
        repo.setHostSource("/host/local");
        repo.setPath("~/local");
        def.setRepos(List.of(repo));
        var daemon = new FakeIncusDaemon().container(TEMPLATE, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, TEMPLATE));
        var instance = InstanceListing.collectEntries(daemon.client().listJson()).getFirst();
        var loader = new ToolDefLoader(List.of());
        var actions = new InstanceActions(() -> Map.of(TEMPLATE, def), () -> loader, List::of);
        var context = actions.buildActionContext(instance);
        assertNull(context.repos().getFirst().url());
        var offered = actions.resolveActionsForInstance(instance).stream()
                .filter(action -> tool.equals(action.toolName())).toList();

        assertEquals(1, offered.size());
        var action = assertInstanceOf(YamlToolAction.class, offered.getFirst());
        assertEquals("Open repo 'local' in " + editor, action.label());
        assertEquals(expectedUrl, action.resolveUrl(context));
    }

    /** A YAML tool with one shell action, requiring {@code requires}. */
    private static ToolDef yamlTool(String name, String... requires) {
        var def = new ToolDef();
        def.setName(name);
        def.setRequires(java.util.Arrays.stream(requires).map(ToolDef.ToolRef::new).toList());
        var entry = new ToolDef.ActionEntry();
        entry.setType("shell");
        entry.setCommand("run-" + name);
        def.setActions(List.of(entry));
        return def;
    }

    /** The tool names whose actions F9 offers for an unbuilt clone of {@code template}. */
    private static List<String> offeredForClone(Map<String, ImageDef> defs, String template, ToolDef... tools) {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(
                Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PROFILE, template));
        var instance = InstanceListing.collectEntries(daemon.client().listJson()).getFirst();
        var loader = new ToolDefLoader(List.of());
        var fallbacks = new java.util.LinkedHashMap<String, ToolDef>();
        for (var tool : tools) fallbacks.put(tool.getName(), tool);
        loader.addFallbacks(fallbacks);
        var actions = new InstanceActions(() -> defs, () -> loader, List::of);
        return actions.resolveActionsForInstance(instance).stream().map(ToolAction::toolName).toList();
    }

    @Test
    void transitiveDependenciesAreOfferedInTheOrderIsxRunListsThem() {
        // pa -> pb -> pc: isx run and the shell's F12 menu list them depth first from the top.
        assertEquals(List.of("pa", "pb", "pc"), offeredForClone(Map.of(TEMPLATE, template("pa")), TEMPLATE,
                yamlTool("pa", "pb"), yamlTool("pb", "pc"), yamlTool("pc")));
    }

    @Test
    void aParentCycleStillResolves() {
        // A template edited into a cycle after it was built still loads; the refresh must not spin.
        var cx = template("pa");
        cx.setName("tpl-cx");
        cx.setParent("tpl-cy");
        var cy = new ImageDef();
        cy.setName("tpl-cy");
        cy.setParent("tpl-cx");
        cy.setTools(List.of(new ToolDef.ToolRef("pb")));
        var offered = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> offeredForClone(
                Map.of("tpl-cx", cx, "tpl-cy", cy), "tpl-cx", yamlTool("pa"), yamlTool("pb")));
        assertEquals(List.of("pb", "pa"), offered);
    }

    @Test
    void aCloneOffersRepoActionsOnlyForTheReposItWasBuiltWith() {
        var built = template("vscode-remote");
        built.setRepos(List.of(repo("~/a")));
        var onDisk = template("vscode-remote");
        onDisk.setRepos(List.of(repo("~/a"), repo("~/b")));
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(
                Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.PROFILE, TEMPLATE,
                Metadata.BUILD_SOURCE, new BuildSource(Map.of(TEMPLATE, built),
                        Map.of(), Map.of(), Map.of()).toJson()));
        var instance = InstanceListing.collectEntries(daemon.client().listJson()).getFirst();
        var loader = new ToolDefLoader(List.of());
        var actions = new InstanceActions(() -> Map.of(TEMPLATE, onDisk), () -> loader, List::of);

        assertEquals(List.of("Open repo 'a' in VS Code"), actions.resolveActionsForInstance(instance).stream()
                .filter(action -> "vscode-remote".equals(action.toolName())).map(ToolAction::label).toList());
    }

    private static ImageDef.RepoEntry repo(String path) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/example/" + path.substring(2) + ".git");
        repo.setPath(path);
        return repo;
    }
}
