package dev.incusspawn.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A download cache that never fetches (#2588): each URL is answered by a small file of the test's,
 * so a whole build runs over fakes without the base image or a tool's binary coming from the
 * network. Claude Code's release files are answered as its installer reads them. Every URL asked
 * for is recorded.
 */
public final class OfflineDownloads extends DownloadCache {

    /** The Claude Code version its {@code latest} file names. */
    public static final String CLAUDE_VERSION = "2.1.0";
    private static final String CLAUDE_RELEASES = "https://downloads.claude.ai/claude-code-releases/";

    private final Path dir;
    private final List<String> asked = new ArrayList<>();

    public OfflineDownloads(Path dir) {
        this.dir = dir;
    }

    /** The URLs asked for, in order. */
    public synchronized List<String> asked() {
        return List.copyOf(asked);
    }

    @Override
    public Path download(String url, String sha256) {
        return answer(url);
    }

    @Override
    public Path downloadAllowingLocalFile(String url, String sha256, Listener listener) {
        return answer(url);
    }

    private synchronized Path answer(String url) {
        asked.add(url);
        String content;
        if (url.equals(CLAUDE_RELEASES + "latest")) {
            content = CLAUDE_VERSION + "\n";
        } else if (url.equals(CLAUDE_RELEASES + CLAUDE_VERSION + "/manifest.json")) {
            var checksum = "0".repeat(64);
            content = "{\"platforms\": {\"linux-x64\": {\"checksum\": \"" + checksum + "\"},"
                    + " \"linux-arm64\": {\"checksum\": \"" + checksum + "\"}}}";
        } else {
            // A stand-in for the file, unique to its URL: an image's fingerprint is its file's checksum
            content = "offline stand-in for " + url + "\n";
        }
        try {
            Files.createDirectories(dir);
            var file = dir.resolve("download-" + asked.size());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code tools} with each built-in tool that downloads on the host answered by {@code downloads}
     * instead: Claude Code's installer is the one a first template holds.
     */
    public static List<ToolSetup> over(Iterable<ToolSetup> tools, DownloadCache downloads) {
        var offline = new ArrayList<ToolSetup>();
        for (var tool : tools) {
            offline.add(tool instanceof ClaudeSetup ? new ClaudeSetup(downloads) : tool);
        }
        return offline;
    }
}
