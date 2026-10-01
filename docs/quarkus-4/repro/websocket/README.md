# Vert.x 5 WebSocket reproducers

Run from `docs/quarkus-4/repro/`. See V-W1 in [`../../MIGRATION.md`](../../MIGRATION.md).

## A frame pipelined with the upgrade request is dropped

```sh
./run.sh 5.1.8  websocket/PipelinedFrameDropped.java            # pipelined: TIMEOUT (frame lost)
./run.sh 4.5.34 websocket/PipelinedFrameDropped.java            # pipelined: echo:hi
./run.sh 5.1.8  websocket/PipelinedFrameDropped.java handshake  # lost via webSocketHandshakeHandler too
```

The program starts an echo WebSocket server and opens two raw connections. The first sends a text frame in the same write as the upgrade request (`pipelined`). The second sends the frame only after the 101 (`after101`). It prints what came back for each.

Modes:
- `ws-pause` (default): `webSocketHandler` pauses the socket, sets its handler, and resumes 50 ms later. This is how a relaying proxy behaves.
- `ws`: the handler is set immediately, with no pause.
- `ws-later`: the handler is set 50 ms later, with no pause, so frames are lost on both versions. This is the control.
- `handshake`: `webSocketHandshakeHandler` then `accept()`, 5.x only.

Observed:

| Mode | 4.5.34 pipelined | 5.1.8 pipelined | after101 (both) |
|---|---|---|---|
| `ws-pause` | echoed | **lost** | echoed |
| `ws` | lost | lost | echoed |
| `ws-later` | lost | lost | lost |
| `handshake` | n/a | **lost** | echoed |

## The dropped frame's buffer leaks

```sh
./run.sh 5.1.8  websocket/PipelinedFrameLeaks.java 2>&1 | grep -c LEAK:   # 31
./run.sh 4.5.34 websocket/PipelinedFrameLeaks.java 2>&1 | grep -c LEAK:   # 0
```

`run.sh` enables `-Dio.netty.leakDetection.level=paranoid`. The ignored frame is never released in `Http1ServerConnection.handleMessage` (5.1.8, `:200-238`).
