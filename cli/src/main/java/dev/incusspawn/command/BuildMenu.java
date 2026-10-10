package dev.incusspawn.command;

import dev.incusspawn.command.InstanceListing.TemplateInfo;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * The TUI's build menu (F5 on a template): what can be built from there, each with what it
 * does and the {@code isx build} arguments it runs. It owns its options, their keys and its
 * rendering; the {@link Tui} starts the chosen build once the terminal is released.
 */
final class BuildMenu {

    /** What a key did: nothing the TUI must act on, nothing at all, close the menu, or build {@link #selected}. */
    enum Outcome { HANDLED, IGNORED, CLOSE, BUILD }

    private final ModalRenderer modal;
    private final TuiTheme theme;

    /** One line of the menu: the {@code isx build} arguments it runs, and whether it can be chosen. */
    record BuildMenuOption(String label, String description, String badge, String[] buildArgs, boolean enabled) {}

    private final java.util.List<BuildMenuOption> buildMenuOptions;

    private int buildMenuSelectedIndex;

    /** The options for {@code template}, from the TUI's template definitions, rows, and out-of-sync set. */
    BuildMenu(ModalRenderer modal, TuiTheme theme, TemplateInfo template,
              Map<String, dev.incusspawn.config.ImageDef> imageDefs, java.util.List<TemplateInfo> templateEntries,
              Set<String> templatesOutOfSync) {
        this.modal = modal;
        this.theme = theme;
        var options = new java.util.ArrayList<BuildMenuOption>();
        var def = imageDefs.get(template.name());
        boolean isBuilt = !"not built".equals(template.buildStatus());

        // Option 1: Build/Rebuild single template
        if (isBuilt) {
            options.add(new BuildMenuOption(
                    "Rebuild " + template.name(),
                    "Deletes and rebuilds this template",
                    null, new String[]{template.name()}, true));
        } else {
            options.add(new BuildMenuOption(
                    "Build " + template.name(),
                    "Builds this template for the first time",
                    null, new String[]{template.name()}, true));
        }

        // Option 2: Rebuild with parents (only for non-root templates)
        if (def != null && !def.isRoot()) {
            var chain = new java.util.ArrayList<String>();
            BuildCommand.collectAllRecursive(def, imageDefs, chain, new java.util.LinkedHashSet<>());
            var chainStr = String.join(" → ", chain);
            options.add(new BuildMenuOption(
                    "Rebuild " + template.name() + " with parents",
                    "Rebuilds " + chainStr,
                    null, new String[]{template.name(), "--with-parents"}, true));
        }

        // Option: Rebuild with descendants (only when template has descendants)
        if (def != null) {
            var descChain = new java.util.ArrayList<String>();
            var descSeen = new java.util.LinkedHashSet<String>();
            BuildCommand.collectDescendants(template.name(), imageDefs, descChain, descSeen);
            if (!descChain.isEmpty()) {
                var fullChain = new java.util.ArrayList<String>();
                fullChain.add(template.name());
                fullChain.addAll(descChain);
                var chainStr = String.join(" → ", fullChain);
                options.add(new BuildMenuOption(
                        "Rebuild " + template.name() + " with descendants",
                        "Rebuilds " + chainStr,
                        null, new String[]{template.name(), "--with-descendants"}, true));
            }
        }

        // Option 3: Build missing templates (only if there are missing ones)
        long missingCount = templateEntries.stream()
                .filter(t -> "not built".equals(t.buildStatus())).count();
        if (missingCount > 0) {
            options.add(new BuildMenuOption(
                    "Build templates not yet built",
                    "Builds all templates that haven't been built yet",
                    missingCount + (missingCount == 1 ? " template" : " templates"),
                    new String[]{"--missing"}, true));
        }

        // Option 4: Rebuild out of sync templates (uses cached data from buildTemplateRowData)
        if (templatesOutOfSync.isEmpty()) {
            options.add(new BuildMenuOption(
                    "Rebuild out of sync templates",
                    "All templates match their current definitions",
                    "all in sync", new String[]{"--out-of-sync"}, false));
        } else {
            options.add(new BuildMenuOption(
                    "Rebuild out of sync templates",
                    "Rebuilds all templates whose definition changed\n"
                            + "since last build, built with an older version,\n"
                            + "or whose parent was rebuilt after them",
                    templatesOutOfSync.size() + (templatesOutOfSync.size() == 1 ? " template" : " templates"),
                    new String[]{"--out-of-sync"}, true));
        }

        // Option 5: (Re)build all templates
        options.add(new BuildMenuOption(
                "(Re)build all templates",
                "Deletes and rebuilds every template",
                null, new String[]{"--all"}, true));

        buildMenuOptions = options;
        buildMenuSelectedIndex = 0;
    }

