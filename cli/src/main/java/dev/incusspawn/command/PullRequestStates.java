package dev.incusspawn.command;

import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.proxy.EventLogReader;
import dev.incusspawn.proxy.EventLogReader.PullRequest;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.util.SystemTrust;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether the pull requests an instance opened are still open, merged or closed (#2241), asked of
 * GitHub from the host, on demand only: by {@code isx overview} and the TUI's overview.
 *
 * <p>Each instance's pull requests are asked about with that instance's own GitHub account,
 * resolved as the proxy resolves it (its {@code account.github} stamp, else the namespace's
 * default), so no other account learns which repositories it works in. It fails closed: an
 * instance pinned to a missing account, one that inherits its parent's, or an account without a
 * token is not asked about, and its pull requests stay {@link State#UNKNOWN}. Every instance on
 * one account is asked about in one GraphQL request, its repositories and numbers passed as
 * variables, never written into the query.
 *
 * <p>The pull requests asked about are all the event log recorded for the instance, a request the
 * proxy passed through without the account (no GitHub proof) included: the account may be asked
 * about a repository it was not used for there, and only the host sees the answer.
 */
public final class PullRequestStates {

    private PullRequestStates() {}

    /** What became of a pull request; {@code field} is its value in {@code pull_request_states}. */
    public enum State {
        OPEN("open"), MERGED("merged"), CLOSED("closed"), UNKNOWN("unknown");

        public final String field;

        State(String field) {
            this.field = field;
        }
    }

    /** Asks GitHub, with {@code token}, what became of {@code pulls}; one missing from the answer is unknown. */
    @FunctionalInterface
    public interface GitHub {
        Map<PullRequest, State> ask(String token, List<PullRequest> pulls) throws IOException;
    }

    /** At most this many pull requests in one request: GitHub's GraphQL limits are far above it. */
    static final int PER_REQUEST = 100;

    /**
     * What GitHub said of the pull requests of {@code instances} in the log, by instance name,
     * then by pull request. One it was not asked about (no usable account, a failed request) or
     * did not answer for is absent, so it is unknown; {@code failed} hears why a request failed,
     * once per account, never with the token.
     */
    public static Map<String, Map<PullRequest, State>> read(List<InstanceInfo> instances, EventLogReader.Log log,
                                                SpawnConfig config, GitHub github,
                                                java.util.function.Consumer<String> failed) {
        return read(instances, log, config, github, failed, new Settled());
    }

    /**
     * As {@link #read(List, EventLogReader.Log, SpawnConfig, GitHub, java.util.function.Consumer)},
     * but a pull request {@code settled} holds as merged for the same account is not asked about
     * again, and one GitHub answers merged is added to it. The accounts are asked at once.
     */
    public static Map<String, Map<PullRequest, State>> read(List<InstanceInfo> instances, EventLogReader.Log log,
                                                SpawnConfig config, GitHub github,
                                                java.util.function.Consumer<String> failed, Settled settled) {
        var pullsOf = new LinkedHashMap<String, List<PullRequest>>();
        var byToken = new LinkedHashMap<String, Set<PullRequest>>();
        var tokenOf = new LinkedHashMap<String, String>();
        for (var instance : instances) {
            var pulls = OverviewCommand.effortOf(log, instance).pullRequests();
            pullsOf.put(instance.name(), pulls);
            if (pulls.isEmpty()) continue;
            var token = token(config, instance);
            if (token == null) continue;
            tokenOf.put(instance.name(), token);
            // GraphQL's Int is 32 bits: a larger number would fail the whole request, every pull request with it
            pulls.stream().filter(PullRequest::githubShaped)
                    .forEach(byToken.computeIfAbsent(token, t -> new LinkedHashSet<>())::add);
        }
        var answers = new java.util.concurrent.ConcurrentHashMap<String, Map<PullRequest, State>>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            byToken.forEach((token, pulls) -> pool.submit(() -> {
                var states = new LinkedHashMap<PullRequest, State>();
                var ask = new java.util.ArrayList<PullRequest>();
                for (var pull : pulls) {
                    if (settled.merged(token, pull)) states.put(pull, State.MERGED);
                    else ask.add(pull);
                }
                try {
                    for (int i = 0; i < ask.size(); i += PER_REQUEST) {
                        states.putAll(github.ask(token, ask.subList(i, Math.min(ask.size(), i + PER_REQUEST))));
                    }
                } catch (IOException | RuntimeException e) {
                    // The message is the client's or GitHub's, which never holds the token sent
                    failed.accept("Could not ask GitHub whether pull requests were merged: " + e.getMessage());
                }
                states.forEach((pull, state) -> {
                    if (state == State.MERGED) settled.merge(token, pull);
                });
                answers.put(token, states);
            }));
        }
        var result = new LinkedHashMap<String, Map<PullRequest, State>>();
        pullsOf.forEach((name, pulls) -> {
            var token = tokenOf.get(name);
            var states = token == null ? Map.<PullRequest, State>of() : answers.getOrDefault(token, Map.of());
            var own = new LinkedHashMap<PullRequest, State>();
            for (var pull : pulls) {
                var state = states.get(pull);
                if (state != null) own.put(pull, state);
            }
            result.put(name, own);
        });
        return result;
    }

    /**
     * The pull requests GitHub answered merged, per account, for as long as one TUI session
     * lasts: a merged pull request stays merged, so it is not asked about again. Kept per
     * account, so an account is never told what only another one could see.
     */
    public static final class Settled {
        private final Set<String> merged = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private static String key(String token, PullRequest pull) {
            return dev.incusspawn.tool.ToolDef.sha256hex(token) + "\n" + pull;
        }

        boolean merged(String token, PullRequest pull) {
            return merged.contains(key(token, pull));
        }

        void merge(String token, PullRequest pull) {
            merged.add(key(token, pull));
        }
    }

    /**
     * The token of the GitHub account the proxy would use for {@code instance}, or null when
     * there is none it may use: a pin to an account not configured, {@code inherit}, or no token.
     */
    static String token(SpawnConfig config, InstanceInfo instance) {
        var config0 = instance.metadata() == null ? null : instance.metadata().path("config");
        var pin = config0 == null ? null : AccountSelection.fromConfig(config0).get(GhSetup.NAMESPACE);
        final String account;
        try {
            account = AccountResolver.effectiveAccount(config, GhSetup.NAMESPACE, pin);
        } catch (AccountResolver.UnknownAccountException e) {
            return null;
        }
        if (account.isEmpty() || AccountResolver.INHERIT.equals(account)) return null;
        var token = AccountResolver.value(config, GhSetup.NAMESPACE, account, "token");
        return token == null || token.isBlank() ? null : token;
    }

    /** The one GraphQL request: an aliased field per pull request, every value a variable. */
    static String body(List<PullRequest> pulls) {
        var query = new StringBuilder("query(");
        var fields = new StringBuilder();
        var variables = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pulls.size(); i++) {
            var pull = pulls.get(i);
            var slash = pull.repo().indexOf('/');
            if (i > 0) query.append(", ");
            query.append("$o").append(i).append(": String!, $n").append(i).append(": String!, $p").append(i).append(": Int!");
            fields.append(" p").append(i).append(": repository(owner: $o").append(i).append(", name: $n").append(i)
                    .append(") { pullRequest(number: $p").append(i).append(") { state } }");
            variables.put("o" + i, pull.repo().substring(0, slash));
            variables.put("n" + i, pull.repo().substring(slash + 1));
            variables.put("p" + i, pull.number());
        }
        query.append(") {").append(fields).append(" }");
        var body = new LinkedHashMap<String, Object>();
        body.put("query", query.toString());
        body.put("variables", variables);
        return JqValues.fromJavaObject(body).toJsonString();
    }

    /**
     * GitHub's answer to {@link #body}: a pull request it could not see (no access, deleted) is
     * left out. An answer with no data at all is a refused request, such as a token without the
     * scope or a rate limit, and throws with GitHub's first message.
     */
    static Map<PullRequest, State> parse(List<PullRequest> pulls, JqValue answer) throws IOException {
        var states = new LinkedHashMap<PullRequest, State>();
        var data = answer.path("data");
        if (!data.isObject()) {
            var message = answer.path("errors").isArray() && !answer.path("errors").asList().isEmpty()
                    ? answer.path("errors").asList().getFirst().pathText("message", "") : "";
            message = dev.incusspawn.util.OutputFormat.oneLine(message);
            if (message.length() > 200) message = message.substring(0, 200) + "…";
            throw new IOException(message.isEmpty() ? "GitHub answered with no data" : "GitHub refused: " + message);
        }
        for (int i = 0; i < pulls.size(); i++) {
            var state = switch (data.path("p" + i).path("pullRequest").pathText("state", "")) {
                case "OPEN" -> State.OPEN;
                case "MERGED" -> State.MERGED;
                case "CLOSED" -> State.CLOSED;
                default -> null;
            };
            if (state != null) states.put(pulls.get(i), state);
        }
        return states;
    }

    /**
     * GitHub itself, through isx's HTTPS stack and the host's trust. Its one client is made at the
     * first request and serves every request after it, across accounts, so a TUI session pays
     * for one TLS setup and not one per request.
     */
    public static GitHub api() {
        var shared = new java.util.concurrent.atomic.AtomicReference<HttpClient>();
        return (token, pulls) -> {
            var client = shared.get();
            if (client == null) {
                var made = SystemTrust.httpClient().connectTimeout(Duration.ofSeconds(5)).build();
                client = shared.compareAndExchange(null, made);
                if (client == null) client = made;
                else made.close();
            }
            var request = HttpRequest.newBuilder(URI.create("https://api.github.com/graphql"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body(pulls)))
                    .build();
            HttpResponse<byte[]> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            if (response.statusCode() != 200) throw new IOException("GitHub answered HTTP " + response.statusCode());
            return parse(pulls, Jjq.parseForeign(response.body(), "GitHub's answer"));
        };
    }

    /**
     * {@code name}, the {@code i}th pull request as shown, with what became of it when GitHub said:
     * {@code o/r#12 (merged)}; alone when {@code states} does not say.
     */
    public static String labelled(String name, List<State> states, int i) {
        var state = states == null || i >= states.size() ? State.UNKNOWN : states.get(i);
        return state == State.UNKNOWN ? name : name + " (" + state.field + ")";
    }

    /** {@link State#field} of each, for {@code pull_request_states}. */
    public static List<String> fields(List<State> states) {
        return states.stream().map(s -> s.field).toList();
    }
}
