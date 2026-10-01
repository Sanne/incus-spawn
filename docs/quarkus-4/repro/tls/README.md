# Vert.x 5 TLS reproducers

Found while porting incus-spawn's MITM proxy from Quarkus 3.40.0.CR1 (Vert.x 4.5.34, Netty 4.1.138)
to Quarkus 4.0.0.Beta1 (Vert.x 5.1.8, Netty 4.2.18). Each file is a self-contained single-file Java
program that depends only on `io.vertx:vertx-core` (plus the JDK's `keytool`, which it runs to make a
throwaway self-signed certificate). Each one prints what it saw and exits 0 when the behaviour is the expected one, or 1 when the bug reproduces.

## How to run

Requires JDK 21+ (tested on 25) and Maven, which is used only to resolve the classpath:

```sh
./run.sh 5.1.8 tls/SniMappingOnEventLoop.java
./run.sh 4.5.34 tls/SniMappingOnEventLoop.java
./run.sh 5.1.8 tls/UpdateSslOptionsIgnoresNonKeyCertChanges.java
./run.sh 4.5.34 tls/UpdateSslOptionsIgnoresNonKeyCertChanges.java
./run.sh 5.1.8 tls/ClientSslCacheIgnoresHostnameVerification.java   # 5.x only
```

`run.sh <version> <file>` runs `mvn dependency:build-classpath -Dvertx.version=<version>` on
`pom.xml` (cached in `target/`), then `java -cp <cp> <file>` (the JDK's source-file launcher).

Source references below are paths under `io/vertx/core/` in the `vertx-core` sources jar of the stated version.

---

## 1. SNI key-manager mapper runs on the event loop by default (regression from 4.5)

File: `SniMappingOnEventLoop.java`

**Setup:** a `NetServer` with `setSsl(true).setSni(true)` and a custom `KeyCertOptions` whose
`keyManagerFactoryMapper` records the thread it runs on. A JDK client then handshakes with SNI.

**Expected:** the mapper runs on a worker thread, as in 4.5. A mapper may block, for example to load or mint a certificate. The javadoc of `ServerSslContextProvider#serverNameAsyncMapping` still says "mapping happens on a Vert.x worker thread".

**Actual (5.1.8):** it runs on `vert.x-eventloop-thread-N`, unless the server sets
`setSslEngineOptions(new JdkSSLEngineOptions().setUseWorkerThread(true))`. That option also moves the SSLEngine's own handshake tasks to the worker pool, so the two cannot be chosen separately.

Observed:

| Version | Default engine options | `useWorkerThread(true)` | Exit |
|---|---|---|---|
| 5.1.8  | EVENT LOOP (`vert.x-eventloop-thread-0`) | worker (`vert.x-internal-blocking-2`) | 1 |
| 4.5.34 | worker (`vert.x-internal-blocking-2`) | worker | 0 |

**Source refs:**
- 5.1.8 `internal/net/SslChannelProvider.java:107-108`: `delegatedTaskExec = useWorkerPool() ? workerPool : ImmediateExecutor.INSTANCE` is passed to `serverNameAsyncMapping(delegatedTaskExec, ...)`.
- 5.1.8 `internal/tls/ServerSslContextProvider.java:68` (javadoc) and the `serverNameAsyncMapping` body, which runs on whatever executor it is handed.
- 4.5.34 `net/impl/SslChannelProvider.java:140-142`: `serverNameMapping()` always calls `workerPool.execute(...)`.
- `SSLEngineOptions.DEFAULT_USE_WORKER_POOL = false` in both versions.

No migration-guide entry mentions this.

---

## 2. `updateSSLOptions` silently ignores protocol, cipher and client-auth changes when key/cert are equal

File: `UpdateSslOptionsIgnoresNonKeyCertChanges.java`

**Setup:** the server listens with default protocols (TLSv1.2 and TLSv1.3). Then
`updateSSLOptions(sameKeyCert + enabledSecureTransportProtocols={TLSv1.2}, force=false)` is called,
and a JDK client offering both protocols reports which one was negotiated.

**Expected:** the update is applied. The options differ, and the javadoc says the update is skipped only when the options object is `equals` to the existing one. The method returns `true`, and the client then negotiates TLSv1.2.

**Actual (5.1.8):** returns `false`; client still negotiates TLSv1.3. With `force=true` it applies.

Observed:

| Version | `force=false` | `force=true` | Exit |
|---|---|---|---|
| 5.1.8  | returned false, negotiated TLSv1.3 | returned true, negotiated TLSv1.2 | 1 |
| 4.5.34 | returned true, negotiated TLSv1.2 | returned true, negotiated TLSv1.2 | 0 |

**Source refs:**
- 5.1.8 `internal/tls/SslContextManager.java:163-188`: `resolveSslContextProvider` looks up `sslContextProviderMap` by `new ConfigKey(options)` and returns the cached provider.
- `SslContextManager.java` `ConfigKey` (lines 268-296): `equals`/`hashCode` cover only `keyCertOptions`, `trustOptions` (via `trustOptionsOf`) and `crlValues`. They leave out enabled protocols, cipher suites, client auth, ALPN and the handshake timeout.
- 5.1.8 `net/impl/SslContextProviderReference.java:71-85`: `updated` is reported as `false` because the same provider instance came back.
- 4.5.34 `net/impl/SSLHelper.java:252-275` `updateSslContext` compares `prev.options.equals(options)`, and 4.5.34 `net/SSLOptions.java:402-414` `equals` covers all fields.

Related, and not covered by a test here: in 5.1.8, `updateSSLOptions` also ignores `ServerSSLOptions.sni`, `useAlpn`, `applicationLayerProtocols` and the handshake timeout. Those come from the options captured at listen (`net/impl/tcp/NetServerImpl.java:236-242`), and the javadoc does not say so.

---

## 3. Per-connect `ClientSSLOptions` share a cached context regardless of `hostnameVerificationAlgorithm` (security)

File: `ClientSslCacheIgnoresHostnameVerification.java`

**Setup:** the server's certificate is for `other.example`, and the client trusts it. A single `NetClient` connects to `127.0.0.1` (no SNI) twice. Each connect passes its own `ConnectOptions.setSslOptions(new ClientSSLOptions().setTrustOptions(sameTrust).setHostnameVerificationAlgorithm(algo))`. This is repeated with a fresh client and the order reversed.

**Expected:** each connect honours its own algorithm, whatever the order: `""` connects and `"HTTPS"` is rejected (name mismatch).

**Actual (5.1.8):** the first connect decides for every later one on that client. If `""` comes first, the later `"HTTPS"` connect succeeds, so hostname verification is silently skipped. If `"HTTPS"` comes first, the later `""` connect is rejected.

Observed (5.1.8, exit 1):

```
fresh client:  algo=''      -> connected  algo='HTTPS' -> connected (expected rejected)
fresh client:  algo='HTTPS' -> rejected   algo=''      -> rejected  (expected connected)
```

4.5.34: not applicable, because `ClientSSLOptions` and per-connect SSL options do not exist there.

**Source refs:**
- 5.1.8 `internal/tls/ClientSslContextManager.java:33-54`: the provider bakes in `hostnameVerificationAlgorithm`, but the lookup goes through `resolveSslContextProvider(options, factory, false, ctx)`, which is keyed by `ConfigKey`.
- 5.1.8 `internal/tls/SslContextManager.java` `ConfigKey` and `trustOptionsOf` (lines 192-200): the verification algorithm only reaches the key in the `trustAll` case (`TrustAllOptions.instance(verify)`), never with real trust options.
- 5.1.8 `net/impl/tcp/NetClientImpl.java:229-241`: a per-connect `ClientSSLOptions` copy goes straight to `sslContextManager.resolveSslContextProvider`.
- The same cache sits behind `HttpClient` and `WebSocketClient` requests that carry their own `ClientSSLOptions` (`http/impl/HttpClientBase.java:77-86`).

The same key also ignores per-connect enabled protocols and cipher suites, as in issue 2.
