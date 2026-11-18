package dev.incusspawn.config;

import dev.incusspawn.config.ImageDef.SkillsDef;
import dev.incusspawn.tool.PinnedSkills;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.ValueConverter;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 (incus-spawn-jjq-migration): {@code ValueConverter} for the
 * {@code skills} union — either a list shorthand or an object with
 * {@code repo}/{@code list} (and a pin: {@code ref}/{@code commit}/{@code path}) — mirroring {@code SkillsDef.Deserializer}.
 */
public class SkillsDefConverter implements ValueConverter<SkillsDef> {

    @Override
    public SkillsDef fromJqValue(JqValue value) {
        if (value == null || value.isNull()) return SkillsDef.EMPTY;
        if (value.isArray()) {
            var items = new ArrayList<String>();
            for (var entry : value.asList()) {
                items.add(Jjq.text(entry));
            }
            return new SkillsDef(null, items);
        }
        if (value.isObject()) {
            String repo = null;
            String ref = null;
            String commit = null;
            String path = null;
            List<String> list = List.of();
            var unknown = new ArrayList<String>();
            for (var field : value.asMap().entrySet()) {
                switch (field.getKey()) {
                    case "repo" -> repo = field.getValue().isNull() ? null : field.getValue().asText();
                    case "ref" -> ref = field.getValue().isNull() ? null : Jjq.text(field.getValue());
                    case "commit" -> commit = field.getValue().isNull() ? null : Jjq.text(field.getValue());
                    case "path" -> path = field.getValue().isNull() ? null : Jjq.text(field.getValue());
                    case "list" -> {
                        if (!field.getValue().isArray()) continue;
                        var items = new ArrayList<String>();
                        for (var entry : field.getValue().asList()) {
                            items.add(Jjq.text(entry));
                        }
                        list = items;
                    }
                    default -> unknown.add(field.getKey());
                }
            }
            var problem = pinProblem(repo, ref, commit, path);
            if (problem != null) throw new JqMapperException("skills: " + problem);
            var skills = new SkillsDef(repo, list, ref, commit, path);
            unknown.forEach(skills::addUnknownKey);
            return skills;
        }
        throw new JqMapperException(
                "Expected a skill list or a mapping with 'repo'/'list' for skills, got " + describe(value));
    }

    /**
     * Why a pinned skill pack is declared wrong, or null (#2354). A pin is the tag and the commit
     * it names, together, so a moved tag fails the build rather than changing what it installs.
     */
    static String pinProblem(String repo, String ref, String commit, String path) {
        if (ref == null && commit == null && path == null) return null;
        if (ref == null || ref.isBlank()) {
            return "'commit' and 'path' take a 'ref', the tag the skills are read at";
        }
        if (commit == null) {
            return "'ref' needs 'commit', the commit ID the tag names (git ls-remote <url> 'refs/tags/<tag>^{}')";
        }
        var repoProblem = PinnedSkills.repoProblem(repo);
        if (repoProblem != null) return "'repo' " + repoProblem + " with a 'ref'";
        var commitProblem = PinnedSkills.commitProblem(commit);
        if (commitProblem != null) return "'commit' " + commitProblem;
        var pathProblem = PinnedSkills.pathProblem(path);
        if (pathProblem != null) return "'path' " + pathProblem;
        return null;
    }

    @Override
    public JqValue toJqValue(SkillsDef value) {
        if (value == null) return JqObject.builder(0).build();
        // Bean shape, mirroring Jackson's default serialization of SkillsDef.
        var items = JqArray.arrayBuilder();
        for (var skill : value.getList()) {
            items.add(skill);
        }
        var obj = JqObject.builder(5);
        if (value.getRepo() != null) {
            obj.put("repo", value.getRepo());
        }
        if (value.getRef() != null) obj.put("ref", value.getRef());
        if (value.getCommit() != null) obj.put("commit", value.getCommit());
        if (value.getPath() != null) obj.put("path", value.getPath());
        obj.put("list", items.build());
        return obj.build();
    }

    private static String describe(JqValue value) {
        if (value.isArray()) return "a list";
        if (value.isObject()) return "a mapping";
        if (value.isNull()) return "an empty value";
        return "a single value";
    }
}
