# Review round 8 (confirmation of round-7 fixes) — QuietInbox

DO NOT activate workflow modes; READ-ONLY review only. No edits, no state-changing git, no instrumented tests, no devices.

Repository: /Users/iml1s/Documents/mine/quietinbox, branch main. Review `git diff ad5ee31..b4e5639` (one fix commit; 652aa69 after it only edits the review index).
Round-7 reports: `docs/reviews/2026-09-06-round7/{gemini-3.8-flash-high-agy,claude-subagent}.md`. Findings that b4e5639 claims to fix:
- I-1 (subagent): the loading placeholder on a period switch carried the previous period's `capped` label (and report/subtitle). Now a fresh `AnalyticsUiState(loading = true, selection = s)` is emitted.
- I-2 (subagent): a Locked / Opening vault left the activity page spinning forever because `combine` never emitted. Now `vaultSignals()` merges every non-Ready `VaultRepository.state` with the sampled count ticks, and `transformLatest` emits an explained `vaultLocked` state (rendered as an `EmptyState` "Vault locked") or keeps loading while Opening.
- M-1/M-2 (both): all five query sites go through one cancellation-safe `orDefault` helper; the count flow uses `retryWhen` (fallback tick + back-off) instead of `catch { emit }` that ended the ticks.
- M-5/M-6 (subagent): `last` is now read and written only inside the `transformLatest` block; `coroutineContext.ensureActive()` between the CPU stages of `compute()`.
- M-7 / M-3 (both): `feature/analytics/src/test/.../AnalyticsViewModelTest.kt` (6 tests, mockk + coroutines-test, real `Dispatchers.Default`).
- New: `selectedPeriod` StateFlow feeds the chip row directly so a tap is reflected before a slow query is cancelled.

Verify each claim against the code. Then look hard at the new Flow chain for regressions: `merge(vault.state.filter { it !is Ready }, ticks.map { vault.state.value })` — can the first emission be lost, can Locked → Ready recovery fail, can `retryWhen` busy-loop or starve, can `ticks.map { vault.state.value }` race a state change; is the `last` bookkeeping now race-free; does `emit(...).also { last = it }` on the Locked/Opening branches behave; is anything in the test suite tautological, timing-flaky, or dependent on `Dispatchers.Default` thread naming in a way CI could break. Confirm docs (CHANGELOG Unreleased, SCOPE, TEST_MATRIX en + zh-Hant, reviews index) match the code. You may run `./gradlew :feature:analytics:testDebugUnitTest --console=plain` (JVM only).

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES
- Round-7 verification table (finding → fixed? evidence with file:line); new findings (Critical / Important / Minor) each with a concrete failure scenario; other observations
