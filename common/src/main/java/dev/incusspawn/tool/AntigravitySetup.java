package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Antigravity CLI ({@code agy}), Google's terminal coding agent, in its Gemini API key mode (#2366):
 * the only one that keeps the credential on the host. It spends the {@code gemini} namespace through
 * Gemini CLI's proxy entry, as Pi and opencode do: {@code agy} sends {@code GEMINI_API_KEY} as
 * {@code X-Goog-Api-Key} to {@code generativelanguage.googleapis.com}, where the proxy swaps the
 * proof for the account's key. Its Google sign-in keeps a token in the guest's keyring, and its
 * ADC mode reads credentials from the guest, so neither is configured.
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
    static final String SETTINGS_PATH = CLI_DIR + "/settings.json";
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

    @Override
    public String name() { return "antigravity"; }

    @Override
    public String description() { return "Antigravity CLI (agy) " + VERSION + " — Google coding agent"; }

    @Override
    public boolean codingAgent() { return true; }

    @Override
    public McpClient mcpClient() { return McpClients.ANTIGRAVITY; }

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
        return List.of(EnvEntry.set("GEMINI_API_KEY", GeminiSetup.PLACEHOLDER_API_KEY),
                EnvEntry.set(DISABLE_AUTO_UPDATE, "true"));
    }

    /** Node is for {@code isx mcp}'s delegation, whose guest runner ({@code antigravity-runner.cjs}) is a Node script; agy needs none. */
    @Override
    public List<String> packages() { return List.of("tar", "gzip", "nodejs"); }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var action = new ToolDef.ActionEntry();
        action.setLabel("Antigravity CLI");
        action.setType("shell");
        action.setCommand("bash -lc agy");
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
        configure(c);
    }

    @Override
    public void reconfigure(Container c, Map<String, String> resolvedParams) {
        configure(c);
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
     * isx's rule made agentuser's, since the build writes the rule as root.
     */
    private static void configure(Container c) {
        BuildOutput.stepStart("Configuring Antigravity CLI");
        var read = c.sh("if [ -f " + SETTINGS_PATH + " ]; then cat " + SETTINGS_PATH + "; fi")
                .assertSuccess("Failed to read Antigravity CLI's settings");
        c.shAsUser("agentuser", "mkdir -p " + CLI_DIR + "/cache " + RULES_DIR)
                .assertSuccess("Failed to create Antigravity CLI's directories");
        c.writeFile(SETTINGS_PATH, settingsJson(read.stdout()));
        c.chown(SETTINGS_PATH, "agentuser:agentuser");
        c.sh("[ -s " + ONBOARDING_PATH + " ] || { " + Container.heredoc("cat > " + ONBOARDING_PATH, ONBOARDING.strip())
                        + "\nchown agentuser:agentuser " + ONBOARDING_PATH + "; }")
                .assertSuccess("Failed to write Antigravity CLI's onboarding state");
        BuildOutput.stepDone();
    }

    /**
     * {@code existing} (empty for none) with isx's settings put in, the user's others kept:
     * <ul>
     *   <li>{@code modelProvider: gemini}, the API key mode, which reads {@code GEMINI_API_KEY};
     *   <li>every tool call and artifact proceeding without a question, and files outside the
     *       workspace reachable, since the instance is the boundary (#1857);
     *   <li>no terminal sandbox, which would want an OS container inside the container;
     *   <li>the home trusted, so starting there does not ask (it trusts each folder by its exact path);
     *   <li>no tips and no surveys.
     * </ul>
     */
    static String settingsJson(String existing) {
        JqObject root = JqObject.EMPTY;
        if (!existing.isBlank()) {
            if (!(Jjq.parseForeign(existing, SETTINGS_PATH) instanceof JqObject object)) {
                throw new IllegalStateException(SETTINGS_PATH + " must be a JSON object");
            }
            root = object;
        }
        var trusted = new LinkedHashSet<String>();
        if (root.get("trustedWorkspaces") instanceof JqArray array) {
            for (int i = 0; i < array.size(); i++) {
                var v = array.getElement(i);
                if (v.isString()) trusted.add(v.asText());
            }
        }
        trusted.add(HOME);
        var trustedArray = JqArray.arrayBuilder();
        trusted.forEach(trustedArray::add);
        root = root.with("modelProvider", JqString.of("gemini"))
                .with("toolPermission", JqString.of("always-proceed"))
                .with("artifactReviewPolicy", JqString.of("always-proceed"))
                .with("allowNonWorkspaceAccess", JqBoolean.TRUE)
                .with("enableTerminalSandbox", JqBoolean.FALSE)
                .with("trustedWorkspaces", trustedArray.build())
                .with("showTips", JqBoolean.FALSE)
                .with("showFeedbackSurvey", JqBoolean.FALSE);
        return JqValues.toPrettyJsonString(root) + "\n";
    }
}
