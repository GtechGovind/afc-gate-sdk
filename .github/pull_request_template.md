## Summary

Describe the behavior and API impact.

## Safety checklist

- [ ] State-changing serial commands are never retried or replayed.
- [ ] New protocol fields are validated before model creation.
- [ ] Mutable byte arrays are defensively copied at codec boundaries.
- [ ] Public API changes include an intentional ABI dump update.
- [ ] `sdkVersion` is incremented and the matching `CHANGELOG.md` section is present.
- [ ] Tests cover success, malformed input, timeout/cancellation, and unsupported capability behavior.
- [ ] KDoc and operational documentation are updated.
- [ ] `./gradlew clean` and `./gradlew check dokkaGeneratePublicationHtml publishToMavenLocal` pass.
