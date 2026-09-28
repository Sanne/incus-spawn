package dev.incusspawn.lifecycle;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins how many Incus round trips a whole branch costs, from preflight to its start, as
 * {@link InstanceLifecycleRequestBudgetTest} does for the steps inside it. Each is paid in
 * sequence before the user gets a prompt, and more over the macOS vsock tunnel.
 *
 * <p>The source and the new branch are each read once: every check and step works from what
 * that read returned. Budgets are exact, so they ratchet: above budget is a regression to fix;
 * below budget is an improvement, so lower the number here in the same change.
 */
@ExtendWith(TempHome.class)
class BranchFlowRequestBudgetTest {

    private static final String SOURCE = "tpl-dev";
    private static final String NAME = "dev-2";

    private Runnable originalRefresh;
    private java.util.function.Predicate<dev.incusspawn.incus.IncusClient> originalHealthCheck;

    @BeforeEach
    void setUp() throws Exception {
        originalRefresh = BranchFlow.proxyRefresh;
        originalHealthCheck = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyRefresh = () -> {};
        BranchFlow.proxyHealthCheck = incus -> true;
        writeConfig("github:\n  accounts:\n    work: { token: ghp_w }\n  default: work\n");
    }

    @AfterEach
    void tearDown() {
        BranchFlow.proxyRefresh = originalRefresh;
        BranchFlow.proxyHealthCheck = originalHealthCheck;
    }

    private static void writeConfig(String yaml) throws Exception {
        Files.createDirectories(SpawnConfig.configDir());
        Files.writeString(SpawnConfig.configDir().resolve("config.yaml"), yaml);
    }

    private static FakeIncusDaemon template() {
        return new FakeIncusDaemon().container(SOURCE,
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, SOURCE));
    }

    private static Map<String, ImageDef> defs() throws Exception {
        return Map.of(SOURCE, ImageDef.parseYaml("""
                name: tpl-dev
                tools: [gh]
                accounts:
                  github: work
                repos:
                  - url: https://github.com/example/project.git
                    path: /home/agentuser/project
                """));
    }

    private static BranchFlow.Request request(NetworkMode mode, boolean start) {
        return new BranchFlow.Request(SOURCE, NAME, false, false, mode,
                null, null, null, null, List.of(), start, Map.of());
    }

    private static void assertBudget(int expected, List<String> requests, String flow) {
        assertEquals(expected, requests.size(), () -> flow + " should make " + expected
                + " Incus request(s), made " + requests.size() + ":\n  "
                + String.join("\n  ", requests)
                + "\nMore is a latency regression; fewer is an improvement -- lower the budget.");
    }

    private static long reads(List<String> requests, String instance) {
        return requests.stream().filter(r -> r.equals("GET /1.0/instances/" + instance)).count();
    }

    @Test
    void anAirgappedBranch() throws Exception {
        var daemon = template();
        var incus = daemon.client();
        var preflight = BranchFlow.preflight(incus, request(NetworkMode.AIRGAP, false), defs());
        // Whether the name is free, and the source.
        assertBudget(2, daemon.requests(), "preflight (airgap)");

        daemon.clearRequests();
        BranchFlow.create(incus, preflight);
        // The pools, the copy and its wait, the branch, its one write.
        assertBudget(5, daemon.requests(), "create (airgap, not started)");
    }

    @Test
    void aNetworkedBranchThroughItsStart() throws Exception {
        var daemon = template().diesOnStart();
        var incus = daemon.client();
        var preflight = BranchFlow.preflight(incus, request(NetworkMode.FULL, true), defs());
        // Whether the name is free, the source, the bridge.
        assertBudget(3, daemon.requests(), "preflight (networked)");

        daemon.clearRequests();
        assertThrows(IncusException.class, () -> BranchFlow.create(incus, preflight),
                "the fake's instance dies on start, which ends the flow at the readiness wait");
        var requests = daemon.requests();
        var started = requests.indexOf("PUT /1.0/instances/" + NAME + "/state");
        // The pools, the copy and its wait, the branch, the address listing and the static
        // network config push, its one write; then the start and its wait. Nothing is read
        // between the write and the start.
        assertBudget(9, requests.subList(0, started + 2), "create (networked) through its start");
        assertEquals(0, reads(requests, SOURCE), "the source is read once, in preflight");
        assertEquals(1, reads(requests.subList(0, started), NAME), "the branch is read once before it starts");
    }

    @Test
    void addingGitRemotesReadsNothingMore() throws Exception {
        // With a host path configured, the template's repos are looked for in it: from the
        // definitions preflight already had, not by reading the branch and rescanning them.
        var hostDir = Files.createTempDirectory("isx-host");
        writeConfig("host-paths: ['" + hostDir + "']\n"
                + "github:\n  accounts:\n    work: { token: ghp_w }\n  default: work\n");
        var daemon = template();
        var incus = daemon.client();
        var preflight = BranchFlow.preflight(incus, request(NetworkMode.AIRGAP, false), defs());
        daemon.clearRequests();
        BranchFlow.create(incus, preflight);
        assertBudget(5, daemon.requests(), "create (airgap, not started, with a host path)");
    }

    @Test
    void aSourceThatIsGoneIsRefusedByTheReadThatFindsOut() throws Exception {
        var daemon = new FakeIncusDaemon();
        var e = assertThrows(BranchFlow.BranchException.class, () ->
                BranchFlow.preflight(daemon.client(), request(NetworkMode.AIRGAP, false), defs()));
        assertEquals("'" + SOURCE + "' does not exist.", e.getMessage());
        assertBudget(2, daemon.requests(), "preflight (source gone)");
    }
}
