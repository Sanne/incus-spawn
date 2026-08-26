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
        List<ResolvedToolProxy> toolProxies,
        String toolProxyFingerprint
) {
    public ProxyCredentials {
        anthropicApiKey = anthropicApiKey != null ? anthropicApiKey : "";
        oauthToken = oauthToken != null ? oauthToken : "";
        vertexRegion = vertexRegion != null ? vertexRegion : "";
        vertexProjectId = vertexProjectId != null ? vertexProjectId : "";
        toolProxies = toolProxies != null ? toolProxies : List.of();
        toolProxyFingerprint = toolProxyFingerprint != null ? toolProxyFingerprint : "";
    }

    public static ProxyCredentials fromConfig(SpawnConfig config) {
        var claude = config.getClaude();
        var resolved = ToolProxyResolver.resolve(config);
        return new ProxyCredentials(
                claude.getApiKey(),
                claude.getOauthToken(),
                claude.isUseVertex(),
                claude.getCloudMlRegion(),
                claude.getVertexProjectId(),
                resolved,
                ToolProxyResolver.fingerprint(resolved)
        );
    }

    public List<String> toolProxyNames() {
        return toolProxies.stream()
                .filter(tp -> tp.auth() == null || !"anthropic".equals(tp.auth().getType()))
                .map(ResolvedToolProxy::toolName)
                .distinct().sorted().toList();
    }
}
