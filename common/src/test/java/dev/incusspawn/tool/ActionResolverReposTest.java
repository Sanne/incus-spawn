package dev.incusspawn.tool;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.TempHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The repos an instance's {@code expand: repos} actions are offered for (#1445): a clone's are
 * those it was built with, like its tools, so a repo added to the YAML after branching is not
 * offered for a directory the clone does not have. A template's come from its YAML.
 */
@ExtendWith(TempHome.class)
class ActionResolverReposTest {

    private static final String BASE = "tpl-base";
    private static final String TEMPLATE = "tpl-agent";
    private static final String CLONE = "dev-1";

    private static ImageDef def(String name, String parent, String... repoPaths) {
        var def = new ImageDef();
        def.setName(name);
        def.setParent(parent);
        def.setRepos(Arrays.stream(repoPaths).map(path -> {
            var repo = new ImageDef.RepoEntry();
            repo.setUrl("https://github.com/example/" + path.substring(2) + ".git");
            repo.setPath(path);
            return repo;
        }).toList());
        return def;
    }

    /** The chain {@code tpl-base} (repo {@code ~/base}) → {@code tpl-agent} with {@code leafRepos}. */
    private static Map<String, ImageDef> chain(String... leafRepos) {
        return Map.of(BASE, def(BASE, null, "~/base"), TEMPLATE, def(TEMPLATE, BASE, leafRepos));
    }

    private static List<String> repoPaths(String type, Map<String, ImageDef> built, Map<String, ImageDef> onDisk) {
        var name = Metadata.TYPE_BASE.equals(type) ? TEMPLATE : CLONE;
        var daemon = new FakeIncusDaemon().container(name, Map.of(
                Metadata.TYPE, type,
                Metadata.PROFILE, TEMPLATE,
                Metadata.BUILD_SOURCE, new BuildSource(built, Map.of(), Map.of(), Map.of()).toJson()));
        var resolver = new ActionResolver(daemon.client(), new ToolDefLoader(List.of()), List.of(), onDisk);
        return resolver.buildActionContext(name, resolver.readInstance(name), TEMPLATE).repos().stream()
                .map(ActionContext.RepoInfo::path).toList();
    }

    @Test
    void aCloneOffersTheReposItWasBuiltWithNotOnesAddedToTheYamlSince() {
        assertEquals(List.of("/home/agentuser/base", "/home/agentuser/a"),
                repoPaths(Metadata.TYPE_CLONE, chain("~/a"), chain("~/a", "~/b")));
    }

    @Test
    void aTemplateOnDiskOffersTheReposItsYamlNames() {
        assertEquals(List.of("/home/agentuser/base", "/home/agentuser/a", "/home/agentuser/b"),
                repoPaths(Metadata.TYPE_BASE, chain("~/a"), chain("~/a", "~/b")));
    }

    @Test
    void aTemplateWhoseYamlWasDeletedOffersTheReposItWasBuiltWith() {
        assertEquals(List.of("/home/agentuser/base", "/home/agentuser/a"),
                repoPaths(Metadata.TYPE_BASE, chain("~/a"), Map.of()));
    }
}
