package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.ModelIds;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.proxy.GoogleAccounts;
import dev.incusspawn.proxy.GooglePlaceholders;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Antigravity CLI ({@code agy}), Google's terminal coding agent, on the {@code gemini} namespace in
 * one of two modes, decided by the account the build resolves (#2366, #2443):
 * <ul>
 *   <li><b>API key</b> ({@code modelProvider: gemini}): {@code agy} sends {@code GEMINI_API_KEY} as
 *       {@code X-Goog-Api-Key} to {@code generativelanguage.googleapis.com}, where the proxy swaps
 *       the proof for the account's key, through Gemini CLI's proxy entry as Pi and opencode do.</li>
 *   <li><b>Google sign-in</b> (an account of {@code type: google}): {@code agy} signs in as it does
 *       anywhere, and the proxy keeps what Google issued on the host ({@code GoogleAuthRoute}). The
 *       guest's token file holds look-alikes carrying the instance's {@code gemini} proof, which the
 *       login script {@link #LOGIN_SCRIPT_PATH} seeds and keeps current from {@code GEMINI_API_KEY}.
 *       Without a keyring, which no instance has, {@code agy} keeps its token in that file.</li>
 * </ul>
 * Its ADC mode reads credentials from the guest and is not configured.
 *
 * <p>The binary is pinned: Google's installer reads the newest release from a manifest and checks
 * its sha512, and the sha256 below was taken from the tarballs that manifest named, after that
 * check. Its self-updater is turned off, so the pin is what runs. The upstream watch's
 * {@code antigravity} item moves the pin and reads these lines as they are written. A change to
 * their shape takes three PRs: {@code upstream/engine/source-antigravity.sh} first, reading both
 * shapes, since {@code upstream-verify} reads a PR with main's engine and {@code UpstreamManifestTest}
 * validates the engine PR's own tree; then this file; then the engine drops the old shape
 * (design/upstream.md).
 */
public class AntigravitySetup implements ToolSetup {

    static final String VERSION = "1.5.13";
    private static final String BUILD = "1.5.13-5999302598852608";
    private static final String DOWNLOAD_BASE = "https://storage.googleapis.com/antigravity-public/antigravity-cli/" + BUILD;
    static final Download X86_64 = new Download(DOWNLOAD_BASE + "/linux-x64/cli_linux_x64.tar.gz",
            "7fa9cc6df0d09562693d637c406a86c1b0f52ab3364714b1fd2d79cc6060d4b2");
    static final Download AARCH64 = new Download(DOWNLOAD_BASE + "/linux-arm/cli_linux_arm64.tar.gz",
            "36596ad8038531fd75f0e97b5ba8967a30f2fd67f8d9c61959d2e4761d7b215f");

    /** One architecture's tarball, which holds the single binary {@code antigravity}. */
    record Download(String url, String sha256) {}

    static final String INSTALL_DIR = "/opt/antigravity-cli";
    static final String COMMAND = "/usr/local/bin/agy";

    static final String HOME = "/home/agentuser";
    static final String CLI_DIR = HOME + "/.gemini/antigravity-cli";
    public static final String SETTINGS_PATH = CLI_DIR + "/settings.json";
    /** What makes the first interactive start skip the color scheme and sign-in questions. */
    static final String ONBOARDING_PATH = CLI_DIR + "/cache/onboarding.json";
    static final String ONBOARDING = """
            {"consumerOnboardingComplete": true, "enterpriseOnboardingComplete": true, "onboardingComplete": true}
            """;
    /** Its global MCP servers, apart from Gemini CLI's {@code settings.json}. */
    public static final String MCP_CONFIG_PATH = HOME + "/.gemini/config/mcp_config.json";
    /**
     * A global rule of isx's own: {@code agy} adds every file in this directory to what it reads,
     * so the user's {@code ~/.gemini/GEMINI.md} and {@code AGENTS.md} stay theirs. A rule there
     * is loaded only with front matter that says when it applies.
     */
    static final String RULES_DIR = HOME + "/.gemini/config/rules";
    public static final String CONTEXT_PATH = RULES_DIR + "/isx.md";
    static final String RULE_FRONT_MATTER = "---\ntrigger: always_on\n---\n";

    /** What tells it not to update itself, read at every start. */
    static final String DISABLE_AUTO_UPDATE = "AGY_CLI_DISABLE_AUTO_UPDATE";

    /** Where {@code agy} keeps its Google sign-in when it has no keyring, as no instance has. */
    public static final String TOKEN_PATH = CLI_DIR + "/antigravity-oauth-token";
    /**
     * The login script of the Google sign-in mode (#2443, the #1108 pattern): {@code agy} reads its
     * sign-in from a file, so each login derives that file from {@code GEMINI_API_KEY}'s proof. A
     * missing file is seeded with the proof's look-alikes and an expiry in the past, so agy's first
     * request is a refresh the proxy answers from the host's session, or refuses when the account
     * has not signed in, which makes agy ask for the sign-in. Seeded only where agy is configured
     * (its directory exists: the build makes agentuser's, and agy's {@code /logout} removes the
     * file alone), so root's login shell leaves no stray file. A file holding isx look-alikes of
     * another digest (an earlier start, a copy, a reset, or the digest the proxy minted for the run
     * that signed in) is brought to this login's. A file holding a real token is left alone.
     */
    public static final String LOGIN_SCRIPT_PATH = "/etc/profile.d/isx-zz-antigravity.sh";
    static final String LOGIN_SCRIPT = """
            # isx: Antigravity CLI's Google sign-in stays on the host (#2443). Its token file holds
            # look-alikes carrying this instance's gemini proof; keep them current with GEMINI_API_KEY.
            case "$GEMINI_API_KEY" in
              gemini-isx_*)
                isx_agy_digest=${GEMINI_API_KEY#gemini-isx_}
                isx_agy_token="$HOME/.gemini/antigravity-cli/antigravity-oauth-token"
                case "$isx_agy_digest" in
                  *[!0-9a-f]*|'') ;;
                  *)
                    if [ ! -e "$isx_agy_token" ] && [ -d "${isx_agy_token%/*}" ]; then
                      (umask 077 && printf '%s\n' "{\\"token\\":{\\"access_token\\":\\"ya29.isx_$isx_agy_digest\\",\\"token_type\\":\\"Bearer\\",\\"refresh_token\\":\\"1//isx_$isx_agy_digest\\",\\"expiry\\":\\"1970-01-01T00:00:00Z\\",\\"expires_in\\":0},\\"auth_method\\":\\"consumer\\",\\"id_token_email\\":\\"\\"}" > "$isx_agy_token") 2>/dev/null
                    elif grep -q 'isx_' "$isx_agy_token" 2>/dev/null && ! grep -q "isx_$isx_agy_digest" "$isx_agy_token"; then
                      # No sed -i: BSD sed reads the next word as its backup suffix, so the edit is a copy
                      (umask 077 && sed -E "s/isx_[0-9a-f]{@DIGEST_HEX_CHARS@}/isx_$isx_agy_digest/g" "$isx_agy_token" > "$isx_agy_token.isx-new") 2>/dev/null \
                        && mv -f "$isx_agy_token.isx-new" "$isx_agy_token" 2>/dev/null
                    fi
                    ;;
                esac
                unset isx_agy_digest isx_agy_token
                ;;
            esac
            """.replace("@DIGEST_HEX_CHARS@", String.valueOf(GooglePlaceholders.DIGEST_HEX_CHARS));

    @Override
    public String name() { return "antigravity"; }

    @Override
    public String description() { return "Antigravity CLI (agy) " + VERSION + " — Google coding agent"; }

    @Override
    public boolean codingAgent() { return true; }

    @Override
    public McpClient mcpClient() { return McpClients.ANTIGRAVITY; }

    /** The search, query and diff CLIs agy's own recommendations name (#2451). */
    @Override
    public List<String> requires() { return List.of("agent-cli-tools"); }

    @Override
    public String environmentContextPath() { return CONTEXT_PATH; }

    @Override
    public String renderEnvironmentContext(String instructions) {
        return RULE_FRONT_MATTER + instructions.strip() + "\n";
    }

    /** The {@code gemini} namespace of Gemini CLI's proxy entry: it has no credential of its own. */
    @Override
    public Set<String> credentialNamespaces() {
        return Set.of(PiSetup.GEMINI_NAMESPACE);
    }

    /** {@code GEMINI_API_KEY} is Gemini CLI's declaration, which fills it for this tool too. */
    @Override
    public List<dev.incusspawn.proxy.ProofToken.Placeholder> placeholders() {
        return List.of();
    }

    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams) {
        var entries = new java.util.ArrayList<EnvEntry>();
        entries.add(EnvEntry.set("GEMINI_API_KEY", GeminiSetup.PLACEHOLDER_API_KEY));
        entries.add(EnvEntry.set(DISABLE_AUTO_UPDATE, "true"));
        // agy has no setting for the model ID (settings.json holds a display label), so the
        // defaults travel as variables that the action and the delegation runner turn into flags.
        var model = resolvedParams.get("model");
        if (model != null && !model.isBlank()) entries.add(EnvEntry.set(MODEL_ENV, model));
        var effort = resolvedParams.get("effort");
        if (effort != null && !effort.isBlank()) entries.add(EnvEntry.set(EFFORT_ENV, effort));
        return entries;
    }

    /** The template's {@code model} and {@code effort}, read by {@link #actions()} and the delegation runner. */
    public static final String MODEL_ENV = "ISX_AGY_MODEL";
    public static final String EFFORT_ENV = "ISX_AGY_EFFORT";

    @Override
    public Map<String, ToolDef.ParameterDef> parameters() {
        var params = new java.util.LinkedHashMap<String, ToolDef.ParameterDef>();
        // Neither declares a default: a free-tier Gemini key has no quota for agy's own default
        // model, and `agy models` lists the IDs a key can use.
        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Gemini model ID for agy (e.g. " + ModelIds.get("example-gemini")
                + "; `agy models` lists them); omitted to use agy's default, which a free-tier key may not be allowed");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        params.put("model", model);
        var effort = new ToolDef.ParameterDef();
        effort.setType("string");
        effort.setDescription("Reasoning effort (low, medium, high, xhigh, max)");
        effort.setPattern("^(low|medium|high|xhigh|max)$");
        effort.setOptional(true);
        effort.setReconfigurable(true);
        params.put("effort", effort);
        return params;
    }

    /** Node is for {@code isx mcp}'s delegation, whose guest runner ({@code antigravity-runner.cjs}) is a Node script; agy needs none. */
    @Override
    public List<String> packages() { return List.of("tar", "gzip", "nodejs"); }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var action = new ToolDef.ActionEntry();
        action.setLabel("Antigravity CLI");
        action.setType("shell");
        action.setCommand("bash -lc 'agy ${" + MODEL_ENV + ":+--model \"$" + MODEL_ENV + "\"} ${" + EFFORT_ENV
                + ":+--effort \"$" + EFFORT_ENV + "\"}'");
        action.setAutoReturn(true);
        return List.of(action);
    }

    @Override
    public String verifyCommand(Map<String, String> resolvedParams) {
        return "agy --help >/dev/null 2>&1";
    }

    private final DownloadCache downloadCache;

    public AntigravitySetup() {
        this(new DownloadCache());
    }

    AntigravitySetup(DownloadCache downloadCache) {
        this.downloadCache = downloadCache;
    }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        install(c, resolvedParams, Map.of());
    }

    @Override
    public void install(Container c, Map<String, String> resolvedParams, Map<String, String> accountSelection) {
        BuildOutput.stepStart("Installing Antigravity CLI");
        var download = download(c.getArchitecture());
        try {
            var cached = downloadCache.download(download.url(), download.sha256());
            var dir = INSTALL_DIR + "/" + VERSION;
            var tarball = "/tmp/antigravity-cli.tar.gz";
            c.filePush(cached.toString(), tarball);
            c.sh(installScript(dir, tarball)).assertSuccess("Failed to install Antigravity CLI");
        } catch (IOException e) {
            throw new RuntimeException("Failed to install Antigravity CLI: " + e.getMessage(), e);
        }
        BuildOutput.stepDone();
        configure(c, googleSignIn(accountSelection));
    }

    /** A child template's inherited copy, on the namespace's default account. */
    @Override
    public void reconfigure(Container c, Map<String, String> resolvedParams) {
        reconfigure(c, resolvedParams, Map.of());
    }

    /**
     * A child template's inherited copy, on the account the child's own {@code accounts:} select:
     * the mode is what the build stamps as this tool's baked identity, so the guest has to be
     * configured for the same account the stamp names.
     */
    @Override
    public void reconfigure(Container c, Map<String, String> resolvedParams, Map<String, String> accountSelection) {
        configure(c, googleSignIn(accountSelection));
    }

    /** Whether the build's {@code gemini} account is a Google sign-in, so agy runs in that mode. */
    static boolean googleSignIn(Map<String, String> accountSelection) {
        return GeminiSetup.selectsGoogleAccount(SpawnConfig.load(), accountSelection);
    }

    /**
     * The mode, not the account: {@code google} or {@code api-key}. Two API key accounts leave the
     * image the same, and so do two Google ones; a move across modes rewrites {@code settings.json}
     * and the login script, which {@link #rebakeForAccount} does on a built instance, since agy
     * reads both when it starts.
     */
    @Override
    public String bakedAccountIdentity(SpawnConfig config, String accountName) {
        if (accountName == null || accountName.isBlank()) return "";
        try {
            var names = dev.incusspawn.config.AccountResolver.accountNames(config, GeminiSetup.NAMESPACE);
            if (!names.contains(accountName)) return "";
        } catch (RuntimeException e) {
            return "";
        }
        return GoogleAccounts.isGoogleAccount(config.tree(), accountName) ? GoogleAccounts.TYPE : GeminiSetup.TYPE_API_KEY;
    }

    @Override
    public boolean canRebakeForAccount() { return true; }

    @Override
    public void rebakeForAccount(Container container, String accountName) {
        configure(container, GoogleAccounts.isGoogleAccount(SpawnConfig.load().tree(), accountName));
    }

    @Override
    public List<DownloadCache.Pinned> pinnedDownloads(String arch) {
        return switch (arch) {
            case "amd64", "x86_64", "aarch64", "arm64" -> {
                var download = download(arch);
                yield List.of(new DownloadCache.Pinned(download.url(), download.sha256()));
            }
            default -> List.of(); // the install itself says there is no build for it
        };
    }

    static Download download(String arch) {
        return switch (arch) {
            case "amd64", "x86_64" -> X86_64;
            case "aarch64", "arm64" -> AARCH64;
            default -> throw new IllegalStateException("Antigravity CLI has no build for " + arch);
        };
    }

    /** Unpacks the pinned tarball to its version's directory and puts {@code agy} on the PATH. */
    static String installScript(String dir, String tarball) {
        return "set -e\n"
                + "rm -rf " + dir + " && mkdir -p " + dir + "\n"
                + "tar -xzf " + tarball + " -C " + dir + " antigravity\n"
                + "rm -f " + tarball + "\n"
                + "chmod 0755 " + dir + "/antigravity\n"
                + "ln -sfn " + dir + "/antigravity " + COMMAND + "\n";
    }

    /**
     * Its settings, merged into what is there, its onboarding marked done, and the directory of
     * isx's rule made agentuser's, since the build writes the rule as root. In the Google sign-in
     * mode the login script goes in and an API-key token file is left alone; in the API key mode
     * the script goes, and so does a token file holding isx look-alikes, which would show a stale
     * sign-in in agy's header.
     */
    private static void configure(Container c, boolean googleSignIn) {
        BuildOutput.stepStart("Configuring Antigravity CLI" + (googleSignIn ? " for a Google account" : ""));
        var read = c.sh("if [ -f " + SETTINGS_PATH + " ]; then cat " + SETTINGS_PATH + "; fi")
                .assertSuccess("Failed to read Antigravity CLI's settings");
        c.shAsUser("agentuser", "mkdir -p " + CLI_DIR + "/cache " + RULES_DIR)
                .assertSuccess("Failed to create Antigravity CLI's directories");
        c.writeFile(SETTINGS_PATH, settingsJson(read.stdout(), googleSignIn));
        c.chown(SETTINGS_PATH, "agentuser:agentuser");
        if (googleSignIn) {
            c.writeFile(LOGIN_SCRIPT_PATH, LOGIN_SCRIPT);
        } else {
            c.sh("rm -f " + LOGIN_SCRIPT_PATH + "; if grep -q '" + ProofToken.MARKER + "' " + TOKEN_PATH
                            + " 2>/dev/null; then rm -f " + TOKEN_PATH + "; fi")
                    .assertSuccess("Failed to remove Antigravity CLI's sign-in script");
        }
        c.sh("[ -s " + ONBOARDING_PATH + " ] || { " + Container.heredoc("cat > " + ONBOARDING_PATH, ONBOARDING.strip())
                        + "\nchown agentuser:agentuser " + ONBOARDING_PATH + "; }")
                .assertSuccess("Failed to write Antigravity CLI's onboarding state");
        BuildOutput.stepDone();
    }

    /** {@link #settingsJson(String, boolean)} for the API key mode. */
    static String settingsJson(String existing) {
        return settingsJson(existing, false);
    }

    /**
     * {@code existing} (empty for none) with isx's settings put in, the user's others kept:
     * <ul>
     *   <li>{@code modelProvider: gemini}, the API key mode, which reads {@code GEMINI_API_KEY}; or
     *       for a Google account no {@code modelProvider} at all, since agy exits when it names
     *       {@code gemini} with no key, and signs in normally when it is absent;
     *   <li>every tool call and artifact proceeding without a question, and files outside the
     *       workspace reachable, since the instance is the boundary (#1857);
     *   <li>no terminal sandbox, which would want an OS container inside the container;
     *   <li>the home trusted, so starting there does not ask (it trusts each folder by its exact path);
     *   <li>no tips and no surveys.
     * </ul>
     */
    static String settingsJson(String existing, boolean googleSignIn) {
        JqObject root = JqObject.EMPTY;
        if (!existing.isBlank()) {
            if (!(Jjq.parseForeign(existing, SETTINGS_PATH) instanceof JqObject object)) {
                throw new IllegalStateException(SETTINGS_PATH + " must be a JSON object");
            }
            root = object;
        }
        root = withTrusted(root, List.of(HOME));
        root = googleSignIn ? root.without("modelProvider") : root.with("modelProvider", JqString.of("gemini"));
        root = root.with("toolPermission", JqString.of("always-proceed"))
                .with("artifactReviewPolicy", JqString.of("always-proceed"))
                .with("allowNonWorkspaceAccess", JqBoolean.TRUE)
                .with("enableTerminalSandbox", JqBoolean.FALSE)
                .with("showTips", JqBoolean.FALSE)
                .with("showFeedbackSurvey", JqBoolean.FALSE);
        return JqValues.toPrettyJsonString(root) + "\n";
    }

    /**
     * {@code existing}, the settings agy or an earlier build wrote, with {@code folders} added to
     * the folders it trusts, the ones already there kept in their order; null when every folder
     * is already trusted, so a build writes nothing. agy trusts a folder by its exact path and asks
     * about any other, so the build lists each of the template's clones (#2465).
     *
     * @throws IllegalStateException when {@code existing} is not a JSON object
     */
    public static String withTrustedWorkspaces(String existing, Collection<String> folders) {
        if (!(Jjq.parseForeign(existing, SETTINGS_PATH) instanceof JqObject root)) {
            throw new IllegalStateException(SETTINGS_PATH + " must be a JSON object");
        }
        var updated = withTrusted(root, folders);
        return updated == root ? null : JqValues.toPrettyJsonString(updated) + "\n";
    }

    /** {@code root} with {@code folders} in its {@code trustedWorkspaces}; {@code root} itself when they all are. */
    private static JqObject withTrusted(JqObject root, Collection<String> folders) {
        var trusted = new LinkedHashSet<String>();
        if (root.get("trustedWorkspaces") instanceof JqArray array) {
            for (int i = 0; i < array.size(); i++) {
                var v = array.getElement(i);
                if (v.isString()) trusted.add(v.asText());
            }
        }
        if (trusted.containsAll(folders) && root.get("trustedWorkspaces") instanceof JqArray) return root;
        trusted.addAll(folders);
        var trustedArray = JqArray.arrayBuilder();
        trusted.forEach(trustedArray::add);
        return root.with("trustedWorkspaces", trustedArray.build());
    }
}
