package dev.incusspawn.tool;

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

    static final Path CACHE_DIR = Path.of(
            System.getProperty("user.home"), ".cache", "incus-spawn", "skills");

    private final Path cacheDir;

    public SkillsCache() {
        this(CACHE_DIR);
    }

    SkillsCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Fetch a SKILL.md from GitHub, returning its content.
     * Cached by owner/repo/skillName — served from cache if present.
     */
    public String fetchSkillMd(String ownerRepo, String skillName, HttpClient http)
            throws IOException, InterruptedException {
        var cached = cacheDir.resolve(ownerRepo).resolve(skillName).resolve("SKILL.md");
        if (Files.exists(cached)) {
            return Files.readString(cached);
        }

        var content = downloadSkillMd(ownerRepo, skillName, http);
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, content);
        return content;
    }

    private static String downloadSkillMd(String ownerRepo, String skillName, HttpClient http)
            throws IOException, InterruptedException {
        for (var branch : new String[]{"main", "master"}) {
            var url = "https://raw.githubusercontent.com/" + ownerRepo + "/" + branch
                    + "/" + skillName + "/SKILL.md";
            var response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        }
        throw new IOException("SKILL.md not found in " + ownerRepo + " for skill '" + skillName + "'");
    }
}
