package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.Environment;
import dev.incusspawn.command.BuildCommand.BuildFailedException;
import dev.incusspawn.command.BuildTools.ResolvedTool;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Installs the agent skills a template and its tools declare, fetched on the host. */
final class SkillInstaller {

    private SkillInstaller() {}

    /** Agent home directory inside the container, shared across agents. */
    private static final String AGENTS_DIR = "/home/agentuser/.agents";

    /** Global skills directory inside the container, shared across agents. */
    private static final String SKILLS_DIR = AGENTS_DIR + "/skills";

    /**
     * Install agent skills declared in the image definition.
     * Fetches SKILL.md files on the host and writes them directly into the container.
     * Deduplicates against skills already declared by ancestor images.
     */
    static void installSkills(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                       List<ResolvedTool> tools) {
        // A skill can be declared by the image or by a tool it installs. Tools carry the
        // procedures that drive them, so the skill travels with the tool into every
        // template using it. Dedupe: two sources can name the same skill.
        var resolvedSet = new LinkedHashSet<String>();
        for (var entry : collectEffectiveSkills(imageDef, defs)) {
            resolvedSet.add(resolveSkillOrFail(entry, imageDef.getSkills().getRepo(),
                    "image definition"));
        }
        for (var tool : tools) {
            // A reconfigureOnly tool was installed by an ancestor, so its skills came with
            // it: in buildFromParent they arrived with the CoW copy, in buildFromScratch
            // the ancestor's own installSkills ran earlier in the chain loop. Re-fetching
            // would make a parameter-only rebuild depend on the skill source still being
            // reachable. This mirrors collectEffectiveSkills subtracting ancestor skills.
            if (tool.reconfigureOnly()) continue;
            var toolSkills = tool.setup().skills();
            for (var entry : toolSkills.getList()) {
                resolvedSet.add(resolveSkillOrFail(entry, toolSkills.getRepo(),
                        "tool '" + tool.name() + "'"));
            }
        }
        if (resolvedSet.isEmpty()) return;
        var resolvedNames = new ArrayList<>(resolvedSet);

        try (var skillsGroup = BuildOutput.group("Skills", resolvedNames.size() + " to install")) {

            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            var cache = new dev.incusspawn.tool.SkillsCache();

            container.exec("mkdir", "-p", SKILLS_DIR);

            for (var resolved : resolvedNames) {
                BuildOutput.stepStart(resolved + "...");
                try {
                    var skills = fetchSkills(resolved, http, cache);
                    for (var skill : skills) {
                        var skillDir = SKILLS_DIR + "/" + skill.name();
                        container.exec("mkdir", "-p", skillDir);
                        container.writeFile(skillDir + "/SKILL.md", skill.content());
                    }
                    BuildOutput.stepDone();
                } catch (IOException | InterruptedException e) {
                    BuildOutput.stepBreak();
                    System.err.println("Error: Failed to fetch skill '" + resolved + "': " + e.getMessage());
                    throw new BuildFailedException();
                }
            }
        }
        // Fix ownership so agentuser owns the agents / skills directories
        container.exec("chown", "-R", "agentuser:agentuser", AGENTS_DIR);
        // Ensure .claude/skills points to the shared location if Claude Code is installed
        // (handles inherited-claude case where ClaudeSetup.linkSkillsDir didn't run)
        container.sh("[ ! -d /home/agentuser/.claude ] || [ -L /home/agentuser/.claude/skills ]"
                + " || { rm -rf /home/agentuser/.claude/skills"
                + " && ln -sfn " + SKILLS_DIR + " /home/agentuser/.claude/skills; }");
    }

    /** A fetched skill ready to be written into the container. */
    record SkillFile(String name, String content) {}

    /**
     * Fetch one or more SKILL.md files for the given resolved source.
     * GitHub skills are cached on the host at {@code ~/.cache/incus-spawn/skills/}.
     * Supports:
     * <ul>
     *   <li>{@code owner/repo@skill-name} — single skill from a GitHub repo</li>
     *   <li>{@code owner/repo} — all skills from a GitHub repo (via Trees API)</li>
     *   <li>{@code https://github.com/owner/repo} — same as owner/repo</li>
     *   <li>{@code ./local/path} or {@code /absolute/path} — local directory</li>
     * </ul>
     */
    static List<SkillFile> fetchSkills(String source, HttpClient http,
            dev.incusspawn.tool.SkillsCache cache)
            throws IOException, InterruptedException {
        // Local path
        if (source.startsWith("./") || source.startsWith("/")) {
            return fetchLocalSkills(Path.of(source));
        }

        // Normalise GitHub URL to owner/repo[@skill]
        var normalised = source;
        if (normalised.startsWith("https://github.com/")) {
            normalised = normalised.substring("https://github.com/".length()).replaceAll("\\.git$", "");
        }

        // owner/repo@skill-name
        var atIdx = normalised.indexOf('@');
        if (atIdx >= 0) {
            var ownerRepo = normalised.substring(0, atIdx);
            var skillName = normalised.substring(atIdx + 1);
            return List.of(new SkillFile(skillName, cache.fetchSkillMd(ownerRepo, skillName, http)));
        }

        // owner/repo — fetch all skills via Trees API
        return fetchAllGitHubSkills(normalised, http, cache);
    }

