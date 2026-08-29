package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

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

    static boolean confirm(String prompt, boolean skipConfirmation) {
        if (skipConfirmation) return true;
        var console = System.console();
        if (console == null) return true;
        if (!askConfirmation(console, prompt, false)) {
            System.out.println("Aborted.");
            return false;
        }
        return true;
    }

    static long dirSize(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        long[] size = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
        return size[0];
    }

    static int fileCount(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        int[] count = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    count[0]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
        return count[0];
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.println("Warning: could not delete " + file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    record DirInfo(Path path, long size, int files) {}

    static List<DirInfo> collectInfo(List<Path> dirs) {
        var result = new ArrayList<DirInfo>();
        for (var dir : dirs) {
            if (Files.isDirectory(dir)) {
                result.add(new DirInfo(dir, dirSize(dir), fileCount(dir)));
            }
        }
        return result;
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

    static CommandResult cleanDirs(List<Path> dirs, boolean dryRun, boolean skipConfirmation,
                                    String category) throws IOException {
        var infos = collectInfo(dirs);
        if (infos.isEmpty()) {
            System.out.println("Nothing to clean — no " + category + " data found.");
            return CommandResult.SUCCESS;
        }

        long total = infos.stream().mapToLong(i -> i.size).sum();
        int totalFiles = infos.stream().mapToInt(i -> i.files).sum();

        if (dryRun) {
            System.out.println("Would delete:");
            printSummary(infos);
            return CommandResult.SUCCESS;
        }

        System.out.println("Will delete:");
        printSummary(infos);
        System.out.println();

        if (!confirm("Proceed?", skipConfirmation)) return CommandResult.SUCCESS;

        for (var info : infos) {
            deleteDir(info.path);
        }
        System.out.println("Freed " + formatSize(total) + " from " + totalFiles + " files.");
        return CommandResult.SUCCESS;
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
            var result = cleanDirs(List.of(Environment.cacheDir()), dryRun, skipConfirmation, "cache");
            cleanDnfCacheVolume(dryRun);
            return result;
        }
    }

    @CommandDefinition(
            name = "state",
            description = "Remove VM state, logs, and appliance artifacts (~/.local/state/ and ~/.local/share/incus-spawn/)",
            generateHelp = true
    )
    public static class State extends BaseCommand {

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
            return cleanDirs(
                    List.of(Environment.vmStateDir(), Environment.dataDir()),
                    dryRun, skipConfirmation, "state");
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
            var dirs = List.of(Environment.configDir());
            var infos = collectInfo(dirs);
            if (infos.isEmpty()) {
                System.out.println("Nothing to clean — no configuration data found.");
                return CommandResult.SUCCESS;
            }

            long total = infos.stream().mapToLong(i -> i.size).sum();
            int totalFiles = infos.stream().mapToInt(i -> i.files).sum();

            if (dryRun) {
                System.out.println("Would delete:");
                printSummary(infos);
                return CommandResult.SUCCESS;
            }

            System.out.println("Will delete:");
            printSummary(infos);
            System.out.println();
            System.out.println("WARNING: This will permanently delete your SSH keys, CA certificate,");
            System.out.println("and configuration. You will need to run 'isx init' again and rebuild");
            System.out.println("all templates.");

            if (!confirm("Delete configuration?", skipConfirmation)) return CommandResult.SUCCESS;

            for (var info : infos) {
                deleteDir(info.path);
            }
            System.out.println("Freed " + formatSize(total) + " from " + totalFiles + " files.");
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "all",
            description = "Remove all incus-spawn data (cache, state, and configuration)",
            generateHelp = true
    )
    public static class All extends BaseCommand {

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
            var infos = collectInfo(dirs);
            if (infos.isEmpty()) {
                System.out.println("Nothing to clean — no incus-spawn data found.");
                return CommandResult.SUCCESS;
            }

            long total = infos.stream().mapToLong(i -> i.size).sum();
            int totalFiles = infos.stream().mapToInt(i -> i.files).sum();

            if (dryRun) {
                System.out.println("Would delete:");
                printSummary(infos);
                return CommandResult.SUCCESS;
            }

            System.out.println("Will delete ALL incus-spawn data:");
            printSummary(infos);
            System.out.println();
            System.out.println("WARNING: This includes your SSH keys, CA certificate, and configuration.");
            System.out.println("You will need to run 'isx init' again and rebuild all templates.");

            if (!confirm("Delete everything?", skipConfirmation)) return CommandResult.SUCCESS;

            for (var info : infos) {
                deleteDir(info.path);
            }
            System.out.println("Freed " + formatSize(total) + " from " + totalFiles + " files.");
            cleanDnfCacheVolume(dryRun);
            return CommandResult.SUCCESS;
        }
    }

    // -- shared pool-clean logic (used by both CLI and TUI) --

    record CleanScan(
            String poolName,
            IncusClient.PoolUsage usage,
            List<String> failedBuilds,
            List<IncusClient.ImageInfo> unusedImages,
            boolean dnfCacheExists
    ) {
        boolean hasAnything() {
            return !failedBuilds.isEmpty() || !unusedImages.isEmpty() || dnfCacheExists;
        }
        long unusedImagesBytes() {
            return unusedImages.stream().mapToLong(IncusClient.ImageInfo::size).sum();
        }
    }

    record CleanResult(
            String poolName,
            IncusClient.PoolUsage beforeUsage,
            IncusClient.PoolUsage afterUsage,
            int failedBuildsDeleted,
            int unusedImagesDeleted,
            boolean dnfCacheDeleted,
            List<String> warnings
    ) {
        boolean found() {
            return failedBuildsDeleted > 0 || unusedImagesDeleted > 0 || dnfCacheDeleted;
        }
    }

    static List<String> findFailedBuilds(IncusClient incus) {
        var result = new ArrayList<String>();
        for (var inst : incus.list()) {
            var name = inst.get("name");
            if (name.endsWith("-failed-build")) result.add(name);
        }
        return result;
    }

    static List<IncusClient.ImageInfo> findUnusedImages(IncusClient incus) {
        var knownAliases = new HashSet<String>();
        for (var def : ImageDef.loadAll().values()) {
            var img = def.getImage();
            if (img != null && !img.contains(":")) knownAliases.add(img);
        }
        var unused = new ArrayList<IncusClient.ImageInfo>();
        for (var image : incus.listImages()) {
            if (image.aliases().stream().noneMatch(knownAliases::contains)) unused.add(image);
        }
        return unused;
    }

    static CleanScan scanPool(IncusClient incus) {
        var pool = incus.findCowPool();
        if (pool == null) return null;

        var usage = incus.getPoolUsageBytes(pool);
        var failedBuilds = findFailedBuilds(incus);
        var unusedImages = findUnusedImages(incus);
        boolean dnfExists = false;
        try {
            dnfExists = incus.storageVolumeExists(pool, BuildCommand.DNF_CACHE_VOLUME);
        } catch (Exception ignored) {}
        return new CleanScan(pool, usage, failedBuilds, unusedImages, dnfExists);
    }

    static CleanResult cleanPool(IncusClient incus) {
        return cleanPool(incus, true, true, true);
    }

    static CleanResult cleanPool(IncusClient incus,
                                 boolean deleteFailedBuilds,
                                 boolean deleteUnusedImages,
                                 boolean deleteDnfCache) {
        var pool = incus.findCowPool();
        if (pool == null) return null;

        var beforeUsage = incus.getPoolUsageBytes(pool);
        var warnings = new ArrayList<String>();

        int failedDeleted = 0;
        if (deleteFailedBuilds) {
            for (var name : findFailedBuilds(incus)) {
                try {
                    incus.delete(name, true);
                    failedDeleted++;
                } catch (Exception e) {
                    warnings.add("Could not delete " + name + ": " + e.getMessage());
                }
            }
        }

        int imagesDeleted = 0;
        if (deleteUnusedImages) {
            for (var image : findUnusedImages(incus)) {
                try {
                    for (var alias : image.aliases()) {
                        incus.deleteImageAlias(alias);
                    }
                    incus.deleteImage(image.fingerprint());
                    imagesDeleted++;
                } catch (Exception e) {
                    warnings.add("Could not delete image: " + e.getMessage());
                }
            }
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

        boolean anyDeleted = failedDeleted > 0 || imagesDeleted > 0 || dnfDeleted;
        var afterUsage = anyDeleted ? incus.getPoolUsageBytes(pool) : beforeUsage;
        return new CleanResult(pool, beforeUsage, afterUsage,
                failedDeleted, imagesDeleted, dnfDeleted, warnings);
    }

    @CommandDefinition(
            name = "pool",
            description = "Reclaim space from the storage pool (failed builds, unused images, build caches)",
            generateHelp = true
    )
    public static class Pool extends BaseCommand {

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
            BuildOutput.note(usage);

            boolean found = false;

            found |= cleanFailedBuilds(incus, dryRun, skipConfirmation);
            found |= cleanUnusedImages(incus, dryRun, skipConfirmation);
            found |= cleanDnfCacheFromPool(incus, pool, dryRun);

            if (!found) {
                BuildOutput.step("Nothing to clean — no reclaimable artifacts found.");
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
                BuildOutput.note("Would delete " + failed.size() + " failed build instance(s).");
                return true;
            }

            if (!confirm(BuildOutput.STEP_INDENT + "Delete " + failed.size() + " failed build instance(s)?", skip)) {
                return true;
            }

            for (var name : failed) {
                try {
                    incus.delete(name, true);
                    BuildOutput.step("  Deleted " + name);
                } catch (Exception e) {
                    System.err.println(BuildOutput.STEP_INDENT + "  Warning: could not delete " + name + ": " + e.getMessage());
                }
            }
            return true;
        }

        private boolean cleanUnusedImages(IncusClient incus, boolean dryRun, boolean skip) {
            var unused = findUnusedImages(incus);
            if (unused.isEmpty()) return false;

            long totalSize = unused.stream().mapToLong(IncusClient.ImageInfo::size).sum();
            System.out.println();
            BuildOutput.step("Unused images (" + unused.size() + ", ~" + formatSize(totalSize) + "):");
            for (var image : unused) {
                BuildOutput.step("  " + image.label() + " (" + formatSize(image.size()) + ")");
            }

            if (dryRun) {
                BuildOutput.note("Would delete " + unused.size() + " unused image(s).");
                return true;
            }

            if (!confirm(BuildOutput.STEP_INDENT + "Delete " + unused.size() + " unused image(s)?", skip)) {
                return true;
            }

            for (var image : unused) {
                try {
                    for (var alias : image.aliases()) {
                        incus.deleteImageAlias(alias);
                    }
                    incus.deleteImage(image.fingerprint());
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
