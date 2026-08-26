package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ProxyCredentials(
        String anthropicApiKey,
        String oauthToken,
        boolean useVertex,
        String vertexRegion,
        String vertexProjectId
) {
    public ProxyCredentials {
        anthropicApiKey = anthropicApiKey != null ? anthropicApiKey : "";
        oauthToken = oauthToken != null ? oauthToken : "";
        vertexRegion = vertexRegion != null ? vertexRegion : "";
        vertexProjectId = vertexProjectId != null ? vertexProjectId : "";
    }

    public static ProxyCredentials fromConfig(SpawnConfig config) {
        var claude = config.getClaude();
        return new ProxyCredentials(
                claude.getApiKey(),
                claude.getOauthToken(),
                claude.isUseVertex(),
                claude.getCloudMlRegion(),
                claude.getVertexProjectId()
        );
    }
}
