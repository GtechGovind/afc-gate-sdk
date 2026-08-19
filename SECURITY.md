# Security policy

Report suspected vulnerabilities privately to the repository owner or the organization's established security channel. Do
not open a public issue containing exploit details, credentials, production topology, or sensitive gate behavior.

Supported releases are the latest released minor line and any explicitly maintained long-term-support line. Security fixes
should include a regression test, impact analysis, safe upgrade guidance, and a new immutable release version.

The SDK treats serial input as untrusted: frames, checksums, lengths, values, correlation fields, and complete settings are
validated; receive queues and partial-frame buffers are bounded; mutable bytes are copied; and platform exceptions do not
cross the public API. Applications remain responsible for OS port permissions, process isolation, maintenance authorization,
dependency updates, secure logging, and physical gate safety.
