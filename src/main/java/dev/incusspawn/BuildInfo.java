package dev.incusspawn;

import java.util.Properties;

import org.eclipse.microprofile.config.ConfigProvider;

public record BuildInfo(String version, String gitSha, String incusClient, String incusServer) {

    private static final BuildInfo INSTANCE = load();

    public static BuildInfo instance() {
        return INSTANCE;
    }

    private static BuildInfo load() {
        var version = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.application.version", String.class)
                .orElse("dev");
        String gitSha = "unknown";
        try (var is = BuildInfo.class.getClassLoader().getResourceAsStream("git.properties")) {
            if (is != null) {
                var props = new Properties();
                props.load(is);
                gitSha = props.getProperty("git.commit.id", "unknown");
            }
        } catch (Exception ignored) {
        }
        String incusClient = "unknown";
        String incusServer = "unknown";
        try {
            var pb = new ProcessBuilder("incus", "version");
            pb.redirectErrorStream(true);
            var p = pb.start();
            var output = new String(p.getInputStream().readAllBytes()).strip();
            if (p.waitFor() == 0 && !output.isEmpty()) {
                for (var line : output.lines().toList()) {
                    if (line.startsWith("Client version:")) {
                        incusClient = line.substring("Client version:".length()).strip();
                    } else if (line.startsWith("Server version:")) {
                        incusServer = line.substring("Server version:".length()).strip();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new BuildInfo(version, gitSha, incusClient, incusServer);
    }
}
