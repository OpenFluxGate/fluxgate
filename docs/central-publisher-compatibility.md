# Central publisher compatibility

Future releases use Sonatype's official `central-publishing-maven-plugin:0.12.0`.
The older 0.6.0 publisher can upload successfully and then fail while parsing the
status response's `warnings` field. An Actions failure therefore does not prove
that a coordinate remains unpublished. Never retry an immutable published
coordinate or move its release tag to work around this error.

Sonatype added usage-limit warnings and safer authentication logging in 0.11.0.
Version 0.12.0 adds `skipUpload` for bundle-only testing. The actual status parser
accepts additive JSON fields while retaining validation errors and failed states.
`waitUntil=published` and normal signing/security gates remain enabled.

Run `python3 .github/scripts/test-central-publisher.py` with JDK 21 and Maven
3.9.2 or newer (Maven 4 is not supported by the upstream plugin). It downloads
ordinary Maven dependencies, but uses only a synthetic `invalid.local.fluxgate`
POM, no signing or Central credentials, `skipUpload=true`,
`ignorePublishedComponents=false`, and a loopback publishing URL. It verifies the
bundle contents and exercises the actual plugin parser with PUBLISHED, FAILED,
VALIDATING, warnings, extra fields, and an invalid state. This is not a full
FluxGate release-bundle test or evidence of a new Central publication.

For a real future-release bundle rehearsal, use the reviewed release source and
`-DskipUpload=true -DignorePublishedComponents=false`; inspect all staged
coordinates and signatures before permitting a real upload. Do not replace this
with `skipPublishing`, which omits artifacts rather than producing the bundle.
A failed/uncertain upload must be reconciled against public artifacts and the
existing deployment status before any retry. Never turn off security checks or
publication-state verification to make Actions green.

Official release notes and option semantics:
https://central.sonatype.org/publish/publish-portal-maven/
