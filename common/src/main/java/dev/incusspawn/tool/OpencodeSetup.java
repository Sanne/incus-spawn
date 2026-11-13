package dev.incusspawn.tool;

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

    static final String DEFAULT_PROVIDER = "anthropic";
    static final String DEFAULT_MODEL = "claude-sonnet-5-5";

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

        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Model ID (e.g. claude-sonnet-5-5, gpt-4.1, gemini-3.7-flash); omitted, "
                + DEFAULT_MODEL + " for " + DEFAULT_PROVIDER + " and opencode's own default otherwise");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        // No declared default, as with Pi: one would be resolved before the build, and every
        // provider would get a Claude model.
        params.put("model", model);

        var small = new ToolDef.ParameterDef();
        small.setType("string");
        small.setDescription("Model ID for opencode's small tasks such as session titles; omitted, opencode's own choice, "
                + "which setting model does not change");
        small.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        small.setOptional(true);
        small.setReconfigurable(true);
        params.put("small-model", small);
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

    /** {@code OPENAI_API_KEY} and {@code GEMINI_API_KEY} are Codex's and Gemini CLI's; this one is opencode's own. */
    @Override
    public List<ProofToken.Placeholder> placeholders() {
        return List.of(ANTHROPIC_KEY);
    }

    @Override
    public Set<String> credentialNamespaces(Map<String, String> resolvedParams) {
        return switch (credentialFor(resolvedParams)) {
            case ANTHROPIC -> Set.of(SpawnConfig.ClaudeConfig.NAMESPACE);
            case OPENAI -> Set.of("openai");
            case GEMINI -> Set.of(PiSetup.GEMINI_NAMESPACE);
            case NONE -> Set.of();
        };
    }

    @Override
    public String credentialProblem(SpawnConfig config, Map<String, String> resolvedParams,
                                    Map<String, String> selection) {
        return credentialFor(resolvedParams) == Credential.ANTHROPIC ? ClaudeSetup.missingAccount(config, selection) : "";
    }

    @Override
    public List<String> credentialNote(Map<String, String> resolvedParams) {
        if (credentialFor(resolvedParams) != Credential.ANTHROPIC) return List.of();
        return List.of("opencode needs a Claude account for its provider '" + provider(resolvedParams) + "'.",
                "To use another provider, set it in the template's tools, run 'isx build' on the template"
                        + " and branch from it:"
                        + " '- opencode: {provider: openai}' spends an OpenAI API key,"
                        + " and a provider isx does not manage needs nothing from isx.");
    }

    /** The isx-managed credential an opencode provider spends. */
    private enum Credential { ANTHROPIC, OPENAI, GEMINI, NONE }

    private static Credential credentialFor(Map<String, String> resolvedParams) {
        return switch (provider(resolvedParams)) {
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
        return switch (credentialFor(resolvedParams)) {
            case ANTHROPIC -> List.of(EnvEntry.set(ANTHROPIC_KEY.env(), ANTHROPIC_KEY.staticValuePrefix()));
            case OPENAI -> List.of(EnvEntry.set("OPENAI_API_KEY", "sk-placeholder"));
            case GEMINI -> List.of(EnvEntry.set("GEMINI_API_KEY", GeminiSetup.PLACEHOLDER_API_KEY));
            case NONE -> List.of();
        };
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
        var providerLine = credentialFor(resolvedParams) != Credential.ANTHROPIC ? ""
                : "  \"provider\": {\"anthropic\": {\"options\": {\"apiKey\": \"{env:" + ANTHROPIC_KEY.env() + "}\"}}},\n";
        return """
                {
                  "$schema": "https://opencode.ai/config.json",
                %s%s%s  "autoupdate": false,
                  "share": "disabled",
                  "instructions": ["%s"],
                  "permission": {"read": "allow", "external_directory": "allow", "doom_loop": "allow"}
                }
                """.formatted(modelLine, smallLine, providerLine, CONTEXT_PATH);
    }
}
