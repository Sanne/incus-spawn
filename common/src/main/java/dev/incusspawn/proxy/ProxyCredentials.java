package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record ProxyCredentials(
        String anthropicApiKey,
        String oauthToken,
        boolean useVertex,
        String vertexRegion,
        String vertexProjectId,
        List<ResolvedToolProxy> toolProxies
) {
    public ProxyCredentials {
        anthropicApiKey = anthropicApiKey != null ? anthropicApiKey : "";
        oauthToken = oauthToken != null ? oauthToken : "";
        vertexRegion = vertexRegion != null ? vertexRegion : "";
        vertexProjectId = vertexProjectId != null ? vertexProjectId : "";
        toolProxies = toolProxies != null ? toolProxies : List.of();
    }

    public static ProxyCredentials fromConfig(SpawnConfig config) {
        var claude = config.getClaude();
        var resolved = ToolProxyResolver.resolve(config);
        // Each ClaudeConfig accessor re-resolves the account, rebuilding the account map every
        // time; resolve once and read the fields off it. Also makes it explicit that all five
        // values describe a single account rather than being independently sourced.
        var account = claude.account();
        var vertex = account != null && account.effectiveType() == SpawnConfig.ClaudeAccountType.VERTEX;
        var oauth = account != null && account.effectiveType() == SpawnConfig.ClaudeAccountType.OAUTH;
        var apiKey = account != null && account.effectiveType() == SpawnConfig.ClaudeAccountType.API_KEY;
        return new ProxyCredentials(
                apiKey ? account.getApiKey() : "",
                oauth ? account.getOauthToken() : "",
                vertex,
                account == null ? "" : account.getCloudMlRegion(),
                account == null ? "" : account.getVertexProjectId(),
                resolved
        );
    }

    public List<String> toolProxyNames() {
        return toolProxies.stream()
                .filter(tp -> tp.auth() == null || !"anthropic".equals(tp.auth().getType()))
                .map(ResolvedToolProxy::toolName)
                .distinct().sorted().toList();
    }
}
