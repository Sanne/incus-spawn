package dev.incusspawn.command;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.util.OutputFormat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Why a built template has no loaded definition of its name (#2057), read from the file its
 * {@code build-source} stamp records. Such a template stays listed and branchable, but nothing
 * rebuilds it: the stamp is the definition as it was, not a file anyone can edit.
 *
 * @param template  the built template
 * @param kind      which of the three it is
 * @param file      the file the stamp records, {@code built-in}, or null when it records none
 * @param renamedTo the loaded template that file now defines, for {@link Kind#RENAMED}
 * @param project   the project directory of a project-local definition, for {@link Kind#OUT_OF_SCOPE}
 */
public record MissingDefinition(String template, Kind kind, String file, String renamedTo, String project) {

    public enum Kind {
        /** The file is loaded, under another {@code name:}. */
        RENAMED,
        /** The file exists but is not loaded here, such as another project's {@code .incus-spawn/}. */
        OUT_OF_SCOPE,
        /** No such file, or a built-in this isx no longer ships, or a stamp that records no file. */
        GONE
    }

    /**
     * Why {@code template}, built with the stamp {@code buildSourceJson}, has no definition in
     * {@code loaded}; null when it has one, or when the stamp holds no definition of that name
     * (no stamp, or an instance branched from a template, which is no template itself). Costs one
     * file check, and no Incus request.
     */
    public static MissingDefinition of(String template, String buildSourceJson, Map<String, ImageDef> loaded) {
        return of(template, buildSourceJson, loaded, Files::isRegularFile);
    }

    static MissingDefinition of(String template, String buildSourceJson, Map<String, ImageDef> loaded,
                                Predicate<Path> exists) {
        // Checked before the stamp is parsed: doctor asks this of every template
        if (loaded.containsKey(template)) return null;
        return ofStamp(template, BuildSource.fromJson(buildSourceJson), loaded, exists);
    }

    /** As above, from a stamp already parsed, or null. */
    public static MissingDefinition ofStamp(String template, BuildSource stamp, Map<String, ImageDef> loaded) {
        return ofStamp(template, stamp, loaded, Files::isRegularFile);
    }

    static MissingDefinition ofStamp(String template, BuildSource stamp, Map<String, ImageDef> loaded,
                                   Predicate<Path> exists) {
        if (loaded.containsKey(template)) return null;
        var stamped = stamp == null ? null : stamp.getDefinitions().get(template);
        if (stamped == null) return null;
        var file = stamp.getSources().get(template);
        if (file == null || !file.startsWith("/")) {
            // Only a path or built-in names a file; anything else, such as "unknown", names none
            return new MissingDefinition(template, Kind.GONE, "built-in".equals(file) ? file : null, null, null);
        }
        for (var def : loaded.values()) {
            if (file.equals(def.getSource())) {
                return new MissingDefinition(template, Kind.RENAMED, file, def.getName(), null);
            }
        }
        if (!exists.test(Path.of(file))) return new MissingDefinition(template, Kind.GONE, file, null, null);
        var project = stamped.getProjectRoot() == null ? null : stamped.getProjectRoot().toString();
        return new MissingDefinition(template, Kind.OUT_OF_SCOPE, file, null, project);
    }

    /** One line saying why there is no definition, for a detail row or a finding. */
    public String reason() {
        var shownFile = file == null ? null : OutputFormat.oneLine(file);
        return switch (kind) {
            case RENAMED -> shownFile + " now defines " + OutputFormat.oneLine(renamedTo);
            case OUT_OF_SCOPE -> shownFile + " is not loaded here";
            case GONE -> "built-in".equals(file) ? "this isx has no built-in of that name"
                    : shownFile == null ? "its build recorded no definition file"
                    : shownFile + " no longer exists";
        };
    }

    /** What to do instead of rebuilding it. */
    public String advice() {
        var name = OutputFormat.oneLine(template);
        return switch (kind) {
            case RENAMED -> "Build " + OutputFormat.oneLine(renamedTo) + " instead; "
                    + name + " stays branchable until you destroy it.";
            case OUT_OF_SCOPE -> project != null
                    ? "Run isx build " + name + " in " + OutputFormat.oneLine(project) + "."
                    : "Run isx build " + name + " where that file is loaded.";
            case GONE -> "Restore the definition to rebuild it; " + name + " stays branchable until you destroy it.";
        };
    }
}
