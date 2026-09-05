# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog.

## [Unreleased]

### Changed
- Licence changed from Apache-2.0 to GPL-3.0-or-later (LICENSE, NOTICE, README, in-app licence text).
- Added `CLAUDE.md` (repository guidance for AI-assisted contributions).

## [0.1.0] — 2026-09-06

First installable vertical slice (plan §3 "v0.1"). Not published to any store.

### Added
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

### Known limitations
See `docs/SCOPE.md` and the in-app "Known limitations".
