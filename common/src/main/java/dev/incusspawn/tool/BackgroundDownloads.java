package dev.incusspawn.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Fetches a build's downloads into the host's download cache ahead of the build, while the person
 * answers questions (#2411): neither Incus nor sudo is needed, only the cache.
 *
 * <p>It never reports anything, progress or failure alike, since a question may be on screen
 * the whole time. Whatever it fetched is then a cache hit for the build. The build waits for a
 * file still being fetched under the lock {@link DownloadCache} holds per URL, and shows that
 * download's progress ({@link DownloadCache.Listener#anotherReceived}), whether this process or
 * another holds it. A file it failed to fetch is fetched again by the build, which reports the
 * failure, continuing what was fetched when the server can resume it.
 *
 * <p>Only {@link DownloadCache#download} is used, so every limit the cache puts on a definition's
 * URL holds here too: {@code http(s)} only, never a host-local address.
 */
public final class BackgroundDownloads implements AutoCloseable {

    private final Thread thread;
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<String> fetched = Collections.synchronizedList(new ArrayList<>());
    private final List<String> failed = Collections.synchronizedList(new ArrayList<>());

    private BackgroundDownloads(DownloadCache cache, Callable<List<DownloadCache.Pinned>> plan) {
        // A daemon: a person who leaves init keeps what finished, and a partial file is continued
        // by the next run, so nothing here holds the process open
        thread = Thread.ofPlatform().daemon().name("isx-downloads-ahead").unstarted(() -> run(cache, plan));
    }

    /**
     * Starts fetching, one file at a time in the plan's order, on a thread of its own. The plan is
     * computed on that thread too, since it may ask the network which base image is the latest;
     * it must print nothing.
     */
    public static BackgroundDownloads start(DownloadCache cache, Callable<List<DownloadCache.Pinned>> plan) {
        var downloads = new BackgroundDownloads(cache, plan);
        downloads.thread.start();
        return downloads;
    }

    private void run(DownloadCache cache, Callable<List<DownloadCache.Pinned>> plan) {
        try {
            List<DownloadCache.Pinned> files;
            try {
                files = plan.call();
            } catch (Exception | LinkageError e) {
                // The build plans again, and says what is wrong
                return;
            }
            // A checksum is what lets the build reuse the file: one without it is fetched again
            var seen = new LinkedHashSet<String>();
            for (var file : files) {
                if (Thread.currentThread().isInterrupted()) return;
                if (file.sha256() == null || !seen.add(file.url())) continue;
                try {
                    cache.download(file.url(), file.sha256());
                    fetched.add(file.url());
                } catch (Exception e) {
                    // Retried by the build, which reports it there
                    failed.add(file.url());
                    if (Thread.currentThread().isInterrupted()) return;
                }
            }
        } finally {
            done.countDown();
        }
    }

    /** Whether every planned download has finished or failed, within {@code timeout}. */
    public boolean await(Duration timeout) throws InterruptedException {
        return done.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** The URLs fetched into the cache so far. */
    public List<String> fetched() {
        return List.copyOf(fetched);
    }

    /** The URLs that failed, which nothing reports: the build fetches each again. */
    public List<String> failed() {
        return List.copyOf(failed);
    }

    /**
     * Stops fetching. What finished stays in the cache, and a partial file is kept for the next
     * download of its URL to continue.
     *
     * <p>The interrupt cancels the HTTP exchange, so the thread normally ends at once. Should it
     * still run when the join gives up, nothing more is done: it holds the lock of at most the one
     * URL it is fetching, and a build that needs that file waits for it under that lock, showing
     * its progress, then reuses it, as for a download still running ahead. The lock's wait is
     * bounded ({@code DownloadCache.LOCK_TIMEOUT}), after which the build fetches into a file of
     * its own. The thread is a daemon, so it never holds the process open.
     */
    @Override
    public void close() {
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
