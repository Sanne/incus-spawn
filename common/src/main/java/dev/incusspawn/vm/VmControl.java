package dev.incusspawn.vm;

import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Client for the control socket of {@code isx-vm}, the launcher that runs the VM on macOS (#2031).
 * <p>
 * The launcher listens on {@code vm.control.sock} in the VM's state directory, for this user
 * alone, and takes one verb a connection, as the guest's agent does: {@code state} (with its pid),
 * {@code counts} and {@code stop}. So the request goes through {@link VmAgentClient#send(Path, String)},
 * whose timeout bounds the whole exchange.
 * <p>
 * {@code counts} is what the relays hold, said by the relays themselves, one line per relayed
 * port: {@code <port> waiting N held N total N refused N}.
 */
final class VmControl {

    private static final Pattern COUNTS_LINE =
            Pattern.compile("(\\d{1,9}) waiting (\\d{1,9}) held (\\d{1,9}) total (\\d{1,9}) refused (\\d{1,9})");

    private VmControl() {}

    /** Asks the launcher to stop the machine, as SIGTERM does; true when it said it will. */
    static boolean stop(Path socket) {
        return VmAgentClient.send(socket, "stop").map("stopping"::equals).orElse(false);
    }

    private static final Pattern STATE_LINE = Pattern.compile("(running|stopping) (\\d{1,10})");

    /** What the launcher said of itself: whether it is stopping its machine, and its pid. */
    record State(boolean stopping, long pid) {}

    /** The state of the launcher answering on {@code socket}, or empty when none answers. */
    static Optional<State> state(Path socket) {
        return VmAgentClient.send(socket, "state").flatMap(VmControl::state);
    }

    static Optional<State> state(String stateReply) {
        var m = STATE_LINE.matcher(stateReply.strip());
        return m.matches() ? Optional.of(new State("stopping".equals(m.group(1)), Long.parseLong(m.group(2))))
                : Optional.empty();
    }

    /** Connections the relay of guest port {@code port} holds now, or empty when it is not known. */
    static OptionalInt held(Path socket, int port) {
        return VmAgentClient.send(socket, "counts").map(reply -> held(reply, port)).orElse(OptionalInt.empty());
    }

    static OptionalInt held(String countsReply, int port) {
        for (var line : countsReply.split("\n")) {
            var m = COUNTS_LINE.matcher(line.strip());
            if (m.matches() && Integer.parseInt(m.group(1)) == port) {
                return OptionalInt.of(Integer.parseInt(m.group(3)));
            }
        }
        return OptionalInt.empty();
    }
}
