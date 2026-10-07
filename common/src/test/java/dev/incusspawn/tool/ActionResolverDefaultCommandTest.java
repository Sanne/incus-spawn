package dev.incusspawn.tool;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.TempHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The default action a new branch opens with, the one rule {@code isx branch} and the TUI's
 * branch dialog share (#868): the reference from the current YAML, matched against the tools the
 * source was built with.
 */
@ExtendWith(TempHome.class)
class ActionResolverDefaultCommandTest {

    private static final String TEMPLATE = "tpl-agent";
    private static final String SOURCE = "dev-1";

    /** A tool with one shell action, optionally behind a feature gate and expanded per repo. */
    private record Tool(String name, String feature, String expand) implements ToolSetup {
        Tool(String name) {
            this(name, null, null);
        }

        @Override
        public List<ToolDef.ActionEntry> actions() {
            var entry = new ToolDef.ActionEntry();
            entry.setId("go");
            entry.setType("shell");
            entry.setExpand(expand);
            entry.setCommand(expand == null ? "run-" + name : "cd ${repo_path} && run-" + name);
            return List.of(entry);
        }

        @Override
        public void install(dev.incusspawn.incus.Container container, Map<String, String> resolvedParams) {}
    }

    private static ImageDef template(String defaultAction, String... tools) {
        var def = new ImageDef();
        def.setName(TEMPLATE);
        def.setDefaultAction(defaultAction);
        def.setTools(java.util.Arrays.stream(tools).map(ToolDef.ToolRef::new).toList());
        return def;
    }

    private static String buildSource(String... tools) {
        return new BuildSource(Map.of(TEMPLATE, template(null, tools)), Map.of(), Map.of(), Map.of()).toJson();
    }

    /** The default command for a branch of {@code source}, from the source as preflight read it. */
    private static String resolve(FakeIncusDaemon daemon, String source, Map<String, ImageDef> defs,
                                  ToolSetup... tools) {
        // Airgapped: what is under test is the resolution, not the proxy health check.
        var request = new BranchFlow.Request(source, "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
        var preflight = BranchFlow.preflight(daemon.client(), request, defs);
        var before = daemon.requests().size();
        var resolver = new ActionResolver(daemon.client(), new ToolDefLoader(List.of()), List.of(tools), defs);
        var command = resolver.defaultCommandForBranch(preflight.template(), preflight.sourceInstance());
        assertEquals(before, daemon.requests().size(),
                () -> "the source as preflight read it is enough, yet it asked Incus:\n  "
                        + String.join("\n  ", daemon.requests().subList(before, daemon.requests().size())));
        return command;
    }

    private static Map<String, String> clone(String buildSource) {
        var config = new HashMap<String, String>();
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        config.put(Metadata.PROFILE, TEMPLATE);
        if (buildSource != null) config.put(Metadata.BUILD_SOURCE, buildSource);
        return config;
    }

    @Test
    void aCloneOpensWithItsTemplatesDefaultAction() {
        var daemon = new FakeIncusDaemon().container(SOURCE, clone(buildSource("agent")));
        assertEquals("run-agent",
                resolve(daemon, SOURCE, Map.of(TEMPLATE, template("agent", "agent")), new Tool("agent")));
    }

    @Test
    void aToolAddedToTheYamlWithoutARebuildYieldsAPlainShell() {
        // The YAML now names 'agent' as the default, but the source was built without it.
        var daemon = new FakeIncusDaemon().container(SOURCE, clone(buildSource("other")));
        assertNull(resolve(daemon, SOURCE, Map.of(TEMPLATE, template("agent", "agent", "other")),
                new Tool("agent"), new Tool("other")));
    }

    @Test
    void aBuiltTemplateWhoseYamlWasDeletedStillOpensWithItsSnapshottedAction() {
        var daemon = new FakeIncusDaemon().container(TEMPLATE, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.PROFILE, TEMPLATE,
                Metadata.DEFAULT_ACTION, "agent",
                Metadata.BUILD_SOURCE, buildSource("agent")));
        assertEquals("run-agent", resolve(daemon, TEMPLATE, Map.of(), new Tool("agent")));
    }

    @Test
    void aTemplateOnDiskIsMatchedAgainstItsDefinition() {
        var daemon = new FakeIncusDaemon().container(TEMPLATE, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_SOURCE, buildSource("old")));
        assertEquals("run-agent",
                resolve(daemon, TEMPLATE, Map.of(TEMPLATE, template("agent", "agent")),
                        new Tool("agent"), new Tool("old")));
    }

    @Test
    void theYamlFallbackLeavesOutAToolWhoseFeatureIsOff() {
        // No build record, so the tools come from the YAML chain, which must drop gated tools.
        var daemon = new FakeIncusDaemon().container(SOURCE, clone(null));
        assertNull(resolve(daemon, SOURCE, Map.of(TEMPLATE, template("agent", "agent")),
                new Tool("agent", "experimental-agent", null)));
    }

    @Test
    void aRepoExpandedActionResolvesAgainstTheTemplatesRepo() {
        var def = template("agent:go", "agent");
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/example/project.git");
        repo.setPath("~/project");
        def.setRepos(List.of(repo));
        var daemon = new FakeIncusDaemon().container(SOURCE, clone(buildSource("agent")));
        assertEquals("cd /home/agentuser/project && run-agent",
                resolve(daemon, SOURCE, Map.of(TEMPLATE, def), new Tool("agent", null, YamlToolAction.EXPAND_REPOS)));
    }

    @Test
    void noDefaultActionMeansAPlainShell() {
        var daemon = new FakeIncusDaemon().container(SOURCE, clone(buildSource("agent")));
        assertNull(resolve(daemon, SOURCE, Map.of(TEMPLATE, template(null, "agent")), new Tool("agent")));
    }
}
