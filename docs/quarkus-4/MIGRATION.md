# Migrating incus-spawn to Quarkus 4

This document records the migration of incus-spawn from Quarkus 3.40.0.CR1 to **Quarkus 4.0.0.Beta1** (released 2026-10-01). The work started on a `main` from early October and was rebased on 2026-10-25 onto a `main` that had meanwhile moved to Quarkus 3.40.1.1 (Vert.x 4.5.35, Netty 4.1.139) and replaced Jackson with jjq (#1263); the comparisons below were made against 3.40.0.CR1 unless a row says otherwise. We treat Beta1 as we would a final release. Every problem we hit, and every doubt we could not settle from documentation alone, is written down here. Part of it is meant to go back to the Quarkus and Vert.x teams as feedback.

Versions compared:

| | Quarkus 3.40.0.CR1 | Quarkus 4.0.0.Beta1 |
|---|---|---|
| Vert.x | 4.5.34 | 5.1.8 |
| Netty | 4.1.138 | 4.2.18 |
| Jackson | 2.22.2 | 3.1.4 (Jackson 2.22.2 still managed). Since #1263 our code uses neither at run time: Jackson 2 is test-scoped, and only Jackson 3 core reaches the proxy, through `vertx-core`. |
| CDI API | `jakarta.enterprise` | `jakarta.cdi` 5.0.0 |
| SmallRye Config | 3.x | 4.0.0 |
| GraalVM minimum | 25.0 | 25.0 |

## Status

| Area | State |
|---|---|
| JVM build and unit tests | Green after the rebase (2026-10-25): 2966 (common) + 1808 (cli) + 816 (proxy). Skipped: 7 macOS-only in common, 7 in cli (`TaskScriptsTest`), and 2 in proxy, the V-W1 test on purpose and a `JitWorkaroundTest` check that needs the Graal JIT, both as on `main`. |
| Native build, both binaries | Green with local Oracle GraalVM 25.0.4.1, x86_64 |
| Native smoke test | `isx --version` and `isx tools show maven-3` work. The proxy answers `/health`, proxies Maven over TLS (miss, then a byte-identical hit), mints a leaf for a name two labels under an intercepted domain (the SNI lookup now on a worker thread), relays to Anthropic, and serves leaves of the new CA after a CA-rotating SIGHUP reload. Since #1402 a caller that is not a known instance gets no credential injected, so the Anthropic request now comes back 401 from upstream, on `main` as here. |
| Startup time | Unchanged. Against 3.40.0.CR1: CLI 4.58 ms vs 4.58 ms, proxy 3.70 ms vs 3.54 ms. After the rebase, `--version` against `main` (3.40.1.1, hyperfine, 300 runs): CLI 2.3 ms vs 2.2 ms, proxy 3.3 ms vs 3.6 ms. |
| Native size | Against 3.40.0.CR1: CLI 34.3 MB, same as before; proxy 92.8 MB to 98.5 MB, see Q-3 and Q-4. After the rebase, against `main` (3.40.1.1, same GraalVM): CLI 37.9 MB on both (the image grows by 2 KB); proxy 99.4 MB to 104.1 MB (10^6 bytes; +3.6 MB code, +1.1 MB image heap). |
| Not yet validated | Real Incus (`validate-on-real-incus`), proxy throughput and latency (`bench/run.sh`), macOS, aarch64, the CI release matrix |
| Not yet done | Upstream issues not yet filed (Q-*, V-*) |

## What we changed, and why

The work is on branch `quarkus-4`, one concern per commit. Changes 2 and 7 were merged into `main` ahead of the upgrade (see below), so they are no longer part of the branch.

1. **Version bump and Vert.x 5 port of the proxy** (`MitmProxy`, `Upstream`, `HostRouteRelay`, `ArtifactCacheHandler`, and the tests that build their own Vert.x clients). Every change here is mechanical:
   - `HttpClientOptions.setMaxPoolSize`/`setHttp2MaxPoolSize` became `PoolOptions.setHttp1MaxSize`/`setHttp2MaxSize`. We traced Vert.x 4.5 and confirmed it built a `PoolOptions` from those setters itself, so the mapping is exact.
   - The WebSocket upstream moved from `HttpClient.webSocket` to `WebSocketClient.connect` with `WebSocketClientOptions`; their defaults are identical.
   - `webSocketHandler(ServerWebSocket)` became `webSocketHandshakeHandler(ServerWebSocketHandshake)`, using `reject(status)` for routing failures and `accept()` to proceed. On `main`, #972 had the handler defer Vert.x 4's handshake with `setHandshake(Future<Integer>)` until the caller's account was resolved, and dial upstream only once Netty accepted it. That maps one to one: the same future now ends in `reject(status)` or `accept()`, and the relay is wired once `accept()` succeeds.
   - `HttpClientRequest.sendHead()`, which `main`'s relay of upstream's 100 Continue uses, is deprecated for removal in Vert.x 6; it is now `writeHead()`. This one compiles, so only a `-Xlint:deprecation` build finds it.
   - `WriteStream.write(Buffer, Handler)` and `end(Handler)` are gone (a test double in `BodySpoolTest`), and `HttpServerRequest.host()` became `authority()` (a test server).
   - `updateSSLOptions` now takes `ServerSSLOptions`.
   - `HttpClientRequest.getHost()/getPort()` became `authority().host()/port()`. This is the request's own host and port (the `Host` header), never the `setServer()` target, so redirects and download resumes behave as before. A new test covers a relative redirect.
2. **Dropped `quarkus-jackson`** from `cli` and `proxy` (merged as #1025). Under Quarkus 4 it is the Jackson 3 extension, and we never injected its `ObjectMapper`. All of our Jackson-mapped classes carry `@RegisterForReflection`, so native mapping does not depend on it; we verified this on the native CLI.
3. **Excluded Jackson 3 `jackson-databind` from `quarkus-vertx`** in `proxy`. Without the exclusion the native proxy grows from 92.8 MB to 118 MB, see Q-2.
4. **MITM server SSL engine on worker threads** (`JdkSSLEngineOptions.setUseWorkerThread(true)`). Without it, our SNI certificate lookup runs on the event loop, and it may generate an RSA key and write a file. See V-T1. `MitmTlsTest` pins this.
5. **Cert reload builds its `ServerSSLOptions` from the server's own options** instead of a bare object. `updateSSLOptions` replaces client auth, protocols and ciphers but silently keeps SNI, ALPN and the handshake timeout from listen time (V-T4). Starting from a bare object would silently reset any of the former. A test reloads the certificates and checks that per-SNI certificates, TLS version, ALPN and cipher stay as they were.
6. **Every WebSocket handshake is decided.** Vert.x 5 leaves a handshake that is neither accepted nor rejected hanging until the idle timeout; in 4.5 it auto-completed. An unexpected exception now rejects with 502. A failed `accept()` is logged instead of ignored. A failure wiring the relay closes the client with 1011. The accepted socket is paused first thing.
7. **Experimental native-image options are bracketed** (merged as #964) in `-H:+UnlockExperimentalVMOptions … -H:-UnlockExperimentalVMOptions`, as Quarkus does, which removes our own native-image warnings.
8. **`VertxDnsSubstitutions`** substitutes `AddressResolverProvider.factory` so Netty's async DNS resolver stays out of the native proxy (-1.2 MB). Quarkus 3 had this substitution and its Vert.x 5 port dropped it (Q-1). It is safe because Quarkus forces `vertx.disableDnsResolver=true` in every native image (`VertxCoreProcessor`).
9. **Docs** (CLAUDE.md, DESIGN.md, `.claude/rules/proxy.md`) now name the Vert.x 5 APIs and the rules that the compiler does not enforce.

## Split out ahead of the upgrade

These parts need no Quarkus 4, so they went to `main` as separate PRs, which keeps this branch smaller. All are merged:
- #961: relative-redirect test (pins the `authority()` switch).
- #962: `MitmTlsTest` (pins V-T1 and the reload behaviour; fails on the Vert.x 5 port without change 4). The branch uses `main`'s version unchanged: it watches the real server `MitmProxy.start()` builds through the mint's log line. The branch's earlier variant, which wrapped the key manager and checked every lookup's thread on a server built from `mitmServerOptions()`, was dropped, since it tested a copy of the options rather than the running server.
- #963: WebSocket early-frame tests. The pipelined-frame test passes on 4.5 and fails on 5.1.8 (V-W1), so the branch disables it.
- #964: experimental native-image options wrapped in unlock/lock (change 7).
- #1025: dropping `quarkus-jackson` (change 2).

In flight: a PR moving the proxy to `PoolOptions` and `WebSocketClient` on Vert.x 4.5, the parts of change 1 that need no Vert.x 5. Once it lands, change 1 shrinks to the handshake handler, `ServerSSLOptions` and `authority()`.

## Open decisions for us

- **D-1: Migrate our own code to Jackson 3 now?** *Mostly settled by `main`*: #1263 replaced Jackson with jjq for every YAML and JSON path, so no Jackson 2 runs in either binary any more. What is left is test code: the test doubles and fixtures in all three modules still use Jackson 2 (`jackson-databind` and `jackson-dataformat-yaml`, test scope), which the Quarkus 4 BOM still manages. They can move to jjq or Jackson 3 whenever Jackson 2 leaves the BOM; nothing ships with it. The measurement below was made before #1263 and is kept for the Quarkus feedback (Q-6, Q-9). The scope was measured by running OpenRewrite `UpgradeJackson_2_3` on a scratch branch:
  - 82 files change.
  - Three hand fixes are needed: two `IOException` signatures, and one `catch` rewrite that breaks a try-with-resources (Q-6).
  - 7 tests then fail in common, all in YAML error handling: exceptions become unchecked, and the duplicate-key message changed.
  - Watch out: Jackson 3 sorts properties alphabetically by default, so the key order of the `config.yaml` we write would change silently, and no test would notice. `FAIL_ON_NULL_FOR_PRIMITIVES` and `FAIL_ON_TRAILING_TOKENS` also flip. Each mapper must be built with Jackson 2 defaults (`configureForJackson2()`), or the new defaults must be adopted deliberately with tests that pin the output order.
  - Estimated effort is 1-2 days. Afterwards the proxy no longer carries two Jackson cores, and the databind exclusion (change 3) becomes a no-op.
- **D-2: Connect upstream before accepting a client WebSocket?** The handshake split now allows it, and it would pass upstream's 401/403/429 on as a real HTTP status instead of a 101 followed by close 1011. It is not complete yet: `reject(int)` cannot carry headers or a body, and `accept()` cannot choose the sub-protocol (V-W4). We suggest a follow-up, not part of this migration.
- **D-3: Netty allocator.** Netty 4.2 defaults to `AdaptiveByteBufAllocator`, and Quarkus 4 sets `io.netty.noUnsafe=true` in native (Q-7). A quick JVM-mode test of four 32 MB TLS relays measured peak RSS of 625 MB on Vert.x 4, 533 MB on Vert.x 5 with the pooled allocator, and 448 MB on Vert.x 5 with the adaptive one. The adaptive allocator kept 68 MB of direct memory after the burst, against 8 MB with the pooled one. The native proxy runs with `-R:MaxRAM=256m`, so `bench/run.sh` on the native binary has to decide whether we pin an allocator.
- **D-4: Use `sendFile(FileChannel)`?** It is new in Vert.x 5. It would close the stat-then-open window in `serveTrusted` and move the blocking open off the event loop. It is marked unstable and works over HTTP/1 only. Revisit once it is stable.
- **D-6: Possibly flaky test under Vert.x 5.** `ArtifactCacheProxyTest.clientThatStopsReadingIsNotTakenForAStalledUpstream` failed once on this branch during a full class run and passed on reruns. It has not been seen failing on `main`. After the rebase (2026-10-25) it did not fail again: `ArtifactCacheProxyTest` (93 tests) and `WebSocketProxyTest` (14) passed 5 runs out of 5 each on their own, plus the full `mvn package` run. `main` has reworked the relay's stall handling since (#1058, #1325, #1533), so the single failure may have been the old code under load. We keep this open until CI has run the branch a few times.
- **D-5: Reconsider `InterceptedCertOptions.MAX_SERVED_NAMES`.** This one predates the migration. Its javadoc and DESIGN.md say Vert.x caches an `SslContext` per SNI name forever, but both 4.5 and 5 keep a 16-entry LRU (`SslContextProvider.DEFAULT_SNI_CACHE_SIZE`). The cap therefore guards nothing on Vert.x's side. The real cost runs the other way: with more than 16 hot names, every miss rebuilds an `SslContext`.

## Feedback for the Quarkus team

Each item has the evidence to act on it. Severity is our judgement.

- **Q-1 (regression, native size): async DNS resolver back in native images.** Commit `12145237998` ("Migrate Vert.x Core extension to Vert.x 5") dropped the Quarkus 3 substitution of `ResolverProvider.factory`. Vert.x 5 reads `vertx.disableDnsResolver` at run time, so Netty's resolver and DNS codec are linked although Quarkus itself forces the property to `true` in native. That costs about 1.2 MB in every native app using `quarkus-vertx`.
  - *Repro:* the native build report of any `quarkus-vertx` app, under `io.netty.resolver.dns`.
  - *Fix:* substitute `io.vertx.core.spi.dns.AddressResolverProvider.factory` to return `DefaultAddressResolverProvider`. Our `proxy/src/main/java/dev/incusspawn/graal/VertxDnsSubstitutions.java` does this.
- **Q-2 (packaging): Jackson 3 databind reaches every Vert.x app.** `vertx-core` 5.1.8's published POM has a JDK-activated profile (`Java21`, `[21,)`) that adds `tools.jackson.core:jackson-core` and `jackson-databind` as non-optional dependencies. `quarkus-vertx` declares databind `<optional>`, but that does not override the transitive one. For us this meant about +18 MB of native image (118 MB against 99.6 MB without it), because databind drags in the JDK XML stack.
  - *Repro:* `mvn dependency:tree -Dverbose -pl proxy` with only the version bump.
  - *Fix:* exclude databind in `quarkus-vertx`, or ask Vert.x to mark it optional (V-P1).
- **Q-3: Jackson 3 core is always reachable in Vert.x native images.** `VertxCoreProcessor` registers `io.vertx.core.json.jackson.v3.JacksonCodec` for reflection unconditionally. That adds 0.87 MiB to the image even when no JSON is used.
- **Q-4: other native size growth in the proxy (+5.7 MB after Q-1 and Q-2).** About +1 MiB is Vert.x 5 core itself. About +0.5 MiB is FFM support pulled in by Netty 4.2's FFM-based buffer cleaner (`8880c6a193a`). About +0.7 MiB comes from Netty buffers going through VarHandles because of `io.netty.noUnsafe=true` (Q-7). About +0.5 MiB is the Quarkus runtime and SmallRye Config 4. Heap metadata adds +2 MiB. This is not a bug; we include it as data for size tracking.
- **Q-5 (BOM): `jackson-annotations` downgraded to 2.21.** The Jackson 3 BOM is imported before the Jackson 2 BOM, so its `jackson-annotations` 2.21 wins over the 2.22 that `jackson-databind` 2.22.2 asks for. It is harmless today (we checked the bytecode), but it is a latent mismatch.
- **Q-6 (tooling): `quarkus update` does nothing for 3.x to 4.0.0.Beta1.** The newest released `quarkus-update-recipes` (1.15.0) has no 4.0 recipes. `4.0.alpha1.yaml` exists only on quarkus-updates `main`, and it covers Jackson only, while the migration guide says the config removals are handled automatically.
  - *Repro:* `mvn io.quarkus:quarkus-maven-plugin:4.0.0.Beta1:update -DplatformVersion=4.0.0.Beta1` on a 3.40 project.
  - The Jackson recipe does not keep Jackson 2 defaults (no `configureForJackson2()`). It also rewrites `catch (IOException)` to `catch (JacksonException)` where the `IOException` came from closing a resource, which no longer compiles.
- **Q-7 (undocumented default): `io.netty.noUnsafe=true` in native.** It was flipped by `623b9207680` ("Attempt to disable unsafe in Netty"), together with the FFM malloc/free path (`8880c6a193a`). Neither the migration guide nor the release notes mention it. It changes buffer performance characteristics for TLS-heavy native apps.
- **Q-8 (stale tuning): `quarkus.netty.allocator-max-order` no longer does anything by default.** Netty 4.2 / Vert.x 5 default to the adaptive allocator, while Quarkus still sets `io.netty.allocator.maxOrder` (`NettyProcessor.java:115,229`), which only affects the pooled allocator. Quarkus should either pin `io.netty.allocator.type` or document the property as inert and warn when it is set.
- **Q-9 (migration guide gaps).** We hit these and found nothing about them:
  - There is no Vert.x 4 to 5 or Netty 4.2 section, not even a pointer to the Vert.x guide. Every item under "Feedback for the Vert.x team" below affected us through Quarkus.
  - Nothing says that Jackson 2 is still BOM-managed and transitional, or that `quarkus-jackson` is now Jackson 3.
  - The Jackson defaults table omits `SORT_PROPERTIES_ALPHABETICALLY`, which silently changes serialized output. The table also describes only the Quarkus-managed mapper, not mappers that applications construct themselves.
  - The CDI API group change (`jakarta.enterprise` to `jakarta.cdi`) and the `smallrye-common-vertx-context` to `smallrye-common-vertx5-context` rename are missing; both matter to anyone with explicit exclusions or enforcer rules.
- **Q-11 (suggestion): reflection-free Jackson outside REST.** The build-time generated (de)serializers (`JacksonSerializerFactory`/`JacksonDeserializerFactory`) exist only in `quarkus-rest-jackson`, opt-in, and only for REST endpoint types. An application that maps its own model, such as a CLI reading YAML config into `@RegisterForReflection` classes with mappers it builds itself, has no way to use them, so it stays on reflection in native. A `quarkus-jackson` option covering the managed mapper outside REST, or explicitly listed model classes, would make the optimisation available to non-REST apps. `quarkus-jackson` itself only registers classes for reflection.
- **Q-10 (minor):**
  - The native build still passes the deprecated `--enable-url-protocols`.
  - The `NativeConfig` javadoc still names `jdk-21` builder images.
  - The uber-jar builder warns about duplicate Jackson 2 and 3 license files, a configuration the BOM itself produces.
  - Uber-jars grew about 6% (Unix extra fields plus 592 directory entries; CLI 11.3 MB to 12.0 MB).
  - `vertx-core` hard-depends on `netty-codec-http3`, `netty-codec-classes-quic` and `netty-tcnative-classes`, and `quarkus-netty` depends on Netty 4.2's `netty-codec` umbrella, which pulls protobuf and marshalling codecs. Quarkus correctly keeps all of them out of the native image (0 bytes), but they are dead weight on the JVM classpath of HTTP/1+2 apps.

## Feedback for the Vert.x team (5.1.8)

The reproducers are in [`repro/`](repro/). Each is a single Java file that depends only on `vertx-core`. Run one with `./run.sh <vertx-version> <file>`; it exits 1 when the bug reproduces.

### TLS

- **V-T1 (regression): a server's SNI key-manager lookup runs on the event loop by default.** 4.5 always ran `keyManagerFactoryMapper` on a worker (`SslChannelProvider`, `workerPool.execute`). 5.1.8 runs it on `delegatedTaskExec`, which is an immediate executor unless `SSLEngineOptions.setUseWorkerThread(true)` (`internal/net/SslChannelProvider.java:107-108`). The `serverNameAsyncMapping` javadoc still says "worker thread", and the migration guide does not mention the change. A mapper that does I/O or key generation now blocks the event loop.
  - *Repro:* `repro/tls/SniMappingOnEventLoop.java`: fails on 5.1.8 (`vert.x-eventloop-thread-0`), passes on 4.5.34.
- **V-T2 (bug): `updateSSLOptions` ignores changes when the key/cert options are equal.** The `SslContextManager` cache key covers only key/cert, trust and CRL values (`SslContextManager.java:263-291`). Restricting protocols to TLSv1.2 with the same certificate returns `false`, and the server keeps negotiating TLSv1.3. 4.5 compared the whole `SSLOptions` and applied it. The javadoc promises a comparison of the options object.
  - *Repro:* `repro/tls/UpdateSslOptionsIgnoresNonKeyCertChanges.java`: fails on 5.1.8 (with `force=true` it works), passes on 4.5.34.
- **V-T3 (bug, security-relevant): per-connect `ClientSSLOptions` that differ only in `hostnameVerificationAlgorithm` share a cached context, and the first connection wins.** On one `NetClient`, a connect with `""` followed by a connect with `"HTTPS"` to a certificate whose name does not match **succeeds**, so hostname verification is silently skipped. The reverse order wrongly fails the second connect. The cache key also ignores per-connect protocols and cipher suites. We are not affected, since we set no per-connect SSL options.
  - *Repro:* `repro/tls/ClientSslCacheIgnoresHostnameVerification.java` (5.x only, as the API is new).
- **V-T4 (docs): `updateSSLOptions(ServerSSLOptions)` accepts a full `ServerSSLOptions` but ignores its `sni`, `useAlpn`, `applicationLayerProtocols` and handshake timeout.** Those stay as captured at listen time (`NetServerImpl.java:236-242, 451`). The method should document this, or reject a mismatch.
- **V-T5 (minor):**
  - Superseded server SSL providers stay in a 64-entry cache after reloads, where 4.5 kept only the latest.
  - The per-SNI `SslContext` cache is a fixed, undocumented 16-entry LRU, so a server answering many names rebuilds contexts on every miss. It should be documented and configurable.
- **V-T6 (migration guide): Netty 4.2 clients verify hostnames by default.** Vert.x shields its users by passing its setting explicitly, but code that builds Netty `SslContext`s directly changes behaviour silently.

### WebSocket

- **V-W1 (regression and buffer leak): a frame pipelined with the upgrade request is dropped.** In 5.1.8 such a frame is decoded before the HTTP handler is swapped for the WebSocket one, and `Http1ServerConnection.handleMessage` (`:200-238`) ignores it without releasing its buffer. 4.5.34 delivered it whenever the `webSocketHandler` paused the socket, the pattern a relaying proxy uses. Without a pause, 4.5 lost it too. RFC 6455 §4.1 says clients must wait for the 101, so the practical impact is small, but the leak is real.
  - *Repro:* `repro/websocket/PipelinedFrameDropped.java` (default mode `ws-pause`): the frame is echoed on 4.5.34 and lost on 5.1.8 in every mode, including `handshake`.
  - *Leak repro:* `repro/websocket/PipelinedFrameLeaks.java` with paranoid leak detection: 31 `LEAK:` reports on 5.1.8, none on 4.5.34.
- **V-W2 (inconsistency): the server-side `accept()` hands over an unpaused socket.** The client side pauses a new WebSocket until the user's callback has run (`WebSocketClientImpl.java:165-169`). If the 101 write completes asynchronously, a frame can be delivered before the user's handlers are set. The server side should get the same treatment, or the docs should say so.
- **V-W3 (docs and migration guide):** the `webSocketHandshakeHandler` javadoc ("Setting no handler, implicitly accepts…") does not say the following, and the migration guide covers none of it:
  - It now works without a `webSocketHandler`; in 4.5 it was ignored unless one was set, and a server with only a handshake handler fails at `listen` with "Set request or WebSocket handler first" (`repro/websocket/PipelinedFrameDropped.java handshake` on 4.5.34).
  - Setting both handlers runs both.
  - A handshake that is neither accepted nor rejected hangs until the idle timeout.
  - `accept()` resolves only after the 101 is flushed.
  - `closeHandler` now fires when the TCP connection closes, not when the close frame is received.
- **V-W4 (API gap for proxies):** `ServerWebSocketHandshake.accept()` cannot set the selected sub-protocol or response headers, and `reject(int)` cannot send headers or a body. Together these block a faithful pass-through proxy, for example one that relays upstream's `WWW-Authenticate` or JSON error.
- **V-W5 (docs and API mismatch):** `WebSocketConnectOptions` inherits `setServer()`, whose javadoc promises a connection to a given address without name resolution. `WebSocketClient` uses it only as a fallback when no host is set, so you cannot pin an IP while keeping SNI as you can with `HttpClient`, and the call silently does nothing.
- **V-W6 (code quality):** `ServerWebSocketHandshaker.reject()` fails its own future with a bare `RejectedExecutionException` ("Not great but for now OK"), and the accept write-failure branch is marked `// TEST THIS ????` (`:221`).

### HTTP client

- **V-H1 (docs):** neither the javadoc nor the migration guide says whether `HttpClientRequest.authority()` is the `Host`/`:authority` value or the connect target when `setServer()` is used (it is the former), or that a trailing dot is stripped.
- **V-H2:** client ALPN is decided by whether `HTTP_2` is in the protocol list (`TcpHttpClientTransport.java:164`), not by `HttpClientOptions.setUseAlpn`, and Vert.x 5 now also offers `http/1.0` in ALPN.
- **V-H3 (minor):**
  - `SharedHttpClientConnectionGroup.LIFO_SELECTOR` never updates `last`, so it picks the last available connection rather than the most recently used one (same in 4.5).
  - `PoolOptions()` sets `http3MaxSize = DEFAULT_HTTP2_MAX_POOL_SIZE` (`PoolOptions.java:84`), which is harmless only because both values are 1.

### Packaging

- **V-P1:** `vertx-core` 5.1.8's `Java21` JDK-activated profile adds Jackson 3 core and databind as non-optional dependencies (see Q-2). JSON mapping support should be optional for consumers that do not use it.

## Pre-existing issues found during the review

None of these are regressions, and none is fixed here.
- The WebSocket relay has no upgrade timeout (`WebSocketConnectOptions.setTimeout` is unset).
- The 51st concurrent WebSocket to one upstream host waits with no timeout.
- The WebSocket relay writes frames with no backpressure.
- The MITM server never selects a sub-protocol, while upstream may choose one.
- `MAX_SERVED_NAMES` is justified by a Vert.x behaviour that does not exist (D-5).

## How this was verified

- Each changed API was traced in the Vert.x 4.5.34 and 5.1.8 sources and in Netty 4.2.18. Quarkus behaviour was checked at tags `3.40.0.CR1` and `4.0.0.Beta1`.
- New tests:
  - `MitmTlsTest` (now on `main`): SNI lookup off the event loop; reload keeps per-SNI certs, protocol and ALPN. Without `setUseWorkerThread(true)` it fails on the branch (`vert.x-eventloop-thread-2`).
  - `WebSocketProxyTest` (now on `main`): refused handshakes answered 400 and never dialled, frames sent immediately after the handshake, plus the V-W1 test, disabled on this branch. The branch adds a check that each refused handshake's failed `accept()` is logged.
  - `ArtifactCacheProxyTest`: relative redirect and resume through `authority()`.
- Dependency trees and native build reports (`-H:+BuildReport`) were compared module by module against `main`.
- A `-Xlint:deprecation` compile of the proxy shows no deprecated Vert.x or Quarkus API in use (rechecked after the rebase, which brought in a `sendHead()`).
