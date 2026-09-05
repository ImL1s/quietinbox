# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

QuietInbox（靜讀）is an Android app that keeps **on-device, encrypted copies of what messaging apps
post to the notification shade**. Kotlin + Jetpack Compose (Material 3 Expressive) + Navigation 3 +
Hilt + Room/SQLCipher + Tink + WorkManager. Licence: GPL-3.0-or-later.

Hard product rules (from the plan; never trade these away):

- **No `INTERNET` permission**, ever. `tools/check-permissions.sh` fails CI if it appears.
- **Never act on source notifications**: no reply, dismiss, mark-read, `PendingIntent` firing.
- Only content the source app puts in a notification is captured; gaps are shown, never hidden.
- Honest data-quality labels (`AMBIGUOUS_REPEAT`, inferred identity, preview restricted).
- No destructive Room migrations; schema JSON under `platform/storage/schemas/` is versioned.
- Docs must not run ahead of the code. Re-read `docs/SCOPE.md`, `CHANGELOG.md`, `docs/TEST_MATRIX.md`
  counts after adding tests; reviewers flagged "docs ahead of code" in every round.

## Build and test

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew test :app:assembleDebug --console=plain            # all JVM tests + debug APK
./gradlew :core:reconcile:test                                # the dedup core (fast)
./gradlew :platform:storage:connectedDebugAndroidTest \
          :platform:crypto:connectedDebugAndroidTest          # SQLCipher, migration, key fsync (device)
./gradlew :app:assembleRelease                                # R8; no keystore in repo
tools/check-permissions.sh                                    # merged-manifest permission gate
```

Toolchain: Gradle 9.7.1 wrapper, AGP 9.4.0 (built-in Kotlin, compileSdk 37, targetSdk 36, minSdk 26),
Kotlin 2.4.10, KSP, Compose BOM 2026.08.00 with material3 pinned to 1.5.0-alpha27 (Expressive APIs;
stable 1.4 lacks them, see ADR-0002). Versions live in `gradle/libs.versions.toml`; conventions in
`build-logic/` (`quietinbox.android.library`, `.library.compose`, `.hilt`, `.feature`, `.kotlin.jvm`).
Tests are Kotest (JUnit Platform); property tests use fixed seeds.

## Layout

- `core/model` contracts and limits · `core/parser` StandardParser + registry · `parsers/apps` five
  app adapters (all `SYNTHETIC_ONLY`, no real-app fixtures) · `core/identity` · `core/reconcile`
  window alignment / dedup (plan §7.2; the six examples are literal tests) · `core/analytics` ·
  `core/designsystem` theme, components, all strings (en + zh-Hant, parity checked) · `core/testing`.
- `platform/capture` NotificationListenerService + `CaptureCoordinator` (bounded queue, generation
  commit fence, journal-first, replay) · `platform/storage` Room + SQLCipher, repositories, retention
  worker, settings · `platform/crypto` Keystore-wrapped key files (`Os.fsync`), recovery key codec ·
  `platform/media` · `platform/backup` Tink streaming AEAD container.
- `feature/*` one module per screen (ViewModel + Compose) · `app` navigation, lock, DI, reminders.
- `docs/` SCOPE (what is done vs. not), ARCHITECTURE, COMPATIBILITY, TEST_MATRIX, ADRs, `reviews/`
  (every independent review round, verbatim).

## Working rules

- Callback thread (`onNotificationPosted`) must stay allocation-light: snapshot, enqueue, return.
  No DB, no bitmap decoding, no parsing there.
- Keep the pipeline single-writer: everything that commits goes through `pipelineMutex`.
- Best-effort bookkeeping uses `guarded {}`; never swallow `CancellationException`.
- Never zero the key array handed to `SupportOpenHelperFactory`; never overwrite an unreadable key file.
- Debug builds skip `FLAG_SECURE` so `adb screencap` works; release honours it.
- Strings: add to both `values/strings.xml` and `values-b+zh+Hant/strings.xml`, same names.
- Do not add real-app notification captures, decompiled sources, or vendor assets to the repo.

## Demo mode and screenshots

Debug builds only: Settings → Developer → "Load demo data", or
`adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op seed -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver`
(`--es op clear` removes it). Everything seeded is fictional (`demo.quietinbox.*` sources).
`tools/demo-screenshots.sh <serial> <en-US|zh-TW> <out-dir>` installs, seeds and captures every screen;
store copies live in `fastlane/metadata/android/<locale>/images/`, reference copies in `docs/screenshots/`.
Use the project's own AVDs (`QuietInbox_Phone`, `Foldable_Test`); never a phone with real notifications.

## Release

`docs/RELEASE.md`: tag `vX.Y.Z` → `release.yml` builds the signed APK/AAB, runs the permission gate,
publishes the GitHub release; Google Play uploads (internal or production) are a deliberate
`workflow_dispatch`, never a side effect of a tag. After touching dependencies, regenerate
`gradle/verification-metadata.xml` from a cold `GRADLE_USER_HOME` (recipe in `docs/RELEASE.md`);
CI fails on any unlisted artifact. Play edition is paid, GitHub edition free, same binary (ADR-0006). Never add
Play Billing / Play Services / any SDK that merges `INTERNET`.

## Device verification recipe

```bash
adb shell cmd notification allow_listener dev.quietinbox.app.debug/dev.quietinbox.platform.capture.QuietInboxListenerService
adb shell pm grant dev.quietinbox.app.debug android.permission.POST_NOTIFICATIONS
# external source for pipeline tests: add com.android.shell by package name in Capture, then
adb shell cmd notification post -S messaging -t "Title" tag "body"
```

Onboarding enables installed sources; on a real phone the reconnect resync will copy the user's own
notifications into the debug vault. Use an emulator for screenshots. On foldable AVDs strip the
"Multiple displays" warning that `screencap -p` prepends to the PNG.

## Review gate before a push

Tests green → device walkthrough → independent review (`docs/reviews/README.md` records the roster
and verdicts; strictest verdict wins) → fix → mini re-review. Archive every report verbatim.
