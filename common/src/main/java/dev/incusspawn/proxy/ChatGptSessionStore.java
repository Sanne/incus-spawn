package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The host's copy of a ChatGPT session (#1332): one file per {@code openai} account, under
 * {@code ~/.config/incus-spawn/openai/<account>/session.json}, readable by the user only.
 *
 * <h3>One writer at a time, and only for the session it read</h3>
 * OpenAI rotates the refresh token on every refresh and rejects a reused one, so a write that
 * lands on the wrong session loses it. Every write therefore takes the account's lock -- an
 * in-process lock and a {@code flock} on {@code <account>/lock}, which the CLI shares -- and a
 * refresh {@link #commit commits} only if the file still holds the session id and generation the
 * refresh started from. A sign-in again changes the id; removal deletes the file. Either way a
 * late refresh is told so, and the proxy never creates a session file: only sign-in does.
 *
 * <h3>The window atomic replacement cannot close</h3>
 * Between OpenAI answering a refresh and the commit's {@code fsync}, the new tokens exist only in
 * that answer. {@link #beginRefresh} records the attempt first, so a proxy that dies in between
 * leaves {@code refreshing} behind and the next start knows the session may need a new sign-in.
 *
 * <p>Every method blocks on the file system: the proxy calls them off the event loop.
 */
public final class ChatGptSessionStore {

    /** The tokens of a session, as OpenAI issued them. Real credentials: never logged. */
    public record Tokens(String idToken, String accessToken, String refreshToken, String accountId) {
        @Override
        public String toString() { return "Tokens[redacted]"; }
    }

    /**
     * One account's session.
     *
     * @param refreshing when an unfinished refresh started, or null: set by {@link #beginRefresh}
     */
    public record Session(String account, String sessionId, long generation, Tokens tokens, Instant refreshing) {
        Session withRefreshing(Instant started) {
            return new Session(account, sessionId, generation, tokens, started);
        }

        /** The session after a committed refresh: the next generation, nothing in flight. */
        Session next(Tokens refreshed) {
            return new Session(account, sessionId, generation + 1, refreshed, null);
        }
    }

    /** What became of a refresh's tokens. */
    public enum Commit {
        /** Written: the file held the session and generation the refresh started from. */
        COMMITTED,
        /** Discarded: the account signed in again, or another refresh landed first. */
        SUPERSEDED,
        /** Discarded: the session is gone, e.g. the account was removed. */
        GONE
    }

    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Supplier<Path> root;

    /** The store under {@code ~/.config/incus-spawn/openai}. */
    public ChatGptSessionStore() {
        this(() -> SpawnConfig.configDir().resolve("openai"));
    }

    ChatGptSessionStore(Supplier<Path> root) {
        this.root = root;
    }

    /** {@code account}'s session, or null when it has none. */
    public Session read(String account) {
        var file = file(account);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var root = JqValues.parseStrict(text);
        var tokens = root.path("tokens");
        var sessionId = root.pathText("session_id", "");
        var refreshing = root.pathText("refreshing", "");
        if (!ChatGptProof.isValidName(sessionId)) throw new IllegalStateException("Malformed session file: " + file);
        return new Session(account, sessionId, root.path("generation").asLong(0),
                new Tokens(tokens.pathText("id_token", ""), tokens.pathText("access_token", ""),
                        tokens.pathText("refresh_token", ""), tokens.pathText("account_id", "")),
                refreshing.isEmpty() ? null : Instant.parse(refreshing));
    }

    /**
     * Signs {@code account} in with {@code tokens} as a new session, replacing any it had: a new
     * session id, so every proof made for the previous one stops working.
     */
    public Session create(String account, Tokens tokens) {
        return locked(account, () -> {
            var previous = read(account);
            var session = new Session(account, UUID.randomUUID().toString(),
                    previous == null ? 0 : previous.generation() + 1, tokens, null);
            write(session);
            return session;
        });
    }

    /** Removes {@code account}'s session. A refresh still in flight then finds it {@link Commit#GONE}. */
    public void remove(String account) {
        locked(account, () -> {
            try {
                Files.deleteIfExists(file(account));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        });
    }

    /**
     * Records that a refresh of {@code session} is about to be sent, if the file still holds it;
     * returns whether it does. The record outlives a crash between OpenAI's answer and the commit.
     */
    public boolean beginRefresh(Session session) {
        return locked(session.account(), () -> {
            var current = read(session.account());
            if (!sameSession(current, session)) return false;
            write(current.withRefreshing(Instant.now()));
            return true;
        });
    }

    /** Clears the record of an unfinished refresh of {@code session} that OpenAI refused. */
    public void abandonRefresh(Session session) {
        locked(session.account(), () -> {
            var current = read(session.account());
            if (sameSession(current, session) && current.refreshing() != null) {
                write(current.withRefreshing(null));
            }
            return null;
        });
    }

    /**
     * Writes the tokens a refresh of {@code session} returned, only if the file still holds that
     * session at that generation; the new generation is one higher.
     */
    public Commit commit(Session session, Tokens refreshed) {
        return locked(session.account(), () -> {
            var current = read(session.account());
            if (current == null) return Commit.GONE;
            if (!sameSession(current, session)) return Commit.SUPERSEDED;
            write(current.next(refreshed));
            return Commit.COMMITTED;
        });
    }

    private static boolean sameSession(Session current, Session expected) {
        return current != null && current.sessionId().equals(expected.sessionId())
                && current.generation() == expected.generation();
    }

    Path file(String account) {
        if (!ChatGptProof.isValidName(account)) throw new IllegalArgumentException("Not a valid account name: " + account);
        return root.get().resolve(account).resolve("session.json");
    }


    private void write(Session session) {
        var tokens = JqObject.builder(4)
                .put("id_token", JqString.of(session.tokens().idToken()))
                .put("access_token", JqString.of(session.tokens().accessToken()))
                .put("refresh_token", JqString.of(session.tokens().refreshToken()))
                .put("account_id", JqString.of(session.tokens().accountId()))
                .build();
        var json = JqObject.builder(5)
                .put("session_id", JqString.of(session.sessionId()))
                .put("generation", JqNumber.of(session.generation()))
                .put("tokens", tokens);
        if (session.refreshing() != null) json.put("refreshing", JqString.of(session.refreshing().toString()));
        writeAtomically(file(session.account()), json.build().toJsonString());
    }

    /**
     * Replaces {@code file} with {@code content}: a 0600 temporary file, synced, renamed over it,
     * and the directory synced, so a crash leaves either the old file or the new one. Its
     * directory exists: {@link #locked} made it.
     */
    private static void writeAtomically(Path file, String content) {
        var dir = file.getParent();
        var userOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        var temp = dir.resolve(file.getFileName() + ".tmp");
        try {
            Files.deleteIfExists(temp);
            try (var channel = FileChannel.open(Files.createFile(temp, userOnly), StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(dir, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs {@code action} holding {@code account}'s lock, in this process and on the file. */
    private <T> T locked(String account, Supplier<T> action) {
        var dir = file(account).getParent();
        var lock = LOCKS.computeIfAbsent(dir, d -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try (var channel = FileChannel.open(dir.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return action.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }
}
