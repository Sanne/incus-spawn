package dev.incusspawn.mcp;

import dev.incusspawn.command.TranscriptGate;
import dev.incusspawn.config.McpConfig;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code isx mcp} tells a model, held to the mechanical rules of
 * .claude/rules/interface-text.md (#2542): the server's instructions, its prompts, and every
 * tool's description with those of its arguments and results. A model reads them, and a person
 * sees them quoted, so they keep to the vocabulary of every other interface text and negate the
 * verb rather than the noun.
 */
class McpToolTextGateTest {

    /** Words of the vocabulary's "Never" column, and the proxy's old name, as in {@code HelpTextGateTest}. */
    private static final Pattern NEVER = Pattern.compile("(?i)\\b(machines?|box|boxes|MITM)\\b");
    /** "container" only where it contrasts with a VM or Docker's (writing.md "One word per concept"). */
    private static final Pattern CONTAINER = Pattern.compile("(?i)\\bcontainers?\\b");
    private static final Pattern CONTRASTED = Pattern.compile("(?i)\\b(VMs?|Docker)\\b");
    /** A literal the model passes back, such as 'none' or '-': a value, not a negation. */
    private static final Pattern LITERAL = Pattern.compile("(?<!\\w)'[^']*'(?!\\w)");

    @Test
    void everyTextAModelReadsKeepsToTheVocabularyAndNegatesTheVerb() {
        var faults = new ArrayList<String>();
        texts().forEach((where, text) -> {
            if (NEVER.matcher(text).find()) faults.add(where + ": a word interface text never uses: " + text);
            if (CONTAINER.matcher(text).find() && !CONTRASTED.matcher(text).find())
                faults.add(where + ": 'container' where no VM or Docker is contrasted: " + text);
            // Split where TranscriptGate reads a sentence start, so a "nothing" after ":" or "--" is still read
            for (var sentence : text.split("(?<=[.;?!])\\s+|\\n+")) {
                if (TranscriptGate.negatesANoun(LITERAL.matcher(sentence).replaceAll("x")))
                    faults.add(where + ": a negated noun, where the verb should be negated: " + sentence);
            }
        });
        assertEquals(List.of(), faults, String.join("\n", faults));
    }

    /** The gate reaches arguments, results and prompts, not only the tools' own lines. */
    @Test
    void theGateReadsEveryPart() {
        var texts = texts();
        assertTrue(texts.containsKey("instructions"), texts.keySet().toString());
        assertTrue(texts.containsKey("prompt coordinate"), texts.keySet().toString());
        assertTrue(texts.containsKey("delegate.idempotency_key"), texts.keySet().toString());
        assertTrue(texts.keySet().stream().anyMatch(k -> k.startsWith("list_templates -> ")), texts.keySet().toString());
    }

    /** Each text, keyed by where a model reads it: {@code exec}, {@code exec.cwd}, {@code exec -> exit_code}. */
    static Map<String, String> texts() {
        var texts = new LinkedHashMap<String, String>();
        texts.put("instructions", McpMain.INSTRUCTIONS);
        for (var prompt : McpPrompt.ALL) {
            texts.put("prompt " + prompt.name(), prompt.description());
            texts.put("prompt " + prompt.name() + " text", prompt.text());
        }
        var backend = new FakeBackend().template("tpl-java", true, "claude");
        var config = new McpConfig();
        var session = new McpSession(new SessionId(1, 1), "alice", 1, "/work", backend, () -> config, s -> false);
        var tools = new McpTools(session, backend, new TemplatePolicy(backend, () -> config),
                new Tasks(session, backend, () -> config)).all();
        for (var tool : tools) {
            texts.put(tool.name(), tool.description());
            descriptions(tool.name() + ".", tool.inputSchema(), texts);
            descriptions(tool.name() + " -> ", tool.outputSchema(), texts);
        }
        return texts;
    }

    /** Every {@code description} under {@code schema}, keyed by the property path that holds it. */
    private static void descriptions(String prefix, JqValue schema, Map<String, String> texts) {
        if (schema instanceof JqObject object) {
            for (var e : object.entries()) {
                var value = e.getValue();
                if (e.getKey().equals("description") && value.isString()) {
                    // A second description at the same path is read too, never put in place of the first
                    texts.merge(prefix.replaceAll("(\\.| -> )$", ""), value.asText(), (a, b) -> a + "\n" + b);
                } else if (e.getKey().equals("properties") && value instanceof JqObject properties) {
                    for (var p : properties.entries()) descriptions(prefix + p.getKey() + ".", p.getValue(), texts);
                } else {
                    descriptions(prefix, value, texts);
                }
            }
        } else if (schema != null && schema.isArray()) {
            for (var v : schema.asList()) descriptions(prefix, v, texts);
        }
    }
}
