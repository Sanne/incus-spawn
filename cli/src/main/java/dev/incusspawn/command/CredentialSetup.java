package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.AccountUsage;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.proxy.InstanceRegistry;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.util.TerminalLink;
import dev.incusspawn.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import static dev.incusspawn.util.BuildOutput.BOLD;
import static dev.incusspawn.util.BuildOutput.DIM;
import static dev.incusspawn.util.BuildOutput.GREEN;
import static dev.incusspawn.util.BuildOutput.YELLOW;
import static dev.incusspawn.util.BuildOutput.styled;
import static dev.incusspawn.command.BaseCommand.askConfirmation;
import static dev.incusspawn.command.InitCommand.commandExists;
import static dev.incusspawn.command.InitCommand.runHostCapturingExit;

/**
 * The credential steps of {@code isx init}: Claude Code, GitHub and every other tool's
 * credentials, the account menu they share (named accounts, the default and the pins that
 * follow it), and the path lists. Each step's entry, with its header, stays in
 * {@link InitCommand}; what is here is the {@code (SpawnConfig, Prompts)} logic behind it.
 * <p>
 * Effects outside the process (token verification, {@code gh}, the browser, environment
 * variables, Incus lookups) are package-private methods, overridden by the flow tests.
 */
class CredentialSetup {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpClient httpClient;

