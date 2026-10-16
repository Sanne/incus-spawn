package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.proxy.InstanceSecret;

/** Presents the guest's GitHub proof to Git, which does not read GH_TOKEN itself (#1420). */
public final class GitHubGitCredential {
    private GitHubGitCredential() {}

    /**
     * Git appends get/store/erase to this shell helper. Read the current delivery on every get,
     * including from a non-login process or an old shell after a proof reset. Sourcing the
     * delivery preserves an explicitly supplied token, just as the login profile does.
     * No credential is stored in Git's configuration or persisted by store/erase.
     */
    static final String HELPER = "!isx_github_credential() { "
            + "[ \"$1\" = get ] || return 0; "
            + "protocol= host=; while IFS= read -r line && [ -n \"$line\" ]; do "
            + "case \"$line\" in protocol=*) protocol=${line#protocol=} ;; host=*) host=${line#host=} ;; esac; done; "
            + "[ \"$protocol\" = https ] || return 0; "
            + "case \"$host\" in github.com|github.com:443) ;; *) return 0 ;; esac; "
            + "GH_TOKEN=${GH_TOKEN:-gho_placeholder}; "
            + "if [ -r " + InstanceSecret.PROOFS_PATH + " ]; then . " + InstanceSecret.PROOFS_PATH + "; fi; "
            + "case \"$GH_TOKEN\" in ''|gho_placeholder*) return 0 ;; esac; "
            + "printf '%s\\n' username=x-access-token \"password=$GH_TOKEN\"; "
            + "}; isx_github_credential";

    /**
     * Root installs a URL-scoped default without touching the user's .gitconfig or other
     * helpers. Replace only our own entry, so retries and upgrades do not accumulate helpers.
     * A template may explicitly reset the helper list in its own Git configuration.
     */
    public static final String INSTALL_SCRIPT = "if command -v git >/dev/null 2>&1; then "
            + "git config --system --replace-all credential.https://github.com.helper "
            + Container.shellQuote(HELPER) + " '^!isx_github_credential'; fi";
}
