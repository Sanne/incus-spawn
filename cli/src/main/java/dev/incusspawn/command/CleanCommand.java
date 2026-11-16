package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;
import dev.incusspawn.Platform;
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
import java.util.function.Predicate;

@CommandDefinition(
        name = "clean",
        description = "Remove cached data, state, or configuration",
        generateHelp = true,
        groupCommands = {
                CleanCommand.Cache.class,
                CleanCommand.State.class,
                CleanCommand.Config.class,
                CleanCommand.Pool.class,
                CleanCommand.Dnf.class,
                CleanCommand.All.class
        }
)
public class CleanCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        BuildOutput.results().println(commandInvocation.getHelpInfo());
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

    /** The directories and their total, then the DNF cache volume if there is one, as a group. */
    static void printSummary(String title, List<DirInfo> infos, DnfCacheVolume dnfCache) {
        try (var group = BuildOutput.group(title, null)) {
            long total = 0;
            int totalFiles = 0;
            for (var info : infos) {
                BuildOutput.step(summaryRow(info.path.toString(), info.size, info.files));
                total += info.size;
                totalFiles += info.files;
            }
            // The volume's size is not known here, so it stays out of the total, below it
            if (!infos.isEmpty()) BuildOutput.step(summaryRow("Total:", total, totalFiles));
            if (dnfCache != null) BuildOutput.step("and the " + dnfCache.label());
        }
    }

    private static String summaryRow(String what, long size, int files) {
        return String.format("%-50s %8s  (%d files)", what, formatSize(size), files);
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
        cleanDirs(dirs, deleteInstances, dryRun, skipConfirmation, category, "Will delete", () -> {}, "Proceed?");
    }

    /**
     * Show what deleting {@code dirs} frees, then its {@code caveats}, and delete them once confirmed.
     *
     * <p>When one of them holds the macOS VM's data disk, it is kept unless {@code deleteInstances}:
     * it is mounted at {@code /var/lib/incus}, so it is the Incus database and the {@code cow} pool,
     * every instance, template and image (#1155). Either way the summary says which.
     *
     * @return whether anything was deleted
     */
    static boolean cleanDirs(List<Path> dirs, boolean deleteInstances, boolean dryRun, boolean skipConfirmation,
                             String category, String header, Runnable caveats, String question)
            throws IOException {
        return cleanDirs(dirs, deleteInstances, dryRun, confirmation(skipConfirmation),
                category, header, caveats, question, null);
    }

    static Predicate<String> confirmation(boolean skipConfirmation) {
        return question -> confirmDestructive(question, skipConfirmation, "--skip-confirmation");
    }

    /**
     * As above, with {@code dnfCache} (if not null) listed and confirmed with the directories and
     * deleted before them: it is never deleted on an answer that kept them (#1627).
     */
    static boolean cleanDirs(List<Path> dirs, boolean deleteInstances, boolean dryRun, Predicate<String> confirm,
                             String category, String header, Runnable caveats, String question,
                             DnfCacheVolume dnfCache)
            throws IOException {
        var disk = Environment.vmDataImage();
        // Only a directory holding the disk itself: delete() spares it there and nowhere deeper
        boolean holdsDisk = Files.exists(disk) && dirs.contains(disk.getParent());
        if (deleteInstances && !holdsDisk) {
            BuildOutput.warn("--delete-instances only deletes the macOS VM's data disk, and there is none.",
                    "To remove instances, use 'isx destroy', or 'isx reset' for everything.");
        }
        var keep = holdsDisk && !deleteInstances ? disk : null;
        var infos = collectInfo(dirs, keep);
        if (infos.isEmpty() && dnfCache == null) {
            BuildOutput.step("Nothing to clean — no " + category + " data found.");
            return false;
        }

        printSummary(dryRun ? "Would delete" : header, infos, dnfCache);
        if (holdsDisk) {
            if (deleteInstances) {
                BuildOutput.warn("This includes the VM's data disk (" + disk + "): every instance, template and image.",
                        "They cannot be recovered.");
            } else {
                BuildOutput.step("Kept " + disk + ": the VM's data disk, with every instance, template and image.");
                BuildOutput.stepNote("Pass --delete-instances to delete it too.");
            }
        }
        if (dryRun) return false;
        caveats.run();

        if (!confirm.test(question)) return false;

        // First: Incus may need what the directories hold, such as the config
        if (dnfCache != null) dnfCache.delete();
        for (var info : infos) {
            delete(info.path, keep);
        }
        if (!infos.isEmpty()) {
            long total = infos.stream().mapToLong(i -> i.size).sum();
            int totalFiles = infos.stream().mapToInt(i -> i.files).sum();
            BuildOutput.success("Freed " + formatSize(total) + " from " + totalFiles + " files");
        }
        return true;
    }

    /** The shared DNF cache volume on the CoW pool, which builds recreate on demand. */
    record DnfCacheVolume(IncusClient incus, String pool) {

        /**
         * The volume, or null when there is none.
         *
         * @throws IllegalStateException when Incus could not say, which is not "none"
         */
        static DnfCacheVolume find(IncusClient incus) {
            IncusClient.CowPoolProbe probe;
            try {
                probe = incus.probeCowPool();
            } catch (Exception e) {
                throw couldNotLook(e);
            }
            if (!probe.listed()) throw couldNotLook("Incus did not list its storage pools.");
            return probe.poolName() == null ? null : find(incus, probe.poolName());
        }

        /** The volume if {@code pool} holds it, or null; throws as {@link #find(IncusClient)} does. */
        static DnfCacheVolume find(IncusClient incus, String pool) {
            try {
                return incus.storageVolumeExists(pool, GuestProvisioning.DNF_CACHE_VOLUME)
                        ? new DnfCacheVolume(incus, pool) : null;
            } catch (Exception e) {
                throw couldNotLook(e);
            }
        }

        /** {@link #find(IncusClient)} for a clean the volume is only part of: a warning, and none. */
        static DnfCacheVolume findOrWarn(IncusClient incus) {
            try {
                return find(incus);
            } catch (IllegalStateException e) {
                BuildOutput.warn(failure(e));
                return null;
            }
        }

        private static IllegalStateException couldNotLook(String why) {
            return new IllegalStateException("could not look for the DNF cache volume: " + why);
        }

        private static IllegalStateException couldNotLook(Exception cause) {
            var e = couldNotLook(reason(cause));
            e.initCause(cause);
            return e;
        }

        private static String reason(Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        String label() {
            return "DNF cache volume (" + GuestProvisioning.DNF_CACHE_VOLUME + ") in pool " + pool;
        }

        /** Delete it, or warn: for a clean the volume is only part of. */
        void delete() {
            try {
                deleteOrThrow();
            } catch (IllegalStateException e) {
                BuildOutput.warn(failure(e));
            }
        }

        /** Delete it, or throw saying why: for {@code isx clean dnf}, whose whole job it is. */
        void deleteOrThrow() {
            try {
                BuildOutput.step(incus.deleteStorageVolume(pool, GuestProvisioning.DNF_CACHE_VOLUME)
                        ? "Deleted " + label()
                        : "The " + label() + " was already gone.");
            } catch (Exception e) {
                var msg = reason(e);
                var why = msg.toLowerCase().contains("in use")
                        ? "it is in use by a running build. Try again after the build finishes."
                        : msg;
                var failure = new IllegalStateException("could not clean DNF cache volume: " + why);
                failure.initCause(e);
                throw failure;
            }
        }
    }

    // -- subcommands --

    @CommandDefinition(
            name = "cache",
            description = "Remove cached downloads, registry blobs, and build caches (~/.cache/incus-spawn/), and the DNF cache volume builds share",
            generateHelp = true
    )
    public static class Cache extends BaseCommand {

        @Option(name = "dry-run", hasValue = false, description = "Show what would be deleted without deleting")
        boolean dryRun;

        @Option(name = "skip-confirmation", hasValue = false, description = "Skip the confirmation prompt")
        boolean skipConfirmation;

        @Override
        protected CommandResult doExecute() throws Exception {
            // On macOS Incus runs in the VM: while it is stopped there is no Incus to ask
            boolean noIncus = Platform.isMacOS() && !VmManager.isRunning();
            clean(noIncus ? null : RuntimeServices.incus(), dryRun, confirmation(skipConfirmation));
            return CommandResult.SUCCESS;
        }

        /** {@code incus} is null when there is no Incus to ask about the DNF cache volume. */
        static void clean(IncusClient incus, boolean dryRun, Predicate<String> confirm) throws IOException {
            cleanDirs(List.of(Environment.cacheDir()), false, dryRun, confirm, "cache", "Will delete",
                    () -> {}, "Proceed?", incus == null ? null : DnfCacheVolume.findOrWarn(incus));
        }
    }

    @CommandDefinition(
            name = "dnf",
            description = "Remove only the DNF cache volume that builds share; the next build downloads its packages again",
            generateHelp = true
    )
    public static class Dnf extends BaseCommand {

        @Option(name = "dry-run", hasValue = false, description = "Show what would be deleted without deleting")
        boolean dryRun;

        @Option(name = "skip-confirmation", hasValue = false, description = "Skip the confirmation prompt")
        boolean skipConfirmation;

        @Override
        protected CommandResult doExecute() throws Exception {
            if (Platform.isMacOS() && !VmManager.isRunning()) {
                BuildOutput.error("the DNF cache volume is in the VM, which is stopped.", "Start it with 'isx vm start'.");
                return CommandResult.valueOf(1);
            }
            clean(RuntimeServices.incus(), dryRun, confirmation(skipConfirmation));
            return CommandResult.SUCCESS;
        }

        /**
         * Its whole job is this volume, so not being able to look for it or to delete it is an
         * error (exit 1), never "nothing to clean".
         */
        static void clean(IncusClient incus, boolean dryRun, Predicate<String> confirm) {
            var volume = DnfCacheVolume.find(incus);
            if (volume == null) {
                BuildOutput.step("Nothing to clean — no DNF cache volume found.");
                return;
            }
            groupOfSteps(dryRun ? "Would delete" : "Will delete", List.of(volume.label()));
            if (dryRun) return;
            if (confirm.test("Proceed?")) volume.deleteOrThrow();
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
            if (VmManager.runningOrAdopted()) {
                BuildOutput.error("the VM is running.", "Stop it first with 'isx vm stop'.");
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
                    "Will delete", () -> BuildOutput.warn(
                            "This will permanently delete your SSH keys, CA certificate, and configuration.",
                            "You will need to run 'isx init' again and rebuild all templates."),
                    "Delete configuration?");
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "all",
            description = "Remove cache, state, and configuration, and on Linux the DNF cache volume builds share (does not touch Incus templates or instances unless --delete-instances)",
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
            if (VmManager.runningOrAdopted()) {
                BuildOutput.error("the VM is running.", "Stop it first with 'isx vm stop'.");
                return CommandResult.valueOf(1);
            }

            // On macOS Incus runs in the VM, stopped here, so there is no Incus to ask about the DNF cache
            clean(Platform.isMacOS() ? null : RuntimeServices.incus(), deleteInstances, dryRun,
                    confirmation(skipConfirmation));
            return CommandResult.SUCCESS;
        }

        /** {@code incus} is null when there is no Incus to ask about the DNF cache volume. */
        static void clean(IncusClient incus, boolean deleteInstances, boolean dryRun, Predicate<String> confirm)
                throws IOException {
            var dirs = List.of(
                    Environment.cacheDir(),
                    Environment.vmStateDir(),
                    Environment.dataDir(),
                    Environment.configDir());
            boolean poolDeleted = deleteInstances && Files.exists(Environment.vmDataImage());
            // With only the DNF cache volume left, there is no configuration to warn about
            boolean configGoes = Files.isDirectory(Environment.configDir());
            Runnable caveats = () -> {
                if (configGoes) {
                    BuildOutput.warn("This includes your SSH keys, CA certificate, and configuration.",
                            "You will need to run 'isx init' again and rebuild all templates.");
                }
                if (!poolDeleted) {
                    BuildOutput.note("Built templates and instances in the Incus storage pool are not affected."
                            + " Use 'isx clean pool' to reclaim pool space.");
                }
            };
            // With the data disk gone there is no pool left to hold the DNF cache volume
            var dnfCache = poolDeleted || incus == null ? null : DnfCacheVolume.findOrWarn(incus);
            cleanDirs(dirs, deleteInstances, dryRun, confirm, "incus-spawn",
                    "Will delete incus-spawn cache, state, and configuration", caveats,
                    "Delete everything listed?", dnfCache);
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
        // One settle of the pool's accounting at the end, not one per build (#2061)
        try (var batch = incus.deleteBatch()) {
            for (var name : names) {
                try {
                    incus.delete(name, true);
                    count++;
                    deleted.accept(name);
                } catch (Exception e) {
                    failed.accept(name, e);
                }
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
                // Both, whichever file this definition names now: an older isx on the same host
                // keeps the stock image under <image>-vm
                for (var alias : BuildCommand.vmImageAliases(img)) {
                    knownAliases.add(alias);
                    downloadedAliases.add(alias);
                }
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
            dnfExists = incus.storageVolumeExists(pool, GuestProvisioning.DNF_CACHE_VOLUME);
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
                if (incus.storageVolumeExists(pool, GuestProvisioning.DNF_CACHE_VOLUME)) {
                    if (incus.deleteStorageVolume(pool, GuestProvisioning.DNF_CACHE_VOLUME)) {
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
                BuildOutput.step("No CoW storage pool found.");
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
            found |= cleanDnfCacheFromPool(incus, pool, dryRun, confirmation(skipConfirmation));

            if (!found) {
                BuildOutput.step("Nothing to clean — no reclaimable artifacts found.");
            }
            if (!baseImages && !images.base().isEmpty()) {
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

            groupOfSteps("Failed builds (" + failed.size() + ")", failed);

            if (dryRun) {
                BuildOutput.step("Would delete " + failed.size() + " failed build instance(s).");
                return true;
            }

            if (!confirmDestructive("Delete " + failed.size() + " failed build instance(s)?", skip, "--skip-confirmation")) {
                return true;
            }

            deleteFailedBuilds(incus, failed, name -> BuildOutput.step("Deleted " + name),
                    (name, e) -> BuildOutput.warn("Could not delete " + name + ": " + failure(e)));
            return true;
        }

        private boolean cleanImages(IncusClient incus, List<IncusClient.ImageInfo> images, String title,
                                    String noun, boolean dryRun, boolean skip) {
            if (images.isEmpty()) return false;

            groupOfSteps(title + " (" + images.size() + ", ~" + formatSize(totalSize(images)) + ")", images.stream()
                    .map(image -> image.label() + " (" + formatSize(image.size()) + ")").toList());

            if (dryRun) {
                BuildOutput.step("Would delete " + images.size() + " " + noun + ".");
                return true;
            }

            if (!confirmDestructive("Delete " + images.size() + " " + noun + "?", skip, "--skip-confirmation")) {
                return true;
            }

            for (var image : images) {
                try {
                    deleteImage(incus, image);
                    BuildOutput.step("Deleted " + image.label());
                } catch (Exception e) {
                    BuildOutput.warn("Could not delete image " + image.label() + ": " + failure(e));
                }
            }
            return true;
        }

        /** List the DNF cache volume and delete it once confirmed, like every other category here. */
        static boolean cleanDnfCacheFromPool(IncusClient incus, String pool, boolean dryRun,
                                             Predicate<String> confirm) {
            DnfCacheVolume volume;
            try {
                volume = DnfCacheVolume.find(incus, pool);
            } catch (IllegalStateException e) {
                // The pool listing just answered, so this is rare; the other categories still stand
                BuildOutput.warn(failure(e));
                return false;
            }
            if (volume == null) return false;

            groupOfSteps("DNF cache volume", List.of(volume.label()));
            if (dryRun) {
                BuildOutput.step("Would delete the DNF cache volume; the next build downloads its packages again.");
                return true;
            }
            if (confirm.test("Delete the DNF cache volume? The next build downloads its packages again.")) {
                volume.delete();
            }
            return true;
        }
    }
}
