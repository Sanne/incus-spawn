package dev.incusspawn.tool;

import dev.incusspawn.config.ModelIds;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.util.BuildOutput;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * opencode, the provider-agnostic coding agent: like Pi it spends Claude's, OpenAI's or
 * Gemini's credential by the {@code provider} its template chose, and has none of its own.
 */
public class OpencodeSetup implements ToolSetup {

    public static final String DEFAULT_PROVIDER = "anthropic";
    static final String DEFAULT_MODEL = ModelIds.get("opencode-default");

    static final String NPM_PACKAGE = "opencode-ai";
    static final String CONFIG_PATH = "/home/agentuser/.config/opencode/opencode.json";

    /**
     * Pi's location, so a template with both keeps one file. opencode only finds the root's
     * {@code AGENTS.md} by itself outside a Git worktree, so its config names it in
     * {@code instructions} too; the user's {@code ~/.config/opencode/AGENTS.md} stays theirs.
     */
    public static final String CONTEXT_PATH = PiSetup.CONTEXT_PATH;

    /**
     * What opencode's {@code anthropic} provider sends as its API key: the claude namespace's
     * proof, which the proxy swaps for the account's own credential, whatever its type. Not
     * {@code ANTHROPIC_API_KEY}, which Claude Code also reads: with an OAuth account it would
     * switch Claude Code to API-key auth.
     */
    static final ProofToken.Placeholder ANTHROPIC_KEY = new ProofToken.Placeholder(
            "OPENCODE_ANTHROPIC_API_KEY", "sk-ant-", SpawnConfig.ClaudeConfig.NAMESPACE);

    @Override
    public boolean codingAgent() { return true; }

    @Override
    public McpClient mcpClient() { return McpClients.OPENCODE; }

    @Override
    public String name() { return "opencode"; }

    @Override
    public String description() { return "opencode — AI coding assistant"; }

    @Override
    public Map<String, ToolDef.ParameterDef> parameters() {
        var params = new LinkedHashMap<String, ToolDef.ParameterDef>();

        var provider = new ToolDef.ParameterDef();
        provider.setType("string");
        provider.setDescription("opencode provider (e.g. anthropic, openai, google)");
        provider.setPattern("^[a-z][a-z0-9_-]*$");
        provider.setOptional(true);
        provider.setReconfigurable(true);
        provider.setDefault(DEFAULT_PROVIDER);
        params.put("provider", provider);
        params.put(PiSetup.PROVIDERS, PiSetup.providersParameter("opencode", List.of("anthropic", "openai", "google")));

        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Model ID (e.g. " + ModelIds.get("example-claude-sonnet") + ", " + ModelIds.get("example-openai")
                + ", " + ModelIds.get("example-gemini-flash") + "); omitted, "
                + DEFAULT_MODEL + " for " + DEFAULT_PROVIDER + " and opencode's own default otherwise");
        model.setPattern(MODEL_ID_PATTERN);
        model.setOptional(true);
        model.setReconfigurable(true);
        // No declared default, as with Pi: one would be resolved before the build, and every
        // provider would get a Claude model.
        params.put("model", model);

        var small = new ToolDef.ParameterDef();
        small.setType("string");
        small.setDescription("Model ID for opencode's small tasks such as session titles; omitted, opencode's own choice, "
                + "which setting model does not change");
        small.setPattern(MODEL_ID_PATTERN);
        small.setOptional(true);
        small.setReconfigurable(true);
        params.put("small-model", small);

        var effort = new ToolDef.ParameterDef();
        effort.setType("string");
        effort.setDescription("Reasoning effort, as the variant opencode's build and plan agents start with (none, minimal, low,"
                + " medium, high, xhigh, max); which exist depends on the model, and opencode ignores one the model lacks."
                + " Needs model, unless the provider is " + DEFAULT_PROVIDER);
        effort.setPattern("^(none|minimal|low|medium|high|xhigh|max)$");
        effort.setOptional(true);
        effort.setReconfigurable(true);
        params.put("effort", effort);
        return params;
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var a = new ToolDef.ActionEntry();
        a.setLabel("opencode");
        a.setType("shell");
        // Back in the directory of the latest top-level session, as Bob's action does; opencode's
        // --continue resumes that directory's last session.
        a.setCommand("d=$(sqlite3 -noheader ~/.local/share/opencode/opencode.db \"SELECT directory FROM session"
                + " WHERE parent_id IS NULL AND time_archived IS NULL ORDER BY time_updated DESC LIMIT 1\" 2>/dev/null);"
                + " if [ -n \"$d\" ] && [ -d \"$d\" ]; then cd \"$d\"; opencode --continue || opencode; else opencode; fi");
        a.setAutoReturn(true);
        return List.of(a);
    }

