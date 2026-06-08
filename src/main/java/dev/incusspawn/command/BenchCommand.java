package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.IncusClient;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

/**
 * Benchmark Incus API call latency. Useful for comparing JVM vs native image
 * and diagnosing transport overhead (Unix socket vs HTTPS).
 *
 * Usage: isx bench
 */
@CommandDefinition(name = "bench", description = "Benchmark Incus API latency", generateHelp = true)
public class BenchCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        if (!InitCommand.requireIncusHost()) return CommandResult.valueOf(1);
        var incus = RuntimeServices.incus();
        var error = incus.checkConnectivity();
        if (error != null) {
            System.err.println("Incus not reachable: " + error);
            return CommandResult.valueOf(1);
        }

        System.out.println("Benchmarking Incus API latency...\n");

        // Warmup (first call includes SSL handshake)
        long warmup = time(() -> incus.exists("nonexistent"));
        System.out.printf("  First call (includes TLS handshake): %dms%n", warmup);

        // exists() — lightweight GET
        long[] times = new long[20];
        for (int i = 0; i < times.length; i++) {
            times[i] = time(() -> incus.exists("tpl-minimal"));
        }
        printStats("exists()", times);

        // list() — heavier GET with recursion
        times = new long[10];
        for (int i = 0; i < times.length; i++) {
            times[i] = time(() -> incus.list());
        }
        printStats("list()", times);

        // configGet() — single key lookup
        times = new long[20];
        for (int i = 0; i < times.length; i++) {
            times[i] = time(() -> {
                try { incus.configGet("tpl-minimal", "user.incus-spawn.type"); }
                catch (Exception ignored) {}
            });
        }
        printStats("configGet()", times);

        // exec (WebSocket) — measures the full WebSocket lifecycle
        if (incus.exists("tpl-minimal")) {
            boolean wasStopped = false;
            try {
                incus.start("tpl-minimal");
            } catch (Exception e) {
                // already running or can't start — skip exec bench
            }
            if (incus.pollUntilReady("tpl-minimal", 10, "echo", "ready")) {
                // Warmup — let Incus settle after container start
                for (int i = 0; i < 3; i++) incus.shellExec("tpl-minimal", "echo", "warmup");

                times = new long[20];
                for (int i = 0; i < times.length; i++) {
                    times[i] = time(() -> incus.shellExec("tpl-minimal", "echo", "bench"));
                }
                printStats("exec+output(ws)", times);
            }
            try { incus.stop("tpl-minimal"); } catch (Exception ignored) {}
        }

        return CommandResult.SUCCESS;
    }

    private static long time(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void printStats(String label, long[] times) {
        long total = 0, max = 0, min = Long.MAX_VALUE;
        for (long t : times) {
            total += t;
            if (t > max) max = t;
            if (t < min) min = t;
        }
        System.out.printf("  %-14s %3dms avg, %3dms min, %3dms max  (%d calls)%n",
                label, total / times.length, min, max, times.length);
    }
}
