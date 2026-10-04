package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a model a coordinator chose for a delegate can be used by the instance's credential,
 * asked before the task starts, so a model the account cannot reach fails the call rather than
 * the task (#1012). It asks the way the task would: a one-turn, tool-less {@code claude -p} on
 * that model, in the instance and through its proxy account. A model's id, an alias such as
 * {@code haiku} and a Vertex account's own naming are all answered the same way, which no list
 * of models on the host could do.
 *
 * <p>A model that answered is remembered for the session, per template, Claude account and
 * model, so a profile costs one small request the first time and nothing after. A refusal is
 * not remembered: the user may fix the account meanwhile.
 */
final class ModelCheck {

    /** Identifies the check's script among a session's execs; for tests. */
    static final String MARKER = "isx-model-check";
    /** The credential namespace whose account decides which models answer. */
    static final String NAMESPACE = "claude";
    /** How long the check may take: Claude Code retries an overloaded API, and the call waits. */
    static final int TIMEOUT_SECONDS = 90;
    private static final int ERROR_LIMIT = 600;

    private final InstanceBackend backend;
    private final Set<String> usable = ConcurrentHashMap.newKeySet();

    ModelCheck(InstanceBackend backend) {
        this.backend = backend;
    }

    /** Refuse, with a {@link ToolError} and before anything runs, what is not a model name. */
    static void requireName(String model) {
        if (model != null && !McpConfig.isModelName(model)) {
            throw new ToolError("model must be a Claude Code model id or alias (e.g. claude-haiku-4-5, "
                    + "sonnet, opus[1m]): letters, digits and . _ : @ / [ ] -");
        }
    }

    /**
     * Fail with a {@link ToolError} unless {@code model} answers in {@code instance}, made from
     * {@code template}, whose Claude account is pinned to {@code pinned} (null for the configured
     * default). Nothing to do for a null model. Remembered by the account the pin resolves to
     * now, so a default the user changed meanwhile is checked again.
     */
    void require(String instance, String template, String pinned, String model) {
        if (model == null) return;
        var key = template + '\0' + backend.effectiveAccount(NAMESPACE, pinned) + '\0' + model;
        if (usable.contains(key)) return;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var exit = backend.exec(instance, script(model), null, out, err);
        var refusal = refusal(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        if (refusal != null) {
            throw new ToolError("model '" + model + "' cannot be used in " + instance + " with the account its "
                    + "template '" + template + "' uses: " + refusal + ". Leave model unset for the template's own "
                    + "(list_templates shows it), or choose another.");
        }
        usable.add(key);
    }

    /**
     * Ask Claude Code for one tool-less turn on {@code model}, in an empty directory, for its
     * JSON result. Exits as Claude Code did.
     */
    static String script(String model) {
        return ": " + MARKER + "; "
                + "command -v claude >/dev/null || { echo 'no Claude Code in this instance' >&2; exit 127; }; "
                + "w=$(mktemp -d); trap 'rm -rf \"$w\"' EXIT; cd \"$w\" || exit 1; "
                + "echo 'Reply with the single word OK.' | timeout " + TIMEOUT_SECONDS + " " + AskScript.oneShot(model)
                + " --max-turns 1 --output-format json";
    }

    /**
     * Null when the model answered; otherwise what Claude Code said about it. Fails closed: only
     * a {@code result} event of subtype {@code success}, not flagged {@code is_error}, with an
     * exit of 0, passes -- an empty, unreadable or error answer is no evidence the model can be used.
     */
    static String refusal(int exit, String out, String err) {
        String result = null;
        var isError = false;
        var success = false;
        var last = out.strip().lines().reduce((a, b) -> b).orElse("");
        try {
            var json = last.isEmpty() ? null : JsonRpc.JSON.readTree(last);
            if (json != null && json.isObject()) {
                isError = json.path("is_error").asBoolean(false);
                result = json.path("result").asText(null);
                success = "result".equals(json.path("type").asText()) && "success".equals(json.path("subtype").asText());
            }
        } catch (java.io.IOException e) {
            // not a result: the exit code and stderr say what happened
        }
        if (exit == 0 && success && !isError && result != null) return null;
        var detail = !err.isBlank() ? err.strip() : last;
        var why = isError && result != null && !result.isBlank() ? result
                : exit == 0 ? "Claude Code gave no successful result" + (detail.isEmpty() ? "" : ": " + detail)
                : exit == 124 ? "no answer within " + TIMEOUT_SECONDS + " seconds"
                : !detail.isEmpty() ? detail
                : "Claude Code failed (exit " + exit + ")";
        return why.length() > ERROR_LIMIT ? why.substring(why.length() - ERROR_LIMIT) : why;
    }
}
