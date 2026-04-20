package dev.incusspawn.tool;

import dev.incusspawn.RuntimeConstants;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Host-side cache for Claude Code skill files (SKILL.md).
 * <p>
 * Skills are cached in {@code ~/.cache/incus-spawn/skills/<owner>/<repo>/<skill-name>/SKILL.md}
 * mirroring the GitHub path structure. A cached file is always reused — to force a refresh,
 * delete the cache directory.
 */
public class SkillsCache {

    private static Path defaultCacheDir() {
        return RuntimeConstants.SKILLS_CACHE_DIR;
    }

    private final Path cacheDir;

    public SkillsCache() {
        this(defaultCacheDir());
    }

    SkillsCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Fetch a SKILL.md from GitHub, returning its content.
     * Cached by owner/repo/skillName. On subsequent calls, a conditional
     * request (If-None-Match) revalidates the cache without re-downloading
     * unchanged content.
     */
    public String fetchSkillMd(String ownerRepo, String skillName, HttpClient http)
            throws IOException, InterruptedException {
        var cached = cacheDir.resolve(ownerRepo).resolve(skillName).resolve("SKILL.md");
        var etagFile = cacheDir.resolve(ownerRepo).resolve(skillName).resolve(".etag");

        String existingEtag = null;
        boolean hasCached = Files.exists(cached);
        if (hasCached && Files.exists(etagFile)) {
            existingEtag = Files.readString(etagFile).strip();
        }

        DownloadResult result;
        try {
            result = downloadSkillMd(ownerRepo, skillName, http, existingEtag);
        } catch (IOException e) {
            if (hasCached) {
                System.err.println("Warning: could not refresh skill '" + skillName
                        + "' from " + ownerRepo + " (offline?), using cached version.");
                return Files.readString(cached);
            }
            throw e;
        }
        if (result == null) {
            return Files.readString(cached);
        }

        Files.createDirectories(cached.getParent());
        Files.writeString(cached, result.content());
        if (result.etag() != null) {
            Files.writeString(etagFile, result.etag());
        }
        return result.content();
    }

    private record DownloadResult(String content, String etag) {}

    private static DownloadResult downloadSkillMd(String ownerRepo, String skillName,
            HttpClient http, String ifNoneMatch)
            throws IOException, InterruptedException {
        for (var branch : new String[]{"main", "master"}) {
            var url = "https://raw.githubusercontent.com/" + ownerRepo + "/" + branch
                    + "/" + skillName + "/SKILL.md";
            var reqBuilder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10));
            if (ifNoneMatch != null) {
                reqBuilder.header("If-None-Match", ifNoneMatch);
            }
            var response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 304) {
                return null;
            }
            if (response.statusCode() == 200) {
                var etag = response.headers().firstValue("ETag").orElse(null);
                return new DownloadResult(response.body(), etag);
            }
        }
        throw new IOException("SKILL.md not found in " + ownerRepo + " for skill '" + skillName + "'");
    }
}
