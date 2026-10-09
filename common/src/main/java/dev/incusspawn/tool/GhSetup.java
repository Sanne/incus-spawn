package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.util.BuildOutput;

import java.util.List;
import java.util.Map;

import static dev.incusspawn.incus.Container.shellQuote;

public class GhSetup implements ToolSetup {

    /** Config namespace, matching the {@code config-namespace} declared in {@link #proxy()}. */
    public static final String NAMESPACE = "github";

    private static final String PLACEHOLDER_TOKEN = "gho_placeholder";
    private static final long[] DEFAULT_RETRY_DELAYS_MS = {500, 500, 500, 500};
    long[] retryDelaysMs = DEFAULT_RETRY_DELAYS_MS;

    @Override
    public String name() {
        return "gh";
    }

    @Override
    public String description() {
        return "GitHub — PAT for git operations";
    }

    @Override
    public ToolDef.ProxyDef proxy() {
        var token = new ToolDef.ConfigEntry();
        token.setConfigPath("token");
        token.setDescription("GitHub personal access token");
        token.setSecret(true);

        var basicAuth = new ToolDef.AuthDef();
        basicAuth.setDomains(List.of("github.com"));
        basicAuth.setType("basic");
        basicAuth.setUsername("x-access-token");
        basicAuth.setPassword("${token}");

        var bearerAuth = new ToolDef.AuthDef();
        bearerAuth.setDomains(List.of("*.github.com", "*.githubusercontent.com"));
        bearerAuth.setType("bearer");
        bearerAuth.setToken("${token}");

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace(NAMESPACE);
        proxy.setConfiguration(Map.of("token", token));
        proxy.setAuth(List.of(basicAuth, bearerAuth));
        return proxy;
    }

    @Override
    public List<String> packages() {
        return List.of("gh");
    }

    /**
     * The commit email belongs to an identity as much as the token does, so it moves into the
     * account with it. It is not a proxy entry -- nothing injects it -- so it has to be named.
     */
    @Override
    public dev.incusspawn.config.AccountShape accountShape() {
        return dev.incusspawn.config.AccountShape.declared(List.of("token"), List.of("token", "email"));
    }

    /** The commit email, when the account names one: which identity it is, without the token. */
    @Override
    public String describeAccount(SpawnConfig config, String accountName) {
        var email = AccountResolver.value(config, NAMESPACE, accountName, "email");
        return email == null || email.isBlank() ? "" : "commits as " + email;
    }

    /**
     * The account name plus a fingerprint of what the identity is derived from:
     * {@code user.name} and {@code user.email} come from whoever the token belongs to (and the
     * configured email), so any change of account is a change of identity -- unlike Claude,
     * where two accounts of the same auth mode leave the image identical.
     *
     * <p>The name alone is not enough: replacing an account's token with another user's keeps
     * the name, and the instance would go on committing as the previous user while pushing as
     * the new one (#281). The fingerprint is a truncated hash, never the token.
     */
    @Override
    public String bakedAccountIdentity(SpawnConfig config, String accountName) {
        var account = AccountResolver.effectiveAccount(config, NAMESPACE, accountName);
        if (account.isEmpty()) return "";
        var token = AccountResolver.value(config, NAMESPACE, account, "token");
        // Nothing to derive an identity from, so nothing is baked: an account with no token --
        // a flat config holding only an email presents as one -- leaves a build without a
        // [user], and claiming its name here would make every use try, and fail, to re-derive.
        if (token.isBlank()) return "";
        var email = AccountResolver.value(config, NAMESPACE, account, "email");
        return account + IDENTITY_FINGERPRINT_SEPARATOR + fingerprint(token + "\0" + email);
    }

    private static final String IDENTITY_FINGERPRINT_SEPARATOR = "#";
    private static final int IDENTITY_FINGERPRINT_LENGTH = 12;

    /** The fingerprint carries over: the credential it describes is the same one, renamed. */
    @Override
    public String renameBakedIdentity(String baked, String from, String to) {
        if (baked.equals(dev.incusspawn.incus.Metadata.ACCOUNT_IDENTITY_NONE)) return null;
        if (baked.equals(from)) return to;
        var rest = baked.startsWith(from) ? baked.substring(from.length()) : "";
        return rest.matches(IDENTITY_FINGERPRINT_SEPARATOR + "[0-9a-f]{" + IDENTITY_FINGERPRINT_LENGTH + "}")
                ? to + rest : null;
    }

