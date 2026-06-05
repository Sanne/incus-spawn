package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import static dev.incusspawn.incus.Container.shellQuote;

import java.util.List;

public class GhSetup implements ToolSetup {

    private static final String PLACEHOLDER_TOKEN = "gho_placeholder";

    @Override
    public String name() {
        return "gh";
    }

    @Override
    public List<String> packages() {
        return List.of("gh");
    }

    @Override
    public void install(Container c, java.util.Map<String, String> resolvedParams) {
        System.out.println("Installing GitHub CLI...");
        configureAuth(c);
        configureGit(c);
    }

    /**
     * Set GH_TOKEN so the CLI believes it's authenticated and proceeds to make
     * network requests. The placeholder is not a real credential — the MITM proxy
     * replaces the Authorization header with the real token before it reaches GitHub.
     * <p>
     * We use GH_TOKEN instead of hosts.yml because newer gh versions store tokens
     * in the system keyring (via D-Bus), which doesn't exist in containers. Any
     * hosts.yml triggers a migration that fails without D-Bus.
     */
    private void configureAuth(Container c) {
        c.appendToProfile("export GH_TOKEN=" + PLACEHOLDER_TOKEN);
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

        var result = c.sh("GH_TOKEN=" + PLACEHOLDER_TOKEN
                + " gh api user --jq '[.login, .name, .email] | @tsv'");
        if (!result.success() || result.stdout().isBlank()) {
            System.out.println("  Could not determine git identity from GitHub token — skipping user.name/email.");
            return;
        }

        var parts = result.stdout().lines().findFirst().orElse("").split("\t", -1);
        if (parts[0].isEmpty()) {
            System.out.println("  Unexpected GitHub API response — skipping user.name/email.");
            return;
        }

        var login = parts[0];
        var name = parts.length >= 2 && !parts[1].isEmpty() ? parts[1] : login;

        if (!hasName) {
            gitConfig(c, "user.name", name);
        }

        if (!hasEmail) {
            var email = findEmailFromApi(c);
            if (email == null && parts.length >= 3 && !parts[2].isEmpty()) {
                email = parts[2];
            }
            if (email != null) {
                gitConfig(c, "user.email", email);
            } else {
                System.out.println("  No email found — grant the 'user:email' scope on your GitHub token to enable noreply email detection.");
            }
        }
    }

    private String findEmailFromApi(Container c) {
        var result = c.sh("GH_TOKEN=" + PLACEHOLDER_TOKEN
                + " gh api user/emails --jq '"
                + "([.[] | select(.email | endswith(\"@users.noreply.github.com\")) | .email] | first)"
                + " // ([.[] | select(.primary and .verified) | .email] | first)'");
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
