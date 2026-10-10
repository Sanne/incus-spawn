package dev.incusspawn.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CpuInfoTest {

    @Test
    void logicalCoresIsAtLeastOne() {
        assertTrue(CpuInfo.logicalCores() >= 1, "must always report at least one core");
    }

    @Test
    void performanceCoresIsNonNegative() {
        // 0 is the "cannot determine" sentinel (homogeneous CPU / Intel Mac).
        assertTrue(CpuInfo.performanceCores() >= 0);
    }

    @Test
    void performanceCoresNeverExceedsLogicalCores() {
        int p = CpuInfo.performanceCores();
        if (p > 0) {
            assertTrue(p <= CpuInfo.logicalCores(),
                    "P-cores are a subset of all logical cores");
        }
    }

    @Test
    void highPerfCoresIsAtLeastOne() {
        assertTrue(CpuInfo.highPerfCores() >= 1, "must always report at least one core");
    }

    @Test
    void highPerfCoresDoesNotExceedLogicalCores() {
        // P-cores are a subset of all logical cores; the fallback returns exactly
        // the logical count, so highPerfCores can never be larger. Compared against
        // logicalCores() (not availableProcessors(), which the native image caps).
        assertTrue(CpuInfo.highPerfCores() <= CpuInfo.logicalCores(),
                "high-performance cores cannot exceed total logical cores");
    }

    /** A fake /sys root. */
    @TempDir
    Path sys;

    private Path cpuDir() {
        return sys.resolve("devices").resolve("system").resolve("cpu");
    }

    /** The CPUs sharing each (package, core) of the x86-style fixtures, for core_cpus_list. */
    private final Map<String, SortedSet<Integer>> threadsOfCore = new HashMap<>();

    /**
     * One logical CPU in a fake /sys/devices/system/cpu, x86-style: package and core ids
     * identify its physical core, and core_cpus_list names every CPU given the same pair so far.
     */
    private void cpu(int n, int pkg, int core, Integer capacity) throws IOException {
        var threads = threadsOfCore.computeIfAbsent(pkg + "/" + core, k -> new TreeSet<>());
        threads.add(n);
        var list = threads.stream().map(String::valueOf).collect(Collectors.joining(","));
        writeTopology(n, pkg, core, list, capacity);
        for (int sibling : threads) {
            Files.writeString(cpuDir().resolve("cpu" + sibling).resolve("topology").resolve("core_cpus_list"), list + "\n");
        }
    }

    /** One logical CPU's topology and optional capacity, its core list as given. */
    private void writeTopology(int n, int pkg, int core, String coreCpus, Integer capacity) throws IOException {
        var cpu = cpuDir().resolve("cpu" + n);
        var topology = Files.createDirectories(cpu.resolve("topology"));
        Files.writeString(topology.resolve("physical_package_id"), pkg + "\n");
        Files.writeString(topology.resolve("core_id"), core + "\n");
        Files.writeString(topology.resolve("core_cpus_list"), coreCpus + "\n");
        if (capacity != null) Files.writeString(cpu.resolve("cpu_capacity"), capacity + "\n");
    }

    /** CPU {@code n}'s highest frequency, in kHz, as cpufreq reports it. */
    private void maxFreq(int n, int khz) throws IOException {
        var cpufreq = Files.createDirectories(cpuDir().resolve("cpu" + n).resolve("cpufreq"));
        Files.writeString(cpufreq.resolve("cpuinfo_max_freq"), khz + "\n");
    }

    /** The CPU list an Intel hybrid PMU publishes, e.g. /sys/devices/cpu_core/cpus. */
    private void pmuCpus(String pmu, String list) throws IOException {
        var dir = Files.createDirectories(sys.resolve("devices").resolve(pmu));
        Files.writeString(dir.resolve("cpus"), list + "\n");
    }

    /**
     * An i7-12700H: 6 P-cores with Hyper-Threading (cpu0-11, core ids 0, 4, .. 20) and 8 E-cores
     * (cpu12-19, core ids 24-31). intel_pstate leaves every cpu_capacity at 1024 when SMT is
     * possible, so capacity cannot tell them apart.
     */
    private void i7_12700H() throws IOException {
        for (int n = 0; n < 12; n++) cpu(n, 0, (n / 2) * 4, 1024);
        for (int n = 12; n < 20; n++) cpu(n, 0, 24 + (n - 12), 1024);
    }

    @Test
    void anIntelHybridWithSmtCountsItsPerformanceCoresFromThePmu() throws IOException {
        i7_12700H();
        pmuCpus("cpu_core", "0-11");
        pmuCpus("cpu_atom", "12-19"); // not read; as the kernel publishes it
        assertEquals(6, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void anIntelHybridWithSmtFallsBackToTheMaxFrequency() throws IOException {
        // No hybrid PMU listing (older kernel): the P-cores' 4.7 GHz against the E-cores' 3.5
        i7_12700H();
        for (int n = 0; n < 12; n++) maxFreq(n, 4_700_000);
        for (int n = 12; n < 20; n++) maxFreq(n, 3_500_000);
        assertEquals(6, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void favouredCoresByFrequencyDoNotHideTheOtherPerformanceCores() throws IOException {
        // Turbo Boost Max 3.0: two P-cores at 4.8 GHz, the other four at 4.6
        i7_12700H();
        for (int n = 0; n < 12; n++) maxFreq(n, n < 4 ? 4_800_000 : 4_600_000);
        for (int n = 12; n < 20; n++) maxFreq(n, 3_500_000);
        assertEquals(6, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void anAmdZen5cHybridCountsItsFullCoresByFrequency() throws IOException {
        // Ryzen AI 9 HX 370: 4 Zen 5 and 8 Zen 5c cores, all with SMT, equal capacities, no
        // cpu_core PMU; the Zen 5 cores reach 5.1 GHz, the Zen 5c 3.3
        for (int n = 0; n < 24; n++) {
            cpu(n, 0, n / 2, 1024);
            maxFreq(n, n < 8 ? 5_100_000 : 3_300_000);
        }
        assertEquals(4, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void anX3dPartsSlowerCcdIsTheSameTier() throws IOException {
        // A dual-CCD X3D: the V-cache CCD tops out at 5.25 GHz against 5.7 (92%), one tier
        for (int n = 0; n < 32; n++) {
            cpu(n, 0, n / 2, 1024);
            maxFreq(n, n < 16 ? 5_250_000 : 5_700_000);
        }
        assertEquals(16, CpuInfo.fastestPhysicalCores(sys));
    }

    @Test
    void aHybridNothingTellsApartCountsEveryPhysicalCore() throws IOException {
        // No PMU listing, no cpufreq, equal capacities: the kinds cannot be told apart
        i7_12700H();
        assertEquals(14, CpuInfo.fastestPhysicalCores(sys));
    }

    @Test
    void fastestPhysicalCoresCountsSmtSiblingsOnce() throws IOException {
        // 4 cores with 2 threads each, no capacity files: a homogeneous CPU
        for (int n = 0; n < 8; n++) cpu(n, 0, n % 4, null);
        assertEquals(4, CpuInfo.fastestPhysicalCores(sys));
        // One tier, so nothing beyond host CPUs - 2 limits a VM there
        assertEquals(0, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void aHybridByCapacityCountsOnlyItsBigCores() throws IOException {
        // 2 P-cores with 2 threads each (capacity 1024), 4 E-cores with one (capacity 512)
        cpu(0, 0, 0, 1024);
        cpu(1, 0, 0, 1024);
        cpu(2, 0, 4, 1024);
        cpu(3, 0, 4, 1024);
        for (int n = 4; n < 8; n++) cpu(n, 0, 8 + n, 512);
        assertEquals(2, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void favouredPerformanceCoresDoNotHideTheOtherPerformanceCores() throws IOException {
        // x86 hybrid: two favoured P-cores at 1024, two more at 990, four E-cores at 600
        cpu(0, 0, 0, 1024);
        cpu(1, 0, 1, 1024);
        cpu(2, 0, 2, 990);
        cpu(3, 0, 3, 990);
        for (int n = 4; n < 8; n++) cpu(n, 0, 8 + n, 600);
        assertEquals(4, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void offlineCpusAreNotCounted() throws IOException {
        for (int n = 0; n < 4; n++) cpu(n, 0, n, null);
        Files.writeString(cpuDir().resolve("cpu3").resolve("online"), "0\n");
        Files.writeString(cpuDir().resolve("cpu2").resolve("online"), "1\n");
        assertEquals(3, CpuInfo.fastestPhysicalCores(sys));
    }

    @Test
    void aFastestCpuWithoutTopologyMakesTheCountUnknown() throws IOException {
        cpu(0, 0, 0, null);
        cpu(1, 0, 1, null);
        Files.createDirectories(cpuDir().resolve("cpu2"));
        assertEquals(0, CpuInfo.fastestPhysicalCores(sys), "a partial count would cap a VM too low");
    }

    @Test
    void anUnreadableCapacityMakesTheCountUnknown() throws IOException {
        cpu(0, 0, 0, 1024);
        cpu(1, 0, 1, 1024);
        Files.writeString(cpuDir().resolve("cpu1").resolve("cpu_capacity"), "garbage\n");
        assertEquals(0, CpuInfo.fastestPhysicalCores(sys), "a partial count would cap a VM too low");
    }

    @Test
    void aMalformedCapacityDoesNotOverruleThePmu() throws IOException {
        i7_12700H();
        pmuCpus("cpu_core", "0-11");
        Files.writeString(cpuDir().resolve("cpu15").resolve("cpu_capacity"), "garbage\n");
        assertEquals(6, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void aPmuListingNoOnlineCpuFallsThrough() throws IOException {
        // A listing that names none of the online CPUs says nothing about them
        cpu(0, 0, 0, 1024);
        cpu(1, 0, 1, 1024);
        cpu(2, 0, 2, 512);
        pmuCpus("cpu_core", "8-11");
        assertEquals(2, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void aCapacityMissingOnOneCpuMakesTheTierUnknown() throws IOException {
        // One CPU short of a cpu_capacity file next to CPUs with one: not a lower count, unknown
        for (int n = 0; n < 8; n++) cpu(n, 0, n % 4, n == 7 ? null : 1024);
        assertEquals(0, CpuInfo.fastestPhysicalCores(sys));
        assertEquals(0, CpuInfo.hybridTopTierCores(sys));
        cpu(8, 0, 4, 512);
        assertEquals(0, CpuInfo.hybridTopTierCores(sys), "a hybrid too");
    }

    /**
     * An RK3588 under Linux 6.x device tree: 4 Cortex-A55 (cpu0-3, cpu_capacity 414) and two
     * clusters of 2 Cortex-A76 (cpu4-5, cpu6-7, 1024), no SMT. core_id restarts at 0 in each
     * cluster and physical_package_id is 0 everywhere, so (package, core) is not a core's
     * identity; core_cpus_list, each CPU alone, is.
     */
    private void rk3588() throws IOException {
        int[] coreIds = {0, 1, 2, 3, 0, 1, 0, 1};
        for (int n = 0; n < 8; n++) writeTopology(n, 0, coreIds[n], String.valueOf(n), n < 4 ? 414 : 1024);
    }

    @Test
    void anRk3588CountsItsFourBigCores() throws IOException {
        rk3588();
        assertEquals(4, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void olderKernelsThreadSiblingsListServesTheSame() throws IOException {
        rk3588();
        for (int n = 0; n < 8; n++) {
            var topology = cpuDir().resolve("cpu" + n).resolve("topology");
            Files.move(topology.resolve("core_cpus_list"), topology.resolve("thread_siblings_list"));
        }
        assertEquals(4, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void aHybridWithoutTopologyIsTreatedAsHomogeneous() throws IOException {
        // The PMU names the P-cores, but one has no readable topology: its core count is
        // unknown, and unknown follows the one-tier rule
        i7_12700H();
        pmuCpus("cpu_core", "0-11");
        Files.delete(cpuDir().resolve("cpu0").resolve("topology").resolve("core_cpus_list"));
        assertEquals(0, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void anUnparseableFrequencyTellsNothing() throws IOException {
        i7_12700H();
        for (int n = 0; n < 20; n++) maxFreq(n, n < 12 ? 4_700_000 : 3_500_000);
        Files.writeString(cpuDir().resolve("cpu19").resolve("cpufreq").resolve("cpuinfo_max_freq"), "?\n");
        assertEquals(14, CpuInfo.fastestPhysicalCores(sys), "no tier source left: every core");
    }

    @Test
    void aThreeTierArmSocCountsItsPrimeCores() throws IOException {
        // 1 prime core (1024), 4 middle (700), 4 little (300): the highest tier is the prime core
        cpu(0, 0, 0, 1024);
        for (int n = 1; n < 5; n++) cpu(n, 0, n, 700);
        for (int n = 5; n < 9; n++) cpu(n, 0, n, 300);
        assertEquals(1, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void bigLogicalCpusAreTheTopTiersThreadsOnAHybridOnly() throws IOException {
        i7_12700H();
        pmuCpus("cpu_core", "0-11");
        assertEquals(12, CpuInfo.linuxBigCores(sys), "the P-cores' 12 threads");
    }

    @Test
    void aHomogeneousCpuHasNoBigCores() throws IOException {
        for (int n = 0; n < 8; n++) cpu(n, 0, n % 4, 1024);
        assertEquals(0, CpuInfo.linuxBigCores(sys), "0 means use every core");
    }

    @Test
    void oneTierBySpreadIsNotHybrid() throws IOException {
        // A dual-CCD X3D: its slower CCD is within 80%, one tier
        for (int n = 0; n < 32; n++) {
            cpu(n, 0, n / 2, 1024);
            maxFreq(n, n < 16 ? 5_250_000 : 5_700_000);
        }
        assertEquals(0, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void anUndetectableHybridIsTreatedAsHomogeneous() throws IOException {
        // An i7-12700H with nothing to tell its tiers apart, then with a malformed capacity
        i7_12700H();
        assertEquals(0, CpuInfo.hybridTopTierCores(sys));
        Files.writeString(cpuDir().resolve("cpu1").resolve("cpu_capacity"), "garbage\n");
        assertEquals(0, CpuInfo.hybridTopTierCores(sys));
    }

    @Test
    void fastestPhysicalCoresIsZeroWithoutATopology() {
        assertEquals(0, CpuInfo.fastestPhysicalCores(sys));
    }

    @Test
    void cpuListsParseRangesAndSingles() {
        assertEquals(Set.of(0, 1, 2, 3, 8, 10, 11), CpuInfo.parseCpuList("0-3,8,10-11"));
        assertNull(CpuInfo.parseCpuList(""));
        assertNull(CpuInfo.parseCpuList("0-x"));
        assertNull(CpuInfo.parseCpuList("0-2147483647"), "not a CPU list, and no endless loop");
        assertNull(CpuInfo.parseCpuList("5-2"));
    }
}
