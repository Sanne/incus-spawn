package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.TemplateDetails;
import dev.incusspawn.command.BuildCommand;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SecretRedactor;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Padding;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The F3 template details, full screen rather than a dialog: the effective settings of a
 * template run long. The compact view shows what a build of it produces (see
 * {@link TemplateDetails}); the tree view shows each setting on the layer that declares it.
 *
 * <p>Owns its view state and scroll keys. The keys with side effects (F4 edit, n new child,
 * Esc close) stay with {@link Tui}, which supplies what the view reads through
 * {@link Source} so it can be rendered headlessly.
 */
final class TemplateDetailView {

    /** What the view reads from the TUI: definitions, tool metadata, host state and the list's flags. */
    interface Source {
        Map<String, ImageDef> imageDefs();

        /** Transitive tool dependencies that the explicit list does not name. */
        List<String> autoDeps(List<String> explicitTools);

        /** The host checkout a repo URL is linked to, or null. */
        Path hostRepoMatch(String cloneUrl);

        boolean definitionChanged(String template);

        boolean parentRebuilt(String template);

        /** Where the definition this one replaces from an earlier search layer lives, or null. */
        String overriddenSource(String template);

        /** The file the built image was built from, as its build stamped it, or null. */
        String builtFrom(String template);

        String currentVersion();
    }

    private static final int LABEL_WIDTH = 16;

    private final ModalRenderer modal;
    private final TuiTheme theme;
    private final Source source;
    private final Supplier<LocalDateTime> clock;

    private boolean compact = true;
    private int scrollOffset;
    private int pageHeight = 10; // content rows, as last rendered

    TemplateDetailView(ModalRenderer modal, TuiTheme theme, Source source, Supplier<LocalDateTime> clock) {
        this.modal = modal;
        this.theme = theme;
        this.source = source;
        this.clock = clock;
    }

    /** Reset to the compact view at the top, as each F3 opens it. */
    void open() {
        compact = true;
        scrollOffset = 0;
    }

