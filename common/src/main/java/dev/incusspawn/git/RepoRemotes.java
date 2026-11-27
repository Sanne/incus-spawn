package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.OutputFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static dev.incusspawn.incus.Container.shellQuote;

/**
 * The remotes a {@code repos:} entry names beside its clone's own (#343): {@code remote:} renames
 * the clone's remote, and {@code remotes:} adds others, each spelled out in full.
 *
 * <p>An added remote is never fetched by isx, so a fork that does not exist yet cannot fail a
 * build, and it is marked {@code skipFetchAll}, so {@code git fetch --all} (which
 * {@code isx update-all} runs) skips it too. The one placeholder a URL may hold is
 * {@link #LOGIN}, the login of the instance's GitHub account. A remote holding it is recorded in
 * {@link #RECORD}, and {@link #fillScript} sets its URL from the login at build time and again on
 * every change of the instance's GitHub account. Without a login its URL keeps the placeholder,
 * which names no repository, so it never goes on naming another account's fork, and the
 * branches that track it keep their settings for when a login fills it in again.
 */
public final class RepoRemotes {

    /** The name of a clone's own remote when the entry names none. */
    public static final String DEFAULT_REMOTE = "origin";

    /** The placeholder for the login of the instance's GitHub account. */
    public static final String LOGIN = "{github.login}";

    /** The remotes that name {@link #LOGIN}, one per line: the clone's path, the remote's name and its URL, tab-separated. */
    public static final String RECORD = "/etc/isx/repo-remotes";

    /** A remote name every Git accepts and no shell or record reads otherwise: no slash, no leading dot or dash. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * A GitHub login as GitHub has issued them: a letter or digit, then letters, digits and hyphens
     * (older accounts have doubled and trailing ones), and the underscore before an Enterprise
     * Managed User's shortcode ({@code handle_shortcode}). Each is safe in a URL path and a shell
     * word. Its length, at most {@value #GITHUB_LOGIN_MAX} characters, is checked apart.
     */
    private static final Pattern GITHUB_LOGIN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    /** The most characters GitHub allows in a login. */
    private static final int GITHUB_LOGIN_MAX = 39;

    /** Why a repo with a remote naming {@link #LOGIN} cannot have its path: the record is one line per remote. */
    public static final String PATH_PROBLEM = "a repo whose remote names " + LOGIN + " needs a path without control characters";

    private RepoRemotes() {}

    /** A remote that names {@link #LOGIN}, of the clone at {@code path} (as the guest reads it). */
    public record LoginRemote(String path, String name, String url) {}

    /** Why {@code name} cannot be a remote's name, or null. */
    static String nameProblem(String name) {
        if (name == null || name.isBlank()) return "a remote's name must not be blank";
        if (!NAME.matcher(name).matches() || name.contains("..") || name.endsWith(".lock")) {
            return "'" + OutputFormat.oneLine(name) + "' is not a remote name: use letters, digits, '.', '_' and '-',"
                    + " starting with a letter or digit";
        }
        return null;
    }

    /** Why {@code repo}'s {@code remote:} and {@code remotes:} cannot be set up, or null. */
    public static String problem(ImageDef.RepoEntry repo) {
        if (repo.getRemote() != null) {
            if (repo.isHostSource()) {
                return "'remote' names the clone's remote, and a host-source clone keeps none; use 'remotes' to add one";
            }
            var problem = nameProblem(repo.getRemote());
            if (problem != null) return "'remote': " + problem;
        }
        for (var entry : repo.getRemotes().entrySet()) {
            var name = entry.getKey();
            var problem = nameProblem(name);
            if (problem != null) return "'remotes': " + problem;
            var shown = "'remotes." + name + "'";
            if (!repo.isHostSource() && name.equals(repo.remoteName())) {
                return shown + " is the clone's own remote; give the other remote another name, or the clone's"
                        + " another with 'remote'";
            }
            var url = entry.getValue();
            if (url == null || url.isBlank()) return shown + " has no URL";
            if (url.replace(LOGIN, "").indexOf('{') >= 0 || url.replace(LOGIN, "").indexOf('}') >= 0) {
                return shown + ": " + LOGIN + " is the only placeholder a remote's URL may hold";
            }
            if (!GitPreparation.isRemoteUrl(url.replace(LOGIN, "login"))) {
                return shown + " '" + OutputFormat.oneLine(url) + "' is not an HTTPS, SSH or Git network URL";
            }
        }
        if (hasLoginRemote(repo) && repo.getPath() != null
                && repo.getPath().chars().anyMatch(Character::isISOControl)) {
            return PATH_PROBLEM;
        }
        return null;
    }

    /** Whether one of {@code repo}'s remotes names the GitHub login. */
    public static boolean hasLoginRemote(ImageDef.RepoEntry repo) {
        return repo.getRemotes().values().stream().anyMatch(url -> url != null && url.contains(LOGIN));
    }

    /**
     * The commands, run as agentuser in a fresh clone at {@code path} before its prime, that give it
     * {@code repo}'s remotes: its own renamed and the ones with no placeholder added, never fetched.
     * Null when the entry names none of them. A remote naming {@link #LOGIN} is left to
     * {@link #fillScript}.
     */
    public static String cloneScript(ImageDef.RepoEntry repo, String path) {
        var git = "git -C " + shellQuote(path);
        var commands = new ArrayList<String>();
        if (!repo.isHostSource() && !repo.remoteName().equals(DEFAULT_REMOTE)) {
            commands.add(git + " remote rename " + DEFAULT_REMOTE + " " + shellQuote(repo.remoteName()));
        }
        repo.getRemotes().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(e -> !e.getValue().contains(LOGIN))
                .forEach(e -> commands.add(git + " remote add " + shellQuote(e.getKey()) + " " + shellQuote(e.getValue())
                        + " && " + git + " config " + shellQuote("remote." + e.getKey() + ".skipFetchAll") + " true"));
        return commands.isEmpty() ? null : String.join(" && ", commands);
    }

