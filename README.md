# FlowDroid

**Notification-driven automation for Android.** Match a pattern on any
incoming notification, then run a flow — HTTP requests, UI taps, branch on
the result, loop, post a notification back. Designed to survive aggressive
OEM background-killing (Samsung One UI, MIUI, etc.).

> Think Tasker × Make.com, mobile-native, open-source, and built to *stay
> running*.

---

## Features

- **4 trigger types**
  - `NotificationPosted` — match by package, title, text, action-button label (regex)
  - `TimeOfDay` — fire at HH:MM on selected days of the week
  - `Interval` — every N minutes, optional active-hours window
  - `Webhook` — local HTTP endpoint (`/hook/<path>`) with optional shared secret
- **22+ actions** across 8 families
  - **Notifications** — click action button, post, dismiss
  - **Apps** — launch, kill
  - **UI** — tap, swipe, type text, press key (via Accessibility)
  - **Timing** — delay
  - **Network** — HTTP request (GET/POST/PUT/DELETE)
  - **Variables** — set, read, magic-text resolution
  - **Files & data** — read, write, Base64, hash (SHA-256 etc.)
  - **Logic & flow** — `If`/`else`, `Loop` (COUNT / FOREACH_LINES), `TryCatch`
- **Magic text** — `$notif.title`, `$notif.text`, `$notif.package`, plus
  modifier chains (`|regex:…`, `|jsonpath:…`, `|trim`, …) and
  user-defined `$variables`.
- **Bundled template gallery** — 12 ready-to-import flows that exercise
  every feature surface (see [Bundled templates](#bundled-templates)).
- **Reliability-first plumbing** — foreground service + notification
  listener + watchdog that auto-rebinds when Android tears the listener
  down. Every disconnect is journaled and surfaced on the Health screen.
- **First-run wizard** — guides the user through Notification access,
  battery whitelisting, OEM auto-start, and Accessibility.

## Screens

The app has four top-level tabs: **Flows**, **Health**, **Logs**, **Setup**.
Open `mockups.html` in a browser for full visual mockups (dark Material 3,
412×892 viewport) of every surface plus six in-progress redesign ideas for
the Flow Editor.

---

## Building

### Prerequisites

1. **Android Studio Ladybug (2024.2)** or newer.
2. **JDK 17** (Android Studio bundles a compatible JDK).
3. **Android SDK 35** with build tools 35.0.0+.

The first build downloads the Compose BOM, Hilt, and friends — allow
~5 minutes on a fresh machine.

### Generate the Gradle wrapper (one-time)

The wrapper binaries are not checked in. From the project root:

```powershell
gradle wrapper --gradle-version 8.10 --distribution-type bin
```

After that, all subsequent commands use `./gradlew` (Unix) or
`.\gradlew.bat` (Windows PowerShell).

### Build the debug APK

```powershell
.\gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

### Install on a connected device

```powershell
.\gradlew :app:installDebug
adb shell am start -n com.flowdroid.debug/com.flowdroid.MainActivity
```

### Run unit tests

```powershell
.\gradlew :app:testDebugUnitTest
```

### Run instrumented tests (requires emulator or device)

```powershell
.\gradlew :app:connectedDebugAndroidTest
```

---

## Project layout

```
AndroidWorkflow/
├── README.md
├── LICENSE                  # Apache-2.0
├── CONTRIBUTING.md
├── mockups.html             # full-fidelity HTML mockups of every screen
├── settings.gradle.kts
├── build.gradle.kts         # root build script
├── gradle.properties
├── gradle/libs.versions.toml
├── scripts/                 # PowerShell dev helpers (logcat, listener toggle, health)
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/templates/   # bundled flow templates (JSON)
        │   ├── res/...
        │   └── java/com/flowdroid/
        │       ├── FlowDroidApplication.kt
        │       ├── MainActivity.kt
        │       ├── common/         # Outcome, Clock, Logger, domain types
        │       ├── data/           # Room + repositories + import/export
        │       ├── engine/         # trigger matcher + action executors + magic text
        │       ├── logging/        # Timber → Room sink + crash handler
        │       ├── platform/       # webhook server, package picker
        │       ├── service/        # foreground service + notification listener + watchdog
        │       ├── permission/     # permission checker + OEM battery deep links
        │       └── ui/             # Compose: Flows, Health, Logs, Setup tabs
        ├── test/                   # JVM unit tests (JUnit 5 + MockK + Truth + Robolectric)
        └── androidTest/            # instrumented tests
```

---

## Bundled templates

The app ships with twelve starter flows that cover every feature surface.
They are bundled as JSON assets under `app/src/main/assets/templates/` and
shown in the in-app gallery on first run.

| # | Template | What it exercises |
|---|---|---|
| 01 | Hello world | smallest possible flow — notification → echo |
| 02 | Daily morning reminder | `TimeOfDay` trigger + `trigger.*` magic text |
| 03 | Interval heartbeat (HTTP GET) | `Interval` + activeWindow + HTTP action |
| 04 | Webhook echo | `Webhook` trigger + body → notification |
| 05 | State file persistence | `ReadFile` + `WriteFile` across runs |
| 06 | Loop foreach line | `Loop FOREACH_LINES` + `item`/`index` vars |
| 07 | Loop count + Delay | `Loop COUNT` + `Delay` + nested actions |
| 08 | TryCatch flaky HTTP | full `Try`/`Catch`/`Finally` path |
| 09 | Base64 + SHA-256 pipeline | `Base64` + `Hash` + variable chaining |
| 10 | If REGEX severity router | `If REGEX` + else path |
| 11 | jsonpath + regex modifiers | magic-text modifier chains |
| 12 | Notification probe | dumps every notification field — handy for building robust regex/jsonpath patterns |

---

## Dev workflow

### Watch logcat for FlowDroid tags

```powershell
adb logcat -c
adb logcat *:S FlowDroid:V FgService:V Listener:V Watchdog:V
```

### Check listener bind status

```powershell
adb shell dumpsys notification | findstr /i flowdroid
adb shell dumpsys activity services com.flowdroid.debug
```

### Toggle notification listener (to exercise the watchdog)

```powershell
adb shell cmd notification disallow_listener com.flowdroid.debug/com.flowdroid.service.FlowDroidNotificationListenerService
adb shell cmd notification allow_listener   com.flowdroid.debug/com.flowdroid.service.FlowDroidNotificationListenerService
```

### Force-stop & cold start

```powershell
adb shell am force-stop com.flowdroid.debug
adb shell am start -n com.flowdroid.debug/com.flowdroid.MainActivity
```

### Simulate a reboot

```powershell
adb shell svc power reboot
```

The `BootReceiver` should fire and the foreground service should appear in
`dumpsys activity services` within ~20 s.

---

## Design conventions enforced in code

- **`Outcome<T, E>`** for expected failures. **Exceptions** for invariant violations only.
- **Never** call `System.currentTimeMillis()` directly — inject `Clock`.
- **Never** call `Timber.x()` from a `Service.onXxx` callback before checking
  the dispatcher — many service callbacks run on Binder threads.
- **Every** caught throwable is logged with the operation name and at least
  one structured field. "failed" is a bug; "PostNotification failed:
  channelId=null" is a useful log line.
- **No** logging of secrets — `StructuredLogger.REDACTED_KEYS` keeps known
  keys masked.
- **All** background work in services and workers is wrapped in a try/catch
  that downgrades errors to logs — services NEVER crash. That's what kills
  reliability on aggressive OEMs.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache-2.0](LICENSE).
