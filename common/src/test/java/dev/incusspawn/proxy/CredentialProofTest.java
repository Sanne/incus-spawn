package dev.incusspawn.proxy;

import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.TempHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(TempHome.class)
class CredentialProofTest {
    @Test void aGenerationReproducesItsSeedButMetadataCannotDeriveIt() throws Exception {
        var seed = CredentialProof.fresh();
        assertEquals(seed, CredentialProof.retain(seed.generation()));
        assertNotEquals(seed.secret(), CredentialProof.fresh().secret());
        var placeholders = List.of(new ProofToken.Placeholder("GH_TOKEN", "gho_", "github"));
        var metadata = new LinkedHashMap<String, String>();
        CredentialProof.stampInto(metadata, seed, placeholders);
        assertEquals(seed.generation(), metadata.get(Metadata.INSTANCE_PROOF_ID));
        var verifier = ProofVerifier.parse(metadata.get(Metadata.INSTANCE_PROOF_SHA256)).get("github");
        assertTrue(ProofVerifier.matches(ProofToken.derive(seed.secret(), "github"), verifier));
        assertFalse(ProofVerifier.matches(ProofToken.derive(seed.generation(), "github"), verifier));
        for (var secret : List.of(seed.secret(), Files.readString(CredentialProof.keyFile()).strip(),
                placeholders.getFirst().tokenFor(seed.secret()))) assertFalse(metadata.toString().contains(secret));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(CredentialProof.keyFile()));
        assertFalse(seed.toString().contains(seed.secret()));
    }

    @Test void aMissingOrUnsafeKeyFailsInsteadOfChangingAnExistingProof() throws Exception {
        var seed = CredentialProof.fresh();
        var key = CredentialProof.keyFile();
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalStateException.class, () -> CredentialProof.retain(seed.generation()));
        Files.delete(key);
        assertThrows(IllegalStateException.class, () -> CredentialProof.retain(seed.generation()));
        assertFalse(Files.exists(key), "an ordinary start must not replace a lost host key");
        var replacement = CredentialProof.fresh();
        assertNotEquals(seed.secret(), replacement.secret());
        Files.delete(key);
        Files.createSymbolicLink(key, key.resolveSibling("missing"));
        assertThrows(IllegalStateException.class, CredentialProof::fresh);
    }

    @Test void concurrentCreatorsShareOneCompleteHostKey() throws Exception {
        var tasks = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> java.util.concurrent.CompletableFuture.supplyAsync(CredentialProof::fresh)).toList();
        for (var task : tasks) {
            var seed = task.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(seed, CredentialProof.retain(seed.generation()));
        }
    }

    @Test void restoringTheWrongHostKeyDoesNotSilentlyRotateExistingGrants() throws Exception {
        var seed = CredentialProof.fresh();
        var placeholders = List.of(new ProofToken.Placeholder("GH_TOKEN", "gho_", "github"));
        var stamp = ProofVerifier.stamp(seed.secret(), placeholders);
        assertEquals(seed, CredentialProof.retain(seed.generation(), stamp));
        Files.delete(CredentialProof.keyFile());
        CredentialProof.fresh();
        assertThrows(IllegalStateException.class, () -> CredentialProof.retain(seed.generation(), stamp));
    }
}
