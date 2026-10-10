package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.nio.file.Path;
import java.util.ArrayList;

import static dev.incusspawn.incus.Container.shellQuote;

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

    /**
     * Runs the project's pre-build, if it has one, as agentuser. Returns false, after reporting
     * it, when it failed.
     */
    static boolean preBuild(IncusClient incus, String name, String preBuild) {
        if (preBuild == null || preBuild.isBlank()) return true;
        BuildOutput.stepStart("Running pre-build: " + preBuild + "...");
        return GuestUpdate.finish(incus.execInContainer(name, "agentuser", preBuild), "pre-build");
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
            return CommandResult.valueOf(create(RuntimeServices.incus(), loadConfig()));
        }

        /**
         * Builds the template from {@code projectConfig} under a temporary name and, once that
         * succeeds, puts it in place of the one there was, as {@code isx build} does: a failed repo
         * clone or pre-build, or anything thrown on the way, fails the command, deletes the build and
         * leaves the previous template as it was (#1212). Returns the exit code.
         */
        int create(IncusClient incus, ProjectConfig projectConfig) {
            var imageName = name != null ? name : projectConfig.getName();

            if (imageName == null || imageName.isBlank()) {
                System.err.println("Error: project name is required (either as argument or in incus-spawn.yaml 'name' field).");
                return 1;
            }
            if (BuildCommand.reportNameTooLong(imageName)) return 1;

            var parent = projectConfig.getParent();
            boolean exists;
            boolean built;
            var buildName = imageName + BuildCommand.REBUILDING_SUFFIX;
            // Held from the lookup until the copy is made, so a rebuild of the parent cannot swap it
            // away meanwhile (#1212)
            try (var parentHeld = TemplateLock.reading(parent, BuildOutput::note)) {
                if (!incus.exists(parent)) {
                    System.err.println("Error: parent image '" + parent + "' does not exist. Run 'incus-spawn build " + parent + "' first.");
                    return 1;
                }

                BuildOutput.header("Creating project template " + imageName);
                BuildOutput.note("Parent: " + parent);

                exists = incus.exists(imageName);
                if (exists) {
                    BuildOutput.note("'" + imageName + "' already exists. It will be replaced if the create succeeds.");
                }

                if (BuildCommand.reportStrandedStorage(incus, imageName, buildName)) return 1;
                // What a create that died before its swap left behind
                incus.deleteIfExists(buildName);
                try {
                    var machineType = incus.machineType(parent);
                    BuildOutput.stepStart("Cloning from " + parent + "...");
                    incus.copy(parent, buildName);
                    // Copied: another isx's rebuild of the parent may go ahead
                    parentHeld.close();
                    // The parent's own settings are the project template's, but not its Secure Boot
                    if (machineType == MachineType.VM) InstanceLifecycle.disableSecureBoot(incus, buildName);
                    built = build(incus, buildName, imageName, parent, machineType, projectConfig);
                } catch (RuntimeException e) {
                    try {
                        incus.deleteIfExists(buildName);
                    } catch (RuntimeException deleteFailed) {
                        e.addSuppressed(deleteFailed);
                    }
                    throw e;
                }
            }
            if (!built) {
                BuildOutput.stepStart("Deleting the incomplete template...");
                incus.delete(buildName, true);
                BuildOutput.stepDone();
                System.err.println("Error: project template " + imageName + " was not "
                        + (exists ? "replaced; the previous one is kept." : "created.")
                        + " Fix the failure above and run 'isx project create' again.");
                return 1;
            }
            // Not deleted if this fails: by then it may be the only build there is
            TemplateLock.replace(incus, buildName, imageName, BuildOutput::note);
            BuildOutput.success("Project template " + imageName + " created.");
            return 0;
        }

        /**
         * Starts and builds {@code buildName}, just copied from {@code parent}, into the template
         * {@code imageName}, and stops it; false, after the failed step reported why, when one failed.
         */
        private static boolean build(IncusClient incus, String buildName, String imageName, String parent,
                                     MachineType machineType, ProjectConfig projectConfig) {
            incus.start(buildName);
            incus.waitForReady(buildName, machineType);
            BuildOutput.stepDone();

            // Clone repos
            if (projectConfig.getRepos() != null) {
                for (var repo : projectConfig.getRepos()) {
                    BuildOutput.stepStart("Cloning " + repo + "...");
                    var cloned = incus.execInContainer(buildName, "agentuser", "git clone " + shellQuote(repo));
                    if (!GuestUpdate.finish(cloned, "git clone " + repo)) return false;
                }
            }

            if (!preBuild(incus, buildName, projectConfig.getPreBuild())) return false;

            InstanceLifecycle.tagMetadata(incus, buildName, Metadata.TYPE_PROJECT, parent);
            incus.configSet(buildName, Metadata.PROJECT, imageName);

            // Stop the template
            BuildOutput.stepStart("Stopping template...");
            incus.stop(buildName);
            BuildOutput.stepDone();
            return true;
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
            var projectConfig = configPath != null ? ProjectConfig.load(configPath) : ProjectConfig.findInDirectory(Path.of("."));
            return CommandResult.valueOf(update(RuntimeServices.incus(), projectConfig));
        }

        /**
         * Updates the template, re-running {@code projectConfig}'s pre-build when there is one,
         * and returns the exit code. A config found in the working directory rather than named
         * with --config must name this template, or it may be another project's pre-build.
         */
        int update(IncusClient incus, ProjectConfig projectConfig) {
            if (!incus.exists(name)) {
                System.err.println("Error: image '" + name + "' does not exist.");
                return 1;
            }
            if (configPath == null && projectConfig != null && projectConfig.getName() != null && !projectConfig.getName().equals(name)) {
                System.err.println("Error: the incus-spawn.yaml found here is for project '" + projectConfig.getName()
                        + "', not '" + name + "'. Use --config to name the one for '" + name + "'.");
                return 1;
            }

            BuildOutput.header("Updating project template " + name);

            // Start if stopped
            var machineType = incus.machineType(name);
            incus.start(name);
            incus.waitForReady(name, machineType);

            var failedSteps = new ArrayList<String>();
            if (!GuestUpdate.system(incus, name)) failedSteps.add("system update");
            // Update globally installed npm packages (coding tools, etc.)
            if (!NpmUpdate.run(incus, name)) failedSteps.add("npm update");
            // Git fetch in all repos
            if (!GuestUpdate.gitRepos(incus, name)) failedSteps.add("git fetch");

            // Re-run pre-build if config available
            if (projectConfig != null && !preBuild(incus, name, projectConfig.getPreBuild())) {
                failedSteps.add("pre-build");
            }

            // Stop
            BuildOutput.stepStart("Stopping template...");
            incus.stop(name);
            BuildOutput.stepDone();

            if (!failedSteps.isEmpty()) {
                BuildOutput.warn("Failed: " + String.join(", ", failedSteps) + ". Re-run 'isx project update " + name + "' to retry.");
                return 1;
            }
            BuildOutput.success("Project template " + name + " updated.");
            return 0;
        }

    }
}
