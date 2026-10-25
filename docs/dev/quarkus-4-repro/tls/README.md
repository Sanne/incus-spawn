# Vert.x 5 TLS reproducers

Found while porting incus-spawn's MITM proxy from Quarkus 3.40.1.1 (Vert.x 4.5.35, Netty 4.1.139) to Quarkus 4.0.0.Beta2 (Vert.x 5.2.2, Netty 4.2.19).
Each file is a self-contained single-file Java program that depends only on `io.vertx:vertx-core`, plus the JDK's `keytool`, which it runs to make a throwaway self-signed certificate.
Each one prints what it saw and exits 0 when the behaviour is the expected one, or 1 when the bug reproduces.

**Baseline.** The results below were last run on 2026-10-29 against vertx-core 4.5.35 and 5.2.2, on JDK 25, with `run.sh`.
The source references cite the 4.5.34 and 5.1.8 sources jars, which are what the paths and line numbers refer to.

## How to run

Requires JDK 21+ (tested on 25) and Maven, which is used only to resolve the classpath:

```sh
./run.sh 5.2.2 tls/SniMappingOnEventLoop.java
./run.sh 4.5.35 tls/SniMappingOnEventLoop.java
```

`run.sh <version> <file>` runs `mvn dependency:build-classpath -Dvertx.version=<version>` on `pom.xml`, cached in `target/`.
It then runs `java -cp <cp> <file>` with the JDK's source-file launcher.

Source references below are paths under `io/vertx/core/` in the `vertx-core` sources jar of the stated version.

---

## 1. SNI key-manager mapper runs on the event loop by default (regression from 4.5)

File: `SniMappingOnEventLoop.java`

**Setup:** a `NetServer` with `setSsl(true).setSni(true)` and a custom `KeyCertOptions` whose `keyManagerFactoryMapper` records the thread it runs on.
A JDK client then handshakes with SNI.

**Expected:** the mapper runs on a worker thread, as in 4.5.
A mapper may block, for example to load or mint a certificate.
The javadoc of `ServerSslContextProvider#serverNameAsyncMapping` still says "mapping happens on a Vert.x worker thread".

**Actual (5.2.2):** it runs on `vert.x-eventloop-thread-N`, unless the server sets `setSslEngineOptions(new JdkSSLEngineOptions().setUseWorkerThread(true))`.
That option also moves the SSLEngine's own handshake tasks to the worker pool, so the two cannot be chosen separately.

Observed:

| Version | Default engine options | `useWorkerThread(true)` | Exit |
|---|---|---|---|
| 5.2.2  | EVENT LOOP (`vert.x-eventloop-thread-0`) | worker (`vert.x-internal-blocking-2`) | 1 |
| 4.5.35 | worker (`vert.x-internal-blocking-2`) | worker | 0 |

**Source refs:**
- 5.1.8 `internal/net/SslChannelProvider.java:107-108`: `delegatedTaskExec = useWorkerPool() ? workerPool : ImmediateExecutor.INSTANCE` is passed to `serverNameAsyncMapping(delegatedTaskExec, ...)`.
- 5.1.8 `internal/tls/ServerSslContextProvider.java:68` (javadoc) and the `serverNameAsyncMapping` body, which runs on whatever executor it is handed.
- 4.5.34 `net/impl/SslChannelProvider.java:140-142`: `serverNameMapping()` always calls `workerPool.execute(...)`.
- `SSLEngineOptions.DEFAULT_USE_WORKER_POOL = false` in both versions.

No migration-guide entry mentions this.
