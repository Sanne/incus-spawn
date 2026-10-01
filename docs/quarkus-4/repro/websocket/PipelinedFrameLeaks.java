import io.vertx.core.Vertx;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
public class PipelinedFrameLeaks {
  public static void main(String[] a) throws Exception {
    Vertx vertx = Vertx.vertx();
    var server = vertx.createHttpServer().webSocketHandler(ws -> ws.textMessageHandler(m -> {}));
    int port = server.listen(0).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).actualPort();
    for (int n = 0; n < 300; n++) {
      try (var s = new Socket("127.0.0.1", port)) {
        var req = ("GET /x HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes();
        byte[] p = new byte[100]; byte[] m = {1,2,3,4};
        var all = new ByteArrayOutputStream(); all.write(req); all.write(0x82); all.write(0x80 | p.length); all.write(m); all.write(p);
        s.getOutputStream().write(all.toByteArray()); s.getOutputStream().flush();
        s.getInputStream().read(new byte[200]);
      }
      if (n % 50 == 0) { System.gc(); Thread.sleep(50); }
    }
    System.gc(); Thread.sleep(500);
    // trigger leak reporting by allocating more
    for (int n = 0; n < 50; n++) { try (var s = new Socket("127.0.0.1", port)) { s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes()); s.getInputStream().read(); } System.gc(); }
    vertx.close();
  }
}
