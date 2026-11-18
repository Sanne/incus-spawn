package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.TempHome;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a start writes for a ChatGPT session (#1332): the proof verifiers in the instance's
 * metadata, keyed with the secret that start minted, and the guest's Codex auth.json content.
 * Everything fail-closed is checked too: no session, an API-key account, a pin that no longer
 * resolves, and stale stamps an instance still carries.
 */
@ExtendWith(TempHome.class)
class ChatGptStartTest {

    private static final String ACCOUNT = "sso";
    private static final String ACCOUNT_ID = "acct-enterprise";

    private ChatGptSessionStore store;

    @BeforeEach
    void fresh() throws Exception {
        var configDir = Files.createDirectories(Path.of(System.getProperty("user.home"), ".config/incus-spawn"));
        Files.writeString(configDir.resolve("config.yaml"), """
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                    keyed:
                      apiKey: "sk-real-key"
                  default: sso
                """);
        store = new ChatGptSessionStore();
    }

    /** A session, as a sign-in leaves it. */
    private SessionStore.Session<ChatGptSessionStore.Tokens> signIn() {
        var auth = "{\"chatgpt_plan_type\":\"business\",\"chatgpt_account_id\":\"" + ACCOUNT_ID + "\"}";
        var id = jwt("{\"email\":\"user@example.invalid\",\"https://api.openai.com/auth\":" + auth + "}");
        var access = jwt("{\"exp\":9999999999,\"https://api.openai.com/auth\":" + auth + "}");
        return store.create(ACCOUNT, new ChatGptSessionStore.Tokens(id, access, "rt.real-refresh", ACCOUNT_ID));
    }

    private static String jwt(String payload) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"RS256\"}".getBytes()) + "."
                + enc.encodeToString(payload.getBytes()) + ".real-signature";
    }

    /** An instance's config as Incus lists it. */
    private static JqValue instanceConfig(Map<String, String> keys) {
        return configOf(keys, Map.of());
    }

    /** A config of {@code keys} and {@code chatgpt} stamps, as the instance lists them. */
    private static JqValue configOf(Map<String, String> keys, Map<String, String> chatgpt) {
        var all = new LinkedHashMap<String, String>(keys);
        all.putAll(chatgpt);
        var builder = JqObject.builder(all.size());
        all.forEach((key, value) -> builder.put(key, JqString.of(value)));
        return builder.build();
    }

    private static Map<String, String> staleStamps() {
        var stale = new LinkedHashMap<String, String>();
        stale.put(Metadata.CHATGPT_SESSION, "sso:old");
        stale.put(Metadata.CHATGPT_ACCESS_PROOF_SHA256, "a".repeat(64));
        stale.put(Metadata.CHATGPT_REFRESH_PROOF_SHA256, "b".repeat(64));
        stale.put(Metadata.CHATGPT_PROOF_SECRET_SHA256, "c".repeat(64));
        return stale;
    }

    @Test
    void aChatGptAccountStampsVerifiersForTheStartsSecretAndBuildsTheGuestAuth() {
        var session = signIn();
        var secret = InstanceSecret.generate();
        var updates = new LinkedHashMap<String, String>();

        var auth = ChatGptStart.stampInto(updates, instanceConfig(Map.of()), secret,
                SpawnConfig.load(), store);

        var proofs = ChatGptProof.derive(secret, ACCOUNT, session.sessionId());
        assertEquals(ACCOUNT + ":" + session.sessionId(), updates.get(Metadata.CHATGPT_SESSION));
        assertEquals(ChatGptProof.verifier(proofs.access()), updates.get(Metadata.CHATGPT_ACCESS_PROOF_SHA256));
        assertEquals(ChatGptProof.verifier(proofs.refresh()), updates.get(Metadata.CHATGPT_REFRESH_PROOF_SHA256));
        assertEquals(InstanceSecret.sha256(secret), updates.get(Metadata.CHATGPT_PROOF_SECRET_SHA256),
                "the verifiers count only while this secret is the instance's");

        // The guest's auth.json: Codex's own file, with placeholders where its tokens go
        var parsed = JqValues.parse(auth);
        assertEquals("chatgpt", parsed.pathText("auth_mode", ""));
        assertTrue(parsed.path("OPENAI_API_KEY").isNull(), "the key field, null as Codex's own login leaves it: " + auth);
        assertEquals(ACCOUNT_ID, parsed.path("tokens").pathText("account_id", ""));
        assertTrue(parsed.path("tokens").pathText("refresh_token", "").startsWith("rt." + ChatGptProof.MARKER), auth);
        assertFalse(auth.contains("rt.real-refresh"), "the real refresh token never reaches a guest: " + auth);
        assertFalse(auth.contains("real-signature"), "the real tokens never reach a guest: " + auth);
    }

    @Test
    void anotherStartsStampsWouldNotVerify() {
        signIn();
        var first = new LinkedHashMap<String, String>();
        ChatGptStart.stampInto(first, instanceConfig(Map.of()), InstanceSecret.generate(),
                SpawnConfig.load(), store);
        var second = new LinkedHashMap<String, String>();
        ChatGptStart.stampInto(second, instanceConfig(Map.of()), InstanceSecret.generate(),
                SpawnConfig.load(), store);
        assertNotEquals(first.get(Metadata.CHATGPT_ACCESS_PROOF_SHA256),
                second.get(Metadata.CHATGPT_ACCESS_PROOF_SHA256),
                "every start's proofs are its own: the previous start's never verify");
    }

    @Test
    void anApiKeyAccountServesNoSessionAndClearsStaleStamps() {
        signIn();
        var pinned = Map.of(Metadata.accountKey("openai"), "keyed");
        var updates = new LinkedHashMap<String, String>();

        var auth = ChatGptStart.stampInto(updates, configOf(pinned, staleStamps()),
                InstanceSecret.generate(), SpawnConfig.load(), store);

        assertEquals("", auth);
        for (var key : Metadata.CHATGPT_KEYS) assertEquals("", updates.get(key), key);
    }

    @Test
    void anUnresolvablePinServesNoSessionEither() {
        signIn();
        var updates = new LinkedHashMap<String, String>();
        var pinned = Map.of(Metadata.accountKey("openai"), "gone");

        var auth = ChatGptStart.stampInto(updates, configOf(pinned, staleStamps()),
                InstanceSecret.generate(), SpawnConfig.load(), store);

        assertEquals("", auth);
        assertEquals("", updates.get(Metadata.CHATGPT_SESSION));
    }

    @Test
    void aStartWhoseAccountHasNoSessionServesNothingAndWritesNoClearingWhenNoneIsStamped() {
        // The account resolves but was never signed in: no session file exists
        var updates = new LinkedHashMap<String, String>();

        var auth = ChatGptStart.stampInto(updates, instanceConfig(Map.of()),
                InstanceSecret.generate(), SpawnConfig.load(), store);

        assertEquals("", auth);
        assertTrue(updates.isEmpty(), "no start pays for chatgpt keys it does not write: " + updates);
    }
}
