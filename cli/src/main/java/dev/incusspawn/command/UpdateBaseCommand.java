package dev.incusspawn.command;

import dev.incusspawn.baseimage.BaseImageReleases;
import dev.incusspawn.baseimage.BaseImageReleases.Checksums;
import dev.incusspawn.baseimage.BaseImageReleases.Release;
import dev.incusspawn.config.BaseImagePin;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@CommandDefinition(
        name = "update-base",
        description = "Check for and install base image updates",
        generateHelp = true
)
public class UpdateBaseCommand extends BaseCommand {

    BaseImageReleases releases;

    @Argument(required = false, description = "Release tag to pin (e.g. fedora-44-v2)")
    String targetTag;

    @Option(name = "list", hasValue = false, description = "List available versions")
    boolean listOnly;

    @Option(name = "latest", hasValue = false, description = "Track the latest version (remove any pin)")
    boolean useLatest;

    // Output for scripts (#1036): see OutputFormat for what plain and json promise.
    @Option(name = "format", completer = Completers.Format.class, description = "Output format of --list: table (default), plain or json")
    String format;

    @Override
    protected CommandResult doExecute() throws Exception {
        var outputFormat = OutputFormat.parse(format);
        if (outputFormat != OutputFormat.TABLE && !listOnly) {
            throw new IllegalArgumentException("--format applies to --list only");
        }
        // The pin applies to the trusted layers, so a project-local tpl-minimal does not show here
        var minimal = ImageDef.loadTrusted().get(BaseImagePin.TEMPLATE);
        var unpinned = unpinnedMinimal();
        if (minimal == null || unpinned == null) {
            BuildOutput.error("template tpl-minimal not found.");
            return CommandResult.valueOf(1);
        }

        releases = BaseImageReleases.fromImageUrl(unpinned.getImageUrl());
        if (releases == null) {
            BuildOutput.error("template tpl-minimal has no trackable GitHub base image URL.");
            return CommandResult.valueOf(1);
        }

        if (outputFormat != OutputFormat.TABLE) {
            List<Release> available;
            try {
                available = releases.fetchReleases();
            } catch (IOException e) {
                BuildOutput.error("could not fetch releases: " + failure(e));
                return CommandResult.valueOf(1);
            }
            outputFormat.print(BuildOutput.results(), releaseRecords(available, latestOf(available, unpinned),
                    minimal.getImageTag(), minimal.isPinned()));
            return CommandResult.SUCCESS;
        }

        BuildOutput.header("Checking for base image updates");
        var currentTag = minimal.getImageTag();
        var isPinned = minimal.isPinned();
        BuildOutput.step("Current base image: " + (currentTag != null ? OutputFormat.oneLine(currentTag) : "unknown")
                + (isPinned ? " [pinned]" : ""));
        if (BaseImagePin.isLegacyPin(minimal)) {
            BuildOutput.note(OutputFormat.oneLine(minimal.getSource()) + " is a pin an earlier isx wrote, which hides changes to the built-in "
                    + BaseImagePin.TEMPLATE + "; pinning again or --latest replaces it.");
        }

        BuildOutput.stepStart("Fetching available releases");
        List<Release> available;
        try {
            available = releases.fetchReleases();
        } catch (IOException e) {
            BuildOutput.stepFail("Failed to fetch releases: " + e.getMessage());
            return CommandResult.valueOf(1);
        }
        BuildOutput.stepDone();

        if (available.isEmpty()) {
            BuildOutput.warn("No releases found.");
            return CommandResult.SUCCESS;
        }

        var latest = latestOf(available, unpinned);
        if (listOnly) {
            printReleaseList(available, latest, currentTag);
            return CommandResult.SUCCESS;
        }

        // Explicit tag argument → pin to that version
        if (targetTag != null) {
            return pinToTag(targetTag, available, unpinned);
        }

        // --latest flag → track latest, remove any pin
        if (useLatest) {
            return trackLatest(latest);
        }

        // Interactive mode
        return interactive(available, latest, isPinned, unpinned, currentTag);
    }

    /**
     * The tag tracking the latest follows: the newest release on the built-in's line (#1994), or
     * the built-in's own tag when none is listed on it. Another Fedora or channel is pinned by tag.
     */
    static String latestOf(List<Release> available, ImageDef unpinned) {
        var tracked = BaseImageAge.trackedRelease(available, Release::tag, unpinned.getImageTag());
        return tracked == null ? unpinned.getImageTag() : tracked.tag();
    }

