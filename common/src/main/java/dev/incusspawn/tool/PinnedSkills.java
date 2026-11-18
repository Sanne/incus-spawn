package dev.incusspawn.tool;

import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;
import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Skills from a GitHub repository at a pinned commit, each installed as its whole directory (#2354).
 * <p>
 * The pin is the commit ID a tag names, the publisher's own name for the content. Every file is
 * read at that commit, never at the tag, and its Git blob ID is checked against the commit's tree,
 * so what the build installs cannot drift once the pin is written. On a cache miss the tag is
 * resolved too, and a tag that has moved to another commit fails the fetch.
 * <p>
 * Packs are cached on the host at {@code <cache>/skills-pinned/<owner>/<repo>/<commit>/<path>/<skill>/},
 * beside the unpinned skills' cache rather than in it, whose {@code owner/repo@a/b} paths an
 * unchecked skill name could otherwise make land here.
 * A commit never changes, so a cached skill is reused without any request.
 */
public final class PinnedSkills {
    private static final String GITHUB_API = "https://api.github.com";
    private static final String GITHUB_RAW = "https://raw.githubusercontent.com";
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SEGMENT = Pattern.compile("[^/\\\\\\x00-\\x1f\\x7f]+");
    /** The marker of a pack whose every skill was fetched: its names, one per line. */
    private static final String ALL_MARKER = ".all";
    private static final long MAX_FILE_BYTES = 8L << 20;
    private static final long MAX_PACK_BYTES = 64L << 20;
    private static final int MAX_TAG_HOPS = 5;

    private final Path cacheDir;
    private final String apiBase;
    private final String rawBase;

    public PinnedSkills() {
        this(RuntimeConstants.SKILLS_CACHE_DIR.resolveSibling("skills-pinned"), GITHUB_API, GITHUB_RAW);
    }

    /** A cache and GitHub API and raw-content bases of its own; only GitHub's own API is sent the host's token. */
    public PinnedSkills(Path cacheDir, String apiBase, String rawBase) {
        this.cacheDir = cacheDir;
        this.apiBase = apiBase;
        this.rawBase = rawBase;
    }

    /** Why {@code repo} cannot name a GitHub repository, or null. */
    public static String repoProblem(String repo) {
        if (repo == null || !REPO.matcher(repo).matches()) return "must be 'owner/repo'";
        for (var part : repo.split("/")) {
            if (part.equals(".") || part.equals("..")) return "must be 'owner/repo'";
        }
        return null;
    }

    /** Why {@code commit} is not a full commit ID, or null. */
    public static String commitProblem(String commit) {
        return commit != null && COMMIT.matcher(commit).matches() ? null
                : "must be the full 40-character commit ID the tag names";
    }

    /** Why {@code relative} is not a plain relative path inside a repository, or null. */
    public static String pathProblem(String relative) {
        if (relative == null || relative.isEmpty()) return null;
        for (var segment : relative.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                return "must be a relative path inside the repository, without '.' or '..'";
            }
        }
        return null;
    }

    /** One pack: the skills under {@code path} of {@code repo} at {@code commit}, which {@code ref} names. */
    public record Pack(String repo, String ref, String commit, String path) {
        public Pack {
            path = path == null ? "" : trimSlashes(path);
        }

        String prefix() { return path.isEmpty() ? "" : path + "/"; }

        public String describe() {
            return repo + (path.isEmpty() ? "" : "/" + path) + " at " + ref;
        }
    }

    /**
     * Each named skill of {@code pack}, or every one under its path when {@code names} is empty,
     * staged as a directory on the host, by skill name.
     */
    public Map<String, Path> fetch(Pack pack, Collection<String> names, HttpClient http)
            throws IOException, InterruptedException {
        for (var problem : List.of(String.valueOf(repoProblem(pack.repo())), String.valueOf(commitProblem(pack.commit())),
                String.valueOf(pathProblem(pack.path())))) {
            if (!problem.equals("null")) throw new IOException("Invalid skill pack " + pack.describe() + ": " + problem);
        }
        for (var name : names) {
            // A leading dot is refused too: the cache keeps its own marker files beside the skills.
            if (pathProblem(name) != null || name.contains("/") || name.startsWith(".")) {
                throw new IOException("Invalid skill name '" + name + "' in " + pack.describe());
            }
        }
        var root = cacheDir.resolve(pack.repo()).resolve(pack.commit());
        if (!pack.path().isEmpty()) root = root.resolve(pack.path());

        var cached = cached(root, names);
        if (cached != null) return cached;

        requireTagNamesCommit(pack, http);
        var tree = readTree(pack, http);
        var wanted = names.isEmpty() ? skillNames(tree, pack) : new TreeSet<>(names);
        for (var name : wanted) {
            // A discovered name becomes a host path, a container path and a line of the ALL marker.
            if (pathProblem(name) != null || name.startsWith(".")) {
                throw new IOException("Skill directory '" + name + "' in " + pack.describe() + " has an unsafe name");
            }
        }
        if (wanted.isEmpty()) {
            throw new IOException("No skills found in " + pack.describe() + ": no <name>/SKILL.md under '"
                    + (pack.path().isEmpty() ? "/" : pack.path()) + "'");
        }
        var budget = new long[]{MAX_PACK_BYTES};
        var result = new LinkedHashMap<String, Path>();
        for (var name : wanted) {
            var target = root.resolve(name);
            if (!isComplete(target)) stage(pack, name, tree, target, http, budget);
            result.put(name, target);
        }
        if (names.isEmpty()) Files.writeString(root.resolve(ALL_MARKER), String.join("\n", wanted) + "\n");
        return result;
    }

    /** The cached skills, or null when any of them is missing (or the full list was never fetched). */
    private static Map<String, Path> cached(Path root, Collection<String> names) throws IOException {
        Collection<String> wanted = names;
        if (names.isEmpty()) {
            var marker = root.resolve(ALL_MARKER);
            if (!Files.isRegularFile(marker)) return null;
            wanted = Files.readAllLines(marker).stream().filter(s -> !s.isBlank()).toList();
            if (wanted.isEmpty()) return null;
        }
        var result = new LinkedHashMap<String, Path>();
        for (var name : wanted) {
            var dir = root.resolve(name);
            if (!isComplete(dir)) return null;
            result.put(name, dir);
        }
        return result;
    }

    private static boolean isComplete(Path skillDir) {
        return Files.isRegularFile(skillDir.resolve("SKILL.md"));
    }

    /** Fails unless {@code pack.ref()} is a tag that names {@code pack.commit()}. */
    private void requireTagNamesCommit(Pack pack, HttpClient http) throws IOException, InterruptedException {
        var tag = pack.ref().startsWith("refs/tags/") ? pack.ref().substring("refs/tags/".length()) : pack.ref();
        var ref = getJson(apiBase + "/repos/" + pack.repo() + "/git/ref/tags/" + encodePath(tag), http,
                "tag '" + tag + "' of " + pack.repo());
        var object = ref.path("object");
        for (int hop = 0; "tag".equals(object.pathText("type", "")) && hop < MAX_TAG_HOPS; hop++) {
            var annotated = getJson(apiBase + "/repos/" + pack.repo() + "/git/tags/" + object.pathText("sha", ""),
                    http, "tag '" + tag + "' of " + pack.repo());
            object = annotated.path("object");
        }
        var named = object.pathText("sha", "");
        if (!"commit".equals(object.pathText("type", "")) || !named.equals(pack.commit())) {
            throw new IOException("Tag '" + tag + "' of " + pack.repo() + " names commit "
                    + (named.isEmpty() ? "(none)" : named) + ", not the pinned " + pack.commit()
                    + ". Read the tag's commit from the publisher with"
                    + " git ls-remote https://github.com/" + pack.repo() + " 'refs/tags/" + tag + "^{}'"
                    + " (or the plain line for a lightweight tag) and review the change before re-pinning.");
        }
    }

    private record Entry(String path, String mode, String type, String sha, long size) {}

    private List<Entry> readTree(Pack pack, HttpClient http) throws IOException, InterruptedException {
        var json = getJson(apiBase + "/repos/" + pack.repo() + "/git/trees/" + pack.commit() + "?recursive=1",
                http, "the tree of " + pack.repo() + " at " + pack.commit());
        if (json.path("truncated").asBoolean(false)) {
            throw new IOException("GitHub truncated the tree of " + pack.repo() + " at " + pack.commit()
                    + "; isx cannot read a repository that large for skills");
        }
        var entries = new ArrayList<Entry>();
        for (var node : json.path("tree").asList()) {
            entries.add(new Entry(node.pathText("path", ""), node.pathText("mode", ""),
                    node.pathText("type", ""), node.pathText("sha", ""), node.path("size").asLong(0)));
        }
        return entries;
    }

    /** Every {@code <name>} with a {@code <path>/<name>/SKILL.md} blob. */
    private static TreeSet<String> skillNames(List<Entry> tree, Pack pack) {
        var names = new TreeSet<String>();
        var prefix = pack.prefix();
        for (var e : tree) {
            if (!e.path().startsWith(prefix) || !"blob".equals(e.type())) continue;
            var rest = e.path().substring(prefix.length());
            var slash = rest.indexOf('/');
            if (slash > 0 && rest.substring(slash + 1).equals("SKILL.md")) names.add(rest.substring(0, slash));
        }
        return names;
    }

    private void stage(Pack pack, String name, List<Entry> tree, Path target, HttpClient http, long[] budget)
            throws IOException, InterruptedException {
        var skillPrefix = pack.prefix() + name + "/";
        var files = new ArrayList<Entry>();
        boolean hasSkillMd = false;
        for (var e : tree) {
            if (!e.path().startsWith(skillPrefix)) continue;
            var relative = e.path().substring(skillPrefix.length());
            if (pathProblem(relative) != null) {
                throw new IOException("Skill '" + name + "' in " + pack.describe() + " holds an unsafe path: " + e.path());
            }
            switch (e.type()) {
                case "tree" -> { continue; }
                case "blob" -> {
                    if (!e.mode().equals("100644") && !e.mode().equals("100755")) {
                        throw new IOException("Skill '" + name + "' in " + pack.describe() + " holds " + e.path()
                                + (e.mode().equals("120000") ? ", a symlink" : " with mode " + e.mode())
                                + "; isx installs only regular files");
                    }
                }
                default -> throw new IOException("Skill '" + name + "' in " + pack.describe() + " holds " + e.path()
                        + ", a " + e.type() + "; isx installs only regular files");
            }
            if (e.size() > MAX_FILE_BYTES || (budget[0] -= e.size()) < 0) {
                throw new IOException("Skill '" + name + "' in " + pack.describe() + " is too large to install ("
                        + e.path() + ")");
            }
            if (relative.equals("SKILL.md")) hasSkillMd = true;
            files.add(e);
        }
        if (!hasSkillMd) {
            throw new IOException("Skill '" + name + "' not found in " + pack.describe() + ": no "
                    + skillPrefix + "SKILL.md at commit " + pack.commit());
        }

        Files.createDirectories(target.getParent());
        var staging = Files.createTempDirectory(target.getParent(), ".staging-" + name + "-");
        try {
            // A temporary directory is 0700, and it becomes the skill's directory in the container.
            Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rwxr-xr-x"));
            for (var e : files) {
                var bytes = getBytes(rawBase + "/" + pack.repo() + "/" + pack.commit() + "/" + encodePath(e.path()), http,
                        e.path() + " of " + pack.repo(), e.size());
                if (!gitBlobId(bytes).equals(e.sha())) {
                    throw new IOException(e.path() + " of " + pack.repo() + " at " + pack.commit()
                            + " does not match the commit's tree");
                }
                var file = staging.resolve(e.path().substring(skillPrefix.length()));
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(
                        e.mode().equals("100755") ? "rwxr-xr-x" : "rw-r--r--"));
            }
            publish(staging, target);
        } finally {
            // Never masks why staging failed: a leftover staging directory is harmless.
            FileTrees.deleteQuietly(staging);
        }
    }

    /** Moves the staged directory into place; one that another build published first is kept. */
    private static void publish(Path staging, Path target) throws IOException {
        if (Files.exists(target) && !isComplete(target)) FileTrees.delete(target);
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            // Another build published the same commit's skill: same content.
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, target);
        } catch (IOException e) {
            if (!isComplete(target)) throw e;
        }
    }

    public static String gitBlobId(byte[] content) {
        try {
            var sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(sha1.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private JqValue getJson(String url, HttpClient http, String what) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json");
        // The host's token goes to GitHub's API alone, never to another base a test points at.
        var token = GITHUB_API.equals(apiBase) ? Environment.strippedEnv("GITHUB_TOKEN") : "";
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) throw new IOException("GitHub has no " + what);
        if (response.statusCode() != 200) {
            throw new IOException("Could not read " + what + " from GitHub (HTTP " + response.statusCode() + ")");
        }
        return Jjq.parseForeign(response.body(), "GitHub's answer for " + what);
    }

    /** The body of {@code url}, read no further than {@code size} bytes, the size the commit's tree gives the file. */
    private static byte[] getBytes(String url, HttpClient http, String what, long size)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Could not download " + what + " (HTTP " + response.statusCode() + ")");
            }
            var bytes = body.readNBytes((int) Math.min(size, MAX_FILE_BYTES) + 1);
            if (bytes.length > size) throw new IOException(what + " is larger than the commit's tree says");
            return bytes;
        }
    }


    private static String encodePath(String path) {
        var parts = path.split("/", -1);
        var out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('/');
            out.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private static String trimSlashes(String path) {
        int start = 0, end = path.length();
        while (start < end && path.charAt(start) == '/') start++;
        while (end > start && path.charAt(end - 1) == '/') end--;
        return path.substring(start, end);
    }
}
