package dev.incusspawn.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tool installation defined in YAML. Each definition declares
 * packages to install, shell commands to run, files to write, and
 * environment variables to export.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolDef {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private String name;
    private String description = "";
    private List<DownloadEntry> downloads = List.of();
    private List<String> packages = List.of();
    private List<String> run = List.of();
    @JsonProperty("run_as_user")
    private List<String> runAsUser = List.of();
    private List<FileEntry> files = List.of();
    private List<String> env = List.of();
    private List<String> requires = List.of();
    private String verify;
    private List<ActionEntry> actions = List.of();

    private transient volatile String cachedFingerprint;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<DownloadEntry> getDownloads() { return downloads; }
    public void setDownloads(List<DownloadEntry> downloads) { this.downloads = downloads; }
    public List<String> getPackages() { return packages; }
    public void setPackages(List<String> packages) { this.packages = packages; }
    public List<String> getRun() { return run; }
    public void setRun(List<String> run) { this.run = run; }
    public List<String> getRunAsUser() { return runAsUser; }
    public void setRunAsUser(List<String> runAsUser) { this.runAsUser = runAsUser; }
    public List<FileEntry> getFiles() { return files; }
    public void setFiles(List<FileEntry> files) { this.files = files; }
    public List<String> getEnv() { return env; }
    public void setEnv(List<String> env) { this.env = env; }
    public List<String> getRequires() { return requires; }
    public void setRequires(List<String> requires) { this.requires = requires; }
    public String getVerify() { return verify; }
    public void setVerify(String verify) { this.verify = verify; }
    public List<ActionEntry> getActions() { return actions; }
    public void setActions(List<ActionEntry> actions) { this.actions = actions; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DownloadEntry {
        private String url;
        private String sha256;
        private String extract;
        private Map<String, String> links = Map.of();

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getSha256() { return sha256; }
        public void setSha256(String sha256) { this.sha256 = sha256; }
        public String getExtract() { return extract; }
        public void setExtract(String extract) { this.extract = extract; }
        public Map<String, String> getLinks() { return links; }
        public void setLinks(Map<String, String> links) { this.links = links; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileEntry {
        private String path;
        private String content;
        private String owner;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ActionEntry {
        private String label;
        private String type;
        @JsonProperty("requires_running")
        private boolean requiresRunning = true;
        private String expand;
        private String url;
        private String command;
        private String text;
        @JsonProperty("auto_return")
        private boolean autoReturn = false;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isRequiresRunning() { return requiresRunning; }
        public void setRequiresRunning(boolean requiresRunning) { this.requiresRunning = requiresRunning; }
        public String getExpand() { return expand; }
        public void setExpand(String expand) { this.expand = expand; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public boolean isAutoReturn() { return autoReturn; }
        public void setAutoReturn(boolean autoReturn) { this.autoReturn = autoReturn; }
    }

    public String contentFingerprint() {
        var result = cachedFingerprint;
        if (result != null) return result;
        var sb = new StringBuilder();
        for (var d : downloads) {
            sb.append("dl=").append(d.url).append(',').append(d.sha256)
                    .append(',').append(d.extract);
            new TreeMap<>(d.links).forEach((k, v) -> sb.append(',').append(k).append('=').append(v));
            sb.append('\n');
        }
        packages.stream().sorted().forEach(p -> sb.append("pkg=").append(p).append('\n'));
        run.forEach(r -> sb.append("run=").append(r).append('\n'));
        runAsUser.forEach(r -> sb.append("run_as_user=").append(r).append('\n'));
        for (var f : files) {
            sb.append("file=").append(f.path).append(',').append(f.content)
                    .append(',').append(f.owner).append('\n');
        }
        env.stream().sorted().forEach(e -> sb.append("env=").append(e).append('\n'));
        requires.stream().sorted().forEach(r -> sb.append("requires=").append(r).append('\n'));
        if (verify != null) sb.append("verify=").append(verify).append('\n');
        result = sha256hex(sb.toString());
        cachedFingerprint = result;
        return result;
    }

    /**
     * Compute composite fingerprints that incorporate transitive deps.
     * Each tool's entry in the result includes the fingerprints of all its
     * transitive dependencies, so a change in any dep propagates upward.
     *
     * @param rawFingerprints  tool name → own contentFingerprint()
     * @param depMap           tool name → list of required tool names
     * @return tool name → composite fingerprint (includes dep tree)
     */
    public static Map<String, String> compositeFingerprints(
            Map<String, String> rawFingerprints, Map<String, java.util.List<String>> depMap) {
        var cache = new TreeMap<String, String>();
        for (var name : rawFingerprints.keySet()) {
            resolveComposite(name, rawFingerprints, depMap, cache, new java.util.LinkedHashSet<>());
        }
        return cache;
    }

    private static String resolveComposite(String name, Map<String, String> rawFps,
                                            Map<String, java.util.List<String>> depMap,
                                            Map<String, String> cache,
                                            java.util.Set<String> resolving) {
        if (cache.containsKey(name)) return cache.get(name);
        if (!resolving.add(name)) return rawFps.getOrDefault(name, "");
        var sb = new StringBuilder(rawFps.getOrDefault(name, ""));
        var deps = depMap.getOrDefault(name, java.util.List.of());
        for (var dep : deps.stream().sorted().toList()) {
            sb.append("+dep=").append(dep).append(':')
                    .append(resolveComposite(dep, rawFps, depMap, cache, resolving));
        }
        var result = deps.isEmpty() ? sb.toString() : sha256hex(sb.toString());
        cache.put(name, result);
        resolving.remove(name);
        return result;
    }

    public static String sha256hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    static ToolDef loadFromStream(InputStream is) throws IOException {
        return YAML.readValue(is, ToolDef.class);
    }
}
