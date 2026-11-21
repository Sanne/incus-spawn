package dev.incusspawn.command;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.Environment;
import dev.incusspawn.command.BuildCommand.BuildFailedException;
import dev.incusspawn.command.BuildTools.ResolvedTool;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.tool.AntigravitySetup;
import dev.incusspawn.tool.PinnedSkills;
import dev.incusspawn.util.SystemTrust;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Installs the agent skills a template and its tools declare, fetched on the host. */
final class SkillInstaller {
    private static final Pattern GIT_SUFFIX = Pattern.compile("\\.git$");
    private static final Pattern TOP_LEVEL_SKILL = Pattern.compile("[^/]+/SKILL\\.md");

    private SkillInstaller() {}

    /** Agent home directory inside the container, shared across agents. */
    private static final String AGENTS_DIR = "/home/agentuser/.agents";

    /** Global skills directory inside the container, shared across agents. */
    private static final String SKILLS_DIR = AGENTS_DIR + "/skills";

    /**
     * Install agent skills declared in the image definition.
     * Fetches skills on the host and writes them directly into the container.
     * Deduplicates against skills already declared by ancestor images.
     * {@code projectTools} has no default: a caller that left it out would read a project-local
     * tool's local skill source from anywhere on the host.
     */
    static void installSkills(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                       List<ResolvedTool> tools, ProjectTools projectTools) {
        installSkills(container, imageDef, defs, tools, projectTools, new PinnedSkills());
    }

    /** The project-local tools of a build ({@code .incus-spawn/tools/}) and the project they come from. */
    record ProjectTools(Set<String> names, Path root) {
        static final ProjectTools NONE = new ProjectTools(Set.of(), null);

        static ProjectTools of(dev.incusspawn.tool.ToolDefLoader loader) {
            var root = loader.projectRoot();
            return root == null ? NONE : new ProjectTools(loader.projectLocalToolNames(), root);
        }
    }

    static void installSkills(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                       List<ResolvedTool> tools, ProjectTools projectTools, PinnedSkills pinnedSkills) {
        // A skill can be declared by the image or by a tool it installs. Tools carry the
        // procedures that drive them, so the skill travels with the tool into every
        // template using it. Dedupe: two sources can name the same skill.
        var resolvedSet = new LinkedHashSet<String>();
        // A pinned pack's bare names, or ALL for every skill under its path (#2354).
        var packs = new LinkedHashMap<PinnedSkills.Pack, LinkedHashSet<String>>();
        var imageSkills = imageDef.getSkills();
        for (var entry : collectEffectiveSkills(imageDef, defs)) {
            if (imageSkills.isPinned() && isBare(entry)) {
                packs.computeIfAbsent(pack(imageSkills), k -> new LinkedHashSet<>()).add(entry);
            } else {
                resolvedSet.add(resolveSkillOrFail(confinedToProject(imageDef, entry),
                        imageSkills.getRepo(), "image definition"));
            }
        }
        for (var tool : tools) {
            // A reconfigureOnly tool was installed by an ancestor, so its skills came with
            // it: in buildFromParent they arrived with the CoW copy, in buildFromScratch
            // the ancestor's own installSkills ran earlier in the chain loop. Re-fetching
            // would make a parameter-only rebuild depend on the skill source still being
            // reachable. This mirrors collectEffectiveSkills subtracting ancestor skills.
            if (tool.reconfigureOnly()) continue;
            var toolSkills = tool.setup().skills();
            for (var entry : declared(toolSkills)) {
                if (toolSkills.isPinned() && isBare(entry)) {
                    packs.computeIfAbsent(pack(toolSkills), k -> new LinkedHashSet<>()).add(entry);
                } else {
                    resolvedSet.add(resolveSkillOrFail(confinedToProject(tool.name(), projectTools, entry),
                            toolSkills.getRepo(), "tool '" + tool.name() + "'"));
                }
            }
        }
        if (resolvedSet.isEmpty() && packs.isEmpty()) return;
        var resolvedNames = new ArrayList<>(resolvedSet);

        try (var skillsGroup = BuildOutput.group("Skills", (resolvedNames.size() + packs.size()) + " to install")) {

            var http = SystemTrust.httpClient().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            var cache = new dev.incusspawn.tool.SkillsCache();

            container.exec("mkdir", "-p", SKILLS_DIR);

            for (var resolved : resolvedNames) {
                BuildOutput.stepStart(resolved);
                try {
                    var skills = fetchSkills(resolved, http, cache);
                    for (var skill : skills) {
                        var skillDir = SKILLS_DIR + "/" + skill.name();
                        // Replace the directory, so no file of a parent's pinned release is left beside this SKILL.md.
                        container.exec("sh", "-c", "rm -rf -- \"$1\" && mkdir -p -- \"$1\"", "sh", skillDir);
                        container.writeFile(skillDir + "/SKILL.md", skill.content());
                    }
                    BuildOutput.stepDone();
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    BuildOutput.stepBreak();
                    BuildOutput.error("could not fetch skill '" + resolved + "': " + BaseCommand.failure(e));
                    throw new BuildFailedException();
                }
            }

            // Pinned packs go last: each skill replaces whatever an unpinned source wrote under its name.
            var installedFrom = new LinkedHashMap<String, PinnedSkills.Pack>();
            for (var pack : packs.entrySet()) {
                installPack(container, pack.getKey(), pack.getValue(), installedFrom, pinnedSkills, http);
            }
        }
        // Fix ownership so agentuser owns the agents / skills directories
        container.exec("chown", "-R", "agentuser:agentuser", AGENTS_DIR);
        // Ensure .claude/skills and agy's ~/.gemini/config/skills point to the shared location
        // when Claude Code or Antigravity CLI is installed (handles an agent inherited from an
        // image built before its setup linked the directory), in one exec.
        container.sh("{ [ ! -d /home/agentuser/.claude ] || [ -L /home/agentuser/.claude/skills ]"
                + " || { rm -rf /home/agentuser/.claude/skills"
                + " && ln -sfn " + SKILLS_DIR + " /home/agentuser/.claude/skills; }; }; "
                + "! " + AntigravitySetup.INSTALLED_TEST + " || { " + AntigravitySetup.linkSkillsScript()
                + " && chown -h agentuser:agentuser " + AntigravitySetup.SKILLS_LINK + "; }");
    }