    /** The option the menu points at: the one to build when a key returns BUILD. */
    BuildMenuOption selected() {
        return buildMenuOptions.get(buildMenuSelectedIndex);
    }

    /** A key in the menu; Enter or an option's number on an enabled option hands the build back to the TUI. */
    Outcome handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCtrlC()) {
            return Outcome.CLOSE;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            for (int i = buildMenuSelectedIndex + 1; i < buildMenuOptions.size(); i++) {
                if (buildMenuOptions.get(i).enabled()) {
                    buildMenuSelectedIndex = i;
                    break;
                }
            }
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            for (int i = buildMenuSelectedIndex - 1; i >= 0; i--) {
                if (buildMenuOptions.get(i).enabled()) {
                    buildMenuSelectedIndex = i;
                    break;
                }
            }
            return Outcome.HANDLED;
        }
        if (key.isKey(KeyCode.ENTER)) {
            var option = buildMenuOptions.get(buildMenuSelectedIndex);
            if (!option.enabled()) return Outcome.HANDLED;
            return Outcome.BUILD;
        }
        if (key.code() == KeyCode.CHAR && key.character() >= '1' && key.character() <= '9') {
            int index = key.character() - '1';
            if (index < buildMenuOptions.size()) {
                var option = buildMenuOptions.get(index);
                if (!option.enabled()) return Outcome.HANDLED;
                buildMenuSelectedIndex = index;
                return Outcome.BUILD;
            }
            return Outcome.HANDLED;
        }
        return Outcome.IGNORED;
    }

    void renderBuildMenu(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        if (buildMenuOptions == null || buildMenuOptions.isEmpty()) return;

        var lines = new ArrayList<Line>();
        for (int i = 0; i < buildMenuOptions.size(); i++) {
            var opt = buildMenuOptions.get(i);
            var selected = (i == buildMenuSelectedIndex);
            var prefix = selected ? " ▶ " : "   ";
            var numPrefix = "[" + (i + 1) + "] ";

            var labelStyle = !opt.enabled()
                    ? Style.EMPTY.fg(theme.textDim()).bg(modal.bg())
                    : selected
                        ? Style.EMPTY.bold().fg(theme.focusedLabel()).bg(modal.bg())
                        : Style.EMPTY.fg(modal.fg()).bg(modal.bg());
            var spans = new ArrayList<Span>();
            spans.add(Span.styled(prefix + numPrefix + opt.label(), labelStyle));
            if (opt.badge() != null) {
                var badgeStyle = Style.EMPTY.fg(opt.enabled() ? modal.accent() : theme.textDim()).bg(modal.bg());
                spans.add(Span.styled("  " + opt.badge(), badgeStyle));
            }
            lines.add(Line.from(spans));

            // Description lines (may be multi-line via \n)
            var descStyle = Style.EMPTY.fg(theme.textDim()).bg(modal.bg());
            for (var descLine : opt.description().split("\n")) {
                lines.add(Line.styled("       " + descLine, descStyle));
            }

            // Blank separator between options
            if (i < buildMenuOptions.size() - 1) {
                lines.add(Line.styled("", Style.EMPTY.bg(modal.bg())));
            }
        }

        int modalWidth = Math.min(60, screen.width() - 4);
        int modalHeight = Math.min(lines.size() + 4, screen.height() - 2);

        var modalArea = ModalRenderer.centerRect(screen, modalWidth, modalHeight);
        var block = dev.tamboui.widgets.block.Block.builder()
                .borders(dev.tamboui.widgets.block.Borders.ALL)
                .borderType(dev.tamboui.widgets.block.BorderType.DOUBLE)
                .title(modal.styledTitle(" Build Templates ", modal.border()))
                .borderStyle(Style.EMPTY.fg(modal.border()))
                .style(Style.EMPTY.bg(modal.bg()))
                .padding(dev.tamboui.layout.Padding.horizontal(1))
                .build();
        modal.renderBlock(frame, block, modalArea);
        var inner = block.inner(modalArea);

        var rows = dev.tamboui.layout.Layout.vertical()
                .constraints(dev.tamboui.layout.Constraint.length(1),
                        dev.tamboui.layout.Constraint.fill(),
                        dev.tamboui.layout.Constraint.length(1))
                .split(inner);

        // Top spacing
        frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from(
                Line.styled("", Style.EMPTY.bg(modal.bg()))), rows.get(0));

        modal.renderScrollableContent(frame, rows.get(1), lines, 0);

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "1-" + buildMenuOptions.size(), "Select");
        modal.addKey(hintSpans, "Enter", "Build");
        modal.addKey(hintSpans, "Esc", "Cancel");
        frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from(Line.from(hintSpans)), rows.get(2));
    }
}