    /** The remotes naming {@link #LOGIN} among {@code repos}, each at the guest path {@code expand} gives. */
    public static List<LoginRemote> loginRemotes(List<ImageDef.RepoEntry> repos,
                                                 java.util.function.UnaryOperator<String> expand) {
        var result = new ArrayList<LoginRemote>();
        for (var repo : repos) {
            if (repo.getPath() == null) continue;
            repo.getRemotes().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .filter(e -> e.getValue().contains(LOGIN))
                    .forEach(e -> result.add(new LoginRemote(expand.apply(repo.getPath()), e.getKey(), e.getValue())));
        }
        return result;
    }

    /** The record's text for {@code remotes}: one tab-separated line each. */
    static String record(List<LoginRemote> remotes) {
        var sb = new StringBuilder();
        for (var remote : remotes) {
            sb.append(remote.path()).append('\t').append(remote.name()).append('\t').append(remote.url()).append('\n');
        }
        return sb.toString();
    }

    /**
     * The script, run as root, that replaces {@link #RECORD} (under {@code root}, {@code ""} in a
     * guest) with {@code remotes}, or removes it when there are none: each build writes the whole
     * chain's, so a remote a template no longer declares is not filled again.
     */
    public static String recordScript(String root, List<LoginRemote> remotes) {
        var file = shellQuote(root + RECORD);
        if (remotes.isEmpty()) return "rm -f " + file;
        return Container.heredoc("set -e; mkdir -p \"$(dirname " + file + ")\"; cat > " + file + ".new",
                record(remotes).strip()) + "\nchmod 0644 " + file + ".new && mv -f " + file + ".new " + file;
    }

    /** Whether {@code login} can be put into a URL as a GitHub login. */
    public static boolean validLogin(String login) {
        return login != null && login.length() <= GITHUB_LOGIN_MAX && GITHUB_LOGIN.matcher(login).matches();
    }

    /**
     * The script, run as agentuser, that brings every remote of {@link #RECORD} (under
     * {@code root}) in line with {@code login}: added or re-pointed to the URL with the login
     * filled in, marked {@code skipFetchAll} and never fetched. When {@code login} is null its URL is
     * the declared one, placeholder and all, so no remote goes on naming the fork of an account the
     * instance no longer uses; it is not removed, since that would drop every branch's tracking of it. A clone
     * that is gone is skipped. Prints {@link #CHANGED} and the path and name of each remote
     * it changed.
     */
    public static String fillScript(String root, String login) {
        if (login != null && !validLogin(login)) {
            throw new IllegalArgumentException("not a GitHub login: " + OutputFormat.oneLine(login));
        }
        var l = login == null ? "''" : shellQuote(login);
        return "f=" + shellQuote(root + RECORD) + "; login=" + l + "\n"
                + "[ -r \"$f\" ] || exit 0\n"
                + "tab=$(printf '\\t'); status=0\n"
                + "while IFS=\"$tab\" read -r p n u; do\n"
                + "  [ -n \"$n\" ] && [ -d \"$p\" ] || continue\n"
                // Without a login the URL keeps its placeholder, which names no repository
                + "  url=$u\n"
                + "  if [ -n \"$login\" ]; then\n"
                // Every occurrence, by the shell alone: a quoted part of a pattern is literal
                + "    url=''; rest=$u\n"
                + "    while case $rest in *'" + LOGIN + "'*) true ;; *) false ;; esac; do\n"
                + "      url=\"$url${rest%%'" + LOGIN + "'*}$login\"; rest=${rest#*'" + LOGIN + "'}\n"
                + "    done\n"
                + "    url=\"$url$rest\"\n"
                + "  fi\n"
                // The URL as configured, not as an insteadOf rule rewrites it
                + "  if old=$(git -C \"$p\" config --get \"remote.$n.url\"); then\n"
                + "    [ \"$old\" = \"$url\" ] && continue\n"
                + "    git -C \"$p\" remote set-url \"$n\" \"$url\" || { status=1; continue; }\n"
                + "  else\n"
                + "    git -C \"$p\" remote add \"$n\" \"$url\" || { status=1; continue; }\n"
                + "  fi\n"
                + "  git -C \"$p\" config \"remote.$n.skipFetchAll\" true && echo \"" + CHANGED + "$p\t$n\" || status=1\n"
                + "done < \"$f\"\n"
                + "exit $status";
    }

    /** What {@link #fillScript} prints before each remote it changed. */
    public static final String CHANGED = "isx-remote ";

    /** Whether the guest has a {@link #RECORD} to fill: one exec, which must say so, not only succeed. */
    public static boolean hasRecord(Container container) {
        var result = container.sh("if [ -s " + RECORD + " ]; then echo " + HAS_RECORD + "; fi");
        return result.success() && result.stdout().strip().equals(HAS_RECORD);
    }

    private static final String HAS_RECORD = "isx-repo-remotes";

    /** Runs {@link #fillScript} in the guest, failing when a remote could not be set. */
    public static void fill(Container container, String login) {
        container.shAsUser("agentuser", fillScript("", login))
                .assertSuccess("Failed to set the remotes that name the GitHub login");
    }
}
