# Contributing

Remote Shutter follows a small, review-driven process.

## Before submitting changes

1. Run the full test suite:

   ```bash
   scripts/run-tests.sh
   ```

2. Format Android code with the project’s Kotlin settings.
3. Keep the wire protocol backward-compatible unless the change is deliberate and documented.
4. Update `README.md` or `docs/` if behavior changes.

## Commit style

Use short, imperative commit subjects, for example:

```text
Harden trigger receiver on release builds
```

For protocol changes, add or update unit tests on both the Android and Connect IQ sides.