    /** The name a pinned pack's empty {@code list} stands for: every skill under its path. */
    private static final String ALL = "";

    private static PinnedSkills.Pack pack(ImageDef.SkillsDef skills) {
        return new PinnedSkills.Pack(skills.getRepo(), skills.getRef(), skills.getCommit(), skills.getPath());
    }

    /** A pack's entries: its list, or {@link #ALL} for a pinned pack that names none. */
    private static List<String> declared(ImageDef.SkillsDef skills) {
        return skills.isPinned() && skills.getList().isEmpty() ? List.of(ALL) : skills.getList();
    }

    /** Whether an entry is a bare skill name, which resolves against its pack's {@code repo}. */
    private static boolean isBare(String entry) {
        return !entry.contains("://") && !entry.startsWith(".") && !entry.startsWith("/") && !entry.contains("/");
    }

    /** What makes two declarations the same skill: a pinned bare name is its pack's commit and path too. */
    private static String identity(ImageDef.SkillsDef skills, String entry) {
        if (!skills.isPinned() || !isBare(entry)) return entry;
        return "pinned:" + skills.getRepo() + "@" + skills.getCommit() + "/"
                + (skills.getPath() == null ? "" : skills.getPath()) + ":" + entry;
    }

    /**
     * Installs each skill of a pinned pack as its whole directory, after removing what an
     * earlier pin left there, so a file the new release dropped does not linger.
     */
    private static void installPack(Container container, PinnedSkills.Pack pack, Set<String> names,
            Map<String, PinnedSkills.Pack> installedFrom, PinnedSkills pinnedSkills, HttpClient http) {
        BuildOutput.stepStart(pack.describe());
        Map<String, Path> fetched;
        try {
            fetched = pinnedSkills.fetch(pack, names.contains(ALL) ? List.of() : names, http);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            BuildOutput.stepBreak();
            BuildOutput.error("could not fetch skills from " + pack.describe() + ": " + BaseCommand.failure(e));
            throw new BuildFailedException();
        }
        // A name merged with an install-everything declaration of the same pack is still required.
        for (var name : names) {
            if (!name.equals(ALL) && !fetched.containsKey(name)) {
                BuildOutput.stepBreak();
                BuildOutput.error("could not fetch skill '" + name + "' from " + pack.describe()
                        + ": no " + name + "/SKILL.md at commit " + pack.commit());
                throw new BuildFailedException();
            }
        }
        var toPush = new LinkedHashMap<String, Path>();
        for (var skill : fetched.entrySet()) {
            var earlier = installedFrom.putIfAbsent(skill.getKey(), pack);
            if (earlier != null) {
                if (sameContent(earlier, pack)) continue;
                BuildOutput.stepBreak();
                BuildOutput.error("skill '" + skill.getKey() + "' is declared from both " + earlier.describe()
                        + " (" + earlier.commit() + ") and " + pack.describe() + " (" + pack.commit() + ")",
                        "Pin both declarations to the same release, or drop one.");
                throw new BuildFailedException();
            }
            toPush.put(SKILLS_DIR + "/" + skill.getKey(), skill.getValue());
        }
        if (!toPush.isEmpty()) {
            // One exec removes every directory an earlier pin left, rather than one per skill.
            var rm = new ArrayList<String>(List.of("rm", "-rf", "--"));
            rm.addAll(toPush.keySet());
            container.exec(rm.toArray(String[]::new)).assertSuccess("Failed to replace skills in " + SKILLS_DIR);
            for (var dir : toPush.values()) container.filePushRecursive(dir.toString(), SKILLS_DIR);
        }
        BuildOutput.stepDone(String.join(", ", fetched.keySet()));
    }

