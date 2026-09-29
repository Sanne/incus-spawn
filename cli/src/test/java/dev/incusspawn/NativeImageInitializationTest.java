package dev.incusspawn;

import dev.incusspawn.graal.BakedHostStateFeature;
import dev.incusspawn.graal.SyscallReachabilityFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the native-image build arguments, declared once per module in its
 * {@code application.properties}.
 * <p>
 * {@link RuntimeConstants} and {@link RuntimeServices} resolve host paths in their static
 * initializers and are only correct because {@code --initialize-at-run-time} defers them past image
 * build; {@link BakedHostStateFeature} is what catches it when that slips. Dropping either from one
 * declaration yields a binary with the build machine's home directory baked in (that is how
 * {@code /root/.cache/incus-spawn/downloads} once shipped). This test fails in {@code mvn test};
 * the guards themselves only run during a native build.
 */
class NativeImageInitializationTest {

    private static final String BUILD_ARGS_PROPERTY = "quarkus.native.additional-build-args";

    /** Classes in {@code common} whose static initializers must not run at image-build time. */
    private static final List<Class<?>> COMMON_DEFERRED = List.of(Environment.class, RuntimeConstants.class);

    /** Same, for the CLI, which adds its own eagerly-initialized service registry. */
    private static final List<Class<?>> CLI_DEFERRED =
            List.of(Environment.class, RuntimeConstants.class, RuntimeServices.class);

    private static final List<Path> POMS = List.of(Path.of("../pom.xml"), Path.of("pom.xml"), Path.of("../proxy/pom.xml"));

    /** Pom properties through which a platform adds its own arguments to its module's one list. */
    private static final List<String> PLATFORM_PLACEHOLDERS =
            List.of("svm.target.name.args", "macos.plist.args", "native.march.args", "native.optimization");

    private static final List<Class<?>> GUARDS =
            List.of(SyscallReachabilityFeature.class, BakedHostStateFeature.class);

    @Test
    void everyDeclarationDefersTheRightClassesRunsBothGuardsAndKeepsTheEnvironmentSanitized() throws IOException {
        var declarations = Map.of(
                Path.of("src/main/resources-filtered/application.properties"), CLI_DEFERRED,
                Path.of("../proxy/src/main/resources-filtered/application.properties"), COMMON_DEFERRED);

        for (var declaration : declarations.entrySet()) {
            var path = declaration.getKey();
            var arguments = splitArguments(rawValue(path));

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

            // native-image hands the builder a sanitized environment (HOME, LANG, PATH, PWD), which
            // is what keeps a build-time getenv() from baking a CI token into a public binary;
            // -E<name> is the only way to widen it.
            for (var argument : arguments) {
                assertFalse(argument.startsWith("-E"), argument + " in " + path + " passes a builder"
                        + " environment variable through to image-build time, where a build-time"
                        + " initializer can bake its value into the binary. Read it at run time instead.");
            }
        }
    }

    /**
     * A pom property overrides {@code application.properties}, so a profile redefining the list would
     * give its platform a copy that no build on the other platform exercises. The {@code macos-native}
     * profile had one, and it drifted: macOS release builds kept {@code -R:MaxRAM=128m} after Linux
     * moved to 512m (#489). A platform's own arguments join the one list through a placeholder a
     * profile sets, and those values get the checks the list does.
     */
    @Test
    void noPomRedefinesTheListAndPlatformArgumentsJoinIt() throws IOException {
        for (var pom : POMS) {
            var text = Files.readString(pom);
            assertFalse(Pattern.compile("<" + Pattern.quote(BUILD_ARGS_PROPERTY) + "[\\s/>]").matcher(text).find(),
                    pom + " defines " + BUILD_ARGS_PROPERTY + ", shadowing the list in application.properties"
                            + " for the builds it applies to. Add platform-specific arguments through a"
                            + " placeholder property the list includes instead.");

            for (var placeholder : PLATFORM_PLACEHOLDERS) {
                var values = Pattern.compile("<" + Pattern.quote(placeholder) + ">([^<]*)</").matcher(text);
                while (values.find()) {
                    for (var argument : splitArguments(values.group(1))) {
                        assertFalse(argument.startsWith("-E") || argument.startsWith("--initialize-at-build-time"),
                                argument + " in " + placeholder + " in " + pom + " would undo the list's"
                                        + " environment sanitizing or run-time initialization for that platform.");
                    }
                }
            }
        }

        var cliList = rawValue(Path.of("src/main/resources-filtered/application.properties"));
        for (var placeholder : List.of("svm.target.name.args", "macos.plist.args")) {
            assertTrue(cliList.contains("${" + placeholder + "}"), "The CLI's argument list no longer"
                    + " includes ${" + placeholder + "}, so the builds setting it silently lose it.");
        }
    }

    /**
     * The declared native-image arguments, one per element. Arguments are comma-separated and a
     * literal comma inside one argument is backslash-escaped, so splitting on unescaped commas
     * isolates exactly one argument — which is why moving a class to
     * {@code --initialize-at-build-time}, or merely naming it in a comment, fails this test instead
     * of passing it.
     */
    private static List<String> splitArguments(String value) {
        value = value.replaceAll("\\s+", "");
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

    /** The {@code additional-build-args} value, joined across continuation lines. */
    private static String rawValue(Path declaration) throws IOException {
        assertTrue(Files.exists(declaration), "Expected to find " + declaration.toAbsolutePath()
                + " — tests run from the module directory");
        var text = Files.readString(declaration);

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
