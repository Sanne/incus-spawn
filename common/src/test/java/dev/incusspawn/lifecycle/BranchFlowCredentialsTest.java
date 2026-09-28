package dev.incusspawn.lifecycle;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The branch's credential check sees what the branch will be stamped with -- the template's
 * pins, the source's own and any {@code --account} -- and the template a branch of a branch
 * really comes from (#793).
 */
@ExtendWith(TempHome.class)
class BranchFlowCredentialsTest {

    @BeforeEach
    void setUp() throws Exception {
        var configDir = SpawnConfig.configDir();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), """
                openai:
                  accounts:
                    personal: { apiKey: "sk-personal" }
                    spare: {}
                  default: personal
                github:
                  accounts:
                    work: { token: "ghp_work" }
                  default: work
                """);
    }

    private static Map<String, ImageDef> defs(String yaml) throws Exception {
        return Map.of("tpl-dev", ImageDef.parseYaml(yaml));
    }

    private static String problem(FakeIncusDaemon daemon, String source, List<String> overrides,
                                  Map<String, ImageDef> defs) {
        var inherited = BranchFlow.inheritedAccounts(daemon.client(), source, defs);
        return BranchFlow.credentialProblem(inherited, overrides, defs, new ToolDefLoader());
    }

    private static FakeIncusDaemon template() {
        return new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
    }

    @Test
    void anAccountOverrideIsWhatGetsChecked() throws Exception {
        var defs = defs("name: tpl-dev\ntools:\n  - pi: {provider: openai}\n");
        assertEquals("", problem(template(), "tpl-dev", List.of(), defs));
        assertFalse(problem(template(), "tpl-dev", List.of("openai=spare"), defs).isEmpty(),
                "the account the branch will use has no key");
    }

    @Test
    void anOverrideRepairsABrokenTemplatePin() throws Exception {
        var defs = defs("name: tpl-dev\ntools: [gh]\naccounts:\n  github: deleted\n");
        assertTrue(problem(template(), "tpl-dev", List.of(), defs).contains("deleted"));
        assertEquals("", problem(template(), "tpl-dev", List.of("github=work"), defs));
    }

    @Test
    void aBranchOfABranchIsCheckedAgainstItsTemplate() throws Exception {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.PROFILE, "tpl-dev"));
        var result = problem(daemon, "dev-1", List.of(), defs("name: tpl-dev\ntools: [claude]\n"));
        assertTrue(result.contains("Anthropic API key"), result);
    }
}