    CommandResult trackLatest(String latestTag) throws IOException {
        boolean removed = Files.deleteIfExists(BaseImagePin.file());
        if (removeLegacyPin() || removed) {
            BuildOutput.note("Removed version pin.");
        }
        var stillPinned = ImageDef.loadTrusted().get(BaseImagePin.TEMPLATE);
        if (stillPinned != null && stillPinned.isPinned()) {
            // A definition of the user's own that pins its image itself: theirs to change
            BuildOutput.warn(OutputFormat.oneLine(stillPinned.getSource()) + " sets 'pinned: true' itself, so builds stay on "
                    + OutputFormat.oneLine(stillPinned.getImageTag()) + "; remove it there to track the latest base image.");
            return CommandResult.SUCCESS;
        }
        BuildOutput.success("Now tracking the latest base image");
        BuildOutput.note("The newest release (" + latestTag
                + ") will be resolved and installed on the next 'isx build tpl-minimal'.");
        return CommandResult.SUCCESS;
    }

    /**
     * Removes a user {@code minimal.yaml} an {@code isx update-base} before #1563 wrote, which
     * replaced the whole built-in definition; one the user wrote themselves is left alone.
     */
    private static boolean removeLegacyPin() throws IOException {
        var legacy = BaseImagePin.legacyFile();
        return BaseImagePin.isLegacyPin(legacy) && Files.deleteIfExists(legacy);
    }

    /**
     * What a pin is taken against: the trusted {@code tpl-minimal} with no pin applied, or the
     * built-in when that is a legacy pin, which pinning removes.
     */
    static ImageDef unpinnedMinimal() {
        var def = ImageDef.loadTrustedUnpinned().get(BaseImagePin.TEMPLATE);
        // The user layer sits right above the built-ins, so without it the built-in is in effect
        return def != null && BaseImagePin.isLegacyPin(def) ? ImageDef.loadBuiltinByName(BaseImagePin.TEMPLATE) : def;
    }

    CommandResult pinToTag(String tag, List<Release> available, ImageDef current) throws IOException {
        var selected = available.stream()
                .filter(r -> r.tag().equals(tag))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            BuildOutput.error("release '" + OutputFormat.oneLine(tag) + "' not found.");
            printReleaseList(available, latestOf(available, current), current.getImageTag());
            return CommandResult.valueOf(1);
        }

        // A release of another Fedora line is named for that line, and pinned under its names (#1967)
        BaseImageReleases.Names names;
        try {
            names = BaseImageReleases.namesFor(
                    new BaseImageReleases.Names(current.getImage(), current.getImageUrl(), current.getVmImageUrl()),
                    current.getImageTag(), selected.tag());
        } catch (IllegalArgumentException e) {
            BuildOutput.error("cannot pin " + selected.tag() + ", a release of "
                    + BaseImageReleases.releaseLine(selected.tag()) + ": " + OutputFormat.oneLine(e.getMessage()) + ".",
                    "Pin a release of " + BaseImageReleases.releaseLine(current.getImageTag())
                            + ", or name the release line once in tpl-minimal's image and file names.");
            return CommandResult.valueOf(1);
        }

        BuildOutput.stepStart("Fetching checksums for " + selected.tag());
        var checksums = releases.fetchChecksums(selected, names.imageUrl(), names.vmImageUrl());
        if (checksums == null) {
            BuildOutput.stepFail("No readable SHA256SUMS.v2 or SHA256SUMS in release " + selected.tag() + ".");
            return CommandResult.valueOf(1);
        }
        // A pin is used on any host it is copied to: an arch it has no checksum for would
        // download unverified there.
        if (!BaseImageReleases.coversEveryArch(checksums.container())) {
            BuildOutput.stepFail("Release " + selected.tag() + " does not list a checksum for "
                    + BaseImageReleases.assetName(names.imageUrl(), selected.tag(), "<arch>")
                    + " on every architecture.");
            return CommandResult.valueOf(1);
        }
        BuildOutput.stepDone();

