package dev.incusspawn.baseimage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.Environment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fetches available base-image releases from the GitHub repository that a
 * template's {@code image_url} points at. Shared by {@code isx update-base}
 * (interactive selection/pinning) and {@code isx build} (build-time "track
 * latest" resolution).
 *
 * <p>The owning repo is derived from the {@code image_url} rather than hardcoded,
 * so the release list always comes from the same repository the build will
 * download from — the YAML {@code image_url} is the single source of truth.
 *
 * <p>The newest release is always {@code fetchReleases().get(0)} — the GitHub
 * releases API returns releases newest-first.
 */
public final class BaseImageReleases {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern GITHUB_RELEASE_URL =
            Pattern.compile("^https://github\\.com/([^/]+)/([^/]+)/releases/");

    private final String releasesApi;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private BaseImageReleases(String ownerRepo) {
        this.releasesApi = "https://api.github.com/repos/" + ownerRepo + "/releases";
    }

    /**
     * Build a fetcher for the GitHub repo a base-image download URL points at
     * (e.g. an {@code ImageDef.image_url}), or {@code null} if the URL is not a
     * {@code github.com} release URL — i.e. not a releases-tracked base image.
     */
    public static BaseImageReleases fromImageUrl(String imageUrl) {
        if (imageUrl == null) return null;
        var m = GITHUB_RELEASE_URL.matcher(imageUrl);
        if (!m.find()) return null;
        return new BaseImageReleases(m.group(1) + "/" + m.group(2));
    }

    /** A published base-image release. */
    public record Release(String tag, String date, String sha256sumsUrl) {}

    /**
     * SHA256 checksums for a release, keyed by architecture ({@code x86_64},
     * {@code aarch64}). {@code container} covers the {@code .tar.xz} rootfs
     * images; {@code vm} covers the {@code -vm.tar.xz} disk images (may be
     * empty for releases that ship no VM assets).
     */
    public record Checksums(Map<String, String> container, Map<String, String> vm) {}

    public List<Release> fetchReleases() throws IOException {
        var response = sendString(githubRequest(releasesApi)
                .timeout(Duration.ofSeconds(15))
                .build());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned " + response.statusCode());
        }
        var releases = new ArrayList<Release>();
        for (var node : JSON.readTree(response.body())) {
            var tag = node.path("tag_name").asText("");
            if (!tag.startsWith("fedora-")) continue;
            var date = node.path("published_at").asText("").split("T")[0];
            String sha256Url = null;
            for (var asset : node.path("assets")) {
                if ("SHA256SUMS".equals(asset.path("name").asText(""))) {
                    sha256Url = asset.path("browser_download_url").asText(null);
                    break;
                }
            }
            releases.add(new Release(tag, date, sha256Url));
        }
        return releases;
    }

    public Checksums fetchChecksums(Release release) throws IOException {
        if (release.sha256sumsUrl() == null) return null;
        var response = sendString(HttpRequest.newBuilder(URI.create(release.sha256sumsUrl()))
                .timeout(Duration.ofSeconds(15))
                .GET().build());
        if (response.statusCode() != 200) return null;
        return parseSha256Sums(response.body());
    }

    /**
     * Parse a {@code SHA256SUMS} manifest into per-arch container and VM
     * checksums. Each line is {@code <sha256>  <filename>}; the arch is read
     * from the filename and the {@code -vm} marker distinguishes the VM disk
     * image from the container rootfs (e.g. {@code fedora-44-x86_64-vm.tar.xz}
     * vs {@code fedora-44-x86_64.tar.xz}).
     */
    static Checksums parseSha256Sums(String body) {
        var container = new HashMap<String, String>();
        var vm = new HashMap<String, String>();
        for (var line : body.split("\n")) {
            var parts = line.strip().split("\\s+", 2);
            if (parts.length < 2) continue;
            var sha = parts[0];
            var name = parts[1];
            String arch = null;
            if (name.contains("x86_64")) arch = "x86_64";
            else if (name.contains("aarch64")) arch = "aarch64";
            if (arch == null) continue;
            if (name.contains("-vm")) vm.put(arch, sha);
            else container.put(arch, sha);
        }
        return new Checksums(container, vm);
    }

    private HttpResponse<String> sendString(HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private static HttpRequest.Builder githubRequest(String url) {
        var builder = HttpRequest.newBuilder(URI.create(url)).GET();
        var token = Environment.strippedEnv("GITHUB_TOKEN");
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }
}
