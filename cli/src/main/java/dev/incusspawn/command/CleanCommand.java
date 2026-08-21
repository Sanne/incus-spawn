package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

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

            var usage = incus.getStoragePoolUsage(pool);
            System.out.println(usage);
            System.out.println();

            boolean found = false;

            found |= cleanFailedBuilds(incus, dryRun, skipConfirmation);
            found |= cleanUnusedImages(incus, dryRun, skipConfirmation);
            found |= cleanDnfCacheFromPool(incus, pool, dryRun);

            if (!found) {
                System.out.println("Nothing to clean — no reclaimable artifacts found on the pool.");
            }

            var newUsage = incus.getStoragePoolUsage(pool);
            if (found && !dryRun) {
                System.out.println();
                System.out.println(newUsage);
            }

            return CommandResult.SUCCESS;
        }

        private boolean cleanFailedBuilds(IncusClient incus, boolean dryRun, boolean skip) {
            var failed = new ArrayList<String>();
            for (var inst : incus.list()) {
                var name = inst.get("name");
                if (name.endsWith("-failed-build")) {
                    failed.add(name);
                }
            }
            if (failed.isEmpty()) return false;

            System.out.println("Failed build instances (" + failed.size() + "):");
            for (var name : failed) {
                System.out.println("  " + name);
            }

            if (dryRun) {
                System.out.println("Would delete " + failed.size() + " failed build instance(s).");
                System.out.println();
                return true;
            }

            if (!confirm("Delete " + failed.size() + " failed build instance(s)?", skip)) {
                System.out.println();
                return true;
            }

            for (var name : failed) {
                try {
                    incus.delete(name, true);
                    System.out.println("  Deleted " + name);
                } catch (Exception e) {
                    System.err.println("  Warning: could not delete " + name + ": " + e.getMessage());
                }
            }
            System.out.println();
            return true;
        }

        private boolean cleanUnusedImages(IncusClient incus, boolean dryRun, boolean skip) {
            var knownAliases = new HashSet<String>();
            for (var def : ImageDef.loadAll().values()) {
                var img = def.getImage();
                if (img != null && !img.contains(":")) {
                    knownAliases.add(img);
                }
            }

            var unused = new ArrayList<IncusClient.ImageInfo>();
            for (var image : incus.listImages()) {
                boolean inUse = image.aliases().stream().anyMatch(knownAliases::contains);
                if (!inUse) {
                    unused.add(image);
                }
            }
            if (unused.isEmpty()) return false;

            long totalSize = unused.stream().mapToLong(IncusClient.ImageInfo::size).sum();
            System.out.println("Unused base images (" + unused.size() + ", ~" + formatSize(totalSize) + "):");
            for (var image : unused) {
                System.out.println("  " + image.label() + " (" + formatSize(image.size()) + ")");
            }

            if (dryRun) {
                System.out.println("Would delete " + unused.size() + " unused image(s).");
                System.out.println();
                return true;
            }

            if (!confirm("Delete " + unused.size() + " unused image(s)?", skip)) {
                System.out.println();
                return true;
            }

            for (var image : unused) {
                try {
                    for (var alias : image.aliases()) {
                        incus.deleteImageAlias(alias);
                    }
                    incus.deleteImage(image.fingerprint());
                    System.out.println("  Deleted " + image.label());
                } catch (Exception e) {
                    System.err.println("  Warning: could not delete image: " + e.getMessage());
                }
            }
            System.out.println();
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