        writePin(names, selected.tag(), checksums);
        BuildOutput.success("Pinned base image to " + selected.tag());
        BuildOutput.note("The new base image will be downloaded on the next 'isx build tpl-minimal'.");
        return CommandResult.SUCCESS;
    }

    private CommandResult interactive(List<Release> available, String latest, boolean isPinned, ImageDef unpinned,
                                      String currentTag) throws IOException {
        printReleaseList(available, latest, currentTag);

        if (latest.equals(currentTag) && !isPinned) {
            BuildOutput.success("Already tracking the latest version");
            return CommandResult.SUCCESS;
        }

        var prompts = Prompts.terminal();
        if (prompts == null) {
            BuildOutput.error("no terminal to ask on.", "Use --latest or specify a tag.");
            return CommandResult.valueOf(1);
        }
        return askWhatToDo(prompts, latest, available, unpinned);
    }

    /** Asks whether to track the latest release or pin one, through {@code prompts}, and does it. */
    CommandResult askWhatToDo(Prompts prompts, String latest, List<Release> available, ImageDef unpinned)
            throws IOException {
        prompts.say("What would you like to do?");
        prompts.say("1) Track latest (" + latest + ") — always installs the newest release of its line on build");
        prompts.say("2) Pin a specific version");
        prompts.say("3) Cancel");
        var choice = prompts.ask("Choice [1-3]: ");
        if (choice == null) return CommandResult.SUCCESS;

        return switch (choice.strip()) {
            case "1" -> trackLatest(latest);
            case "2" -> {
                var tag = prompts.ask("Enter version tag: ");
                if (tag == null || tag.isBlank()) {
                    BuildOutput.step("Aborted.");
                    yield CommandResult.SUCCESS;
                }
                yield pinToTag(tag.strip(), available, unpinned);
            }
            default -> {
                BuildOutput.step("Aborted.");
                yield CommandResult.SUCCESS;
            }
        };
    }

    /**
     * Writes the pin file: the release's base image and nothing else, so every other field of
     * {@code tpl-minimal} keeps coming from its definition (#1563).
     */
    void writePin(BaseImageReleases.Names names, String tag, Checksums checksums) throws IOException {
        // A VM image is only pinned with its checksums for every arch: without them it would
        // download unverified on the arch that has none.
        var vm = names.vmImageUrl() != null && BaseImageReleases.coversEveryArch(checksums.vm());
        if (!vm && names.vmImageUrl() != null) {
            BuildOutput.warn("Release " + tag + " has no VM image this isx can use;"
                    + " VM templates built on this pin start from the Incus image server instead.");
        }
        var pin = new BaseImagePin(names.image(), names.imageUrl(), vm ? names.vmImageUrl() : null,
                tag, new TreeMap<>(checksums.container()), vm ? new TreeMap<>(checksums.vm()) : null);
        var file = BaseImagePin.file();
        pin.write(file);
        BuildOutput.note("Wrote " + file);
        if (removeLegacyPin()) {
            BuildOutput.note("Removed " + BaseImagePin.legacyFile() + ", the pin an earlier isx wrote: built-in changes to "
                    + BaseImagePin.TEMPLATE + " apply again.");
        }
    }

    private void printReleaseList(List<Release> available, String latest, String currentTag) {
        var lines = new ArrayList<String>();
        for (var r : available) {
            var markers = new ArrayList<String>();
            if (r.tag().equals(latest)) markers.add("latest");
            if (r.tag().equals(currentTag)) markers.add("current");
            var suffix = markers.isEmpty() ? "" : "  [" + String.join(", ", markers) + "]";
            lines.add(r.tag() + "  " + r.date() + suffix);
        }
        groupOfSteps("Available base images", lines);
    }

    /**
     * The fields of {@code isx update-base --list --format=plain|json}, newest first, in order:
     * add to the end, never rename. {@code date} is the release's ISO-8601 publication date;
     * {@code pinned} marks the current release when it is pinned, so builds never move past it.
     * {@code latest} marks the release tracking the latest follows: the newest on the built-in's
     * line, never another Fedora or channel (#1994).
     * {@code listed} is whether the release is among those fetched (the newest published ones):
     * a current base image that is not -- older than all of them, unpublished, or deleted --
     * gets a record of its own, last, with {@code listed} false and no date, so a script always
     * finds the current one. Not being listed says nothing about its age.
     */
    static List<Map<String, Object>> releaseRecords(List<Release> available, String latest, String currentTag, boolean pinned) {
        var records = new ArrayList<Map<String, Object>>();
        for (var r : available) {
            var record = new LinkedHashMap<String, Object>();
            record.put("tag", r.tag());
            record.put("date", r.date().isEmpty() ? null : r.date());
            record.put("latest", r.tag().equals(latest));
            record.put("current", r.tag().equals(currentTag));
            record.put("pinned", pinned && r.tag().equals(currentTag));
            record.put("listed", true);
            records.add(record);
        }
        if (currentTag != null && available.stream().noneMatch(r -> r.tag().equals(currentTag))) {
            var record = new LinkedHashMap<String, Object>();
            record.put("tag", currentTag);
            record.put("date", null);
            // The built-in itself is what tracking follows when nothing newer of its line is listed
            record.put("latest", currentTag.equals(latest));
            record.put("current", true);
            record.put("pinned", pinned);
            record.put("listed", false);
            records.add(record);
        }
        return records;
    }
}
