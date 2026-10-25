# Quarkus 4

This is the record of moving incus-spawn from Quarkus 3.40.1.1 to Quarkus 4.0.0.Beta2.
We are Quarkus maintainers, and we use this project to validate Quarkus 4 on a real application before it ships.
So we treat the beta as a final release: every problem still open is written down here, and the Q-* and V-* items go back upstream.
An item is removed once a release fixes it.

| | Quarkus 3.40.1.1 | Quarkus 4.0.0.Beta2 |
|---|---|---|
| Vert.x | 4.5.35 | 5.2.2 |
| Netty | 4.1.139 | 4.2.19 |
| Jackson | 2.x, test scope only since jjq (#1263) | 3.1.8 core reaches the proxy through `vertx-core`; 2.x still managed, test scope only |
| CDI API | `jakarta.enterprise` | `jakarta.cdi` 5.0.0 |
| SmallRye Config | 3.x | 4.0.0 |
| GraalVM minimum | 25.0 | 25.0 |

## Landed ahead of the upgrade

These need no Quarkus 4, so they went to `main` on their own:

- #961: a relative redirect resolves against the request's authority, which pins the `authority()` switch.
- #962: `MitmTlsTest`, the SNI lookup off the event loop, and a reload that keeps per-SNI certificates, protocol and ALPN.
- #963: tests for the WebSocket relay's early frames, including the pipelined frame Vert.x 5 drops.
- #964: experimental native-image options locked again after unlocking them.
- #1025: `quarkus-jackson` dropped, since under Quarkus 4 it is the Jackson 3 extension and we never injected its mapper.
- #1669: client pools sized with `PoolOptions`, and `WebSocketClient` for the upstream WebSocket leg.
- #1687: `MitmProxy.mitmServerOptions()`, which `start()` and `reload()` both build from, with the SSL engine on worker threads.

## What the upgrade itself changes

Every remaining change is in the proxy; `common` and `cli` compile and pass unchanged.

- **The WebSocket handshake.** `webSocketHandler` with `setHandshake(Future)` became `webSocketHandshakeHandler(ServerWebSocketHandshake)`.
  The future that waited for the caller's account now ends in `reject(status)` or `accept()`, and the relay is wired once `accept()` succeeds.
  Vert.x 5 leaves a handshake that is neither accepted nor rejected open until the idle timeout, where Vert.x 4 completed it when the handler returned.
  So every path decides it: an unexpected exception rejects with 502, a failed `accept()` is logged, and a failure wiring the relay closes the client with 1011.
  The accepted socket is paused first thing.
- **`HttpClientRequest.getHost()/getPort()` became `authority()`.**
  It is the request's own `Host`, never the `setServer()` target, so redirects and download resumes behave as before.
- **`updateSSLOptions` takes `ServerSSLOptions`.**
  `HttpServerOptions.getSslOptions()` returns one, so the reload from `mitmServerOptions()` (#1687) compiles unchanged.
- **`sendHead()` became `writeHead()`** for the 100-continue relay; `sendHead()` is deprecated for removal in Vert.x 6.
- **Test doubles**: `WriteStream.write(Buffer, Handler)` and `end(Handler)` are gone, and `HttpServerRequest.host()` became `authority()`.
- **The pipelined-frame test is disabled** (V-W1): Vert.x 5.2.2 drops a frame sent in the same write as the upgrade request, before the proxy sees the socket.
  RFC 6455 says a client must wait for the 101, so nothing a conforming client does is affected.
  The frame's buffer also leaks, so a guest that ignores the RFC can grow the proxy's direct memory with every upgrade.
  Nothing in the proxy can release it; the fix has to come from Vert.x.

## Verification

On Oracle GraalVM 25.0.4.1, x86_64, against Quarkus 4.0.0.Beta2:

- **`mvn package`**: all three modules pass; the proxy runs 821 tests and skips 2 (the V-W1 test on purpose, and a `JitWorkaroundTest` check that needs the Graal JIT, as on `main`).
- **Native proxy**: builds at 101.7 MB, against 101.5 MB on Quarkus 3; 2.2 MiB of that is Q-1, which we do not work around.
- **Repro programs**: rerun on Vert.x 5.2.2; V-T1 and V-W1 still reproduce.

Not yet validated: real Incus (`validate-on-real-incus`), proxy throughput, latency and memory (`bench/run.sh`, D-3), macOS, aarch64, and the release workflow with PGO.

## Open decisions

- **D-1: Jackson 2 in tests.**
  Since #1263 no Jackson runs in either binary, but test doubles and fixtures in all three modules still use Jackson 2 (`jackson-databind`, `jackson-dataformat-yaml`, test scope).
  The Quarkus 4 BOM still manages it, so this does not block the upgrade; #1702 moves them to jjq and the schema validator to Jackson 3.
- **D-2: Connect upstream before accepting a client WebSocket?**
  The handshake split allows it, and upstream's 401, 403 or 429 would then reach the client as a real HTTP status instead of a 101 followed by close 1011.
  `reject(int)` cannot carry headers or a body, and `accept()` cannot choose the sub-protocol (V-W4), so it is a follow-up.
- **D-3: Netty allocator.**
  Netty 4.2 defaults to `AdaptiveByteBufAllocator`, and Quarkus 4 sets `io.netty.noUnsafe=true` in native (Q-7).
  A JVM-mode test, four 32 MB TLS relays, measured peak RSS of 625 MB on Vert.x 4, 533 MB on Vert.x 5 pooled and 448 MB on Vert.x 5 adaptive.
  The adaptive allocator kept 68 MB of direct memory after the burst, against 8 MB pooled.
  The native proxy runs with `-R:MaxRAM=256m`, so `bench/run.sh` on the native binary decides whether we pin one.
- **D-4: `sendFile(FileChannel)`.**
  New in Vert.x 5, it would close the stat-then-open window in `serveTrusted`, but it is marked unstable and HTTP/1-only.
- **D-5: `InterceptedCertOptions.MAX_SERVED_NAMES`.**
  Its javadoc says Vert.x caches an `SslContext` per SNI name forever, but both 4.5 and 5 keep a 16-entry LRU (`SslContextProvider.DEFAULT_SNI_CACHE_SIZE`).
  The cap guards nothing on Vert.x's side, and with more than 16 hot names every miss rebuilds a context.

## Feedback for the Quarkus team

Q-1, Q-3 and Q-7 are confirmed against Beta2.
Q-8 to Q-11 were noted during the port and are not rechecked.

- **Q-1 (native size regression): the async DNS resolver is back in native images.**
  Commit `12145237998` ("Migrate Vert.x Core extension to Vert.x 5") dropped the Quarkus 3 substitution of `ResolverProvider.factory`.
  Vert.x 5 reads `vertx.disableDnsResolver` at run time, so Netty's resolver and DNS codec are linked although Quarkus forces the property in native.
  That costs 2.2 MiB in our native proxy (106,827,040 bytes against 104,533,280); substituting `AddressResolverProvider.factory` with `DefaultAddressResolverProvider` removes it.
  We do not carry that substitution ourselves, since it targets Vert.x internals (#1853, deferred).
- **Q-3: Jackson 3 core is always reachable in Vert.x native images.**
  `VertxCoreProcessor` registers `io.vertx.core.json.jackson.v3.JacksonCodec` for reflection unconditionally, which adds 0.87 MiB with no JSON in use.
- **Q-7 (undocumented default): `io.netty.noUnsafe=true` in native.**
  It changes buffer performance for TLS-heavy native apps, and neither the migration guide nor the release notes mention it.
- **Q-8 (stale tuning): `quarkus.netty.allocator-max-order` is inert by default.**
  Netty 4.2 defaults to the adaptive allocator, and `io.netty.allocator.maxOrder` only affects the pooled one.
  Quarkus should pin `io.netty.allocator.type` or warn when the property is set.
- **Q-9 (migration guide gaps).**
  It has no Vert.x 4 to 5 or Netty 4.2 section, not even a pointer to the Vert.x guide.
  It does not say that Jackson 2 is still managed, or that `quarkus-jackson` is now Jackson 3.
  Its Jackson defaults table omits `SORT_PROPERTIES_ALPHABETICALLY`, which silently changes serialized output.
  The CDI API group change and the `smallrye-common-vertx-context` rename are missing, and both matter to explicit exclusions and enforcer rules.
- **Q-10 (minor).**
  The native build still passes the deprecated `--enable-url-protocols`, and the `NativeConfig` javadoc still names `jdk-21` builder images.
  The uber-jar builder warns about duplicate Jackson 2 and 3 license files, which the BOM itself produces.
  `vertx-core` hard-depends on the HTTP/3, QUIC and tcnative classes, and `quarkus-netty` on Netty's `netty-codec` umbrella: none reaches a native image, but all sit on the JVM classpath.
- **Q-11 (suggestion): reflection-free Jackson outside REST.**
  The build-time generated (de)serializers exist only in `quarkus-rest-jackson`, for REST endpoint types.
  An application mapping its own model with mappers it builds itself has no way to use them.

## Feedback for the Vert.x team (5.2.2)

The reproducers are in [`quarkus-4-repro/`](quarkus-4-repro/), each a single Java file depending only on `vertx-core`.
`./run.sh <vertx-version> <file>` runs one, and it exits 1 when the bug reproduces.

V-T1 and V-W1 are confirmed against 5.2.2 by the reproducers.
The other items were read from the sources and are not rechecked.

- **V-T1 (regression): a server's SNI key-manager lookup runs on the event loop by default.**
  4.5 always ran `keyManagerFactoryMapper` on a worker; 5.2.2 runs it on the engine's delegated-task executor, which is immediate unless `setUseWorkerThread(true)`.
  The `serverNameAsyncMapping` javadoc still says "worker thread".
  Repro: `tls/SniMappingOnEventLoop.java`.
- **V-T4 (docs): `updateSSLOptions(ServerSSLOptions)` ignores the update's SNI, ALPN and handshake timeout.**
  Those stay as captured at listen time.
- **V-T5 (minor):** superseded server SSL providers stay in a 64-entry cache after reloads, and the per-SNI `SslContext` cache is a fixed, undocumented 16-entry LRU.
- **V-T6 (migration guide):** Netty 4.2 clients verify hostnames by default, which changes code that builds Netty `SslContext`s directly.
- **V-W1 (regression and buffer leak): a frame pipelined with the upgrade request is dropped.**
  It is decoded before the HTTP handler is swapped for the WebSocket one, and `Http1ServerConnection.handleMessage` ignores it without releasing its buffer.
  Repro: `websocket/PipelinedFrameDropped.java`, and `websocket/PipelinedFrameLeaks.java` for the leak.
- **V-W2:** the server-side `accept()` hands over an unpaused socket, while the client side pauses a new WebSocket until the user's callback has run.
- **V-W3 (docs):** the `webSocketHandshakeHandler` javadoc does not say that it now works without a `webSocketHandler`, that setting both runs both, that an undecided handshake hangs until the idle timeout, or that `accept()` resolves only after the 101 is flushed.
- **V-W4 (API gap for proxies):** `accept()` cannot set the sub-protocol or response headers, and `reject(int)` cannot send headers or a body.
- **V-W5:** `WebSocketConnectOptions` inherits `setServer()`, but `WebSocketClient` uses it only when no host is set, so a caller cannot pin an address while keeping SNI.
- **V-W6:** `ServerWebSocketHandshaker.reject()` fails its own future with a bare `RejectedExecutionException`.
- **V-H1 (docs):** nothing says whether `HttpClientRequest.authority()` is the `Host` value or the connect target under `setServer()`; it is the former.
- **V-H2:** client ALPN is decided by whether `HTTP_2` is in the protocol list, not by `setUseAlpn`, and Vert.x 5 now also offers `http/1.0`.
- **V-H3 (minor):** `LIFO_SELECTOR` never updates `last`, and `PoolOptions()` sets `http3MaxSize` from the HTTP/2 default.
- **V-P1:** `vertx-core`'s `Java21` profile makes Jackson 3 core and databind non-optional.
  Quarkus excludes databind in its BOM (quarkusio/quarkus#57401), so it no longer reaches Quarkus apps.

## Known before the upgrade, not changed by it

- The WebSocket relay has no upgrade timeout, and the 51st concurrent WebSocket to one host waits with none either.
- The WebSocket relay writes frames with no backpressure.
- The MITM server never selects a sub-protocol, while upstream may choose one.
