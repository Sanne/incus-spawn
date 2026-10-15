package dev.incusspawn.git;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolved build associations and a host-owned cleanup record that survives instance deletion. */
public final class HostRepoSources {
    private HostRepoSources() {}

    public static Map<String, String> parse(String json) {
        var sources = new LinkedHashMap<String, String>();
        if (json == null || json.isBlank()) return sources;
        if (!(JqValues.parseStrict(json) instanceof JqObject object)) {
            throw new IllegalArgumentException("Invalid host repository associations");
        }
        object.forEach((guest, host) -> {
            if (!host.isString() || !Path.of(host.asText()).isAbsolute()) {
                throw new IllegalArgumentException("Invalid host repository source path");
            }
            sources.put(guest, host.asText());
        });
        return sources;
    }

    /** A derived template retains the resolved associations of the repositories it inherited. */
    public static void record(IncusClient incus, String instance, Map<String, String> added) {
        var sources = parse(incus.configGet(instance, Metadata.HOST_REPOSITORIES));
        sources.putAll(added);
        incus.configSet(instance, Metadata.HOST_REPOSITORIES, JqValues.serialize(JqValues.fromJavaObject(sources)));
    }

    static Path cleanupFile(String instance) {
        // Hashing also keeps this path safe for callers handling a vanished or malformed name.
        return Environment.dataDir().resolve("git-remotes").resolve(PreparationGit.hash(instance) + ".json");
    }

    static Map<String, String> remembered(String instance) throws IOException {
        var file = cleanupFile(instance);
        return Files.exists(file) ? parse(Files.readString(file)) : new LinkedHashMap<>();
    }

    /** Record before adding the remote, so interruption cannot leave an undiscoverable remote. */
    static void remember(String instance, Path source) throws IOException {
        var file = cleanupFile(instance);
        Files.createDirectories(file.getParent());
        var paths = remembered(instance);
        paths.put(source.toString(), source.toString());
        var temporary = Files.createTempFile(file.getParent(), "remotes-", ".json");
        try {
            Files.writeString(temporary, JqValues.serialize(JqValues.fromJavaObject(paths)));
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
