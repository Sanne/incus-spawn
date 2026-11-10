package dev.incusspawn.tui;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A built template whose definition is not loaded (#2057): after a rename of {@code name:} in its
 * file, the old build is still listed, but its row says it has no definition, the context line
 * says the file now defines the new name, and the build menu cannot rebuild it.
 */
@ExtendWith(IsolatedHome.class)
class TuiMissingDefinitionTest {

    private static final String FILE = "/home/me/.config/incus-spawn/images/isx-presentation.yaml";

    private static ImageDef def(String name) {
        var def = new ImageDef();
        def.setName(name);
        def.setImage("fedora-44");
        def.setSource(FILE);
        return def;
    }

    /** Both templates built, each from {@link #FILE} under its name at the time. */
    private static List<InstanceListing.InstanceInfo> listing() {
        var daemon = new FakeIncusDaemon();
        for (var name : List.of("isx-presentation", "tpl-isx-presentation")) {
            daemon.container(name, Map.of(
                    Metadata.TYPE, Metadata.TYPE_BASE,
                    Metadata.BUILD_VERSION, BuildInfo.instance().version(),
                    Metadata.BUILD_SOURCE, new BuildSource(Map.of(name, def(name)), Map.of(), Map.of(),
                            Map.of(name, FILE)).toJson()));
        }
        return InstanceListing.collectEntries(daemon.client().listJson());
    }

    private static Tui renamed() {
        var tui = new Tui();
        tui.useDefinitions(new java.util.HashMap<>(Map.of("tpl-isx-presentation", def("tpl-isx-presentation"))),
                new ToolDefLoader(List.of()), List.of());
        tui.loader.mergeInstances(listing());
        tui.buildTemplateRowData();
        return tui;
    }

    private static InstanceListing.TemplateInfo row(Tui tui, String name) {
        return tui.templateEntries.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theOldNameIsMarkedAndTheNewOneIsNot() {
        var tui = renamed();

        assertEquals(Set.of("isx-presentation"), tui.missingDefinitions.keySet());
        assertTrue(tui.anyMissingDefinition, "the legend explains the ◌");
        var line = new MainScreen(tui).buildContextLine(row(tui, "isx-presentation"), null, true).spans().stream()
                .map(s -> s.content()).reduce("", String::concat);
        assertTrue(line.contains("◌ no definition: " + FILE + " now defines tpl-isx-presentation"), line);
    }

    /** A live refresh merges into the same definitions, the stamp's included: still no definition. */
    @Test
    void aLiveRefreshDoesNotTakeTheStampForADefinition() {
        var tui = renamed();
        tui.loader.mergeInstances(listing());

        assertEquals(Set.of("isx-presentation"), tui.missingDefinitions.keySet());
    }

    @Test
    void theBuildMenuCannotRebuildIt() {
        var tui = renamed();
        var menu = new BuildMenu(new ModalRenderer(TuiTheme.dark()), TuiTheme.dark(), row(tui, "isx-presentation"),
                tui.imageDefs, tui.templateEntries, Set.of(), List.of(), tui.missingDefinitions);

        var option = menu.selected();
        assertEquals("Rebuild isx-presentation", option.label());
        assertFalse(option.enabled());
        assertTrue(option.description().contains("Build tpl-isx-presentation instead"), option.description());
    }

    @Test
    void outOfSyncLeavesItOut() {
        var tui = new Tui();
        tui.useDefinitions(new java.util.HashMap<>(), new ToolDefLoader(List.of()), List.of());
        var daemon = new FakeIncusDaemon().container("isx-presentation", Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_VERSION, "0.0.1",
                Metadata.BUILD_SOURCE, new BuildSource(Map.of("isx-presentation", def("isx-presentation")),
                        Map.of(), Map.of(), Map.of("isx-presentation", FILE)).toJson()));
        tui.loader.mergeInstances(InstanceListing.collectEntries(daemon.client().listJson()));
        tui.buildTemplateRowData();

        var menu = new BuildMenu(new ModalRenderer(TuiTheme.dark()), TuiTheme.dark(), row(tui, "isx-presentation"),
                tui.imageDefs, tui.templateEntries, Set.of(), List.of(), tui.missingDefinitions);
        assertTrue(tui.anyTemplateOutdated);
        assertTrue(tui.missingDefinitions.containsKey("isx-presentation"));
        assertFalse(menu.selected().enabled());
    }
}
