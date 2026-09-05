# Review round 9 (final confirmation of round-8 fixes) — QuietInbox

DO NOT activate workflow modes; READ-ONLY review only. No edits, no state-changing git, no instrumented tests, no devices.

Repository: /Users/iml1s/Documents/mine/quietinbox, branch main, HEAD ea34339. Review `git diff 652aa69..ea34339` (two commits: 1249be5 and ea34339).
Round-8 reports: `docs/reviews/2026-09-06-round8/{gemini-3.8-flash-high-agy,claude-subagent}.md`. What the two commits claim:
- agy I-1 / subagent Minor: `vaultSignals()` now uses `vault.state.flatMapLatest`; every transition to Ready restarts the inner count subscription (replayed first value ticks at once), so Locked → Ready recovers with unchanged counts. A seventh test locks the vault while the page is open and unlocks it without a count change (verified to fail on the previous implementation).
- subagent M-1: the test harness mirrors `flowWithDb` (counts observable only while Ready); the off-main-thread check compares against the collector's thread instead of a worker thread name.
- subagent "three items": the quiet-recompute test now holds the recompute at its first query and asserts the visible state (verified to fail when the loading guard is removed); `orDefault` became `Degradation.orDefault` and a failed query sets `AnalyticsUiState.degraded`, rendered as an error-coloured honesty label (`analytics_degraded`, en + zh-Hant); the count-query back-off counter resets on any successful emission (`onEach` + local counter instead of retryWhen's cumulative attempt).
- CI: the JVM job now also runs `:platform:capture:testDebugUnitTest` and `:feature:analytics:testDebugUnitTest`.
- Imports sorted; CHANGELOG (Unreleased), SCOPE, TEST_MATRIX (en + zh-Hant), reviews index updated (8 tests).

Verify each claim. Then look for regressions: `flatMapLatest` cancelling the inner subscription on every vault-state change (any lost tick or double compute?); the `consecutiveFailures` var captured by two lambdas on one flow chain; `Degradation` as a private class with an inline member-extension; whether `degraded` can be shown while loading or carried by the placeholder; the string parity between the two `strings_analytics.xml`; anything tautological or timing-flaky in the 8 tests (real `Dispatchers.Default`, `sample(400)`, `withTimeoutOrNull(700)`). Confirm docs match code. You may run `./gradlew :feature:analytics:testDebugUnitTest --console=plain` (JVM only; ANDROID_HOME=$HOME/Library/Android/sdk).

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES
- Round-8 verification table (finding → fixed? evidence with file:line); new findings (Critical / Important / Minor) each with a concrete failure scenario; other observations
