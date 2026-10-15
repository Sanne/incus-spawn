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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which tools' actions the TUI offers a template on F9, and picks Enter's default action from.
 * Its own copy of {@code ActionResolver.collectInstalledTools}' rule (#1158 would merge them), so
 * it is pinned here: a template uses its YAML, unless that is gone, when the tools it was built
 * with stand in (#868).
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
}
