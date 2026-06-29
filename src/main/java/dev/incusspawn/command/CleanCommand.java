package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
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
import java.util.List;

@CommandDefinition(
        name = "clean",
        description = "Remove cached data, state, or configuration",
        generateHelp = true,
        groupCommands = {
                CleanCommand.Cache.class,
                CleanCommand.State.class,
                CleanCommand.Config.class,
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
        System.out.print(prompt + " (y/N): ");
        if (!console.readLine().strip().equalsIgnoreCase("y")) {
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
        if (!Environment.isMacOS()) return;
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
}
