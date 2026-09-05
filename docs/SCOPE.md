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
| Dedup with `AMBIGUOUS_REPEAT`, revisions, stale-window handling, resync-as-repost | Done | 16 JVM tests in `core:reconcile` including a 1,000-seed property test (the six §7.2 examples are literal test cases) |
| Encrypted vault (Room + SQLCipher, per-install random key, Keystore-wrapped) | Done | Instrumented tests `VaultRoundTripTest` + `MigrationTest` (1→2) on device; `KeystoreWrapper` sets `setUserAuthenticationRequired(false)` |
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
| Adaptive layout (rail + list-detail on wide windows) | Done (emulator) | Foldable_Test AVD (API 36, 2076×2152): `NavigationRail` + `ListDetailSceneStrategy` show inbox and conversation side by side; phone shows bottom bar |

## Not done (plan v1.0 items explicitly out of this milestone)

- **Real-source E2E (L3)** for LINE / WhatsApp / Telegram / Instagram / Messenger with two consenting test accounts. All five adapters are `SYNTHETIC_ONLY`; nothing has been observed from the real apps. See `docs/COMPATIBILITY.md`.
- **72-hour soak, OEM matrix, API 26 lane, 16 KB page-size verification** (plan §15). One physical device (Samsung SM-S9280, Android 16) and one foldable emulator (API 36) were exercised locally; the CI workflow defines API 29/35 emulator lanes but has not been executed yet.
- **Release signing, reproducible-build comparison, SBOM, F-Droid submission** (plan §17). `assembleRelease` is configured with R8 but no keystore exists in the repo.
- **Password-based backup (Argon2id)**, high-security lock-vault mode, remote-config rule updates, networked media variant — all P2 by plan.
- **Original-notification `PendingIntent` reuse** when opening the source app: v0.1 always falls back to the launcher intent (the snapshot deliberately never retains `PendingIntent`s).
- **Golden corpus diff reports** for parser changes (plan §14): fixtures are Kotest cases, no separate corpus tooling yet.
- **Diagnostic bundle export with redaction preview** (plan §14): only a body-free clipboard summary exists.
- **Name / trademark / package-id clearance**: `dev.quietinbox` is a placeholder.

## Review round 1 (2026-09-06): findings fixed before the first push

Four independent reviewers (Gemini 3.8 Flash high via agy, a Claude subagent, Claude Fable 5; Codex and Kimi were blocked by usage limits — see `.omc/research/`) returned REQUEST CHANGES. Every Critical and Important finding was fixed and covered where a unit test could express it:

- `DatabaseHolder.db()` no longer hangs when the vault ends up Locked while a caller waits.
- Deletion suppression is keyed by scope + identity (DB v2, explicit migration), so deleting a whole conversation survives active-notification replay.
- Pause/source-disable rotate the generation and are re-checked in `process()`; the consumer loop restarts after any throwable; bitmaps in flight are bounded.
- Journal replay and live processing share one mutex; failed commits stay PENDING for up to 3 attempts; FK-safe observation links.
- Reconciler aligns the whole window (ids as overrides), keeps the checkpoint on stale replays, and treats a same-post resync as a repost.
- Restore links media blobs to the restored message, dedupes only against pre-existing rows, reads bounded lines, and never deletes the user's target document.
- Key files are fsync'd; a missing Keystore key is reported as invalidated rather than minting a new one.
- App lock is closed until the setting is known; search queries use 3-grams / single CJK characters so "hell" finds "hello" and "開" finds "開會".

## Known defects and rough edges found during device verification

- `ShortNavigationBar` / `WideNavigationRail` from material3 1.5.0-alpha27 rendered a single item; replaced with `NavigationBar` / `NavigationRail` (see ADR-0002).
- Pipeline counters on the Capture page are per-process (reset on restart); the persisted journal count is shown separately.
- Package visibility on Android 11+ required `<queries>`; sources not listed there and without a launcher activity can be added by exact package name.
