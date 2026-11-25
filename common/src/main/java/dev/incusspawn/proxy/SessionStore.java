package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
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
 * The host's copy of a sign-in session a guest's tool would otherwise keep itself: one file per
 * account of a credential namespace, under {@code ~/.config/incus-spawn/<namespace>/<account>/session.json},
 * readable by the user only. {@link ChatGptSessionStore} holds Codex's ChatGPT sessions (#1332)
 * and {@link GoogleSessionStore} Antigravity's Google sign-ins (#2443); what differs between them
 * is the tokens a session holds, which each subclass encodes.
 *
 * <h3>One writer at a time, and only for the session it read</h3>
 * A provider may rotate the refresh token on every refresh and reject a reused one, so a write
 * that lands on the wrong session loses it. Every write therefore takes the account's lock -- an
 * in-process lock and a {@code flock} on {@code <account>/lock}, which the CLI shares -- and a
 * refresh {@link #commit commits} only if the file still holds the session id and generation the
 * refresh started from. A sign-in again changes the id; removal deletes the file. Either way a
 * late refresh is told so, and the proxy never creates a session file by refreshing: only a
 * sign-in does.
 *
 * <h3>The window atomic replacement cannot close</h3>
 * Between the provider answering a refresh and the commit's {@code fsync}, the new tokens exist
 * only in that answer. {@link #beginRefresh} records the attempt first, so a proxy that dies in
 * between leaves {@code refreshing} behind and the next start knows the session may need a new
 * sign-in.
 *
 * <p>Every method blocks on the file system: the proxy calls them off the event loop.
 *
 * @param <T> the tokens of one session, a record whose {@code toString} redacts them
 */
public abstract class SessionStore<T> {

    /**
     * One account's session.
     *
     * @param refreshing when an unfinished refresh started, or null: set by {@link #beginRefresh}
     */
    public record Session<T>(String account, String sessionId, long generation, T tokens, Instant refreshing) {
        Session<T> withRefreshing(Instant started) {
            return new Session<>(account, sessionId, generation, tokens, started);
        }

        /** The session after a committed refresh: the next generation, nothing in flight. */
        Session<T> next(T refreshed) {
            return new Session<>(account, sessionId, generation + 1, refreshed, null);
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

    protected SessionStore(Supplier<Path> root) {
        this.root = root;
    }

    /** The tokens as the file holds them. */
    protected abstract JqObject encode(T tokens);

    /** The tokens read back from {@code node}, the file's {@code tokens} object. */
    protected abstract T decode(JqValue node);

    /** {@code account}'s session, or null when it has none. */
    public Session<T> read(String account) {
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
        var sessionId = root.pathText("session_id", "");
        var refreshing = root.pathText("refreshing", "");
        if (!SessionNames.isValid(sessionId)) throw new IllegalStateException("Malformed session file: " + file);
        return new Session<>(account, sessionId, root.path("generation").asLong(0), decode(root.path("tokens")),
                refreshing.isEmpty() ? null : Instant.parse(refreshing));
    }

    /**
     * Signs {@code account} in with {@code tokens} as a new session, replacing any it had: a new
     * session id, so every proof made for the previous one stops working.
     */
    public Session<T> create(String account, T tokens) {
        return locked(account, () -> {
            var previous = read(account);
            var session = new Session<>(account, UUID.randomUUID().toString(),
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
     * Moves {@code from}'s session to {@code to}, as an account rename does, keeping its id and
     * generation so what was made for it still works. Replaces a session {@code to} had, which
     * only an account removed under that name could have left. A refresh of {@code from} still
     * in flight then finds it {@link Commit#GONE}.
     *
     * @return whether {@code from} had a session
     */
    public boolean move(String from, String to) {
        return locked(from, () -> {
            var session = read(from);
            if (session == null) return false;
            locked(to, () -> {
                write(new Session<>(to, session.sessionId(), session.generation(), session.tokens(), session.refreshing()));
                return null;
            });
            try {
                Files.deleteIfExists(file(from));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return true;
        });
    }

    /**
     * Records that a refresh of {@code session} is about to be sent, if the file still holds it;
     * returns whether it does. The record outlives a crash between the provider's answer and the commit.
     */
    public boolean beginRefresh(Session<T> session) {
        return locked(session.account(), () -> {
            var current = read(session.account());
            if (!sameSession(current, session)) return false;
            write(current.withRefreshing(Instant.now()));
            return true;
        });
    }

    /** Clears the record of an unfinished refresh of {@code session} that the provider refused. */
    public void abandonRefresh(Session<T> session) {
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
    public Commit commit(Session<T> session, T refreshed) {
        return locked(session.account(), () -> {
            var current = read(session.account());
            if (current == null) return Commit.GONE;
            if (!sameSession(current, session)) return Commit.SUPERSEDED;
            write(current.next(refreshed));
            return Commit.COMMITTED;
        });
    }

    private static <T> boolean sameSession(Session<T> current, Session<T> expected) {
        return current != null && current.sessionId().equals(expected.sessionId())
                && current.generation() == expected.generation();
    }

    Path file(String account) {
        if (!SessionNames.isValid(account)) throw new IllegalArgumentException("Not a valid account name: " + account);
        return root.get().resolve(account).resolve("session.json");
    }

    private void write(Session<T> session) {
        var json = JqObject.builder(4)
                .put("session_id", JqString.of(session.sessionId()))
                .put("generation", JqNumber.of(session.generation()))
                .put("tokens", encode(session.tokens()));
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
    private <R> R locked(String account, Supplier<R> action) {
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
