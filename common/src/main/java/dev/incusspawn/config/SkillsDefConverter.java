package dev.incusspawn.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import dev.incusspawn.config.ImageDef.SkillsDef;
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
 * {@code repo}/{@code list} — mirroring {@code SkillsDef.Deserializer}.
 */
@RegisterForReflection
public class SkillsDefConverter implements ValueConverter<SkillsDef> {

    @Override
    public SkillsDef fromJqValue(JqValue value) {
        if (value == null || value.isNull()) return SkillsDef.EMPTY;
        if (value.isArray()) {
            var items = new ArrayList<String>();
            for (var entry : value.asList()) {
                items.add(entry.asText());
            }
            return new SkillsDef(null, items);
        }
        if (value.isObject()) {
            String repo = null;
            List<String> list = List.of();
            for (var field : value.asMap().entrySet()) {
                switch (field.getKey()) {
                    case "repo" -> repo = field.getValue().isNull() ? null : field.getValue().asText();
                    case "list" -> {
                        if (!field.getValue().isArray()) continue;
                        var items = new ArrayList<String>();
                        for (var entry : field.getValue().asList()) {
                            items.add(entry.asText());
                        }
                        list = items;
                    }
                    default -> { /* ignoreUnknown, like Jackson */ }
                }
            }
            return new SkillsDef(repo, list);
        }
        throw new JqMapperException(
                "Expected a skill list or a mapping with 'repo'/'list' for skills, got " + describe(value));
    }

    @Override
    public JqValue toJqValue(SkillsDef value) {
        if (value == null) return JqObject.builder(0).build();
        // Bean shape, mirroring Jackson's default serialization of SkillsDef.
        var items = JqArray.arrayBuilder();
        for (var skill : value.getList()) {
            items.add(skill);
        }
        var obj = JqObject.builder(2);
        if (value.getRepo() != null) {
            obj.put("repo", value.getRepo());
        }
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
