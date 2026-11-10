package dev.incusspawn.util;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether this process may run a command with elevated privilege (#2074).
 * <p>
 * {@code isx init --no-sudo} hands every privileged step to the person as a script and forbids
 * this process from elevating at all. Each place isx starts a host command that may elevate asks
 * {@link #check} first, so a step that would still reach for sudo fails loudly instead of running
 * it. {@code PrivilegeGateTest} holds every file that names {@code sudo} to asking.
 */
public final class Privilege {

    private Privilege() {}

    /** The commands that elevate, by their file name. */
    private static final Set<String> ELEVATING = Set.of("sudo", "pkexec", "doas", "su", "run0");

    /** One of {@link #ELEVATING} as a command word in a shell script: at its start or after a separator, by path or not. */
    private static final Pattern ELEVATING_WORD = Pattern.compile(
            "(?:^|[\\s;&|()`])(?:\\S*/)?(?:" + String.join("|", ELEVATING) + ")(?=\\s|$)");

    private static volatile boolean forbidden;

    /** From now on, this process runs nothing that elevates. */
    public static void forbid() {
        forbidden = true;
    }

    /** Lifts {@link #forbid}, for tests. */
    public static void allow() {
        forbidden = false;
    }

    public static boolean forbidden() {
        return forbidden;
    }

    /** Thrown for a command that would elevate while that is forbidden. */
    public static final class Refused extends IllegalStateException {
        Refused(String command) {
            super("isx init --no-sudo runs nothing as root, but this step would run: " + command);
        }
    }

    /** Refuses {@code command} when it would elevate and this process may not. */
    public static void check(String... command) {
        if (forbidden && elevates(List.of(command))) throw new Refused(String.join(" ", command));
    }

    /** {@link #check(String...)} for a command held as a list. */
    public static void check(List<String> command) {
        check(command.toArray(String[]::new));
    }

    /**
     * Whether {@code command} elevates: its program is one of {@link #ELEVATING}, or it is a shell
     * whose script names one, as init's subid write does ({@code sh -c "... | sudo tee ..."}).
     */
    public static boolean elevates(List<String> command) {
        if (command.isEmpty()) return false;
        if (ELEVATING.contains(name(command.getFirst()))) return true;
        if (List.of("sh", "bash").contains(name(command.getFirst()))) {
            return command.stream().skip(1).anyMatch(arg -> ELEVATING_WORD.matcher(arg).find());
        }
        return false;
    }


    private static String name(String program) {
        return program.substring(program.lastIndexOf('/') + 1);
    }
}
