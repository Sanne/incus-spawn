package dev.incusspawn;

import dev.incusspawn.graal.BakedHostPathFeature;
import dev.incusspawn.graal.SyscallReachabilityFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the native-image build arguments against drift between their three declarations: the CLI's
 * and the proxy's {@code application.properties}, and the duplicate list in {@code cli/pom.xml}'s
 * {@code macos-native} profile.
 * <p>
 * {@link RuntimeConstants} and {@link RuntimeServices} resolve host paths in their static
 * initializers and are only correct because {@code --initialize-at-run-time} defers them past image
 * build; {@link BakedHostPathFeature} is what catches it when that slips. Dropping either from one
 * declaration yields a binary with the build machine's home directory baked in (that is how
 * {@code /root/.cache/incus-spawn/downloads} once shipped) — and a Linux build would never notice
 * the macOS profile drifting. This test fails in {@code mvn test}; the guards themselves only run
 * during a native build.
 */
class NativeImageInitializationTest {

    private static final String BUILD_ARGS_PROPERTY = "quarkus.native.additional-build-args";

    /** Classes in {@code common} whose static initializers must not run at image-build time. */
    private static final List<Class<?>> COMMON_DEFERRED = List.of(Environment.class, RuntimeConstants.class);

    /** Same, for the CLI, which adds its own eagerly-initialized service registry. */
    private static final List<Class<?>> CLI_DEFERRED =
            List.of(Environment.class, RuntimeConstants.class, RuntimeServices.class);

    private static final List<Class<?>> GUARDS =
            List.of(SyscallReachabilityFeature.class, BakedHostPathFeature.class);

    @Test
    void everyDeclarationDefersTheRightClassesAndRunsBothGuards() throws IOException {
        var declarations = Map.of(
                Path.of("src/main/resources-filtered/application.properties"), CLI_DEFERRED,
                Path.of("pom.xml"), CLI_DEFERRED,  // macos-native profile
                Path.of("../proxy/src/main/resources-filtered/application.properties"), COMMON_DEFERRED);

        for (var declaration : declarations.entrySet()) {
            var path = declaration.getKey();
            var arguments = buildArguments(path);

            var runtimeInit = argument(arguments, "--initialize-at-run-time=", path);
            for (var deferred : declaration.getValue()) {
                assertTrue(runtimeInit.contains(deferred.getName()),
                        deferred.getName() + " must be listed in --initialize-at-run-time in " + path
                                + ": it resolves host paths in its static initializer, which GraalVM"
                                + " would otherwise run (and constant-fold) at image build time."
                                + " Found: " + runtimeInit);
            }

            var features = argument(arguments, "--features=", path);
            for (var guard : GUARDS) {
                assertTrue(features.contains(guard.getName()),
                        guard.getName() + " must be registered via --features in " + path
                                + ", otherwise that binary is built unguarded. Found: " + features);
            }
        }
    }

    /**
     * The declared native-image arguments, one per element. Arguments are comma-separated and a
     * literal comma inside one argument is backslash-escaped, so splitting on unescaped commas
     * isolates exactly one argument — which is why moving a class to
     * {@code --initialize-at-build-time}, or merely naming it in a comment, fails this test instead
     * of passing it.
     */
    private static List<String> buildArguments(Path declaration) throws IOException {
        var value = rawValue(declaration).replaceAll("\\s+", "");
        var arguments = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c == ',' && (i == 0 || value.charAt(i - 1) != '\\')) {
                arguments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        arguments.add(current.toString());
        return arguments;
    }

    /** The {@code additional-build-args} value: a wrapped properties entry, or a pom property element. */
    private static String rawValue(Path declaration) throws IOException {
        assertTrue(Files.exists(declaration), "Expected to find " + declaration.toAbsolutePath()
                + " — tests run from the module directory");
        var text = Files.readString(declaration);

        if (declaration.getFileName().toString().endsWith(".xml")) {
            var open = "<" + BUILD_ARGS_PROPERTY + ">";
            var from = text.indexOf(open);
            assertTrue(from >= 0, "No " + open + " element in " + declaration);
            return text.substring(from + open.length(), text.indexOf("</" + BUILD_ARGS_PROPERTY + ">", from));
        }

        var entry = BUILD_ARGS_PROPERTY + "=";
        var from = text.indexOf(entry);
        assertTrue(from >= 0, "No " + entry + " entry in " + declaration);
        var value = new StringBuilder();
        for (var line : text.substring(from + entry.length()).lines().toList()) {
            boolean continued = line.endsWith("\\");           // properties line continuation
            value.append(continued ? line.substring(0, line.length() - 1) : line);
            if (!continued) break;
        }
        return value.toString();
    }

    private static String argument(List<String> arguments, String name, Path declaration) {
        return arguments.stream()
                .filter(a -> a.startsWith(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + name + " argument in " + declaration + ": " + arguments));
    }
}
