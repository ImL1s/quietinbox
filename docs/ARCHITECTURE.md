# Architecture

## Module graph

```
app ──► feature:* ──► core:designsystem ──► core:model
 │         │
 │         └──► platform:storage ──► core:parser / core:identity / core:reconcile / core:analytics
 │                     │            └──► platform:crypto (Keystore KEK, Tink AEAD, recovery key codec)
 │                     └──► Room + SQLCipher, DataStore, WorkManager
 ├──► platform:capture ──► parsers:apps ──► core:parser
 │            └──► platform:media ──► platform:crypto, platform:storage
 └──► platform:backup ──► platform:crypto, platform:storage, Tink Streaming AEAD
```

`core:*` and `parsers:apps` are plain Kotlin/JVM modules: they cannot reference `android.*`, run on
the JVM under Kotest, and hold every algorithm the plan requires to be testable without a device
(parsing, identity, deduplication, statistics, normalisation). `platform:*` modules wrap Android
APIs; `feature:*` modules are Compose UI + Hilt ViewModels; `app` wires navigation and DI.

## Capture pipeline (plan §5)

```
StatusBarNotification
  → CaptureCoordinator.isCapturable      (enabled-source allow-list; own package only with the synthetic marker)
  → SnapshotFactory.create               (allow-listed extras, size bounds, TruncationFlags; no PendingIntent/RemoteViews/Bitmap decode)
  → Channel(MAX_QUEUE_DEPTH)             (overflow ⇒ counted, DEGRADED, gap recorded — never DROP_OLDEST silently)
  → generation check                     (commit fence: anything queued before revoke/pause is discarded)
  → IngestRepository.journal             (durable accepted; JSON payload in the encrypted vault, short TTL)
  → ParserRegistry.parse                 (adapter by package, else StandardParser)
  → IdentityResolver.resolve             (chat id > shortcut > notification stream > title; never cross-stream)
  → Reconciler.reconcile                 (suffix/prefix window alignment, ids, AMBIGUOUS_REPEAT, stale windows)
  → IngestRepository.commit              (one transaction: conversation, messages, revisions, links, tokens, checkpoint, journal state)
  → MediaCopier.copyPending              (bounded, time-limited, encrypted blobs; failure reasons kept)
  → Room Flows → ViewModels → Compose
```

Process death before `journal` loses the event (documented as platform-unobservable); after
`journal` the row is replayed on next vault open with `CaptureOrigin.REPLAY`.

## Storage (plan §8)

Single SQLCipher database `quietinbox.vault` (WAL) with the tables of §8: `source_configuration`,
`capture_session`, `gap_interval`, `event_journal`, `notification_checkpoint`, `conversation`,
`message`, `message_revision`, `observation_link`, `media_blob`, `deletion_suppression`,
`search_token`, `summary_observation`, `local_diagnostic_event`. Schema is exported to
`platform/storage/schemas/` and `fallbackToDestructiveMigration()` is not used.

Search: `search_token(token, messageId)` holds CJK bigrams, Latin words and 3-grams produced by
`SearchNormalizer.tokens`; a query is the same tokens joined by `GROUP BY … HAVING COUNT(DISTINCT
token) = n`, then every candidate is re-verified as a normalised substring in Kotlin.

## Keys (plan §9)

- `KeystoreWrapper`: AES-256-GCM key in AndroidKeyStore, `setUserAuthenticationRequired(false)`,
  so the listener can write while the screen is locked. The UI lock is a separate gate.
- `KeyMaterial`: three 32-byte random secrets (`db.key`, `media.key`, `recovery.key`) stored only
  Keystore-wrapped under `files/keys/`. Failure ⇒ `VaultState.Locked`, never a silent wipe.
- `BlobCipher`: Tink AES-256-GCM with the file name as associated data.
- `BackupCrypto`: HKDF-SHA256(recovery key, random salt) → Tink AES-256-GCM-HKDF streaming AEAD;
  header (magic, version, salt) bound as associated data.

## UI

Material 3 Expressive (`MaterialExpressiveTheme`, expressive motion scheme, large shapes) with a
brand palette by default and optional dynamic colour. Navigation 3 back stack; on windows ≥ medium
width a `NavigationRail` plus `ListDetailSceneStrategy` shows inbox and conversation side by side.
Every quality state renders text + icon (colour is never the only signal).
