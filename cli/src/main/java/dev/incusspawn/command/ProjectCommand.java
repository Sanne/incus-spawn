package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.nio.file.Path;

@CommandDefinition(
        name = "project",
        description = "Manage project templates",
        generateHelp = true,
        groupCommands = {
                ProjectCommand.Create.class,
                ProjectCommand.Update.class
        }
)
public class ProjectCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        System.out.println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    @CommandDefinition(
            name = "create",
            description = "Create a project template from a parent base image",
            generateHelp = true
    )
    public static class Create extends BaseCommand {

        @Argument(required = true, description = "Name of the project template")
        String name;

        @Option(name = "config", description = "Path to incus-spawn.yaml (default: auto-detect from cwd)")
        Path configPath;

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            var projectConfig = loadConfig();
            var imageName = name != null ? name : projectConfig.getName();

            if (imageName == null || imageName.isBlank()) {
                System.err.println("Error: project name is required (either as argument or in incus-spawn.yaml 'name' field).");
                return CommandResult.valueOf(1);
            }

            var parent = projectConfig.getParent();
            System.out.println("Creating project template: " + imageName + " (parent: " + parent + ")");

            if (!incus.exists(parent)) {
                System.err.println("Error: parent image '" + parent + "' does not exist. Run 'incus-spawn build " + parent + "' first.");
                return CommandResult.valueOf(1);
            }

            if (incus.exists(imageName)) {
                System.out.println("Image " + imageName + " already exists. Deleting and rebuilding...");
                incus.delete(imageName, true);
            }

            // Clone from parent
            System.out.println("Cloning from " + parent + "...");
            incus.copy(parent, imageName);
            incus.start(imageName);
            incus.waitForReady(imageName);

            // Clone repos
            if (projectConfig.getRepos() != null && !projectConfig.getRepos().isEmpty()) {
                System.out.println("Cloning git repositories...");
                for (var repo : projectConfig.getRepos()) {
                    System.out.println("  Cloning " + repo + "...");
                    incus.execInContainer(imageName, "agentuser", "git", "clone", repo);
                }
            }

            // Run pre-build
            if (projectConfig.getPreBuild() != null && !projectConfig.getPreBuild().isBlank()) {
                System.out.println("Running pre-build: " + projectConfig.getPreBuild());
                var result = incus.execInContainer(imageName, "agentuser", "sh", "-c", projectConfig.getPreBuild());
                if (!result.success()) {
                    System.err.println("Warning: pre-build command failed: " + result.stderr().strip());
                }
            }

            InstanceLifecycle.tagMetadata(incus, imageName, Metadata.TYPE_PROJECT, parent);
            incus.configSet(imageName, Metadata.PROJECT, imageName);

            // Stop the template
            System.out.println("Stopping project template...");
            incus.stop(imageName);

            System.out.println("Project template " + imageName + " created successfully.");
            return CommandResult.SUCCESS;
        }

        private ProjectConfig loadConfig() {
            if (configPath != null) {
                return ProjectConfig.load(configPath);
            }
            var found = ProjectConfig.findInDirectory(Path.of("."));
            if (found != null) {
                return found;
            }
            System.err.println("Error: no incus-spawn.yaml found. Use --config to specify one.");
            System.exit(1);
            return null;
        }

    }

    @CommandDefinition(
            name = "update",
            description = "Update a project template (system packages, git repos, dependencies)",
            generateHelp = true
    )
    public static class Update extends BaseCommand {

        @Argument(required = true, description = "Name of the project template to update")
        String name;

        @Option(name = "config", description = "Path to incus-spawn.yaml")
        Path configPath;

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            if (!incus.exists(name)) {
                System.err.println("Error: image '" + name + "' does not exist.");
                return CommandResult.valueOf(1);
            }

            System.out.println("Updating project template: " + name);

            // Start if stopped
            incus.start(name);
            incus.waitForReady(name);

            // System updates
            System.out.println("Running system updates...");
            incus.shellExec(name, "dnf", "update", "-y");

            // Update Claude Code
            System.out.println("Updating Claude Code...");
            incus.shellExec(name, "npm", "update", "-g", "@anthropic-ai/claude-code");

            // Git fetch in all repos
            System.out.println("Updating git repositories...");
            var result = incus.execInContainer(name, "agentuser",
                    "sh", "-c", "for d in ~/*/; do if [ -d \"$d/.git\" ]; then echo \"Fetching $d\" && cd \"$d\" && git fetch --all && cd ~; fi; done");
            if (result.success() && !result.stdout().isBlank()) {
                System.out.println(result.stdout());
            }

            // Re-run pre-build if config available
            ProjectConfig projectConfig = null;
            if (configPath != null) {
                projectConfig = ProjectConfig.load(configPath);
            } else {
                projectConfig = ProjectConfig.findInDirectory(Path.of("."));
            }
            if (projectConfig != null && projectConfig.getPreBuild() != null && !projectConfig.getPreBuild().isBlank()) {
                System.out.println("Running pre-build: " + projectConfig.getPreBuild());
                incus.execInContainer(name, "agentuser", "sh", "-c", projectConfig.getPreBuild());
            }

            // Stop
            System.out.println("Stopping template...");
            incus.stop(name);

            System.out.println("Project template " + name + " updated successfully.");
            return CommandResult.SUCCESS;
        }

    }
}
