package dev.incusspawn.baseimage;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.Environment;
import dev.incusspawn.util.SystemTrust;
import io.hyperfoil.tools.jjq.value.JqValue;

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
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    /** A release tag, with its release line (distribution and release) as group 1: fedora-44 in fedora-44-20261101. */
    private static final Pattern RELEASE_TAG = Pattern.compile("([a-z]+-[0-9]+)-[A-Za-z0-9._-]+");

    private static final Pattern GITHUB_RELEASE_URL =
            Pattern.compile("^https://github\\.com/([^/]+)/([^/]+)/releases/");

    private final String releasesApi;
    private final HttpClient client = SystemTrust.httpClient()
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

    /** The architectures base images are published for. */
    static final List<String> ARCHES = dev.incusspawn.config.BaseImagePin.ARCHES;

    /**
     * A published base-image release, with the download URLs of its manifests, each null when the
     * release has none: {@code SHA256SUMS}, which Fedora 45 releases no longer publish, and
     * {@code SHA256SUMS.v2}, which is read first.
     */
    public record Release(String tag, String date, String sha256sumsUrl, String sha256sumsV2Url) {}

    /**
     * SHA256 checksums of the assets a template downloads from a release, keyed by architecture
     * ({@code x86_64}, {@code aarch64}): {@code container} for its {@code image_url},
     * {@code vm} for its {@code vm_image_url}. An arch the release has no such asset for is
     * absent, so either map may be empty.
     */
    public record Checksums(Map<String, String> container, Map<String, String> vm) {}

    public List<Release> fetchReleases() throws IOException {
        var response = send(githubRequest(releasesApi)
                .timeout(Duration.ofSeconds(15))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned " + response.statusCode());
        }
        return parseReleases(response.body());
    }

    /**
     * The fedora releases in GitHub's release list, as the bytes it sent. A malformed list is an
     * {@link IOException} like a failed request, so callers keep the built-in base image.
     */
    static List<Release> parseReleases(byte[] json) throws IOException {
        JqValue list;
        try {
            list = Jjq.parseForeign(json, "GitHub's release list");
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        var releases = new ArrayList<Release>();
        for (var node : list.asList()) {
            var tag = node.pathText("tag_name", "");
            if (!tag.startsWith("fedora-")) continue;
            var date = node.pathText("published_at", "").split("T")[0];
            String sha256Url = null;
            String sha256V2Url = null;
            for (var asset : node.getField("assets").asList()) {
                var name = asset.pathText("name", "");
                if ("SHA256SUMS".equals(name)) sha256Url = asset.pathText("browser_download_url", null);
                else if ("SHA256SUMS.v2".equals(name)) sha256V2Url = asset.pathText("browser_download_url", null);
            }
            releases.add(new Release(tag, date, sha256Url, sha256V2Url));
        }
        return releases;
    }

    /**
     * The checksums of the assets {@code imageUrl} and {@code vmImageUrl} name in {@code release},
     * or null when its manifest cannot be fetched or read. {@code SHA256SUMS.v2} is read when the
     * release has one: it lists every asset, the VM image built from scratch
     * ({@code -vm-direct.tar.xz}) included, while {@code SHA256SUMS} stays exactly what isx
     * releases before it parse (#1255).
     */
    public Checksums fetchChecksums(Release release, String imageUrl, String vmImageUrl) throws IOException {
        // A v2 that cannot be downloaded (an error status or a failed request) leaves SHA256SUMS,
        // which still lists the older assets; one that downloads but does not parse is a broken
        // release, and is not read around.
        String body = null;
        if (release.sha256sumsV2Url() != null) {
            try {
                body = fetchText(release.sha256sumsV2Url());
            } catch (IOException e) {
                body = null;
            }
        }
        if (body == null && release.sha256sumsUrl() != null) body = fetchText(release.sha256sumsUrl());
        if (body == null) return null;
        try {
            return checksumsFor(parseManifest(body), release.tag(), imageUrl, vmImageUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException("unreadable checksum manifest in " + release.tag() + ": " + e.getMessage(), e);
        }
    }

    private String fetchText(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("not a URL: " + url, e);
        }
        var response = send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }

    /**
     * A {@code SHA256SUMS}-style manifest, one {@code <sha256>  <filename>} per line, as filename
     * to checksum. A line that is not that shape, or a file listed twice, makes the whole
     * manifest unreadable: a checksum is what a base image is trusted by, so none is guessed.
     */
    static Map<String, String> parseManifest(String body) {
        var sums = new HashMap<String, String>();
        for (var line : body.split("\n")) {
            if (line.isBlank()) continue;
            var parts = WHITESPACE.split(line.strip(), 2);
            if (parts.length < 2 || !SHA256_HEX.matcher(parts[0]).matches()) {
                throw new IllegalArgumentException("not '<sha256>  <file>': " + line.strip());
            }
            var name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
            if (sums.putIfAbsent(name, parts[0].toLowerCase(java.util.Locale.ROOT)) != null) {
                throw new IllegalArgumentException(name + " is listed twice");
            }
        }
        return sums;
    }

    /**
     * The checksums, per arch, of the files {@code imageUrl} and {@code vmImageUrl} download for
     * {@code tag}: each URL's last path segment with {@code {arch}} and {@code {tag}} filled in,
     * looked up by its exact name. Matching the names a template downloads, rather than guessing
     * from markers in a file name, keeps a new asset from ever standing in for another.
     */
    static Checksums checksumsFor(Map<String, String> manifest, String tag, String imageUrl, String vmImageUrl) {
        var container = new HashMap<String, String>();
        var vm = new HashMap<String, String>();
        for (var arch : ARCHES) {
            if (imageUrl != null) putIfListed(container, arch, manifest, assetName(imageUrl, tag, arch));
            if (vmImageUrl != null) putIfListed(vm, arch, manifest, assetName(vmImageUrl, tag, arch));
        }
        return new Checksums(container, vm);
    }

    private static void putIfListed(Map<String, String> into, String arch, Map<String, String> manifest, String name) {
        var sha = manifest.get(name);
        if (sha != null) into.put(arch, sha);
    }

    /** The image alias and download URLs a base image is taken under; {@code vmImageUrl} may be null. */
    public record Names(String image, String imageUrl, String vmImageUrl) {}

    /** The release line {@code tag} is of, as {@code fedora-44} for {@code fedora-44-20261101}, or null. */
    public static String releaseLine(String tag) {
        if (tag == null) return null;
        var m = RELEASE_TAG.matcher(tag);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * The names a release {@code tag} is taken under, given {@code current}, the names of the
     * release {@code currentTag}. A release of another line has its assets named for that line
     * (#1967), so the alias and both URLs' file names move to it, as {@code move_line} in
     * {@code .github/scripts/base-image-sums.sh} moves them for a bump to another line; within one
     * line, or when either tag names no line, they stay as they are.
     *
     * @throws IllegalArgumentException when a name does not hold the current line exactly once,
     *         so it cannot be moved without guessing; the message says which
     */
    public static Names namesFor(Names current, String currentTag, String tag) {
        var from = releaseLine(currentTag);
        var to = releaseLine(tag);
        if (from == null || to == null || from.equals(to)) return current;
        return new Names(current.image() == null ? null : moveLine(current.image(), from, to),
                moveFileLine(current.imageUrl(), from, to), moveFileLine(current.vmImageUrl(), from, to));
    }

    private static String moveFileLine(String url, String from, String to) {
        if (url == null) return null;
        var slash = url.lastIndexOf('/') + 1;
        return url.substring(0, slash) + moveLine(url.substring(slash), from, to);
    }

    /** {@code name} with its one {@code <from>-} made {@code <to>-}, as the workflow's {@code move_line}. */
    private static String moveLine(String name, String from, String to) {
        var marker = from + "-";
        var at = name.indexOf(marker);
        if (at < 0 || at != name.lastIndexOf(marker)) {
            throw new IllegalArgumentException("'" + name + "' does not name release line " + from
                    + " exactly once, so it cannot be moved to " + to);
        }
        return name.substring(0, at) + to + "-" + name.substring(at + marker.length());
    }

    /** Whether {@code sums} has a checksum for every arch base images are published for. */
    public static boolean coversEveryArch(Map<String, String> sums) {
        return sums.keySet().containsAll(ARCHES);
    }

    /** The file name {@code urlTemplate} downloads for {@code tag} on {@code arch}. */
    public static String assetName(String urlTemplate, String tag, String arch) {
        var url = urlTemplate.replace("{arch}", arch).replace("{tag}", tag);
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) throws IOException {
        try {
            return client.send(request, body);
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
