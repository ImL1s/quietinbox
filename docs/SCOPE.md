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
| Dedup with `AMBIGUOUS_REPEAT`, revisions, stale-window handling, resync-as-repost | Done | 20 JVM tests in `core:reconcile` including two 1,000-iteration property tests (the six §7.2 examples plus the closed-window ambiguous repeat are literal test cases) |
| Encrypted vault (Room + SQLCipher, per-install random key, Keystore-wrapped) | Done | Instrumented tests `VaultRoundTripTest` + `MigrationTest` (1→2, 2→3) + `KeystoreWrapperTest` (serialised KEK creation) on device; `KeystoreWrapper` sets `setUserAuthenticationRequired(false)` |
| Journal-first commit, commit fence on revoke / pause / source change / maintenance | Done | `CaptureCoordinator`: admission fence before and inside the pipeline lock, commit fence before the write; source policy changes under the lock; `CaptureCoordinatorTest` (16) |
| "Delete everything" as a verified, exclusive maintenance run; cipher cache tied to the key epoch | Done | `VaultMaintenance`, `VaultRepository.deleteEverything` → `ResetResult`; `VaultMaintenanceTest` (4), `DeletionGraphTest` on device; reset walked through on the AVD |
| Deletion graph and read-time expiry (journal payload cleared, media rows/files with their messages, projection rebuilt, expired copies hidden) | Done | `DeletionGraphTest` (5, on device) |
| Inbox / conversation UI with quality labels | Done | Device screenshots |
| Search (CJK bigram + Latin trigram, parameterised, keyset-paged, verified until the page is full) | Done | Instrumented `VaultRoundTripTest` (開會 / hel) and `SearchPagingTest` (250 false-positive candidates, resumable cursor); UI on device |
| Cold start fails closed: nothing read from a notification before the source policy is known | Done | `CaptureCoordinatorTest` (held unread, dropped with a `COLD_START` gap when the vault does not open); synthetic capture on the AVD after a cold start |
| Locked / opening vault on the search and conversation pages | Done | `SearchViewModelTest` (2), `ConversationViewModelTest` (1); not device-exercised (a vault cannot be locked on demand on the AVD) |
| Activity insights (observed-only: overview, heat map, rankings, best time, chattiness, quiet rate, emoji, catchphrases) | Done | 32 JVM tests in `core:analytics` plus 8 in `AnalyticsViewModelTest` (state rules, off-main-thread computation, locked/opening vault); UI on device; at most 50,000 messages per period are loaded (every tab shows a notice when it capped) |
| Capture health page with gaps and diagnostics | Done | UI on device |
| Retention TTL worker | Done (not soak-tested) | `RetentionWorker`, 12h periodic |
| Media copy (content:// + notification bitmap, encrypted) | Implemented, **not device-verified** | `MediaCopier`; no test yet exercises a real content URI |
| Encrypted backup export/import with recovery key (under the maintenance gate, paged export, partial-media report) | Done (emulator) | `BackupService` + HKDF RFC vectors; `BackupStagerTest` (21 JVM tests); instrumented `BackupRoundTripTest` on the API 36 AVD (export → wipe → import, expired copy excluded, skipped media reported, media decrypts under the current key); no SAF picker walkthrough on a device yet |
| Own reminders (off by default, DST-safe local time, only when something is unviewed) | Implemented, **not device-verified** | `ReminderSchedulerTest` (5 JVM tests: `delayUntilNext`, `ReminderPolicy`); the worker itself has no device test yet |
| UI lock (BiometricPrompt), screenshot protection | Implemented, **partially verified** | FLAG_SECURE verified to block `screencap` in debug before the debug-only exemption was added; biometric flow not exercised |
| Demo mode (debug builds only) | Done | `DemoDataRepository` lives in the `debug` source set of `platform:storage` behind the `DemoData` interface; release binds a no-op and its dex contains no demo class or text (checked with `strings` on `classes.dex`); `DemoDataTest` on device |
| No INTERNET permission | Done | `aapt2 dump permissions` on debug APK; `tools/check-permissions.sh` in CI |
| zh-Hant + en localisation | Done | `core/designsystem/res/values*` |
| Distribution: paid Google Play + free GitHub Releases, same binary (ADR-0006) | Done (Play: 0.1.0 under Google review; GitHub: 0.1.1) | `versionCode` 5 / 0.1.1 (the audit fixes, issues #1–#16) is the GitHub release `v0.1.1` (tag at `c6b6645`, signed APK + `SHA256SUMS.txt`); it goes to Play by `workflow_dispatch` once the 0.1.0 review clears. `versionCode` 4 / 0.1.0 submitted to Play production on 2026-09-06 (paid, 172 countries, no INTERNET permission, no Play Billing); tag `v0.1.0` → `release.yml` builds the signed APK + `SHA256SUMS.txt` and runs the permission gate; store listing (zh-TW + en-US) and images from `fastlane/metadata/android/`; screenshots reference in `docs/screenshots/` |
| Adaptive layout (rail + list-detail on wide windows) | Done (emulator) | Foldable_Test AVD (API 36, 2076×2152): `NavigationRail` + `ListDetailSceneStrategy` show inbox and conversation side by side; phone shows bottom bar |

## Not done (plan v1.0 items explicitly out of this milestone)

- **Real-source E2E (L3)** for LINE / WhatsApp / Telegram / Instagram / Messenger with two consenting test accounts. All five adapters are `SYNTHETIC_ONLY`; nothing has been observed from the real apps. See `docs/COMPATIBILITY.md`.
- **72-hour soak, OEM matrix, API 26 lane, 16 KB page-size verification** (plan §15). One physical device (Samsung SM-S9280, Android 16) and one foldable emulator (API 36) were exercised locally; the CI workflow defines API 29/35 emulator lanes but has not been executed yet.
- **Release signing, reproducible-build comparison, SBOM, F-Droid submission** (plan §17). `assembleRelease` is configured with R8 but no keystore exists in the repo.
- **Password-based backup (Argon2id)**, high-security lock-vault mode, remote-config rule updates, networked media variant — all P2 by plan.
- **Original-notification `PendingIntent` reuse** when opening the source app: v0.1 always falls back to the launcher intent (the snapshot deliberately never retains `PendingIntent`s).
- **Per-profile source control and a non-null account key** (audit #8): sources are keyed by package only, `accountKey` is nullable in the conversation identity. Making it `NOT NULL` needs the `conversation` table recreated (the `message` FK points at it), which is deferred to a later schema version; the inbox tags work-profile conversations and `docs/COMPATIBILITY.md` documents the limits.
- **Conversation list counts between expiry and the retention sweep** (audit #7): expired copies are hidden at read time everywhere, but a conversation row's `messageCount` is only rebuilt when something is deleted, swept or restored, so a row can show one more than the page total until the next sweep (at most 12 hours).
- **Restore re-bases expiry** (audit #7 / #16): a backup never contains a copy that was already expired when it was made; restoring an older backup gives its copies a fresh retention window on purpose (the whole-repository review asked that an old backup not be swept on the next run). A restore is therefore a deliberate "bring it back", never an accident.
- **Deletion suppression is keyed by fingerprint** (audit #9, round 11): several deleted messages with one fingerprint share one token, so a replay of the same post is suppressed as a whole (correct) while a genuinely new message with the same fingerprint inside that same post is suppressed too; a per-id token needs schema v4.
- **Governance not yet in CI** (audit #12): detekt / ktlint, CodeQL, SBOM, coverage thresholds, reproducible builds and commit signing. Each adds dependencies or maintainer-side keys; tracked, not started.
- **Golden corpus diff reports** for parser changes (plan §14): fixtures are Kotest cases, no separate corpus tooling yet.
- **Diagnostic bundle export with redaction preview** (plan §14): only a body-free clipboard summary exists.
- **Name / trademark / package-id clearance**: `dev.quietinbox` is a placeholder.

## Review round 1 (2026-09-06): findings fixed before the first push

Four independent reviewers (Gemini 3.8 Flash high via agy, a Claude subagent, Claude Fable 5; Codex and Kimi were blocked by usage limits — see `docs/reviews/`) returned REQUEST CHANGES. Every Critical and Important finding was fixed and covered where a unit test could express it:

- `DatabaseHolder.db()` no longer hangs when the vault ends up Locked while a caller waits.
- Deletion suppression is keyed by scope + identity (DB v2, explicit migration), so deleting a whole conversation survives active-notification replay.
- Pause/source-disable rotate the generation and are re-checked in `process()`; the consumer loop restarts after any throwable; bitmaps in flight are bounded.
- Journal replay and live processing share one mutex; failed commits stay PENDING for up to 3 attempts; FK-safe observation links.
- Reconciler aligns the whole window (ids as overrides), keeps the checkpoint on stale replays, and treats a same-post resync as a repost.
- Restore links media blobs to the restored message, dedupes only against pre-existing rows, and bounds each line, the record count, the staged media bytes and the total staged text. It only reads the chosen document.
- Export writes the complete ciphertext to a private temp file first and opens the chosen document only for the final copy, so a failure before that copy leaves an existing target untouched (a failure during the copy itself can still leave it truncated; the error text says so).
- Key files are written data-fsync → rename → directory-fsync (`Os.fsync`; java.io cannot open a directory), and a directory fsync failure is reported instead of handing out an unproven key. A missing Keystore key is reported as invalidated rather than minting a new one.

Round 2 (re-review of those fixes, `docs/reviews/2026-09-06-round2/`) found no Critical and three Important issues, all fixed: an ambiguous single repeat shrank the checkpoint window (the next post could duplicate two messages), the directory fsync was a silent no-op, and two documentation claims were ahead of the code. Also fixed from its Minor list: the checkpoint-loss guard links one row per pre-existing row instead of collapsing a batch onto a single id; a window id that no longer exists is written as null instead of being carried forward; `CancellationException` is rethrown in the capture pipeline; the disconnected session id is cleared; restore staging bounds the total text.
- App lock is closed until the setting is known; search queries use 3-grams / single CJK characters so "hell" finds "hello" and "開" finds "開會".

## Known defects and rough edges found during device verification

- `ShortNavigationBar` / `WideNavigationRail` from material3 1.5.0-alpha27 rendered a single item; replaced with `NavigationBar` / `NavigationRail` (see ADR-0002).
- Pipeline counters on the Capture page are per-process (reset on restart); the persisted journal count is shown separately.
- Package visibility on Android 11+ required `<queries>`; sources not listed there and without a launcher activity can be added by exact package name.
- Restore stages the whole backup in memory before the atomic merge. The limits (2,000,000 records, 16M chars of text, 256 MB of media held as base64 text) are nominal: a deliberately huge file can exhaust the heap before a limit triggers, and a genuine vault larger than these limits cannot be restored. Streaming staging to a temp file is a v1.0 item. The vault itself is never touched before the counts check out.
- During cold start, until the first source list has loaded, third-party notifications are held as framework objects only (nothing is read from them; at most 256, the oldest evicted first); an eviction, a vault that does not open within 15 s, a notification of a capturable source held across a disconnect / pause / maintenance run, or a loss the locked vault could not record at the time is written as a bounded `COLD_START` gap once the vault can be written, and forgotten only once that write succeeded (audit #13).
- `closeWindow` / `closeAllWindows` run outside the pipeline mutex, so a close can race the checkpoint upsert of a commit in flight (the same-post-time rule limits the effect to a possible `AMBIGUOUS_REPEAT` label).
