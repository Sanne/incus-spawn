package dev.incusspawn.proxy;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * A GitHub operation an instance makes that the event log records (#2241): a push, or a pull
 * request opened. It reads the little that names them and never holds a body.
 *
 * <p>A push is {@code POST github.com/<owner>/<repo>[.git]/git-receive-pack}; the branches are the
 * commands at the head of its body, before the pack, read up to {@link #MAX_REQUEST} bytes. A pull
 * request is {@code POST api.github.com/repos/<owner>/<repo>/pulls} or the {@code createPullRequest}
 * mutation of {@code POST api.github.com/graphql} (what {@code gh pr create} sends), and its number
 * is read from the first {@link #MAX_RESPONSE} bytes of the answer. Both are logged only when
 * GitHub answered with success, so a call that failed or was refused costs the log nothing.
 *
 * <p>A push's success is the HTTP answer's: a rejected ref comes back inside a 200's report-status
 * and is not read, so the line records a push GitHub accepted, not that every ref moved.
 */
final class GitHubCall implements ApiActivity.Ends {

    static final int MAX_REQUEST = 8 * 1024;
    static final int MAX_RESPONSE = 32 * 1024;
    static final int MAX_REFS = 16;
    static final int MAX_NAME = 255;

    enum Kind { PUSH, PULL_REST, PULL_GRAPHQL }

    private static final Pattern PUSH = Pattern.compile("/([^/]{1,100})/([^/]{1,100}?)(?:\\.git)?/git-receive-pack");
    private static final Pattern PULLS = Pattern.compile("/repos/[^/]{1,100}/[^/]{1,100}/pulls");
    private static final Pattern PULL_URL =
            Pattern.compile("https://github\\.com/([A-Za-z0-9_.-]{1,100})/([A-Za-z0-9_.-]{1,100})/pull/(\\d{1,10})");
    private static final byte[] CREATE = "createPullRequest(".getBytes(StandardCharsets.US_ASCII);

    /** What the request is, or null when it is none the log records. {@code encoded}: it has a Content-Encoding. */
    static Kind classify(String domain, String method, String path, boolean encoded) {
        if (path == null || !"POST".equals(method)) return null;
        if ("github.com".equals(domain)) {
            return !encoded && PUSH.matcher(path).matches() ? Kind.PUSH : null;
        }
        if ("api.github.com".equals(domain)) {
            if ("/graphql".equals(path)) return encoded ? null : Kind.PULL_GRAPHQL;
            return PULLS.matcher(path).matches() ? Kind.PULL_REST : null;
        }
        return null;
    }

    private final EventLog log;
    private final String instance;
    private final String incarnation;
    private final Kind kind;
    private final String path;
    private final long startedAt;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final byte[] request;
    private int requestLength;
    private final byte[] response;
    private int responseLength;
    private volatile int status;

    GitHubCall(EventLog log, String instance, String incarnation, Kind kind, String path, long startedAt) {
        this.log = log;
        this.instance = instance;
        this.incarnation = incarnation;
        this.kind = kind;
        this.path = path;
        this.startedAt = startedAt;
        this.request = kind == Kind.PULL_REST ? new byte[0] : new byte[MAX_REQUEST];
        this.response = kind == Kind.PUSH ? new byte[0] : new byte[MAX_RESPONSE];
    }

    /** Whether the answer is read, and so must arrive uncompressed. */
    boolean readsResponse() {
        return kind != Kind.PUSH;
    }

    /** One chunk of the request being relayed; the first {@link #MAX_REQUEST} bytes are kept. */
    synchronized void request(Buffer chunk) {
        var take = Math.min(chunk.length(), request.length - requestLength);
        if (take <= 0) return;
        chunk.getBytes(0, take, request, requestLength);
        requestLength += take;
    }

    /** One chunk of the response being relayed; the first {@link #MAX_RESPONSE} bytes are kept. */
    synchronized void response(Buffer chunk) {
        var take = Math.min(chunk.length(), response.length - responseLength);
        if (take <= 0) return;
        chunk.getBytes(0, take, response, responseLength);
        responseLength += take;
    }

    void respond(int status) {
        this.status = status;
    }

    @Override
    public void end(long now) {
        if (!ended.compareAndSet(false, true) || status < 200 || status >= 300) return;
        synchronized (this) {
        try {
            var duration = Math.max(0, now - startedAt);
            if (kind == Kind.PUSH) {
                var m = PUSH.matcher(path);
                if (!m.matches()) return;
                var commands = pushCommands(request, requestLength);
                if (commands.refs().isEmpty() && commands.deleted().isEmpty()) return;
                log.push(new EventLog.Push(java.time.Instant.ofEpochMilli(now), instance, incarnation,
                        bound(m.group(1)) + "/" + bound(m.group(2)), status, duration,
                        commands.refs(), commands.deleted()));
                return;
            }
            if (kind == Kind.PULL_GRAPHQL && indexOf(request, requestLength, CREATE) < 0) return;
            var found = PULL_URL.matcher(new String(response, 0, responseLength, StandardCharsets.UTF_8));
            if (!found.find()) return;
            log.pullRequest(new EventLog.PullRequest(java.time.Instant.ofEpochMilli(now), instance, incarnation,
                    found.group(1) + "/" + found.group(2), Long.parseLong(found.group(3)), status, duration));
        } catch (RuntimeException e) {
            // Whatever the guest sent, it costs the log a line and never the request
        }
        }
    }

    /** The branches a push's commands update and delete. */
    record Commands(List<String> refs, List<String> deleted) {}

    /**
     * The commands at the head of a receive-pack body: pkt-lines of {@code <old> <new> <ref>}, the
     * first followed by a NUL and the capabilities, ended by a flush-pkt, with the pack after it.
     * Reads what is in {@code buf} and stops at the first line that is not whole or not a command,
     * so a cut-off or garbage body gives what came before it.
     */
    static Commands pushCommands(byte[] buf, int length) {
        var refs = new ArrayList<String>();
        var deleted = new ArrayList<String>();
        var at = 0;
        while (at + 4 <= length && refs.size() + deleted.size() < MAX_REFS) {
            var size = hex4(buf, at);
            if (size <= 4 || at + size > length) break;
            var end = at + size;
            if (at + 10 <= length && buf[at + 4] == 's' && buf[at + 5] == 'h' && buf[at + 6] == 'a'
                    && buf[at + 7] == 'l' && buf[at + 8] == 'l' && buf[at + 9] == 'o') {
                at = end; // a shallow clone's push lists its shallow commits first
                continue;
            }
            var nul = at + 4;
            while (nul < end && buf[nul] != 0 && buf[nul] != '\n') nul++;
            var command = new String(buf, at + 4, nul - (at + 4), StandardCharsets.UTF_8).split(" ", 3);
            if (command.length < 3 || command[2].isEmpty()) break;
            var ref = bound(command[2]);
            if (command[1].chars().allMatch(c -> c == '0')) deleted.add(ref);
            else refs.add(ref);
            at = end;
        }
        return new Commands(List.copyOf(refs), List.copyOf(deleted));
    }

    private static int hex4(byte[] buf, int at) {
        var n = 0;
        for (var i = at; i < at + 4; i++) {
            var d = Character.digit(buf[i], 16);
            if (d < 0) return -1;
            n = n * 16 + d;
        }
        return n;
    }

    private static String bound(String name) {
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME);
    }

    private static int indexOf(byte[] buf, int length, byte[] needle) {
        outer:
        for (var i = 0; i + needle.length <= length; i++) {
            for (var j = 0; j < needle.length; j++) {
                if (buf[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
