
# Contributing

Contributions should be focused, buildable, and easy to review.

Before opening a pull request:

```bash
./scripts/run-all-checks.sh
./scripts/build-linux.sh fdroid
```

Keep commits limited to one logical change. Add or update tests for behavior changes. Do not commit generated build output, local SDK configuration, signing files, tokens, passwords, private keys, device identifiers, or service credentials.

Changes to the contribution scheduler, update verification, TLS handling, credential storage, enrollment, or remote commands require tests and a clear security explanation in the pull request.
