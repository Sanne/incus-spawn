package dev.incusspawn.util;

/**
 * Makes the JDK wake virtual threads blocked on a socket from platform threads, not from
 * virtual ones (#2129).
 * <p>
 * Since JDK 22 the default poller on Linux ({@code VTHREAD_POLLERS}) delivers a socket's
 * readiness through sub-pollers that are virtual threads, unparked by one platform master
 * poller. In a native image such a sub-poller can lose its wakeup for good, and every socket it
 * serves then stays unread until something closes it. In CI that held {@code isx ssh-proxy} for
 * 30 s: Incus had answered all four exec sockets, and isx read nothing until the stdin socket's
 * handshake watchdog closed it.
 * <p>
 * The first virtual thread to wait on a socket runs the static initializer of
 * {@code sun.nio.ch.Poller}, which starts the sub-pollers before it returns. A sub-poller that
 * starts meanwhile registers with the master poller and then waits for that initializer to
 * finish. GraalVM's native image makes that wait with a lock condition, which parks through
 * {@code LockSupport} and so uses the virtual thread's one park permit; HotSpot waits inside
 * the VM and leaves the permit alone. When the master poller unparks the sub-poller before it
 * has run again, that unpark and the initializer's become one permit, the class-initialization
 * wait spends it, and the sub-poller then parks with nobody left to wake it. The window is the
 * time the sub-poller waits for a CPU, so few or busy cores make it likely (design/native-image.md
 * "Socket wakeups come from platform threads").
 * <p>
 * The platform-thread mode ({@code SYSTEM_THREADS}, mode 1) has its own threads block in
 * {@code epoll_wait} and unpark the reader. No virtual thread polls, so none holds a
 * registration while it waits for the initializer. A woken reader still needs a carrier to run,
 * in either mode.
 * <p>
 * The JDK reads {@code jdk.pollerMode} once, when the first virtual thread waits on a socket,
 * a pipe or a selector, so this must run before that: first thing in each binary's entry
 * point. A value already set, such as {@code -Djdk.pollerMode=2} to compare the modes, is kept.
 */
public final class VirtualThreadPollers {

    /** The system property the JDK's {@code sun.nio.ch.Poller} reads. */
    static final String PROPERTY = "jdk.pollerMode";
    /** {@code SYSTEM_THREADS}: dedicated platform threads named {@code Read-Poller} and {@code Write-Poller}. */
    static final String PLATFORM_THREADS = "1";

    private VirtualThreadPollers() {}

    /** Selects platform-thread pollers unless a mode was set already; call before any virtual thread uses a socket. */
    public static void usePlatformThreads() {
        if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, PLATFORM_THREADS);
        }
    }
}
