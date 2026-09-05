# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog.

## [Unreleased]

Nothing yet.

## [0.1.0] — 2026-09-06

First installable vertical slice (plan §3 "v0.1"). Shipped as `versionCode` 4 to both channels on 2026-09-06: Google Play (paid, `dev.quietinbox.app`, submitted for Google review) and GitHub Releases (free, tag `v0.1.0`, signed APK + `SHA256SUMS.txt`). Play internal-track uploads 1–3 were earlier cuts of this version and never reached production. See ADR-0006 for the distribution model.

### Added
- Demo mode (debug builds only): `DemoDataRepository` fills the vault with fully synthetic, bilingual conversations — including one `AMBIGUOUS_REPEAT` pair, a revised message, a placeholder image, a preview-restricted body, capture gaps and diagnostics — so the app can be demonstrated and screenshotted without exposing a real notification. Triggered from Settings → Developer or from a debug-only broadcast receiver; `seed()` is idempotent and `clear()` deletes strictly by the `demo.quietinbox.` and `demo-` tags. Adds `tools/demo-screenshots.sh` (installs, walks onboarding, seeds, captures seven screens per locale) and the instrumented `DemoDataTest`. No schema change and no new permission.
- Activity statistics extended to five free tabs over one shared period selector (7 days / this month / last month / 3 months / all / custom range): a weekday x hour heat map, overall / weekday / weekend rankings, the dominant time band per conversation, observed messages per active day, the share of days with nothing observed plus the longest quiet run, repeated-phrase (CJK n-gram and Latin word) ranking per sender, and the emoji ranking re-scored per period. Nothing is locked behind a purchase and every label still describes observed messages only.
- Onboarding: scope, source selection, notification-access grant with restricted-settings guidance, synthetic test notification, label preview.
- Capture pipeline: `NotificationListenerService` → bounded allow-listed snapshot → bounded queue → encrypted journal → parser → identity → reconcile → single-transaction commit → media copy.
- Parsers: standard parser (MessagingStyle, InboxStyle, BigText, group summaries, preview placeholders, system notices) and five synthetic-only adapters (LINE, WhatsApp, Telegram, Instagram, Messenger).
- Identity and dedup: scoped identity keys, window alignment, `AMBIGUOUS_REPEAT`, revisions, stale-window replay handling, deletion suppression.
- Encrypted vault: Room + SQLCipher, per-installation Keystore-wrapped keys, retention worker, CJK/Latin n-gram search index.
- UI (Material 3 Expressive, zh-Hant + en): inbox with filters and quality labels, conversation with source/capture times and floating toolbar, search, activity statistics, capture health with sources/gaps/diagnostics, settings (theme, app lock, screenshot protection, retention, media disclosure, reminders, recovery key, encrypted backup/restore, delete all).
- Backup: Tink streaming-AEAD container keyed from a recovery key; verified import with atomic merge.
- Tests: 97 JVM tests including two 1,000-iteration property tests; instrumented SQLCipher round-trip, schema migration and durable key-write tests; RFC 5869 vectors.
- CI: JVM tests, assemble, network-permission gate, emulator lanes (API 29/35).

### Fixed (pre-release review round 1, 2026-09-06)
- Vault open failure could hang callers forever; pause did not rotate the capture generation; deleting a conversation did not suppress replays; restored media was garbage-collected; restore collapsed legitimate duplicates; journal replay raced live capture; FK violations rolled back whole batches; window alignment drifted with mixed ids; stale replays shrank checkpoints; resync produced spurious "possible repeat" rows; app lock could be bypassed on cold start; 4+ letter Latin and single CJK searches returned nothing; key files were not fsync'd. Database schema is now v2 with an explicit migration.
- Round 2 review: an ambiguous single repeat no longer shrinks the checkpoint window (a following post could duplicate messages); the key-directory fsync now really runs (`Os.fsync`; the java.io attempt silently failed on Android) and its failure is reported; export stages the ciphertext in a private temp file before touching the chosen document; restore staging bounds total text; the checkpoint-loss guard keeps multiplicity; stale window ids are dropped from checkpoints; capture no longer swallows coroutine cancellation.

### Changed (pre-publication reviews, 2026-09-06)
- Licence changed from Apache-2.0 to GPL-3.0-or-later (LICENSE, NOTICE, README, in-app licence text).
- Round 3 review minors: the checkpoint-loss guard links the newest matching rows (not the oldest); restore text staging limit lowered from 64M to 16M chars; best-effort bookkeeping in capture uses `guarded {}` (failures swallowed, cancellation propagated); disconnected session id cleared synchronously.
- Whole-repository review: a deleted conversation can no longer come back as an empty row after a notification replay (the conversation row is created only when something is stored); restoring a backup older than the retention window re-bases message expiry on the current setting instead of letting the next retention run delete everything; messages sent by the device owner are now recognised (`isSelf`) from MessagingStyle semantics; restore encrypts media before the write transaction and keeps duplicate multiplicity; restored sources come back disabled; `onRemoved` uses the same bounded tag as capture; SECURITY.md has a real reporting channel.
- Added `CLAUDE.md` (repository guidance for AI-assisted contributions).
- Rounds 5–6 review: the analytics "only the newest 50,000 messages are counted" notice is now rendered on every tab; a period switch shows a loading state at once while background vault changes recompute quietly, sampled at 400 ms after an immediate first pass, on `Dispatchers.Default`; restore no longer deletes attached blobs when a cancellation lands after the transaction committed; `isSelf` ignores blank names; the vault-count subscription is shared instead of duplicated and an error no longer leaves the page loading forever.
- Round 4 review (pre-publication): restore no longer leaves encrypted media files behind for messages it skipped, and reports only the media it actually attached; media preparation runs inside the cleanup scope; the summary-only count respects the end of the selected period; the demo seeder and its fictional content moved to the `debug` source set behind a `DemoData` interface (release binds a no-op); analytics load at most 50,000 messages per period (the UI says so when capped) and recompute at most once per 400 ms while the vault keeps changing (period switches recompute at once); custom periods are clamped like "All"; `isSelf` compares the MessagingStyle Person key/uri before falling back to the name; `MediaCopier` compresses a shared bitmap once and holds the permit for the whole unit of work; every GitHub Action is pinned to a commit SHA; `keystore.properties` gaps fail with a clear message; instrumented regression tests for the deleted-conversation replay and for `clear()` sparing non-demo rows.

### Known limitations
See `docs/SCOPE.md` and the in-app "Known limitations".