    private static String fingerprint(String material) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, IDENTITY_FINGERPRINT_LENGTH);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean canRebakeForAccount() { return true; }

    /**
     * Re-derive the git identity for a different account.
     *
     * <p>Clearing first is the whole mechanism: {@link #configureGitIdentity} skips when an
     * identity is already present, so without this the instance would keep committing as the
     * account it was branched from while pushing with the new one's token. The lookup itself
     * needs no account argument beyond the config read -- {@code gh api user} goes through the
     * proxy, which already knows which account this instance uses.
     */
    @Override
    public void rebakeForAccount(Container container, String accountName) {
        // Every build stamps an identity for every namespace, so instances without gh carry
        // one too; there, no git identity was derived and there is nothing to bring in line.
        if (!container.sh("command -v gh").success()) return;
        var config = SpawnConfig.load();
        // Resolve before overwriting. Clearing first and then failing -- no network, a revoked
        // token, gh missing -- would leave the instance with no author at all, which is worse
        // than the stale one it had: every commit made before the next successful attempt would
        // be unattributed rather than merely attributed to the previous account.
        var identity = resolveIdentity(container, config, accountName, true);
        gitConfig(container, "user.name", identity.name());
        gitConfig(container, "user.email", identity.email());
    }

    @Override
    public List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        return List.of(EnvEntry.set("GH_TOKEN", PLACEHOLDER_TOKEN));
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        install(c, resolvedParams, java.util.Map.of());
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams,
                        java.util.Map<String, String> accountSelection) {
        BuildOutput.stepStart("Installing GitHub CLI...");
        // Loaded once and threaded down: every lookup below used to re-read and re-serialize
        // config.yaml, so a single install parsed it three times over.
        var config = SpawnConfig.load();
        configureGit(c, config,
                AccountResolver.effectiveAccount(config, NAMESPACE, accountSelection.get(NAMESPACE)));
        BuildOutput.stepDone();
    }

    /**
     * A template built before a GitHub token was configured used to get no {@code [user]},
     * silently, and every branch committed without an author. The build now says so, and stamps
     * {@link dev.incusspawn.incus.Metadata#ACCOUNT_IDENTITY_NONE}, which is what makes the last
     * sentence true.
     */
    @Override
    public String unbakedIdentityWarning() {
        return NO_IDENTITY_WARNING;
    }

    static final String NO_IDENTITY_WARNING = "No git identity: no GitHub token is configured,"
            + " so commits made in this template and its branches have no author. Add a token with"
            + " 'isx init'; branches pick the identity up the next time isx branches, opens a shell"
            + " in or runs a command in them.";

    private void configureGit(Container c, SpawnConfig config, String accountName) {
        boolean existingConfig = c.sh("test -f /home/agentuser/.gitconfig").success();
        configureGitIdentity(c, config, accountName);
        if (!existingConfig) {
            configureGitDefaults(c);
        }
    }

    /**
     * Resolve the git identity from whichever GitHub account applies and write it into
     * {@code .gitconfig}.
     *
     * <p>The identity is derived by asking the API who the token belongs to, through the proxy
     * -- so it follows the account the caller is pinned to without this code knowing which one
     * that is. Skipped when an identity is already present, which is what makes it cheap to
     * call again at branch time; {@link #rebakeForAccount} is how a re-point forces a refresh.
     * Writing nothing -- no token to derive from -- is what the build's
     * {@link #lacksBakedIdentity} check then finds, warns about and stamps.
     */
    void configureGitIdentity(Container c, SpawnConfig config, String accountName) {
        boolean hasName = gitConfigGet(c, "user.name");
        boolean hasEmail = gitConfigGet(c, "user.email");
        if (hasName && hasEmail) {
            return;
        }

        var identity = resolveIdentity(c, config, accountName, false);
        if (identity == null) return;
        if (!hasName) gitConfig(c, "user.name", identity.name());
        if (!hasEmail) gitConfig(c, "user.email", identity.email());
    }

    /**
     * Whether gh is in the image and {@code .gitconfig} has no {@code user.name} or no
     * {@code user.email}: one guest exec, which is what the build's stamp and an older template's
     * first reconcile are decided by, rather than by what config.yaml held at the time.
     */
    @Override
    public boolean lacksBakedIdentity(Container container) {
        return !container.shAsUser("agentuser", "! command -v gh >/dev/null"
                + " || { git config --global --get user.name && git config --global --get user.email; } >/dev/null")
                .success();
    }

    /** The git identity behind a GitHub account, as the API reports it. */
    record GitIdentity(String name, String email) {}

    /**
     * Ask the API who this account's token belongs to.
     *
     * <p>The request carries only the placeholder token: it goes through the MITM proxy, which
     * substitutes the real one for whichever account the caller is pinned to. That is why
     * neither this method nor its callers need the credential itself.
     *
     * @param required when true, a failure throws rather than returning null -- a build must not
     *     produce a template with no identity, and a re-point must not overwrite a good identity
     *     with nothing
     */
    private GitIdentity resolveIdentity(Container c, SpawnConfig config,
                                        String accountName, boolean required) {
        var command = "GH_TOKEN=" + PLACEHOLDER_TOKEN
                + " gh api user --jq '[.login, .name, .email] | @tsv'";
        var tokenConfigured = !AccountResolver.value(config, NAMESPACE, accountName, "token").isBlank();
        var result = c.sh(command);
        if (tokenConfigured) {
            for (int attempt = 0; attempt < retryDelaysMs.length
                    && (!result.success() || result.stdout().isBlank()); attempt++) {
                try {
                    Thread.sleep(retryDelaysMs[attempt]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IncusException("Interrupted while determining git identity from GitHub", e);
                }
                result = c.sh(command);
            }
        }
        if (!result.success() || result.stdout().isBlank()) {
            var detail = result.stderr().isBlank() ? "" : " (" + result.stderr().strip() + ")";
            if (required || tokenConfigured) {
                throw new IncusException("Could not determine git identity from GitHub" + detail);
            }
            return null;
        }

        var parts = result.stdout().lines().findFirst().orElse("").split("\t", -1);
        if (parts[0].isEmpty()) {
            // With a token configured this is a failed lookup like any other, and fails the build:
            // returning null would leave the template without an author and claiming nothing.
            if (required || tokenConfigured) throw new IncusException("GitHub reported no login for this account");
            return null;
        }

        var login = parts[0];
        var name = parts.length >= 2 && !parts[1].isEmpty() ? parts[1] : login;

        var configEmail = AccountResolver.value(config, NAMESPACE, accountName, "email");
        var email = configEmail.isBlank() ? null : configEmail;
        boolean publicEmailHidden = parts.length < 3 || parts[2].isEmpty();
        if (email == null) {
            email = findEmailFromApi(c, publicEmailHidden);
        }
        if (email == null && !publicEmailHidden) {
            email = parts[2];
        }
        if (email == null) {
            email = login + "@users.noreply.github.com";
        }
        return new GitIdentity(name, email);
    }

    private static final String JQ_NOREPLY = "([.[] | select(.verified and (.email | endswith(\"@users.noreply.github.com\"))) | .email] | first)";
    private static final String JQ_PRIMARY = "([.[] | select(.primary and .verified) | .email] | first)";
    private static final String JQ_ANY_VERIFIED = "([.[] | select(.verified) | .email] | first)";

    private String findEmailFromApi(Container c, boolean preferNoreply) {
        String jq = preferNoreply
                ? JQ_NOREPLY + " // " + JQ_PRIMARY + " // " + JQ_ANY_VERIFIED
                : JQ_PRIMARY + " // " + JQ_ANY_VERIFIED;
        var result = c.sh("GH_TOKEN=" + PLACEHOLDER_TOKEN
                + " gh api user/emails --jq '" + jq + "'");
        if (!result.success() || result.stdout().isBlank() || result.stdout().strip().equals("null")) {
            return null;
        }
        return result.stdout().strip();
    }

    private void configureGitDefaults(Container c) {
        gitConfig(c, "push.default", "current");
        gitConfig(c, "pull.ff", "only");
        gitConfig(c, "init.defaultBranch", "main");

        gitConfig(c, "alias.st", "status");
        gitConfig(c, "alias.co", "checkout");
        gitConfig(c, "alias.br", "branch --sort=committerdate");
        gitConfig(c, "alias.l", "log --pretty=oneline --decorate --abbrev-commit");
        gitConfig(c, "alias.uncommit", "reset --soft HEAD^");
        gitConfig(c, "alias.fix", "commit --amend --no-edit");
    }

    private boolean gitConfigGet(Container c, String key) {
        return c.shAsUser("agentuser", "git config --global --get " + shellQuote(key)).success();
    }

    private void gitConfig(Container c, String key, String value) {
        c.shAsUser("agentuser", "git config --global " + shellQuote(key) + " " + shellQuote(value))
                .assertSuccess("Failed to set git config " + key);
    }
}
