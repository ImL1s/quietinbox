# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog.

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
- Tests: 74 JVM tests including a 1,000-seed property test; instrumented SQLCipher round-trip test; RFC 5869 vectors.
- CI: JVM tests, assemble, network-permission gate, emulator lanes (API 29/35).

### Known limitations
See `docs/SCOPE.md` and the in-app "Known limitations".
