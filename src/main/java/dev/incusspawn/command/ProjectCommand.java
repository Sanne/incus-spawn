package dev.incusspawn.command;

import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(
        name = "project",
        description = "Manage project golden images",
        mixinStandardHelpOptions = true,
        subcommands = {
                ProjectCommand.Create.class,
                ProjectCommand.Update.class
        }
)
public class ProjectCommand {

    @Command(
            name = "create",
            description = "Create a project golden image from a parent base image",
            mixinStandardHelpOptions = true
    )
    public static class Create implements Runnable {

        @Parameters(index = "0", description = "Name of the project golden image")
        String name;

        @Option(names = "--config", description = "Path to incus-spawn.yaml (default: auto-detect from cwd)")
        Path configPath;

        @Inject
        IncusClient incus;

        @Override
        public void run() {
            var projectConfig = loadConfig();
            var imageName = name != null ? name : projectConfig.getName();

            if (imageName == null || imageName.isBlank()) {
                System.err.println("Error: project name is required (either as argument or in incus-spawn.yaml 'name' field).");
                return;
            }

            var parent = projectConfig.getParent();
            System.out.println("Creating project golden image: " + imageName + " (parent: " + parent + ")");

            if (!incus.exists(parent)) {
                System.err.println("Error: parent image '" + parent + "' does not exist. Run 'incus-spawn build " + parent + "' first.");
                return;
            }

            if (incus.exists(imageName)) {
                System.out.println("Image " + imageName + " already exists. Deleting and rebuilding...");
                incus.delete(imageName, true);
            }

            // Clone from parent
            System.out.println("Cloning from " + parent + "...");
            incus.copy(parent, imageName);
            incus.start(imageName);
            waitForReady(imageName);

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

            // Tag with metadata
            incus.configSet(imageName, Metadata.TYPE, Metadata.TYPE_PROJECT);
            incus.configSet(imageName, Metadata.PARENT, parent);
            incus.configSet(imageName, Metadata.PROJECT, imageName);
            incus.configSet(imageName, Metadata.CREATED, Metadata.today());

            // Stop the golden image
            System.out.println("Stopping project golden image...");
            incus.stop(imageName);

            System.out.println("Project golden image " + imageName + " created successfully.");
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

        private void waitForReady(String container) {
            for (int i = 0; i < 30; i++) {
                var result = incus.shellExec(container, "true");
                if (result.success()) return;
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        }
    }

    @Command(
            name = "update",
            description = "Update a project golden image (system packages, git repos, dependencies)",
            mixinStandardHelpOptions = true
    )
    public static class Update implements Runnable {

        @Parameters(index = "0", description = "Name of the project golden image to update")
        String name;

        @Option(names = "--config", description = "Path to incus-spawn.yaml")
        Path configPath;

        @Inject
        IncusClient incus;

        @Override
        public void run() {
            if (!incus.exists(name)) {
                System.err.println("Error: image '" + name + "' does not exist.");
                return;
            }

            System.out.println("Updating project golden image: " + name);

            // Start if stopped
            incus.start(name);
            waitForReady(name);

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
            System.out.println("Stopping golden image...");
            incus.stop(name);

            System.out.println("Project golden image " + name + " updated successfully.");
        }

        private void waitForReady(String container) {
            for (int i = 0; i < 30; i++) {
                var r = incus.shellExec(container, "true");
                if (r.success()) return;
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        }
    }
}
