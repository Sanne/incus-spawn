package dev.incusspawn.tui;

import dev.incusspawn.command.BuildCommand;
import dev.incusspawn.command.InstanceListing.TemplateInfo;
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
 * does and the {@code isx build} arguments it runs. With templates marked (Space, #1131) it
 * first offers to build them together. It owns its options, their keys and its rendering; the
 * {@link Tui} starts the chosen build once the terminal is released.
 */
final class BuildMenu {

    /** What a key did: nothing the TUI must act on, nothing at all, close the menu, or build {@link #selected}. */
    enum Outcome { HANDLED, IGNORED, CLOSE, BUILD }

    private final ModalRenderer modal;
    private final TuiTheme theme;

    /**
     * One line of the menu: the {@code isx build} arguments it runs, whether it can be chosen, the
     * templates it rebuilds that the TUI checks are not busy (besides, for an option not about
     * the marks, the one under the cursor; the flag-only builds pick their own), and whether it
     * builds the marked templates (which the TUI then unmarks).
     */
    record BuildMenuOption(String label, String description, String badge, String[] buildArgs, boolean enabled,
                           java.util.List<String> rebuilds, boolean marked) {
        BuildMenuOption(String label, String description, String badge, String[] buildArgs, boolean enabled) {
            this(label, description, badge, buildArgs, enabled, java.util.List.of(), false);
        }
    }

    private final java.util.List<BuildMenuOption> buildMenuOptions;

    private int buildMenuSelectedIndex;

    /**
     * The options for {@code template}, from the TUI's template definitions, rows, and out-of-sync set,
     * led by those for the {@code marked} templates when there are any.
     */
    BuildMenu(ModalRenderer modal, TuiTheme theme, TemplateInfo template,
              Map<String, dev.incusspawn.config.ImageDef> imageDefs, java.util.List<TemplateInfo> templateEntries,
              Set<String> templatesOutOfSync, java.util.List<String> marked) {
        this(modal, theme, template, imageDefs, templateEntries, templatesOutOfSync, marked, Map.of());
    }

    /**
     * As above, where {@code missing} are the built templates with no loaded definition: whose
     * definitions in {@code imageDefs} come from their build stamps, and which {@code isx build}
     * refuses (#2057), so no option that builds one can be chosen.
     */
    BuildMenu(ModalRenderer modal, TuiTheme theme, TemplateInfo template,
              Map<String, dev.incusspawn.config.ImageDef> imageDefs, java.util.List<TemplateInfo> templateEntries,
              Set<String> templatesOutOfSync, java.util.List<String> marked,
              Map<String, dev.incusspawn.command.MissingDefinition> missing) {
        this.modal = modal;
        this.theme = theme;
        var options = new java.util.ArrayList<BuildMenuOption>();
        // isx build follows no parent or child that has no definition, so neither do the chains shown
        if (!missing.isEmpty()) {
            var loaded = new java.util.HashMap<>(imageDefs);
            loaded.keySet().removeAll(missing.keySet());
            imageDefs = loaded;
        }
        addMarkedOptions(options, marked, imageDefs, missing);
        var noDefinition = missing.get(template.name());
        var def = noDefinition != null ? null : imageDefs.get(template.name());
        boolean isBuilt = !"not built".equals(template.buildStatus());

        // Option 1: Build/Rebuild single template
        if (noDefinition != null) {
            options.add(new BuildMenuOption(
                    "Rebuild " + template.name(),
                    "No definition here: " + noDefinition.reason() + ".\n" + noDefinition.advice(),
                    "no definition", new String[]{template.name()}, false));
        } else if (isBuilt) {
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
            var chain = BuildCommand.withParentsChain(java.util.List.of(def), imageDefs);
            options.add(new BuildMenuOption(
                    "Rebuild " + template.name() + " with parents",
                    "Rebuilds " + String.join(" → ", chain),
                    null, new String[]{template.name(), "--with-parents"}, true, chain, false));
        }

        // Option: Rebuild with descendants (only when template has descendants)
        if (def != null) {
            var fullChain = BuildCommand.withDescendantsChain(java.util.List.of(def), imageDefs);
            if (fullChain.size() > 1) {
                options.add(new BuildMenuOption(
                        "Rebuild " + template.name() + " with descendants",
                        "Rebuilds " + String.join(" → ", fullChain),
                        null, new String[]{template.name(), "--with-descendants"}, true, fullChain, false));
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

    /**
     * The options for the marked templates: with their parents (when that adds any), with their
     * descendants (when any has some), and alone. The batches are computed as {@code isx build}
     * does, so the description names what the build will do, and the names are passed parents
     * first, since a plain {@code isx build a b} builds them in the order given. A marked template
     * without a definition gets only the last: {@code isx build} cannot follow its parents or
     * descendants.
     */
    private static void addMarkedOptions(java.util.List<BuildMenuOption> options, java.util.List<String> marked,
                                         Map<String, dev.incusspawn.config.ImageDef> imageDefs,
                                         Map<String, dev.incusspawn.command.MissingDefinition> missing) {
        if (marked.isEmpty()) return;
        var count = "Rebuild " + marked.size() + " selected";
        var undefined = marked.stream().filter(missing::containsKey).toList();
        if (!undefined.isEmpty()) {
            options.add(new BuildMenuOption(count, "No definition here for " + String.join(", ", undefined)
                    + ";\nisx build refuses it", "no definition", marked.toArray(String[]::new), false));
            return;
        }
        var defs = marked.stream().map(imageDefs::get).toList();
        var names = marked.toArray(String[]::new);
        if (!defs.contains(null)) {
            // The batches are unions of chains on separate branches, so they are listed, not arrowed
            var withParents = BuildCommand.withParentsChain(defs, imageDefs);
            names = withParents.stream().filter(marked::contains).toArray(String[]::new);
            if (withParents.size() > marked.size()) {
                options.add(new BuildMenuOption(count + " with parents",
                        "Rebuilds " + String.join(", ", withParents),
                        null, withFlag(names, "--with-parents"), true, withParents, true));
            }
            var withDescendants = BuildCommand.withDescendantsChain(defs, imageDefs);
            if (withDescendants.size() > marked.size()) {
                options.add(new BuildMenuOption(count + " with descendants",
                        "Rebuilds " + String.join(", ", withDescendants),
                        null, withFlag(names, "--with-descendants"), true, withDescendants, true));
            }
        }
        options.add(new BuildMenuOption(count, "Rebuilds " + String.join(", ", names),
                null, names, true, java.util.List.of(names), true));
    }

    private static String[] withFlag(String[] names, String flag) {
        var args = java.util.Arrays.copyOf(names, names.length + 1);
        args[names.length] = flag;
        return args;
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
        // The last line of the selected option, to scroll it into view when the menu is taller than the screen
        int selectedLastLine = 0;
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
            if (selected) selectedLastLine = lines.size() - 1;

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

        modal.renderScrollableContent(frame, rows.get(1), lines,
                Math.max(0, selectedLastLine - rows.get(1).height() + 1));

        var hintSpans = new ArrayList<Span>();
        modal.addKey(hintSpans, "1-" + buildMenuOptions.size(), "Select");
        modal.addKey(hintSpans, "Enter", "Build");
        modal.addKey(hintSpans, "Esc", "Cancel");
        frame.renderWidget(dev.tamboui.widgets.paragraph.Paragraph.from(Line.from(hintSpans)), rows.get(2));
    }
}
