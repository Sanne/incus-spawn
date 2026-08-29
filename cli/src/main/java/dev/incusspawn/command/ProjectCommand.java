package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.util.BuildOutput;
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

            if (!incus.exists(parent)) {
                System.err.println("Error: parent image '" + parent + "' does not exist. Run 'incus-spawn build " + parent + "' first.");
                return CommandResult.valueOf(1);
            }

            BuildOutput.header("Creating project template " + imageName);
            BuildOutput.note("Parent: " + parent);

            if (incus.exists(imageName)) {
                BuildOutput.note("'" + imageName + "' already exists — deleting and rebuilding.");
                incus.delete(imageName, true);
            }

            // Clone from parent
            BuildOutput.stepStart("Cloning from " + parent + "...");
            incus.copy(parent, imageName);
            incus.start(imageName);
            incus.waitForReady(imageName);
            BuildOutput.stepDone();

            // Clone repos
            if (projectConfig.getRepos() != null && !projectConfig.getRepos().isEmpty()) {
                for (var repo : projectConfig.getRepos()) {
                    BuildOutput.stepStart("Cloning " + repo + "...");
                    incus.execInContainer(imageName, "agentuser", "git", "clone", repo);
                    BuildOutput.stepDone();
                }
            }

            // Run pre-build
            if (projectConfig.getPreBuild() != null && !projectConfig.getPreBuild().isBlank()) {
                BuildOutput.stepStart("Running pre-build: " + projectConfig.getPreBuild() + "...");
                var result = incus.execInContainer(imageName, "agentuser", "sh", "-c", projectConfig.getPreBuild());
                if (!result.success()) {
                    BuildOutput.stepFail("Warning: pre-build command failed: " + result.stderr().strip());
                } else {
                    BuildOutput.stepDone();
                }
            }

            InstanceLifecycle.tagMetadata(incus, imageName, Metadata.TYPE_PROJECT, parent);
            incus.configSet(imageName, Metadata.PROJECT, imageName);

            // Stop the template
            BuildOutput.stepStart("Stopping template...");
            incus.stop(imageName);
            BuildOutput.stepDone();

            BuildOutput.success("Project template " + imageName + " created.");
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

            BuildOutput.header("Updating project template " + name);

            // Start if stopped
            incus.start(name);
            incus.waitForReady(name);

            // System updates
            BuildOutput.stepStart("Running system updates...");
            incus.shellExec(name, "dnf", "update", "-y");
            BuildOutput.stepDone();

            // Update globally installed npm packages (coding tools, etc.)
            if (incus.shellExec(name, "which", "npm").success()) {
                BuildOutput.stepStart("Updating npm packages...");
                incus.shellExec(name, "npm", "update", "-g");
                BuildOutput.stepDone();
            }

            // Git fetch in all repos
            BuildOutput.stepStart("Updating git repositories...");
            incus.execInContainer(name, "agentuser",
                    "sh", "-c", "for d in ~/*/; do if [ -d \"$d/.git\" ]; then echo \"Fetching $d\" && cd \"$d\" && git fetch --all && cd ~; fi; done");
            BuildOutput.stepDone();

            // Re-run pre-build if config available
            ProjectConfig projectConfig = null;
            if (configPath != null) {
                projectConfig = ProjectConfig.load(configPath);
            } else {
                projectConfig = ProjectConfig.findInDirectory(Path.of("."));
            }
            if (projectConfig != null && projectConfig.getPreBuild() != null && !projectConfig.getPreBuild().isBlank()) {
                BuildOutput.stepStart("Running pre-build: " + projectConfig.getPreBuild() + "...");
                incus.execInContainer(name, "agentuser", "sh", "-c", projectConfig.getPreBuild());
                BuildOutput.stepDone();
            }

            // Stop
            BuildOutput.stepStart("Stopping template...");
            incus.stop(name);
            BuildOutput.stepDone();

            BuildOutput.success("Project template " + name + " updated.");
            return CommandResult.SUCCESS;
        }

    }
}