    @Override
    public List<String> packages() {
        // ripgrep: opencode's search tools use rg, and download it on first use when it is missing
        return List.of("nodejs", "ripgrep", "sqlite");
    }

    @Override
    public Set<String> credentialNamespaces() {
        return new LinkedHashSet<>(List.of(SpawnConfig.ClaudeConfig.NAMESPACE, "openai", PiSetup.GEMINI_NAMESPACE));
    }

    /**
     * {@code OPENAI_API_KEY} and {@code GEMINI_API_KEY} are Codex's and Gemini CLI's; this one is
     * opencode's own. Theirs are filled whether or not Codex or Gemini CLI is in the image: a start
     * reads the declarations of every tool the proxy serves ({@link ProofToken#declared()}), which
     * includes both built-in tools.
     */
    @Override
    public List<ProofToken.Placeholder> placeholders() {
        return List.of(ANTHROPIC_KEY);
    }

    @Override
    public Set<String> credentialNamespaces(Map<String, String> resolvedParams) {
        // Claude's first, as Pi's: it is the one credentialProblem judges
        var credentials = credentials(resolvedParams);
        var namespaces = new LinkedHashSet<String>();
        if (credentials.contains(Credential.ANTHROPIC)) namespaces.add(SpawnConfig.ClaudeConfig.NAMESPACE);
        if (credentials.contains(Credential.OPENAI)) namespaces.add("openai");
        if (credentials.contains(Credential.GEMINI)) namespaces.add(PiSetup.GEMINI_NAMESPACE);
        return namespaces;
    }

    /** Which provider, so the first run sets up that provider's account, or none (#2536). */
    @Override
    public FirstRunQuestion firstRunQuestion() {
        return PiSetup.providerQuestion("opencode");
    }

    /**
     * A Claude account must be complete, and an {@code openai} or {@code gemini} one must hold an
     * API key rather than a ChatGPT or Google sign-in ({@link ApiKeyAccounts}). A key missing
     * altogether is found by the check of every tool's {@code secret: true} entries, for the
     * account the instance uses.
     */
    @Override
    public String credentialProblem(SpawnConfig config, Map<String, String> resolvedParams,
                                    Map<String, String> selection) {
        var credentials = credentials(resolvedParams);
        if (credentials.contains(Credential.ANTHROPIC)) {
            var claude = claudeProblem(config, resolvedParams, selection);
            if (!claude.isEmpty()) return claude;
        }
        // Each listed provider's account, not only the default's: a sign-in gives none of them a key
        if (credentials.contains(Credential.OPENAI)) {
            var openai = ApiKeyAccounts.openaiKeyProblem(config, selection);
            if (!openai.isEmpty()) return openai;
        }
        return credentials.contains(Credential.GEMINI) ? ApiKeyAccounts.geminiKeyProblem(config, selection) : "";
    }

    /** A Claude account's problem, unless the parent instance answers for it. */
    private String claudeProblem(SpawnConfig config, Map<String, String> resolvedParams, Map<String, String> selection) {
        // An inherited Claude account is the parent instance's, whatever this config holds. The
        // credential check skips a tool all of whose accounts are inherited, not one of several,
        // so only a template with several is looked up here: the branch path reads the config once.
        if (credentialNamespaces(resolvedParams).size() > 1 && dev.incusspawn.config.AccountResolver.inherits(config.tree(), SpawnConfig.ClaudeConfig.NAMESPACE,
                selection.get(SpawnConfig.ClaudeConfig.NAMESPACE))) {
            return "";
        }
        return ClaudeSetup.missingAccount(config, selection);
    }

