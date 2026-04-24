package dev.incusspawn.git;

import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GitRemoteUtils {

    private static final String CONTAINER_HOME = "/home/agentuser";

    public record IsxUrl(String instance, String path) {}

    private GitRemoteUtils() {}

    public static IsxUrl parseIsxUrl(String url) {
        if (url == null || !url.startsWith("isx://")) return null;
        var rest = url.substring("isx://".length());
        var slashIdx = rest.indexOf('/');
        if (slashIdx <= 0) return null;
        var instance = rest.substring(0, slashIdx);
        var path = rest.substring(slashIdx);
        if (instance.isEmpty() || path.isEmpty()) return null;
        path = expandContainerTilde(path);
        return new IsxUrl(instance, path);
    }

    public static String expandContainerTilde(String path) {
        if (path.startsWith("/~/")) return CONTAINER_HOME + path.substring(2);
        if (path.equals("/~")) return CONTAINER_HOME;
        return path;
    }

    public static String repoNameFromUrl(String gitUrl) {
        if (gitUrl == null || gitUrl.isEmpty()) return "";
        var url = gitUrl.strip();
        // Handle SSH: git@github.com:org/repo.git
        var colonIdx = url.indexOf(':');
        if (colonIdx > 0 && !url.substring(0, colonIdx).contains("/")) {
            url = url.substring(colonIdx + 1);
        }
        // Strip query/fragment
        var qIdx = url.indexOf('?');
        if (qIdx >= 0) url = url.substring(0, qIdx);
        var hIdx = url.indexOf('#');
        if (hIdx >= 0) url = url.substring(0, hIdx);
        // Take last path segment
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        var lastSlash = url.lastIndexOf('/');
        var name = lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
        // Strip .git suffix
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        return name;
    }

    public static String normalizeGitUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        var s = url.strip();
        // SSH format: git@github.com:org/repo.git -> github.com/org/repo
        var atIdx = s.indexOf('@');
        var colonIdx = s.indexOf(':', atIdx > 0 ? atIdx : 0);
        if (atIdx > 0 && colonIdx > atIdx && !s.substring(atIdx + 1, colonIdx).contains("/")) {
            s = s.substring(atIdx + 1);
            s = s.replaceFirst(":", "/");
        } else {
            // HTTPS/SSH scheme: strip protocol
            s = s.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
            // Strip user@ prefix
            var at = s.indexOf('@');
            if (at >= 0 && at < s.indexOf('/')) {
                s = s.substring(at + 1);
            }
        }
        // Strip trailing slash and .git (loop so ".git/" is handled)
        boolean changed = true;
        while (changed) {
            changed = false;
            if (s.endsWith("/")) { s = s.substring(0, s.length() - 1); changed = true; }
            if (s.endsWith(".git")) { s = s.substring(0, s.length() - 4); changed = true; }
        }
        // Strip www. prefix on host
        if (s.startsWith("www.")) s = s.substring(4);
        return s.toLowerCase();
    }

    public static boolean urlsMatch(String a, String b) {
        return normalizeGitUrl(a).equals(normalizeGitUrl(b));
    }

    public static Path resolveHostRepoPath(String repoName, SpawnConfig config) {
        // Per-repo override first
        var override = config.getRepoPaths().get(repoName);
        if (override != null && !override.isEmpty()) {
            return Path.of(HostResourceSetup.expandHostTilde(override));
        }
        // Fall back to host-path base directory
        if (config.getHostPath().isEmpty()) return null;
        var basePath = HostResourceSetup.expandHostTilde(config.getHostPath());
        return Path.of(basePath, repoName);
    }

    public static List<ImageDef.RepoEntry> collectReposForInstance(String instanceName, IncusClient incus) {
        var repos = new ArrayList<ImageDef.RepoEntry>();
        try {
            var parent = incus.configGet(instanceName, Metadata.PARENT);
            if (parent.isEmpty()) return repos;

            var allDefs = ImageDef.loadAll();
            var current = allDefs.get(parent);
            while (current != null) {
                repos.addAll(current.getRepos());
                if (current.isRoot() || current.getParent() == null) break;
                current = allDefs.get(current.getParent());
            }
        } catch (Exception e) {
            // Instance might not have parent metadata — that's fine
        }
        return repos;
    }

    public static boolean isGitRepo(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }
}
