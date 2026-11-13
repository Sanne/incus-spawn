package dev.incusspawn.util;

/**
 * Makes the JDK wake virtual threads blocked on a socket from platform threads, not from
 * virtual ones (#2129).
 * <p>
 * Since JDK 22 the default poller on Linux ({@code VTHREAD_POLLERS}) delivers a socket's
 * readiness through sub-pollers that are virtual threads, unparked by one platform master
 * poller. In CI that wakeup did not reach {@code isx ssh-proxy}: Incus had answered all four
 * exec sockets, and isx read nothing for 30 s, until the stdin socket's handshake watchdog,
 * itself a virtual thread that ran on time, closed it. The close unparked the reader from the
 * closing thread, without a sub-poller; the poller's wakeup never came. JDK-8371045 is one
 * known way virtual sub-pollers starve (every carrier pinned by class initialization), though
 * not what happened there, since the watchdog ran. The platform-thread mode
 * ({@code SYSTEM_THREADS}, mode 1) has its own threads block in {@code epoll_wait} and unpark
 * the reader, which takes the virtual sub-pollers out of the wakeup path. A woken reader still
 * needs a carrier to run, in either mode.
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
