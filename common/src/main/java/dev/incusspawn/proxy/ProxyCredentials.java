package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ProxyCredentials(
        String anthropicApiKey,
        String oauthToken,
        String ghToken,
        String bobApiKey,
        String openaiApiKey,
        boolean useVertex,
        String vertexRegion,
        String vertexProjectId
) {
    public ProxyCredentials {
        anthropicApiKey = anthropicApiKey != null ? anthropicApiKey : "";
        oauthToken = oauthToken != null ? oauthToken : "";
        ghToken = ghToken != null ? ghToken : "";
        bobApiKey = bobApiKey != null ? bobApiKey : "";
        openaiApiKey = openaiApiKey != null ? openaiApiKey : "";
        vertexRegion = vertexRegion != null ? vertexRegion : "";
        vertexProjectId = vertexProjectId != null ? vertexProjectId : "";
    }

    public static ProxyCredentials fromConfig(SpawnConfig config) {
        var claude = config.getClaude();
        return new ProxyCredentials(
                claude.getApiKey(),
                claude.getOauthToken(),
                config.getGithub().getToken(),
                config.getBob().getApiKey(),
                config.getOpenai().getApiKey(),
                claude.isUseVertex(),
                claude.getCloudMlRegion(),
                claude.getVertexProjectId()
        );
    }
}
