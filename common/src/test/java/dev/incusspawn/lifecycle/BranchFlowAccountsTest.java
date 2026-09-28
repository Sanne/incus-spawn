package dev.incusspawn.lifecycle;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every branch, whichever front end asks for it, carries its account selection by the time the
 * proxy is told to re-read the instance list. Static IPs are handed out lowest-free, so a new
 * branch often reuses a destroyed instance's address; a proxy that re-read before the pins were
 * stamped, or was never told, would serve the old instance's account (#800).
 */
@ExtendWith(TempHome.class)
class BranchFlowAccountsTest {

    private static final String GITHUB = Metadata.accountKey("github");

    /** The branch's pin each time the proxy was signalled. */
    private final List<String> pinAtRefresh = new ArrayList<>();
    private FakeIncusDaemon daemon;
    private java.util.function.Consumer<String> originalRefresh;

    @BeforeEach
    void setUp() throws Exception {
        var configDir = SpawnConfig.configDir();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), """
                github:
                  accounts:
                    personal:
                      token: "ghp_personal"
                    work:
                      token: "ghp_work"
                    bot:
                      token: "ghp_bot"
                  default: personal
                """);
        originalRefresh = BranchFlow.proxyRefresh;
        BranchFlow.proxyRefresh = healthAddress -> pinAtRefresh.add(pin());
    }

    @AfterEach
    void tearDown() {
        BranchFlow.proxyRefresh = originalRefresh;
    }

    private static Map<String, ImageDef> templateWithAccount(String account) throws Exception {
        return Map.of("tpl-dev", ImageDef.parseYaml(
                "name: tpl-dev\naccounts:\n  github: " + account + "\n"));
    }

    /** The branch's github pin, or "" when it has none. */
    private String pin() {
        return daemon.instance("dev-2").path("config").path(GITHUB).asText("");
    }

    private static FakeIncusDaemon template() {
        return new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
    }

    private void branch(String source, Map<String, ImageDef> defs) {
        branch(source, defs, List.of());
    }

    private void branch(String source, Map<String, ImageDef> defs, List<String> accountOverrides) {
        // Airgapped and not started: what is under test is the selection and the signal, not
        // the proxy health check or the guest's boot.
        var request = new BranchFlow.Request(source, "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, accountOverrides, false, Map.of());
        BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, defs));
    }

    @Test
    void theTemplatesAccountIsStampedBeforeTheProxyIsTold() throws Exception {
        daemon = template();
        branch("tpl-dev", templateWithAccount("work"));

        assertEquals("work", pin());
        assertEquals(List.of("work"), pinAtRefresh,
                "the proxy must be signalled once, after the pin is on the branch");
    }

    @Test
    void theSourceInstancesOwnPinWinsOverItsTemplate() throws Exception {
        // dev-1 was re-pointed with 'isx account set' after it was branched from tpl-dev
        daemon = new FakeIncusDaemon().container("dev-1",
                Map.of(Metadata.PROFILE, "tpl-dev", GITHUB, "bot"));
        branch("dev-1", templateWithAccount("work"));

        assertEquals("bot", pin());
        assertEquals(List.of("bot"), pinAtRefresh);
    }

    @Test
    void theProxyIsToldEvenWhenNothingIsPinned() {
        // The branch may reuse a destroyed instance's address; the proxy must forget that one.
        daemon = template();
        branch("tpl-dev", Map.of());

        assertEquals(List.of(""), pinAtRefresh);
    }

    @Test
    void aPinToAMissingAccountIsRefusedBeforeAnythingIsCreated() throws Exception {
        daemon = template();
        assertThrows(BranchFlow.BranchException.class,
                () -> branch("tpl-dev", templateWithAccount("nobody")));

        assertEquals(List.of(), pinAtRefresh);
        assertEquals(List.of(), daemon.requests().stream()
                .filter(r -> r.startsWith("POST ")).toList(), "no branch was created");
    }

    // ── who chose each pin is recorded beside it ───────────────────────────────

    private static final String GITHUB_ORIGIN = Metadata.accountOriginKey("github");

    private String origin() {
        return daemon.instance("dev-2").path("config").path(GITHUB_ORIGIN).asText("");
    }

    @Test
    void aTemplatesChoiceIsRecordedAsTheTemplates() throws Exception {
        daemon = template();
        branch("tpl-dev", templateWithAccount("work"));
        assertEquals("template:tpl-dev", origin());
    }

    /** Even when it names the template's own account: an explicit choice stays explicit. */
    @Test
    void anAccountFlagIsRecordedAsExplicit() throws Exception {
        daemon = template();
        branch("tpl-dev", templateWithAccount("work"), List.of("github=work"));
        assertEquals("work", pin());
        assertEquals("explicit", origin());
    }

    @Test
    void anExplicitChoiceOnTheSourceIsRecordedAsCopiedFromIt() throws Exception {
        daemon = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.PROFILE, "tpl-dev",
                GITHUB, "bot", GITHUB_ORIGIN, "explicit"));
        branch("dev-1", templateWithAccount("work"));
        assertEquals("bot", pin());
        assertEquals("copied:dev-1", origin());
    }

    @Test
    void theTemplatesChoiceStaysTheTemplatesThroughABranchOfABranch() throws Exception {
        daemon = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.PROFILE, "tpl-dev",
                GITHUB, "work", GITHUB_ORIGIN, "template:tpl-dev"));
        branch("dev-1", templateWithAccount("work"));
        assertEquals("template:tpl-dev", origin());
    }

    /** A template built before origins were recorded: its pin matching its definition is its own. */
    @Test
    void anUnrecordedPinMatchingTheTemplateIsTheTemplates() throws Exception {
        daemon = new FakeIncusDaemon().container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.PROFILE, "tpl-dev", GITHUB, "work"));
        branch("tpl-dev", templateWithAccount("work"));
        assertEquals("template:tpl-dev", origin());
    }

    @Test
    void aBranchThatPinsNothingCarriesNoOrigin() {
        daemon = template();
        branch("tpl-dev", Map.of());
        assertEquals("", origin());
    }
}
