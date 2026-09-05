# ADR-0004: Identity and deduplication model

Date: 2026-09-06 · Status: accepted

## Identity (plan §7.1)

Inside a `SourceScope` (package + profile + optional account key):

1. adapter-provided source chat id with `VERIFIED` evidence → `chat:<id>` (none exist yet);
2. `shortcutId` → `shortcut:<id>` (`INFERRED_FROM_STREAM`);
3. notification tag/id stream → `stream:<tag>|<id>` (`INFERRED_FROM_STREAM`);
4. title only → `title:<text>` (`UNRESOLVED`).

Group keys and collapse keys are never conversation ids. Same-named conversations in different
streams are never merged automatically.

## Deduplication (plan §7.2)

`Reconciler` keeps a bounded window (≤ 64 items) per notification stream in
`notification_checkpoint`:

- proven `sourceMessageId` decides identity; same id + different body ⇒ revision;
- otherwise the new window is aligned to the previous one by the largest suffix/prefix overlap;
  items after the overlap are new; a window fully contained in the previous one is a stale replay;
- a single id-less, timestamp-less item identical to the last known one, posted under a closed or
  different notification, is `AMBIGUOUS_REPEAT`: stored as its own row, linked to the original,
  counted separately in the UI, never silently dropped;
- identical items within one window keep their multiplicity;
- oversized windows degrade (truncate + `DEGRADED_RESOURCE_LIMIT`) instead of blocking.

User deletion writes a body-free suppression token (conversation id + fingerprint, 30-day TTL) so an
active-notification replay cannot resurrect the message. Restoring a backup can, and the UI says so.
