# Contributing to FlowDroid

Thanks for your interest. This is a small open-source project; contributions
of all sizes are welcome — bug fixes, new actions, new triggers, templates,
docs.

If you're planning something bigger than a bug fix (a new trigger type, a new
permission, a UI rework), please **open an issue first** so we can agree on
the shape before you invest the time.

## Getting set up

Prerequisites: Android Studio Ladybug (2024.2)+, JDK 17, Android SDK 35.
The Gradle wrapper is checked in, so no extra setup is needed.

```powershell
git clone https://github.com/<you>/FlowDroid.git
cd FlowDroid
.\gradlew :app:assembleFullDebug          # build the sideload APK
.\gradlew :app:testFullDebugUnitTest      # run the unit tests
```

(On macOS/Linux use `./gradlew`.)

### Build flavors

The app has two product flavors on the `distribution` dimension:

| Flavor      | Purpose                         | Extra manifest                          |
|-------------|---------------------------------|-----------------------------------------|
| `full`      | Sideload / GitHub release APK   | `app/src/full/AndroidManifest.xml`      |
| `playStore` | Google Play upload              | none — restricted permissions stripped  |

Combined with build types this gives tasks like `assembleFullDebug`,
`assemblePlayStoreRelease`, `testFullDebugUnitTest`, etc. Anything that
needs a [Play-restricted permission](#restricted-permissions-sms-call-log-)
belongs in the `full` flavor only.

## Workflow

1. Fork the repo and create a topic branch off `main`:
   `git checkout -b feat/sms-received-trigger`
2. Make your change. Keep the diff focused — one concern per PR.
3. Run the unit tests for **both** flavors before opening a PR:
   ```powershell
   .\gradlew :app:testFullDebugUnitTest :app:testPlayStoreDebugUnitTest
   ```
4. Open a pull request with a short description of *why* the change is
   needed and what it changes. Link any related issue. Screenshots are
   appreciated for UI changes.

## Project layout

```
app/src/main/java/com/flowdroid/
├── common/flow/        Domain model: Flow, Trigger, Action, FlowEngine, ActionExecutor
├── data/db/            Room DB + FlowSerialization (JSON surrogates for Trigger/Action)
├── engine/             FlowEngineImpl, magic text, condition evaluation
│   ├── actions/        One executor per action (e.g. MessagingExecutors.kt)
│   └── di/             EngineModule — Hilt map binding Action class → executor
├── platform/           Trigger sources: schedule/, webhook/, mqtt/, lifecycle/
├── service/            Foreground service, notification listener, accessibility, receivers
└── ui/flows/           Compose editor: ActionCatalog, TriggerCatalog, *Fields, FlowSummary
```

## Adding a new action

An action is a data class in the domain model plus an executor that runs it.
Use an existing one as your reference — `Action.SendSms` /
`SendSmsExecutor` is a good, compact example. Touch points, in order:

1. **Domain model** — add a `data class YourAction(...) : Action` in
   `common/flow/Flow.kt`. Include `continueOnError` and `label` like the
   others, and a KDoc comment saying what it does and any permission it needs.
2. **Serialization** — in `data/db/FlowSerialization.kt`:
   - add a `TYPE_YOUR_ACTION` constant (this string is the on-disk
     discriminator — **never rename it** once released),
   - add an `@Serializable @SerialName(TYPE_YOUR_ACTION)` surrogate,
   - add both mappings (domain → surrogate and surrogate → domain).
3. **Executor** — create `engine/actions/YourActionExecutor.kt` implementing
   `ActionExecutor<Action.YourAction>`:
   - expand user-facing strings with `magicText.expandOrEmpty(...)`,
   - validate inputs and return `Outcome.err(ExecutionError.InvalidParam(...))`
     instead of throwing,
   - check runtime permissions *before* calling the API, and return a
     `SystemFailure` that tells the user where to grant it,
   - rethrow `CancellationException` / `OutOfMemoryError`, and log and wrap
     everything else.
4. **DI binding** — register it in `engine/di/EngineModule.kt`:
   ```kotlin
   @Binds @IntoMap @ActionKey(Action.YourAction::class)
   abstract fun bindYourAction(impl: YourActionExecutor): ActionExecutor<*>
   ```
5. **UI** — in `ui/flows/`:
   - `ActionCatalog.kt` — an `ActionEntry` (id, name, description, family,
     search keywords, factory with sensible defaults),
   - `ActionFamilyVisuals.kt` — map it to a family/icon,
   - a `YourActionFields` composable for the editor form (see
     `Phase19ActionFields.kt`), wired into the `when` blocks in
     `FlowEditorScreen.kt` (fields, display name, `copy(label = …)`),
   - `FlowSummary.kt` — a one-line summary for the flow list.
6. **Tests** — an executor test in `app/src/test/.../engine/actions/`
   (see `HashExecutorTest.kt` for the pattern) and a round-trip case in
   `FlowSerializationTest.kt`.
7. **ProGuard** — if you add a library that relies on reflection, add keep
   rules in `app/proguard-rules.pro` and check that `assembleFullRelease`
   still works.

The Kotlin compiler helps you here: most of the `when` expressions are
exhaustive, so a missed spot usually shows up as a build error.

## Adding a new trigger

Triggers are a bit more involved because something has to *listen* for the
event and hand it to the engine.

1. **Domain model** — add `data class YourTrigger(...) : Trigger` in
   `common/flow/Flow.kt`.
2. **Event payload** — add a `TriggerEvent.YourEvent` in
   `common/flow/FlowEngine.kt`. Document the magic-text bindings it exposes
   (e.g. `{sms.sender}`, `{sms.body}`) in the KDoc, and populate them in
   `FlowEngineImpl`.
3. **Serialization** — `TYPE_…` constant, surrogate and both mappings in
   `FlowSerialization.kt`, same as for actions.
4. **Event source** — the component that receives the OS event and calls
   `flowEngine.onTriggered(flowId, TriggerEvent.YourEvent(...))`. Look at
   `platform/webhook/WebhookServer.kt` (in-process server) and
   `platform/mqtt/MqttSubscribeManager.kt` (long-lived connection). A
   `BroadcastReceiver` in `service/` (e.g. `TimeTriggerReceiver.kt`) is
   the model for OS broadcasts.
5. **Arming** — if your source needs per-flow registration, add a branch in
   `platform/lifecycle/TriggerLifecycle.kt` (`arm` / `disarm`).
6. **UI** — `TriggerCatalog.kt`, `ActionFamilyVisuals.kt`, a fields
   composable plus the `when` blocks in `FlowEditorScreen.kt`, and
   `FlowSummary.kt`.
7. **Tests** — matching/dispatch tests for the source (see
   `WebhookServerTest.kt`), catalog tests (`TriggerCatalogTest.kt`),
   summary tests (`FlowSummaryTest.kt`), and serialization round-trip.

## Restricted permissions (SMS, call log, …)

Google Play only allows SMS and call-log permissions
(`SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`, `READ_CALL_LOG`, …) for apps whose
*core* function is messaging or calling. FlowDroid doesn't qualify, so:

- **Declare restricted permissions only in `app/src/full/AndroidManifest.xml`**,
  never in `app/src/main/AndroidManifest.xml`.
- Receivers or services that need them (e.g. an `SMS_RECEIVED` receiver)
  should also be declared in the `full` manifest, so they don't exist in
  the Play build.
- Code shared between flavors must handle the permission being absent and
  return a clear `ExecutionError` rather than crashing.
- After your change, build `:app:assemblePlayStoreRelease` and check that
  the merged manifest
  (`app/build/intermediates/merged_manifests/playStoreRelease/`) does
  **not** contain the permission.

Before writing code for a new permission, add a line to the PR or issue
explaining why it's needed.

### Example: an "SMS received" trigger

This is a frequently requested feature. Here's a sketch of how it fits
together:

- `Trigger.SmsReceived(senderRegex: String?, bodyRegex: String?)` in
  `Flow.kt`, plus a `TriggerEvent.SmsReceived(sender, body, receivedAtMillis)`
  exposing `{sms.sender}` and `{sms.body}`.
- An `SmsReceivedReceiver` in `app/src/full/java/com/flowdroid/...` handling
  `android.provider.Telephony.SMS_RECEIVED`. It parses the PDUs with
  `Telephony.Sms.Intents.getMessagesFromIntent`, joins multipart messages
  and, for each enabled flow whose `SmsReceived` filters match, calls
  `flowEngine.onTriggered(...)`. Keep `onReceive` short: use `goAsync()` or
  hand the work off to a coroutine scope.
- `RECEIVE_SMS` and the `<receiver>` entry go in
  `app/src/full/AndroidManifest.xml`.
- In the `playStore` build, either hide the trigger from `TriggerCatalog`
  or show it as unavailable. Don't let users build a flow that silently
  never fires.
- Never log message bodies or full phone numbers. Log lengths and digit
  counts instead, like `SendSmsExecutor` does.

## Code style

- **Kotlin official** style (`kotlin.code.style=official` is set in
  `gradle.properties`).
- Use the `Outcome<T, E>` type for expected failures; reserve exceptions
  for invariant violations.
- Services and workers must never crash — wrap background work in a
  try/catch that downgrades errors to structured logs. See the existing
  patterns in `app/src/main/java/com/flowdroid/service/`.
- Never call `System.currentTimeMillis()` directly — inject `Clock`.
- Don't log secrets or personal data (message bodies, phone numbers,
  tokens). The `StructuredLogger.REDACTED_KEYS` set masks known sensitive
  keys; add to it if you introduce new ones.
