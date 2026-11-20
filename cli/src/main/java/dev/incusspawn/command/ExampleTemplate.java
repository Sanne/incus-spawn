package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.tool.ToolSetup;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The first template isx writes for a person's preferred agent (#2409): {@code tpl-agent}, on
 * {@code tpl-minimal}, with the agent in {@code tools:} and as its {@code default-action}.
 * The file is the person's from the moment it is written: isx never writes over it, and changing
 * the preference later changes no template.
 */
final class ExampleTemplate {

    private ExampleTemplate() {}

    static final String NAME = "tpl-agent";

    /** Where it is written: the user's own templates. */
    static Path file() {
        return ImageDef.userImagesDir().resolve(NAME + ".yaml");
    }

    /**
     * The text of {@code tpl-agent.yaml} for {@code agent}, a coding agent's tool. Its
     * {@code default-action} is the agent's own action, left out for an agent that has none.
     */
    static String text(ToolSetup agent) {
        var name = agent.name();
        var title = title(agent);
        var action = agent.actions().isEmpty() ? "" : "default-action: " + name + "\n";
        return """
                # yaml-language-server: $schema=https://isx.run/schema/template.json
                # Your first agent template, written by isx for %2$s.
                # It is yours: isx never changes it. Build it with 'isx build %3$s'.
                name: %3$s
                description: %2$s on a minimal Fedora machine
                parent: tpl-minimal
                tools:
                  - %1$s
                %4$s
                # Packages to install with dnf (https://isx.run/docs/templates#image-schema-fields):
                # packages:
                #   - ripgrep

                # Repositories to clone into each machine (https://isx.run/docs/templates#declarative-repos):
                # repos:
                #   - url: https://github.com/you/project.git
                #     path: ~/project
                # workdir: ~/project

                # A first template needs no other field. 'isx templates new' writes every one,
                # with a note each (https://isx.run/docs/templates#image-schema-fields).
                """.formatted(name, title, NAME, action);
    }

    /**
     * The agent's name as a person knows it, {@code Claude Code}: the probe's name for it, the same
     * in every isx version, or for an agent the probe does not know, its description up to the dash.
     * The description is not taken first, since one carries the pinned version (Antigravity's).
     */
    static String title(ToolSetup agent) {
        var known = HostAgentProbe.title(agent.name());
        if (!known.equals(agent.name())) return known;
        var description = agent.description();
        if (description.isBlank()) return agent.name();
        var dash = description.indexOf(" — ");
        return dash < 0 ? description : description.substring(0, dash);
    }

    /**
     * Writes {@link #text} for {@code agent} to {@code file}, never over a file already there.
     *
     * @return whether it was written: false when {@code file} exists
     */
    static boolean write(Path file, ToolSetup agent) throws IOException {
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, text(agent), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }
}
