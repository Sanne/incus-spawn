package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which file the list says a template was built from (#1099): read from the {@code build-source}
 * stamp the listing carries, re-read when a rebuild re-stamps it, and named on the △ context line.
 */
@ExtendWith(IsolatedHome.class)
class TuiBuiltFromTest {

    private static final String TEMPLATE = "tpl-app";
    private static final String CURRENT = "/home/me/work/.incus-spawn/images/tpl-app.yaml";
    private static final String OLD = "/home/me/.config/incus-spawn/images/a-rather-long-old-tpl-app-definition.yaml";

    private static ImageDef definition() {
        var def = new ImageDef();
        def.setName(TEMPLATE);
        def.setImage("fedora-44");
        def.setSource(CURRENT);
        return def;
    }

    /** The listing of one built template whose build stamped {@code builtFrom}, with a stale definition sha. */
    private static List<InstanceListing.InstanceInfo> listing(String builtFrom) {
        var daemon = new FakeIncusDaemon().container(TEMPLATE, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.PROFILE, TEMPLATE,
                Metadata.BUILD_VERSION, BuildInfo.instance().version(),
                Metadata.DEFINITION_SHA, "sha-of-an-older-definition",
                Metadata.BUILD_SOURCE, new BuildSource(Map.of(TEMPLATE, definition()), Map.of(), Map.of(),
                        Map.of(TEMPLATE, builtFrom)).toJson()));
        return InstanceListing.collectEntries(daemon.client().listJson());
    }

    private static Tui list() {
        var list = new Tui();
        list.useDefinitions(new java.util.HashMap<>(Map.of(TEMPLATE, definition())),
                new ToolDefLoader(List.of()), List.of());
        return list;
    }

    private static String contextLine(Tui list) {
        list.buildTemplateRowData();
        var template = new InstanceListing.TemplateInfo(TEMPLATE, "", "2026-10-01", "container",
                BuildInfo.instance().version(), "sha-of-an-older-definition", "", "", -1, -1, "container");
        return new MainScreen(list).buildContextLine(template, null, true).spans().stream()
                .map(s -> s.content()).reduce("", String::concat);
    }

    @Test
    void aRebuildThatReStampsTheFileIsReadAgain() {
        var list = list();
        list.loader.mergeInstances(listing(OLD));
        assertEquals(OLD, list.loader.builtFrom(TEMPLATE));
        list.loader.mergeInstances(listing(OLD));
        assertEquals(OLD, list.loader.builtFrom(TEMPLATE));
        list.loader.mergeInstances(listing(CURRENT));
        assertEquals(CURRENT, list.loader.builtFrom(TEMPLATE));
    }

    @Test
    void theContextLineNamesTheOtherFileByItsNameOnly() {
        var list = list();
        list.loader.mergeInstances(listing(OLD));
        var line = contextLine(list);
        assertTrue(line.contains("△ definition changed since last build (built from a-rather-long-old-tpl-app-definition.yaml)"), line);
        assertFalse(line.contains("/home/me/.config"), line);
    }

    @Test
    void theBuiltInPlaceholderIsSaidInWords() {
        var list = list();
        list.loader.mergeInstances(listing("built-in"));
        var line = contextLine(list);
        assertTrue(line.contains("(built from the built-in definition)"), line);
    }

    @Test
    void theSameFileAddsNothing() {
        var list = list();
        list.loader.mergeInstances(listing(CURRENT));
        var line = contextLine(list);
        assertTrue(line.contains("△ definition changed since last build"), line);
        assertFalse(line.contains("built from"), line);
    }

    @Test
    void aFileOfTheSameNameElsewhereIsCalledAnother() {
        var list = list();
        list.loader.mergeInstances(listing("/home/me/.config/incus-spawn/images/tpl-app.yaml"));
        var line = contextLine(list);
        assertTrue(line.contains("(built from another tpl-app.yaml)"), line);
    }

    @Test
    void aBuildFromStoredMetadataStaysShortOnTheBar() {
        var list = list();
        list.loader.mergeInstances(listing("stored"));
        var line = contextLine(list);
        assertTrue(line.contains("(built from stored definition)"), line);
    }
}