    /** Handle view and scroll keys; false for keys the caller owns. */
    boolean handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.TAB) || ShiftTabBindings.isShiftTab(key)) {
            compact = !compact;
            scrollOffset = 0;
        } else if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            scrollBy(1);
        } else if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            scrollBy(-1);
        } else if (key.isKey(KeyCode.PAGE_DOWN) || key.isChar(' ')) {
            scrollBy(pageHeight);
        } else if (key.isKey(KeyCode.PAGE_UP)) {
            scrollBy(-pageHeight);
        } else if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            scrollOffset = 0;
        } else if (key.isKey(KeyCode.END) || key.isChar('G')) {
            scrollOffset = Integer.MAX_VALUE; // capped during render
        } else {
            return false;
        }
        return true;
    }

    private void scrollBy(int rows) {
        scrollOffset = (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) scrollOffset + rows));
    }

    void render(Frame frame, Rect screen, InstanceListing.TemplateInfo template) {
        var block = Block.builder()
                .borders(Borders.ALL).borderType(BorderType.DOUBLE)
                .title(modal.styledTitle(" " + template.name() + " — " + (compact ? "Compact" : "Tree") + " ",
                        modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(Padding.horizontal(1))
                .build();
        frame.buffer().fill(screen, new Cell(" ", Style.EMPTY.bg(modal.bg())));
        frame.renderWidget(block, screen);
        var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(block.inner(screen));

        int textWidth = rows.get(0).width() - 1; // leave room for the scrollbar
        var lines = compact ? compactLines(template, textWidth) : treeLines(template.name(), textWidth);
        pageHeight = Math.max(1, rows.get(0).height() - 1);
        scrollOffset = modal.renderScrollableContent(frame, rows.get(0), lines, scrollOffset);

        var hints = new ArrayList<Span>();
        modal.addKey(hints, "Tab", compact ? "Tree view" : "Compact view");
        modal.addKey(hints, "↑↓/PgUp/PgDn", "Scroll");
        modal.addKey(hints, "F4", "Edit");
        modal.addKey(hints, "n", "New child…");
        modal.addKey(hints, "F3/Esc", "Close");
        frame.renderWidget(Paragraph.from(Line.from(hints)), rows.get(1));
    }

    // --- Styles ---

    private Style lineStyle() { return Style.EMPTY.fg(modal.fg()).bg(modal.bg()); }
    private Style labelStyle() { return Style.EMPTY.fg(modal.accent()).bg(modal.bg()); }
    private Style dimStyle() { return Style.EMPTY.fg(theme.textDim()).bg(modal.bg()); }
    private Style warnStyle() { return Style.EMPTY.fg(theme.statusWarning()).bg(modal.bg()); }

    private List<ImageDef> chain(String templateName) {
        var defs = source.imageDefs();
        var def = defs.get(templateName);
        return def == null ? List.of() : ImageDef.chain(def, defs);
    }

    // --- Compact view: the effective settings ---

    List<Line> compactLines(InstanceListing.TemplateInfo template, int width) {
        var chain = chain(template.name());
        if (chain.isEmpty()) return List.of();

        var lines = new ArrayList<Line>();
        var current = chain.get(chain.size() - 1);
        var details = TemplateDetails.resolve(current, source.imageDefs());
        var lineStyle = lineStyle();
        var labelStyle = labelStyle();
        var dimStyle = dimStyle();

        if (!current.getDescription().isEmpty()) {
            for (var text : wrap(current.getDescription(), width)) lines.add(Line.styled(text, lineStyle));
            lines.add(Line.styled("", lineStyle));
        }

        // Runtime: always shown, defaults spelled out
        lines.add(row("Type:", TemplateDetails.typeLabel(details.type()), lineStyle));
        if (template.isBuilt()) {
            var built = TemplateDetails.staleBuiltType(details.type(), template.instanceMode(), template.runtime());
            if (built != null) {
                lines.add(row("", "built as " + TemplateDetails.typeLabel(built) + " — rebuild to apply",
                        warnStyle()));
            }
        }
        lines.add(row("GUI:", details.gui() ? guiLabel(current, details.type(), source.imageDefs()) : "off", lineStyle));
        lines.add(row("Workdir:", orDefault(details.workdir(), "(home directory)"),
                details.workdir() != null ? lineStyle : dimStyle));
        lines.add(row("Shell command:", orDefault(details.shellCommand(), "(login shell)"),
                details.shellCommand() != null ? lineStyle : dimStyle));
        lines.add(row("Default action:", orDefault(details.defaultAction(), "(shell)"),
                details.defaultAction() != null ? lineStyle : dimStyle));
        lines.add(Line.styled("", lineStyle));

        addBuildStatus(lines, template);

        // Origin
        lines.add(row("Source:", sourceLabel(current.getSource()), dimStyle));
        var builtFrom = template.isBuilt() ? otherBuildFile(source.builtFrom(template.name()), current) : null;
        if (builtFrom != null) {
            // Only a lead when the definition differs too; a moved, unchanged file needs no rebuild
            lines.add(row("", "built from " + sourceLabel(builtFrom),
                    source.definitionChanged(template.name()) ? warnStyle() : dimStyle));
        }
        var overridden = source.overriddenSource(template.name());
        if (overridden != null) lines.add(row("Overrides:", sourceLabel(overridden), dimStyle));
        addBaseImage(lines, details.root());
        if (chain.size() > 1) {
            var names = chain.stream().map(ImageDef::getName).toList();
            lines.add(row("Inherits:", String.join(" → ", names), lineStyle));
        }
        lines.add(Line.styled("", lineStyle));

        var allPackages = new ArrayList<String>();
        for (var def : chain) allPackages.addAll(def.getPackages());
        addSection(lines, "Packages", allPackages);

        var allToolsFormatted = new ArrayList<String>();
        var allToolNames = new ArrayList<String>();
        for (var def : chain) {
            for (var toolRef : def.getTools()) {
                allToolsFormatted.add(formatToolWithParams(toolRef));
                allToolNames.add(toolRef.getName());
            }
        }
        addSection(lines, "Tools", allToolsFormatted);
        addOptionalSection(lines, "Dependencies (auto)", source.autoDeps(allToolNames));

        var allRepos = new ArrayList<ImageDef.RepoEntry>();
        for (var def : chain) allRepos.addAll(def.getRepos());
        if (allRepos.isEmpty()) {
            lines.add(Line.from(List.of(Span.styled("Repos: ", labelStyle), Span.styled("(none)", dimStyle))));
        } else {
            lines.add(Line.styled("Repos:", labelStyle));
            for (var repo : allRepos) {
                lines.add(Line.styled("  " + repo.getUrl() + " → " + repo.getPath(), lineStyle));
                if (repo.hasPrime()) {
                    lines.add(Line.from(List.of(
                            Span.styled("    prime: ", labelStyle),
                            Span.styled(repo.getPrime(), lineStyle))));
                }
                var hostMatch = source.hostRepoMatch(repo.getUrl());
                lines.add(Line.styled(hostMatch != null
                        ? "    Linked to host repository at " + hostMatch
                        : "    No matching host checkout found", dimStyle));
            }
        }
        lines.add(Line.styled("", lineStyle));

        var allHostResources = new ArrayList<String>();
        for (var def : chain) {
            for (var hr : def.getHostResources()) {
                var containerPath = HostResourceSetup.resolveContainerPath(hr.getSource(), hr.getPath());
                allHostResources.add(hr.getSource() + " → " + containerPath + "  (" + hr.getMode() + ")");
            }
        }
        addSection(lines, "Host Resources", allHostResources);

        // Newer settings: shown only when something sets them, to keep the view short
        var accounts = new ArrayList<String>();
        details.accounts().forEach((ns, account) -> accounts.add(ns + " = " + account));
        addOptionalSection(lines, "Accounts", accounts);

        var env = new ArrayList<String>();
        for (var layerEnv : details.env()) env.add(formatEnv(layerEnv.entry()) + "  (" + layerEnv.layer() + ")");
        addOptionalSection(lines, "Environment", env);

        var skills = new ArrayList<>(details.skills());
        for (var repo : details.skillRepos()) skills.add("catalog: " + repo);
        addOptionalSection(lines, "Skills", skills);

        addOptionalSection(lines, "Package repos", details.packageRepos());
        addOptionalSection(lines, "Removed packages", details.removePackages());
        addOptionalSection(lines, "Masked services", details.maskServices());

        var notes = new ArrayList<String>();
        for (var note : details.agentNotes()) {
            if (!notes.isEmpty()) notes.add("");
            for (var noteLine : note.split("\n")) notes.addAll(wrap(noteLine, Math.max(20, width - 2)));
        }
        addOptionalSection(lines, "Agent notes", notes);

        // Drop the spacer the last section leaves behind
        var last = lines.get(lines.size() - 1);
        if (last.spans().stream().allMatch(s -> s.content().isEmpty())) lines.remove(lines.size() - 1);
        return lines;
    }

    /** A definition's origin for display: a path, or the words for a placeholder that is none. */
    static String sourceLabel(String source) {
        if (source == null) return "(unknown)";
        return switch (source) {
            case "built-in" -> "the built-in definition";
            case "stored" -> "the definition stored with an earlier build";
            default -> source;
        };
    }

    /**
     * The file name alone, for a one-line bar that has no room for a full path; "another" when
     * {@code current} has the same name, so the two do not read as one file.
     */
    static String shortSourceLabel(String source, String current) {
        if ("stored".equals(source)) return "stored definition";
        if (!source.startsWith("/")) return sourceLabel(source);
        var fileName = java.nio.file.Path.of(source).getFileName();
        if (fileName == null) return source;
        var name = fileName.toString();
        var currentName = current != null && current.startsWith("/")
                ? java.nio.file.Path.of(current).getFileName().toString() : null;
        return name.equals(currentName) ? "another " + name : name;
    }

    /** The file a build was made from when it is not where {@code def} is now read from, else null. */
    static String otherBuildFile(String builtFrom, ImageDef def) {
        return builtFrom != null && def != null && !builtFrom.equals(def.getSource()) ? builtFrom : null;
    }

    private Line row(String label, String value, Style valueStyle) {
        return Line.from(List.of(
                Span.styled(String.format("%-" + LABEL_WIDTH + "s", label), labelStyle()),
                Span.styled(value, valueStyle)));
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }

    /** Built date and age, what the list flags as out of sync, the building isx version and size. */
    private void addBuildStatus(List<Line> lines, InstanceListing.TemplateInfo template) {
        if (!template.isBuilt()) {
            lines.add(row("Status:", "not built", Style.EMPTY.fg(theme.statusStopped()).bg(modal.bg())));
            lines.add(Line.styled("", lineStyle()));
            return;
        }
        var spans = new ArrayList<Span>();
        spans.add(Span.styled(String.format("%-" + LABEL_WIDTH + "s", "Built:"), labelStyle()));
        spans.add(Span.styled(template.buildStatus(), lineStyle()));
        if (InstanceListing.parseTimestamp(template.buildStatus()) != null) {
            spans.add(Span.styled("  (" + Metadata.ageDescription(template.buildStatus(), clock.get()) + ")",
                    dimStyle()));
        }
        lines.add(Line.from(spans));

        var currentVersion = source.currentVersion();
        if (template.buildVersion().isEmpty()) {
            lines.add(row("", "! built before isx version tracking", warnStyle()));
        } else if (!template.buildVersion().equals(currentVersion)) {
            lines.add(row("", "! built with isx v" + template.buildVersion()
                    + " (current: v" + currentVersion + ")", warnStyle()));
        } else {
            lines.add(row("Built with:", "isx v" + template.buildVersion(), dimStyle()));
        }
        if (source.definitionChanged(template.name())) {
            lines.add(row("", "△ definition changed since last build", warnStyle()));
        }
        if (source.parentRebuilt(template.name())) {
            var def = source.imageDefs().get(template.name());
            var parentName = def != null ? def.getParent() : template.parent();
            lines.add(row("", "↑ parent " + parentName + " was rebuilt since last build", warnStyle()));
        }
        if (template.diskUsage() >= 0) {
            lines.add(row("Disk:", UsageFormat.diskCell(template.diskUsage())
                    + "  (approx, excludes shared blocks)", lineStyle()));
        }
        lines.add(Line.styled("", lineStyle()));
    }

    /** The base image line, plus where a prebuilt image comes from and whether it is pinned. */
    private void addBaseImage(List<Line> lines, ImageDef root) {
        var spans = new ArrayList<Span>();
        spans.add(Span.styled(String.format("%-" + LABEL_WIDTH + "s", "Base image:"), labelStyle()));
        spans.add(Span.styled(root.getImage(), lineStyle()));
        if (root.getImageTag() != null) spans.add(Span.styled("  tag " + root.getImageTag(), dimStyle()));
        if (root.isPinned()) spans.add(Span.styled("  (pinned)", labelStyle()));
        lines.add(Line.from(spans));
        if (root.getImageUrl() != null) {
            lines.add(row("", "from " + BuildCommand.resolveImageUrl(root.getImageUrl(), root.getImageTag()),
                    dimStyle()));
        }
        if (root.getVmImageUrl() != null) {
            lines.add(row("", "VM image from "
                    + BuildCommand.resolveImageUrl(root.getVmImageUrl(), root.getImageTag()), dimStyle()));
        }
    }

    /** A section that is always listed, saying "(none)" when empty. */
    private void addSection(List<Line> lines, String label, List<String> items) {
        if (items.isEmpty()) {
            lines.add(Line.from(List.of(Span.styled(label + ": ", labelStyle()), Span.styled("(none)", dimStyle()))));
            lines.add(Line.styled("", lineStyle()));
        } else {
            addOptionalSection(lines, label, items);
        }
    }

    /** A section listed only when it has items. */
    /** What a branch of a {@code gui: true} template gets by default: {@code BranchFlow.defaultsFor}'s rule. */
    static String guiLabel(ImageDef def, String type, Map<String, ImageDef> defs) {
        if ("vm".equals(type)) return "off by default: VM branches get it only with --gui";
        if (ImageDef.chain(def, defs).stream().anyMatch(layer -> layer.getProjectRoot() != null)) {
            return "off by default: project-local (--gui or the branch dialog turns it on)";
        }
        return "on by default for branches made from a Wayland session";
    }

    private void addOptionalSection(List<Line> lines, String label, List<String> items) {
        if (items.isEmpty()) return;
        lines.add(Line.styled(label + ":", labelStyle()));
        for (var item : items) lines.add(Line.styled("  " + item, lineStyle()));
        lines.add(Line.styled("", lineStyle()));
    }

    // --- Tree view: each layer's own declarations ---

    List<Line> treeLines(String templateName, int width) {
        var chain = chain(templateName);
        if (chain.isEmpty()) return List.of();

        var lines = new ArrayList<Line>();
        var lineStyle = lineStyle();
        var labelStyle = labelStyle();
        var nameStyle = Style.EMPTY.bold().fg(modal.accent()).bg(modal.bg());
        var dimStyle = dimStyle();

        for (int i = 0; i < chain.size(); i++) {
            var def = chain.get(i);
            var indent = "  ".repeat(i);
            var connector = i == 0 ? "" : "└ ";
            var contentIndent = i == 0 ? "  " : "  ".repeat(i) + "  ";

            var nameSpans = new ArrayList<Span>();
            if (!indent.isEmpty() || !connector.isEmpty()) {
                nameSpans.add(Span.styled(indent + connector, dimStyle));
            }
            nameSpans.add(Span.styled(def.getName(), nameStyle));
            if (def.isRoot()) {
                nameSpans.add(Span.styled("  " + def.getImage(), dimStyle));
                if (def.getImageTag() != null) nameSpans.add(Span.styled("  tag " + def.getImageTag(), dimStyle));
                if (def.isPinned()) nameSpans.add(Span.styled("  (pinned)", labelStyle));
            }
            lines.add(Line.from(nameSpans));

            lines.add(Line.styled(contentIndent + def.getSource(), dimStyle));

            if (!def.getDescription().isEmpty()) {
                for (var text : wrap(def.getDescription(), width - contentIndent.length())) {
                    lines.add(Line.styled(contentIndent + text, lineStyle));
                }
            }

            if (def.isRoot() && def.getImageUrl() != null) {
                treeRow(lines, contentIndent, "Image: ",
                        BuildCommand.resolveImageUrl(def.getImageUrl(), def.getImageTag()), dimStyle);
            }
            if (def.isRoot() && def.getVmImageUrl() != null) {
                treeRow(lines, contentIndent, "VM image: ",
                        BuildCommand.resolveImageUrl(def.getVmImageUrl(), def.getImageTag()), dimStyle);
            }

            // Settings this layer declares itself; a child inheriting them shows nothing
            var ownType = declaredType(def);
            if (ownType != null) treeRow(lines, contentIndent, "Type: ", TemplateDetails.typeLabel(ownType), lineStyle);
            if (def.isGui()) treeRow(lines, contentIndent, "GUI: ", "asked for by default", lineStyle);
            if (def.getWorkdir() != null) treeRow(lines, contentIndent, "Workdir: ", def.getWorkdir(), lineStyle);
            if (def.getShellCommand() != null) {
                treeRow(lines, contentIndent, "Shell command: ", def.getShellCommand(), lineStyle);
            }
            if (def.getDefaultAction() != null) {
                treeRow(lines, contentIndent, "Default action: ", def.getDefaultAction(), lineStyle);
            }

            if (!def.getPackages().isEmpty()) {
                treeRow(lines, contentIndent, "Packages: ", String.join(", ", def.getPackages()), lineStyle);
            }
            if (!def.getRemovePackages().isEmpty()) {
                treeRow(lines, contentIndent, "Removes: ", String.join(", ", def.getRemovePackages()), lineStyle);
            }
            if (!def.getPackageRepos().isEmpty()) {
                var repos = def.getPackageRepos().stream().map(r -> r.getType() + ":" + r.getName()).toList();
                treeRow(lines, contentIndent, "Package repos: ", String.join(", ", repos), lineStyle);
            }
            if (!def.getMaskServices().isEmpty()) {
                treeRow(lines, contentIndent, "Masks: ", String.join(", ", def.getMaskServices()), lineStyle);
            }

            if (!def.getTools().isEmpty()) {
                var toolSpans = new ArrayList<Span>();
                toolSpans.add(Span.styled(contentIndent + "Tools: ", labelStyle));
                var toolNames = def.getTools().stream().map(ToolDef.ToolRef::getName).toList();
                var toolDisplay = def.getTools().stream().map(TemplateDetailView::formatToolWithParams).toList();
                toolSpans.add(Span.styled(String.join(", ", toolDisplay), lineStyle));
                var levelAutoDeps = source.autoDeps(toolNames);
                if (!levelAutoDeps.isEmpty()) {
                    toolSpans.add(Span.styled("  (+" + String.join(", ", levelAutoDeps) + ")", dimStyle));
                }
                lines.add(Line.from(toolSpans));
            }

            if (!def.getSkills().getList().isEmpty()) {
                var skillSpans = new ArrayList<Span>();
                skillSpans.add(Span.styled(contentIndent + "Skills: ", labelStyle));
                skillSpans.add(Span.styled(String.join(", ", def.getSkills().getList()), lineStyle));
                if (def.getSkills().getRepo() != null) {
                    skillSpans.add(Span.styled("  (catalog " + def.getSkills().getRepo() + ")", dimStyle));
                }
                lines.add(Line.from(skillSpans));
            }

            for (var repo : def.getRepos()) {
                lines.add(Line.from(List.of(
                        Span.styled(contentIndent + "Repo: ", labelStyle),
                        Span.styled(repo.getUrl() + " → " + repo.getPath(), lineStyle))));
                if (repo.hasPrime()) {
                    lines.add(Line.from(List.of(
                            Span.styled(contentIndent + "  prime: ", labelStyle),
                            Span.styled(repo.getPrime(), lineStyle))));
                }
                var hostMatch = source.hostRepoMatch(repo.getUrl());
                lines.add(Line.styled(contentIndent + (hostMatch != null
                        ? "  Linked to host repository at " + hostMatch
                        : "  No matching host checkout found"), dimStyle));
            }

            for (var hr : def.getHostResources()) {
                var containerPath = HostResourceSetup.resolveContainerPath(hr.getSource(), hr.getPath());
                lines.add(Line.from(List.of(
                        Span.styled(contentIndent + "Host: ", labelStyle),
                        Span.styled(hr.getSource() + " → " + containerPath, lineStyle),
                        Span.styled("  (" + hr.getMode() + ")", dimStyle))));
            }

            if (!def.getAccounts().isEmpty()) {
                var accounts = def.getAccounts().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "));
                treeRow(lines, contentIndent, "Accounts: ", accounts, lineStyle);
            }
            for (var entry : def.getEnv()) {
                treeRow(lines, contentIndent, "Env: ", formatEnv(entry), lineStyle);
            }
            if (def.getAgentNote() != null && !def.getAgentNote().isBlank()) {
                lines.add(Line.styled(contentIndent + "Agent note:", labelStyle));
                for (var noteLine : def.getAgentNote().strip().split("\n")) {
                    for (var text : wrap(noteLine, width - contentIndent.length() - 2)) {
                        lines.add(Line.styled(contentIndent + "  " + text, lineStyle));
                    }
                }
            }

            if (i < chain.size() - 1) lines.add(Line.styled("", lineStyle));
        }
        return lines;
    }

    private void treeRow(List<Line> lines, String indent, String label, String value, Style valueStyle) {
        lines.add(Line.from(List.of(Span.styled(indent + label, labelStyle()), Span.styled(value, valueStyle))));
    }

    /**
     * The {@code type} a layer's own file sets. Loading copies a parent's type into children that
     * set none, so a child counts as declaring it only when it differs from its parent's.
     */
    private String declaredType(ImageDef def) {
        if (def.getType() == null) return null;
        if (def.isRoot()) return def.getType();
        var defs = source.imageDefs();
        var parent = defs.get(def.getParent());
        var parentType = parent != null ? ImageDef.resolveType(parent, defs) : null;
        return def.getType().equals(parentType) ? null : def.getType();
    }

    // --- Formatting ---

    static String formatToolWithParams(ToolDef.ToolRef toolRef) {
        if (toolRef.getParams().isEmpty()) return toolRef.getName();
        var params = toolRef.getParams().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));
        return toolRef.getName() + " (" + params + ")";
    }

    /** One env entry as the shell would apply it, with secret-looking values masked. */
    static String formatEnv(EnvEntry entry) {
        var value = entry.getValue() == null ? "" : entry.getValue();
        if (SecretRedactor.looksSecret(entry.getName()) || SecretRedactor.hasSecretShape(value)) {
            value = "••••••";
        }
        return switch (entry.getStrategy()) {
            case SET -> entry.getName() + "=" + value;
            case SET_IF_UNSET -> entry.getName() + "=" + value + "  (if unset)";
            case PREPEND -> entry.getName() + "=" + value + entry.getSeparator() + "$" + entry.getName();
            case APPEND -> entry.getName() + "=$" + entry.getName() + entry.getSeparator() + value;
        };
    }

    /** Greedy word wrap; a word longer than {@code width} is left whole for the view to clip. */
    static List<String> wrap(String text, int width) {
        var out = new ArrayList<String>();
        if (width <= 0 || text.length() <= width) {
            out.add(text);
            return out;
        }
        var line = new StringBuilder();
        for (var word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
