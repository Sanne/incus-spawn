package dev.incusspawn.proxy;

import io.netty.channel.Channel;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpVersion;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Upstream's response body, held back on the proxy's backpressure by not reading its socket,
 * never by pausing the response in Vert.x (#2088).
 * <p>
 * A paused Vert.x 5 HTTP/1.x client connection queues what still arrives, and drops that queue
 * when upstream closes ({@code VertxConnection.handleClosed}): the body's last chunks and its end
 * are lost, and the response fails with "Connection was closed". A server closes after a body
 * whenever it answers HTTP/1.0, sends {@code Connection: close}, reaches its request limit or
 * times the connection out. So the response is never paused: holding it turns off the socket's
 * auto-read, and what Netty had already read waits here, in order, with the end or the error
 * behind it. That is at most what one read brings, or the socket's receive buffer once upstream
 * has closed, as epoll then reads on to the end.
 * <p>
 * Every call, and every handler, runs on the response's context. Once Vert.x has delivered the
 * response's end or error, the connection may serve another request, so reading is turned back on
 * then, whatever is still held here. HTTP/2 is held by pausing its stream, whose flow control
 * keeps what arrives.
 * <p>
 * Vert.x still pauses the connection itself when the response's context is not on the
 * connection's own event loop (#2176): the stream's queue then crosses threads, and above 16
 * chunks it pauses the connection, whose queued tail a close drops as above. It never does on
 * the connection's loop, where each chunk is handed on as it is read. Every response is there
 * today, because the MITM listener is one server on one event loop, so every request and every
 * upstream connection it opens run on that loop. A response anywhere else is counted and warned
 * about once ({@link #offLoop}), never served differently.
 */
final class ResponseHold {
    // Responses whose context was off their connection's event loop (#2176)
    private static final AtomicLong OFF_LOOP = new AtomicLong();
    // HTTP/1.x responses whose loop was checked, so a test of the count above is not vacuous
    private static final AtomicLong CHECKED = new AtomicLong();

    private final HttpClientResponse response;
    // The HTTP/1.x connection's socket; null for HTTP/2 or 3, whose stream is paused instead
    private final Channel channel;
    private final ArrayDeque<Buffer> held = new ArrayDeque<>();
    private Handler<Buffer> handler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;
    private boolean holding;
    // This hold turned the socket's reading off, and has yet to turn it back on
    private boolean readsOff;
    // Vert.x has delivered the end, or the error: the connection is no longer this response's
    private boolean ended;
    private Throwable failure;
    // The end or error was handed on, or the response let go: nothing more is
    private boolean finished;
    private boolean delivering;

    ResponseHold(HttpClientResponse response) {
        this.response = response;
        this.channel = channelOf(response);
        response.handler(this::arrived);
        response.endHandler(v -> {
            if (over()) ended = true;
            deliver();
        });
        response.exceptionHandler(err -> {
            if (over()) failure = err;
            deliver();
        });
    }

    private static Channel channelOf(HttpClientResponse response) {
        if (response.version() != HttpVersion.HTTP_1_1 && response.version() != HttpVersion.HTTP_1_0) return null;
        // Vert.x's own connection type: what holds the socket is not in its public API
        if (!(response.request().connection() instanceof io.vertx.core.http.impl.HttpClientConnection conn)) return null;
        var channel = conn.channelHandlerContext().channel();
        CHECKED.incrementAndGet();
        // This runs in the response's handler, so on the response's context
        if (!(Vertx.currentContext() instanceof io.vertx.core.internal.ContextInternal context)
                || !context.isEventLoopContext() || context.nettyEventLoop() != channel.eventLoop()) {
            if (OFF_LOOP.getAndIncrement() == 0) {
                ProxyLog.warn("An upstream response is handled off its connection's event loop, so a close "
                        + "after a burst of chunks may cut its end (#2176): please report this");
            }
        }
        return channel;
    }

    /** How many responses ran off their connection's event loop, where Vert.x can pause it itself (#2176). */
    static long offLoop() {
        return OFF_LOOP.get();
    }

    /** How many HTTP/1.x responses had their event loop checked for {@link #offLoop}. */
    static long checked() {
        return CHECKED.get();
    }

    HttpClientResponse response() {
        return response;
    }

    ResponseHold handler(Handler<Buffer> handler) {
        this.handler = handler;
        return this;
    }

    ResponseHold endHandler(Handler<Void> endHandler) {
        this.endHandler = endHandler;
        return this;
    }

    ResponseHold exceptionHandler(Handler<Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    /** Stops handing on the body, and reading it, until {@link #release}. */
    void hold() {
        if (channel == null) {
            response.pause();
            return;
        }
        holding = true;
        stopReading();
    }

    /** Hands on what is held, the end or error behind it, then reads on, unless a handler held it again. */
    void release() {
        if (channel == null) {
            response.resume();
            return;
        }
        holding = false;
        deliver();
        if (!holding) startReading();
    }

    /**
     * Nothing more is handed on, and the socket reads again: the request is to be reset. The
     * response keeps this hold's own handlers, which now drop what comes: Vert.x refuses a new
     * handler on a response it has ended, as one whose end or error is still held here is.
     */
    void letGo() {
        finished = true;
        held.clear();
        handler = null;
        endHandler = null;
        exceptionHandler = null;
        startReading();
    }

    /** Lets the response go, then resets its request: nothing of it is to be relayed. */
    void reset() {
        letGo();
        response.request().reset();
    }

    /** Whether this is the first of the response's end and error: one after the other is not handed on. */
    private boolean over() {
        if (ended || failure != null) return false;
        startReading();
        return true;
    }

    private void arrived(Buffer chunk) {
        if (finished) return;
        if (holding || delivering || !held.isEmpty()) {
            held.add(chunk);
            // Vert.x turns reading back on once its own queue empties, if it ever turned it off
            if (holding) stopReading();
        } else if (handler != null) {
            handler.handle(chunk);
        }
    }

    private void deliver() {
        if (delivering) return;
        delivering = true;
        try {
            while (!holding && !finished && !held.isEmpty()) {
                var chunk = held.poll();
                if (handler != null) handler.handle(chunk);
            }
            if (holding || finished || !held.isEmpty()) return;
            if (ended) {
                finished = true;
                if (endHandler != null) endHandler.handle(null);
            } else if (failure != null) {
                finished = true;
                if (exceptionHandler != null) exceptionHandler.handle(failure);
            }
        } finally {
            delivering = false;
        }
    }

    private void stopReading() {
        if (ended || failure != null) return;
        readsOff = true;
        channel.config().setAutoRead(false);
    }

    private void startReading() {
        if (!readsOff) return;
        readsOff = false;
        channel.config().setAutoRead(true);
    }
}
