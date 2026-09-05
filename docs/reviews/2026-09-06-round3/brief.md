# Review round 3 (mini re-review after round-2 fixes) — QuietInbox

DO NOT activate workflow modes; READ-ONLY review only. Do not edit, create or delete any file in the repository. Do not run git commands that change state. Do not run instrumented tests or install anything on a device.

## What to review
Repository: <repo>, branch main, HEAD 6a9b0ce.
Review `git diff c96fbf0..6a9b0ce` (20 files) in the context of the whole codebase.

Round-2 reports: `docs/reviews/2026-09-06-round2/gemini-3.8-flash-high-agy.md` (APPROVE WITH MINOR FIXES, 3 Minor) and
`docs/reviews/2026-09-06-round2/claude-subagent.md` (REQUEST CHANGES: Important 1–3, Minor 1–9).
This commit claims to fix: subagent Important 1, 2, 3 and Minor 1, 3, 4, 5, 6, 8, 9; agy M1, M2, M3.
Not fixed on purpose (documented as known issues): subagent Minor 2 (cold-start offer filtering) and Minor 7 (closeWindow outside the pipeline mutex).

Your job:
1. Verify each claimed fix is real and complete (file:line), and that the tests added actually cover the defect
   (`ReconcilerAmbiguousKeepTest`, the second property in `ReconcilerPropertyTest`, `WrappedSecretFileTest`).
2. Look hard for regressions introduced by the fixes, in particular:
   - `Reconciler.kt` `addsNothing` now ignores `AmbiguousRepeat`: interplay with `IngestRepository.commit`
     (`storedIds` is now `HashMap<Int, Long?>`; `WINDOW_KEPT` items have `decisionIndex = null`; the ambiguous
     row is inserted with `DedupState.AMBIGUOUS_REPEAT` but is not in the kept window — can that cause a
     duplicate or a lost link on the next post?).
   - `WrappedSecretFile.fsyncDirectory` (`Os.open(dir, O_RDONLY, 0)` + `Os.fsync` + `Os.close`, failure → `IOException`
     → `KeyResult.Failed(Unavailable)`): any case where a healthy device now fails to create a key
     (SELinux, FUSE/emulated storage is NOT used — files live under `filesDir`)? The `.tmp` and the renamed file
     both exist after a partial failure — is `getOrCreate()` on the next call correct?
   - `BackupService.export` staging in `cacheDir`: the temp file name, deletion on every path, behaviour when
     `cacheDir` is full, and whether the `Ok(counts)` return moved out of the `use` blocks changed EOF handling.
   - `IngestRepository` `preExisting: Map<String, ArrayDeque<Long>>` consumed with `removeFirstOrNull()`; the new
     `findIdsByFingerprint` DAO query; the explicit-null `storedIds` mapping.
   - `CaptureCoordinator`: `CancellationException` rethrown in the consumer loop (`catch (t: Throwable)`) — does the
     restart loop still restart after non-cancellation throwables, and does the scope terminate cleanly?
   - `BackupService.stage` `MAX_STAGED_TEXT_CHARS` (64M chars): reasonable, and counted correctly?
   - Is the repeated-content property sound (it asserts no duplication, not no loss — see the KDoc)?
3. Check the documentation claims changed in `docs/SCOPE.md`, `docs/adr/0004-identity-and-dedup.md`,
   `CHANGELOG.md` and the two `backup_failed_io` strings against the code (docs-honesty).

You may run: `export ANDROID_HOME=$HOME/Library/Android/sdk && ./gradlew :core:reconcile:test :core:model:test --console=plain`.

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES
- Round-2 fix verification table: finding → verified fixed / partially / not fixed (file:line)
- New Critical / Important / Minor findings (file:line, why, concrete fix)
- Other observations
