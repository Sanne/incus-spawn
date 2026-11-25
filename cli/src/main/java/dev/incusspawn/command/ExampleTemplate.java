package dev.incusspawn.command;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.GhSetup;
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
        return text(agent, false);
    }

    /**
     * As {@link #text(ToolSetup)}, with the {@code gh} tool after the agent when {@code withGitHub}
     * (#2535): it installs gh signed in to the account, injects the account's token through the proxy
     * and sets the account's Git name and email.
     */
    static String text(ToolSetup agent, boolean withGitHub) {
        return text(agent, withGitHub, java.util.Map.of());
    }

    /**
     * As {@link #text(ToolSetup, boolean)}, with the agent's {@code params}, the first run's answer
     * to its {@link ToolSetup#firstRunQuestion()} (#2536): {@code - pi: {provider: openai}}.
     * A value is one the parameter's pattern accepted, a plain YAML word.
     */
    static String text(ToolSetup agent, boolean withGitHub, java.util.Map<String, String> params) {
        var name = agent.name();
        var listed = params.isEmpty() ? name : name + ": {" + String.join(", ",
                params.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList()) + "}";
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
                %5$s%4$s
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
                """.formatted(listed, title, NAME, action, withGitHub ? "  - gh\n" : "");
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

    /** Whether {@code config} holds a GitHub account a template's {@code gh} tool can use. */
    static boolean hasGitHubAccount(SpawnConfig config) {
        return !AccountResolver.usableAccountNames(config, GhSetup.NAMESPACE).isEmpty();
    }

    /**
     * Writes {@link #text(ToolSetup, boolean)} for {@code agent} to {@code file}, never over a file already there.
     *
     * @param withGitHub whether to list the {@code gh} tool after the agent, for a saved GitHub account
     * @return whether it was written: false when {@code file} exists
     */
    static boolean write(Path file, ToolSetup agent, boolean withGitHub) throws IOException {
        return write(file, agent, withGitHub, java.util.Map.of());
    }

    /** As {@link #write(Path, ToolSetup, boolean)}, with the agent's {@code params}: {@link #text(ToolSetup, boolean, java.util.Map)}. */
    static boolean write(Path file, ToolSetup agent, boolean withGitHub, java.util.Map<String, String> params) throws IOException {
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, text(agent, withGitHub, params), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }
}
