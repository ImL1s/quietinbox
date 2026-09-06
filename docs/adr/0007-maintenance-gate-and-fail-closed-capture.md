> 繁體中文：[docs/zh-Hant/adr/0007-maintenance-gate-and-fail-closed-capture.md](../zh-Hant/adr/0007-maintenance-gate-and-fail-closed-capture.md)

# ADR-0007: Maintenance gate and fail-closed capture

Date: 2026-09-06 · Status: accepted

## Context

An independent audit (GPT-5.5 Pro, base `96b0cf9`; issues #1–#17) showed that the product's four
plain promises were not complete guarantees in the code: *stop means stop* (a queued event could
still be committed after a pause, a revoke or a source switched off), *deleted stays deleted*
(journal payloads and media outlived their rows; a replay could bring content back), *nothing
before consent* (media copies were on before the disclosure), and *a reset leaves nothing behind*
(a cached cipher could encrypt with a destroyed key; other work kept writing during the reset).
Cold start read the extras of every app's notification before the user's source list was known.

## Decision

- **One gate for every vault writer.** `VaultMaintenance` (platform/storage) owns the pipeline
  mutex, a registry of cancellable *vault work* (`work {}`: media copies, journal replay,
  retention, backup export) and *exclusive runs* (`exclusive {}`: delete-everything, backup
  import). An exclusive run flags, cancels and joins the workers, then holds the pipeline lock for
  its whole duration. Listeners (`MaintenanceListener`) are told synchronously at start and end,
  never through a conflating flow. The capture side rotates its generation on start (everything
  queued is dropped, nothing new is queued) and records the window as an exact `MAINTENANCE` gap.
- **Three fences on the capture pipeline.** Admission is evaluated before waiting for the pipeline
  lock and again inside it; the commit is fenced once more right before the write. A pause or a
  maintenance run leaves an accepted event `PENDING` for the next replay; a source disabled or
  removed since discards it (`DISCARDED`, payload cleared). Source policy changes (add, enable,
  pause, remove) are made *under* the pipeline lock, so an event that waited for the lock is fenced
  against the new policy. Replay never runs while paused and excludes paused sources at the query.
- **Text does not outlive its row.** A journal payload is cleared the moment the row leaves
  `PENDING`; media rows and files go with their messages; removing a source with its data removes
  its whole deletion graph; the conversation projection is rebuilt from what remains; reads filter
  expiry themselves instead of waiting for the retention worker.
- **Keys have an epoch.** `KeyMaterial.epoch` is bumped when the secrets are destroyed; a cached
  cipher primitive is tied to the epoch it was built under and is neither cached nor returned when
  the epoch moved during the build. KEK creation is serialised process-wide.
- **Fail closed on cold start.** Before the source list is known, a third-party notification is
  held as the framework object only (nothing is read from it), bounded to 256 with the oldest
  evicted first; once the policy is known only notifications from enabled sources are snapshotted.
  Every loss — an eviction, a vault that does not open within 15 s, a loss the locked vault could
  not record at the time — is written as a bounded `COLD_START` gap as soon as the vault can be
  written, and a loss is only forgotten once that write succeeded. Gaps are shown, never hidden,
  even when the gap table itself was unreachable.
- **Delete-everything is verified.** Each step (database files, media files, keys, reopen) is
  checked and the failed step is named; the vault is always reopened, so a failed reset can never
  leave the app hanging on an `Opening` vault.

## Consequences

- Schema v3 (three nullable columns: journal `packageName`, suppression `sourceMessageId` and
  `postedAtEpochMs`). No row rewritten.
- A restore or reset is a short, honest capture gap rather than a silent overlap; a backup export
  can be cancelled by a reset and reads media outside the transaction so it never blocks capture.
- Deletion suppression is keyed by fingerprint: a replay of the same post is suppressed as a whole
  (ids decide when both sides carry the same one, otherwise post time), and a genuinely new
  message with the same fingerprint inside that same post is suppressed too. A per-id token needs
  a later schema version. Media copies are off until the disclosure is accepted, also for
  installations that had the old default.
- What this ADR does not settle: per-profile source control (schema work), real-source fixtures
  (#17), and the governance items still outside CI (detekt, CodeQL, SBOM, reproducible builds).
