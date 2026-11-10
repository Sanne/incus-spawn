package dev.incusspawn.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a definition reaches beyond its own instance (#2076): the host paths it mounts, the host
 * repositories it exports, the host's display, the accounts it pins and the instances its
 * coordinators may control. Once something other than the person writes a template, this is what
 * the person reviews, so the template check reports it beside its findings.
 * Only the definition's own fields count: what its parent reaches was reviewed with the parent.
 *
 * @param kind   {@code host-image}, {@code host-resource}, {@code host-source}, {@code gui}, {@code account} or {@code coordinator}
 * @param field  the key path, such as {@code host-resources[0]}
 * @param detail what it reaches, for a person to read
 */
public record TemplateReach(String kind, String field, String detail) {

    /**
     * Every reach of {@code def}'s own fields, in the order of the format's reference. A
     * {@code projectLocal} definition is said to reach what it does there: its {@code gui} is no
     * branch default and its {@code coordinator} is ignored, so neither is told as reaching more.
     */
    public static List<TemplateReach> of(ImageDef def, boolean projectLocal) {
        var reach = new ArrayList<TemplateReach>();
        // A child's own image_url is read too when a build starts it from scratch, as one that changes
        // machine type; its vm_image_url never is, since that build takes the root's
        imageFromHost(reach, "image_url", def.getImageUrl(), def.isRoot());
        if (def.isRoot()) imageFromHost(reach, "vm_image_url", def.getVmImageUrl(), true);
        var resources = def.getHostResources();
        for (int i = 0; i < resources.size(); i++) {
            var hr = resources.get(i);
            reach.add(new TemplateReach("host-resource", "host-resources[" + i + "]",
                    "mounts host " + hr.getSource() + " at " + (hr.getPath() == null ? "the same path" : hr.getPath())
                            + " (" + hr.getMode() + ")"));
        }
        var repos = def.getRepos();
        for (int i = 0; i < repos.size(); i++) {
            var repo = repos.get(i);
            if (repo.isHostSource()) {
                reach.add(new TemplateReach("host-source", "repos[" + i + "].host-source",
                        "exports host repository " + repo.getHostSource() + ", unpushed history included"));
            }
        }
        if (def.isGui()) {
            reach.add(new TemplateReach("gui", "gui", projectLocal
                    ? "branches get the host's GPU, Wayland display and audio only with --gui (never by default in a project)"
                    : "branches get the host's GPU, Wayland display and audio by default"));
        }
        // Sorted: the accounts map is a Map.copyOf, whose order changes from one run to the next.
        def.getAccounts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> reach.add(new TemplateReach("account", "accounts." + e.getKey(),
                        "signs in with the host's '" + e.getValue() + "' " + e.getKey() + " account")));
        var coordinator = def.getCoordinator();
        if (coordinator != null && projectLocal) {
            reach.add(new TemplateReach("coordinator", "coordinator",
                    "ignored: a project-local template makes no coordinators"));
        } else if (coordinator != null) {
            var over = coordinator.getControlOver();
            if (over != null && !over.isEmpty()) {
                reach.add(new TemplateReach("coordinator", "coordinator.control-over",
                        "its branches may create instances of " + String.join(", ", over)
                                + " (as far as mcp.yaml allows)"
                                + (coordinator.getIsxControlCapability() == null ? ", and are isx MCP clients by default" : "")));
            }
            if (Boolean.TRUE.equals(coordinator.getIsxControlCapability())) {
                reach.add(new TemplateReach("coordinator", "coordinator.isx-control-capability",
                        "its branches are isx MCP clients by default"));
            }
        }
        return List.copyOf(reach);
    }

    /** A base image a {@code file://} URL imports from the host, which is a host file the build reads. */
    private static void imageFromHost(List<TemplateReach> reach, String field, String url, boolean root) {
        if (url != null && url.regionMatches(true, 0, "file:", 0, 5)) {
            reach.add(new TemplateReach("host-image", field, "imports host file " + url + " as the base image"
                    + (root ? "" : " when a build starts it from scratch (a change of machine type)")));
        }
    }

    /** The fields of a reach in {@code --format=json}, in order: add to the end, never rename. */
    public Map<String, Object> record() {
        var record = new LinkedHashMap<String, Object>();
        record.put("kind", kind);
        record.put("field", field);
        record.put("detail", detail);
        return record;
    }
}