    // Not a static final: GraalVM native-image would capture it at build time.
    // HTTP/1.1: Java's default HTTP/2 ALPN negotiation can cause the first TLS
    // request in a native-image JVM to fail, then succeed on retry.
    private HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        }
        return httpClient;
    }

    void closeHttpClient() {
        if (httpClient != null) {
            httpClient.close();
            httpClient = null;
        }
    }

    // Pass-throughs to the host, overridable so the credential flows can be driven without an
    // environment, a host 'claude' or a browser. Each is one line in production.

    /** An environment variable, stripped; {@code ""} when unset. */
    String env(String name) {
        return Environment.strippedEnv(name);
    }

    boolean hostHasCommand(String command) {
        return commandExists(command);
    }

    boolean openUrl(String url) {
        return Platform.openUrl(url);
    }

    private static final List<String> KNOWN_TOOL_ORDER = List.of("claude", "gh", "bob", "codex");

    List<String> selectCredentials(Map<String, ToolSetup> allTools, SpawnConfig config, Prompts prompts) {
        var configTree = JSON.valueToTree(config);

        var toolsWithProxy = new ArrayList<Map.Entry<String, ToolSetup>>();
        for (var entry : allTools.entrySet()) {
            var tool = entry.getValue();
            if (!tool.hasOwnCredentials()) continue;
            var proxyDef = tool.proxy();
            if (proxyDef == null || proxyDef.getConfiguration().isEmpty()) continue;
            toolsWithProxy.add(entry);
        }

        if (toolsWithProxy.isEmpty()) {
            System.out.println("  No tools with proxy credentials found.");
            return List.of();
        }

        toolsWithProxy.sort(Comparator.<Map.Entry<String, ToolSetup>, Integer>comparing(
                        e -> {
                            int idx = KNOWN_TOOL_ORDER.indexOf(e.getKey());
                            return idx >= 0 ? idx : Integer.MAX_VALUE;
                        })
                .thenComparing(Map.Entry::getKey));

        System.out.println("  Which credentials do you want to configure?");
        for (int i = 0; i < toolsWithProxy.size(); i++) {
            var entry = toolsWithProxy.get(i);
            var toolName = entry.getKey();
            var tool = entry.getValue();
            var desc = describeTool(toolName, tool);
            var tag = isToolConfigured(toolName, tool, config, configTree, allTools) ? " [configured]" : "";
            System.out.println("    " + (i + 1) + ". " + desc + tag);
        }
        System.out.println();
        System.out.print("  Enter numbers separated by commas, 'all', or press Enter to skip: ");
        var input = readInput(prompts.readLine());
        if (input.isBlank()) {
            System.out.println("  Skipped credential setup.");
            return List.of();
        }

        var selected = new ArrayList<String>();
        if (input.strip().equalsIgnoreCase("all")) {
            for (var entry : toolsWithProxy) selected.add(entry.getKey());
        } else {
            for (var part : input.split(",")) {
                try {
                    int idx = Integer.parseInt(part.strip()) - 1;
                    if (idx >= 0 && idx < toolsWithProxy.size()) {
                        var name = toolsWithProxy.get(idx).getKey();
                        if (!selected.contains(name)) selected.add(name);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return selected;
    }

    private static boolean isToolConfigured(String toolName, ToolSetup tool,
            SpawnConfig config, com.fasterxml.jackson.databind.JsonNode configTree,
            Map<String, ToolSetup> allTools) {
        if ("claude".equals(toolName)) return config.getClaude().hasAuth();
        var proxyDef = tool.proxy();
        if (proxyDef == null) return false;
        boolean anyChecked = false;
        for (var entry : proxyDef.getConfiguration().entrySet()) {
            var configDef = entry.getValue();
            if (!configDef.getValue().isBlank()) continue; // hardcoded literal — always resolved
            if (configDef.isConfirm()) continue;
            if (configDef.getConfigPath().isBlank()) continue;
            anyChecked = true;
            if (configuredValue(proxyDef, configDef, configTree, allTools).isBlank()) return false;
        }
        // If no config-path entries were checked, all config is hardcoded — tool is configured
        return anyChecked || proxyDef.getConfiguration().values().stream()
                .anyMatch(c -> !c.getValue().isBlank());
    }

    /**
     * What an entry resolves to for its namespace's default account -- flat or under
     * {@code accounts:}, the same answer the proxy would serve an instance that pins nothing.
     */
    private static String configuredValue(ToolDef.ProxyDef proxyDef, ToolDef.ConfigEntry configDef,
            com.fasterxml.jackson.databind.JsonNode configTree, Map<String, ToolSetup> allTools) {
        var fullPath = proxyDef.fullConfigPath(configDef);
        var namespace = proxyDef.namespaceOf(configDef);
        if (namespace.isBlank() || !fullPath.startsWith(namespace + ".")) {
            return dev.incusspawn.config.AccountResolver.navigate(configTree, fullPath);
        }
        var account = dev.incusspawn.config.AccountResolver.effectiveAccount(configTree, namespace,
                dev.incusspawn.config.AccountResolver.shapeOf(allTools, namespace), null);
        return dev.incusspawn.config.AccountResolver.value(configTree, namespace, account,
                fullPath.substring(namespace.length() + 1));
    }

    void setupClaudeAuth(SpawnConfig config, Prompts prompts) {
        var target = config.getClaude().hasAuth()
                ? new AccountTarget(config.getClaude().accountName(), false)
                : AccountTarget.FRESH;

        // Detect existing env vars
        var envVertex = env("CLAUDE_CODE_USE_VERTEX");
        var envApiKey = env("ANTHROPIC_API_KEY");
        var envOauthToken = env("CLAUDE_CODE_OAUTH_TOKEN");

        if ("1".equals(envVertex)) {
            var region = env("CLOUD_ML_REGION");
            var projectId = env("ANTHROPIC_VERTEX_PROJECT_ID");
            System.out.println("  Detected Vertex AI configuration from environment:");
            System.out.println("    Region:  " + (region.isBlank() ? "(not set)" : region));
            System.out.println("    Project: " + (projectId.isBlank() ? "(not set)" : projectId));

            if (region.isBlank() || projectId.isBlank()) {
                System.out.println("  CLOUD_ML_REGION and ANTHROPIC_VERTEX_PROJECT_ID must both be set for verification.");
                System.out.println("  Continuing with manual setup...");
            } else {
                System.out.println("  Verifying Vertex AI configuration...");
                var result = verifyVertexConfig(region, projectId);
                if (result.verified()) {
                    System.out.println("  " + styled(BOLD + GREEN, "\u2713 " + result.message()));
                    if (askConfirmation(prompts, envAccountPrompt(config, target, "  Use this configuration?"), true)) {
                        saveVertexConfig(config, target, region, projectId);
                        System.out.println("  Claude auth configuration saved.");
                        return;
                    }
                    System.out.println("  Skipping environment config. Continuing with manual setup...");
                } else {
                    System.out.println("  " + result.message());
                    if (askConfirmation(prompts, "  Save anyway? Press Enter to configure manually.", false)) {
                        saveVertexConfig(config, target, region, projectId);
                        System.out.println("  Claude auth configuration saved (unverified).");
                        return;
                    }
                }
            }
        } else if (!envOauthToken.isBlank()) {
            System.out.println("  Detected CLAUDE_CODE_OAUTH_TOKEN from environment.");
            System.out.println("  Verifying OAuth token...");
            var oauthResult = verifyOauthToken(envOauthToken);
            if (oauthResult.verified()) {
                System.out.println("  " + styled(BOLD + GREEN, "\u2713 " + oauthResult.message()));
                if (askConfirmation(prompts, envAccountPrompt(config, target, "  Use this token?"), true)) {
                    saveOauthConfig(config, target, envOauthToken);
                    System.out.println("  Claude auth configuration saved.");
                    return;
                }
                System.out.println("  Skipping environment token. Continuing with manual setup...");
            } else {
                System.out.println("  " + oauthResult.message());
                System.out.println("  Continuing with manual setup...");
            }
        } else if (!envApiKey.isBlank()) {
            System.out.println("  Detected ANTHROPIC_API_KEY from environment.");
            System.out.println("  Verifying API key...");
            var result = verifyAnthropicApiKey(envApiKey);
            if (result.verified()) {
                System.out.println("  " + styled(BOLD + GREEN, "\u2713 " + result.message()));
                if (askConfirmation(prompts, envAccountPrompt(config, target, "  Use this key?"), true)) {
                    saveDirectConfig(config, target, envApiKey);
                    System.out.println("  Claude auth configuration saved.");
                    return;
                }
                System.out.println("  Skipping environment key. Continuing with manual setup...");
            } else {
                System.out.println("  " + result.message());
                System.out.println("  Continuing with manual setup...");
            }
        }

        // Offer to keep, extend or re-point the configured accounts on re-run.
        // allAccounts() rather than hasAuth(): even when every account is incomplete, the
        // user must be able to see (and remove) them rather than hand-editing config.yaml.
        if (!config.getClaude().allAccounts().isEmpty()) {
            var managed = manageClaudeAccounts(config, prompts);
            if (managed.isEmpty()) return;
            target = managed.get();
        }

        System.out.println("  How do you authenticate with Claude?");
        System.out.println("    1. Anthropic API key");
        System.out.println("    2. Claude Pro/Max subscription (OAuth token)");
        System.out.println("    3. Google Cloud Vertex AI");
        System.out.println();
        System.out.print("  Choice (1/2/3, or Enter to skip): ");
        var authChoice = readInput(prompts.readLine());

        if (authChoice.equals("3")) {
            while (true) {
                System.out.print("  CLOUD_ML_REGION (or press Enter to skip): ");
                var region = readInput(prompts.readLine());
                if (region.isBlank()) {
                    System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                    return;
                }
                System.out.print("  ANTHROPIC_VERTEX_PROJECT_ID: ");
                var projectId = readInput(prompts.readLine());
                if (projectId.isBlank()) {
                    System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                    return;
                }

                System.out.println("  Verifying Vertex AI configuration...");
                var result = verifyVertexConfig(region, projectId);
                if (result.verified()) {
                    System.out.println("  " + styled(BOLD + GREEN, "✓ " + result.message()));
                    saveVertexConfig(config, target, region, projectId);
                    System.out.println("  Claude auth configuration saved.");
                    break;
                } else {
                    System.out.println("  " + result.message());
                    switch (askVerificationFailureAction(prompts)) {
                        case SKIP -> {
                            System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                            break;
                        }
                        case SAVE_UNVERIFIED -> {
                            saveVertexConfig(config, target, region, projectId);
                            System.out.println("  Claude auth configuration saved (unverified).");
                            break;
                        }
                        case RETRY -> {
                            continue;
                        }
                    }
                    break;
                }
            }
        } else if (authChoice.equals("2")) {
            setupClaudeOauth(config, prompts, target);
        } else if (authChoice.equals("1")) {
            while (true) {
                System.out.print("  ANTHROPIC_API_KEY (or press Enter to skip): ");
                var key = askSecret(prompts);
                if (key.isBlank()) {
                    System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                    break;
                }

                System.out.println("  Verifying API key...");
                var result = verifyAnthropicApiKey(key);
                if (result.verified()) {
                    System.out.println("  " + styled(BOLD + GREEN, "✓ " + result.message()));
                    saveDirectConfig(config, target, key);
                    System.out.println("  Claude auth configuration saved.");
                    break;
                } else {
                    System.out.println("  " + result.message());
                    switch (askVerificationFailureAction(prompts)) {
                        case SKIP -> {
                            System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                            break;
                        }
                        case SAVE_UNVERIFIED -> {
                            saveDirectConfig(config, target, key);
                            System.out.println("  Claude auth configuration saved (unverified).");
                            break;
                        }
                        case RETRY -> {
                            continue;
                        }
                    }
                    break;
                }
            }
        } else {
            System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
        }
    }

    /** Names the account an environment credential will overwrite, when there is a choice. */
    private static String envAccountPrompt(SpawnConfig config, AccountTarget target, String question) {
        var accounts = config.getClaude().effectiveAccounts();
        if (accounts.size() < 2) return question;
        return question + " (replaces account '" + target.name() + "'; the others are kept)";
    }

    /** One line per account: marker, name, what it is, and a masked credential. */
    private static String describeClaudeAccount(String name, SpawnConfig.ClaudeAccount account, boolean isDefault) {
        var resolved = account.effectiveType();
        var secret = resolved == null ? "" : switch (resolved) {
            case API_KEY -> " (" + maskSecret(account.getApiKey()) + ")";
            case OAUTH -> " (" + maskSecret(account.getOauthToken()) + ")";
            case VERTEX -> "";
        };
        var tag = isDefault ? "  [default]" : !account.isComplete() ? "  [incomplete]" : "";
        return (isDefault ? "  * " : "    ") + name + "  —  " + account.describe() + secret + tag;
    }

    /**
     * Shows the configured Claude accounts and lets the user keep, add, re-point or remove one.
     *
     * @return the account to configure next, or empty when there is nothing left to do.
     */
    private Optional<AccountTarget> manageClaudeAccounts(SpawnConfig config, Prompts prompts) {
        while (true) {
            // Re-read every pass: a rename replaces the whole Claude section of the config.
            var claude = config.getClaude();
            var accounts = claude.allAccounts();
            var defaultName = claude.accountName();
            System.out.println("  Claude accounts:");
            accounts.forEach((name, account) ->
                    System.out.println(describeClaudeAccount(name, account, name.equals(defaultName))));
            if (claude.accountFor(SpawnConfig.ClaudeAccount::servesDirectApi) == null) {
                System.out.println("    (none of these can answer 'isx ask' — add an API key or Vertex account)");
            }
            System.out.println();
            // 'd' and 'x' are reachable as raw keystrokes even when not listed, so the same
            // fact gates both the menu and the handlers -- state it once.
            var canManageMultiple = accounts.size() > 1;
            System.out.println("    a. Add another account");
            System.out.println("    r. Replace all with a single account");
            if (canManageMultiple) {
                System.out.println("    d. Change which account is the default");
                System.out.println("    x. Remove an account");
            }
            System.out.println("    n. Rename an account");
            System.out.print("  Choice (Enter to keep as-is): ");
            var choice = readInput(prompts.readLine()).toLowerCase(java.util.Locale.ROOT);

            switch (choice) {
                case "" -> {
                    return Optional.empty();
                }
                case "a" -> {
                    var name = askAccountName(prompts, accounts.keySet());
                    if (name.isEmpty()) return Optional.empty();
                    return Optional.of(new AccountTarget(name, false));
                }
                case "r" -> {
                    var going = new java.util.ArrayList<>(accounts.keySet());
                    going.remove(AccountTarget.FRESH.name());
                    if (!confirmLosingAccounts(config, SpawnConfig.ClaudeConfig.NAMESPACE, going, prompts)) continue;
                    return Optional.of(AccountTarget.FRESH);
                }
                case "n" -> {
                    renameAccount(config, SpawnConfig.ClaudeConfig.NAMESPACE, accounts.keySet(), prompts);
                    continue;
                }
                // 'd' and 'x' save immediately rather than on the way out of the menu: each
                // prints a confirmation naming what changed, and the loop then returns to the
                // listing, so a later abort (or Ctrl-C) must not leave that message a lie.
                case "d" -> {
                    if (canManageMultiple) {
                        System.out.print("  Name of the account to make default: ");
                        var name = readInput(prompts.readLine());
                        if (accounts.containsKey(name)) {
                            var account = accounts.get(name);
                            if (!account.isComplete()) {
                                System.out.println("  Account '" + name + "' is incomplete — fix it before making it the default.");
                            } else {
                                changeDefaultAccount(config, SpawnConfig.ClaudeConfig.NAMESPACE, name,
                                        () -> config.getClaude().setDefaultAccount(name), prompts);
                            }
                        } else if (!name.isEmpty()) {
                            System.out.println("  No account named '" + name + "'.");
                        }
                        continue;
                    }
                }
                case "x" -> {
                    if (canManageMultiple) {
                        System.out.print("  Name of the account to remove: ");
                        var name = readInput(prompts.readLine());
                        if (accounts.containsKey(name)) {
                            var account = accounts.get(name);
                            if (account.isComplete() && claude.effectiveAccounts().size() <= 1) {
                                System.out.println("  Cannot remove '" + name + "' — it is the only working account.");
                            } else if (confirmLosingAccounts(config, SpawnConfig.ClaudeConfig.NAMESPACE,
                                    List.of(name), prompts)) {
                                var moving = runningFollowersIfDefault(config, SpawnConfig.ClaudeConfig.NAMESPACE, name);
                                claude.getAccounts().remove(name);
                                if (name.equals(claude.getDefaultAccount())) {
                                    claude.setDefaultAccount(claude.accountName());
                                }
                                config.save();
                                System.out.println("  Removed account '" + name + "'.");
                                if (!moving.isEmpty()) refreshIdentities(moving);
                            }
                        } else if (!name.isEmpty()) {
                            System.out.println("  No account named '" + name + "'.");
                        }
                        continue;
                    }
                }
                default -> { }
            }
            System.out.println("  Please choose one of the listed options.");
        }
    }

    /** Prompts for a new account name, rejecting duplicates and anything YAML-hostile. */
    private static String askAccountName(Prompts prompts, java.util.Set<String> taken) {
        return askAccountName(prompts, taken, "  Name for this account (e.g. personal, work — Enter to cancel): ");
    }

    private static String askAccountName(Prompts prompts, java.util.Set<String> taken, String prompt) {
        while (true) {
            System.out.print(prompt);
            var name = readInput(prompts.readLine());
            if (name.isEmpty()) return "";
            if (taken.contains(name)) {
                System.out.println("  There is already an account named '" + name + "'.");
                continue;
            }
            if (!name.matches("[A-Za-z0-9._-]+")) {
                System.out.println("  Use letters, digits, '.', '_' or '-' only.");
                continue;
            }
            return name;
        }
    }

    record AuthResult(boolean verified, String message) {}

    enum VerificationFailureAction { RETRY, SKIP, SAVE_UNVERIFIED }

    private static VerificationFailureAction askVerificationFailureAction(Prompts prompts) {
        while (true) {
            System.out.print("  Try again? (Y/n/s to save anyway): ");
            var action = parseVerificationFailureAction(prompts.readLine());
            if (action != null) return action;
            System.out.println("  Please answer y, n, or s.");
        }
    }

    static VerificationFailureAction parseVerificationFailureAction(String answer) {
        if (answer == null) return VerificationFailureAction.SKIP;
        return switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "", "y" -> VerificationFailureAction.RETRY;
            case "n" -> VerificationFailureAction.SKIP;
            case "s" -> VerificationFailureAction.SAVE_UNVERIFIED;
            default -> null;
        };
    }

    /**
     * Every {@code readLine()} prompt in this class strips its input; secrets must do the
     * same or a paste that picks up a stray leading/trailing space silently becomes a
     * different credential and is reported as rejected by the remote API.
     */
    static String readSecret(char[] chars) {
        return chars == null ? "" : new String(chars).strip();
    }

    /**
     * Reads a secret and confirms what arrived. {@code readPassword()} echoes nothing, so
     * without this a paste that never landed looks exactly like one that did, and a truncated
     * one only shows up as a rejection from the remote API. The line shows the length and the
     * same masked form init already prints for a configured credential -- never more.
     *
     * <p>Dimmed and worded "received" rather than green: it says the input arrived, not that
     * the credential works; verification still speaks for that.
     */
    static String askSecret(Prompts prompts) {
        var secret = readSecret(prompts.readPassword());
        if (!secret.isEmpty()) {
            System.out.println("  " + styled(DIM, describeReceivedSecret(secret)));
        }
        return secret;
    }

    static String describeReceivedSecret(String secret) {
        var n = secret.length();
        return "\u2713 Received " + n + (n == 1 ? " character" : " characters")
                + " (" + maskSecret(secret) + ")";
    }

    /**
     * The plaintext counterpart to {@link #readSecret(char[])}: strips, and answers EOF with
     * {@code ""} rather than throwing.
     *
     * <p>{@link Prompts#readLine()} returns null once stdin is closed, so a bare
     * {@code readLine().strip()} ends init with a {@code NullPointerException} the moment it is
     * run non-interactively -- piped input that runs out, a terminated parent, a CI job. Empty
     * is the right answer because every prompt here already reads it as "skip", "finish" or
     * "take the default", so EOF behaves exactly as pressing Enter does and each retry loop
     * still terminates.
     *
     * <p>Prompts that need a different answer at EOF keep choosing their own and do not use
     * this: {@link BaseCommand#askConfirmation} takes an explicit {@code eofValue}, and
     * {@link #parseVerificationFailureAction} maps null to SKIP so it never re-prompts.
     */
    static String readInput(String line) {
        return line == null ? "" : line.strip();
    }

    /** Real 'claude setup-token' output runs to ~108 chars; much shorter means a paste cut at a line wrap. */
    private static final int OAUTH_TOKEN_MIN_PLAUSIBLE_LENGTH = 90;

    /** Non-fatal shape check; verification against the API remains the authority. */
    static Optional<String> oauthTokenShapeWarning(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (!token.startsWith(SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX)) {
            return Optional.of("Note: token does not start with '" + SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX
                    + "' (unexpected format for 'claude setup-token' output).");
        }
        if (token.length() < OAUTH_TOKEN_MIN_PLAUSIBLE_LENGTH) {
            return Optional.of("Note: token is " + token.length() + " characters, shorter than expected."
                    + " If your terminal wrapped the token, the paste may have been cut short.");
        }
        return Optional.empty();
    }

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * " API said: ..." from an Anthropic error body, or "" when it carries no usable
     * message. Remote input: a malformed or oversized body must yield nothing rather
     * than throwing or flooding the terminal.
     */
    static String apiErrorSuffix(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            var message = JSON.readTree(body).path("error").path("message");
            if (!message.isTextual()) {
                return "";
            }
            var text = WHITESPACE_RUN.matcher(message.asText()).replaceAll(" ").strip();
            if (text.isEmpty()) {
                return "";
            }
            return " API said: " + (text.length() > 200 ? text.substring(0, 200) + "\u2026" : text);
        } catch (Exception e) {
            return "";
        }
    }

    /** GitHub's token prefixes: fine-grained PAT, classic PAT, OAuth (gh CLI), user-to-server, server-to-server, refresh. */
    private static final List<String> GITHUB_TOKEN_PREFIXES =
            List.of("github_pat_", "ghp_", "gho_", "ghu_", "ghs_", "ghr_");

    private static final List<String> KNOWN_PREFIXES = java.util.stream.Stream
            .concat(GITHUB_TOKEN_PREFIXES.stream(), java.util.stream.Stream.of("sk-ant-")).toList();

    /** Characters shown from each end of a secret, when showing them is safe at all. */
    private static final int MASK_EDGE = 4;

    /**
     * A recognisable form of a secret that reveals at most a third of it.
     *
     * <p>A known prefix is a public format marker rather than secret material, so it is shown
     * freely and does not count. Of the rest, a long secret shows its first and last four
     * characters, a shorter one only its last four, and anything under twelve characters of
     * material nothing at all. A flat "first four, last four" once showed eight of a
     * nine-character password -- next to its exact length, in the "received" line.
     */
    static String maskSecret(String secret) {
        if (secret == null) return "****";
        var prefix = "";
        for (var p : KNOWN_PREFIXES) {
            if (secret.startsWith(p)) { prefix = p; break; }
        }
        int material = secret.length() - prefix.length();
        var tail = secret.substring(secret.length() - Math.min(MASK_EDGE, secret.length()));
        if (prefix.isEmpty() && 3 * (2 * MASK_EDGE) <= material) {
            return secret.substring(0, MASK_EDGE) + "..." + tail;
        }
        if (3 * MASK_EDGE <= material) {
            return prefix + "..." + tail;
        }
        return "****";
    }

    /**
     * Where a credential being configured goes, for every namespace alike: the account it is
     * written into, and whether every other account of the namespace goes with it. Kept apart
     * because they are separate questions -- "replace all" and "replace this one" differ only in
     * the second. Absent ({@code Optional.empty()}) means leave everything as it is.
     */
    record AccountTarget(String name, boolean replaceOthers) {
        /** A namespace's only account, under the name a single credential is saved as. */
        static final AccountTarget FRESH =
                new AccountTarget(NamespaceAccounts.DEFAULT_ACCOUNT_NAME, true);
    }

    private void saveDirectConfig(SpawnConfig config, AccountTarget target, String apiKey) {
        saveClaudeAccount(config, target, SpawnConfig.ClaudeAccount.ofApiKey(apiKey));
    }

    private void saveOauthConfig(SpawnConfig config, AccountTarget target, String oauthToken) {
        saveClaudeAccount(config, target, SpawnConfig.ClaudeAccount.ofOauth(oauthToken));
    }

    private void saveVertexConfig(SpawnConfig config, AccountTarget target, String region, String projectId) {
        saveClaudeAccount(config, target, SpawnConfig.ClaudeAccount.ofVertex(region, projectId));
    }

    private void saveClaudeAccount(SpawnConfig config, AccountTarget target, SpawnConfig.ClaudeAccount account) {
        if (target.replaceOthers()) {
            config.getClaude().setSingleAccount(target.name(), account);
        } else {
            config.getClaude().putAccount(target.name(), account);
        }
        config.save();
        noteAiHelpNeedsDirectApiAccount(config);
    }

    /**
     * A Pro/Max token cannot answer 'isx ask' -- it is only valid for Claude Code itself. Say so
     * when the account is saved, rather than letting the feature fail later with an API error.
     */
    private void noteAiHelpNeedsDirectApiAccount(SpawnConfig config) {
        if (config.getClaude().accountFor(SpawnConfig.ClaudeAccount::servesDirectApi) != null) return;
        if (config.getOpenai().hasAuth()) return;
        System.out.println();
        System.out.println("  Note: 'isx ask' and '?' in the TUI cannot use a Pro/Max token — it is");
        System.out.println("  only valid for Claude Code itself. Add an Anthropic API key or a Vertex");
        System.out.println("  AI account as a second Claude account to enable them.");
    }

    AuthResult verifyAnthropicApiKey(String key) {
        if (!key.startsWith("sk-ant-")) {
            System.out.println("  Note: key does not start with 'sk-ant-' (unexpected format).");
        }
        return verifyAnthropicCredential("x-api-key", key, "API key");
    }

    AuthResult verifyOauthToken(String token) {
        oauthTokenShapeWarning(token).ifPresent(warning -> System.out.println("  " + warning));
        return verifyAnthropicCredential("Authorization", "Bearer " + token, "OAuth token");
    }

    private AuthResult verifyAnthropicCredential(String headerName, String headerValue, String label) {
        try {
            var client = getHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header(headerName, headerValue)
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 400 -> new AuthResult(true, label + " verified.");
                case 401 -> new AuthResult(false, label + " rejected (HTTP 401). It may be expired or invalid."
                        + apiErrorSuffix(response.body()));
                case 403 -> new AuthResult(true, label + " accepted (HTTP 403). It may have restricted permissions.");
                default -> new AuthResult(false, "Unexpected response (HTTP " + response.statusCode() + "). The "
                        + label.toLowerCase() + " may be invalid." + apiErrorSuffix(response.body()));
            };
        } catch (Exception e) {
            return new AuthResult(false, "Could not reach api.anthropic.com: " + e.getMessage());
        }
    }

    private void setupClaudeOauth(SpawnConfig config, Prompts prompts, AccountTarget target) {
        System.out.println("  A Pro/Max subscription does not come with an API key. What it can");
        System.out.println("  produce is a long-lived OAuth token (valid about a year):");
        System.out.println();
        System.out.println("    1. Install Claude Code: " + TerminalLink.link("https://claude.com/claude-code"));
        System.out.println("    2. Sign in with the subscription account: run " + styled(BOLD, "claude")
                + ", then " + styled(BOLD, "/login"));
        System.out.println("    3. Run " + styled(BOLD, "claude setup-token"));
        System.out.println("    4. Copy the token it prints (it starts with '"
                + SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX + "')");
        System.out.println();
        System.out.println("  Re-run 'isx init' to paste a fresh token once this one expires.");
        System.out.println();

        if (hostHasCommand("claude")) {
            System.out.println("  Found 'claude' CLI on this host — steps 1 and 2 are already done.");
            if (askConfirmation(prompts, "  Run 'claude setup-token' now?", true)) {
                runClaudeSetupToken();
            }
        } else {
            System.out.println("  'claude' CLI not found on this host — follow the steps above on any");
            System.out.println("  machine where it is installed, then paste the token here.");
        }

        while (true) {
            System.out.print("  Paste your OAuth token (or press Enter to skip): ");
            var token = askSecret(prompts);
            if (token.isBlank()) {
                System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                break;
            }

            System.out.println("  Verifying OAuth token...");
            var result = verifyOauthToken(token);
            if (result.verified()) {
                System.out.println("  " + styled(BOLD + GREEN, "\u2713 " + result.message()));
                saveOauthConfig(config, target, token);
                System.out.println("  Claude auth configuration saved.");
                break;
            } else {
                System.out.println("  " + result.message());
                switch (askVerificationFailureAction(prompts)) {
                    case SKIP -> {
                        System.out.println("  Skipped Claude setup. Configure later with 'isx init'.");
                        break;
                    }
                    case SAVE_UNVERIFIED -> {
                        saveOauthConfig(config, target, token);
                        System.out.println("  Claude auth configuration saved (unverified).");
                        break;
                    }
                    case RETRY -> {
                        continue;
                    }
                }
                break;
            }
        }
    }

    /** Runs 'claude setup-token' attached to the terminal, so its prompts reach the user. */
    void runClaudeSetupToken() {
        try {
            var pb = new ProcessBuilder("claude", "setup-token");
            pb.inheritIO();
            var process = pb.start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("  'claude setup-token' timed out after 2 minutes.");
            } else if (process.exitValue() != 0) {
                System.err.println("  'claude setup-token' exited with code " + process.exitValue() + ".");
            } else {
                System.out.println("  Token generation complete.");
            }
        } catch (Exception e) {
            System.err.println("  Failed to run 'claude setup-token': " + e.getMessage());
        }
    }

    AuthResult verifyVertexConfig(String region, String projectId) {
        if (!commandExists("gcloud")) {
            return new AuthResult(false,
                    "gcloud CLI not found. Install it from " + TerminalLink.link("https://cloud.google.com/sdk/docs/install") + "\n"
                    + "  Then run: gcloud auth login");
        }

        String accessToken;
        try {
            var pb = new ProcessBuilder("gcloud", "auth", "print-access-token");
            var process = pb.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new AuthResult(false, "gcloud timed out. Check your gcloud configuration.");
            }
            var stdout = new String(process.getInputStream().readAllBytes()).strip();
            var stderr = new String(process.getErrorStream().readAllBytes()).strip();
            if (process.exitValue() != 0 || stdout.isBlank()) {
                var detail = !stderr.isBlank() ? stderr : stdout;
                return new AuthResult(false,
                        "gcloud auth failed" + (detail.isBlank() ? "" : ": " + detail)
                        + "\n  Run: gcloud auth login");
            }
            accessToken = stdout;
        } catch (Exception e) {
            return new AuthResult(false, "Failed to run gcloud: " + e.getMessage());
        }

        try {
            var host = ProxyConfig.vertexHost(region);
            var url = "https://" + host + "/v1/projects/" + projectId
                    + "/locations/" + region
                    + "/publishers/anthropic/models/claude-sonnet-4-6:rawPredict";
            var client = getHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 400, 404 -> new AuthResult(true,
                        "Vertex AI verified (region: " + region + ", project: " + projectId + ").");
                case 401 -> new AuthResult(false,
                        "Vertex AI authentication failed (HTTP 401). Run: gcloud auth login");
                case 403 -> new AuthResult(false,
                        "Vertex AI access denied (HTTP 403). Check that the Vertex AI API is enabled\n"
                        + "  for project '" + projectId + "' and your account has the required permissions.");
                default -> new AuthResult(false,
                        "Unexpected Vertex AI response (HTTP " + response.statusCode() + ").");
            };
        } catch (Exception e) {
            return new AuthResult(false, "Could not reach Vertex AI endpoint: " + e.getMessage());
        }
    }

    /**
     * GitHub's fine-grained PAT creation form, pre-filled through its template-URL query
     * parameters with the permissions the agent needs: read/write on Contents, Issues and Pull
     * requests, and read on Email addresses so {@link #checkGitHubEmail} can find the commit
     * identity. GitHub's permission picker hides anything not yet added, so without the
     * pre-fill the email permission is easy to miss. Resource owner, repository access and
     * expiration are left for the user to choose on the page.
     */
    static final String GH_PAT_NEW_URL = "https://github.com/settings/personal-access-tokens/new"
            + "?name=isx&description=Agent+token+for+isx+instances"
            + "&contents=write&issues=write&pull_requests=write&emails=read";

    /**
     * Last-resort fallback: reuse the host's authenticated 'gh' CLI token. This is deliberately
     * discouraged — the 'gh' login is almost always your personal identity, so the agent would act
     * as you with your (usually broad) scopes, defeating the point of an isolated environment. It is
     * offered only when the user skips the dedicated-PAT prompt, and defaults to No. Returns true if
     * a token was verified and saved.
     */
    /**
     * Outcome of the optional 'gh' CLI token fallback.
     * <ul>
     *   <li>{@code SAVED} — a token was verified and persisted; GitHub setup is done.
     *   <li>{@code NOT_OFFERED} — gh is unavailable or the user declined reuse; no attempt made.
     *   <li>{@code FAILED} — reuse was attempted but the token could not be read or verified, so
     *       the caller should fall back to manual PAT entry (as the failure message promises).
     * </ul>
     */
    private enum GhTokenOutcome { SAVED, NOT_OFFERED, FAILED }

    private GhTokenOutcome offerGhCliToken(SpawnConfig config, Prompts prompts, AccountTarget account) {
        if (!ghCliLoggedIn()) {
            return GhTokenOutcome.NOT_OFFERED;
        }

        System.out.println("  An authenticated 'gh' CLI is available on this host.");
        System.out.println("  " + styled(DIM, "Not recommended: that login is almost certainly your personal identity,"
                + " so the agent would act as you with whatever scopes 'gh' holds. Prefer a dedicated"
                + " agent account and a fine-grained PAT (above)."));
        if (!askConfirmation(prompts, "  Reuse your personal 'gh' token anyway?", false)) {
            return GhTokenOutcome.NOT_OFFERED;
        }

        var token = readGhAuthToken();
        if (token == null || token.isBlank()) {
            System.out.println("  Could not read a token from 'gh auth token' — continuing with manual setup.");
            return GhTokenOutcome.FAILED;
        }
        var result = verifyGitHubToken(token, prompts);
        if (result == null) {
            System.out.println("  The 'gh' token failed verification — continuing with manual setup.");
            return GhTokenOutcome.FAILED;
        }
        if (result.email == null) {
            System.out.println("  " + styled(BOLD + YELLOW, "⚠ No email accessible — git commits will have no author email."));
        }
        saveGitHubToken(config, account, token, result.email);
        return GhTokenOutcome.SAVED;
    }

    /**
     * Whether saving a credential into this namespace has something to preserve, and so must ask
     * where it goes rather than assume {@link AccountTarget#FRESH} -- which replaces every account.
     *
     * <p>Any account counts, usable or not. Asking whether the <em>default</em> has a credential
     * is not enough: a default without a token beside another account with one would skip the
     * menu, and the next save would delete that other account without a word.
     */
    static boolean hasAccountsToPreserve(SpawnConfig config, String namespace) {
        return !NamespaceAccounts.names(config, namespace).isEmpty();
    }

    /**
     * Persist a verified GitHub token (and email, if any) and print the matching "saved" line.
     *
     * <p>{@code target} is passed rather than parked on the instance: the Claude flow's
     * equivalent mutable field is how a credential wipe shipped once already (#741), because a
     * code path that returns early leaves a stale target for a later write to act on. An
     * argument cannot go stale.
     */
    private void saveGitHubToken(SpawnConfig config, AccountTarget target, String token, String email) {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("token", token);
        if (email != null) values.put("email", email);
        saveAccount(config, GhSetup.NAMESPACE,
                dev.incusspawn.config.AccountResolver.shapeOf(config, GhSetup.NAMESPACE), target, values);
        config.save();
        System.out.println(email != null
                ? "  GitHub configuration saved."
                : "  GitHub configuration saved (without email).");
    }

    /**
     * Write one account's values the way {@code target} says: into that account alone, or as the
     * only account left.
     *
     * <p>Nothing is written unless the values carry the credential, so skipping the secret --
     * or every prompt -- neither leaves an account without one behind nor clears the others
     * for a "replace all", which would swap a working credential for a region and nothing else.
     * Only an account that already exists, whose credential stays put, may have its other
     * values edited on their own.
     *
     * @return whether anything was written
     */
    static boolean saveAccount(SpawnConfig config, String namespace, dev.incusspawn.config.AccountShape shape,
                               AccountTarget target, Map<String, String> values) {
        if (values.isEmpty()) return false;
        var keepsCredential = !target.replaceOthers() && dev.incusspawn.config.AccountResolver
                .accountNames(config.tree(), namespace, shape).contains(target.name());
        if (!keepsCredential && !shape.hasCredential(values)) return false;
        if (target.replaceOthers()) {
            NamespaceAccounts.replaceAll(config, namespace, target.name(), values);
        } else {
            values.forEach((key, value) -> NamespaceAccounts.put(config, namespace, target.name(), key, value));
        }
        return true;
    }

    /**
     * Whether the host has an authenticated 'gh' CLI. 'gh auth status' exits 0 only when gh is
     * installed and logged in; a missing binary or no active login exits non-zero, so this one
     * check gates the whole reuse fallback.
     */
    boolean ghCliLoggedIn() {
        return runHostCapturingExit("gh", "auth", "status") == 0;
    }

    /** Reads the token backing the current 'gh' login (stdout of 'gh auth token'). */
    String readGhAuthToken() {
        try {
            // Discard stderr: gh warnings must not leak into the interactive init flow, and an
            // undrained stderr pipe could fill and block the process until the timeout below.
            var p = new ProcessBuilder("gh", "auth", "token")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Prints step-by-step instructions for creating a fine-grained PAT and offers to open the page. */
    private void printGitHubPatGuide(Prompts prompts) {
        System.out.println("  To create a fine-grained PAT (ideally signed in as the agent's account,");
        System.out.println("  not your personal one):");
        System.out.println();
        System.out.println("    1. Open the pre-filled token page:");
        System.out.println("       " + styled(BOLD, TerminalLink.link(GH_PAT_NEW_URL)));
        System.out.println("       It already grants what isx needs:");
        System.out.println("         Contents, Issues, Pull requests  " + styled(DIM, "read and write"));
        System.out.println("         Email addresses                  " + styled(DIM, "read (stamps your git commit identity)"));
        System.out.println("    2. Check " + styled(BOLD, "Resource owner") + " is the agent's account, set an expiration, and");
        System.out.println("       under " + styled(BOLD, "Repository access") + " choose the repos to grant.");
        System.out.println("    3. Click " + styled(BOLD, "Generate token")
                + " and copy the value (starts with 'github_pat_').");
        System.out.println();
        System.out.println("  " + styled(DIM, "Adding a permission by hand: under Permissions, click 'Add permissions' and"));
        System.out.println("  " + styled(DIM, "search for it. Avoid admin, org, and delete permissions unless you need them."));
        System.out.println();

        System.out.println("  Already have a token? Answer 'n' and paste it at the next prompt.");
        if (askConfirmation(prompts, "  Open the pre-filled token page in your browser now?", true)) {
            if (openUrl(GH_PAT_NEW_URL)) {
                System.out.println("  Opened your browser — finish there, then paste the token below.");
            } else {
                System.out.println("  Could not open a browser — visit the URL above manually.");
            }
        }
        System.out.println();
    }

    /**
     * Account menu for any credential namespace, driven entirely by the namespace name.
     *
     * <p>Nothing here knows what a GitHub token is, so the same menu serves GitHub, Bob, OpenAI
     * and any credential a YAML tool declares. Claude keeps its own menu: its accounts are typed,
     * carry an auth mode, and it alone reports which of them can answer {@code isx ask}. Both
     * answer in the same vocabulary, an {@link AccountTarget}.
     *
     * <p>The menu writes nothing except where it says so ('d', 'x'): what a target means is
     * applied only once a credential has been collected, so an abandoned flow leaves the file
     * exactly as it was.
     *
     * @return the account to write credentials into, or empty to leave everything as it is
     */
    Optional<AccountTarget> chooseAccountTarget(SpawnConfig config, String namespace, String label,
                                                Prompts prompts) {
        while (true) {
            var accounts = NamespaceAccounts.names(config, namespace);
            var defaultName = NamespaceAccounts.defaultName(config, namespace);
            if (accounts.isEmpty()) return Optional.of(AccountTarget.FRESH);

            // One credential is the common case and should not have to learn about accounts to
            // be replaced -- whether it is a flat one from an older isx or accounts.default.
            if (accounts.size() == 1) {
                var only = accounts.get(0);
                System.out.println("  " + label + ": configured.");
                System.out.println("    r. Replace it");
                System.out.println("    a. Add a second account (keeps the current one)");
                System.out.print("  Choice (Enter to keep as-is): ");
                var choice = readInput(prompts.readLine()).toLowerCase(java.util.Locale.ROOT);
                switch (choice) {
                    case "" -> { return Optional.empty(); }
                    case "r" -> { return Optional.of(new AccountTarget(only, true)); }
                    case "a" -> {
                        var name = askAccountName(prompts, new java.util.LinkedHashSet<>(accounts));
                        return name.isEmpty() ? Optional.empty() : Optional.of(new AccountTarget(name, false));
                    }
                    default -> { continue; }
                }
            }

            System.out.println("  " + label + " accounts:");
            for (var name : accounts) {
                System.out.println("    - " + name + (name.equals(defaultName) ? "  (default)" : ""));
            }
            System.out.println();
            System.out.println("    a. Add another account");
            System.out.println("    e. Replace an existing account's credentials");
            System.out.println("    r. Replace all with a single account");
            System.out.println("    d. Change which account is the default");
            System.out.println("    x. Remove an account");
            System.out.println("    n. Rename an account");
            System.out.print("  Choice (Enter to keep as-is): ");
            var choice = readInput(prompts.readLine()).toLowerCase(java.util.Locale.ROOT);

            switch (choice) {
                case "" -> { return Optional.empty(); }
                case "a" -> {
                    var name = askAccountName(prompts, new java.util.LinkedHashSet<>(accounts));
                    return name.isEmpty() ? Optional.empty() : Optional.of(new AccountTarget(name, false));
                }
                case "e" -> {
                    System.out.print("  Name of the account to replace: ");
                    var name = readInput(prompts.readLine());
                    if (accounts.contains(name)) return Optional.of(new AccountTarget(name, false));
                    if (!name.isEmpty()) System.out.println("  No account named '" + name + "'.");
                }
                case "r" -> {
                    var going = new java.util.ArrayList<>(accounts);
                    going.remove(AccountTarget.FRESH.name());
                    if (confirmLosingAccounts(config, namespace, going, prompts)) return Optional.of(AccountTarget.FRESH);
                }
                case "n" -> renameAccount(config, namespace, new java.util.LinkedHashSet<>(accounts), prompts);
                // 'd' and 'x' save immediately and loop back to the listing, so an abort after
                // this point cannot leave the confirmation they printed a lie.
                case "d" -> {
                    System.out.print("  Name of the account to make default: ");
                    var name = readInput(prompts.readLine());
                    if (accounts.contains(name)) {
                        changeDefaultAccount(config, namespace, name,
                                () -> NamespaceAccounts.setDefault(config, namespace, name), prompts);
                    } else if (!name.isEmpty()) {
                        System.out.println("  No account named '" + name + "'.");
                    }
                }
                case "x" -> {
                    System.out.print("  Name of the account to remove: ");
                    var name = readInput(prompts.readLine());
                    if (accounts.contains(name) && confirmLosingAccounts(config, namespace, List.of(name), prompts)) {
                        var moving = runningFollowersIfDefault(config, namespace, name);
                        NamespaceAccounts.remove(config, namespace, name);
                        config.save();
                        System.out.println("  Removed account '" + name + "'.");
                        if (!moving.isEmpty()) refreshIdentities(moving);
                    } else if (!name.isEmpty() && !accounts.contains(name)) {
                        System.out.println("  No account named '" + name + "'.");
                    }
                }
                default -> { }
            }
        }
    }

    /**
     * Before accounts go away, name what still points at them -- instances pinned to them, whose
     * requests fail once they are gone, and templates whose {@code accounts:} name them, which
     * then refuse to branch -- and let the user back out. Removal never falls back to the
     * default on their behalf: for the client work this exists for, the default belongs to
     * someone else.
     *
     * @return true to go ahead
     */
    boolean confirmLosingAccounts(SpawnConfig config, String namespace, java.util.Collection<String> going,
                                  Prompts prompts) {
        if (going.isEmpty()) return true;
        var states = instanceAccountStates();
        var pins = pinsOf(states);
        var instances = new java.util.ArrayList<String>();
        var templates = new java.util.ArrayList<String>();
        for (var account : going) {
            AccountUsage.pinnedTo(pins, namespace, account)
                    .forEach(i -> instances.add(i + " (" + namespace + "=" + account + ")"));
            templatesNaming(namespace, account)
                    .forEach(t -> templates.add(t + " (" + namespace + ": " + account + ")"));
        }
        // Removing the default moves everything that follows it, which is every unpinned
        // instance -- not an error for them, but a change of principal worth saying out loud.
        var followers = going.contains(currentDefault(config, namespace))
                ? followersOf(states, namespace) : List.<String>of();
        if (instances.isEmpty() && templates.isEmpty() && followers.isEmpty()) return true;
        if (!followers.isEmpty()) {
            System.out.println("  These instances follow the " + namespace + " default, which is going,"
                    + " and would switch to the new default:");
            followers.forEach(i -> System.out.println("    - " + i));
        }
        if (!instances.isEmpty()) {
            System.out.println("  These instances are pinned to " + (going.size() == 1 ? "it" : "those accounts")
                    + " and would fail their requests:");
            instances.forEach(i -> System.out.println("    - " + i));
            System.out.println("  Re-point them first with 'isx account set', or 'isx account unset'"
                    + " to have them follow the default.");
        }
        if (!templates.isEmpty()) {
            System.out.println("  These templates name " + (going.size() == 1 ? "it" : "them")
                    + " under accounts: and could no longer be branched:");
            templates.forEach(t -> System.out.println("    - " + t));
        }
        return askConfirmation(prompts, "  Remove anyway?", false);
    }

    /**
     * Rename an account and follow it everywhere isx can: the default, and every instance
     * pinned to it. Template YAML is the user's file and is only reported, never rewritten.
     */
    void renameAccount(SpawnConfig config, String namespace, java.util.Set<String> accounts, Prompts prompts) {
        System.out.print("  Name of the account to rename: ");
        var from = readInput(prompts.readLine());
        if (from.isEmpty()) return;
        if (!accounts.contains(from)) {
            System.out.println("  No account named '" + from + "'.");
            return;
        }
        var to = askAccountName(prompts, accounts, "  New name for '" + from + "' (Enter to cancel): ");
        if (to.isEmpty()) return;
        NamespaceAccounts.rename(config, namespace, from, to);
        config.save();
        System.out.println("  Renamed '" + from + "' to '" + to + "'.");
        try {
            var repointed = renameInInstances(config, namespace, from, to);
            if (!repointed.isEmpty()) {
                System.out.println("  Re-pointed " + String.join(", ", repointed) + " to '" + to + "'.");
            }
        } catch (Exception e) {
            System.out.println("  Could not update the instances pinned to '" + from + "' ("
                    + e.getMessage() + "). Re-point them with 'isx account set <instance> "
                    + namespace + "=" + to + "'.");
        }
        var templates = templatesNaming(namespace, from);
        if (!templates.isEmpty()) {
            System.out.println("  " + (templates.size() == 1 ? "Template " : "Templates ")
                    + String.join(", ", templates) + (templates.size() == 1 ? " still names '" : " still name '")
                    + from + "' under accounts: -- change it to '" + to + "' in the YAML, or"
                    + " branching from " + (templates.size() == 1 ? "it" : "them") + " fails.");
        }
    }

    /** Every isx instance's account state, or none when Incus cannot be asked. Overridden in tests. */
    Map<String, InstanceRegistry.AccountState> instanceAccountStates() {
        try {
            return InstanceRegistry.accountStates(RuntimeServices.incus());
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, Map<String, String>> pinsOf(Map<String, InstanceRegistry.AccountState> states) {
        var pins = new LinkedHashMap<String, Map<String, String>>();
        states.forEach((name, state) -> { if (!state.pins().isEmpty()) pins.put(name, state.pins()); });
        return pins;
    }

    private static List<String> followersOf(Map<String, InstanceRegistry.AccountState> states, String namespace) {
        return states.entrySet().stream().filter(e -> e.getValue().followsDefault(namespace))
                .map(Map.Entry::getKey).toList();
    }

    private static String currentDefault(SpawnConfig config, String namespace) {
        try {
            return dev.incusspawn.config.AccountResolver.effectiveAccount(config, namespace, null);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The running instances that follow {@code account} as the default, which removing it moves. */
    private List<String> runningFollowersIfDefault(SpawnConfig config, String namespace, String account) {
        if (!account.equals(currentDefault(config, namespace))) return List.of();
        return instanceAccountStates().entrySet().stream()
                .filter(e -> e.getValue().followsDefault(namespace) && e.getValue().running())
                .map(Map.Entry::getKey).toList();
    }

    enum DefaultChange { SWITCH, KEEP, CANCEL }

    /**
     * Make {@code to} the {@code namespace} default, applied by {@code apply}. Every instance that
     * does not pin this credential follows the default, so this changes whose account they spend
     * on their next request. When there are any, they are named and the user chooses: switch them
     * (the default, since that is what following the default means), keep them on the account
     * they use now -- by pinning it for them -- or cancel. Switched instances that are running
     * have their git identity brought in line straight away, as {@code isx account set} does.
     *
     * @return whether the default changed
     */
    boolean changeDefaultAccount(SpawnConfig config, String namespace, String to, Runnable apply,
                                 Prompts prompts) {
        var from = currentDefault(config, namespace);
        if (to.equals(from)) {
            System.out.println("  '" + to + "' is already the default.");
            return false;
        }
        var states = instanceAccountStates();
        var followers = new LinkedHashMap<String, InstanceRegistry.AccountState>();
        states.forEach((name, state) -> { if (state.followsDefault(namespace)) followers.put(name, state); });

        var decision = followers.isEmpty() ? DefaultChange.SWITCH
                : askDefaultChange(config, namespace, from, to, followers, prompts);
        if (decision == DefaultChange.CANCEL) {
            System.out.println("  Default left as '" + from + "'.");
            return false;
        }
        if (decision == DefaultChange.KEEP) {
            // Pinned before the default moves, so none of them is ever served the new one.
            for (var failure : pinInstances(config, List.copyOf(followers.keySet()), namespace, from)) {
                System.out.println("  " + failure);
            }
        }
        apply.run();
        config.save();
        System.out.println("  Default account is now '" + to + "'.");
        if (decision == DefaultChange.KEEP) {
            System.out.println("  " + String.join(", ", followers.keySet()) + " stay on '" + from
                    + "', now pinned to it.");
        } else if (!followers.isEmpty()) {
            var running = followers.entrySet().stream().filter(e -> e.getValue().running())
                    .map(Map.Entry::getKey).toList();
            if (!running.isEmpty()) refreshIdentities(running);
        }
        return true;
    }

    private DefaultChange askDefaultChange(SpawnConfig config, String namespace, String from, String to,
                                           Map<String, InstanceRegistry.AccountState> followers, Prompts prompts) {
        System.out.println("  These instances follow the " + namespace + " default and would switch from '"
                + from + "' to '" + to + "':");
        var setup = dev.incusspawn.config.AccountSelection.namespaceSetups(config).get(namespace);
        var refused = new java.util.ArrayList<String>();
        followers.forEach((name, state) -> {
            System.out.println("    - " + name + (state.running() ? " (running)" : ""));
            if (!dev.incusspawn.config.AccountSelection.requiredRebuild(config, setup, namespace, to,
                    state.bakedIdentities()).isEmpty()) {
                refused.add(name);
            }
        });
        if (!refused.isEmpty()) {
            System.out.println("  " + String.join(", ", refused) + (refused.size() == 1 ? " was" : " were")
                    + " built for another auth mode than '" + to + "': " + (refused.size() == 1 ? "its " : "their ")
                    + namespace + " requests would be refused until pinned to an account " + (refused.size() == 1 ? "it" : "they")
                    + " can use. Keeping " + (followers.size() == 1 ? "it" : "them") + " on '" + from
                    + "' avoids that.");
        }
        while (true) {
            System.out.print("  Enter to switch them, k to keep them on '" + from + "' (pins it for them),"
                    + " c to cancel: ");
            var line = prompts.readLine();
            if (line == null) return DefaultChange.CANCEL;   // EOF: never change principals unasked
            switch (line.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "", "s" -> { return DefaultChange.SWITCH; }
                case "k" -> { return DefaultChange.KEEP; }
                case "c" -> { return DefaultChange.CANCEL; }
                default -> System.out.println("  Please answer Enter, k or c.");
            }
        }
    }

    /**
     * Pin instances to the account they use now, as an explicit choice. Overridden in tests.
     *
     * @return one line per instance that could not be pinned, saying why
     */
    List<String> pinInstances(SpawnConfig config, List<String> instances, String namespace, String account) {
        var failures = new java.util.ArrayList<String>();
        var incus = RuntimeServices.incus();
        var setups = dev.incusspawn.config.AccountSelection.namespaceSetups(config);
        for (var instance : instances) {
            try {
                InstanceLifecycle.changeAccounts(incus, instance, config, setups, Map.of(namespace, account),
                        msg -> { }, msg -> System.out.println("  Warning: " + msg));
            } catch (RuntimeException e) {
                failures.add("Could not pin " + instance + " (" + e.getMessage() + "); it will switch.");
            }
        }
        return failures;
    }

    /** Bring running instances' baked git identity in line with their new account. Overridden in tests. */
    void refreshIdentities(List<String> instances) {
        var incus = RuntimeServices.incus();
        for (var instance : instances) {
            InstanceLifecycle.reconcileAccountIdentities(incus, instance,
                    msg -> System.out.println("  " + instance + ": " + msg),
                    msg -> System.out.println("  Warning: " + msg));
        }
    }

    /** Re-point instances after a rename, and tell the proxy. Overridden in tests. */
    List<String> renameInInstances(SpawnConfig config, String namespace, String from, String to) {
        var repointed = AccountSelection.renameInInstances(RuntimeServices.incus(), config, namespace, from, to);
        if (!repointed.isEmpty()) ProxyService.signalAccountRefresh();
        return repointed;
    }

    /** Templates whose own {@code accounts:} name this account. Overridden in tests. */
    List<String> templatesNaming(String namespace, String account) {
        try {
            return ImageDef.loadAll(msg -> { }).values().stream()
                    .filter(def -> account.equals(def.getAccounts().get(namespace)))
                    .map(ImageDef::getName)
                    .sorted()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    void setupGitHubAuth(SpawnConfig config, Prompts prompts) {
        // The same menu every non-Claude credential gets, so adding a second GitHub identity
        // needs no GitHub-specific UX. It answers FRESH straight away when nothing is saved,
        // and comes back after each saved token so another identity fits in the same run;
        // Enter (or EOF) moves on.
        var account = chooseAccountTarget(config, GhSetup.NAMESPACE, "GitHub", prompts);
        while (account.isPresent() && collectGitHubToken(config, prompts, account.get())) {
            System.out.println();
            account = chooseAccountTarget(config, GhSetup.NAMESPACE, "GitHub", prompts);
        }
    }

    /** Walks the user through one token for {@code account}; returns true once one is saved. */
    private boolean collectGitHubToken(SpawnConfig config, Prompts prompts, AccountTarget account) {
        // Prioritize a dedicated agent identity: walk the user through minting a fine-grained PAT.
        printGitHubPatGuide(prompts);

        while (true) {
            System.out.print("  GitHub PAT for the agent (or press Enter to skip): ");
            var token = askSecret(prompts);
            if (token.isBlank()) {
                // Last resort only: reuse the host's personal 'gh' login. Discouraged — it makes the
                // agent act as you — so it is offered here (default No), never as the primary path.
                var outcome = offerGhCliToken(config, prompts, account);
                if (outcome == GhTokenOutcome.SAVED) {
                    return true;
                }
                if (outcome == GhTokenOutcome.FAILED) {
                    // The gh fallback promised to "continue with manual setup" — re-prompt for a PAT.
                    continue;
                }
                reportGitHubSkipped(config);
                return false;
            }

            var result = verifyPastedGitHubToken(token, prompts);
            if (result == null) {
                if (!askConfirmation(prompts, "  Try again?", true)) {
                    reportGitHubSkipped(config);
                    return false;
                }
                continue;
            }

            if (result.email == null) {
                var replacement = offerTokenWithEmail(token, prompts);
                if (replacement != null) {
                    token = replacement.token();
                    result = replacement.result();
                }
            }
            // Same target for every save in this flow, so a re-minted PAT lands in the account
            // the user picked rather than back in the flat field.
            saveGitHubToken(config, account, token, result.email);
            return true;
        }
    }

    private record VerifiedToken(String token, GitHubVerifyResult result) {}

    /**
     * For a token that verified but cannot see an email: explains how to grant it, and takes a
     * replacement. A mistyped or half-pasted replacement must not end the step, so this asks
     * again until one verifies or the user settles for the original.
     *
     * @return the replacement, or null to keep the original token without an email
     */
    private VerifiedToken offerTokenWithEmail(String token, Prompts prompts) {
        System.out.println("  " + styled(BOLD + YELLOW, "⚠ No email accessible — git commits will have no author email."));
        System.out.println("  To fix this, either:");
        System.out.println("    • Edit your PAT at " + TerminalLink.link(patSettingsUrl(token)) + ":");
        System.out.println("      under Permissions, click 'Add permissions', search for 'Email addresses'");
        System.out.println("      and set it to Read-only (for a classic token, add the 'user:email' scope)");
        System.out.println("    • Or make your email public at " + TerminalLink.link("https://github.com/settings/profile"));
        while (true) {
            System.out.print("  Enter new PAT with email permission, or press Enter to keep the one above"
                    + " (without email): ");
            var newToken = askSecret(prompts);
            if (newToken.isBlank()) return null;

            var newResult = verifyPastedGitHubToken(newToken, prompts);
            if (newResult == null) {
                System.out.println("  That token failed verification — try again, or press Enter to keep the one above.");
                continue;
            }
            if (newResult.email == null) {
                System.out.println("  (still without email)");
            }
            return new VerifiedToken(newToken, newResult);
        }
    }

    /**
     * A skip leaves every saved account as it was, so "skipped setup" is only true when there
     * are none -- not after adding a first account this run, nor on a re-run that has some.
     */
    private static void reportGitHubSkipped(SpawnConfig config) {
        System.out.println(hasAccountsToPreserve(config, GhSetup.NAMESPACE)
                ? "  No token added for this account."
                : "  Skipped GitHub setup. You can configure it later by re-running 'isx init'.");
    }

    /**
     * {@link #verifyGitHubToken} for a token the user typed, after checking its shape: kept in
     * the flow rather than the seam, which tests replace, so the warning stays testable.
     */
    private GitHubVerifyResult verifyPastedGitHubToken(String token, Prompts prompts) {
        githubTokenShapeWarning(token).ifPresent(warning -> System.out.println("  " + warning));
        return verifyGitHubToken(token, prompts);
    }

    /**
     * Non-fatal shape check; verification against the API remains the authority. The input is
     * hidden, so a keystroke typed before the paste goes unseen: name it rather than leave the
     * user with a bare 401.
     */
    static Optional<String> githubTokenShapeWarning(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        if (GITHUB_TOKEN_PREFIXES.stream().anyMatch(token::startsWith)) return Optional.empty();
        for (var prefix : GITHUB_TOKEN_PREFIXES) {
            int at = token.indexOf(prefix);
            if (at > 0) {
                return Optional.of("Note: " + at + (at == 1 ? " character comes" : " characters come")
                        + " before '" + prefix + "' — something was typed before the paste.");
            }
        }
        return Optional.of("Note: token does not start with 'github_pat_' (fine-grained) or 'ghp_' (classic).");
    }

    record GitHubVerifyResult(String login, String email) {}

    record EmailParseResult(java.util.List<String> verified, String primary) {}

    /** @param prompts picks between several verified emails, when the account has them */
    GitHubVerifyResult verifyGitHubToken(String token, Prompts prompts) {
        System.out.println("  Testing GitHub token...");
        try {
            var client = getHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user"))
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("  Authentication failed (HTTP " + response.statusCode() + ").");
                return null;
            }

            var json = JSON.readTree(response.body());
            var login = json.has("login") ? json.get("login").asText(null) : null;
            var email = json.has("email") && !json.get("email").isNull() ? json.get("email").asText(null) : null;
            if (email == null) {
                email = checkGitHubEmail(client, token, prompts);
            }

            if (login != null) {
                if (email != null) {
                    System.out.println("  " + styled(BOLD + GREEN, "\u2713 Token verified. Authenticated as: " + login + " <" + email + ">"));
                } else {
                    System.out.println("  " + styled(BOLD + GREEN, "\u2713 Token verified. Authenticated as: " + login));
                }
            } else {
                System.out.println("  Token verified (could not determine username).");
            }

            return new GitHubVerifyResult(login, email);
        } catch (Exception e) {
            System.out.println("  Could not test token: " + e.getMessage());
            return null;
        }
    }

    private String checkGitHubEmail(java.net.http.HttpClient client, String token, Prompts prompts) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user/emails"))
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            var parsed = parseGitHubEmails(response.body());
            if (parsed == null) {
                return null;
            }
            return chooseEmail(parsed, prompts);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The address git commits will carry, when the token can see several verified ones.
     * Enter, EOF and anything out of range take the first entry, which
     * {@link #parseGitHubEmails} makes the noreply address whenever there is one -- so the
     * default never publishes a real address that a private one could have covered.
     */
    static String chooseEmail(EmailParseResult parsed, Prompts prompts) {
        if (parsed.verified.size() == 1) {
            return parsed.verified.get(0);
        }

        System.out.println("  Multiple verified emails found:");
        for (int i = 0; i < parsed.verified.size(); i++) {
            var label = parsed.verified.get(i);
            if (label.endsWith("@users.noreply.github.com")) label += " (private, recommended)";
            else if (label.equals(parsed.primary)) label += " (primary)";
            System.out.println("    " + (i + 1) + ". " + label);
        }
        System.out.print("  Select email for git commits [1]: ");
        var choice = readInput(prompts.readLine());
        if (choice.isEmpty()) {
            return parsed.verified.get(0);
        }
        try {
            int idx = Integer.parseInt(choice) - 1;
            if (idx >= 0 && idx < parsed.verified.size()) {
                return parsed.verified.get(idx);
            }
        } catch (NumberFormatException ignored) {}
        return parsed.verified.get(0);
    }

    static EmailParseResult parseGitHubEmails(String json) {
        try {
            var emails = JSON.readTree(json);
            var verifiedEmails = new ArrayList<String>();
            String primaryEmail = null;
            String noreplyEmail = null;
            for (var entry : emails) {
                if (!entry.path("verified").asBoolean(false)) continue;
                var email = entry.path("email").asText(null);
                if (email == null) continue;
                if (email.endsWith("@users.noreply.github.com")) {
                    noreplyEmail = email;
                    continue;
                }
                verifiedEmails.add(email);
                if (entry.path("primary").asBoolean(false)) {
                    primaryEmail = email;
                }
            }
            if (noreplyEmail != null) {
                verifiedEmails.add(0, noreplyEmail);
            }
            if (verifiedEmails.isEmpty()) {
                return null;
            }
            return new EmailParseResult(java.util.List.copyOf(verifiedEmails), primaryEmail);
        } catch (Exception e) {
            return null;
        }
    }

    private static String patSettingsUrl(String token) {
        if (token.startsWith("github_pat_")) {
            return "https://github.com/settings/personal-access-tokens";
        }
        return "https://github.com/settings/tokens";
    }

    void printCurrentPaths(java.util.List<String> paths) {
        if (paths.isEmpty()) return;
        System.out.println("  Current paths:");
        printNumberedPaths(paths);
    }

    /**
     * The entry number an input refers to, or null if it is not one. Accepts "2" and, because
     * the list reads like numbered entries, "#2" too. Digits too many to parse still name an
     * entry -- one that cannot exist -- so they are never mistaken for a directory to add.
     */
    static Integer entryNumber(String input) {
        var m = java.util.regex.Pattern.compile("#?\\s*(\\d+)").matcher(input);
        if (!m.matches()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException tooLarge) {
            return Integer.MAX_VALUE;
        }
    }

    private void printNumberedPaths(java.util.List<String> paths) {
        for (int i = 0; i < paths.size(); i++) {
            System.out.println("    " + (i + 1) + ". " + paths.get(i));
        }
    }

    static String describeTool(String toolName, ToolSetup tool) {
        return tool.description().isBlank() ? toolName : tool.description();
    }

    void setupGenericToolCredentials(String toolName, ToolSetup tool, SpawnConfig config, Prompts prompts) {
        var desc = describeTool(toolName, tool);
        var proxyDef = tool.proxy();
        if (proxyDef == null) return;

        // A tool with a namespace of its own gets named accounts, through the same menu GitHub
        // uses: nothing below knows what the credential is. A tool without one (or borrowing
        // another tool's) has nothing to hold accounts in, and writes its paths directly.
        var namespace = proxyDef.getConfigNamespace();
        var shape = tool.accountShape();
        AccountTarget target = null;
        if (!namespace.isBlank()) {
            if (!hasAccountsToPreserve(config, namespace)) {
                target = AccountTarget.FRESH;
            } else {
                var chosen = chooseAccountTarget(config, namespace, desc, prompts);
                if (chosen.isEmpty()) return;
                target = chosen.get();
            }
        }

        var configTree = config.tree();
        // Only the account being written counts as "current": a new account starts empty, and
        // "replace all" replaces rather than offering to keep.
        var targetExists = target != null && !target.replaceOthers() && dev.incusspawn.config.AccountResolver
                .accountNames(configTree, namespace, shape).contains(target.name());
        var accountValues = new LinkedHashMap<String, String>();
        var sharedValues = new LinkedHashMap<String, String>();
        for (var entry : proxyDef.getConfiguration().entrySet()) {
            var configKey = entry.getKey();
            var configDef = entry.getValue();
            if (!configDef.getValue().isBlank()) continue;
            if (configDef.getConfigPath().isBlank()) continue;

            var label = configDef.getDescription().isBlank() ? configKey : configDef.getDescription();

            // Per account: what the shape says an identity holds. Everything else -- a licence
            // consent -- belongs to the namespace, and every account inherits it.
            var fullPath = proxyDef.fullConfigPath(configDef);
            var key = target != null && fullPath.startsWith(namespace + ".")
                    ? fullPath.substring(namespace.length() + 1) : "";
            var perAccount = !key.isEmpty() && shape.flatKeys().contains(key);
            String existing;
            if (perAccount) {
                existing = targetExists
                        ? dev.incusspawn.config.AccountResolver.value(configTree, namespace, target.name(), key)
                        : "";
            } else {
                existing = ToolProxyResolver.navigateConfigPath(configTree, fullPath);
            }
            boolean hasExisting = configDef.isConfirm() ? "true".equals(existing) : !existing.isBlank();
            if (hasExisting) {
                System.out.println("  " + label + ": " + maskSecret(existing));
                if (askConfirmation(prompts, "  Keep current?", true, true)) {
                    // A kept value still has to travel with a "replace" into the new layout.
                    if (perAccount) accountValues.put(key, existing);
                    continue;
                }
            }

            for (var helpLine : configDef.getHelp()) {
                System.out.println("  " + helpLine);
            }
            if (configDef.getHelp().isEmpty() && !configDef.getDescription().isBlank()) {
                System.out.println("  " + label);
            }

            String value;
            if (configDef.isConfirm()) {
                value = askConfirmation(prompts, "  " + label + "?", false, true) ? "true" : "false";
            } else {
                System.out.print("  " + label + " (or press Enter to skip): ");
                if (configDef.isSecret()) {
                    value = askSecret(prompts);
                } else {
                    value = readInput(prompts.readLine());
                }
            }
            if (value.isBlank()) continue;
            if (perAccount) {
                accountValues.put(key, value);
            } else {
                sharedValues.put(fullPath, value);
            }
        }

        boolean savedAny = false;
        if (target != null && !accountValues.isEmpty()) {
            savedAny = saveAccount(config, namespace, shape, target, accountValues);
            if (!savedAny) System.out.println("  No credential entered, so no account was changed.");
        }
        for (var shared : sharedValues.entrySet()) {
            config.setConfigByPath(shared.getKey(), shared.getValue());
            savedAny = true;
        }

        if (savedAny) {
            config.save();
            System.out.println("  " + desc + " credentials saved.");
        } else {
            System.out.println("  Skipped. Configure later with 'isx init'.");
        }
    }

    void setupPathList(
            java.util.function.Function<SpawnConfig, java.util.List<String>> getter,
            java.util.function.BiConsumer<SpawnConfig, java.util.List<String>> setter,
            String skipMessage, SpawnConfig config, Prompts prompts) {
        var existing = getter.apply(config);
        printCurrentPaths(existing);

        var paths = new java.util.ArrayList<>(existing);
        while (true) {
            var hasEntries = !paths.isEmpty();
            System.out.print(hasEntries
                    ? "  Add a local directory, type an entry's number to remove it, or press Enter to finish: "
                    : "  Add a local directory (or press Enter to skip): ");
            var input = readInput(prompts.readLine());
            if (input.isEmpty()) break;

            if (input.contains("://")) {
                System.out.println("  That looks like a URL — this needs a local directory path (e.g. ~/my-templates).");
                continue;
            }

            var entryNumber = entryNumber(input);
            if (entryNumber != null) {
                int index = entryNumber - 1;
                if (index >= 0 && index < paths.size()) {
                    System.out.println("  Removed: " + paths.remove(index));
                    printNumberedPaths(paths);
                } else if (paths.isEmpty()) {
                    System.out.println("  There are no entries to remove.");
                } else {
                    System.out.println("  No such entry — type a number from 1 to " + paths.size() + ".");
                }
                continue;
            }
            if (input.startsWith("#")) {
                System.out.println("  To remove an entry, type just its number (e.g. 1).");
                continue;
            }

            var expanded = HostResourceSetup.expandHostTilde(input);
            var path = java.nio.file.Path.of(expanded);
            if (!java.nio.file.Files.isDirectory(path)
                    && !askConfirmation(prompts, "  '" + input + "' is not an existing directory. Add it anyway?", false)) {
                continue;
            }
            var resolved = path.toAbsolutePath().normalize().toString();
            if (paths.contains(resolved)) {
                System.out.println("  Already in the list.");
            } else {
                paths.add(resolved);
                System.out.println("  Added: " + resolved);
                printNumberedPaths(paths);
            }
        }

        if (!paths.equals(existing)) {
            setter.accept(config, paths);
            config.save();
            System.out.println("  Paths saved.");
        } else if (paths.isEmpty()) {
            System.out.println(skipMessage);
        } else {
            System.out.println("  Paths unchanged.");
        }
    }
}
