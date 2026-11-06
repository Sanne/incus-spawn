package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.AccountUsage;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.proxy.InstanceRegistry;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.ByteLines;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.SystemTrust;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqValue;

import static dev.incusspawn.util.BuildOutput.BOLD;
import static dev.incusspawn.util.BuildOutput.DIM;
import static dev.incusspawn.util.BuildOutput.styled;
import static dev.incusspawn.command.BaseCommand.askYesNo;
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
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern ENTRY_NUMBER = Pattern.compile("#?\\s*(\\d+)");

    private HttpClient httpClient;
    /** What {@link #toolsOf} loads from, and for which search paths. */
    private dev.incusspawn.tool.ToolDefLoader toolsLoader;
    private List<String> toolsSearchPaths;

    // Not a static final: GraalVM native-image would capture it at build time.
    // HTTP/1.1: Java's default HTTP/2 ALPN negotiation can cause the first TLS
    // request in a native-image JVM to fail, then succeed on retry.
    private HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = SystemTrust.httpClient().version(HttpClient.Version.HTTP_1_1).build();
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

    private static final String ADD_API_KEY_LATER = "No API key added. Add one later with 'isx account add "
            + SpawnConfig.ClaudeConfig.NAMESPACE + "'.";

    private static final String CLAUDE_SKIPPED = "Skipped Claude setup. Configure later with 'isx account add "
            + SpawnConfig.ClaudeConfig.NAMESPACE + "'.";

    private static final List<String> KNOWN_TOOL_ORDER = List.of("claude", "gh", "bob", "codex");

    List<String> selectCredentials(Map<String, ToolSetup> allTools, SpawnConfig config, Prompts prompts) {
        var configTree = config.tree();

        var toolsWithProxy = new ArrayList<Map.Entry<String, ToolSetup>>();
        for (var entry : allTools.entrySet()) {
            // A tool behind a feature gate is offered once its feature is on: until then the
            // proxy would not use its credential, and its name alone tells a newcomer nothing (#1441)
            if (BuildTools.isFeatureGated(entry.getValue(), config)) continue;
            if (CredentialCheck.hasCredentialStep(entry.getValue())) toolsWithProxy.add(entry);
        }

        if (toolsWithProxy.isEmpty()) {
            BuildOutput.step("No tools with proxy credentials found.");
            return List.of();
        }

        toolsWithProxy.sort(Comparator.<Map.Entry<String, ToolSetup>, Integer>comparing(
                        e -> {
                            int idx = KNOWN_TOOL_ORDER.indexOf(e.getKey());
                            return idx >= 0 ? idx : Integer.MAX_VALUE;
                        })
                .thenComparing(Map.Entry::getKey));

        BuildOutput.step("Which credentials do you want to configure?");
        for (int i = 0; i < toolsWithProxy.size(); i++) {
            var entry = toolsWithProxy.get(i);
            var toolName = entry.getKey();
            var tool = entry.getValue();
            var desc = describeTool(toolName, tool);
            var tag = isToolConfigured(toolName, tool, config, configTree, allTools) ? " [configured]" : "";
            prompts.say((i + 1) + ". " + desc + tag);
        }
        var input = readInput(prompts.ask("Enter numbers separated by commas, 'all', or press Enter to skip: "));
        if (input.isBlank()) {
            BuildOutput.step("Skipped credential setup.");
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
            SpawnConfig config, io.hyperfoil.tools.jjq.value.JqValue configTree,
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
            io.hyperfoil.tools.jjq.value.JqValue configTree, Map<String, ToolSetup> allTools) {
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
            BuildOutput.step("Detected Vertex AI configuration from environment:");
            prompts.say("Region:  " + (region.isBlank() ? "(not set)" : region));
            prompts.say("Project: " + (projectId.isBlank() ? "(not set)" : projectId));

            if (region.isBlank() || projectId.isBlank()) {
                BuildOutput.step("CLOUD_ML_REGION and ANTHROPIC_VERTEX_PROJECT_ID must both be set for verification.");
                BuildOutput.step("Continuing with manual setup");
            } else {
                var result = verifying("Verifying Vertex AI configuration", () -> verifyVertexConfig(region, projectId));
                if (result.verified()) {
                    BuildOutput.ok(result.message());
                    if (askYesNo(prompts, envAccountPrompt(config, target, "Use this configuration?"), true)) {
                        saveVertexConfig(config, target, region, projectId);
                        BuildOutput.step("Claude auth configuration saved.");
                        return;
                    }
                    BuildOutput.step("Skipping environment config, continuing with manual setup");
                } else {
                    if (askYesNo(prompts, "Save anyway? Press Enter to configure manually.", false)) {
                        saveVertexConfig(config, target, region, projectId);
                        BuildOutput.step("Claude auth configuration saved (unverified).");
                        return;
                    }
                }
            }
        } else if (!envOauthToken.isBlank()) {
            BuildOutput.step("Detected CLAUDE_CODE_OAUTH_TOKEN from environment.");
            var oauthResult = verifying("Verifying OAuth token", () -> verifyOauthToken(envOauthToken));
            if (oauthResult.verified()) {
                BuildOutput.ok(oauthResult.message());
                if (askYesNo(prompts, envAccountPrompt(config, target, "Use this token?"), true)) {
                    saveOauthConfig(config, prompts, target, envOauthToken, true);
                    return;
                }
                BuildOutput.step("Skipping environment token, continuing with manual setup");
            } else {
                BuildOutput.step("Continuing with manual setup");
            }
        } else if (!envApiKey.isBlank()) {
            BuildOutput.step("Detected ANTHROPIC_API_KEY from environment.");
            var result = verifying("Verifying API key", () -> verifyAnthropicApiKey(envApiKey));
            if (result.verified()) {
                BuildOutput.ok(result.message());
                if (askYesNo(prompts, envAccountPrompt(config, target, "Use this key?"), true)) {
                    saveDirectConfig(config, target, envApiKey);
                    BuildOutput.step("Claude auth configuration saved.");
                    return;
                }
                BuildOutput.step("Skipping environment key, continuing with manual setup");
            } else {
                BuildOutput.step("Continuing with manual setup");
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

        BuildOutput.step("How do you authenticate with Claude?");
        prompts.say("1. Anthropic API key");
        prompts.say("2. Claude Pro, Max or Team subscription (OAuth token)");
        prompts.say("3. Google Cloud Vertex AI");
        var authChoice = readInput(prompts.ask("Choice (1/2/3, or Enter to skip): "));

        if (authChoice.equals("3")) {
            while (true) {
                var region = readInput(prompts.ask("CLOUD_ML_REGION (or press Enter to skip): "));
                if (region.isBlank()) {
                    BuildOutput.step(CLAUDE_SKIPPED);
                    return;
                }
                var projectId = readInput(prompts.ask("ANTHROPIC_VERTEX_PROJECT_ID: "));
                if (projectId.isBlank()) {
                    BuildOutput.step(CLAUDE_SKIPPED);
                    return;
                }

                var result = verifying("Verifying Vertex AI configuration", () -> verifyVertexConfig(region, projectId));
                if (result.verified()) {
                    BuildOutput.ok(result.message());
                    saveVertexConfig(config, target, region, projectId);
                    BuildOutput.step("Claude auth configuration saved.");
                    break;
                } else {
                    switch (askVerificationFailureAction(prompts)) {
                        case SKIP -> {
                            BuildOutput.step(CLAUDE_SKIPPED);
                            break;
                        }
                        case SAVE_UNVERIFIED -> {
                            saveVertexConfig(config, target, region, projectId);
                            BuildOutput.step("Claude auth configuration saved (unverified).");
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
            if (!setupClaudeApiKey(config, prompts, target)) BuildOutput.step(CLAUDE_SKIPPED);
        } else {
            BuildOutput.step(CLAUDE_SKIPPED);
        }
    }

    /** Asks for an Anthropic API key until one verifies or is saved anyway: false when the user skips. */
    private boolean setupClaudeApiKey(SpawnConfig config, Prompts prompts, AccountTarget target) {
        while (true) {
            var key = askSecret(prompts, "ANTHROPIC_API_KEY (or press Enter to skip): ");
            if (key.isBlank()) return false;

            var result = verifying("Verifying API key", () -> verifyAnthropicApiKey(key));
            if (result.verified()) {
                BuildOutput.ok(result.message());
                saveDirectConfig(config, target, key);
                BuildOutput.step("Claude auth configuration saved.");
                return true;
            }
            switch (askVerificationFailureAction(prompts)) {
                case SKIP -> {
                    return false;
                }
                case SAVE_UNVERIFIED -> {
                    saveDirectConfig(config, target, key);
                    BuildOutput.step("Claude auth configuration saved (unverified).");
                    return true;
                }
                case RETRY -> {
                    // ask again
                }
            }
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
        return (isDefault ? "* " : "  ") + name + "  —  " + account.describe() + secret + tag;
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
            BuildOutput.step("Claude accounts:");
            accounts.forEach((name, account) ->
                    prompts.say(describeClaudeAccount(name, account, name.equals(defaultName))));
            if (claude.accountFor(SpawnConfig.ClaudeAccount::servesDirectApi) == null) {
                prompts.say("(none of these can answer 'isx ask' — add an API key, which Max and Team plans include credits for, or Vertex)");
            }
            // 'd' and 'x' are reachable as raw keystrokes even when not listed, so the same
            // fact gates both the menu and the handlers -- state it once.
            var canManageMultiple = accounts.size() > 1;
            prompts.say("a. Add another account");
            prompts.say("r. Replace all with a single account");
            if (canManageMultiple) {
                prompts.say("d. Change which account is the default");
                prompts.say("x. Remove an account");
            }
            prompts.say("n. Rename an account");
            var choice = readInput(prompts.ask("Choice (Enter to keep as-is): ")).toLowerCase(java.util.Locale.ROOT);

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
                        var name = readInput(prompts.ask("Name of the account to make default: "));
                        if (accounts.containsKey(name)) {
                            var account = accounts.get(name);
                            if (!account.isComplete()) {
                                BuildOutput.step("Account '" + name + "' is incomplete — fix it before making it the default.");
                            } else {
                                changeDefaultAccount(config, SpawnConfig.ClaudeConfig.NAMESPACE, name,
                                        () -> config.getClaude().setDefaultAccount(name), prompts);
                            }
                        } else if (!name.isEmpty()) {
                            prompts.say("No account named '" + name + "'.");
                        }
                        continue;
                    }
                }
                case "x" -> {
                    if (canManageMultiple) {
                        var name = readInput(prompts.ask("Name of the account to remove: "));
                        if (accounts.containsKey(name)) {
                            var account = accounts.get(name);
                            if (account.isComplete() && claude.effectiveAccounts().size() <= 1) {
                                BuildOutput.step("Cannot remove '" + name + "' — it is the only working account.");
                            } else if (confirmLosingAccounts(config, SpawnConfig.ClaudeConfig.NAMESPACE,
                                    List.of(name), prompts)) {
                                var moving = runningFollowersIfDefault(config, SpawnConfig.ClaudeConfig.NAMESPACE, name);
                                claude.getAccounts().remove(name);
                                if (name.equals(claude.getDefaultAccount())) {
                                    claude.setDefaultAccount(claude.accountName());
                                }
                                config.save();
                                BuildOutput.step("Removed account '" + name + "'.");
                                if (!moving.isEmpty()) refreshIdentities(moving);
                            }
                        } else if (!name.isEmpty()) {
                            prompts.say("No account named '" + name + "'.");
                        }
                        continue;
                    }
                }
                default -> { }
            }
            prompts.say("Please choose one of the listed options.");
        }
    }

    /** Prompts for a new account name, rejecting duplicates and anything YAML-hostile. */
    private static String askAccountName(Prompts prompts, java.util.Set<String> taken) {
        return askAccountName(prompts, taken, "Name for this account (e.g. personal, work — Enter to cancel): ");
    }

    private static String askAccountName(Prompts prompts, java.util.Set<String> taken, String prompt) {
        while (true) {
            var name = readInput(prompts.ask(prompt));
            if (name.isEmpty()) return "";
            if (taken.contains(name)) {
                prompts.say("There is already an account named '" + name + "'.");
                continue;
            }
            if (!ACCOUNT_NAME.matcher(name).matches()) {
                prompts.say("Use letters, digits, '.', '_' or '-' only.");
                continue;
            }
            if (AccountResolver.INHERIT.equals(name)) {
                prompts.say("'" + name + "' is reserved: it takes the credential of the isx instance isx runs in.");
                continue;
            }
            return name;
        }
    }

    record AuthResult(boolean verified, String message) {}

    /**
     * Verify under a live step: a ✓ when it verified, for the caller to report its message, or a
     * ✗ with the message under it as a step, which keeps the hyperlink a message can hold.
     */
    private static AuthResult verifying(String label, java.util.function.Supplier<AuthResult> verify) {
        BuildOutput.stepStart(label);
        AuthResult result;
        try {
            result = verify.get();
        } catch (RuntimeException e) {
            BuildOutput.abandonStep();
            throw e;
        }
        if (result.verified()) BuildOutput.stepDone();
        else {
            BuildOutput.stepBreak();
            BuildOutput.step(result.message());
        }
        return result;
    }

    enum VerificationFailureAction { RETRY, SKIP, SAVE_UNVERIFIED }

    private static VerificationFailureAction askVerificationFailureAction(Prompts prompts) {
        while (true) {
            var action = parseVerificationFailureAction(prompts.ask("Try again? (Y/n/s to save anyway): "));
            if (action != null) return action;
            prompts.say("Please answer y, n, or s.");
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
     * Asks {@code question} for a secret, read unechoed, and confirms what arrived. {@code readPassword()} echoes nothing, so
     * without this a paste that never landed looks exactly like one that did, and a truncated
     * one only shows up as a rejection from the remote API. The line shows the length and the
     * same masked form init already prints for a configured credential -- never more.
     *
     * <p>Dimmed and worded "received" rather than green: it says the input arrived, not that
     * the credential works; verification still speaks for that.
     */
    static String askSecret(Prompts prompts, String question) {
        var secret = readSecret(prompts.askSecret(question));
        if (!secret.isEmpty()) {
            prompts.say(styled(DIM, describeReceivedSecret(secret)));
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
     * this: {@link BaseCommand#askYesNo} takes an explicit {@code eofValue}, and
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
    static String apiErrorSuffix(byte[] body) {
        if (body == null || ByteLines.isBlank(body)) {
            return "";
        }
        try {
            var message = Jjq.parseForeign(body, "the API's error").getField("error").getField("message");
            if (!message.isString()) {
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

    /** Saves the token's account; a verified one is reported as an ok, one saved anyway as a plain step. */
    private void saveOauthConfig(SpawnConfig config, Prompts prompts, AccountTarget target, String oauthToken,
                                 boolean verified) {
        saveClaudeAccount(config, target, SpawnConfig.ClaudeAccount.ofOauth(oauthToken));
        if (verified) {
            BuildOutput.ok("Claude auth configuration saved");
        } else {
            BuildOutput.step("Claude auth configuration saved (unverified).");
        }
        offerApiKeyForAiHelp(config, prompts);
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
    }

    /**
     * A subscription token cannot answer 'isx ask' -- it is only valid for Claude Code itself.
     * Max and Team plans include monthly API credit that can, so say so when such an account is
     * saved and offer to add the key beside it, rather than letting AI Help fail later (#1674).
     * The subscription stays the default: instances keep using it, and AI Help picks the key.
     */
    private void offerApiKeyForAiHelp(SpawnConfig config, Prompts prompts) {
        if (!AiHelpClient.targets(config).isEmpty()) return;
        BuildOutput.step("'isx ask' and '?' in the TUI cannot use a subscription token, only an API key.");
        BuildOutput.step("Max and Team plans include monthly API credits for one (Pro does not): claim them");
        BuildOutput.step("on claude.ai under Settings > Billing, then create a key in the linked Claude Console.");
        BuildOutput.step(TerminalLink.link(AiHelpClient.API_CREDITS_URL));
        if (!askYesNo(prompts, "Add an Anthropic API key account now?", false)) {
            BuildOutput.step(ADD_API_KEY_LATER);
            return;
        }
        var name = askAccountName(prompts, config.getClaude().allAccounts().keySet(),
                "Name for the API key account (e.g. api — Enter to cancel): ");
        if (name.isEmpty()) {
            BuildOutput.step(ADD_API_KEY_LATER);
            return;
        }
        if (!setupClaudeApiKey(config, prompts, new AccountTarget(name, false))) {
            BuildOutput.step(ADD_API_KEY_LATER);
        }
    }

    AuthResult verifyAnthropicApiKey(String key) {
        if (!key.startsWith("sk-ant-")) {
            BuildOutput.warn("Key does not start with 'sk-ant-' (unexpected format).");
        }
        return verifyAnthropicCredential("x-api-key", key, "API key");
    }

    AuthResult verifyOauthToken(String token) {
        oauthTokenShapeWarning(token).ifPresent(BuildOutput::warn);
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
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

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
        BuildOutput.step("A subscription signs Claude Code in with a long-lived OAuth token (valid");
        BuildOutput.step("about a year). Pro has no API key; Max and Team include API credits for one.");
        prompts.say("1. Install Claude Code: " + TerminalLink.link("https://claude.com/claude-code"));
        prompts.say("2. Sign in with the subscription account: run " + styled(BOLD, "claude")
                + ", then " + styled(BOLD, "/login"));
        prompts.say("3. Run " + styled(BOLD, "claude setup-token"));
        prompts.say("4. Copy the token it prints (it starts with '"
                + SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX + "')");
        BuildOutput.step("Run 'isx account add claude' to paste a fresh token once this one expires.");

        if (hostHasCommand("claude")) {
            BuildOutput.step("Found 'claude' CLI on this host — steps 1 and 2 are already done.");
            if (askYesNo(prompts, "Run 'claude setup-token' now?", true)) {
                runClaudeSetupToken();
            }
        } else {
            BuildOutput.step("'claude' CLI not found on this host — follow the steps above on any");
            BuildOutput.step("machine where it is installed, then paste the token here.");
        }

        while (true) {
            var token = askSecret(prompts, "Paste your OAuth token (or press Enter to skip): ");
            if (token.isBlank()) {
                BuildOutput.step(CLAUDE_SKIPPED);
                break;
            }

            var result = verifying("Verifying OAuth token", () -> verifyOauthToken(token));
            if (result.verified()) {
                BuildOutput.ok(result.message());
                saveOauthConfig(config, prompts, target, token, true);
                break;
            } else {
                switch (askVerificationFailureAction(prompts)) {
                    case SKIP -> {
                        BuildOutput.step(CLAUDE_SKIPPED);
                        break;
                    }
                    case SAVE_UNVERIFIED -> {
                        saveOauthConfig(config, prompts, target, token, false);
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
                BuildOutput.warn("'claude setup-token' timed out after 2 minutes.");
            } else if (process.exitValue() != 0) {
                BuildOutput.warn("'claude setup-token' exited with code " + process.exitValue() + ".");
            } else {
                BuildOutput.step("Token generation complete.");
            }
        } catch (Exception e) {
            BuildOutput.warn("Failed to run 'claude setup-token': " + BaseCommand.failure(e));
        }
    }

    AuthResult verifyVertexConfig(String region, String projectId) {
        if (!commandExists("gcloud")) {
            return new AuthResult(false,
                    "gcloud CLI not found. Install it from " + TerminalLink.link("https://cloud.google.com/sdk/docs/install") + "."
                    + " Then run: gcloud auth login");
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
                        "gcloud auth failed" + (detail.isBlank() ? "" : " (" + OutputFormat.oneLine(detail) + ")")
                        + ". Run: gcloud auth login");
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
                        "Vertex AI access denied (HTTP 403). Check that the Vertex AI API is enabled"
                        + " for project '" + projectId + "' and your account has the required permissions.");
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

        BuildOutput.step("An authenticated 'gh' CLI is available on this host.");
        BuildOutput.step(styled(DIM, "Not recommended: that login is almost certainly your personal identity,"
                + " so the agent would act as you with whatever scopes 'gh' holds. Prefer a dedicated"
                + " agent account and a fine-grained PAT (above)."));
        if (!askYesNo(prompts, "Reuse your personal 'gh' token anyway?", false)) {
            return GhTokenOutcome.NOT_OFFERED;
        }

        var token = readGhAuthToken();
        if (token == null || token.isBlank()) {
            BuildOutput.warn("Could not read a token from 'gh auth token' — continuing with manual setup.");
            return GhTokenOutcome.FAILED;
        }
        var result = verifyGitHubToken(token, prompts);
        if (result == null) {
            BuildOutput.step("The 'gh' token failed verification — continuing with manual setup.");
            return GhTokenOutcome.FAILED;
        }
        if (result.email == null) {
            BuildOutput.warn("No email accessible — git commits will have no author email.");
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
        if (email != null) {
            BuildOutput.ok("GitHub configuration saved");
        } else {
            // Not a clean success: the warning above says commits will have no author email
            BuildOutput.step("GitHub configuration saved (without email).");
        }
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
        BuildOutput.step("To create a fine-grained PAT (ideally signed in as the agent's account, not your personal one):");
        prompts.say("1. Open the pre-filled token page: " + styled(BOLD, TerminalLink.link(GH_PAT_NEW_URL)));
        BuildOutput.stepNote("It already grants what isx needs:");
        BuildOutput.stepNote("Contents, Issues, Pull requests  read and write");
        BuildOutput.stepNote("Email addresses                  read (stamps your git commit identity)");
        prompts.say("2. Check " + styled(BOLD, "Resource owner") + " is the agent's account, set an expiration,"
                + " and under " + styled(BOLD, "Repository access") + " choose the repos to grant.");
        prompts.say("3. Click " + styled(BOLD, "Generate token")
                + " and copy the value (starts with 'github_pat_').");
        BuildOutput.step(styled(DIM, "Adding a permission by hand: under Permissions, click 'Add permissions' and search for it."));
        BuildOutput.step(styled(DIM, "Avoid admin, org, and delete permissions unless you need them."));

        BuildOutput.step("Already have a token? Answer 'n' and paste it at the next prompt.");
        if (askYesNo(prompts, "Open the pre-filled token page in your browser now?", true)) {
            if (openUrl(GH_PAT_NEW_URL)) {
                BuildOutput.step("Opened your browser — finish there, then paste the token below.");
            } else {
                BuildOutput.warn("Could not open a browser — visit the URL above manually.");
            }
        }
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
                BuildOutput.step(label + ": configured.");
                prompts.say("r. Replace it");
                prompts.say("a. Add a second account (keeps the current one)");
                var choice = readInput(prompts.ask("Choice (Enter to keep as-is): ")).toLowerCase(java.util.Locale.ROOT);
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

            BuildOutput.step(label + " accounts:");
            for (var name : accounts) {
                prompts.say("- " + name + (name.equals(defaultName) ? "  (default)" : ""));
            }
            prompts.say("a. Add another account");
            prompts.say("e. Replace an existing account's credentials");
            prompts.say("r. Replace all with a single account");
            prompts.say("d. Change which account is the default");
            prompts.say("x. Remove an account");
            prompts.say("n. Rename an account");
            var choice = readInput(prompts.ask("Choice (Enter to keep as-is): ")).toLowerCase(java.util.Locale.ROOT);

            switch (choice) {
                case "" -> { return Optional.empty(); }
                case "a" -> {
                    var name = askAccountName(prompts, new java.util.LinkedHashSet<>(accounts));
                    return name.isEmpty() ? Optional.empty() : Optional.of(new AccountTarget(name, false));
                }
                case "e" -> {
                    var name = readInput(prompts.ask("Name of the account to replace: "));
                    if (accounts.contains(name)) return Optional.of(new AccountTarget(name, false));
                    if (!name.isEmpty()) prompts.say("No account named '" + name + "'.");
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
                    var name = readInput(prompts.ask("Name of the account to make default: "));
                    if (accounts.contains(name)) {
                        changeDefaultAccount(config, namespace, name,
                                () -> NamespaceAccounts.setDefault(config, namespace, name), prompts);
                    } else if (!name.isEmpty()) {
                        prompts.say("No account named '" + name + "'.");
                    }
                }
                case "x" -> {
                    var name = readInput(prompts.ask("Name of the account to remove: "));
                    if (accounts.contains(name) && confirmLosingAccounts(config, namespace, List.of(name), prompts)) {
                        var moving = runningFollowersIfDefault(config, namespace, name);
                        NamespaceAccounts.remove(config, namespace, name);
                        config.save();
                        BuildOutput.step("Removed account '" + name + "'.");
                        if (!moving.isEmpty()) refreshIdentities(moving);
                    } else if (!name.isEmpty() && !accounts.contains(name)) {
                        prompts.say("No account named '" + name + "'.");
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
            BuildOutput.step("These instances follow the " + namespace + " default, which is going,"
                    + " and would switch to the new default:");
            followers.forEach(name -> prompts.say("- " + name));
        }
        if (!instances.isEmpty()) {
            BuildOutput.step("These instances are pinned to " + (going.size() == 1 ? "it" : "those accounts")
                    + " and would fail their requests:");
            instances.forEach(name -> prompts.say("- " + name));
            BuildOutput.step("Re-point them first with 'isx account set', or 'isx account unset'"
                    + " to have them follow the default.");
        }
        if (!templates.isEmpty()) {
            BuildOutput.step("These templates name " + (going.size() == 1 ? "it" : "them")
                    + " under accounts: and could no longer be branched:");
            templates.forEach(name -> prompts.say("- " + name));
        }
        return askYesNo(prompts, "Remove anyway?", false);
    }

    /**
     * Rename an account and follow it everywhere isx can: the default, and every instance
     * pinned to it. Template YAML is the user's file and is only reported, never rewritten.
     */
    void renameAccount(SpawnConfig config, String namespace, java.util.Set<String> accounts, Prompts prompts) {
        var from = readInput(prompts.ask("Name of the account to rename: "));
        if (from.isEmpty()) return;
        if (!accounts.contains(from)) {
            prompts.say("No account named '" + from + "'.");
            return;
        }
        var to = askAccountName(prompts, accounts, "New name for '" + from + "' (Enter to cancel): ");
        if (to.isEmpty()) return;
        NamespaceAccounts.rename(config, namespace, from, to);
        config.save();
        BuildOutput.step("Renamed '" + from + "' to '" + to + "'.");
        try {
            var repointed = renameInInstances(config, namespace, from, to);
            if (!repointed.isEmpty()) {
                BuildOutput.step("Re-pointed " + String.join(", ", repointed) + " to '" + to + "'.");
            }
        } catch (Exception e) {
            BuildOutput.warn("Could not update the instances pinned to '" + from + "' ("
                    + e.getMessage() + "). Re-point them with 'isx account set <instance> "
                    + namespace + "=" + to + "'.");
        }
        var templates = templatesNaming(namespace, from);
        if (!templates.isEmpty()) {
            BuildOutput.step((templates.size() == 1 ? "Template " : "Templates ")
                    + String.join(", ", templates) + (templates.size() == 1 ? " still names '" : " still name '")
                    + from + "' under accounts: -- change it to '" + to + "' in the YAML, or"
                    + " branching from " + (templates.size() == 1 ? "it" : "them") + " fails.");
        }
    }

    /**
     * The Incus the account flows ask about instances. A test home ({@code IsolatedHome}) replaces
     * it, so no test reads or changes the instances of an Incus the developer runs (#1965).
     */
    private static volatile Supplier<IncusClient> incus = RuntimeServices::incus;

    /** Replace the Incus the account flows ask, returning what they asked before. For tests. */
    static Supplier<IncusClient> replaceIncus(Supplier<IncusClient> replacement) {
        var previous = incus;
        incus = replacement;
        return previous;
    }

    private static IncusClient incus() {
        return incus.get();
    }

    /** Every isx instance's account state, or none when Incus cannot be asked. Overridden in tests. */
    Map<String, InstanceRegistry.AccountState> instanceAccountStates() {
        try {
            return InstanceRegistry.accountStates(incus());
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
            BuildOutput.step("'" + to + "' is already the default.");
            return false;
        }
        var states = instanceAccountStates();
        var followers = new LinkedHashMap<String, InstanceRegistry.AccountState>();
        states.forEach((name, state) -> { if (state.followsDefault(namespace)) followers.put(name, state); });

        var decision = followers.isEmpty() ? DefaultChange.SWITCH
                : askDefaultChange(config, namespace, from, to, followers, prompts);
        if (decision == DefaultChange.CANCEL) {
            BuildOutput.step("Default left as '" + from + "'.");
            return false;
        }
        if (decision == DefaultChange.KEEP) {
            // Pinned before the default moves, so none of them is ever served the new one.
            for (var failure : pinInstances(config, List.copyOf(followers.keySet()), namespace, from)) {
                BuildOutput.warn(failure);
            }
        }
        apply.run();
        config.save();
        BuildOutput.step("Default account is now '" + to + "'.");
        if (decision == DefaultChange.KEEP) {
            BuildOutput.step(String.join(", ", followers.keySet()) + " stay on '" + from
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
        BuildOutput.step("These instances follow the " + namespace + " default and would switch from '"
                + from + "' to '" + to + "':");
        var setup = dev.incusspawn.config.AccountSelection.namespaceSetups(config).get(namespace);
        var refused = new java.util.ArrayList<String>();
        followers.forEach((name, state) -> {
            prompts.say("- " + name + (state.running() ? " (running)" : ""));
            if (!dev.incusspawn.config.AccountSelection.requiredRebuild(config, setup, namespace, to,
                    state.bakedIdentities()).isEmpty()) {
                refused.add(name);
            }
        });
        if (!refused.isEmpty()) {
            BuildOutput.step(String.join(", ", refused) + (refused.size() == 1 ? " was" : " were")
                    + " built for another auth mode than '" + to + "': " + (refused.size() == 1 ? "its " : "their ")
                    + namespace + " requests would be refused until pinned to an account " + (refused.size() == 1 ? "it" : "they")
                    + " can use. Keeping " + (followers.size() == 1 ? "it" : "them") + " on '" + from
                    + "' avoids that.");
        }
        while (true) {
            var line = prompts.ask("Enter to switch them, k to keep them on '" + from + "' (pins it for them),"
                    + " c to cancel: ");
            if (line == null) return DefaultChange.CANCEL;   // EOF: never change principals unasked
            switch (line.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "", "s" -> { return DefaultChange.SWITCH; }
                case "k" -> { return DefaultChange.KEEP; }
                case "c" -> { return DefaultChange.CANCEL; }
                default -> prompts.say("Please answer Enter, k or c.");
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
        var incus = incus();
        var setups = dev.incusspawn.config.AccountSelection.namespaceSetups(config);
        for (var instance : instances) {
            try {
                InstanceLifecycle.changeAccounts(incus, instance, config, setups, Map.of(namespace, account),
                        // Its warnings alone, as before, each naming the instance
                        (msg, details) -> BuildOutput.warn(instance + ": " + msg, details));
            } catch (RuntimeException e) {
                failures.add("Could not pin " + instance + " (" + e.getMessage() + "); it will switch.");
            }
        }
        return failures;
    }

    /** Bring running instances' baked git identity in line with their new account. Overridden in tests. */
    void refreshIdentities(List<String> instances) {
        var incus = incus();
        for (var instance : instances) {
            InstanceLifecycle.reconcileAccountIdentities(incus, instance, new dev.incusspawn.util.Output() {
                @Override
                public void step(String msg) {
                    BuildOutput.step(instance + ": " + msg);
                }

                @Override
                public void warn(String msg, String... details) {
                    BuildOutput.warn(instance + ": " + msg, details);
                }

                // The re-derive is a live step, which the defaults would drop
                @Override
                public void stepStart(String msg) {
                    BuildOutput.stepStart(instance + ": " + msg);
                }

                @Override
                public void stepDone(String detail) {
                    BuildOutput.stepDone(detail);
                }

                @Override
                public void abandonStep() {
                    BuildOutput.abandonStep();
                }
            });
        }
    }

    /** Re-point instances after a rename, and tell the proxy. Overridden in tests. */
    List<String> renameInInstances(SpawnConfig config, String namespace, String from, String to) {
        var repointed = AccountSelection.renameInInstances(incus(), config, namespace, from, to);
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

    /**
     * Name the keys in {@code namespace} that isx does not read before asking for its credential,
     * so a hand-written {@code openai.token} is not taken for one this step skipped (#1733). They
     * are the user's: this step does not move them to the key isx reads.
     */
    void warnUnreadKeys(SpawnConfig config, String namespace) {
        var note = dev.incusspawn.config.UnreadConfigKeys.note(config.tree(), namespace, toolsOf(config));
        if (note.isEmpty()) return;
        BuildOutput.warn(note, "isx does not use them: rename or remove them in config.yaml.");
    }

    /**
     * Every tool of the config in hand, not of a second read of config.yaml (#890), loaded once
     * per run of steps rather than once per step; again only when its search paths change.
     */
    private Map<String, ToolSetup> toolsOf(SpawnConfig config) {
        var searchPaths = List.copyOf(config.getSearchPaths());
        if (toolsLoader == null || !searchPaths.equals(toolsSearchPaths)) {
            toolsLoader = dev.incusspawn.tool.ToolDefLoader.withoutProjectTools(searchPaths);
            toolsSearchPaths = searchPaths;
        }
        // Filtered per call, as a step may turn a feature on
        return dev.incusspawn.config.UnreadConfigKeys.trustedTools(config, toolsLoader);
    }

    void setupGitHubAuth(SpawnConfig config, Prompts prompts) {
        // The same menu every non-Claude credential gets, so adding a second GitHub identity
        // needs no GitHub-specific UX. It answers FRESH straight away when nothing is saved,
        // and comes back after each saved token so another identity fits in the same run;
        // Enter (or EOF) moves on.
        warnUnreadKeys(config, GhSetup.NAMESPACE);
        var account = chooseAccountTarget(config, GhSetup.NAMESPACE, "GitHub", prompts);
        while (account.isPresent() && collectGitHubToken(config, prompts, account.get())) {
            account = chooseAccountTarget(config, GhSetup.NAMESPACE, "GitHub", prompts);
        }
    }

    /** Walks the user through one token for {@code account}; returns true once one is saved. */
    private boolean collectGitHubToken(SpawnConfig config, Prompts prompts, AccountTarget account) {
        // Prioritize a dedicated agent identity: walk the user through minting a fine-grained PAT.
        printGitHubPatGuide(prompts);

        while (true) {
            var token = askSecret(prompts, "GitHub PAT for the agent (or press Enter to skip): ");
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
                if (!askYesNo(prompts, "Try again?", true)) {
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
        BuildOutput.warn("No email accessible — git commits will have no author email.");
        BuildOutput.step("To fix this, either:");
        prompts.say("• Edit your PAT at " + TerminalLink.link(patSettingsUrl(token)) + ":");
        BuildOutput.stepNote("under Permissions, click 'Add permissions', search for 'Email addresses'"
                + " and set it to Read-only (for a classic token, add the 'user:email' scope).");
        prompts.say("• Or make your email public at " + TerminalLink.link("https://github.com/settings/profile"));
        while (true) {
            var newToken = askSecret(prompts, "Enter new PAT with email permission, or press Enter to keep the one above"
                    + " (without email): ");
            if (newToken.isBlank()) return null;

            var newResult = verifyPastedGitHubToken(newToken, prompts);
            if (newResult == null) {
                BuildOutput.step("That token failed verification — try again, or press Enter to keep the one above.");
                continue;
            }
            if (newResult.email == null) {
                BuildOutput.step("(still without email)");
            }
            return new VerifiedToken(newToken, newResult);
        }
    }

    /**
     * A skip leaves every saved account as it was, so "skipped setup" is only true when there
     * are none -- not after adding a first account this run, nor on a re-run that has some.
     */
    private static void reportGitHubSkipped(SpawnConfig config) {
        BuildOutput.step(hasAccountsToPreserve(config, GhSetup.NAMESPACE)
                ? "No token added for this account."
                : "Skipped GitHub setup. You can configure it later with 'isx account add " + GhSetup.NAMESPACE + "'.");
    }

    /**
     * {@link #verifyGitHubToken} for a token the user typed, after checking its shape: kept in
     * the flow rather than the seam, which tests replace, so the warning stays testable.
     */
    private GitHubVerifyResult verifyPastedGitHubToken(String token, Prompts prompts) {
        githubTokenShapeWarning(token).ifPresent(BuildOutput::warn);
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
        BuildOutput.stepStart("Testing GitHub token");
        try {
            var client = getHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user"))
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                BuildOutput.stepFail("Authentication failed (HTTP " + response.statusCode() + ").");
                return null;
            }
            BuildOutput.stepDone();

            var json = Jjq.parseForeign(response.body(), "GitHub's user");
            var login = json.pathText("login", null);
            var email = json.pathText("email", null);
            if (email == null) {
                email = checkGitHubEmail(client, token, prompts);
            }

            if (login != null) {
                if (email != null) {
                    BuildOutput.ok("Token verified. Authenticated as: " + login + " <" + email + ">");
                } else {
                    BuildOutput.ok("Token verified. Authenticated as: " + login);
                }
            } else {
                BuildOutput.step("Token verified (could not determine username).");
            }

            return new GitHubVerifyResult(login, email);
        } catch (Exception e) {
            BuildOutput.abandonStep();
            BuildOutput.warn("Could not test token: " + BaseCommand.failure(e));
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
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
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

        BuildOutput.step("Multiple verified emails found:");
        for (int i = 0; i < parsed.verified.size(); i++) {
            var label = parsed.verified.get(i);
            if (label.endsWith("@users.noreply.github.com")) label += " (private, recommended)";
            else if (label.equals(parsed.primary)) label += " (primary)";
            prompts.say((i + 1) + ". " + label);
        }
        var choice = readInput(prompts.ask("Select email for git commits [1]: "));
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

    static EmailParseResult parseGitHubEmails(byte[] json) {
        try {
            var emails = Jjq.parseForeign(json, "GitHub's email list");
            var verifiedEmails = new ArrayList<String>();
            String primaryEmail = null;
            String noreplyEmail = null;
            for (var entry : emails.asList()) {
                if (!entry.pathBoolean("verified", false)) continue;
                var email = entry.pathText("email", null);
                if (email == null) continue;
                if (email.endsWith("@users.noreply.github.com")) {
                    noreplyEmail = email;
                    continue;
                }
                verifiedEmails.add(email);
                if (entry.pathBoolean("primary", false)) {
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
        printNumberedPaths(paths);
    }

    /**
     * The entry number an input refers to, or null if it is not one. Accepts "2" and, because
     * the list reads like numbered entries, "#2" too. Digits too many to parse still name an
     * entry -- one that cannot exist -- so they are never mistaken for a directory to add.
     */
    static Integer entryNumber(String input) {
        var m = ENTRY_NUMBER.matcher(input);
        if (!m.matches()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException tooLarge) {
            return Integer.MAX_VALUE;
        }
    }

    /** The paths, numbered, in a group of their own: the same list, at the same indent, whenever it is shown. */
    private void printNumberedPaths(java.util.List<String> paths) {
        if (paths.isEmpty()) return;
        try (var group = BuildOutput.group("Current paths", null)) {
            for (int i = 0; i < paths.size(); i++) {
                BuildOutput.step((i + 1) + ". " + paths.get(i));
            }
        }
    }

    /**
     * The tool's description, else its name, as one line: a definition can come from a cloned
     * repository's {@code .incus-spawn/tools/}, so neither is printed raw (#1222).
     */
    static String describeTool(String toolName, ToolSetup tool) {
        return OutputFormat.oneLine(tool.description().isBlank() ? toolName : tool.description());
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
            warnUnreadKeys(config, namespace);
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

            // Definition text, so one line each, like describeTool.
            var description = OutputFormat.oneLine(configDef.getDescription());
            var label = description.isBlank() ? OutputFormat.oneLine(configKey) : description;

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
                BuildOutput.step(label + ": " + maskSecret(existing));
                if (askYesNo(prompts, "Keep current?", true, true)) {
                    // A kept value still has to travel with a "replace" into the new layout.
                    if (perAccount) accountValues.put(key, existing);
                    continue;
                }
            }

            for (var helpLine : configDef.getHelp()) {
                BuildOutput.step(OutputFormat.oneLine(helpLine));
            }
            if (configDef.getHelp().isEmpty() && !description.isBlank()) {
                BuildOutput.step(label);
            }

            String value;
            if (configDef.isConfirm()) {
                value = askYesNo(prompts, label + "?", false, true) ? "true" : "false";
            } else {
                var question = label + " (or press Enter to skip): ";
                if (configDef.isSecret()) {
                    value = askSecret(prompts, question);
                } else {
                    value = readInput(prompts.ask(question));
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
            if (!savedAny) BuildOutput.step("No credential entered, so no account was changed.");
        }
        for (var shared : sharedValues.entrySet()) {
            config.setConfigByPath(shared.getKey(), shared.getValue());
            savedAny = true;
        }

        if (savedAny) {
            config.save();
            BuildOutput.step(desc + " credentials saved.");
        } else {
            BuildOutput.step("Skipped. Configure later with '"
                    + CredentialCheck.stepCommand(toolName, new dev.incusspawn.tool.ToolDefLoader().allToolSetups()) + "'.");
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
            var input = readInput(prompts.ask(hasEntries
                    ? "Add a local directory, type an entry's number to remove it, or press Enter to finish: "
                    : "Add a local directory (or press Enter to skip): "));
            if (input.isEmpty()) break;

            if (input.contains("://")) {
                BuildOutput.step("That looks like a URL — this needs a local directory path (e.g. ~/my-templates).");
                continue;
            }

            var entryNumber = entryNumber(input);
            if (entryNumber != null) {
                int index = entryNumber - 1;
                if (index >= 0 && index < paths.size()) {
                    BuildOutput.step("Removed: " + paths.remove(index));
                    printNumberedPaths(paths);
                } else if (paths.isEmpty()) {
                    BuildOutput.step("There are no entries to remove.");
                } else {
                    BuildOutput.step("No such entry — type a number from 1 to " + paths.size() + ".");
                }
                continue;
            }
            if (input.startsWith("#")) {
                BuildOutput.step("To remove an entry, type just its number (e.g. 1).");
                continue;
            }

            var expanded = HostResourceSetup.expandHostTilde(input);
            var path = java.nio.file.Path.of(expanded);
            if (!java.nio.file.Files.isDirectory(path)
                    && !askYesNo(prompts, "'" + input + "' is not an existing directory. Add it anyway?", false)) {
                continue;
            }
            var resolved = path.toAbsolutePath().normalize().toString();
            if (paths.contains(resolved)) {
                BuildOutput.step("Already in the list.");
            } else {
                paths.add(resolved);
                BuildOutput.step("Added: " + resolved);
                printNumberedPaths(paths);
            }
        }

        if (!paths.equals(existing)) {
            setter.accept(config, paths);
            config.save();
            BuildOutput.step("Paths saved.");
        } else if (paths.isEmpty()) {
            BuildOutput.step(skipMessage);
        } else {
            BuildOutput.step("Paths unchanged.");
        }
    }
}
