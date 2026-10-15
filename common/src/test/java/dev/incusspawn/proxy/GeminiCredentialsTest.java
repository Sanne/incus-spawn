package dev.incusspawn.proxy;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.lifecycle.TempHome;
import dev.incusspawn.tool.GeminiSetup;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(TempHome.class)
class GeminiCredentialsTest {
    private static final String DOMAIN = "generativelanguage.googleapis.com";
    private static final GeminiSetup TOOL = new GeminiSetup();
    private static final Map<String, ToolSetup> TOOLS = Map.of(TOOL.name(), TOOL);

    @Test
    void savedAccountsResolveToTheSelectedHeaderAndExactDomain() throws Exception {
        var config = Jjq.spawnConfig("""
                gemini:
                  default: personal
                  accounts:
                    personal:
                      apiKey: gemini-test-personal
                    work:
                      apiKey: gemini-test-work
                """);
        config.save();
        var reloaded = SpawnConfig.load();
        assertEquals(List.of("personal", "work"), AccountResolver.accountNames(reloaded, "gemini"));
        assertEquals("gemini-test-personal", ToolProxyResolver.resolve(reloaded, TOOLS).getFirst().computeHeaderValue());
        var selected = ToolProxyResolver.resolve(reloaded, TOOLS, Map.of("gemini", "work"));
        assertEquals(1, selected.size());
        assertEquals(DOMAIN, selected.getFirst().domain());
        assertEquals("x-goog-api-key", selected.getFirst().headerName());
        assertEquals("gemini-test-work", selected.getFirst().computeHeaderValue());
        assertEquals(Set.of(DOMAIN), ToolProxyResolver.resolvedDomains(reloaded));
        assertThrows(AccountResolver.UnknownAccountException.class,
                () -> ToolProxyResolver.resolve(reloaded, TOOLS, Map.of("gemini", "removed")));
    }

    @Test
    void aMissingKeyIsDiagnosedBeforeBranchingAndCreatesNoRoute() throws Exception {
        var config = new SpawnConfig();
        assertTrue(ToolProxyResolver.resolve(config, TOOLS).isEmpty());
        var template = Jjq.imageDef("name: tpl-gemini\ntools: [gemini-cli]\n");
        var problem = CredentialCheck.check(config, template, Map.of(template.getName(), template),
                Map.of(), TOOLS, TOOLS);
        assertTrue(problem.contains("Gemini API key"), problem);
        assertTrue(problem.contains("isx init"), problem);
    }

    @Test
    void theGuestReadsItsCurrentProofWithoutReceivingTheHostKey(@TempDir Path dir) throws Exception {
        var config = new SpawnConfig();
        config.setConfigByPath("gemini.apiKey", "gemini-host-only-secret");
        config.save();
        var placeholders = ProofToken.declared();
        var placeholder = placeholders.stream().filter(p -> p.env().equals("GEMINI_API_KEY")).findFirst().orElseThrow();
        var secret = InstanceSecret.generate();
        var profile = dir.resolve("proof-tokens");
        Files.writeString(profile, ProofToken.profile(secret, placeholders));
        var process = new ProcessBuilder("sh", "-c", ". \"$1\"; printf '%s' \"$GEMINI_API_KEY\"", "test", profile.toString());
        process.environment().put("GEMINI_API_KEY", TOOL.envEntries(Map.of()).getFirst().getValue());
        var running = process.start();
        var token = new String(running.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, running.waitFor());
        assertEquals(placeholder.tokenFor(secret), token);
        assertFalse(Files.readString(profile).contains("gemini-host-only-secret"));

        var carriers = new ProofCarriers(TOOLS.values());
        assertTrue(carriers.warnings().isEmpty());
        var presented = carriers.read(DOMAIN, name -> name.equalsIgnoreCase("x-goog-api-key") ? List.of(token) : List.of());
        var verifier = ProofVerifier.parse(ProofVerifier.stamp(secret, placeholders)).get("gemini");
        assertTrue(ProofVerifier.matches(presented.digests().get("gemini").getFirst(), verifier));
        assertFalse(ProofVerifier.matches(ProofToken.derive(InstanceSecret.generate(), "gemini"), verifier));
        for (var domain : List.of("aiplatform.googleapis.com", "other.googleapis.com", "evil." + DOMAIN, DOMAIN + ".evil")) {
            assertTrue(carriers.read(domain, name -> List.of(token)).digests().isEmpty(), domain);
        }
        assertTrue(carriers.read(DOMAIN, name -> name.equals("Authorization") ? List.of(token) : List.of())
                .digests().get("gemini").isEmpty());
    }
}
