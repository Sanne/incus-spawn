import dev.incusspawn.config.GitCacheConfig;
import dev.incusspawn.git.GitPreparation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;

/** Run with Java 25 and common/target/classes; --cache retains generations across remote updates. */
public class GitPreparationBench {
    public static void main(String[] args) throws Exception {
        boolean persistent = args.length > 0 && args[0].equals("--cache");
        int offset = persistent ? 2 : 0;
        if (args.length < offset + 1 || args.length > offset + 2) {
            throw new IllegalArgumentException("Usage: GitPreparationBench.java [--cache directory] <remote-url> [matching-checkout]");
        }
        var cache = persistent ? Path.of(args[1]) : Files.createTempDirectory("isx-git-bench-");
        try {
            var preparation = new GitPreparation(cache, GitCacheConfig.DEFAULT);
            var request = new GitPreparation.Request(args[offset], null,
                    args.length == offset + 2 ? Path.of(args[offset + 1]) : null);
            for (int trial = 0; trial < 3; trial++) {
                long start = System.nanoTime();
                try (var result = preparation.prepare(request, phase -> {})) {
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    var phases = result.timingsMillis().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                            .map(e -> "\"" + e.getKey() + "\":" + e.getValue()).collect(Collectors.joining(","));
                    long retained = 0;
                    var snapshots = cache.resolve("snapshots");
                    if (Files.isDirectory(snapshots)) {
                        try (var files = Files.walk(snapshots)) {
                            for (var file : files.filter(Files::isRegularFile).toList()) retained += Files.size(file);
                        }
                    }
                    System.out.println("{\"trial\":" + trial + ",\"strategy\":\"" + result.strategy()
                            + "\",\"total_ms\":" + elapsed
                            + ",\"retained_bytes\":" + retained + ",\"phases_ms\":{" + phases + "}}");
                }
            }
        } finally {
            if (!persistent) {
                try (var paths = Files.walk(cache)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }
}
