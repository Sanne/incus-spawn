package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HttpsTransport.HttpsWsConnection — specifically the sendClose()
 * fix for the race where the server closes the WebSocket before the client
 * sends its close frame.
 */
class HttpsWsConnectionTest {

    @Test
    void sendCloseUnwrapsIOExceptionFromCompletionException() {
        var conn = new HttpsTransport.HttpsWsConnection();
        conn.setWebSocket(stubWebSocket(
                CompletableFuture.failedFuture(new IOException("Output closed"))));

        var ex = assertThrows(IOException.class, conn::sendClose,
                "sendClose should unwrap IOException from CompletionException");
        assertEquals("Output closed", ex.getMessage());
    }

    @Test
    void sendCloseRethrowsNonIOExceptionAsIOException() {
        var conn = new HttpsTransport.HttpsWsConnection();
        conn.setWebSocket(stubWebSocket(
                CompletableFuture.failedFuture(new RuntimeException("unexpected"))));

        var ex = assertThrows(IOException.class, conn::sendClose,
                "sendClose should rethrow non-IO CompletionException as IOException");
        assertInstanceOf(RuntimeException.class, ex.getCause());
    }

    @Test
    void sendCloseSucceedsNormally() {
        var conn = new HttpsTransport.HttpsWsConnection();
        conn.setWebSocket(stubWebSocket(
                CompletableFuture.completedFuture(null)));

        assertDoesNotThrow(conn::sendClose,
                "sendClose should complete without error on a clean close");
    }

    private static WebSocket stubWebSocket(CompletableFuture<WebSocket> sendCloseResult) {
        return new WebSocket() {
            @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
                return sendCloseResult;
            }
            @Override public void request(long n) {}
            @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
                return CompletableFuture.completedFuture(this);
            }
            @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
                return CompletableFuture.completedFuture(this);
            }
            @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
                return CompletableFuture.completedFuture(this);
            }
            @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
                return CompletableFuture.completedFuture(this);
            }
            @Override public String getSubprotocol() { return ""; }
            @Override public boolean isOutputClosed() { return false; }
            @Override public boolean isInputClosed() { return false; }
            @Override public void abort() {}
        };
    }
}
