package dev.incusspawn.graal;

import org.graalvm.nativeimage.hosted.Feature;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build-time guard that fails the native-image build if any code path can
 * reach a GraalVM lazy system-property resolver that triggers a syscall
 * (getcwd for user.dir, uname for os.name/os.version, getpwuid for
 * user.home/user.name).
 *
 * <h2>Why this matters</h2>
 * Short-lived CLI commands (--help, --version, completions) should start in
 * microseconds.  GraalVM resolves these properties lazily — only on first
 * access — but the syscall cost is still measurable when it lands on the
 * hot path of every invocation.  Worse, a single transitive call to
 * {@code System.getProperty("user.dir")} anywhere in the reachability graph
 * is enough to pull the resolver into the image, even if that path is rarely
 * executed at runtime.
 *
 * <p>By failing the build when a resolver is reachable, we catch accidental
 * regressions (e.g. a new dependency that reads user.dir at class-init time)
 * before they ship, rather than discovering them via strace after the fact.
 * When a resolver IS legitimately needed, the fix is to add it to this
 * class's allowed list and document why the syscall is acceptable.
 *
 * <h2>How it works</h2>
 * Hooks into {@code afterAnalysis} and calls the public
 * {@link Feature.AfterAnalysisAccess#isReachable} API.  If a resolver is
 * reachable, a BFS over the caller graph (via reflection into the internal
 * {@code AnalysisMethod.getCallers()} API) identifies the application-level
 * entry points that pull it in, then the build is aborted with that context.
 */
public class SyscallReachabilityFeature implements Feature {

    private record Target(String method, String property, String syscall) {}

    private static final Target[] TARGETS = {
        new Target("userDirValue",  "user.dir",    "getcwd()"),
        new Target("userHomeValue", "user.home",   "getpwuid()"),
        new Target("userNameValue", "user.name",   "getpwuid()"),
        new Target("osNameValue",   "os.name",     "uname()"),
        new Target("osVersionValue","os.version",  "uname()"),
    };

    private static final String SYS_PROP_SUPPORT =
            "com.oracle.svm.core.jdk.SystemPropertiesSupport";

    @Override
    public String getDescription() {
        return "Reports which system-property resolvers (and their syscalls) are reachable";
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        System.err.println();
        System.err.println("[isx-syscall-guard] Checking system-property resolver reachability:");

        Class<?> sysPropClass;
        try {
            sysPropClass = Class.forName(SYS_PROP_SUPPORT);
        } catch (ClassNotFoundException e) {
            System.err.println("[isx-syscall-guard]   " + SYS_PROP_SUPPORT + " not found — skipping");
            return;
        }

        List<String> violations = new ArrayList<>();
        for (Target t : TARGETS) {
            if (analyzeTarget(access, sysPropClass, t)) {
                violations.add(t.method + "() (" + t.property + " -> " + t.syscall + ")");
            }
        }
        System.err.println();

        if (!violations.isEmpty()) {
            throw new Error("[isx-syscall-guard] Build aborted: " + violations.size()
                    + " system-property resolver(s) are reachable and would trigger syscalls at runtime:\n  "
                    + String.join("\n  ", violations)
                    + "\nEither eliminate the code path or add the method to the allowed list in "
                    + SyscallReachabilityFeature.class.getSimpleName());
        }
    }

    /** @return true if the target is reachable (a violation) */
    private boolean analyzeTarget(AfterAnalysisAccess access, Class<?> sysPropClass, Target target) {
        try {
            Method method = sysPropClass.getDeclaredMethod(target.method);

            if (!access.isReachable(method)) {
                System.err.printf("[isx-syscall-guard]   ok  %-16s (%s) — not reachable, no %s at runtime%n",
                        target.method + "()", target.property, target.syscall);
                return false;
            }

            System.err.printf("[isx-syscall-guard]   !!  %-16s (%s) — REACHABLE, %s may fire at runtime%n",
                    target.method + "()", target.property, target.syscall);

            printCallerPaths(access, method);
            return true;

        } catch (NoSuchMethodException e) {
            System.err.printf("[isx-syscall-guard]   --  %-16s — method not found on this GraalVM version%n",
                    target.method + "()");
            return false;
        }
    }

    // --- Internal-API caller chain via reflection ---

    private void printCallerPaths(AfterAnalysisAccess access, Method targetMethod) {
        try {
            Object metaAccess = access.getClass().getMethod("getMetaAccess").invoke(access);
            Method lookup = metaAccess.getClass().getMethod("lookupJavaMethod", Executable.class);
            Object analysisMethod = lookup.invoke(metaAccess, targetMethod);

            Map<String, Set<String>> appCallerPaths = findApplicationCallers(analysisMethod);

            if (appCallerPaths.isEmpty()) {
                System.err.println("[isx-syscall-guard]         (no application-level callers found)");
            } else {
                for (var entry : appCallerPaths.entrySet()) {
                    System.err.println("[isx-syscall-guard]         " + entry.getKey());
                    for (String step : entry.getValue()) {
                        System.err.println("[isx-syscall-guard]           via " + step);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[isx-syscall-guard]         (caller analysis unavailable: " + e.getMessage() + ")");
        }
    }

    /**
     * BFS from the target method upward through callers, collecting the first
     * non-framework methods encountered and the framework path that leads to them.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> findApplicationCallers(Object targetAnalysisMethod) throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Set<Object> visited = new LinkedHashSet<>();
        Deque<Object[]> queue = new ArrayDeque<>();
        // each queue entry: [analysisMethod, pathSoFar (as Set<String>)]
        queue.add(new Object[]{ targetAnalysisMethod, new LinkedHashSet<String>() });

        while (!queue.isEmpty()) {
            Object[] current = queue.poll();
            Object method = current[0];
            Set<String> pathSoFar = (Set<String>) current[1];

            if (!visited.add(method) || visited.size() > 200) continue;

            String name = formatMethod(method);
            boolean isFramework = isFrameworkClass(name);

            if (!isFramework && method != targetAnalysisMethod) {
                result.computeIfAbsent(name, k -> new LinkedHashSet<>()).addAll(pathSoFar);
                continue;
            }

            Set<?> callers = (Set<?>) method.getClass().getMethod("getCallers").invoke(method);
            for (Object caller : callers) {
                Set<String> newPath = new LinkedHashSet<>(pathSoFar);
                if (method != targetAnalysisMethod) {
                    newPath.add(name);
                }
                queue.add(new Object[]{ caller, newPath });
            }
        }
        return result;
    }

    private static String formatMethod(Object analysisMethod) throws Exception {
        return (String) analysisMethod.getClass()
                .getMethod("format", String.class)
                .invoke(analysisMethod, "%H.%n(%p)");
    }

    private static boolean isFrameworkClass(String formattedName) {
        return formattedName.startsWith("com.oracle.svm.")
                || formattedName.startsWith("java.")
                || formattedName.startsWith("javax.")
                || formattedName.startsWith("jdk.")
                || formattedName.startsWith("sun.");
    }
}
