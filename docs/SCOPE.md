# Scope, definition of done, and honest gaps

The plan (`QuietInbox_開源專案完整計劃`, 2026-09-05) describes a 10–14 week v1.0. This document
records what the repository **actually delivers today** and what it does **not**. "Compiles" is not
"done" (plan §16); every line below states its evidence.

## Definition of done for this milestone (plan §3 "v0.1" + §18)

| Item | Status | Evidence |
| --- | --- | --- |
| Authorisation onboarding | Done | Device walkthrough on SM-S9280 / Android 16 (screens: scope → sources → access → test → preview) |
| Synthetic test notification publisher (L2) | Done | `SyntheticNotifications`; captured 3/3 messages on device |
| NotificationListenerService capture, bounded snapshot | Done | `platform:capture`; no DB/network/decoding on callback thread |
| Multi-message parser (MessagingStyle / Inbox / BigText / summary) | Done | 10 JVM tests in `core:parser` |
| Identity without cross-stream merging | Done | 5 JVM tests in `core:identity` |
| Dedup with `AMBIGUOUS_REPEAT`, revisions, stale-window handling | Done | 12 JVM tests + 1,000-seed property test in `core:reconcile` (the six §7.2 examples are literal test cases) |
| Encrypted vault (Room + SQLCipher, per-install random key, Keystore-wrapped) | Done | Instrumented test `VaultRoundTripTest` (device); `KeystoreWrapper` sets `setUserAuthenticationRequired(false)` |
| Journal-first commit, commit fence on revoke | Done | `IngestRepository.commit`, `CaptureCoordinator.process` generation check |
| Inbox / conversation UI with quality labels | Done | Device screenshots |
| Search (CJK bigram + Latin trigram, parameterised, paged) | Done | Instrumented test covers 開會 / hel; UI on device |
| Activity statistics (observed-only) | Done | 4 JVM tests in `core:analytics`; UI on device |
| Capture health page with gaps and diagnostics | Done | UI on device |
| Retention TTL worker | Done (not soak-tested) | `RetentionWorker`, 12h periodic |
| Media copy (content:// + notification bitmap, encrypted) | Implemented, **not device-verified** | `MediaCopier`; no test yet exercises a real content URI |
| Encrypted backup export/import with recovery key | Implemented, **not device-verified** | `BackupService` + HKDF RFC vectors; no round-trip instrumented test yet |
| Own reminders (off by default, DST-safe local time) | Implemented, **not device-verified** | `ReminderScheduler.delayUntilNext` pure function; no unit test yet |
| UI lock (BiometricPrompt), screenshot protection | Implemented, **partially verified** | FLAG_SECURE verified to block `screencap` in debug before the debug-only exemption was added; biometric flow not exercised |
| No INTERNET permission | Done | `aapt2 dump permissions` on debug APK; `tools/check-permissions.sh` in CI |
| zh-Hant + en localisation | Done | `core/designsystem/res/values*` |
| Adaptive layout (rail + list-detail on wide windows) | Implemented, **not verified on tablet/foldable** | `MainNavigation` uses `ListDetailSceneStrategy`; only phone tested |

## Not done (plan v1.0 items explicitly out of this milestone)

- **Real-source E2E (L3)** for LINE / WhatsApp / Telegram / Instagram / Messenger with two consenting test accounts. All five adapters are `SYNTHETIC_ONLY`; nothing has been observed from the real apps. See `docs/COMPATIBILITY.md`.
- **72-hour soak, OEM matrix, foldable/tablet runs, API 26 lane, 16 KB page-size verification** (plan §15). Only one device (Samsung SM-S9280, Android 16) and no emulator lanes were run locally; the CI workflow defines API 29/35 emulator lanes but has not been executed yet.
- **Release signing, reproducible-build comparison, SBOM, F-Droid submission** (plan §17). `assembleRelease` is configured with R8 but no keystore exists in the repo.
- **Password-based backup (Argon2id)**, high-security lock-vault mode, remote-config rule updates, networked media variant — all P2 by plan.
- **Original-notification `PendingIntent` reuse** when opening the source app: v0.1 always falls back to the launcher intent (the snapshot deliberately never retains `PendingIntent`s).
- **Golden corpus diff reports** for parser changes (plan §14): fixtures are Kotest cases, no separate corpus tooling yet.
- **Diagnostic bundle export with redaction preview** (plan §14): only a body-free clipboard summary exists.
- **Name / trademark / package-id clearance**: `dev.quietinbox` is a placeholder.

## Known defects and rough edges found during device verification

- `ShortNavigationBar` / `WideNavigationRail` from material3 1.5.0-alpha27 rendered a single item; replaced with `NavigationBar` / `NavigationRail` (see ADR-0002).
- Pipeline counters on the Capture page are per-process (reset on restart); the persisted journal count is shown separately.
- Package visibility on Android 11+ required `<queries>`; sources not listed there and without a launcher activity can be added by exact package name.
