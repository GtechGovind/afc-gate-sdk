# Testing strategy

Normal verification is deterministic and hardware-independent. It exercises the public contract through an in-memory
serial transport, session concurrency and recovery behavior, Puloon golden frames and CRC, fragmented/coalesced input,
malformed payloads, defensive copies, bounded buffers, all documented command groups, and JVM boundary behavior that does
not require an open port.

The suite also covers concurrent callers, explicit reconnect versus pending automatic reconnect, initial-open recovery,
caller cancellation, sequence wrap, maximum frame size, sustained fragmented decoding, strict field parsing, settings
completeness/uniqueness/ranges, hardware-profile capability snapshots, all pass modes, and mechanism-specific boundaries.

Run the production gate locally:

```shell
./gradlew clean
./gradlew check dokkaGeneratePublicationHtml publishToMavenLocal
```

`check` includes compilation with warnings as errors, tests, ktlint, Detekt, Kotlin ABI comparison, Kover's minimum line
coverage rule, and coverage XML generation. Dokka separately fails on undocumented public or internal declarations.

## Hardware-in-the-loop tests

Real-device tests must remain opt-in and excluded from ordinary `check`. A controlled HIL environment should cover:

- Every approved controller firmware and physical mechanism
- Entry, exit, invalid ticket, emergency input/output, and all supported modes
- Sensor activation and fault injection
- USB disconnect during a read and during every state-changing command class
- Power loss, controller reboot, and host restart reconciliation
- Long-running polling plus concurrent command traffic
- Corrupt/noisy serial input and recovery
- Settings read/write/read-back with known restore values
- Maintenance diagnostics under physical safety supervision

Never run actuator or reset tests on an in-service passenger gate. HIL fixtures must include emergency-stop procedures and
restore the controller to a documented baseline after each test.

## ABI changes

The reference ABI is stored under `api/`. `checkKotlinAbi` fails on unreviewed consumer-visible changes. Run
`./gradlew updateKotlinAbi` only after reviewing compatibility and intentionally accepting the API change.

## IDE dependency resolution

Reproduce IntelliJ's Kotlin Multiplatform dependency-resolution path from a terminal with:

```shell
./gradlew resolveIdeDependencies
```

Gradle configuration caching is intentionally disabled because Kotlin Multiplatform's IDE dependency resolver currently
retains Gradle model objects that cannot be serialized. Ordinary build caching and parallel execution remain enabled.

Checksum-based Gradle dependency verification is intentionally not enabled because IntelliJ resolves additional source
variants during import. Dependency versions remain locked; review changes to both lockfiles during dependency upgrades.

Gradle deprecation warnings use `warning.mode=all` rather than `fail`. Some warnings originate inside the Kotlin Gradle
plugin during IntelliJ model creation and must not prevent project import. Kotlin source warnings are still compiler
errors, and `check` continues to fail on formatting, static-analysis, ABI, test, and coverage violations.
