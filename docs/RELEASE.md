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
6. Open a pull request and require the JDK verification plus native DMG, MSI, and DEB package-smoke matrix to pass. Confirm
   each installer has the expected application icon, version, menu integration, and platform shortcut behavior.
7. Merge the verified pull request, create an annotated `MAJOR.MINOR.PATCH` tag on the merge commit, and push the tag.
8. The release workflow revalidates the tag/version, reruns all gates, publishes the SDK to GitHub Packages, generates
   SHA-256 checksums and build-provenance attestations for every artifact, and
   creates a GitHub release containing the SDK JARs, the compiled control-panel JAR, and native DMG, MSI, and DEB installers.
   The control-panel JAR is a release download only and is not published as a Maven package.
9. Promote/deploy only after a consumer smoke test and any required HIL certification.

Do not rebuild an already released version. If an artifact is wrong, publish a new patch version so provenance and caches
remain trustworthy.

The workflow rejects any release tag whose commit is not reachable from `origin/main`. Do not tag a pull-request head.
Installer signing, macOS notarization, and physical HIL certification are deployment-owner responsibilities unless their
credentials and controlled hardware are explicitly configured in CI.

## Automation credentials

GitHub Packages uses the workflow-provided `GITHUB_TOKEN`; no long-lived repository secret is required. If Maven Central or
an internal repository is added later, store signing and repository credentials in the CI secret store, scope them to the
release environment, and never place keys in Gradle properties committed to source control.