- Comment the *why*, not the *what* — especially for Android platform
  quirks and OEM workarounds.

## Tests

- Unit tests live in `app/src/test/` (JUnit 5 + MockK + Truth + Robolectric).
- Instrumented tests live in `app/src/androidTest/` and require an emulator
  or device.
- Prefer table-driven tests where the same logic is exercised against many
  inputs.
- New actions and triggers need at least an executor/source test and a
  serialization round-trip test.

## Adding a new flow template

Templates are bundled JSON files that ship with the APK and seed the
gallery on first run. To add one:

1. Drop the JSON into `app/src/main/assets/templates/NN-your-name.json`
   (follow the existing numbering).
2. Add a `Template(...)` entry to `TemplateGallery.kt` with a one-line
   description of which feature surface(s) it exercises.
3. Add a test in `FlowImportExportTest` that loads it and asserts on the
   parsed `FlowDraft`.

Templates must not contain real phone numbers, account IDs, tokens or
personal endpoints. Use placeholders such as `+10000000000` or
`https://example.com`.

## Reporting bugs

Open a GitHub issue with:
- Device + Android version (and OEM skin, e.g. One UI / MIUI — background
  killing behaves very differently between them)
- Which build you're on (`full` or `playStore`)
- A short repro
- Relevant lines from the in-app **Logs → App log** tab. Please redact
  anything personal first.

## License

By submitting a contribution you agree it will be licensed under the
project's Apache-2.0 license.
