package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@CommandDefinition(
        name = "clean",
        description = "Remove cached data, state, or configuration",
        generateHelp = true,
        groupCommands = {
                CleanCommand.Cache.class,
                CleanCommand.State.class,
                CleanCommand.Config.class,
                CleanCommand.Pool.class,
                CleanCommand.All.class
        }
)
public class CleanCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        System.out.println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    // -- shared helpers --

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    record DirInfo(Path path, long size, int files) {}

    static List<DirInfo> collectInfo(List<Path> dirs) {
        return collectInfo(dirs, null);
    }

    /** What deleting {@code dirs} frees, leaving {@code keep} (if not null) out of the count. */
    static List<DirInfo> collectInfo(List<Path> dirs, Path keep) {
        var result = new ArrayList<DirInfo>();
        for (var dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            var info = measure(dir, keep);
            // Nothing but the kept file left: there is nothing here to delete
            if (info.files == 0 && keep != null && dir.equals(keep.getParent())) continue;
            result.add(info);
        }
        return result;
    }

    private static DirInfo measure(Path dir, Path keep) {
        long[] size = {0};
        int[] files = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!file.equals(keep)) {
                        size[0] += attrs.size();
                        files[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
        return new DirInfo(dir, size[0], files[0]);
    }

    static void printSummary(List<DirInfo> infos) {
        long total = 0;
        int totalFiles = 0;
        for (var info : infos) {
            System.out.printf("  %-50s %8s  (%d files)%n",
                    info.path, formatSize(info.size), info.files);
            total += info.size;
            totalFiles += info.files;
        }
        System.out.printf("  %-50s %8s  (%d files)%n", "Total:", formatSize(total), totalFiles);
    }

    /** Delete {@code dir}, or everything in it but {@code keep} when that is one of its entries. */
    private static void delete(Path dir, Path keep) throws IOException {
        if (keep == null || !dir.equals(keep.getParent())) {
            FileTrees.delete(dir);
            return;
        }
        try (var children = Files.list(dir)) {
            for (var child : children.toList()) {
                if (child.equals(keep)) continue;
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) FileTrees.delete(child);
                else Files.deleteIfExists(child);
            }
        }
    }

    static void cleanDirs(List<Path> dirs, boolean dryRun, boolean skipConfirmation,
                          String category) throws IOException {
        cleanDirs(dirs, false, dryRun, skipConfirmation, category);
    }

    static void cleanDirs(List<Path> dirs, boolean deleteInstances, boolean dryRun, boolean skipConfirmation,
                          String category) throws IOException {
        cleanDirs(dirs, deleteInstances, dryRun, skipConfirmation, category, "Will delete:", List.of(), "Proceed?");
    }

    /**
     * Show what deleting {@code dirs} frees, then {@code warning}, and delete them once confirmed.
     *
     * <p>When one of them holds the macOS VM's data disk, it is kept unless {@code deleteInstances}:
     * it is mounted at {@code /var/lib/incus}, so it is the Incus database and the {@code cow} pool,
     * every instance, template and image (#1155). Either way the summary says which.
     *
     * @return whether anything was deleted
     */
    static boolean cleanDirs(List<Path> dirs, boolean deleteInstances, boolean dryRun, boolean skipConfirmation,
                             String category, String header, List<String> warning, String question)
            throws IOException {
        var disk = Environment.vmDataImage();
        // Only a directory holding the disk itself: delete() spares it there and nowhere deeper
        boolean holdsDisk = Files.exists(disk) && dirs.contains(disk.getParent());
        if (deleteInstances && !holdsDisk) {
            System.out.println("Note: --delete-instances only deletes the macOS VM's data disk, and there is none.");
            System.out.println("To remove instances, use 'isx destroy', or 'isx reset' for everything.");
        }
        var keep = holdsDisk && !deleteInstances ? disk : null;
        var infos = collectInfo(dirs, keep);
        if (infos.isEmpty()) {
            System.out.println("Nothing to clean — no " + category + " data found.");
            return false;
        }

        System.out.println(dryRun ? "Would delete:" : header);
        printSummary(infos);
        if (holdsDisk) {
            System.out.println();
            if (deleteInstances) {
                System.out.println("WARNING: This includes the VM's data disk (" + disk + "):");
                System.out.println("every instance, template and image. They cannot be recovered.");
            } else {
                System.out.println("Kept " + disk + ": the VM's data disk, with every instance,");
                System.out.println("template and image. Pass --delete-instances to delete it too.");
            }
        }
        if (dryRun) return false;
        System.out.println();
        warning.forEach(System.out::println);

        if (!confirmDestructive(question, skipConfirmation, "--skip-confirmation")) return false;

        for (var info : infos) {
            delete(info.path, keep);
        }
        long total = infos.stream().mapToLong(i -> i.size).sum();
        int totalFiles = infos.stream().mapToInt(i -> i.files).sum();
        System.out.println("Freed " + formatSize(total) + " from " + totalFiles + " files.");
        return true;
    }

    static void cleanDnfCacheVolume(boolean dryRun) {
        try {
            var incus = RuntimeServices.incus();
            var pool = incus.findCowPool();
            if (pool == null) return;
            var volume = BuildCommand.DNF_CACHE_VOLUME;
            if (dryRun) {
                System.out.println("Would delete DNF cache volume (" + volume + ") from pool " + pool);
                return;
            }
            if (incus.deleteStorageVolume(pool, volume)) {
                System.out.println("Deleted DNF cache volume (" + volume + ") from pool " + pool);
            }
        } catch (Exception e) {
            var msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("in use")) {
                System.err.println("Warning: could not clean DNF cache volume — it is in use by a running build. Try again after the build finishes.");
            } else {
                System.err.println("Warning: could not clean DNF cache volume: " + msg);
            }
        }
    }

    // -- subcommands --

    @CommandDefinition(
            name = "cache",
            description = "Remove cached downloads, registry blobs, and build caches (~/.cache/incus-spawn/)",
            generateHelp = true
    )
    public static class Cache extends BaseCommand {

        @Option(name = "dry-run", hasValue = false, description = "Show what would be deleted without deleting")
        boolean dryRun;

        @Option(name = "skip-confirmation", hasValue = false, description = "Skip the confirmation prompt")
        boolean skipConfirmation;

        @Override
        protected CommandResult doExecute() throws Exception {
            cleanDirs(List.of(Environment.cacheDir()), dryRun, skipConfirmation, "cache");
            cleanDnfCacheVolume(dryRun);
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "state",
            description = "Remove VM state, logs, and appliance artifacts (~/.local/state/ and ~/.local/share/incus-spawn/); keeps the macOS VM's data disk",
            generateHelp = true
    )
    public static class State extends BaseCommand {

        @Option(name = "delete-instances", hasValue = false,
                description = "Also delete the macOS VM's data disk: every instance, template and image")
        boolean deleteInstances;

        @Option(name = "dry-run", hasValue = false, description = "Show what would be deleted without deleting")
        boolean dryRun;

        @Option(name = "skip-confirmation", hasValue = false, description = "Skip the confirmation prompt")
        boolean skipConfirmation;

        @Override
        protected CommandResult doExecute() throws Exception {
            if (VmManager.isRunning()) {
                System.err.println("Error: VM is currently running. Stop it first with 'isx vm stop'.");
                return CommandResult.valueOf(1);
            }
            cleanDirs(List.of(Environment.vmStateDir(), Environment.dataDir()), deleteInstances,
                    dryRun, skipConfirmation, "state");
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "config",
            description = "Remove configuration, SSH keys, and CA certificate (~/.config/incus-spawn/)",
            generateHelp = true
    )
    public static class Config extends BaseCommand {

        @Option(name = "dry-run", hasValue = false, description = "Show what would be deleted without deleting")
        boolean dryRun;

        @Option(name = "skip-confirmation", hasValue = false, description = "Skip the confirmation prompt")
        boolean skipConfirmation;

        @Override
        protected CommandResult doExecute() throws Exception {
            cleanDirs(List.of(Environment.configDir()), false, dryRun, skipConfirmation, "configuration",
                    "Will delete:", List.of(
                            "WARNING: This will permanently delete your SSH keys, CA certificate,",
                            "and configuration. You will need to run 'isx init' again and rebuild",
                            "all templates."),
                    "Delete configuration?");
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "all",
            description = "Remove cache, state, and configuration (does not touch Incus templates or instances unless --delete-instances)",
            generateHelp = true
    )
    public static class All extends BaseCommand {

        @Option(name = "delete-instances", hasValue = false,
                description = "Also delete the macOS VM's data disk: every instance, template and image")
        boolean deleteInstances;

        @Option(name = "dry-run", hasValue = false, description = "Show what would be deleted without deleting")
        boolean dryRun;

        @Option(name = "skip-confirmation", hasValue = false, description = "Skip the confirmation prompt")
        boolean skipConfirmation;

        @Override
        protected CommandResult doExecute() throws Exception {
            if (VmManager.isRunning()) {
                System.err.println("Error: VM is currently running. Stop it first with 'isx vm stop'.");
                return CommandResult.valueOf(1);
            }

            var dirs = List.of(
                    Environment.cacheDir(),
                    Environment.vmStateDir(),
                    Environment.dataDir(),
                    Environment.configDir());
            boolean poolDeleted = deleteInstances && Files.exists(Environment.vmDataImage());
            var warning = new ArrayList<>(List.of(
                    "WARNING: This includes your SSH keys, CA certificate, and configuration.",
                    "You will need to run 'isx init' again and rebuild all templates."));
            if (!poolDeleted) {
                warning.add("Note: Built templates and instances in the Incus storage pool are not affected.");
                warning.add("Use 'isx clean pool' to reclaim pool space.");
            }
            boolean deleted = cleanDirs(dirs, deleteInstances, dryRun, skipConfirmation, "incus-spawn",
                    "Will delete incus-spawn cache, state, and configuration:", warning,
                    "Delete all listed directories?");
            // With the data disk gone there is no pool left to hold the DNF cache volume
            if (deleted && !poolDeleted) cleanDnfCacheVolume(false);
            return CommandResult.SUCCESS;
        }
    }

    // -- shared pool-clean logic (used by both CLI and TUI) --

    public record CleanScan(
            String poolName,
            IncusClient.PoolUsage usage,
            List<String> failedBuilds,
            List<IncusClient.ImageInfo> unusedImages,
            List<IncusClient.ImageInfo> baseImages,
            boolean dnfCacheExists
    ) {
        public long unusedImagesBytes() {
            return totalSize(unusedImages);
        }

        public long baseImagesBytes() {
            return totalSize(baseImages);
        }
    }

    public record CleanResult(
            String poolName,
            IncusClient.PoolUsage beforeUsage,
            IncusClient.PoolUsage afterUsage,
            int failedBuildsDeleted,
            int unusedImagesDeleted,
            int baseImagesDeleted,
            boolean dnfCacheDeleted,
            List<String> warnings
    ) {
        public boolean found() {
            return failedBuildsDeleted > 0 || unusedImagesDeleted > 0 || baseImagesDeleted > 0 || dnfCacheDeleted;
        }
    }

    static long totalSize(List<IncusClient.ImageInfo> images) {
        return images.stream().mapToLong(IncusClient.ImageInfo::size).sum();
    }

    /**
     * Delete every failed build, then tell the proxy once. A failed build keeps the static IP
     * and account pins its build was given (#903), so until the proxy re-reads, the next
     * holder of that address would be served the deleted build's accounts.
     */
    static int deleteFailedBuilds(IncusClient incus, List<String> warnings) {
        return deleteFailedBuilds(incus, findFailedBuilds(incus), name -> {},
                (name, e) -> warnings.add("Could not delete " + name + ": " + e.getMessage()));
    }

    /** Delete these failed builds, reporting each, then tell the proxy once if any went. */
    static int deleteFailedBuilds(IncusClient incus, List<String> names, Consumer<String> deleted,
                                  BiConsumer<String, Exception> failed) {
        int count = 0;
        for (var name : names) {
            try {
                incus.delete(name, true);
                count++;
                deleted.accept(name);
            } catch (Exception e) {
                failed.accept(name, e);
            }
        }
        if (count > 0) InstanceDestroyer.refreshProxy();
        return count;
    }

    /** Delete one failed build and tell the proxy, as {@link #deleteFailedBuilds} does. */
    static void deleteFailedBuild(IncusClient incus, String name) {
        incus.delete(name, true);
        InstanceDestroyer.refreshProxy();
    }

    static List<String> findFailedBuilds(IncusClient incus) {
        var result = new ArrayList<String>();
        for (var inst : incus.list()) {
            var name = inst.get("name");
            if (name.endsWith("-failed-build")) result.add(name);
        }
        return result;
    }

    /**
     * Local images split by what deleting them costs. {@code unused} match no template at all.
     * {@code base} are the base images a template downloads from its {@code image_url} or
     * {@code vm_image_url}: deleting one is safe, but the next build of that template has to
     * download it again, so, like the DNF cache, they are only removed on request. An image
     * behind a template's alias that has no URL to fetch it again is in neither list.
     */
    record ImageScan(List<IncusClient.ImageInfo> unused, List<IncusClient.ImageInfo> base) {}

    static ImageScan classifyImages(List<IncusClient.ImageInfo> images, Collection<ImageDef> defs) {
        var knownAliases = new HashSet<String>();
        var downloadedAliases = new HashSet<String>();
        for (var def : defs) {
            var img = def.getImage();
            if (img == null || img.contains(":")) continue;
            knownAliases.add(img);
            if (def.getImageUrl() != null) downloadedAliases.add(img);
            if (def.getVmImageUrl() != null) {
                knownAliases.add(BuildCommand.vmImageAlias(img));
                downloadedAliases.add(BuildCommand.vmImageAlias(img));
            }
        }
        var unused = new ArrayList<IncusClient.ImageInfo>();
        var base = new ArrayList<IncusClient.ImageInfo>();
        for (var image : images) {
            if (image.aliases().stream().anyMatch(downloadedAliases::contains)) base.add(image);
            else if (image.aliases().stream().noneMatch(knownAliases::contains)) unused.add(image);
        }
        return new ImageScan(unused, base);
    }

    static ImageScan scanImages(IncusClient incus) {
        return classifyImages(incus.listImages(), ImageDef.loadAll().values());
    }

    static List<IncusClient.ImageInfo> findUnusedImages(IncusClient incus) {
        return scanImages(incus).unused();
    }

    public static CleanScan scanPool(IncusClient incus) {
        var pool = incus.findCowPool();
        if (pool == null) return null;

        var usage = incus.getPoolUsageBytes(pool);
        var failedBuilds = findFailedBuilds(incus);
        var images = scanImages(incus);
        boolean dnfExists = false;
        try {
            dnfExists = incus.storageVolumeExists(pool, BuildCommand.DNF_CACHE_VOLUME);
        } catch (Exception ignored) {}
        return new CleanScan(pool, usage, failedBuilds, images.unused(), images.base(), dnfExists);
    }

    /** Remove everything reclaimable, cached base images included. */
    static CleanResult cleanPool(IncusClient incus) {
        return cleanPool(incus, true, true, true, true);
    }

    public static CleanResult cleanPool(IncusClient incus,
                                 boolean deleteFailedBuilds,
                                 boolean deleteUnusedImages,
                                 boolean deleteBaseImages,
                                 boolean deleteDnfCache) {
        var pool = incus.findCowPool();
        if (pool == null) return null;

        var beforeUsage = incus.getPoolUsageBytes(pool);
        var warnings = new ArrayList<String>();

        int failedDeleted = deleteFailedBuilds ? deleteFailedBuilds(incus, warnings) : 0;

        int imagesDeleted = 0;
        int baseImagesDeleted = 0;
        if (deleteUnusedImages || deleteBaseImages) {
            var images = scanImages(incus);
            if (deleteUnusedImages) imagesDeleted = deleteImages(incus, images.unused(), warnings);
            if (deleteBaseImages) baseImagesDeleted = deleteImages(incus, images.base(), warnings);
        }

        boolean dnfDeleted = false;
        if (deleteDnfCache) {
            try {
                if (incus.storageVolumeExists(pool, BuildCommand.DNF_CACHE_VOLUME)) {
                    if (incus.deleteStorageVolume(pool, BuildCommand.DNF_CACHE_VOLUME)) {
                        dnfDeleted = true;
                    }
                }
            } catch (Exception e) {
                var msg = e.getMessage();
                if (msg != null && msg.toLowerCase().contains("in use")) {
                    warnings.add("DNF cache volume in use by a running build");
                } else {
                    warnings.add("Could not clean DNF cache volume: " + msg);
                }
            }
        }

        boolean anyDeleted = failedDeleted > 0 || imagesDeleted > 0 || baseImagesDeleted > 0 || dnfDeleted;
        var afterUsage = anyDeleted ? incus.getPoolUsageBytes(pool) : beforeUsage;
        return new CleanResult(pool, beforeUsage, afterUsage,
                failedDeleted, imagesDeleted, baseImagesDeleted, dnfDeleted, warnings);
    }

    private static int deleteImages(IncusClient incus, List<IncusClient.ImageInfo> images, List<String> warnings) {
        int deleted = 0;
        for (var image : images) {
            try {
                deleteImage(incus, image);
                deleted++;
            } catch (Exception e) {
                warnings.add("Could not delete image: " + e.getMessage());
            }
        }
        return deleted;
    }

    static void deleteImage(IncusClient incus, IncusClient.ImageInfo image) {
        for (var alias : image.aliases()) {
            incus.deleteImageAlias(alias);
        }
        incus.deleteImage(image.fingerprint());
    }

    @CommandDefinition(
            name = "pool",
            description = "Reclaim space from the storage pool (failed builds, unused images, build caches)",
            generateHelp = true
    )
    public static class Pool extends BaseCommand {

        @Option(name = "base-images", hasValue = false,
                description = "Also remove downloaded base images; the next build of their templates downloads them again")
        boolean baseImages;

        @Option(name = "dry-run", hasValue = false, description = "Show what would be deleted without deleting")
        boolean dryRun;

        @Option(name = "skip-confirmation", hasValue = false, description = "Skip the confirmation prompt")
        boolean skipConfirmation;

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            var pool = incus.findCowPool();
            if (pool == null) {
                System.out.println("No CoW storage pool found.");
                return CommandResult.SUCCESS;
            }

            BuildOutput.header("Reclaim pool space");
            var usage = incus.getStoragePoolUsage(pool);
            BuildOutput.step(usage);

            boolean found = false;

            found |= cleanFailedBuilds(incus, dryRun, skipConfirmation);
            var images = scanImages(incus);
            found |= cleanImages(incus, images.unused(), "Unused images", "unused image(s)", dryRun, skipConfirmation);
            if (baseImages) {
                found |= cleanImages(incus, images.base(), "Cached base images", "cached base image(s)",
                        dryRun, skipConfirmation);
            }
            found |= cleanDnfCacheFromPool(incus, pool, dryRun);

            if (!found) {
                BuildOutput.step("Nothing to clean — no reclaimable artifacts found.");
            }
            if (!baseImages && !images.base().isEmpty()) {
                System.out.println();
                BuildOutput.note("Kept " + images.base().size() + " cached base image(s) (~"
                        + formatSize(totalSize(images.base())) + "); pass --base-images to remove them"
                        + " (the next build downloads them again).");
            }

            if (found && !dryRun) {
                var newUsage = incus.getStoragePoolUsage(pool);
                BuildOutput.success("Reclaimed — " + newUsage);
            }

            return CommandResult.SUCCESS;
        }

        private boolean cleanFailedBuilds(IncusClient incus, boolean dryRun, boolean skip) {
            var failed = findFailedBuilds(incus);
            if (failed.isEmpty()) return false;

            System.out.println();
            BuildOutput.step("Failed builds (" + failed.size() + "):");
            for (var name : failed) {
                BuildOutput.step("  " + name);
            }

            if (dryRun) {
                BuildOutput.step("Would delete " + failed.size() + " failed build instance(s).");
                return true;
            }

            if (!confirmDestructive(BuildOutput.STEP_INDENT + "Delete " + failed.size() + " failed build instance(s)?", skip, "--skip-confirmation")) {
                return true;
            }

            deleteFailedBuilds(incus, failed, name -> BuildOutput.step("  Deleted " + name),
                    (name, e) -> System.err.println(BuildOutput.STEP_INDENT + "  Warning: could not delete "
                            + name + ": " + e.getMessage()));
            return true;
        }

        private boolean cleanImages(IncusClient incus, List<IncusClient.ImageInfo> images, String title,
                                    String noun, boolean dryRun, boolean skip) {
            if (images.isEmpty()) return false;

            System.out.println();
            BuildOutput.step(title + " (" + images.size() + ", ~" + formatSize(totalSize(images)) + "):");
            for (var image : images) {
                BuildOutput.step("  " + image.label() + " (" + formatSize(image.size()) + ")");
            }

            if (dryRun) {
                BuildOutput.step("Would delete " + images.size() + " " + noun + ".");
                return true;
            }

            if (!confirmDestructive(BuildOutput.STEP_INDENT + "Delete " + images.size() + " " + noun + "?", skip, "--skip-confirmation")) {
                return true;
            }

            for (var image : images) {
                try {
                    deleteImage(incus, image);
                    BuildOutput.step("  Deleted " + image.label());
                } catch (Exception e) {
                    System.err.println(BuildOutput.STEP_INDENT + "  Warning: could not delete image: " + e.getMessage());
                }
            }
            return true;
        }

        private boolean cleanDnfCacheFromPool(IncusClient incus, String pool, boolean dryRun) {
            try {
                if (!incus.storageVolumeExists(pool, BuildCommand.DNF_CACHE_VOLUME)) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }

            if (dryRun) {
                System.out.println("Would delete DNF cache volume (" + BuildCommand.DNF_CACHE_VOLUME + ") from pool " + pool);
                return true;
            }

            cleanDnfCacheVolume(false);
            return true;
        }
    }
}
