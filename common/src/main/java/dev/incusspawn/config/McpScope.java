package dev.incusspawn.config;

import dev.incusspawn.util.OutputFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which templates an {@code isx mcp} session may branch, adopt and fork from (#1812). The host's
 * own session gets {@code templates:} in {@code mcp.yaml}. A coordinator in an instance gets only
 * what is in both that list and its template's {@code coordinator.control-over}: the definition says
 * what the coordinator is for, {@code mcp.yaml} which templates any agent may ever touch, and
 * neither can widen the other.
 *
 * <p>Deny by default: a coordinator whose template declares no list, has no trusted definition,
 * or whose lineage is untrusted controls nothing. Every refusal and warning about a mismatch
 * comes from here, so each surface names the same list, file and key to change.
 */
public final class McpScope {

    /** Where the person approves templates for every agent, as every message names it. */
    public static final String MCP_YAML = "~/.config/incus-spawn/" + McpConfig.FILE_NAME;

    private final String coordinator;
    private final List<String> approved;
    private final List<String> declared;
    private final String declaredIn;
    private final String denied;

    private McpScope(String coordinator, List<String> approved, List<String> declared, String declaredIn, String denied) {
        this.coordinator = coordinator;
        this.approved = List.copyOf(approved);
        this.declared = declared == null ? null : List.copyOf(declared);
        this.declaredIn = declaredIn;
        this.denied = denied;
    }

    /** The host's own session: {@code mcp.yaml}'s list alone. */
    public static McpScope host(List<String> approved) {
        return new McpScope(null, approved, null, null, null);
    }

    /** A coordinator of {@code lineage}, as {@code InstanceRegistry.lineageOf} judged it. */
    public static McpScope coordinator(dev.incusspawn.proxy.InstanceRegistry.Lineage lineage, Map<String, ImageDef> defs,
                                       List<String> approved) {
        // Denied on the flag itself, never on whether a reason was written for it
        if (!lineage.untrusted()) return coordinator(lineage.template(), null, defs, approved);
        var why = lineage.why() == null || lineage.why().isBlank() ? "of no known lineage" : lineage.why();
        return coordinator(lineage.template(), why, defs, approved);
    }

    /**
     * A coordinator branched from {@code template}, judged against {@code defs}, which must hold
     * trusted definitions only for {@code isx mcp}. {@code untrusted} is why its lineage admits
     * nothing (a failed or project build in it), or null.
     */
    public static McpScope coordinator(String template, String untrusted, Map<String, ImageDef> defs, List<String> approved) {
        // Never null past here: a null coordinator is the host's scope, which would widen to mcp.yaml's list
        if (template == null) template = "";
        if (untrusted != null && !untrusted.isBlank()) return denied(template, approved, "it is " + untrusted);
        var def = template.isBlank() ? null : defs.get(template);
        if (def == null) {
            return denied(template, approved, template.isBlank()
                    ? "its template is not known"
                    : "template '" + template + "' has no definition in ~/.config/incus-spawn/images or isx's own");
        }
        if (ImageDef.anyProjectLocal(def, defs)) {
            return denied(template, approved, "a project-local definition is in its template's chain, and a cloned"
                    + " repository cannot let its branches drive isx");
        }
        // The layer whose coordinator: says anything decides alone, as it does the default
        var layer = ImageDef.coordinatorLayer(def, defs);
        if (layer != null && layer.getCoordinator().getControlOver() != null) {
            return new McpScope(template, approved, layer.getCoordinator().getControlOver(), layer.getName() + where(layer), null);
        }
        var named = layer == null ? def : layer;
        return new McpScope(template, approved, null, named.getName() + where(named), null);
    }

    private static McpScope denied(String template, List<String> approved, String why) {
        return new McpScope(template, approved, List.of(), null, why);
    }

    private static String where(ImageDef def) {
        return def.isBuiltIn() || def.getSource() == null || "unknown".equals(def.getSource()) ? "" : " (" + def.getSource() + ")";
    }

    /** Whether this is a coordinator's scope rather than the host's. */
    public boolean isCoordinator() {
        return coordinator != null;
    }

    /** The templates the session may use: both lists for a coordinator, in {@code mcp.yaml}'s order. */
    public List<String> templates() {
        if (coordinator == null) return approved;
        if (declared == null) return List.of();
        return approved.stream().filter(declared::contains).toList();
    }

    public boolean allows(String template) {
        return templates().contains(template);
    }

    /** The command that approves templates, for a person at a terminal (#2077). */
    public static final String SETUP_COMMAND = "isx mcp setup";

    /** What an agent is told about approvals: a person's step, never one it can take (#2077). */
    public static final String HOW_TO_APPROVE = "Only the user can approve templates, on the host: by running " + SETUP_COMMAND
            + " at a terminal, or by listing them under templates in " + MCP_YAML + ".";

    /** Why {@code template} is out of this scope, with what to change; null when it is in. */
    public String explain(String template) {
        if (allows(template)) return null;
        template = OutputFormat.oneLine(String.valueOf(template));
        var named = "template '" + template + "'";
        if (coordinator == null) {
            return named + " is not approved for agents" + (approved.isEmpty() ? " (none are)." : "; approved: "
                    + String.join(", ", approved) + ".") + " " + HOW_TO_APPROVE;
        }
        if (denied != null) return "this coordinator controls no template: " + denied + ".";
        var inApproved = approved.contains(template);
        if (declared == null) {
            return named + " is out of this coordinator's reach: its template " + coordinator + " declares no "
                    + "coordinator.control-over, so it controls nothing. Ask the user to list " + template
                    + " under coordinator.control-over in " + declaredIn
                    + (inApproved ? "." : ", and under templates in " + MCP_YAML + ".");
        }
        var inDeclared = declared.contains(template);
        if (inDeclared) return notApproved(template);
        return named + " is not in coordinator.control-over of " + declaredIn + ", which says what this coordinator"
                + " may control. Ask the user to add it there" + (inApproved ? "." : ", and to templates in " + MCP_YAML + ".");
    }

    private String notApproved(String template) {
        return "template '" + template + "' is in coordinator.control-over of " + declaredIn
                + " but not approved for agents. Ask the user to add " + template + " to templates in " + MCP_YAML
                + ", or remove it from coordinator.control-over there.";
    }

    /**
     * Each way the two lists disagree, as a warning naming the file and key to change: why the
     * scope is empty, and each template the definition lists that {@code mcp.yaml} does not
     * approve. Empty for the host's session and for a scope the lists agree on.
     */
    public List<String> mismatches() {
        if (coordinator == null) return List.of();
        var warnings = new ArrayList<String>();
        if (denied != null) {
            warnings.add((coordinator.isBlank() ? "This coordinator controls" : "Coordinators of " + coordinator + " control")
                    + " no template: " + denied + ".");
            return warnings;
        }
        if (declared == null) {
            warnings.add(coordinator + " declares no coordinator.control-over, so its coordinators control nothing."
                    + " List the templates they may control under coordinator.control-over in " + declaredIn + ".");
            return warnings;
        }
        for (var template : declared) {
            if (!approved.contains(template)) {
                warnings.add(template + " is in coordinator.control-over of " + declaredIn + " but not approved for agents."
                        + " Add it to templates in " + MCP_YAML + ", or remove it from coordinator.control-over there.");
            }
        }
        if (templates().isEmpty() && warnings.isEmpty()) {
            warnings.add("coordinator.control-over of " + declaredIn + " is empty, so its coordinators control nothing.");
        }
        return warnings;
    }

    /**
     * What a coordinator may control, in one line: {@code "tpl-a, tpl-b"}, or {@code "nothing"},
     * then any template its definition lists that {@code mcp.yaml} does not approve.
     */
    public String summary() {
        var effective = templates();
        var line = new StringBuilder(effective.isEmpty() ? "nothing" : String.join(", ", effective));
        if (declared != null && denied == null) {
            var unapproved = declared.stream().filter(t -> !approved.contains(t)).toList();
            if (!unapproved.isEmpty()) {
                line.append("; ").append(String.join(", ", unapproved)).append(" not approved in ").append(McpConfig.FILE_NAME);
            }
        }
        return line.toString();
    }
}
