# Performance and deployment tuning

## Runtime design

Serial line speed and controller response time dominate command latency. The SDK therefore optimizes predictability and
allocation bounds rather than attempting parallel writes that a serial controller cannot safely process.

- One fair coroutine mutex serializes logical operations and sequence allocation.
- A second session mutex guarantees one request/response exchange on the wire.
- Blocking jSerialComm calls stay on `Dispatchers.IO`; protocol orchestration uses `Dispatchers.Default`.
- The Puloon decoder reuses a geometrically grown byte array instead of concatenating on every fragment.
- Decoder, decoded-frame, and transport chunk buffers are bounded.
- Byte arrays are copied only at ownership boundaries; decoded immutable models leave protocol bytes behind.
- Status polling shares the wire queue, so it cannot corrupt an application command.
- The JVM transport handles partial native writes and uses finite read/write timeouts.

## Application tuning

Reuse a connected `Gate`; do not create/connect per passenger. Avoid a polling interval faster than operational freshness
requires. A 250–500 ms interval is normally less intrusive than the protocol minimum, but measure on approved hardware.
Keep `responseTimeout` slightly above the observed high-percentile controller latency and use a small read retry count.
Oversized timeouts amplify queueing delay; excessive retries can hide failing hardware.

Do not launch an unbounded coroutine per passenger. Apply upstream admission control when the application can produce
requests faster than the physical gate can complete them. The SDK serializes calls safely, but caller coroutines still
consume application memory while waiting.

Collect flows once per concern instead of repeatedly starting short-lived collectors. Keep event/status callbacks fast; send
expensive persistence or analytics work to an application-owned bounded queue.

## JVM deployment

Start with the JVM defaults on JDK 17 or 21 and tune only from production measurements. For a containerized service, set an
explicit memory limit and consider `-XX:MaxRAMPercentage` plus `-XX:+ExitOnOutOfMemoryError` according to the organization's
runtime standard. Do not use aggressive low-latency GC flags without measuring command-latency distributions under serial
traffic.

jSerialComm uses native access. On JVMs that require it, configure the appropriate `--enable-native-access` flag described in
[Operations](OPERATIONS.md). Keep the SDK and native dependency on the module path or classpath consistently across test and
production.

## Build performance and reproducibility

The project enables the Gradle configuration cache, local/remote build cache compatibility, parallel execution, Kotlin
daemon memory bounds, dependency locking, pinned wrapper and automation versions, and reproducible archive ordering/timestamps. CI uses the
wrapper, validates its checksum, and builds on JDK 17 and 21.

For a shared CI fleet, configure an authenticated Gradle remote build cache at the organization level. Do not commit cache
credentials. Preserve `--no-daemon` in ephemeral CI; local developers benefit from the daemon.

## Measurement

The deterministic stress suite decodes sustained fragmented traffic and verifies 65,536-sequence wrap behavior. Before a
release that changes codec/session hot paths, compare:

- Frame decode throughput and allocation rate for fragmented and coalesced streams
- Command latency p50/p95/p99 with status polling enabled
- Heap growth during prolonged noise/malformed input
- Reconnect duration and command-queue recovery
- CPU and allocation profile during the highest supported serial baud rate

Use JMH or `kotlinx-benchmark` in a separate benchmark source set when microbenchmarking. Never mix benchmark assertions into
functional tests or establish a CI timing threshold on shared runners; use dedicated stable hardware for regression gates.
