# Review round 5 (mini re-review of round-4 fixes) — QuietInbox

DO NOT activate workflow modes; READ-ONLY review only. Do not edit, create or delete files in the repository; no git commands that change state; no instrumented tests; never touch devices or emulators.

Repository: /Users/iml1s/Documents/mine/quietinbox, branch main, HEAD fa49902. Review `git diff 7587c73..fa49902`.
Round-4 reports: `.omc/research/dual-review-round4-agy.md` (APPROVE WITH MINOR FIXES), `dual-review-round4-kimi.md` (APPROVE WITH MINOR FIXES), `dual-review-round4-subagent.md` (REQUEST CHANGES: I-1..I-5, M-1..M-8). This commit claims to fix agy Important 1–2, agy Minor 1/4, Kimi I-1/I-2 and Minor (store text), subagent I-1..I-5 and M-1..M-8 (M-8 partially: permit now wraps the whole unit and the bitmap is compressed once).

Verify each claimed fix (file:line) and look for regressions, in particular:
1. `BackupService.apply`: preparation inside the try, `usedFiles` accounting, cleanup of unused blobs on success and of all blobs on failure/cancellation; `Counts` correctness.
2. `DemoData` interface + `platform/storage/src/debug` / `src/release` Hilt modules: does the release variant compile without any reference to `DemoDataRepository`? Does the debug `DemoModule` bind correctly for `SettingsViewModel` and `DemoReceiver`? Any Hilt duplicate-binding risk?
3. `statsBetween(..., limit)` ordering (DESC + asReversed), `capped` flag and its string; `debounce(400)` + `distinctUntilChanged()` — does the first emission still arrive promptly and does a selection change still recompute?
4. `SnapshotFactory.isSelf` key/uri/name precedence — enumerate the cases; any way a real contact is marked self, or the owner marked not-self when the app sets only a name?
5. `Period.custom` clamp; `MediaCopier` permit/bitmap change; the two new instrumented tests (read them for correctness even though you cannot run them).
6. Workflows pinned to SHAs; `permissions` narrowed; `versionCode = 2`; docs/CHANGELOG honesty.

You may run: `export ANDROID_HOME=$HOME/Library/Android/sdk && ./gradlew test --console=plain` (JVM only).

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES
- Round-4 fix verification table (finding → verified / partially / not fixed, file:line)
- New Critical / Important / Minor findings
