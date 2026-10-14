package dev.incusspawn.command;

import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx doctor} run inside an instance: the exported proof tokens against the secret file
 * they were derived from (#1109). A fake secret file and environment; no Incus.
 */
@ExtendWith(IsolatedHome.class)
class DoctorInstanceCredentialsTest {

    private static final ProofToken.Placeholder GH = new ProofToken.Placeholder("GH_TOKEN", "gho_", "github");
    private static final Predicate<String> GUARDS_HOLD = guard -> true;

    @TempDir Path run;

    private Path secretFile() { return run.resolve("instance-secret"); }
    private Path proofsFile() { return run.resolve("proof-tokens"); }

    /** A start as {@code InstanceSecret.GUEST_SCRIPT} leaves it: the secret, and the proofs it derives. */
    private String start(List<ProofToken.Placeholder> placeholders) throws IOException {
        var secret = InstanceSecret.generate();
        Files.writeString(secretFile(), secret + "\n");
        Files.writeString(proofsFile(), InstanceSecret.guestEnv(secret, false, placeholders)
                .get(InstanceSecret.PROOFS_DELIVERY_ENV));
        return secret;
    }

    private List<DoctorCommand.Finding> check(Map<String, String> env, List<ProofToken.Placeholder> placeholders,
                                              Predicate<String> guardHolds) {
        return DoctorCommand.instanceCredentialFindings(env, secretFile(), proofsFile(), () -> placeholders, guardHolds);
    }

    private static Map<String, String> env(String... pairs) {
        var env = new HashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) env.put(pairs[i], pairs[i + 1]);
        return env;
    }

    private static List<DoctorCommand.Finding> problems(List<DoctorCommand.Finding> findings) {
        return findings.stream().filter(f -> f.status().isProblem()).toList();
    }

    @Test
    void onTheHostThereIsNothingToCheck() {
        assertEquals(List.of(), check(env(), List.of(GH), GUARDS_HOLD),
                "no secret file and no profile variable: not inside an instance");
    }

    @Test
    void aProofOfThisStartPasses() throws IOException {
        var secret = start(List.of(GH));
        var findings = check(env("GH_TOKEN", GH.tokenFor(secret)), List.of(GH), GUARDS_HOLD);
        assertEquals(List.of(), problems(findings), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.status() == DoctorCommand.Status.OK), findings.toString());
    }

    @Test
    void anEarlierStartsProofIsReportedWithTheFix() throws IOException {
        var earlier = start(List.of(GH));
        start(List.of(GH));
        var problems = problems(check(env("GH_TOKEN", GH.tokenFor(earlier)), List.of(GH), GUARDS_HOLD));
        assertEquals(1, problems.size(), problems.toString());
        var mismatch = problems.get(0);
        assertTrue(mismatch.label().contains("GH_TOKEN"), mismatch.label());
        assertTrue(mismatch.detail().contains("earlier start"), mismatch.detail());
        var fix = mismatch.remediation().description();
        assertTrue(fix.contains("new login shell") && fix.contains("isx shell"), fix);
        assertNull(mismatch.remediation().action(), "doctor cannot re-source the user's shell for them");
    }

    @Test
    void theBuildsPlaceholderIsAMismatchOnlyWhereThisStartDeliveredAProof() throws IOException {
        start(List.of(GH));
        var stale = problems(check(env("GH_TOKEN", "gho_placeholder"), List.of(GH), GUARDS_HOLD));
        assertEquals(1, stale.size(), "a shell opened before the secret arrived keeps the static placeholder");
        assertTrue(stale.get(0).detail().contains("placeholder"), stale.get(0).detail());

        start(List.of());
        assertEquals(List.of(), problems(check(env("GH_TOKEN", "gho_placeholder"), List.of(GH), GUARDS_HOLD)),
                "a start that gave this variable no proof (a tool the host does not serve) leaves the placeholder by design");
    }

    @Test
    void anOldImageWhoseGuardFailsNeedsARebuild() throws IOException {
        var guarded = new ProofToken.Placeholder("ANTHROPIC_API_KEY", "sk-ant-", "claude").onlyWhen("false");
        start(List.of(guarded));
        var findings = problems(check(env("ANTHROPIC_API_KEY", "sk-ant-placeholder"),
                List.of(guarded), guard -> !guard.equals("false")));
        assertEquals(1, findings.size());
        assertTrue(findings.getFirst().remediation().description().contains("Rebuild"));
        assertTrue(findings.getFirst().remediation().description().contains("login alone cannot"));
    }

    @Test
    void aValueSomebodyElseSetAndAnUnsetVariableAreNotOurs() throws IOException {
        start(List.of(GH));
        assertEquals(List.of(), problems(check(env("GH_TOKEN", "ghp_mine"), List.of(GH), GUARDS_HOLD)));
        assertEquals(List.of(), problems(check(env(), List.of(GH), GUARDS_HOLD)));
    }

    @Test
    void aMissingSecretFileSaysTheProxyInjectsNothingUntilTheHostActs() {
        var problems = problems(check(env(InstanceSecret.FILE_ENV_VAR, secretFile().toString()), List.of(GH), GUARDS_HOLD));
        assertEquals(1, problems.size(), problems.toString());
        var missing = problems.get(0);
        assertTrue(missing.label().contains("secret"), missing.label());
        var fix = missing.remediation().description();
        assertTrue(fix.contains("isx shell") && fix.contains("isx start"), fix);
    }

    @Test
    void aSecretWithoutItsProofsAsksForANewDelivery() throws IOException {
        start(List.of(GH));
        Files.delete(proofsFile());
        var problems = problems(check(env("GH_TOKEN", "gho_placeholder"), List.of(GH), GUARDS_HOLD));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).remediation().description().contains("isx shell"), problems.toString());
    }

    @Test
    void anOddLengthHexSecretIsReportedNotACrash() throws IOException {
        start(List.of(GH));
        Files.writeString(secretFile(), "abc\n");
        var problems = problems(check(env("GH_TOKEN", "gho_placeholder"), List.of(GH), GUARDS_HOLD));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).label().contains("invalid"), problems.toString());
    }

    @Test
    void aDifferentPrefixOrNamespaceThanTheStartUsedIsNotedAndNeverAFixThatCannotWork() throws IOException {
        var secret = start(List.of(new ProofToken.Placeholder("GH_TOKEN", "xyz_", "github")));
        var prefix = check(env("GH_TOKEN", "xyz_isx_" + ProofToken.derive(secret, "github")), List.of(GH), GUARDS_HOLD);
        assertEquals(List.of(), problems(prefix), prefix.toString());
        assertTrue(prefix.stream().anyMatch(f -> f.status() == DoctorCommand.Status.NOTE
                && f.detail().contains("GH_TOKEN")), prefix.toString());

        start(List.of(new ProofToken.Placeholder("GH_TOKEN", "gho_", "other")));
        var current = new ProofToken.Placeholder("GH_TOKEN", "gho_", "other").tokenFor(
                Files.readString(secretFile()).strip());
        var namespace = check(env("GH_TOKEN", current), List.of(GH), GUARDS_HOLD);
        assertEquals(List.of(), problems(namespace), "the shell is current: a new shell would change nothing");
        assertTrue(namespace.stream().anyMatch(f -> f.status() == DoctorCommand.Status.NOTE
                && f.detail().contains("GH_TOKEN")), namespace.toString());
        assertFalse(namespace.toString().contains(current), "a proof never reaches the output");
    }

    @Test
    void aSecretFileThatIsNotASecretIsReported() throws IOException {
        Files.writeString(secretFile(), "not hex\n");
        Files.writeString(proofsFile(), "");
        var problems = problems(check(env(), List.of(GH), GUARDS_HOLD));
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void everyCheckInsideAnInstanceSaysWhereCredentialsReach() throws IOException {
        start(List.of(GH));
        var note = check(env(), List.of(GH), GUARDS_HOLD).stream()
                .filter(f -> f.status() == DoctorCommand.Status.NOTE).findFirst().orElseThrow();
        assertTrue(note.detail().contains("login environment") && note.detail().contains("https://"), note.detail());
    }

    @Test
    void aBlankProfileVariableOnTheHostIsNotAnInstance() {
        assertEquals(List.of(), check(env(InstanceSecret.FILE_ENV_VAR, ""), List.of(GH), GUARDS_HOLD));
    }

    @Test
    void aDeliveredVariableThisIsxDoesNotDeclareIsStillJudgedByWhatWasDelivered() throws IOException {
        var secret = start(List.of(GH));
        var current = check(env("GH_TOKEN", GH.tokenFor(secret)), List.of(), GUARDS_HOLD);
        assertEquals(List.of(), problems(current));
        assertTrue(current.stream().anyMatch(f -> f.status() == DoctorCommand.Status.NOTE
                && f.detail().contains("GH_TOKEN")), "a host and guest that disagree must not read as all clear: " + current);
        var stale = problems(check(env("GH_TOKEN", "gho_placeholder"), List.of(), GUARDS_HOLD));
        assertEquals(1, stale.size(), "the file says this start filled it, so the shell is stale");
    }

    @Test
    void anUnreadableDeclarationIsOneFindingNotACrash() throws IOException {
        start(List.of(GH));
        var problems = problems(DoctorCommand.instanceCredentialFindings(env("GH_TOKEN", "gho_placeholder"),
                secretFile(), proofsFile(), () -> { throw new IllegalStateException("bad tool YAML"); }, GUARDS_HOLD));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).detail().contains("bad tool YAML"), problems.toString());
    }

    @Test
    void theDocsPointerNamesAHeadingTheDocsPageHas() throws IOException {
        var anchor = DoctorCommand.CREDENTIALS_DOCS.substring(DoctorCommand.CREDENTIALS_DOCS.indexOf('#') + 1);
        // site/build.sh makes a heading's id the way this does
        assertTrue(Files.readAllLines(Path.of("..", "README.md")).stream()
                .filter(line -> line.startsWith("## ") || line.startsWith("### "))
                .map(line -> line.replaceFirst("^#+ ", "").toLowerCase().replaceAll("[^\\w\\s-]", "").replaceAll("\\s+", "-").replaceAll("-+$", ""))
                .anyMatch(anchor::equals), anchor);
    }

    @Test
    void theDeclarationIsTheOneTheStartUses() throws IOException {
        var declared = ProofToken.declared();
        var gh = declared.stream().filter(p -> p.env().equals("GH_TOKEN")).findFirst().orElseThrow();
        var earlier = start(declared);
        start(declared);
        var problems = problems(DoctorCommand.instanceCredentialFindings(env("GH_TOKEN", gh.tokenFor(earlier)),
                secretFile(), proofsFile(), ProofToken::declared, GUARDS_HOLD));
        assertEquals(1, problems.size(), problems.toString());
    }
}
