# Release process

## Versioning

Use semantic versioning for the published SDK. Increment the major version for incompatible public API or behavior changes,
minor for backward-compatible features/vendors, and patch for backward-compatible fixes. Pre-release builds keep a suffix
such as `-SNAPSHOT`.

## Release checklist

1. Update `CHANGELOG.md` and set `sdkVersion` to the intended non-snapshot version for local verification.
2. Review the public ABI diff. Update the reference dump only for intentional compatible or versioned changes.
3. Run `./gradlew clean` followed by `./gradlew check dokkaGeneratePublicationHtml publishToMavenLocal`.
4. Inspect generated POM/module metadata, compiled JAR, sources JAR, and Javadoc/Dokka JAR.
5. Test the locally published coordinate from the Gate application.
6. Commit, create an annotated `MAJOR.MINOR.PATCH` tag, and push the tag.
7. The release workflow revalidates the tag/version, reruns all gates, publishes the SDK to GitHub Packages, generates
   SHA-256 checksums for every artifact, and
   creates a GitHub release containing the SDK JARs, the compiled control-panel JAR, and native DMG, MSI, and DEB installers.
   The control-panel JAR is a release download only and is not published as a Maven package.
8. Promote/deploy only after a consumer smoke test and any required HIL certification.

Do not rebuild an already released version. If an artifact is wrong, publish a new patch version so provenance and caches
remain trustworthy.

## Automation credentials

GitHub Packages uses the workflow-provided `GITHUB_TOKEN`; no long-lived repository secret is required. If Maven Central or
an internal repository is added later, store signing and repository credentials in the CI secret store, scope them to the
release environment, and never place keys in Gradle properties committed to source control.
