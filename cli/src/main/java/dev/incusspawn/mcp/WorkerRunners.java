package dev.incusspawn.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The guest-side Node runners of the agents whose runs go through one
 * ({@link DelegateAgent#usesRunner}), as {@code node -e} runs them: the I/O they share
 * ({@code mcp/runner-io.cjs}), then the agent's own script.
 */
final class WorkerRunners {

    private WorkerRunners() {}

    static String of(DelegateAgent agent) {
        return switch (agent) {
            case CODEX -> load("codex-runner.cjs");
            case PI -> load("pi-runner.cjs");
            case OPENCODE -> load("opencode-runner.cjs");
            default -> throw new IllegalArgumentException(agent.id + " has no runner");
        };
    }

    private static String load(String name) {
        return resource("runner-io.cjs") + resource(name);
    }

    private static String resource(String name) {
        try (var in = WorkerRunners.class.getResourceAsStream("/mcp/" + name)) {
            if (in == null) throw new IllegalStateException("missing runner resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read runner " + name, e);
        }
    }
}
