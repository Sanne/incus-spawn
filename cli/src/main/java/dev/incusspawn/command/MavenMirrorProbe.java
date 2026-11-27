package dev.incusspawn.command;

import dev.incusspawn.config.MavenMirrors;
import dev.incusspawn.config.MavenRepositories;
import dev.incusspawn.util.SystemTrust;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Whether a {@code maven-mirrors:} entry works (#2681), asked as the proxy asks a mirror on a
 * miss: the {@code .sha1} of one artifact the repository is known to hold, then the artifact, bare
 * {@code GET}s, the artifact checked against that checksum. Every failure there falls back to the
 * repository, so the builds pass and a mirror that serves nothing is otherwise invisible.
 *
 * <p>Run by {@code isx doctor} itself, not by the proxy: both trust the host's CA store through
 * {@link SystemTrust} and resolve through the system resolver, so the answer is the one the proxy
 * would get, and it is there while the proxy is down too. The whole check of one mirror, every
 * redirect and both bodies included, is bounded by {@link #TIMEOUT}, below the proxy's own share
 * for a mirror, so a mirror that hangs or stalls mid-body costs doctor a few seconds, never a stall.
 */
final class MavenMirrorProbe {

    /** How long the whole check of one mirror may take: connecting, every redirect and both bodies. */
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Shorter than {@link #TIMEOUT}, so a host that never accepts is told apart from one that stalls once connected. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
    /** As many redirects as the proxy follows for a {@code .sha1}; the artifact itself is never redirected. */
    private static final int MAX_REDIRECTS = 10;
    private static final int MAX_SIDECAR_BYTES = 64 * 1024;
    private static final int MAX_ARTIFACT_BYTES = 1024 * 1024;
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");

    /**
     * One small, old, fixed artifact of each built-in repository that can take a mirror, by its URL.
     * A repository {@code maven-repositories:} declares has none isx can know.
     */
    static final Map<String, String> KNOWN_ARTIFACTS = Map.of(
            "https://repo.maven.apache.org/maven2/", "junit/junit/4.13.2/junit-4.13.2.pom",
            "https://repo1.maven.org/maven2/", "junit/junit/4.13.2/junit-4.13.2.pom",
            "https://plugins.gradle.org/m2/",
            "com/gradle/develocity/com.gradle.develocity.gradle.plugin/3.18/com.gradle.develocity.gradle.plugin-3.18.pom");

    /**
     * One bare GET, the redirect not followed, its answer and body in by {@code deadline} (a
     * {@code System.nanoTime}), else an {@link HttpTimeoutException}.
     */
    interface Transport {
        Answer get(URI uri, int maxBytes, long deadline) throws IOException, InterruptedException;
    }

    /** A mirror's answer: its status, its {@code Location} when it has one, and at most the bytes asked for (null past them). */
    record Answer(int status, String location, byte[] body) {}

    /**
     * What came of asking: {@code problem} is null when the mirror served the artifact, and
     * {@code artifact} is null when isx knows none of the repository's to ask for.
     */
    record Result(MavenMirrors.Entry entry, String artifact, String problem) {
        boolean untested() {
            return artifact == null;
        }
    }

    private final Transport transport;

    MavenMirrorProbe(Transport transport) {
        this.transport = transport;
    }

    /** Over the network, trusting what the proxy trusts. */
    static MavenMirrorProbe overNetwork(HttpClient client) {
        return new MavenMirrorProbe(networkTransport(client));
    }

    /** The client {@link #overNetwork} asks over, which the caller closes once every check is done. */
    static HttpClient networkClient() {
        return SystemTrust.httpClient()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * GETs over {@code client}, each bounded as a whole by its deadline. {@code HttpRequest.timeout}
     * stops at the headers, so a mirror that sends them and then stalls would hold a blocking body
     * read for good: the exchange is asynchronous instead, its body gathered by
     * {@link #limited}, and cancelled when the deadline passes.
     */
    static Transport networkTransport(HttpClient client) {
        return (uri, maxBytes, deadline) -> {
            var left = deadline - System.nanoTime();
            if (left <= 0) throw new HttpTimeoutException("no time left");
            var request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofNanos(left)).build();
            var exchange = client.sendAsync(request, response -> limited(maxBytes));
            try {
                var response = exchange.get(left, TimeUnit.NANOSECONDS);
                return new Answer(response.statusCode(), response.headers().firstValue("Location").orElse(null),
                        response.body());
            } catch (TimeoutException e) {
                throw new HttpTimeoutException("no answer in time");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException io) throw io;
                throw new IOException(e.getCause());
            } finally {
                // Cancelling a finished exchange does nothing; a running one is aborted
                exchange.cancel(true);
            }
        };
    }

    /** A body of at most {@code maxBytes}, given as null past them, its connection then let go. */
    private static HttpResponse.BodySubscriber<byte[]> limited(int maxBytes) {
        return new HttpResponse.BodySubscriber<>() {
            private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            private final CompletableFuture<byte[]> body = new CompletableFuture<>();
            private Flow.Subscription subscription;

            @Override
            public CompletionStage<byte[]> getBody() {
                return body;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                if (body.isDone()) return;
                for (var buffer : buffers) {
                    var chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    bytes.writeBytes(chunk);
                }
                if (bytes.size() > maxBytes) {
                    body.complete(null);
                    subscription.cancel();
                }
            }

            @Override
            public void onError(Throwable error) {
                body.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                body.complete(bytes.toByteArray());
            }
        };
    }

    Result probe(MavenMirrors.Entry entry) {
        var artifact = KNOWN_ARTIFACTS.get(entry.repository().toString());
        if (artifact == null) return new Result(entry, null, null);
        var mirror = entry.mirror();
        var artifactUri = URI.create("https://" + mirror.authority() + mirror.pathPrefix() + artifact);
        var sidecarUri = URI.create(artifactUri + ".sha1");
        // One limit for the whole check, every redirect and both bodies included
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        try {
            var followed = followed(sidecarUri, deadline);
            var sidecar = followed.answer();
            if (followed.stopped() != null) return new Result(entry, artifact, followed.stopped());
            if (sidecar.status() != 200) return new Result(entry, artifact, answered(sidecar, followed.uri(), null));
            var expected = sha1Of(sidecar.body());
            if (expected == null) return new Result(entry, artifact, "it does not serve a usable .sha1 at " + shown(followed.uri()));
            var answer = get(artifactUri, MAX_ARTIFACT_BYTES, deadline);
            if (answer.status() != 200) {
                return new Result(entry, artifact, answered(answer, artifactUri, "the proxy does not follow a redirect for the artifact"));
            }
            if (answer.body() == null) {
                return new Result(entry, artifact, artifactUri + " sent more than " + MAX_ARTIFACT_BYTES / 1024
                        + " KiB, more than the artifact it names holds");
            }
            if (!expected.equals(sha1(answer.body()))) {
                return new Result(entry, artifact, artifactUri + " does not match the mirror's own .sha1");
            }
            return new Result(entry, artifact, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(entry, artifact, "the check was interrupted");
        } catch (Unanswered e) {
            // Named by the server that failed, a redirect's target included, not always the mirror
            return new Result(entry, artifact, failure(e.failure(), e.uri.getHost(),
                    dev.incusspawn.util.Authority.of(e.uri.getHost(), e.uri.getPort())));
        } catch (IOException e) {
            return new Result(entry, artifact, failure(e, mirror));
        }
    }

    /** A GET that failed, with the URI it asked, so the failure names the server that gave it. */
    private static final class Unanswered extends IOException {
        final URI uri;

        Unanswered(URI uri, IOException cause) {
            super(cause.getMessage(), cause);
            this.uri = uri;
        }

        IOException failure() {
            return (IOException) getCause();
        }
    }

    private Answer get(URI uri, int maxBytes, long deadline) throws IOException, InterruptedException {
        try {
            return transport.get(uri, maxBytes, deadline);
        } catch (IOException e) {
            throw new Unanswered(uri, e);
        }
    }

    /**
     * An answer and the URI that gave it, the last one asked once redirects were followed, and why
     * a redirect was not followed, or null.
     */
    private record Followed(URI uri, Answer answer, String stopped) {}

    /** The sidecar's answer, its redirects followed as the proxy follows them: over https, an http one included. */
    private Followed followed(URI uri, long deadline) throws IOException, InterruptedException {
        var answer = get(uri, MAX_SIDECAR_BYTES, deadline);
        for (int hops = 0; answer.status() >= 300 && answer.status() < 400 && answer.location() != null; hops++) {
            if (hops >= MAX_REDIRECTS) {
                return new Followed(uri, answer, "its .sha1 redirected more than " + MAX_REDIRECTS + " times, the most the proxy follows");
            }
            URI target;
            try {
                target = uri.resolve(answer.location());
            } catch (IllegalArgumentException e) {
                return new Followed(uri, answer, shown(uri) + " redirected to " + shown(answer.location())
                        + ", which is not a URL");
            }
            var scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("http")) target = URI.create("https" + target.toString().substring(4));
            // An http URL is held to a host as well, as the proxy's redirectTarget holds it
            if (!scheme.equals("https") && !scheme.equals("http") || target.getHost() == null) {
                return new Followed(uri, answer, shown(uri) + " redirected to " + shown(answer.location())
                        + ", which the proxy does not follow: only an https URL with a host");
            }
            uri = target;
            answer = get(uri, MAX_SIDECAR_BYTES, deadline);
        }
        return new Followed(uri, answer, null);
    }

    /** {@code uri}'s status, and for a redirect, {@code unfollowed}: why it was not followed. */
    private static String answered(Answer answer, URI uri, String unfollowed) {
        var said = "it answered HTTP " + answer.status() + " for " + shown(uri);
        return answer.status() >= 300 && answer.status() < 400 && unfollowed != null ? said + ", and " + unfollowed : said;
    }

    /** A URL a mirror may have chosen, on one line with no control character. */
    private static String shown(Object uri) {
        return dev.incusspawn.util.OutputFormat.oneLine(String.valueOf(uri));
    }

    /** What failed, in the reader's terms: the connection, TLS with its reason, or the time limit. */
    static String failure(IOException e, MavenRepositories.Url mirror) {
        return failure(e, mirror.host(), mirror.authority());
    }

    /** As above, for the server at {@code host}, named {@code authority} (with its port when not 443). */
    static String failure(IOException e, String host, String authority) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLException) {
                var reason = reason(t);
                return "TLS with " + authority + " failed" + (reason == null ? "" : " (" + reason + ")");
            }
        }
        if (e instanceof HttpConnectTimeoutException) {
            return "isx could not connect to " + authority + " within " + CONNECT_TIMEOUT.toSeconds() + " s";
        }
        if (e instanceof HttpTimeoutException) {
            return authority + " did not serve the check within " + TIMEOUT.toSeconds() + " s";
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            // HttpClient reports a name it cannot resolve as a ConnectException caused by UnresolvedAddressException
            if (t instanceof UnknownHostException || t instanceof java.nio.channels.UnresolvedAddressException) {
                return "isx could not resolve " + host;
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                var reason = reason(t);
                // HttpClient gives a refused connection no message
                return "isx could not connect to " + authority + (reason == null ? "" : " (" + reason + ")");
            }
        }
        var reason = reason(e);
        return "the request to " + authority + " failed" + (reason == null ? "" : " (" + reason + ")");
    }

    /** The innermost message, which is the specific one, such as which certificate path failed; null when none has one. */
    private static String reason(Throwable t) {
        String message = null;
        for (; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) message = t.getMessage();
        }
        return message == null ? null : dev.incusspawn.util.OutputFormat.oneLine(message);
    }

    /** The checksum a {@code .sha1} holds, read as the proxy reads one ({@link dev.incusspawn.util.ChecksumText}). */
    static String sha1Of(byte[] body) {
        return dev.incusspawn.util.ChecksumText.hex(body, SHA1);
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
