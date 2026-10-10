package dev.incusspawn.util;

import dev.incusspawn.Platform;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Best-effort CPU topology inspection. Single source of truth for CPU counts
 * used to size resources (appliance VM vCPUs, container limits) and CPU/IO-bound
 * parallelism (e.g. concurrent git clones).
 */
public final class CpuInfo {

    private CpuInfo() {}

    /**
     * Total logical processors on the host. Reads {@code /proc/cpuinfo} (Linux)
     * or {@code sysctl hw.logicalcpu} (macOS) rather than
     * {@link Runtime#availableProcessors()}, which the native image pins via
     * {@code -R:ActiveProcessorCount} and so under-reports the real machine.
     * Always at least 1.
     */
    public static int logicalCores() {
        try {
            var cpuinfo = Files.readString(Path.of("/proc/cpuinfo"));
            int count = 0;
            for (var line : cpuinfo.split("\n")) {
                if (line.startsWith("processor")) count++;
            }
            if (count > 0) return count;
        } catch (IOException ignored) {}
        int sysctl = sysctlInt("hw.logicalcpu");
        if (sysctl > 0) return sysctl;
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Count of high-performance ("P"/big) logical cores, or 0 where the platform
     * does not expose the distinction (homogeneous CPU, Intel Mac, etc.). On
     * hybrid CPUs this excludes the slower efficiency cores.
     */
    public static int performanceCores() {
        try {
            if (Platform.isMacOS()) return macPerformanceCores();
            if (Platform.isLinux()) return linuxBigCores();
        } catch (Exception ignored) {
            // fall through
        }
        return 0;
    }

    /**
     * Parallelism bound for CPU/IO-bound fan-out: the performance-core count when
     * it can be determined, otherwise all logical cores. Always at least 1.
     */
    public static int highPerfCores() {
        int p = performanceCores();
        return Math.max(1, p > 0 ? p : logicalCores());
    }

    // On Apple Silicon perflevel0 is the performance cluster; on Intel Macs the
    // key is absent and sysctl fails, so this returns 0 (use all cores). P-cores
    // have a single thread each, so logicalcpu == physicalcpu here.
    private static int macPerformanceCores() {
        return sysctlInt("hw.perflevel0.logicalcpu");
    }

    private static int sysctlInt(String key) {
        var values = sysctlInts(key);
        return values.length == 1 ? values[0] : 0;
    }

    /**
     * The integer values of {@code keys}, in order, from one {@code sysctl} process; an empty
     * array when it fails, any key is unknown (sysctl then prints fewer lines), or a value does
     * not parse.
     */
    private static int[] sysctlInts(String... keys) {
        try {
            var command = new ArrayList<String>(List.of("sysctl", "-n"));
            command.addAll(List.of(keys));
            var pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var process = pb.start();
            var lines = new String(process.getInputStream().readAllBytes()).strip().split("\\s*\n\\s*");
            if (process.waitFor() != 0 || lines.length != keys.length) return new int[0];
            var values = new int[keys.length];
            for (int i = 0; i < keys.length; i++) values[i] = Integer.parseInt(lines[i]);
            return values;
        } catch (IOException | NumberFormatException e) {
            return new int[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new int[0];
        }
    }

    /** The sysfs root on Linux. */
    private static final String SYSFS = "/sys";

    /** The scan of the real sysfs once made; null until then. Set at run time, never in a static initializer. */
    private static volatile Scan hostScan;

    /** The scan of this host's sysfs, made once per process: the topology does not change while it runs. */
    private static Scan hostScan() {
        var scan = hostScan;
        if (scan == null) hostScan = scan = scan(Path.of(SYSFS));
        return scan;
    }

    // On a hybrid CPU the big cores are the top tier (see Scan); a homogeneous CPU has no tiers,
    // so this returns 0 (use all cores).
    private static int linuxBigCores() {
        return bigLogicalCpus(hostScan());
    }

    /** {@link #linuxBigCores()} from a sysfs root. */
    static int linuxBigCores(Path root) {
        return bigLogicalCpus(scan(root));
    }

    private static int bigLogicalCpus(Scan scan) {
        return scan.hybrid() ? scan.fastestLogical() : 0;
    }

    /** {@link #hybridTopTierCores()} on macOS once known; -1 until then. */
    private static volatile int macHybridTopTierCores = -1;

    /**
     * On a hybrid host -- more than one tier of cores -- the physical cores of the top tier, a
     * core's SMT threads counted once; 0 on a host with one tier, or whose tiers cannot be told
     * apart. Read once per process: the topology does not change while it runs.
     */
    public static int hybridTopTierCores() {
        try {
            if (Platform.isMacOS()) {
                int known = macHybridTopTierCores;
                if (known >= 0) return known;
                // Apple Silicon: perflevel0 is the performance cluster, whose cores have one
                // thread each. One process for both keys; a failed read is not kept, so the next
                // call asks again rather than taking the host for one tier
                var levels = sysctlInts("hw.nperflevels", "hw.perflevel0.logicalcpu");
                if (levels.length == 0) return 0;
                return macHybridTopTierCores = levels[0] > 1 ? Math.max(0, levels[1]) : 0;
            }
            if (Platform.isLinux()) return hybridTopTierCores(hostScan());
        } catch (Exception ignored) {
            // fall through
        }
        return 0;
    }

    /** {@link #hybridTopTierCores()} from a sysfs root. */
    static int hybridTopTierCores(Path root) {
        return hybridTopTierCores(scan(root));
    }

    private static int hybridTopTierCores(Scan scan) {
        return scan.hybrid() ? physicalCores(scan) : 0;
    }

    /**
     * The top tier's physical cores from a sysfs root, hybrid or not, or 0 when the scan cannot
     * be trusted (an unreadable capacity, a capacity on some CPUs but not others, a top-tier CPU
     * without a readable core list). Production
     * code asks {@link #hybridTopTierCores()}; this is where tests check the tier counting on
     * hosts with one tier, which that reports as 0.
     */
    static int fastestPhysicalCores(Path root) {
        return physicalCores(scan(root));
    }

    private static int physicalCores(Scan scan) {
        return scan.fastestCores() == null ? 0 : scan.fastestCores().size();
    }

    /**
     * Where a tier value tells CPUs apart, the top tier is every CPU at least this share of the
     * highest: a dual-CCD X3D's slower CCD and the favoured cores of an Intel or AMD part are one
     * tier with the rest, while efficiency cores are far below.
     */
    private static final double TOP_TIER_SHARE = 0.8;

    /**
     * The online CPUs of the host's top tier: how many logical CPUs, and their distinct physical
     * cores (each a {@code core_cpus_list}), null when the scan cannot be trusted. {@code hybrid} says whether
     * any online CPU was left out.
     *
     * <p>The tier comes from the first source that tells CPUs apart:
     * <ol>
     * <li>the hybrid PMU's list of performance CPUs, {@code /sys/devices/cpu_core/cpus} (Intel,
     *     kernel 5.13+). Intel hybrids with Hyper-Threading need it: intel_pstate scales
     *     {@code cpu_capacity} only when SMT is impossible, so on 12th-14th gen every CPU, E-cores
     *     included, reads 1024. On Arrow Lake it is the only source that works: its E-cores reach
     *     about 80% of the P-cores' capacity and clock alike;</li>
     * <li>{@code cpu_capacity}, where the values differ (ARM big.LITTLE, Intel hybrids without
     *     SMT). A capacity file that exists but cannot be read or parsed makes the scan unknown;</li>
     * <li>{@code cpufreq/cpuinfo_max_freq}, where every CPU has one and they differ: AMD Zen 5 +
     *     Zen 5c hybrids, which have no {@code cpu_core} PMU and equal capacities, but whose
     *     compact cores top out near 65% of the others;</li>
     * <li>none: every online CPU is the top tier.</li>
     * </ol>
     * Within the last two, the top tier is every CPU within {@link #TOP_TIER_SHARE} of the highest
     * (on a three-tier ARM SoC, its prime cores). A capacity on some CPUs but not others makes the
     * tier unknown; a frequency missing on any CPU makes that source tell nothing.
     */
    private record Scan(boolean hybrid, int fastestLogical, Set<Set<Integer>> fastestCores) {
        static final Scan UNKNOWN = new Scan(false, 0, null);
    }

    /**
     * One online CPU; {@code capacity} null without a capacity file, {@code core} the CPUs that
     * share its physical core (null when unreadable), {@code dir} for what is read lazily.
     */
    private record Cpu(int id, Path dir, Long capacity, boolean badCapacity, Set<Integer> core) {
        /** The CPU's highest frequency, or null when cpufreq does not say. */
        Long maxFreq() {
            var text = readOrNull(dir.resolve("cpufreq").resolve("cpuinfo_max_freq"));
            try {
                return text == null ? null : Long.valueOf(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static Scan scan(Path root) {
        var base = root.resolve("devices").resolve("system").resolve("cpu");
        if (!Files.isDirectory(base)) return Scan.UNKNOWN;
        var online = new ArrayList<Cpu>();
        try (DirectoryStream<Path> cpus = Files.newDirectoryStream(base, "cpu[0-9]*")) {
            for (var cpu : cpus) {
                if ("0".equals(readOrNull(cpu.resolve("online")))) continue;
                Long capacity = null;
                boolean badCapacity = false;
                var capFile = cpu.resolve("cpu_capacity");
                if (Files.exists(capFile)) {
                    try {
                        capacity = Long.valueOf(readOrNull(capFile));
                    } catch (NumberFormatException e) {
                        badCapacity = true; // also an unreadable file: readOrNull gave null
                    }
                }
                // A physical core is the set of CPUs that share it, not its package and core id:
                // on device-tree Arm, core_id restarts in every cluster and the package is 0
                // throughout. core_cpus_list is 5.3+, thread_siblings_list the older name
                var topology = cpu.resolve("topology");
                var core = parseCpuList(readOrNull(topology.resolve("core_cpus_list")));
                if (core == null) core = parseCpuList(readOrNull(topology.resolve("thread_siblings_list")));
                online.add(new Cpu(Integer.parseInt(cpu.getFileName().toString().substring(3)), cpu,
                        capacity, badCapacity, core));
            }
        } catch (IOException | NumberFormatException e) {
            return Scan.UNKNOWN;
        }

        var top = topTier(root, online);
        if (top == null || top.isEmpty()) return Scan.UNKNOWN;
        return new Scan(top.size() < online.size(), top.size(), distinctCores(top));
    }

    /**
     * The physical cores of {@code top}, one entry per core, or null when a CPU's core list is
     * missing: its core cannot be told from the others.
     */
    private static Set<Set<Integer>> distinctCores(List<Cpu> top) {
        var cores = new HashSet<Set<Integer>>();
        for (var cpu : top) {
            if (cpu.core() == null) return null;
            cores.add(cpu.core());
        }
        return cores;
    }

    /** The online CPUs of the top tier, or null when it cannot be told; see {@link Scan}. */
    private static List<Cpu> topTier(Path root, List<Cpu> online) {
        var pCores = parseCpuList(readOrNull(root.resolve("devices").resolve("cpu_core").resolve("cpus")));
        if (pCores != null) {
            var listed = online.stream().filter(cpu -> pCores.contains(cpu.id())).toList();
            if (!listed.isEmpty()) return listed;
        }
        if (online.stream().anyMatch(Cpu::badCapacity)) return null;
        // A capacity on some CPUs but not others: unknown rather than a lower count (the VM default
        // then follows the one-tier rule), without asking cpufreq
        long withCapacity = online.stream().filter(cpu -> cpu.capacity() != null).count();
        if (withCapacity > 0 && withCapacity < online.size()) return null;
        if (withCapacity > 0) {
            var byCapacity = tierBy(online.stream().collect(Collectors.toMap(cpu -> cpu, Cpu::capacity)));
            if (byCapacity != null) return byCapacity;
        }
        // A frequency missing anywhere tells nothing
        var freqs = new HashMap<Cpu, Long>();
        for (var cpu : online) {
            var freq = cpu.maxFreq();
            if (freq == null) return online;
            freqs.put(cpu, freq);
        }
        var byFrequency = tierBy(freqs);
        return byFrequency != null ? byFrequency : online;
    }

    /**
     * The CPUs whose value is at least {@link #TOP_TIER_SHARE} of the highest; null when all
     * values are equal.
     */
    private static List<Cpu> tierBy(Map<Cpu, Long> values) {
        var stats = values.values().stream().mapToLong(Long::longValue).summaryStatistics();
        if (stats.getMin() == stats.getMax()) return null;
        return values.entrySet().stream().filter(e -> e.getValue() >= stats.getMax() * TOP_TIER_SHARE)
                .map(Map.Entry::getKey).toList();
    }

    /**
     * A sysfs CPU list ({@code 0-3,8,10-11}) as CPU numbers; null for none or one that does not
     * parse.
     */
    /** Linux's highest CPU number (NR_CPUS is at most 8192); a list beyond it is not a CPU list. */
    private static final int MAX_CPU_ID = 8191;

    static Set<Integer> parseCpuList(String list) {
        if (list == null) return null;
        var cpus = new HashSet<Integer>();
        try {
            for (var part : list.split(",")) {
                var range = part.strip().split("-", 2);
                int from = Integer.parseInt(range[0]);
                int to = range.length == 2 ? Integer.parseInt(range[1]) : from;
                if (from < 0 || to < from || to > MAX_CPU_ID) return null;
                for (int n = from; n <= to; n++) cpus.add(n);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return cpus;
    }

    /** The stripped content of a small sysfs file, or null when it cannot be read. */
    private static String readOrNull(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return null;
        }
    }
}
