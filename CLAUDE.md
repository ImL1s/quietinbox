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
Tests are Kotest (JUnit Platform); property tests use fixed seeds. Instrumented tests are JUnit 4:
a `@Test` method must return `Unit` (end a `runBlocking { … }` body with `Unit`, or the class fails
to load). Coordinator tests run on real dispatchers: hand-offs are latches or polls, never
`delay()`-based ordering, and every "X must not happen" test has a negative control that fails
when the guard is removed.

## Layout

- `core/model` contracts and limits · `core/parser` StandardParser + registry · `parsers/apps` five
  app adapters (all `SYNTHETIC_ONLY`, no real-app fixtures) · `core/identity` · `core/reconcile`
  window alignment / dedup (plan §7.2; the six examples are literal tests) · `core/analytics` ·
  `core/designsystem` theme, components, all strings (en, zh-Hant, zh-Hans, ja, ko; parity checked in CI) · `core/testing`.
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
- Keep the pipeline single-writer: everything that commits goes through `pipelineMutex`, which is
  owned by `VaultMaintenance` (ADR-0007). Anything else that writes the vault in the background
  (media copies, journal replay, retention, backup export) runs inside `maintenance.work {}`;
  whole-vault writes (reset, backup import) are `maintenance.exclusive {}` runs. Never take the
  pipeline lock from inside `work {}` for longer than one event.
- Source policy changes (add / enable / pause / remove a source) go through `CaptureCoordinator`,
  never straight to `SourceRepository` from a ViewModel: the write and the in-memory allow-list
  must change under the pipeline lock.
- Gaps are shown, never hidden: any path that drops a captured notification records a gap, and a
  loss the locked vault cannot record yet is remembered and written once the vault opens.
- Before the source policy is known, do not read a third-party notification: hold the framework
  object, decide when the policy loads (`Held` buffer in `CaptureCoordinator`).
- Best-effort bookkeeping uses `guarded {}`; never swallow `CancellationException`.
- Room writes that also create files: write the files first, insert rows and links in one
  transaction, and clear the cleanup list *inside* the transaction block (`MediaCopier.store`,
  `BackupService.apply`), so a cancellation landing after the commit never deletes a linked file.
- Deletion is a graph: journal payload cleared on leaving `PENDING`, media rows and files with
  their messages, `rebuildProjection` after every delete / expiry / restore, reads filter expiry.
- A cached crypto primitive must be tied to `KeyMaterial.epoch`; never hand out one built under a
  dead epoch.
- Never zero the key array handed to `SupportOpenHelperFactory`; never overwrite an unreadable key file.
- Debug builds skip `FLAG_SECURE` so `adb screencap` works; release honours it.
- Dates, times and numbers in the UI are formatted with the composition's locale (`currentLocale()` in
  `Formatting.kt`), never `Locale.getDefault()`: a per-app language does not update the process default
  while the process lives, and the listener keeps it alive.
- Strings: every user-facing string exists in all five catalogues — `values` (en), `values-b+zh+Hant`,
  `values-b+zh+Hans`, `values-ja`, `values-ko` — with the same names, placeholders and plurals;
  `python3 tools/check-strings.py` (also in CI) fails on any gap. A new language also goes into
  `localeFilters` (app/build.gradle.kts), `app/res/xml/locales_config.xml`, `fastlane/metadata/android/<locale>/`,
  `fastlane/whatsnew/`, and `tools/demo-screenshots.sh`.
- Lint is a hard gate in every module (`abortOnError = true`, no baseline): fix the finding, do not
  suppress it. `MissingPermission` needs the `checkSelfPermission` call in the *same method* as the
  guarded call, and the library manifest must declare the permission it uses.
- Do not add real-app notification captures, decompiled sources, or vendor assets to the repo.

## Demo mode and screenshots

Debug builds only: Settings → Developer → "Load demo data", or
`adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op seed -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver`
(`--es op clear` removes it; `--es lang ja-JP` names the demo's language, otherwise the app's configuration decides). Everything seeded is fictional (`demo.quietinbox.*` sources); `DemoLocalisation` swaps the Chinese names,
titles and bodies for zh-Hans / ja / ko equivalents when the app runs in one of those languages.
`tools/demo-screenshots.sh <serial> <en-US|zh-TW|zh-CN|ja-JP|ko-KR> <out-dir>` installs, seeds and captures every screen;
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

## Audit trail

The 2026-09-06 audit findings are GitHub issues #1–#17 (labels P0/P1/P2/audit); #17 (real-source
fixtures) stays open because it needs real devices and accounts. Every fix cites its issue and its
review round (`docs/reviews/2026-09-06-round{10,…,23}/`; rounds 13–17 re-reviewed each fix commit until
both reviewers approved with no finding; rounds 18–23 reviewed the localisation until both approved
with no finding). ADR-0007 records the design.

## Review gate before a push

Tests green → device walkthrough → independent review (`docs/reviews/README.md` records the roster
and verdicts; strictest verdict wins) → fix → mini re-review. Archive every report verbatim.