    private static List<SkillFile> fetchAllGitHubSkills(String ownerRepo, HttpClient http,
            dev.incusspawn.tool.SkillsCache cache)
            throws IOException, InterruptedException {
        // Use GitHub Trees API to find all SKILL.md files
        for (var branch : List.of("main", "master")) {
            var treeUrl = "https://api.github.com/repos/" + ownerRepo + "/git/trees/"
                    + branch + "?recursive=1";
            var token = Environment.strippedEnv("GITHUB_TOKEN");
            var reqBuilder = HttpRequest.newBuilder(URI.create(treeUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json");
            if (!token.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + token);
            }
            var response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) continue;

            var mapper = new ObjectMapper();
            var tree = mapper.readTree(response.body()).path("tree");
            var skills = new ArrayList<SkillFile>();
            for (var node : tree) {
                var path = node.path("path").asText();
                // Match <skill-name>/SKILL.md at the top level only
                if (path.matches("[^/]+/SKILL\\.md")) {
                    var skillName = path.substring(0, path.indexOf('/'));
                    skills.add(new SkillFile(skillName, cache.fetchSkillMd(ownerRepo, skillName, http)));
                }
            }
            if (!skills.isEmpty()) return skills;
        }
        throw new IOException("No SKILL.md files found in " + ownerRepo);
    }

    private static List<SkillFile> fetchLocalSkills(Path localPath) throws IOException {
        if (!Files.isDirectory(localPath)) {
            throw new IOException("Local skill path is not a directory: " + localPath);
        }
        // If there's a SKILL.md directly in this dir, treat it as a single skill
        var directSkill = localPath.resolve("SKILL.md");
        if (Files.exists(directSkill)) {
            return List.of(new SkillFile(localPath.getFileName().toString(),
                    Files.readString(directSkill)));
        }
        // Otherwise scan subdirectories for SKILL.md files
        var skills = new ArrayList<SkillFile>();
        try (var entries = Files.list(localPath)) {
            for (var entry : entries.toList()) {
                var skillMd = entry.resolve("SKILL.md");
                if (Files.isDirectory(entry) && Files.exists(skillMd)) {
                    skills.add(new SkillFile(entry.getFileName().toString(),
                            Files.readString(skillMd)));
                }
            }
        }
        if (skills.isEmpty()) {
            throw new IOException("No SKILL.md files found in " + localPath);
        }
        return skills;
    }

    /**
     * Collect skills declared in this image, minus any already declared by ancestor images.
     */
    static List<String> collectEffectiveSkills(ImageDef imageDef, Map<String, ImageDef> defs) {
        var skills = new LinkedHashSet<>(imageDef.getSkills().getList());
        if (skills.isEmpty()) return List.of();

        var ancestorSkills = new LinkedHashSet<String>();
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            ancestorSkills.addAll(ancestor.getSkills().getList());
        }
        skills.removeAll(ancestorSkills);
        return new ArrayList<>(skills);
    }

    /**
     * Resolve a skill entry to a fully-qualified source string.
     * <ul>
     *   <li>Contains {@code ://} or starts with {@code .} or {@code /} → local/URL, pass through</li>
     *   <li>Contains {@code /} → owner/repo or owner/repo@skill, pass through</li>
     *   <li>Plain name → prepend {@code skillsRepo@}; throws if no skillsRepo set</li>
     * </ul>
     */
    /**
     * Resolve one skill source, naming the declaring definition if a bare name can't be
     * resolved — otherwise the error sends you to the image YAML for a tool's typo.
     */
    private static String resolveSkillOrFail(String entry, String repo, String source) {
        try {
            return resolveSkillSource(entry, repo);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage() + " (declared by " + source + ")");
            System.err.println("Use the fully qualified form 'owner/repo@skill-name', or set 'skills.repo' in the " + source + ".");
            throw new BuildFailedException();
        }
    }

    static String resolveSkillSource(String skill, String skillsRepo) {
        if (skill.contains("://") || skill.startsWith(".") || skill.startsWith("/")) {
            return skill;
        }
        if (skill.contains("/")) {
            return skill;
        }
        if (skillsRepo == null || skillsRepo.isBlank()) {
            throw new IllegalArgumentException(
                    "Skill '" + skill + "' is a short name but no skills.repo is defined.");
        }
        return skillsRepo + "@" + skill;
    }
}
