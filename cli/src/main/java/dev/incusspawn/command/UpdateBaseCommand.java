package dev.incusspawn.command;

import dev.incusspawn.baseimage.BaseImageReleases;
import dev.incusspawn.baseimage.BaseImageReleases.Checksums;
import dev.incusspawn.baseimage.BaseImageReleases.Release;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@CommandDefinition(
        name = "update-base",
        description = "Check for and install base image updates",
        generateHelp = true
)
public class UpdateBaseCommand extends BaseCommand {

    private BaseImageReleases releases;

    @Argument(required = false, description = "Release tag to pin (e.g. fedora-44-v2)")
    String targetTag;

    @Option(name = "list", hasValue = false, description = "List available versions")
    boolean listOnly;

    @Option(name = "latest", hasValue = false, description = "Track the latest version (remove any pin)")
    boolean useLatest;

    @Override
    protected CommandResult doExecute() throws Exception {
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        if (minimal == null) {
            System.err.println("Template tpl-minimal not found.");
            return CommandResult.valueOf(1);
        }

        releases = BaseImageReleases.fromImageUrl(minimal.getImageUrl());
        if (releases == null) {
            System.err.println("Template tpl-minimal has no trackable GitHub base image URL.");
            return CommandResult.valueOf(1);
        }

        BuildOutput.header("Checking for base image updates");
        var currentTag = minimal.getImageTag();
        var isPinned = minimal.isPinned();
        BuildOutput.note("Current base image: " + (currentTag != null ? currentTag : "unknown")
                + (isPinned ? " [pinned]" : ""));

        BuildOutput.stepStart("Fetching available releases...");
        List<Release> available;
        try {
            available = releases.fetchReleases();
        } catch (IOException e) {
            BuildOutput.stepFail("Failed to fetch releases: " + e.getMessage());
            return CommandResult.valueOf(1);
        }
        BuildOutput.stepDone();

        if (available.isEmpty()) {
            System.out.println("No releases found.");
            return CommandResult.SUCCESS;
        }

        if (listOnly) {
            printReleaseList(available, currentTag);
            return CommandResult.SUCCESS;
        }

        // Explicit tag argument → pin to that version
        if (targetTag != null) {
            return pinToTag(targetTag, available, minimal);
        }

        // --latest flag → track latest, remove any pin
        if (useLatest) {
            return trackLatest(available.get(0).tag());
        }

        // Interactive mode
        return interactive(available, minimal, currentTag);
    }

    private CommandResult trackLatest(String latestTag) throws IOException {
        var overridePath = ImageDef.userImagesDir().resolve("minimal.yaml");
        if (Files.deleteIfExists(overridePath)) {
            BuildOutput.note("Removed version pin.");
        }
        BuildOutput.success("Now tracking the latest base image.");
        BuildOutput.note("The newest release (" + latestTag
                + ") will be resolved and installed on the next 'isx build tpl-minimal'.");
        return CommandResult.SUCCESS;
    }

    private CommandResult pinToTag(String tag, List<Release> available, ImageDef current) throws IOException {
        var selected = available.stream()
                .filter(r -> r.tag().equals(tag))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            System.err.println("Release '" + tag + "' not found.");
            printReleaseList(available, current.getImageTag());
            return CommandResult.valueOf(1);
        }

        BuildOutput.stepStart("Fetching checksums for " + selected.tag() + "...");
        var checksums = releases.fetchChecksums(selected);
        if (checksums == null || checksums.container().isEmpty()) {
            BuildOutput.stepFail("SHA256SUMS not found in release " + selected.tag() + ".");
            return CommandResult.valueOf(1);
        }
        BuildOutput.stepDone();

        writeUserOverride(current, selected.tag(), checksums);
        BuildOutput.success("Pinned base image to " + selected.tag() + ".");
        BuildOutput.note("The new base image will be downloaded on the next 'isx build tpl-minimal'.");
        return CommandResult.SUCCESS;
    }

    private CommandResult interactive(List<Release> available, ImageDef current, String currentTag) throws IOException {
        printReleaseList(available, currentTag);

        var latest = available.get(0);
        if (latest.tag().equals(currentTag) && !current.isPinned()) {
            BuildOutput.success("Already tracking the latest version.");
            return CommandResult.SUCCESS;
        }

        var console = System.console();
        if (console == null) {
            System.err.println("No console available. Use --latest or specify a tag.");
            return CommandResult.valueOf(1);
        }

        System.out.println("\nWhat would you like to do?");
        System.out.println("  1) Track latest (" + latest.tag() + ") — always installs the newest release on build");
        System.out.println("  2) Pin a specific version");
        System.out.println("  3) Cancel");
        System.out.print("Choice [1-3]: ");

        var choice = console.readLine();
        if (choice == null) return CommandResult.SUCCESS;
        choice = choice.strip();

        return switch (choice) {
            case "1" -> trackLatest(latest.tag());
            case "2" -> {
                System.out.print("Enter version tag: ");
                var tag = console.readLine();
                if (tag == null || tag.isBlank()) {
                    System.out.println("Aborted.");
                    yield CommandResult.SUCCESS;
                }
                yield pinToTag(tag.strip(), available, current);
            }
            default -> {
                System.out.println("Aborted.");
                yield CommandResult.SUCCESS;
            }
        };
    }

    private void writeUserOverride(ImageDef current, String tag, Checksums checksums) throws IOException {
        var dir = ImageDef.userImagesDir();
        Files.createDirectories(dir);
        var overridePath = dir.resolve("minimal.yaml");

        var yaml = new StringBuilder();
        yaml.append("name: tpl-minimal\n");
        yaml.append("description: ").append(current.getDescription()).append('\n');
        yaml.append("image: ").append(current.getImage()).append('\n');
        yaml.append("image_url: ").append(current.getImageUrl()).append('\n');
        if (current.getVmImageUrl() != null) {
            yaml.append("vm_image_url: ").append(current.getVmImageUrl()).append('\n');
        }
        yaml.append("image_tag: ").append(tag).append('\n');
        yaml.append("pinned: true\n");
        appendShaBlock(yaml, "image_sha256", checksums.container());
        if (current.getVmImageUrl() != null && !checksums.vm().isEmpty()) {
            appendShaBlock(yaml, "vm_image_sha256", checksums.vm());
        }

        Files.writeString(overridePath, yaml.toString());
        BuildOutput.note("Wrote " + overridePath);
    }

    private static void appendShaBlock(StringBuilder yaml, String key, Map<String, String> shas) {
        yaml.append(key).append(":\n");
        shas.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> yaml.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n'));
    }

    private void printReleaseList(List<Release> available, String currentTag) {
        System.out.println("\nAvailable base images:");
        for (int i = 0; i < available.size(); i++) {
            var r = available.get(i);
            var markers = new ArrayList<String>();
            if (i == 0) markers.add("latest");
            if (r.tag().equals(currentTag)) markers.add("current");
            var suffix = markers.isEmpty() ? "" : "  [" + String.join(", ", markers) + "]";
            System.out.println("  " + r.tag() + "  " + r.date() + suffix);
        }
    }
}
