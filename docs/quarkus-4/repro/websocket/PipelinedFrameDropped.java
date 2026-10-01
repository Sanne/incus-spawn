import io.vertx.core.Vertx;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class PipelinedFrameDropped {
  public static void main(String[] a) throws Exception {
    String mode = a.length > 0 ? a[0] : "ws-pause"; // ws-pause (default) | ws | ws-later | handshake (5.x only)
    Vertx vertx = Vertx.vertx();
    var server = vertx.createHttpServer();
    if (mode.equals("handshake")) {
      server.webSocketHandshakeHandler(hs -> hs.accept().onSuccess(ws ->
          ws.textMessageHandler(m -> ws.writeTextMessage("echo:" + m))));
    } else if (mode.equals("ws-pause")) {
      server.webSocketHandler(ws -> { ws.pause(); ws.textMessageHandler(m -> ws.writeTextMessage("echo:" + m)); vertx.setTimer(50, t -> ws.resume()); });
    } else if (mode.equals("ws-later")) {
      server.webSocketHandler(ws -> vertx.setTimer(50, t -> ws.textMessageHandler(m -> ws.writeTextMessage("echo:" + m))));
    } else {
      server.webSocketHandler(ws -> ws.textMessageHandler(m -> ws.writeTextMessage("echo:" + m)));
    }
    int port = server.listen(0).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).actualPort();
    for (String send : new String[]{"pipelined", "after101"}) {
      try (var s = new Socket("127.0.0.1", port)) {
        s.setSoTimeout(2000);
        var req = ("GET /x HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] p = "hi".getBytes(); byte[] m = {1,2,3,4};
        var f = new ByteArrayOutputStream(); f.write(0x81); f.write(0x80 | p.length); f.write(m);
        for (int i = 0; i < p.length; i++) f.write(p[i] ^ m[i % 4]);
        var out = s.getOutputStream(); var in = s.getInputStream();
        if (send.equals("pipelined")) { var all = new ByteArrayOutputStream(); all.write(req); all.write(f.toByteArray()); out.write(all.toByteArray()); }
        else out.write(req);
        out.flush();
        var head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) { int b = in.read(); if (b < 0) break; head.append((char) b); }
        if (send.equals("after101")) { out.write(f.toByteArray()); out.flush(); }
        String result;
        try { in.read(); int len = in.read() & 0x7f; result = new String(in.readNBytes(len)); }
        catch (java.net.SocketTimeoutException e) { result = "TIMEOUT (frame lost)"; }
        System.out.println(Vertx.class.getPackage().getImplementationVersion() + " mode=" + mode + " " + send + ": " + head.toString().split("\r\n")[0] + " -> " + result);
      } catch (Throwable t) { System.out.println(send + " " + t); }
    }
    vertx.close();
  }
}
