import dev.incusspawn.config.GitCacheConfig;
import dev.incusspawn.git.GitPreparation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;

/** Run with Java 25 and common/target/classes on the classpath; uses a fresh, automatically removed cache. */
public class GitPreparationBench {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: GitPreparationBench.java <remote-url> [matching-checkout]");
        }
        var cache = Files.createTempDirectory("isx-git-bench-");
        try {
            var preparation = new GitPreparation(cache, GitCacheConfig.DEFAULT);
            var request = new GitPreparation.Request(args[0], null, args.length == 2 ? Path.of(args[1]) : null);
            for (int trial = 0; trial < 3; trial++) {
                long start = System.nanoTime();
                try (var result = preparation.prepare(request, phase -> {})) {
                    var phases = result.timingsMillis().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                            .map(e -> "\"" + e.getKey() + "\":" + e.getValue()).collect(Collectors.joining(","));
                    System.out.println("{\"trial\":" + trial + ",\"strategy\":\"" + result.strategy()
                            + "\",\"total_ms\":" + (System.nanoTime() - start) / 1_000_000
                            + ",\"phases_ms\":{" + phases + "}}");
                }
            }
        } finally {
            try (var paths = Files.walk(cache)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
