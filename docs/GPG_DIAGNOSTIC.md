# Non-publishing signing diagnostic (draft)

This workflow is intentionally manual-only and refuses refs other than this
repository's `main`. Review and merge it into trusted main before any authorized
manual run. Keep the existing `release` environment protections; do not weaken
branch policies or required approvals to make this diagnostic run.

It reads only `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` from that environment/repository.
It requires a nonempty passphrase, imports into a fresh temporary keyring, signs a
fixed local text, verifies it, and removes the temporary keyring on exit. It does
not check out repository code, accept inputs, invoke third-party actions, request
network access, use Maven, publish artifacts, upload logs/artifacts, or authenticate
to Central. GnuPG diagnostics and key metadata are suppressed. Passphrase is passed
by file descriptor, not command-line argument. Network key retrieval is disabled.
The GitHub-hosted runner/control plane remains a trust boundary; this is not an OS
network sandbox. Do not enable shell tracing or add key metadata to logging.

A pass establishes that the supplied key/passphrase can sign on that runner. It
does NOT establish Maven plugin compatibility, Central namespace/token permissions,
public-key server availability, all-artifact coverage, or release readiness. Errors
are intentionally stage-level only. No key fingerprints or private key details
should be pasted into PRs or logs.

## Local regression test

With Python, PyYAML and GnuPG available, run:

```sh
python tests/test_gpg_diagnostic.py
```

The test extracts the exact workflow script and uses only generated disposable
keys. It checks success, incorrect passphrase, malformed/missing key, missing
passphrase, public-only key, cleanup, workflow guards and Bash syntax. It never
reads real release secrets and never dispatches the workflow.

Validation: YAML guards, Bash syntax, all six synthetic-key cases, and temporary
keyring cleanup passed on the GitHub-hosted Ubuntu runner in
https://github.com/OpenFluxGate/fluxgate/actions/runs/44102798736
(head afe44fca306a8618ad256535ccd9ce3b3c9f34b9). Local execution was blocked by
sandbox Unix-socket restrictions; the PR-only regression workflow resolved this
test-environment limitation without release secrets or the release environment.
No real-secret diagnostic or publication has been performed.
