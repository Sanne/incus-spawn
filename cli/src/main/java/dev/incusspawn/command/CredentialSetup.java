package dev.incusspawn.command;

import dev.incusspawn.config.ModelIds;
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

    /** A credential namespace as a person names its accounts: {@code claude} is "Claude". */
    static String accountName(String namespace) {
        return switch (namespace) {
            case "claude" -> "Claude";
            case "openai" -> "OpenAI";
            case "gemini" -> "Gemini";
            case "github" -> "GitHub";
            case "bob" -> "IBM Bob";
            default -> OutputFormat.oneLine(namespace);
        };
    }

    /** Whether this is init's first run, which leaves some questions to later (#2528). */
    boolean firstRun;

    /** The tool name of the agent the first run set up, or null: an account kind only another agent can use is not offered (#2528). */
    String agent;

    /** Whether the first run chose no agent: what acts on GitHub is then isx machines, never "the agent" (#2528). */
    boolean noAgent;

    /**
     * What the agent runs in, as this step names it: "isx machines" in the first run, which
     * introduces that word, and "instances", as the rest of isx says, everywhere else (#2528).
     */
    String machines() {
        return firstRun ? "isx machines" : "instances";
    }

    boolean hostHasCommand(String command) {
        return commandExists(command);
    }

    boolean openUrl(String url) {
        return Platform.openUrl(url);
    }

    private static final String CLAUDE_SKIPPED = "Skipped. 'isx account add "
            + SpawnConfig.ClaudeConfig.NAMESPACE + "' adds one later.";

    /**
     * What a skipped Claude step says: "no account yet" only when there is none, never after a
     * skip that leaves saved accounts as they were.
     */
    private static String claudeSkipped(SpawnConfig config) {
        return config.getClaude().allAccounts().isEmpty() ? CLAUDE_SKIPPED : "Your Claude accounts stay as they were.";
    }

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
        // A ChatGPT session is a configured credential too (#1332): its account holds no API key
        for (var namespace : tool.credentialNamespaces()) {
            if (!dev.incusspawn.proxy.ChatGptAccounts.NAMESPACE.equals(namespace)) continue;
            var account = dev.incusspawn.config.AccountResolver.effectiveAccount(configTree, namespace,
                    dev.incusspawn.config.AccountResolver.shapeOf(allTools, namespace), null);
            if (!account.isBlank() && !dev.incusspawn.config.AccountResolver.INHERIT.equals(account)
                    && dev.incusspawn.proxy.ChatGptAccounts.isChatGpt(configTree.path(namespace)
                            .path("accounts").path(account))) return true;
        }
        boolean anyChecked = false;
        for (var entry : proxyDef.getConfiguration().entrySet()) {
            var configDef = entry.getValue();
            if (!configDef.getValue().isBlank()) continue; // hardcoded literal — always resolved
            if (configDef.isConfirm()) continue;
            if (configDef.getConfigPath().isBlank()) continue;
            anyChecked = true;
            if (configDef.isList()) {
                if (ToolProxyResolver.listItems(toolName, proxyDef, entry.getKey(), configTree, allTools, Map.of()).isEmpty()) return false;
                continue;
            }
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

    /** The ChatGPT sign-in this step runs; overridable so a flow test drives it without a network. */
    dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
        return new dev.incusspawn.proxy.ChatGptSignIn();
    }

    /**
     * The openai credential (#1332): a ChatGPT session signed in on the host, or an API key.
     * A ChatGPT plan, Enterprise included, gives no API key a container could use, so the
     * session is what those users have: isx signs it in by the browser flow Codex's own login
     * uses, or by its device-code flow for a host whose browser is elsewhere, and keeps it in
     * the host's session store, where the proxy answers Codex's requests from; instances hold
     * only placeholders. An API key stays the platform credential it always was.
     */
    void setupCodexAuth(SpawnConfig config, Prompts prompts) {
        var namespace = "openai";
        warnUnreadKeys(config, namespace);
        AccountTarget target;
        if (!hasAccountsToPreserve(config, namespace)) {
            target = AccountTarget.FRESH;
        } else {
            var chosen = chooseAccountTarget(config, namespace, "OpenAI", prompts);
            if (chosen.isEmpty()) return;
            target = chosen.get();
        }

        var existing = dev.incusspawn.config.AccountResolver.value(config.tree(), namespace, target.name(), "type");
        if ("chatgpt".equals(existing)) {
            BuildOutput.step("OpenAI: configured with a ChatGPT session.");
            prompts.say("s. Sign in again (replaces the session and every instance proof made from it)");
            prompts.say("k. Switch to an API key instead");
            var choice = readInput(prompts.ask("Choice (Enter to keep as-is): "));
            switch (choice) {
                case "s" -> { signInChatGptInBrowser(config, target); return; }
                case "k" -> { askAndSaveApiKey(config, target, prompts, false); return; }
                default -> { return; }
            }
        }

        // Each option says what it is, and "None for now" is one of them: Enter asks again (#2528)
        while (true) {
            var answer = prompts.choose("Which kind of OpenAI account do you have?", List.of(
                    "ChatGPT, signed in with a browser on " + Platform.thisComputer() + "\nFor a Plus, Pro, Business or Enterprise plan.\nEnterprise single sign-on works too.",
                    "ChatGPT, signed in with a code on another device\nWith a browser on a phone or another computer.",
                    "An OpenAI API key\nFrom platform.openai.com, billed by use.",
                    "None for now\nAdd one later with 'isx account add openai'."), null, null);
            var choice = answer == null ? "4" : answer.strip();
            switch (choice) {
                case "1" -> signInChatGptInBrowser(config, target);
                case "2" -> signInChatGptWithDeviceCode(config, target, prompts);
                case "3" -> {
                    // Enter at the key goes back to the kinds of account
                    if (!askAndSaveApiKey(config, target, prompts, true)) continue;
                }
                case "4" -> BuildOutput.note(hasAccountsToPreserve(config, "openai")
                        ? "Your OpenAI accounts stay as they were." : "Skipped the OpenAI account for now.");
                default -> {
                    continue;
                }
            }
            return;
        }
    }

    /**
     * Signs a ChatGPT session in through the browser, where an organisation's SSO happens.
     * The address is printed, never opened for the person: they click or paste it themselves,
     * and the redirect answers only a browser on this host.
     */
    private void signInChatGptInBrowser(SpawnConfig config, AccountTarget target) {
        var signIn = chatGptSignIn();
        dev.incusspawn.proxy.ChatGptSessionStore.Tokens tokens;
        try {
            tokens = signIn.signInWithBrowser(url -> {
                // A report between questions, so through BuildOutput rather than the prompts'
                // conversation (#1703); the address plain, on its own line, so nothing stands
                // between a terminal's link detection and the URL
                BuildOutput.step("Sign in at this address, in a browser on " + Platform.thisComputer() + ":");
                BuildOutput.step(url);
            });
        } catch (Exception e) {
            BuildOutput.warn("The ChatGPT sign-in did not finish: " + e.getMessage(),
                    "Sign in later with 'isx account add openai'.");
            return;
        }
        saveChatGptSession(config, target, tokens);
    }

    /**
     * Signs a ChatGPT session in by the one-time code, for a host whose browser is elsewhere. A
     * code expires by itself, so a sign-in that did not finish offers another try, with a new
     * code, or skipping, as a failed Claude or GitHub check does (#2528).
     */
    private void signInChatGptWithDeviceCode(SpawnConfig config, AccountTarget target, Prompts prompts) {
        var signIn = chatGptSignIn();
        while (true) {
            dev.incusspawn.proxy.ChatGptSessionStore.Tokens tokens = null;
            try {
                var device = signIn.requestDeviceCode();
                // A report between questions, so through BuildOutput rather than the prompts'
                // conversation (#1703). The address is printed plain, on its own line: no bolding
                // stands between a terminal's link detection and the URL. Any device serves, for the
                // one-time code carries the sign-in -- "on this machine" would send a headless host's
                // user nowhere, and this path exists for exactly them.
                BuildOutput.step("On any device, open this address:");
                BuildOutput.step(device.verificationUrl());
                BuildOutput.step("Then enter this code there: " + styled(BOLD, device.userCode()));
                try {
                    tokens = signIn.awaitAuthorization(device, null);
                } catch (InterruptedException e) {
                    // An interrupt ends the step: it is never answered with a new code
                    Thread.currentThread().interrupt();
                    BuildOutput.warn("The ChatGPT sign-in was interrupted.");
                } catch (Exception e) {
                    BuildOutput.warn("The ChatGPT sign-in did not finish: " + e.getMessage());
                }
            } catch (Exception e) {
                BuildOutput.warn("isx could not reach OpenAI's sign-in server: " + e.getMessage());
            }
            if (tokens != null) {
                saveChatGptSession(config, target, tokens);
                return;
            }
            if (Thread.currentThread().isInterrupted() || !tryAgain(prompts, "try again, with a new code")) {
                // A person who has accounts is not told to add one, as option 4 says (#2528)
                BuildOutput.step(hasAccountsToPreserve(config, "openai") ? "Your OpenAI accounts stay as they were."
                        : "Skipped. 'isx account add openai' adds one later.");
                return;
            }
        }
    }

    /** After a sign-in that did not finish: whether to try again ({@code Y}, the default) or skip ({@code s}). */
    private static boolean tryAgain(Prompts prompts, String again) {
        // Apart from the warning above it, as the Claude and GitHub retries are
        BuildOutput.gap();
        prompts.say("What now?");
        prompts.say("  Y  " + again);
        prompts.say("  s  skip this account for now");
        while (true) {
            var answer = prompts.ask("Choice [Y]: ");
            // End of input skips, as every prompt's does
            if (answer == null) return false;
            switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "", "y" -> { return true; }
                case "s" -> { return false; }
                default -> prompts.say("Please answer y or s.");
            }
        }
    }

    /**
     * Records a signed-in session as the account's credential. The replaced accounts' sessions
     * end only once the new account is on disk: a failed {@code create} or save leaves
     * config.yaml still naming them, and their sessions must outlive that naming.
     */
    private void saveChatGptSession(SpawnConfig config, AccountTarget target,
                                    dev.incusspawn.proxy.ChatGptSessionStore.Tokens tokens) {
        // Read before the replace clears them from the config, the target's own name left
        // out: create() just gave that a fresh session
        List<String> replaced = List.of();
        if (target.replaceOthers()) replaced = chatGptAccounts(config, target.name());
        new dev.incusspawn.proxy.ChatGptSessionStore().create(target.name(), tokens);
        if (target.replaceOthers()) {
            NamespaceAccounts.replaceAll(config, "openai", target.name(), java.util.Map.of("type", "chatgpt"));
        } else {
            NamespaceAccounts.put(config, "openai", target.name(), "type", "chatgpt");
            // The session replaces the account's credential: an API key it held before must not
            // stay in config.yaml, nor keep being injected at api.openai.com for it
            NamespaceAccounts.put(config, "openai", target.name(), "apiKey", "");
        }
        config.save();
        endChatGptSessions(replaced);
        BuildOutput.ok("Signed in to ChatGPT");
        BuildOutput.stepNote("The sign-in is saved on " + Platform.thisComputer() + ".");
    }

    /**
     * Asks for the platform API key and records it as the account's credential: false when the
     * person pressed Enter at the key with {@code canGoBack}, to go back to the kinds of account.
     */
    private boolean askAndSaveApiKey(SpawnConfig config, AccountTarget target, Prompts prompts, boolean canGoBack) {
        BuildOutput.gap();
        BuildOutput.step("Create a key at " + TerminalLink.link("https://platform.openai.com/api-keys") + ".");
        BuildOutput.step("OpenAI shows it only once.");
        BuildOutput.gap();
        var answer = prompts.askSecret(canGoBack ? "Paste the API key, or Enter to go back: " : "Paste the API key, or Enter to skip: ");
        var key = readSecret(answer);
        if (key.isBlank()) {
            if (canGoBack && answer != null) return false;
            // "No account yet" only when there is none: the ChatGPT account being switched stays as it was
            BuildOutput.step(hasAccountsToPreserve(config, "openai") ? "Skipped the API key."
                    : "Skipped. 'isx account add openai' adds one later.");
            return true;
        }
        // Which ChatGPT sessions this key ends, read before the save changes the config; they
        // end only after the save succeeded, or a failed save leaves config.yaml naming
        // ChatGPT accounts whose sessions are already gone
        var leaving = new ArrayList<String>();
        if (target.replaceOthers()) {
            leaving.addAll(chatGptAccounts(config, null));
        } else if ("chatgpt".equals(dev.incusspawn.config.AccountResolver.value(
                config.tree(), "openai", target.name(), "type"))) {
            leaving.add(target.name());
            // The account's type goes with its session -- or isChatGpt would keep answering
            // true and the pasted key would be ignored. In the guest, the API-key login
            // script replaces the session's auth.json, whose proofs died with the session.
            NamespaceAccounts.put(config, "openai", target.name(), "type", "");
        }
        if (target.replaceOthers()) {
            NamespaceAccounts.replaceAll(config, "openai", target.name(), java.util.Map.of("apiKey", key));
        } else {
            NamespaceAccounts.put(config, "openai", target.name(), "apiKey", key);
        }
        config.save();
        endChatGptSessions(leaving);
        // Nothing checks an OpenAI key, so the one outcome line says so, and what to do (#2528)
        BuildOutput.ok("API key saved on " + Platform.thisComputer() + ", not checked");
        BuildOutput.stepNote("If it fails, 'isx account add openai' replaces it.");
        return true;
    }

    /**
     * The ChatGPT accounts a save that replaces every account leaves behind, read before it
     * changes the config: {@code keep} names the one whose own session stays (the account the
     * save just gave a fresh session). A session left behind keeps its refresh token on disk
     * with nothing pointing at it, and a later account of the same name would serve it again.
     */
    private static List<String> chatGptAccounts(SpawnConfig config, String keep) {
        var names = new ArrayList<String>();
        for (var name : NamespaceAccounts.names(config, "openai")) {
            if (name.equals(keep)) continue;
            if ("chatgpt".equals(dev.incusspawn.config.AccountResolver.value(
                    config.tree(), "openai", name, "type"))) names.add(name);
        }
        return names;
    }

    /**
     * Ends the named accounts' host sessions, what named them having just left the config.
     * Every name is tried: this runs after {@code config.save()}, so one failure aborting the
     * step would leave the later sessions' refresh tokens on disk with nothing to roll back to.
     */
    private static void endChatGptSessions(List<String> names) {
        if (names.isEmpty()) return;
        var store = new dev.incusspawn.proxy.ChatGptSessionStore();
        for (var name : names) {
            try {
                store.remove(name);
            } catch (java.io.UncheckedIOException e) {
                BuildOutput.warn("Could not end the ChatGPT session of account '" + name + "': "
                                + BaseCommand.failure(e),
                        "Its refresh token stays on disk with nothing pointing at it.",
                        "Sign in again with 'isx init' to replace the session it left behind.");
            }
        }
    }

    /**
     * The Claude account step, shared by the first run, {@code isx account add claude} and
     * {@code isx init credentials}: a credential found in the environment first, then the
     * accounts already saved, then which kind of account to set up (#2528).
     */
    void setupClaudeAuth(SpawnConfig config, Prompts prompts) {
        var target = config.getClaude().hasAuth()
                ? new AccountTarget(config.getClaude().accountName(), false)
                : AccountTarget.FRESH;
        if (useClaudeFromEnvironment(config, prompts, target)) return;

        // Offer to keep, extend or re-point the configured accounts on re-run.
        // allAccounts() rather than hasAuth(): even when every account is incomplete, the
        // user must be able to see (and remove) them rather than hand-editing config.yaml.
        if (!config.getClaude().allAccounts().isEmpty()) {
            var managed = manageClaudeAccounts(config, prompts);
            if (managed.isEmpty()) return;
            target = managed.get();
        }
        chooseClaudeAccountKind(config, prompts, target);
    }

    /** Which kind of Claude account, until one is set up or the person chooses none: Enter asks again. */
    private void chooseClaudeAccountKind(SpawnConfig config, Prompts prompts, AccountTarget target) {
        while (true) {
            var answer = prompts.choose("Which kind of Claude account do you have?", List.of(
                    "An Anthropic API key\nFrom the Claude Console, billed by use.",
                    "A Claude subscription (Pro, Max or Team)\nClaude Code makes a token for it.",
                    "Google Cloud Vertex AI\nBilled to your Google Cloud project.",
                    "None for now\nAdd one later with 'isx account add claude'."), null, null);
            // End of input is no answer at all: nothing is set up
            var choice = answer == null ? "4" : answer.strip();
            var outcome = switch (choice) {
                case "1" -> setupClaudeApiKey(config, prompts, target, null);
                case "2" -> claudeToken(config, prompts, target, false);
                case "3" -> setupClaudeVertex(config, prompts, target);
                case "4" -> {
                    BuildOutput.step(claudeSkipped(config));
                    yield Outcome.DONE;
                }
                default -> Outcome.BACK;
            };
            if (outcome == Outcome.DONE) return;
        }
    }

    /** How a part of a credential step ended: done (saved or skipped), or back to the question before it. */
    enum Outcome { DONE, BACK }

    /**
     * A Claude credential found in this computer's environment, offered before anything is
     * asked: true when it was saved and the step is over.
     */
    private boolean useClaudeFromEnvironment(SpawnConfig config, Prompts prompts, AccountTarget target) {
        var envVertex = env("CLAUDE_CODE_USE_VERTEX");
        var envApiKey = env("ANTHROPIC_API_KEY");
        var envOauthToken = env("CLAUDE_CODE_OAUTH_TOKEN");
        var here = Platform.thisComputer();
        if ("1".equals(envVertex)) {
            var region = env("CLOUD_ML_REGION");
            var projectId = env("ANTHROPIC_VERTEX_PROJECT_ID");
            Lines.step("Found Vertex AI settings in the environment of " + here + ".");
            if (region.isBlank() || projectId.isBlank()) {
                Lines.step((region.isBlank() && projectId.isBlank() ? "CLOUD_ML_REGION and ANTHROPIC_VERTEX_PROJECT_ID are"
                        : (region.isBlank() ? "CLOUD_ML_REGION" : "ANTHROPIC_VERTEX_PROJECT_ID") + " is")
                        + " not set, so isx cannot use them.");
                return false;
            }
            prompts.say("Project: " + OutputFormat.oneLine(projectId) + ", region: " + OutputFormat.oneLine(region));
            var result = verifyVertexConfig(region, projectId);
            if (result.verified()) {
                if (!askYesNo(prompts, envAccountPrompt(config, target, "Use them for " + machines() + "?"), true)) return false;
                saveVertexConfig(config, target, region, projectId);
                BuildOutput.ok("Vertex AI settings verified and saved on " + here);
                return true;
            }
            // Only settings that could not be checked may be saved unverified (#2528)
            if (result.reached()) {
                Lines.step("They did not verify, so isx does not use them. " + result.message());
                return false;
            }
            Lines.step(couldNotCheck("them", result.message()));
            if (!askYesNo(prompts, "Save them unverified?", false)) return false;
            saveVertexConfig(config, target, region, projectId);
            BuildOutput.ok("Vertex AI settings saved on " + here + ", unverified");
            BuildOutput.stepNote("If they fail, 'isx account add claude' replaces them.");
            return true;
        }
        if (!envOauthToken.isBlank()) {
            BuildOutput.step("Found a Claude token in CLAUDE_CODE_OAUTH_TOKEN.");
            warnOfShape(envOauthToken, false);
            var result = verifyOauthToken(envOauthToken);
            if (!result.verified()) {
                // As for Vertex AI: only a check that could not run may keep it, unverified (#2528)
                if (result.reached()) {
                    Lines.step("It did not verify, so isx does not use it. " + result.message());
                    return false;
                }
                Lines.step(couldNotCheck("it", result.message()));
                if (!askYesNo(prompts, envAccountPrompt(config, target, "Save it unverified, and check it later?"), false)) return false;
                saveOauthConfig(config, target, envOauthToken, false);
                return true;
            }
            if (!askYesNo(prompts, envAccountPrompt(config, target, "Use it for " + machines() + "?"), true)) return false;
            saveOauthConfig(config, target, envOauthToken, true);
            return true;
        }
        if (!envApiKey.isBlank()) {
            BuildOutput.step("Found an Anthropic API key in ANTHROPIC_API_KEY.");
            var result = verifyAnthropicApiKey(envApiKey);
            if (!result.verified()) {
                if (result.reached()) {
                    Lines.step("It did not verify, so isx does not use it. " + result.message());
                    return false;
                }
                Lines.step(couldNotCheck("it", result.message()));
                if (!askYesNo(prompts, envAccountPrompt(config, target, "Save it unverified, and check it later?"), false)) return false;
                saveDirectConfig(config, target, envApiKey);
                BuildOutput.ok("API key saved on " + here + ", unverified");
                BuildOutput.stepNote("If it fails, 'isx account add claude' replaces it.");
                return true;
            }
            if (!askYesNo(prompts, envAccountPrompt(config, target, "Use it for " + machines() + "?"), true)) return false;
            saveDirectConfig(config, target, envApiKey);
            BuildOutput.ok("API key verified and saved on " + here);
            return true;
        }
        return false;
    }

    /** Vertex AI's project and region, until they verify or are saved anyway: BACK when the person goes back. */
    Outcome setupClaudeVertex(SpawnConfig config, Prompts prompts, AccountTarget target) {
        var here = Platform.thisComputer();
        BuildOutput.gap();
        BuildOutput.step("Vertex AI needs your Google Cloud project and a region");
        BuildOutput.step("where Claude is enabled. isx signs each request with");
        BuildOutput.step("gcloud on " + here + ", so sign in to gcloud first.");
        BuildOutput.gap();
        while (true) {
            var projectId = readInput(prompts.ask("Project ID, or Enter to go back: "));
            if (projectId.isBlank()) return Outcome.BACK;
            var region = readInput(prompts.ask("Region (such as us-east5): "));
            if (region.isBlank()) return Outcome.BACK;
            var result = verifyVertexConfig(region, projectId);
            if (result.verified()) {
                saveVertexConfig(config, target, region, projectId);
                BuildOutput.ok("Vertex AI settings verified and saved on " + here);
                return Outcome.DONE;
            }
            switch (askVerificationFailureAction(prompts, "The settings", result.message(), result.reached())) {
                case SKIP -> {
                    BuildOutput.step(claudeSkipped(config));
                    return Outcome.DONE;
                }
                case SAVE_UNVERIFIED -> {
                    saveVertexConfig(config, target, region, projectId);
                    BuildOutput.ok("Vertex AI settings saved on " + here + ", unverified");
                    BuildOutput.stepNote("If they fail, 'isx account add claude' replaces them.");
                    return Outcome.DONE;
                }
                case RETRY -> {
                    // asked again
                }
            }
        }
    }

    /**
     * The first run's Claude step when Claude Code on this computer is signed in with a
     * subscription (#2412): the kind of account is answered by what was found, and the token
     * comes from {@code claude setup-token}, never from the computer's own sign-in, which is
     * not copied.
     *
     * <p>isx reads the token the command prints through {@code script(1)}, which gives it a
     * pseudo-terminal and logs to {@code /dev/null} ({@link SetupTokenCapture}, #2543). The paste
     * stays for when none is read, none verifies, or {@code script} is missing. Anything else to go on (a credential in the environment, accounts already
     * saved) takes the full step instead, which reads and keeps those.
     */
    void setupClaudeSubscription(SpawnConfig config, Prompts prompts) {
        var fromEnvironment = "1".equals(env("CLAUDE_CODE_USE_VERTEX")) || !env("ANTHROPIC_API_KEY").isBlank()
                || !env("CLAUDE_CODE_OAUTH_TOKEN").isBlank();
        if (fromEnvironment || !config.getClaude().allAccounts().isEmpty()) {
            setupClaudeAuth(config, prompts);
            return;
        }
        Lines.step("Claude Code on " + Platform.thisComputer() + " is signed in with a subscription.");
        Lines.step("isx uses a separate token for " + machines() + ".");
        if (claudeToken(config, prompts, AccountTarget.FRESH, true) == Outcome.BACK) {
            chooseClaudeAccountKind(config, prompts, AccountTarget.FRESH);
        }
    }

    /**
     * A subscription's token, made by {@code claude setup-token}: run here when Claude Code is
     * installed, else on another computer. With {@code offerOther}, {@code o} chooses another
     * kind of account: BACK, as going back from the paste does where the step was chosen.
     */
    private Outcome claudeToken(SpawnConfig config, Prompts prompts, AccountTarget target, boolean offerOther) {
        var installed = hostHasCommand("claude");
        while (true) {
            var pastePrompt = "Paste the token, or Enter to go back: ";
            BuildOutput.gap();
            if (installed) {
                BuildOutput.step("To make the token:");
                prompts.say("  1. 'claude setup-token' runs and opens your browser");
                prompts.say("  2. You approve, and it prints a token");
                prompts.say("  3. isx reads the token. If it cannot, you paste it");
                BuildOutput.gap();
                prompts.say("Make the token now?");
                prompts.say("  Y  run 'claude setup-token' here");
                prompts.say("  n  I run it myself and paste the token");
                if (offerOther) prompts.say("  o  another kind of account (API key, Vertex AI)");
                prompts.say("  s  skip this account for now");
                var answer = prompts.ask("Choice [Y]: ");
                var choice = answer == null ? "s" : answer.strip().toLowerCase(java.util.Locale.ROOT);
                switch (choice) {
                    case "", "y", "yes" -> {
                        if (saveTokenRead(config, target, runClaudeSetupToken())) return Outcome.DONE;
                        // The token is on the screen above, wrapped and among Claude Code's own lines
                        BuildOutput.stepNote("The token above starts with '" + SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX + "'.");
                        pastePrompt = "Paste the token above, or Enter to go back: ";
                    }
                    case "n", "no" -> { }
                    case "s" -> {
                        BuildOutput.step(claudeSkipped(config));
                        return Outcome.DONE;
                    }
                    case "o" -> {
                        if (offerOther) return Outcome.BACK;
                        continue;
                    }
                    default -> {
                        continue;
                    }
                }
            } else {
                BuildOutput.step("Claude Code is not installed on " + Platform.thisComputer() + ", so make");
                BuildOutput.step("the token on a computer that has it");
                BuildOutput.step("(" + TerminalLink.link("https://claude.com/claude-code") + "):");
                prompts.say("  1. Run 'claude setup-token'. It opens your browser.");
                prompts.say("  2. Approve, and it prints a token.");
                prompts.say("  3. Paste the token here.");
            }
            BuildOutput.gap();
            var outcome = pasteClaudeToken(config, prompts, target, pastePrompt);
            // Back from a token made here returns to its menu; with no menu, to the kinds of account
            if (outcome == Outcome.DONE || !installed) return outcome;
        }
    }

    /** Takes the token until it verifies, is saved anyway or skipped: BACK on Enter. */
    private Outcome pasteClaudeToken(SpawnConfig config, Prompts prompts, AccountTarget target, String prompt) {
        while (true) {
            var token = readSecret(prompts.askSecret(prompt));
            if (token.isBlank()) return Outcome.BACK;
            warnOfShape(token, true);
            var result = verifyOauthToken(token);
            if (result.verified()) {
                saveOauthConfig(config, target, token, true);
                return Outcome.DONE;
            }
            switch (askVerificationFailureAction(prompts, "The token", result.message(), result.reached())) {
                case SKIP -> {
                    BuildOutput.step(claudeSkipped(config));
                    return Outcome.DONE;
                }
                case SAVE_UNVERIFIED -> {
                    saveOauthConfig(config, target, token, false);
                    return Outcome.DONE;
                }
                case RETRY -> {
                    // asked again
                }
            }
        }
    }

    /**
     * Asks for an Anthropic API key until one verifies or is saved anyway: BACK when the person
     * goes back. {@code skipped} is what a skip says, or null for the Claude step's own words.
     */
    Outcome setupClaudeApiKey(SpawnConfig config, Prompts prompts, AccountTarget target, String skipped) {
        var here = Platform.thisComputer();
        BuildOutput.gap();
        BuildOutput.step("Create a key in the Claude Console (Settings > API keys):");
        BuildOutput.step(TerminalLink.link(AiHelpClient.API_KEYS_URL));
        BuildOutput.gap();
        while (true) {
            // With nothing to go back to (the AI Help offer), Enter skips
            var key = readSecret(prompts.askSecret(skipped != null ? "Paste the API key, or Enter to skip: "
                    : "Paste the API key, or Enter to go back: "));
            if (key.isBlank()) return Outcome.BACK;
            var result = verifyAnthropicApiKey(key);
            if (result.verified()) {
                saveDirectConfig(config, target, key);
                BuildOutput.ok("API key verified and saved on " + here);
                return Outcome.DONE;
            }
            switch (askVerificationFailureAction(prompts, "The key", result.message(), result.reached())) {
                case SKIP -> {
                    BuildOutput.step(skipped != null ? skipped : claudeSkipped(config));
                    return Outcome.DONE;
                }
                case SAVE_UNVERIFIED -> {
                    saveDirectConfig(config, target, key);
                    BuildOutput.ok("API key saved on " + here + ", unverified");
                    BuildOutput.stepNote("If it fails, 'isx account add claude' replaces it.");
                    return Outcome.DONE;
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

    static String askAccountName(Prompts prompts, java.util.Set<String> taken, String prompt) {
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

    /**
     * A check's answer: whether the credential verified, why not, and whether the check reached
     * the service at all. Only a check that could not run may be saved unverified (#2528):
     * a credential the service rejected would fail the same way in every isx machine.
     */
    record AuthResult(boolean verified, String message, boolean reached) {
        AuthResult(boolean verified, String message) {
            this(verified, message, true);
        }

        /** The check could not reach the service, or could not run. */
        static AuthResult unchecked(String message) {
            return new AuthResult(false, message, false);
        }
    }

    enum VerificationFailureAction { RETRY, SKIP, SAVE_UNVERIFIED }

    /**
     * Why a check of {@code what} ("the token") could not run: {@code why} alone when it already
     * says isx could not do something, never "isx could not check it: isx could not reach ...".
     */
    static String couldNotCheck(String what, String why) {
        return why.startsWith("isx could not") ? why : "isx could not check " + what + ": " + why;
    }

    /**
     * After {@code what} (as "The token") did not verify, for {@code why}: try again or skip it,
     * with the same {@code s} as the step's own menu. When the check could not run at all
     * ({@code reached} false), saving it unverified is offered too (#1441, #2528).
     */
    static VerificationFailureAction askVerificationFailureAction(Prompts prompts, String what, String why, boolean reached) {
        Lines.step(reached ? what + " did not verify. " + why
                : couldNotCheck(what.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + what.substring(1), why));
        BuildOutput.gap();
        prompts.say("What now?");
        prompts.say("  Y  try again");
        if (!reached) prompts.say("  u  save it unverified, and check it later");
        prompts.say("  s  skip this account for now");
        while (true) {
            var action = parseVerificationFailureAction(prompts.ask("Choice [Y]: "), !reached);
            if (action != null) return action;
            prompts.say(reached ? "Please answer y or s." : "Please answer y, u or s.");
        }
    }

    static VerificationFailureAction parseVerificationFailureAction(String answer, boolean saveOffered) {
        if (answer == null) return VerificationFailureAction.SKIP;
        return switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "", "y" -> VerificationFailureAction.RETRY;
            case "s" -> VerificationFailureAction.SKIP;
            case "u" -> saveOffered ? VerificationFailureAction.SAVE_UNVERIFIED : null;
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
     * Asks {@code question} for a secret, read unechoed and stripped. The receipt that once
     * followed a paste ("Received N characters") is gone: it was a second outcome line beside
     * the one that says the credential was saved, and Sanne chose one (#2528).
     */
    static String askSecret(Prompts prompts, String question) {
        var secret = readSecret(prompts.askSecret(question));
        return secret;
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
            return Optional.of("The token does not start with '" + SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX
                    + "', as one from 'claude setup-token' does.");
        }
        if (token.length() < OAUTH_TOKEN_MIN_PLAUSIBLE_LENGTH) {
            return Optional.of("The token is " + token.length() + " characters, shorter than one from"
                    + " 'claude setup-token'. If your terminal wrapped it, the paste may be cut short.");
        }
        return Optional.empty();
    }

    /**
     * Warns of a token's shape before it is checked, at the width of the frame. Its length only
     * when it was {@code pasted}: a token read from the environment was never pasted, so a short
     * one is not a paste cut short (#2528); its prefix whatever its source, so an API key in
     * {@code CLAUDE_CODE_OAUTH_TOKEN} is named.
     */
    private static void warnOfShape(String token, boolean pasted) {
        var shape = oauthTokenShapeWarning(token);
        if (!pasted && token != null && token.startsWith(SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX)) shape = Optional.empty();
        shape.ifPresent(warning -> {
            var lines = Lines.wrap(warning, Lines.WIDTH - 2);
            BuildOutput.warn(lines.getFirst(), lines.subList(1, lines.size()).toArray(String[]::new));
        });
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
    private void saveOauthConfig(SpawnConfig config, AccountTarget target, String oauthToken,
                                 boolean verified) {
        saveClaudeAccount(config, target, SpawnConfig.ClaudeAccount.ofOauth(oauthToken));
        var here = Platform.thisComputer();
        BuildOutput.ok(verified ? "Token verified and saved on " + here : "Token saved on " + here + ", unverified");
        if (!verified) BuildOutput.stepNote("If it fails, 'isx account add claude' replaces it.");
        // What 'claude setup-token' advises is for a token used by hand: isx answers it (#2528)
        BuildOutput.stepNote("You do not need to store or export it. isx keeps it.");
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

    AuthResult verifyAnthropicApiKey(String key) {
        if (!key.startsWith("sk-ant-")) {
            BuildOutput.warn("Key does not start with 'sk-ant-' (unexpected format).");
        }
        return verifyAnthropicCredential("x-api-key", key, "API key");
    }

    AuthResult verifyOauthToken(String token) {
        return verifyAnthropicCredential("Authorization", "Bearer " + token, "OAuth token");
    }

    /** Anthropic's answer to {@code headerName: headerValue}; the one network call, which tests replace. */
    AuthResult verifyAnthropicCredential(String headerName, String headerValue, String label) {
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
                case 401 -> new AuthResult(false, "Anthropic rejected it (HTTP 401): it may be expired or mistyped."
                        + apiErrorSuffix(response.body()));
                case 403 -> new AuthResult(true, label + " accepted (HTTP 403). It may have restricted permissions.");
                // Overloaded, rate-limited or failing: the service did not judge the credential
                case 429, 500, 502, 503, 504, 529 -> AuthResult.unchecked("Anthropic answered HTTP " + response.statusCode()
                        + ", so it could not check the " + label.toLowerCase(java.util.Locale.ROOT) + "." + apiErrorSuffix(response.body()));
                default -> new AuthResult(false, "Anthropic answered HTTP " + response.statusCode() + ", so the "
                        + label.toLowerCase(java.util.Locale.ROOT) + " may be invalid." + apiErrorSuffix(response.body()));
            };
        } catch (Exception e) {
            return AuthResult.unchecked("isx could not reach api.anthropic.com: " + e.getMessage());
        }
    }

    /**
     * The first token read from 'claude setup-token' that verifies, saved with one line that says
     * so: true when saved. Otherwise it says why and the caller asks for the paste (#2543), so a
     * token that does not verify is saved only after the person's answer there.
     */
    private boolean saveTokenRead(SpawnConfig config, AccountTarget target, List<String> tokens) {
        if (tokens.isEmpty()) return false;
        // A reading too short to be whole is a token cut at a wrap: not worth a check, or its note
        var whole = tokens.stream().filter(token -> oauthTokenShapeWarning(token).isEmpty()).toList();
        if (whole.isEmpty()) {
            BuildOutput.step("isx did not find the whole token on the screen.");
            return false;
        }
        AuthResult first = null;
        for (var token : whole) {
            var result = verifyOauthToken(token);
            if (result.verified()) {
                saveClaudeAccount(config, target, SpawnConfig.ClaudeAccount.ofOauth(token));
                BuildOutput.ok("Token received, verified and saved on " + Platform.thisComputer());
                // What 'claude setup-token' advises is for a token used by hand: isx answers it (#2528)
                BuildOutput.stepNote("You do not need to store or export it. isx keeps it.");
                return true;
            }
            if (first == null) first = result;
        }
        Lines.step(first.reached() ? "The token isx read did not verify. " + first.message()
                : couldNotCheck("the token isx read", first.message()));
        return false;
    }

    /**
     * Runs 'claude setup-token' on the terminal and returns the tokens read from what it printed,
     * longest first, or none. Under {@code script(1)} isx relays the output and reads it
     * ({@link SetupTokenCapture}); without {@code script} the command gets the terminal itself and
     * the person pastes the token.
     */
    List<String> runClaudeSetupToken() {
        if (!hostHasCommand("script")) {
            runClaudeSetupTokenAttached();
            return List.of();
        }
        try {
            var run = SetupTokenCapture.run(SetupTokenCapture.command(Platform.isMacOS()));
            if (run.scriptFailed()) {
                // This script does not take the options isx gives it: the command gets the terminal itself
                runClaudeSetupTokenAttached();
                return List.of();
            }
            if (run.exitCode() == null) {
                BuildOutput.warn("'claude setup-token' did not finish within 2 minutes, so isx stopped it.");
            } else if (run.exitCode() != 0 && run.tokens().isEmpty()) {
                BuildOutput.warn("'claude setup-token' failed (exit code " + run.exitCode() + ").");
            } else if (run.tokens().isEmpty()) {
                BuildOutput.step("isx did not find a token on the screen.");
            }
            return run.tokens();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            BuildOutput.warn("Could not run 'claude setup-token': " + BaseCommand.failure(e));
            return List.of();
        }
    }

    /** Runs 'claude setup-token' attached to the terminal, where {@code script} is missing: the person pastes. */
    private void runClaudeSetupTokenAttached() {
        try {
            var pb = new ProcessBuilder("claude", "setup-token");
            pb.inheritIO();
            var process = pb.start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                BuildOutput.warn("'claude setup-token' did not finish within 2 minutes, so isx stopped it.");
            } else if (process.exitValue() != 0) {
                BuildOutput.warn("'claude setup-token' failed (exit code " + process.exitValue() + ").");
            }
        } catch (Exception e) {
            BuildOutput.warn("Could not run 'claude setup-token': " + BaseCommand.failure(e));
        }
    }

    AuthResult verifyVertexConfig(String region, String projectId) {
        if (!commandExists("gcloud")) {
            return AuthResult.unchecked(
                    "gcloud is not installed on " + Platform.thisComputer() + ". Install it from "
                    + TerminalLink.link("https://cloud.google.com/sdk/docs/install") + ", then run 'gcloud auth login'.");
        }

        String accessToken;
        try {
            var pb = new ProcessBuilder("gcloud", "auth", "print-access-token");
            var process = pb.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return AuthResult.unchecked("gcloud did not answer within 15 seconds. Check its configuration.");
            }
            var stdout = new String(process.getInputStream().readAllBytes()).strip();
            var stderr = new String(process.getErrorStream().readAllBytes()).strip();
            if (process.exitValue() != 0 || stdout.isBlank()) {
                var detail = !stderr.isBlank() ? stderr : stdout;
                return AuthResult.unchecked(
                        "gcloud is not signed in" + (detail.isBlank() ? "" : " (" + OutputFormat.oneLine(detail) + ")")
                        + ". Run 'gcloud auth login'.");
            }
            accessToken = stdout;
        } catch (Exception e) {
            return AuthResult.unchecked("isx could not run gcloud: " + e.getMessage());
        }

        try {
            var host = ProxyConfig.vertexHost(region);
            var url = "https://" + host + "/v1/projects/" + projectId
                    + "/locations/" + region
                    + "/publishers/anthropic/models/" + ModelIds.get("vertex-probe") + ":rawPredict";
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
                // Rate-limited or failing: Vertex AI did not judge the settings
                case 429, 500, 502, 503, 504 -> AuthResult.unchecked(
                        "Vertex AI answered HTTP " + response.statusCode() + ", so it could not check them.");
                default -> new AuthResult(false,
                        "Unexpected Vertex AI response (HTTP " + response.statusCode() + ").");
            };
        } catch (Exception e) {
            return AuthResult.unchecked("isx could not reach Vertex AI: " + e.getMessage());
        }
    }

    /**
     * GitHub's fine-grained PAT creation form, pre-filled through its template-URL query
     * parameters with the permissions the agent needs: read/write on Contents, Issues and Pull
     * requests, and read on Email addresses so {@link #primaryGitHubEmail} can find the commit
     * identity. GitHub's permission picker hides anything not yet added, so without the
     * pre-fill the email permission is easy to miss. Resource owner, repository access and
     * expiration are left for the user to choose on the page.
     */
    static final String GH_PAT_NEW_URL = "https://github.com/settings/personal-access-tokens/new"
            + "?name=isx&description=Agent+token+for+isx+instances"
            + "&contents=write&issues=write&pull_requests=write&emails=read";

    /**
     * The fallback of {@code isx account add github} when no token is pasted: the account the
     * computer's {@code gh} is signed in to, usually the person's own, so it defaults to No.
     */
    private enum GhTokenOutcome { SAVED, NOT_OFFERED, FAILED }

    private GhTokenOutcome offerGhCliToken(SpawnConfig config, Prompts prompts, AccountTarget account) {
        if (!ghCliLoggedIn()) {
            return GhTokenOutcome.NOT_OFFERED;
        }
        BuildOutput.step("gh on " + Platform.thisComputer() + " is signed in to GitHub, usually as you.");
        BuildOutput.step("With that account, the agent acts on GitHub as you,");
        BuildOutput.step("with all of your access.");
        if (!askYesNo(prompts, "Use the account gh is signed in to?", false)) {
            return GhTokenOutcome.NOT_OFFERED;
        }
        return useGhToken(config, prompts, account, false);
    }

    /** Reads, verifies and saves the computer's 'gh' token into {@code account}, the person having asked for it. */
    /** {@code fromOptions}: the first run's three options follow a failure, else the token prompt does. */
    private GhTokenOutcome useGhToken(SpawnConfig config, Prompts prompts, AccountTarget account, boolean fromOptions) {
        var token = readGhAuthToken();
        if (token == null || token.isBlank()) {
            BuildOutput.warn("isx could not read gh's token: 'gh auth token' printed none.");
            return GhTokenOutcome.FAILED;
        }
        var result = verifyGitHubToken(token, prompts);
        if (result == null) {
            BuildOutput.warn("isx could not use your gh sign-in");
            Lines.step((gitHubReached ? "GitHub rejected the token gh is signed in with." : gitHubFailure())
                    + " Sign in again with 'gh auth login', or " + (fromOptions ? "choose another option." : "paste a token."));
            return GhTokenOutcome.FAILED;
        }
        saveGitHubToken(config, account, token, result, true);
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
     * Persist a GitHub token (and email, if any) and say what was saved: whether it verified, and
     * as which account the agent acts.
     *
     * <p>{@code target} is passed rather than parked on the instance: the Claude flow's
     * equivalent mutable field is how a credential wipe shipped once already (#741), because a
     * code path that returns early leaves a stale target for a later write to act on. An
     * argument cannot go stale.
     */
    private void saveGitHubToken(SpawnConfig config, AccountTarget target, String token, GitHubVerifyResult result,
                                 boolean verified) {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("token", token);
        var email = result == null ? null : result.email();
        if (email != null) values.put("email", email);
        saveAccount(config, GhSetup.NAMESPACE,
                dev.incusspawn.config.AccountResolver.shapeOf(config, GhSetup.NAMESPACE), target, values);
        config.save();
        var here = Platform.thisComputer();
        BuildOutput.ok(verified ? "Token verified and saved on " + here : "Token saved on " + here + ", unverified");
        if (result != null && result.login() != null) {
            BuildOutput.stepNote((noAgent ? "isx machines act" : "The agent acts") + " on GitHub as " + OutputFormat.oneLine(result.login()) + ".");
        }
        if (!verified) BuildOutput.stepNote("If it fails, 'isx account add github' replaces it.");
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

    /**
     * How to make a token of the agent's own account (#2528): signed in as that account, which a
     * browser signed in as the person is not, so isx prints the page rather than opening it.
     */
    private void printGitHubPatGuide(Prompts prompts) {
        BuildOutput.gap();
        var account = noAgent ? "the separate" : "the agent's";
        BuildOutput.step("You need a token from " + account + " GitHub account.");
        BuildOutput.gap();
        prompts.say("  1. Sign in to GitHub as " + account + " account, in a");
        prompts.say("     private window or another browser profile.");
        prompts.say("     No such account yet? Create one first:");
        prompts.say("     " + TerminalLink.link("https://github.com/signup"));
        prompts.say("  2. Open the isx token page there. Its permissions are");
        prompts.say("     filled in: contents, issues and pull requests (read");
        prompts.say("     and write), email address (read).");
        prompts.say("  3. Under \"Repository access\", pick the repositories.");
        prompts.say("     A new account does not own any yet, so its list is");
        prompts.say("     empty.");
        prompts.say("  4. Set an expiry date, click \"Generate token\" and copy");
        prompts.say("     the token. It starts with github_pat_.");
        BuildOutput.gap();
        BuildOutput.step("The isx token page:");
        BuildOutput.step(BuildOutput.ansi() ? TerminalLink.link(GH_PAT_NEW_URL, GH_PAT_PAGE) : GH_PAT_PAGE);
        BuildOutput.step("No link to click? Type u at the prompt for its address.");
        BuildOutput.gap();
    }

    /** The token page's address without its query, short enough to show; the link holds the whole of it. */
    static final String GH_PAT_PAGE = "github.com/settings/personal-access-tokens/new";

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
            // Each names its instance, so it is said as it is
            var restarts = InstanceLifecycle.reconcileAccountIdentities(incus, instance, new dev.incusspawn.util.Output() {
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
            restarts.forEach(BuildOutput::warn);
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
        while (account.isPresent() && collectGitHubToken(config, prompts, account.get(), false) == GitHubOutcome.SAVED) {
            account = chooseAccountTarget(config, GhSetup.NAMESPACE, "GitHub", prompts);
        }
    }

    /**
     * The first run's GitHub step (#2412, #2528): as which account the agent acts, each option
     * saying what it gives and what it costs. The computer's {@code gh} login is one of them,
     * named by its {@code login}, and "None for now" another: there is no default, so Enter asks
     * again. A namespace that already holds accounts, usable or not, gets the full step, which
     * keeps them.
     *
     * @param login the computer's {@code gh} login, or null when there is none
     * @return whether an account was saved
     */
    boolean offerGitHubForAgent(SpawnConfig config, Prompts prompts, String login) {
        if (hasAccountsToPreserve(config, GhSetup.NAMESPACE)) {
            setupGitHubAuth(config, prompts);
            return !dev.incusspawn.config.AccountResolver.usableAccountNames(config, GhSetup.NAMESPACE).isEmpty();
        }
        var none = login == null ? "2" : "3";
        while (true) {
            BuildOutput.gap();
            if (noAgent) {
                prompts.say("As which GitHub account should isx machines act?");
                prompts.say("");
                prompts.say("  1. A separate account");
                prompts.say("     What isx machines write or do on GitHub appears");
                prompts.say("     under it, not under your name. Needs a second GitHub");
                prompts.say("     account and a token for it.");
            } else {
                prompts.say("As which GitHub account should the agent act?");
                prompts.say("");
                prompts.say("  1. An account of the agent's own (recommended)");
                prompts.say("     Others can tell the agent's work from yours, and you");
                prompts.say("     decide what it can reach. Needs a second GitHub");
                prompts.say("     account and a token for it.");
            }
            if (login != null) {
                prompts.say("  2. Your own account, " + OutputFormat.oneLine(login));
                prompts.say(noAgent ? "     Quickest. What isx machines write or do on GitHub"
                        : "     Quickest. Whatever the agent writes or does on GitHub");
                prompts.say("     appears as yours, with all of your access.");
            }
            prompts.say("  " + none + ". None for now");
            prompts.say(noAgent ? "     isx machines cannot push, comment or open anything on"
                    : "     The agent cannot push, comment or open anything on");
            prompts.say("     GitHub. Add an account later with");
            prompts.say("     'isx account add github'.");
            if (login == null) {
                prompts.say("");
                prompts.say("To use your own account, sign in with 'gh auth login'");
                prompts.say("first, then run 'isx account add github'.");
            }
            prompts.say("");
            var answer = prompts.ask("Choice: ");
            // End of input is no answer at all: no account
            var choice = answer == null ? none : answer.strip();
            if (choice.equals("1")) {
                var outcome = collectGitHubToken(config, prompts, AccountTarget.FRESH, true);
                if (outcome == GitHubOutcome.BACK) continue;
                return outcome == GitHubOutcome.SAVED;
            }
            if (choice.equals("2") && login != null) {
                if (useGhToken(config, prompts, AccountTarget.FRESH, true) == GhTokenOutcome.SAVED) return true;
                // The failure said why: the options again
                continue;
            }
            if (choice.equals(none)) {
                BuildOutput.note("Skipped the GitHub account for now.");
                return false;
            }
            // Enter or a typo is asked again, never taken as a choice
        }
    }

    /** How the token part of the GitHub step ended. */
    enum GitHubOutcome { SAVED, SKIPPED, BACK }

    /**
     * Walks the person through one token for {@code account}. With {@code canGoBack} (the first
     * run's options), Enter goes back to them; without, Enter offers the account {@code gh} is
     * signed in to, then skips.
     */
    private GitHubOutcome collectGitHubToken(SpawnConfig config, Prompts prompts, AccountTarget account, boolean canGoBack) {
        printGitHubPatGuide(prompts);
        while (true) {
            var token = readSecret(prompts.askSecret(canGoBack ? "Paste the token, or Enter to go back: "
                    : "Paste the token, or Enter to skip: "));
            if (token.equalsIgnoreCase("u")) {
                // The whole address, for a terminal whose links cannot be clicked: on a line of its own
                BuildOutput.step(GH_PAT_NEW_URL);
                continue;
            }
            if (token.isBlank()) {
                if (canGoBack) return GitHubOutcome.BACK;
                var outcome = offerGhCliToken(config, prompts, account);
                if (outcome == GhTokenOutcome.SAVED) return GitHubOutcome.SAVED;
                // The fallback said why it failed: a token is asked for again
                if (outcome == GhTokenOutcome.FAILED) continue;
                reportGitHubSkipped(config);
                return GitHubOutcome.SKIPPED;
            }

            var result = verifyPastedGitHubToken(token, prompts);
            if (result == null) {
                // As for Claude: a check that failed for want of a network need not cost the token (#1441)
                switch (askVerificationFailureAction(prompts, "The token", gitHubFailure(), gitHubReached)) {
                    case SKIP -> {
                        reportGitHubSkipped(config);
                        return GitHubOutcome.SKIPPED;
                    }
                    case SAVE_UNVERIFIED -> {
                        saveGitHubToken(config, account, token, null, false);
                        return GitHubOutcome.SAVED;
                    }
                    case RETRY -> {
                        continue;
                    }
                }
            }
            // Same target for every save in this flow, so a re-minted PAT lands in the account
            // the user picked rather than back in the flat field.
            saveGitHubToken(config, account, token, result, true);
            return GitHubOutcome.SAVED;
        }
    }

    /**
     * A skip leaves every saved account as it was, so "no account added" is only said when there
     * are none: not after adding a first account this run, nor on a re-run that has some.
     */
    private static void reportGitHubSkipped(SpawnConfig config) {
        BuildOutput.step(hasAccountsToPreserve(config, GhSetup.NAMESPACE)
                ? "Skipped the token. This account stays as it was."
                : "Skipped. 'isx account add " + GhSetup.NAMESPACE + "' adds one later.");
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
                return Optional.of(at + (at == 1 ? " character comes" : " characters come")
                        + " before '" + prefix + "': something was typed before the paste.");
            }
        }
        return Optional.of("The token does not start with 'github_pat_' or 'ghp_', as GitHub's tokens do.");
    }

    /** The account a token verified as, and the email its commits carry, or null for none. */
    record GitHubVerifyResult(String login, String email) {}

    /** What GitHub says of a token's account: its login and number, and its primary verified email or null. */
    record GitHubUser(String login, long id, String email) {}

    record EmailParseResult(java.util.List<String> verified, String primary) {}

    /** Why the last token did not verify, as a sentence; null when no check has failed. */
    private String gitHubFailure;
    /** Whether the last failed check reached GitHub, which then rejected the token. */
    boolean gitHubReached = true;

    private String gitHubFailure() {
        return gitHubFailure != null ? gitHubFailure : "GitHub did not accept it.";
    }

    /**
     * Checks {@code token} with GitHub and asks which email the agent's commits carry: null,
     * with {@link #gitHubFailure} saying why, when it did not verify.
     */
    GitHubVerifyResult verifyGitHubToken(String token, Prompts prompts) {
        GitHubUser user;
        try {
            user = fetchGitHubUser(token);
        } catch (Exception e) {
            gitHubReached = e instanceof GitHubRejected;
            gitHubFailure = gitHubReached ? e.getMessage() : "isx could not reach GitHub: " + BaseCommand.failure(e);
            return null;
        }
        return new GitHubVerifyResult(user.login(), chooseCommitEmail(user, prompts, noAgent));
    }

    /** GitHub's answer for a token it does not accept, said for a person. */
    static final class GitHubRejected extends java.io.IOException {
        GitHubRejected(String message) { super(message); }
    }

    /** Reads the token's account from GitHub; tests answer it instead. */
    GitHubUser fetchGitHubUser(String token) throws Exception {
        var client = getHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/user"))
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        // GitHub answers a rate limit with 403 and x-ratelimit-remaining: 0 as well as with 429
        var rateLimited = response.statusCode() == 403
                && (response.headers().firstValue("x-ratelimit-remaining").map("0"::equals).orElse(false)
                    || response.headers().firstValue("retry-after").isPresent());
        if (response.statusCode() == 429 || rateLimited || response.statusCode() >= 500) {
            // GitHub did not judge the token: unreached, so it may be saved unverified
            throw new java.io.IOException("GitHub answered HTTP " + response.statusCode());
        }
        if (response.statusCode() != 200) {
            throw new GitHubRejected("GitHub rejected it (HTTP " + response.statusCode() + "): it may be expired or mistyped.");
        }
        var json = Jjq.parseForeign(response.body(), "GitHub's user");
        var login = json.pathText("login", null);
        var id = json.path("id").asLong(0);
        // The primary address, which the email question names; the public one only when the list is unreadable
        var email = primaryGitHubEmail(client, token);
        if (email == null) email = json.pathText("email", null);
        return new GitHubUser(login, id, email);
    }

    /**
     * Which email the agent's commits carry (#2528): GitHub's private address for the account,
     * which keeps the real one out of public history, or the account's primary address. Asked
     * only when there are both; Enter and end of input take the private one.
     */
    static String chooseCommitEmail(GitHubUser user, Prompts prompts) {
        return chooseCommitEmail(user, prompts, false);
    }

    /** {@link #chooseCommitEmail}, naming isx machines rather than the agent when {@code noAgent}. */
    static String chooseCommitEmail(GitHubUser user, Prompts prompts, boolean noAgent) {
        var noreply = user.id() > 0 && user.login() != null
                ? user.id() + "+" + user.login() + "@users.noreply.github.com" : null;
        var real = user.email() == null || user.email().endsWith("@users.noreply.github.com") ? null : user.email();
        if (noreply == null || real == null) {
            // No question to ask: what the commits carry is said once
            if (noreply != null) {
                Lines.step((noAgent ? "Commits in isx machines carry" : "The agent's commits carry")
                        + " GitHub's private address for " + OutputFormat.oneLine(user.login()) + ".");
            }
            return noreply != null ? noreply : user.email();
        }
        BuildOutput.gap();
        prompts.say(noAgent ? "Which email should commits in isx machines carry?" : "Which email should the agent's commits carry?");
        prompts.say("");
        prompts.say("  1. " + OutputFormat.oneLine(noreply));
        prompts.say("     GitHub's private address. Keeps the real one out of");
        prompts.say("     public history. (recommended)");
        prompts.say("  2. " + OutputFormat.oneLine(real));
        prompts.say("     The account's primary address.");
        prompts.say("");
        while (true) {
            var answer = prompts.ask("Choice [1]: ");
            var choice = answer == null ? "" : answer.strip();
            if (choice.isEmpty() || choice.equals("1")) return noreply;
            if (choice.equals("2")) return real;
            prompts.say("Please answer 1 or 2.");
        }
    }

    /** The account's primary verified email from GitHub's list, or null when the token cannot read it. */
    private String primaryGitHubEmail(java.net.http.HttpClient client, String token) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user/emails"))
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;
            var parsed = parseGitHubEmails(response.body());
            if (parsed == null) return null;
            if (parsed.primary() != null) return parsed.primary();
            return parsed.verified().stream().filter(e -> !e.endsWith("@users.noreply.github.com")).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
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
        // A namespace whose accounts may hold no credential here (a Google sign-in the proxy keeps
        // on the host, #2443) asks which kind this account is before asking for anything, with
        // "None for now" beside the kinds, and Enter at the key goes back to it (#2528)
        var hosted = tool.hostedSignIn();
        var menu = hosted != null && target != null;
        var here = Platform.thisComputer();
        var accountValues = new LinkedHashMap<String, String>();
        var sharedValues = new LinkedHashMap<String, String>();
        // Lists entered anew, by full path: their old items go once the new ones are saved
        var replacedLists = new ArrayList<String>();
        var secretSkipped = false;
        var secretHeld = false;
        // A secret typed or pasted in this step, not one kept
        var secretEntered = false;
        choice:
        while (true) {
            accountValues.clear();
            sharedValues.clear();
            replacedLists.clear();
            secretSkipped = false;
            secretHeld = false;
            secretEntered = false;
            if (menu) {
                var kind = chooseAccountKind(toolName, namespace, proxyDef, hosted, prompts);
                if (kind == AccountKind.NONE) {
                    // The accounts already saved, the one chosen to edit included, stay as they were
                    BuildOutput.note("No " + accountName(namespace) + " account "
                            + (hasAccountsToPreserve(config, namespace) ? "added." : "for now."));
                    return;
                }
                if (kind == AccountKind.HOSTED) {
                    accountValues.put("type", hosted.type());
                    BuildOutput.gap();
                    for (var line : hosted.help()) BuildOutput.step(line);
                    if (saveAccount(config, namespace, shape, target, accountValues)) {
                        // A keyed account moved to the hosted kind keeps no secret it no longer spends
                        if (targetExists) {
                            for (var configDef : proxyDef.getConfiguration().values()) {
                                var path = configDef.isSecret() || configDef.hasSecretField()
                                        ? proxyDef.accountConfigPath(configDef, target.name()) : "";
                                if (!path.isBlank()) config.removeConfigPath(path);
                            }
                        }
                        config.save();
                        BuildOutput.ok(OutputFormat.oneLine(hosted.outcome()));
                    }
                    return;
                }
            }
            var first = true;
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
                if (configDef.isList()) {
                    // A list is a question of its own: the intro below is for a first question that is a key
                    first = false;
                    var listPath = perAccount
                            ? namespace + "." + dev.incusspawn.config.AccountResolver.ACCOUNTS_KEY + "." + target.name() + "." + key
                            : fullPath;
                    var current = targetExists || !perAccount ? itemsAt(configTree, listPath) : Map.<String, String>of();
                    var labels = current.keySet().stream().map(k -> k.substring(0, k.indexOf('.'))).distinct().toList();
                    if (!labels.isEmpty()) {
                        // Kept whatever is answered, as a key is: only items entered anew replace them
                        secretHeld |= configDef.hasSecretField();
                        BuildOutput.step(label + ": " + OutputFormat.oneLine(String.join(", ", labels)));
                        if (askYesNo(prompts, "Keep current?", true, true)) {
                            // Kept items still travel with a "replace" into the new layout
                            if (perAccount) current.forEach((k, v) -> accountValues.put(key + "." + k, v));
                            continue;
                        }
                    }
                    BuildOutput.gap();
                    for (var helpLine : configDef.getHelp()) BuildOutput.step(OutputFormat.oneLine(helpLine));
                    if (configDef.getHelp().isEmpty() && !description.isBlank()) BuildOutput.step(label);
                    var items = askListItems(configDef, prompts);
                    if (items.isEmpty()) continue;
                    secretEntered |= configDef.hasSecretField();
                    replacedLists.add(listPath);
                    items.forEach((k, v) -> {
                        if (perAccount) accountValues.put(key + "." + k, v);
                        else sharedValues.put(fullPath + "." + k, v);
                    });
                    continue;
                }
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
                    // Kept whatever is answered: a replacement left blank does not clear it
                    secretHeld |= configDef.isSecret();
                    BuildOutput.step(label + ": " + maskSecret(existing));
                    if (askYesNo(prompts, "Keep current?", true, true)) {
                        // A kept value still has to travel with a "replace" into the new layout.
                        if (perAccount) accountValues.put(key, existing);
                        continue;
                    }
                }

                // Each question a block of its own; with no menu before it, the first says what it is for
                BuildOutput.gap();
                if (first && !menu && configDef.isSecret()) {
                    Lines.step(OutputFormat.oneLine(ExampleTemplate.title(tool)) + " uses "
                            + ("AEIOUaeiou".indexOf(label.charAt(0)) >= 0 ? "an " : "a ") + label + ".");
                    // What it is for apart from how to make one, as the other steps lay it out (#2528)
                    if (!configDef.getHelp().isEmpty()) BuildOutput.gap();
                }
                first = false;
                for (var helpLine : configDef.getHelp()) {
                    BuildOutput.step(OutputFormat.oneLine(helpLine));
                }
                if (configDef.getHelp().isEmpty() && !description.isBlank() && !configDef.isSecret()) {
                    BuildOutput.step(label);
                }
                // The paste apart from the help above it, as the Claude step's is
                if (configDef.isSecret() && !configDef.getHelp().isEmpty()) BuildOutput.gap();

                String value;
                if (configDef.isConfirm()) {
                    value = askYesNo(prompts, label + "?", false, true) ? "true" : "false";
                } else {
                    var question = (configDef.isSecret() ? "Paste the " + label : label)
                            + (menu && configDef.isSecret() ? ", or Enter to go back: " : ", or Enter to skip: ");
                    if (configDef.isSecret()) {
                        var answer = prompts.askSecret(question);
                        // Enter goes back to the kinds of account; end of input skips, as every prompt's does
                        if (menu && answer != null && readSecret(answer).isEmpty()) continue choice;
                        value = readSecret(answer);
                    } else {
                        value = readInput(prompts.ask(question));
                    }
                }
                if (configDef.isSecret()) {
                    secretSkipped |= value.isBlank();
                    secretHeld |= !value.isBlank();
                    secretEntered |= !value.isBlank();
                }
                if (value.isBlank()) continue;
                if (perAccount) {
                    accountValues.put(key, value);
                } else {
                    sharedValues.put(fullPath, value);
                }
            }
            break;
        }

        boolean savedAny = false;
        // Only a list entered anew is cleared, and only an account's own when it is the one written
        for (var listPath : replacedLists) config.removeConfigPath(listPath);
        if (target != null && !accountValues.isEmpty()) {
            savedAny = saveAccount(config, namespace, shape, target, accountValues);
            // Nothing saved is said once, by the report below (#2528)
            // A hosted account given a credential is one no longer: its type would make the
            // credential unread (a 'type: google' account spends no key, #2443)
            var typePath = namespace + "." + dev.incusspawn.config.AccountResolver.ACCOUNTS_KEY + "." + target.name() + ".type";
            if (savedAny && hosted != null && targetExists
                    && hosted.type().equals(ToolProxyResolver.navigateConfigPath(config.tree(), typePath))) {
                config.removeConfigPath(typePath);
            }
        }
        for (var shared : sharedValues.entrySet()) {
            config.setConfigByPath(shared.getKey(), shared.getValue());
            savedAny = true;
        }

        if (savedAny) config.save();
        // A list whose items hold a secret is the credential too (AccountShape.declaredBy)
        var secret = proxyDef.getConfiguration().values().stream().filter(c -> c.isSecret() || c.hasSecretField())
                .map(c -> OutputFormat.oneLine(c.getDescription())).filter(d -> !d.isBlank()).findFirst().orElse("Credential");
        // What was saved without a new credential is said by name, never reported as nothing
        var answered = proxyDef.getConfiguration().values().stream().filter(c -> !c.isSecret())
                .filter(c -> sharedValues.containsKey(proxyDef.fullConfigPath(c)))
                .map(c -> "\"" + OutputFormat.oneLine(c.getDescription()) + "\"").toList();
        var answersSaved = answered.isEmpty() ? "Your other answers are saved."
                : answered.size() == 1 ? "Your answer to " + answered.getFirst() + " is saved."
                : "Your answers to " + OutputFormat.andList(answered) + " are saved.";
        // A key kept as it was is not newly saved, so never "not checked" (#2528)
        if (savedAny && (secretEntered || (!secretHeld && !secretSkipped))) {
            // Nothing checks a tool's credential, so the one outcome line says so, and what to do (#2528)
            BuildOutput.ok(secret + " saved on " + here + ", not checked");
            BuildOutput.stepNote("If it fails, '" + CredentialCheck.stepCommand(toolName,
                    new dev.incusspawn.tool.ToolDefLoader().allToolSetups()) + "' replaces it.");
            return;
        }
        if (secretHeld) {
            // A credential kept as it was is still there: never "add one later"
            BuildOutput.step("Your credential stays as it was.");
            if (savedAny) Lines.step(answersSaved);
            return;
        }
        var skipped = "Credential".equals(secret) ? "credential" : secret;
        // Read only when it is said: loading every tool definition is not free
        var later = "Add one later with '" + CredentialCheck.stepCommand(toolName, new dev.incusspawn.tool.ToolDefLoader().allToolSetups())
                + "'.";
        Lines.step("Skipped the " + skipped + ".");
        Lines.step(later);
        if (savedAny) Lines.step(answersSaved);
    }

    /** Which kind of account a namespace with a hosted sign-in sets up, or none for now. */
    enum AccountKind { KEY, HOSTED, NONE }

    /**
     * A list's items asked one at a time ({@link ToolDef.ConfigEntry#isList}): a label, then each
     * field, a secret one through {@link #askSecret}, until Enter at a label or No to adding
     * another. Keys are relative to the list: {@code <label>.<field path>}.
     */
    Map<String, String> askListItems(ToolDef.ConfigEntry list, Prompts prompts) {
        var values = new LinkedHashMap<String, String>();
        var labels = new java.util.HashSet<String>();
        while (true) {
            var itemLabel = readInput(prompts.ask("Name for this entry, lowercase (or press Enter to finish): ")).strip();
            if (itemLabel.isEmpty()) break;
            if (!ToolProxyResolver.isItemLabel(itemLabel) || !labels.add(itemLabel)) {
                prompts.say("Use a new name of lowercase letters, digits, '-' and '_', starting with a letter or digit.");
                continue;
            }
            var before = values.size();
            for (var field : list.getForEach().entrySet()) {
                var fieldDef = field.getValue();
                var fieldLabel = fieldDef.getDescription().isBlank()
                        ? OutputFormat.oneLine(field.getKey()) : OutputFormat.oneLine(fieldDef.getDescription());
                var question = fieldLabel + ": ";
                var value = fieldDef.isSecret() ? askSecret(prompts, question) : readInput(prompts.ask(question)).strip();
                if (!value.isBlank()) values.put(itemLabel + "." + fieldDef.fieldPath(field.getKey()), value);
            }
            if (values.size() == before) {
                prompts.say("Nothing entered for " + itemLabel + ", so it is not saved.");
                labels.remove(itemLabel);
            }
            if (!askYesNo(prompts, "Add another?", false, true)) break;
        }
        return values;
    }

    /** A list's items at dotted {@code path}, as {@code <label>.<field>} to value; empty when it holds none. */
    private static Map<String, String> itemsAt(io.hyperfoil.tools.jjq.value.JqValue tree, String path) {
        var node = tree;
        for (var segment : path.split("\\.")) {
            if (!(node instanceof io.hyperfoil.tools.jjq.value.JqObject object) || !object.keys().contains(segment)) return Map.of();
            node = object.getField(segment);
        }
        var result = new LinkedHashMap<String, String>();
        if (node instanceof io.hyperfoil.tools.jjq.value.JqObject items) {
            items.forEach((itemLabel, item) -> {
                if (item instanceof io.hyperfoil.tools.jjq.value.JqObject fields) {
                    fields.forEach((field, value) -> {
                        if (!value.isContainer() && !value.isNull() && !value.asText().isBlank()) {
                            result.put(itemLabel + "." + field, value.asText());
                        }
                    });
                }
            });
        }
        return result;
    }

    /**
     * The kinds of account, each with a line on what it is, and "None for now": Enter asks again
     * (#2528). The hosted kind is offered only to a tool that can sign in that way: a first run
     * for Gemini CLI never offers Antigravity CLI's Google sign-in.
     */
    private AccountKind chooseAccountKind(String toolName, String namespace, ToolDef.ProxyDef proxyDef,
                                          ToolSetup.HostedSignIn hosted, Prompts prompts) {
        // What the other kind of account holds, by the name its own prompt gives it
        var credential = proxyDef.getConfiguration().values().stream().filter(ToolDef.ConfigEntry::isSecret)
                .map(c -> OutputFormat.oneLine(c.getDescription())).filter(d -> !d.isBlank()).findFirst()
                .orElse("credential");
        var offered = !firstRun || agent == null || hosted.tools().contains(agent);
        var options = new ArrayList<String>();
        options.add(("AEIOUaeiou".indexOf(credential.charAt(0)) >= 0 ? "An " : "A ") + credential
                + "\n" + OutputFormat.oneLine(hosted.keyDescription()));
        if (offered) options.add("A " + OutputFormat.oneLine(hosted.label()) + "\n" + hosted.description());
        options.add("None for now\nAdd one later with 'isx account add " + OutputFormat.oneLine(namespace) + "'.");
        while (true) {
            var answer = prompts.choose("Which kind of " + accountName(namespace) + " account do you have?", options, null, null);
            // End of input is no answer at all: nothing is set up
            if (answer == null) return AccountKind.NONE;
            switch (answer.strip()) {
                case "1" -> { return AccountKind.KEY; }
                case "2" -> { return offered ? AccountKind.HOSTED : AccountKind.NONE; }
                case "3" -> {
                    if (offered) return AccountKind.NONE;
                }
                default -> { }
            }
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
