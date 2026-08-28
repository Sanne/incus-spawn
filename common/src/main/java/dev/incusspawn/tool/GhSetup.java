package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.util.BuildOutput;

import java.util.List;

import static dev.incusspawn.incus.Container.shellQuote;

public class GhSetup implements ToolSetup {

    private static final String PLACEHOLDER_TOKEN = "gho_placeholder";
    private static final long[] DEFAULT_RETRY_DELAYS_MS = {500, 500, 500, 500};
    long[] retryDelaysMs = DEFAULT_RETRY_DELAYS_MS;

    @Override
    public String name() {
        return "gh";
    }

    @Override
    public List<String> packages() {
        return List.of("gh");
    }

    @Override
    public List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        return List.of(EnvEntry.set("GH_TOKEN", PLACEHOLDER_TOKEN));
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Installing GitHub CLI...");
        configureGit(c);
        BuildOutput.stepDone();
    }

    private void configureGit(Container c) {
        boolean existingConfig = c.sh("test -f /home/agentuser/.gitconfig").success();
        configureGitIdentity(c);
        if (!existingConfig) {
            configureGitDefaults(c);
        }
    }

    private void configureGitIdentity(Container c) {
        boolean hasName = gitConfigGet(c, "user.name");
        boolean hasEmail = gitConfigGet(c, "user.email");
        if (hasName && hasEmail) {
            return;
        }

        var command = "GH_TOKEN=" + PLACEHOLDER_TOKEN
                + " gh api user --jq '[.login, .name, .email] | @tsv'";
        var tokenConfigured = !SpawnConfig.load().getGithub().getToken().isBlank();
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
            var message = "Could not determine git identity from GitHub token" + detail;
            if (tokenConfigured) {
                throw new IncusException(message + "; refusing to create a template without git identity");
            }
            return;
        }

        var parts = result.stdout().lines().findFirst().orElse("").split("\t", -1);
        if (parts[0].isEmpty()) {
            return;
        }

        var login = parts[0];
        var name = parts.length >= 2 && !parts[1].isEmpty() ? parts[1] : login;

        if (!hasName) {
            gitConfig(c, "user.name", name);
        }

        if (!hasEmail) {
            var configEmail = SpawnConfig.load().getGithub().getEmail();
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
            gitConfig(c, "user.email", email);
        }
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
