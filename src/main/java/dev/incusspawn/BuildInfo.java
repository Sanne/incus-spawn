package dev.incusspawn;

import java.util.Properties;

public record BuildInfo(String version, String gitSha, String runtime) {

    private static final BuildInfo INSTANCE = new BuildInfo(
            readProperty("build.properties", "app.version", "dev"),
            readProperty("git.properties", "git.commit.id", "unknown"),
            detectRuntime());

    public static BuildInfo instance() { return INSTANCE; }

    public String incusClient() { return Environment.INCUS_CLIENT; }
    public String incusServer() { return Environment.INCUS_SERVER; }

    private static String readProperty(String resource, String key, String fallback) {
        try (var is = BuildInfo.class.getClassLoader().getResourceAsStream(resource)) {
            if (is != null) {
                var props = new Properties();
                props.load(is);
                return props.getProperty(key, fallback);
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String detectRuntime() {
        var graalVersion = System.getProperty("org.graalvm.version");
        if (graalVersion != null) {
            return "native (GraalVM " + graalVersion + ")";
        }
        return System.getProperty("java.vm.name", "Unknown JVM")
                + " " + System.getProperty("java.version", "");
    }
}