    /**
     * An {@code openai} or {@code gemini} sign-in in use is fixed by a default, not another account:
     * the remedy of the problem {@link #credentialProblem} reports, so none while Claude's comes first.
     */
    @Override
    public Remedy credentialRemedy(SpawnConfig config, Map<String, String> resolvedParams,
                                   Map<String, String> selection) {
        var credentials = credentials(resolvedParams);
        if (credentials.contains(Credential.ANTHROPIC) && !claudeProblem(config, resolvedParams, selection).isEmpty()) return null;
        if (credentials.contains(Credential.OPENAI)) {
            var openai = ApiKeyAccounts.openaiKeyRemedy(config, selection);
            if (openai != null) return openai;
        }
        return credentials.contains(Credential.GEMINI) ? ApiKeyAccounts.geminiKeyRemedy(config, selection) : null;
    }

    @Override
    public List<String> credentialNote(Map<String, String> resolvedParams) {
        if (!credentials(resolvedParams).contains(Credential.ANTHROPIC)) return List.of();
        if (!DEFAULT_PROVIDER.equals(provider(resolvedParams))) {
            return PiSetup.listedProviderNote("opencode", "a Claude account", DEFAULT_PROVIDER);
        }
        return List.of("opencode needs a Claude account for its provider '" + DEFAULT_PROVIDER + "'.",
                "To use another provider, set it in the template's tools, run 'isx build' on the template"
                        + " and branch from it:"
                        + " '- opencode: {provider: openai}' spends an OpenAI API key,"
                        + " or add a custom tool with a proxy rule for another gateway.");
    }

    /** The isx-managed credential an opencode provider spends. */
    private enum Credential { ANTHROPIC, OPENAI, GEMINI, NONE }

    /** The provider of each credential the template gives opencode, the default's first, each once. */
    private static Map<Credential, String> providersByCredential(Map<String, String> resolvedParams) {
        var byCredential = new LinkedHashMap<Credential, String>();
        for (var provider : PiSetup.allProviders(provider(resolvedParams), resolvedParams.get(PiSetup.PROVIDERS))) {
            byCredential.putIfAbsent(credentialFor(provider), provider);
        }
        return byCredential;
    }

    private static Set<Credential> credentials(Map<String, String> resolvedParams) {
        return providersByCredential(resolvedParams).keySet();
    }

    private static Credential credentialFor(String provider) {
        return switch (provider) {
            case "anthropic" -> Credential.ANTHROPIC;
            case "openai" -> Credential.OPENAI;
            case "google" -> Credential.GEMINI;
            default -> Credential.NONE;
        };
    }