    private static boolean sameContent(PinnedSkills.Pack a, PinnedSkills.Pack b) {
        return a.repo().equals(b.repo()) && a.commit().equals(b.commit()) && a.path().equals(b.path());
    }

    /**
     * A local skill source of a project-local template, as a real path inside its project (#2354):
     * a cloned repository must not copy the host's files into a container, as for host-resources.
     */
    private static String confinedToProject(ImageDef def, String entry) {
        if (def.getProjectRoot() == null || !isLocal(entry)) return entry;
        try {
            return HostResourceSetup.confinedSkillSource(def, entry);
        } catch (HostResourceSetup.HostPathOutsideProjectException e) {
            BuildOutput.error(e.getMessage());
            throw new BuildFailedException();
        }
    }

    /** The same for a project-local tool's local skill source: a cloned repository ships it too. */
    private static String confinedToProject(String tool, ProjectTools projectTools, String entry) {
        if (!projectTools.names().contains(tool) || !isLocal(entry)) return entry;
        try {
            return HostResourceSetup.confinedSkillSource(tool, projectTools.root(), entry);
        } catch (HostResourceSetup.HostPathOutsideProjectException e) {
            BuildOutput.error(e.getMessage());
            throw new BuildFailedException();
        }
    }

    private static boolean isLocal(String entry) {
        return entry.startsWith("./") || entry.startsWith("/");
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
        if (isLocal(source)) {
            return fetchLocalSkills(Path.of(source));
        }

        // Normalise GitHub URL to owner/repo[@skill]
        var normalised = source;
        if (normalised.startsWith("https://github.com/")) {
            normalised = GIT_SUFFIX.matcher(normalised.substring("https://github.com/".length())).replaceAll("");
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
            var response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) continue;

            var tree = Jjq.parseForeign(response.body(), "GitHub's tree of " + ownerRepo).getField("tree");
            var skills = new ArrayList<SkillFile>();
            for (var node : tree.asList()) {
                var path = node.pathText("path", "");
                // Match <skill-name>/SKILL.md at the top level only
                if (TOP_LEVEL_SKILL.matcher(path).matches()) {
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
     * A pinned bare name counts as already declared only at the same commit and path, so a
     * child that re-pins a skill installs the new release.
     */
    static List<String> collectEffectiveSkills(ImageDef imageDef, Map<String, ImageDef> defs) {
        var own = imageDef.getSkills();
        var entries = declared(own);
        if (entries.isEmpty()) return List.of();

        var ancestorSkills = new HashSet<String>();
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            for (var entry : declared(ancestor.getSkills())) {
                ancestorSkills.add(identity(ancestor.getSkills(), entry));
            }
        }
        var skills = new LinkedHashSet<String>();
        for (var entry : entries) {
            if (!ancestorSkills.contains(identity(own, entry))) skills.add(entry);
        }
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
            BuildOutput.error(e.getMessage() + " (declared by " + source + ")",
                    "Use the fully qualified form 'owner/repo@skill-name', or set 'skills.repo' in the " + source + ".");
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
