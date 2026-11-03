package dev.incusspawn;

import java.util.Properties;
import java.util.regex.Pattern;

/** The POM version identifies builds and templates; the display version describes a snapshot's commit. */
public record BuildInfo(String version, String gitSha, String runtime, String displayVersion) {

    private static final Pattern GIT_DESCRIBE = Pattern.compile("v?(\\d\\S*?)(?:-(\\d+)-g[0-9a-f]+)?");

    private static final BuildInfo INSTANCE = createInstance();

    public BuildInfo(String version, String gitSha, String runtime) {
        this(version, gitSha, runtime, version);
    }

    public static BuildInfo instance() { return INSTANCE; }

    public boolean isDev() {
        return version.equals("dev")
                || version.contains("SNAPSHOT")
                || version.equals("0.0.0");
    }

    public String incusClient() { return Environment.incusClient(); }
    public String incusServer() { return Environment.incusServer(); }
    public String kernelInfo() { return Environment.kernelInfo(); }

    static BuildInfo createInstance() {
        return createInstance(readProperties("build.properties"), readProperties("git.properties"));
    }

    static BuildInfo createInstance(Properties buildProps, Properties gitProps) {
        var version = buildProps.getProperty("app.version", "dev");
        return new BuildInfo(
                version,
                gitProps.getProperty("git.commit.id", "unknown"),
                detectRuntime(),
                snapshotVersion(version, gitProps.getProperty("git.commit.id.describe", "")));
    }

    /** Git's describe formatter returns a bare tag at distance zero, or nothing without a reachable tag. */
    static String snapshotVersion(String version, String description) {
        if (!version.endsWith("-SNAPSHOT") || description.isBlank()) return version;
        var match = GIT_DESCRIBE.matcher(description.strip());
        if (!match.matches()) return version;
        return match.group(1) + "+" + (match.group(2) == null ? "0" : match.group(2));
    }

    private static Properties readProperties(String resource) {
        var props = new Properties();
        try (var is = BuildInfo.class.getClassLoader().getResourceAsStream(resource)) {
            if (is != null) {
                props.load(is);
            }
        } catch (Exception ignored) {}
        return props;
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