    private static String provider(Map<String, String> resolvedParams) {
        var provider = resolvedParams.get("provider");
        return provider != null ? provider : DEFAULT_PROVIDER;
    }

    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams) {
        // One placeholder per provider the template gives opencode, the default's first (#2674)
        var entries = new java.util.ArrayList<EnvEntry>();
        for (var credential : credentials(resolvedParams)) {
            switch (credential) {
                case ANTHROPIC -> entries.add(EnvEntry.set(ANTHROPIC_KEY.env(), ANTHROPIC_KEY.staticValuePrefix()));
                case OPENAI -> entries.add(EnvEntry.set("OPENAI_API_KEY", "sk-placeholder"));
                case GEMINI -> entries.add(EnvEntry.set("GEMINI_API_KEY", GeminiSetup.PLACEHOLDER_API_KEY));
                case NONE -> { }
            }
        }
        return entries;
    }

    @Override
    public String environmentContextPath() { return CONTEXT_PATH; }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Installing opencode");
        NpmGlobalInstall.install(c, "opencode", NPM_PACKAGE);
        linkBinary(c);
        BuildOutput.stepDone();
        configureSettings(c, resolvedParams);
    }

    @Override
    public void reconfigure(Container c, Map<String, String> resolvedParams) {
        configureSettings(c, resolvedParams);
    }

    /**
     * Runs the package's postinstall, which {@code --ignore-scripts} skipped: until it does,
     * {@code opencode} is a stub that only prints how to run it. It puts the platform package's
     * binary in the stub's place, installing that package itself when npm dropped it, and fails
     * when it cannot. It checks the binary by running it, which writes opencode's directories
     * into its {@code HOME} and a {@code TMPDIR}, so both are a scratch directory: root's own
     * {@code /tmp/opencode} would be one agentuser cannot write.
     */
    static void linkBinary(Container c) {
        var result = c.sh(LINK_SCRIPT);
        if (!result.success()) {
            c.out().stepBreak();
            throw Container.failed(c.out(), "The postinstall printed:", "Failed to install opencode's binary"
                    + " (exit code " + result.exitCode() + ")", result);
        }
        if (!c.shAsUser("agentuser", "command -v opencode").success()) {
            c.out().stepBreak();
            throw new IncusException("opencode was installed, but 'opencode' is not on agentuser's PATH");
        }
    }

    static final String LINK_SCRIPT = """
            root=$(npm root -g) || exit 1
            scratch=$(mktemp -d) || exit 1
            cd "$root/%s" && HOME="$scratch" TMPDIR="$scratch" node postinstall.mjs
            rc=$?
            rm -rf "$scratch"
            exit $rc""".formatted(NPM_PACKAGE);

    private void configureSettings(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Configuring opencode");
        c.writeFile(CONFIG_PATH, configJson(resolvedParams));
        // Only opencode's own directory, and ~/.config itself, which writeFile may have created:
        // the rest of ~/.config belongs to other tools and host resources
        c.sh("chown agentuser:agentuser /home/agentuser/.config && chown -R agentuser:agentuser /home/agentuser/.config/opencode")
                .assertSuccess("Failed to give agentuser opencode's config directory");
        BuildOutput.stepDone();
    }

    /**
     * opencode's global config, in the forms 1.x reads ({@code permission}, {@code autoupdate}),
     * which reject the newer ones. Its permissions allow what opencode asks about by default:
     * {@code .env} files, directories outside the project and its loop guard. Everything else it
     * already allows, and its plan agent keeps the rule that denies it edits.
     */
    static String configJson(Map<String, String> resolvedParams) {
        var provider = provider(resolvedParams);
        var model = resolvedParams.get("model");
        if (model == null && DEFAULT_PROVIDER.equals(provider)) model = DEFAULT_MODEL;
        // Unpinned, opencode picks an older Claude model than the one isx sets for Pi and Claude Code
        var modelLine = model == null ? "" : "  \"model\": \"" + provider + "/" + model + "\",\n";
        var small = resolvedParams.get("small-model");
        var smallLine = small == null ? "" : "  \"small_model\": \"" + provider + "/" + small + "\",\n";
        var agentLine = agentLine(provider, model, resolvedParams.get("effort"));
        // As of opencode 1.19.6 (installed unpinned), its google provider reads GOOGLE_GENERATIVE_AI_API_KEY
        // alone, never GEMINI_API_KEY, so the setting names the variable the start's proof replaces. A login-expanded
        // copy would not do: isx-env.sh runs before the proofs, so it would copy the static placeholder.
        // One entry per provider the template gives opencode that needs its variable named; OpenAI's it reads itself.
        var keys = new java.util.ArrayList<String>();
        providersByCredential(resolvedParams).forEach((credential, name) -> {
            var keyEnv = switch (credential) {
                case ANTHROPIC -> ANTHROPIC_KEY.env();
                case GEMINI -> "GEMINI_API_KEY";
                case OPENAI, NONE -> "";
            };
            if (!keyEnv.isEmpty()) keys.add("\"" + name + "\": {\"options\": {\"apiKey\": \"{env:" + keyEnv + "}\"}}");
        });
        var providerLine = keys.isEmpty() ? "" : "  \"provider\": {" + String.join(", ", keys) + "},\n";
        return """
                {
                  "$schema": "https://opencode.ai/config.json",
                %s%s%s%s  "autoupdate": false,
                  "share": "disabled",
                  "instructions": ["%s"],
                  "permission": {"read": "allow", "external_directory": "allow", "doom_loop": "allow"}
                }
                """.formatted(modelLine, smallLine, agentLine, providerLine, CONTEXT_PATH);
    }

    /**
     * The effort as the default variant of opencode's two primary agents. opencode applies an
     * agent's variant only while that agent runs its own model, so each names the model too: a
     * model picked in a session starts at that model's default, as Claude Code's per-model
     * effort does. The root {@code model} keeps no variant.
     */
    private static String agentLine(String provider, String model, String effort) {
        if (effort == null) return "";
        if (model == null) {
            throw new IllegalStateException("The opencode tool's effort parameter needs a model, because opencode"
                    + " applies it to one model: set model too");
        }
        var agent = "{\"model\": \"" + provider + "/" + model + "\", \"variant\": \"" + effort + "\"}";
        return "  \"agent\": {\"build\": " + agent + ", \"plan\": " + agent + "},\n";
    }
}
