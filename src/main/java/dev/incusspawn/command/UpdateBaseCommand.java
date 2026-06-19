package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.config.ImageDef;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@CommandDefinition(
        name = "update-base",
        description = "Check for and install base image updates",
        generateHelp = true
)
public class UpdateBaseCommand extends BaseCommand {

    private static final String GITHUB_REPO = "Sanne/incus-spawn-images";
    private static final String RELEASES_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Argument(required = false, description = "Release tag to install (e.g. fedora-44-v2)")
    String targetTag;

    @Option(name = "list", hasValue = false, description = "List available versions")
    boolean listOnly;

    @Option(name = "latest", hasValue = false, description = "Update to the latest version without prompting")
    boolean useLatest;

    @Override
    protected CommandResult doExecute() throws Exception {
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        if (minimal == null) {
            System.err.println("Template tpl-minimal not found.");
            return CommandResult.valueOf(1);
        }

        var currentTag = minimal.getImageTag();
        System.out.println("Current base image: " + (currentTag != null ? currentTag : "unknown"));

        System.out.println("Fetching available releases...");
        List<ReleaseInfo> releases;
        try {
            releases = fetchReleases();
        } catch (IOException e) {
            System.err.println("Failed to fetch releases: " + e.getMessage());
            return CommandResult.valueOf(1);
        }

        if (releases.isEmpty()) {
            System.out.println("No releases found.");
            return CommandResult.SUCCESS;
        }

        if (listOnly) {
            printReleaseList(releases, currentTag);
            return CommandResult.SUCCESS;
        }

        ReleaseInfo selected;
        if (targetTag != null) {
            selected = releases.stream()
                    .filter(r -> r.tag.equals(targetTag))
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                System.err.println("Release '" + targetTag + "' not found.");
                printReleaseList(releases, currentTag);
                return CommandResult.valueOf(1);
            }
        } else if (useLatest) {
            selected = releases.get(0);
        } else {
            printReleaseList(releases, currentTag);
            var latest = releases.get(0);
            if (latest.tag.equals(currentTag)) {
                System.out.println("\nAlready on the latest version.");
                return CommandResult.SUCCESS;
            }
            System.out.print("\nUpdate to " + latest.tag + "? [Y/n] ");
            var console = System.console();
            if (console == null) {
                System.err.println("No console available. Use --latest or specify a tag.");
                return CommandResult.valueOf(1);
            }
            var answer = console.readLine();
            if (answer != null && answer.strip().equalsIgnoreCase("n")) {
                System.out.println("Aborted.");
                return CommandResult.SUCCESS;
            }
            selected = latest;
        }

        if (selected.tag.equals(currentTag)) {
            System.out.println("Already on " + selected.tag + ".");
            return CommandResult.SUCCESS;
        }

        System.out.println("Fetching checksums for " + selected.tag + "...");
        var checksums = fetchSha256Sums(selected);
        if (checksums == null) {
            System.err.println("SHA256SUMS not found in release " + selected.tag + ".");
            return CommandResult.valueOf(1);
        }

        writeUserOverride(minimal, selected.tag, checksums);
        System.out.println("Updated to " + selected.tag + ".");
        System.out.println("The new base image will be downloaded on the next 'isx build tpl-minimal'.");

        return CommandResult.SUCCESS;
    }

    private void writeUserOverride(ImageDef current, String tag, Checksums checksums) throws IOException {
        var dir = ImageDef.userImagesDir();
        Files.createDirectories(dir);
        var overridePath = dir.resolve("minimal.yaml");

        var yaml = """
                name: tpl-minimal
                description: %s
                image: %s
                image_url: %s
                image_tag: %s
                image_sha256:
                  x86_64: %s
                  aarch64: %s
                """.formatted(
                current.getDescription(),
                current.getImage(),
                current.getImageUrl(),
                tag,
                checksums.x86_64,
                checksums.aarch64);

        Files.writeString(overridePath, yaml);
        System.out.println("Wrote " + overridePath);
    }

    record ReleaseInfo(String tag, String date, String sha256sumsUrl) {}
    record Checksums(String x86_64, String aarch64) {}

    private List<ReleaseInfo> fetchReleases() throws IOException {
        var client = httpClient();
        var request = githubRequest(RELEASES_API).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GitHub API returned " + response.statusCode());
            }
            var releases = new ArrayList<ReleaseInfo>();
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
                releases.add(new ReleaseInfo(tag, date, sha256Url));
            }
            return releases;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private Checksums fetchSha256Sums(ReleaseInfo release) throws IOException {
        if (release.sha256sumsUrl == null) return null;
        var client = httpClient();
        var request = HttpRequest.newBuilder(URI.create(release.sha256sumsUrl))
                .GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            String x86_64 = null, aarch64 = null;
            for (var line : response.body().split("\n")) {
                var parts = line.strip().split("\\s+", 2);
                if (parts.length < 2) continue;
                if (parts[1].contains("x86_64")) x86_64 = parts[0];
                if (parts[1].contains("aarch64")) aarch64 = parts[0];
            }
            if (x86_64 == null || aarch64 == null) return null;
            return new Checksums(x86_64, aarch64);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private void printReleaseList(List<ReleaseInfo> releases, String currentTag) {
        System.out.println("\nAvailable base images:");
        for (int i = 0; i < releases.size(); i++) {
            var r = releases.get(i);
            var markers = new ArrayList<String>();
            if (i == 0) markers.add("latest");
            if (r.tag.equals(currentTag)) markers.add("current");
            var suffix = markers.isEmpty() ? "" : "  [" + String.join(", ", markers) + "]";
            System.out.println("  " + r.tag + "  " + r.date + suffix);
        }
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static HttpRequest.Builder githubRequest(String url) {
        var builder = HttpRequest.newBuilder(URI.create(url)).GET();
        var token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }
}
