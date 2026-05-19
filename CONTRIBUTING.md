# Contributing to FlowDroid

Thanks for your interest. This is a small open-source project; contributions
of all sizes are welcome.

## Workflow

1. Fork the repo and create a topic branch off `main`:
   `git checkout -b fix/some-bug`
2. Make your change. Keep the diff focused — one concern per PR.
3. Run the unit tests locally before opening a PR:
   ```powershell
   .\gradlew :app:testDebugUnitTest
   ```
4. Open a pull request with a short description of *why* the change is
   needed and what it changes. Link any related issue.

## Code style

- **Kotlin official** style (`kotlin.code.style=official` is set in
  `gradle.properties`).
- Use the `Outcome<T, E>` type for expected failures; reserve exceptions
  for invariant violations.
- Services and workers must never crash — wrap background work in a
  try/catch that downgrades errors to structured logs. See the existing
  patterns in `app/src/main/java/com/flowdroid/service/`.
- Never call `System.currentTimeMillis()` directly — inject `Clock`.
- Don't log secrets. The `StructuredLogger.REDACTED_KEYS` set masks known
  sensitive keys; add to it if you introduce new ones.

## Tests

- Unit tests live in `app/src/test/` (JUnit 5 + MockK + Truth + Robolectric).
- Instrumented tests live in `app/src/androidTest/` and require an emulator
  or device.
- Prefer table-driven tests where the same logic is exercised against many
  inputs.

## Adding a new flow template

Templates are bundled JSON files that ship with the APK and seed the
gallery on first run. To add one:

1. Drop the JSON into `app/src/main/assets/templates/NN-your-name.json`
   (follow the existing numbering).
2. Add a `Template(...)` entry to `TemplateGallery.kt` with a one-line
   description of which feature surface(s) it exercises.
3. Add a test in `FlowImportExportTest` that loads it and asserts on the
   parsed `FlowDraft`.

## Reporting bugs

Open a GitHub issue with:
- Device + Android version
- A short repro
- Relevant lines from the in-app **Logs → App log** tab (which is what
  ships to the Room logger). Please redact anything personal first.

## License

By submitting a contribution you agree it will be licensed under the
project's Apache-2.0 license.
