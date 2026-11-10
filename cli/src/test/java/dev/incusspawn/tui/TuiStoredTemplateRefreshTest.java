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
 * A light refresh merges the listing into the definitions the last full reload loaded, which
 * already hold what the last merge took from build stamps: those are still no definitions, so a
 * template known only from its stamp stays one, and its stamp's parent is no unbuilt template.
 */
@ExtendWith(IsolatedHome.class)
class TuiStoredTemplateRefreshTest {

    private static ImageDef def(String name, String parent) {
        var def = new ImageDef();
        def.setName(name);
        def.setParent(parent);
        return def;
    }

    @Test
    void aLightRefreshKeepsAStampedTemplateAsStoredAndAddsNoRows() {
        var tui = new Tui();
        tui.useDefinitions(new java.util.HashMap<>(), new ToolDefLoader(List.of()), List.of());
        var stamp = new BuildSource(Map.of("tpl-gone", def("tpl-gone", "tpl-gone-parent"),
                "tpl-gone-parent", def("tpl-gone-parent", null)), Map.of(), Map.of(), Map.of());
        var daemon = new FakeIncusDaemon().container("tpl-gone", Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_VERSION, BuildInfo.instance().version(),
                Metadata.BUILD_SOURCE, stamp.toJson()));
        var listing = InstanceListing.collectEntries(daemon.client().listJson());

        tui.loader.mergeInstances(listing);
        var rows = tui.templateEntries.stream().map(InstanceListing.TemplateInfo::name).toList();
        tui.loader.mergeInstances(listing);

        assertEquals(Set.of("tpl-gone"), tui.storedSourceTemplates);
        assertEquals(rows, tui.templateEntries.stream().map(InstanceListing.TemplateInfo::name).toList());
        assertEquals(List.of("tpl-gone"), rows);
    }
}
