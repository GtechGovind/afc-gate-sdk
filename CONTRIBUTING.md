# Contributing

Changes must preserve the single public `Gate` abstraction and the safety rule that state-changing commands are never
retried or replayed. Keep vendor wire types internal, use Kotlin APIs in common code, and isolate unavoidable Java APIs in
the JVM transport boundary.

Before opening a pull request:

1. Add or update deterministic tests for success and failure paths.
2. Add KDoc to every new public/internal declaration and implementation-level documentation around non-obvious private
   protocol logic.
3. Update architecture, integration, vendor, or operations documentation affected by the change.
4. Run `./gradlew clean` and then `./gradlew check dokkaGeneratePublicationHtml publishToMavenLocal`.
5. Review generated ABI changes and never run `updateKotlinAbi` merely to silence an unexplained failure.

Protocol changes require a specification reference or captured, sanitized hardware evidence. Never commit passenger data,
credentials, raw production logs, or proprietary protocol documents without permission.
