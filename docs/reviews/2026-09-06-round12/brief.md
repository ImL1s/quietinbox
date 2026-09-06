# Review round 12 (mini re-review of the round-11 fixes) — QuietInbox

DO NOT activate workflow modes; READ-ONLY review only. No edits, no state-changing git, no instrumented tests, no devices.

Repository worktree: /Users/iml1s/Documents/mine/quietinbox-wave2 (branch `wave2`), HEAD a3fd01b. Review `git diff 825d708..a3fd01b` (one commit on top of the round-11 base 825d708).
Round-11 reports: `docs/reviews/2026-09-06-round11/{gemini-3.8-flash-high-agy,claude-subagent}.md`. This commit claims to fix every round-11 finding:

- agy I-1 / subagent Important-2: `BackupService.writeRecords` reads rows inside one `withTransaction` (counts + conversations + messages + revisions + media *metadata* pages) and decrypts/streams media **after** the transaction; `BackupRecord.End` carries `skippedMedia`; an export refused during maintenance returns `Reason.MAINTENANCE` with its own string; `BackupRoundTripTest` +1 (export refused inside `maintenance.exclusive`).
- subagent Important-2 (b): `CaptureCoordinator.process()` tracks `journaled`; an exception before the journal insert records an exact `UNKNOWN` gap + `JOURNAL_FAILED` diagnostic instead of `markJournalRetryable` on a missing row; the old "ordinary pipeline failure" test now fails after acceptance (`markJournal` throws) and a new test covers the pre-acceptance failure.
- subagent Critical-1 / Important-1 / Minor-1 / Minor-2: `hold()` launches the cold-start job inside `synchronized(held)`; `coldStart()` calls `releaseHeld()` when the policy is already loaded; `releaseHeld()` records a bounded `COLD_START` gap when `heldDropped > 0` and snapshots with `heldAtEpochMs`; `MAX_HELD = 256`; new test "a held buffer that overflowed … records the drop as a gap and keeps only sources" (300 notifications, 128 survive).
- agy M-3: `dropHeld()` opens one `COLD_START` gap per lock-out (`coldStartGapId`), `loadSourcePolicy()` closes it; the lock-out test asserts one `openGap` for two notifications and one `closeOpenGaps` after the policy loads.
- subagent Important-3: `SuppressionRule` — matching ids suppress; two *different* ids fall back to the post-time rule; KDoc + CHANGELOG + SCOPE record the residual limitations; `SuppressionRuleTest` (4).
- agy M-2 / subagent Minor-3: `ListenerAccess.openSettings()` tries each intent without `resolveActivity`; `settingsIntent()` builds the list once.
- Minor-4: unexpected reset exceptions map to `delete_everything_step_unexpected`; Minor-5: `unviewedCount` requires `messageCount > 0`; Minor-6: tautological assertion removed; Minor-7: gate exercised in `BackupRoundTripTest`; Minor-9: `VaultMaintenance` KDoc; Minor-10: `va == vb`; Minor-11: cursor KDoc; Minor-12: dead `reminder_body` removed (both locales).
- Docs: CHANGELOG (round-11 bullet, counts 196 JVM / capture 22 / SuppressionRule 4 / backup instrumented 2), TEST_MATRIX en/zh, SCOPE, reviews index rows 11 (en/zh), round-11 archive.
Claimed: 196 JVM tests green, lint 0 errors, instrumented storage 15 / crypto 2 / backup 2 green on the API 36 AVD.

Verify each claim against the diff, then look for regressions: the `synchronized(held)` block now launching a coroutine (any lock held while `scope.launch` schedules?); `releaseHeld()` launching the gap record from inside the pipeline lock; `coldStartGapId` reset paths (maintenance end sets `sourcesLoaded = false` — is a stale gap id possible?); the `journaled` flag when `ingest.journal` returns false (duplicate id) versus throws; export: `mediaRows` held in memory (metadata only — how large can it get?), manifest media count vs `End.skippedMedia`, the `BackupStager` still accepting old backups without `skippedMedia`; suppression fallback when `tokenPostedAtEpochMs` is null but ids differ; string parity en/zh-Hant; docs vs code counts. You may run `./gradlew test --console=plain -q` (JVM only; ANDROID_HOME=$HOME/Library/Android/sdk) in the worktree; do not run instrumented tests.

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES
- Round-11 verification table (finding → fixed? evidence with file:line); new findings (Critical / Important / Minor) each with a concrete failure scenario and file:line; other observations
