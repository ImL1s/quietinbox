# Review round 20 (mini re-review of the round-19 fixes) — QuietInbox

DO NOT activate workflow modes; READ-ONLY review only. No edits, no state-changing git, no instrumented tests, no devices.

Repository: /Users/iml1s/Documents/mine/quietinbox, branch main. Review `git diff ee48710..c90e75f` (one commit, `c90e75f`). Round-19 reports: `docs/reviews/2026-09-06-round19/{gemini-3.8-flash-high-agy,claude-subagent}.md` — this commit answers the subagent's Important I-1 and Minors 1–10 and agy's two nits. Verify each against the diff (file:line / string name):

1. `tools/demo-screenshots.sh`: `imes_off` (record `default_input_method`, warn when fewer than two input methods are enabled, `ime disable` the default) runs right before the app is launched; `imes_on` (`ime enable` + `ime set`) runs from the EXIT trap; after `input text`, `KEYCODE_BACK` hides the (voice) input method; `has_text "$SEARCH_QUERY" || die` guards `shot "3_search"`. Think about: the BACK press when no input method is showing (would leave the search screen → the assertion dies, which is the intended loud failure?); a device where the default IME is the only one (warn, then the assertion fails — acceptable?); the trap restoring the IME even when `imes_off` never ran (`IME_DISABLED` guard). Claimed: all five locales re-shot, each `3_search.png` shows "meeting" with results (19; en-US 14) and no keyboard; `docs/screenshots/phone/*/3_search.png` and the fastlane copies are identical.
2. Text fixes: ko `analytics_quiet_formula` and the ko store text (소스 앱); zh-Hant `conv_open_source_body` / `health_no_gaps` / `section_reminders`; ja store `full_description` (活動の分析, サイレント モード) and changelog 5 / whatsnew / release-notes.json (活動画面); `DemoLocalisation` amounts 12,400 円 / 124,000원. Any leftover 원본 앱 (app strings or store), 取り除く, アクティビティ in ja store/what's-new, or 数据库-for-vault in zh-Hans?
3. `tools/check-strings.py`: `LOCALE_DIR` now excludes tv / car / desk / watch / vrheadset / night / land / port; `PLACEHOLDER` = `%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdf]|%%`. Mutations to consider: `values-tv` with one string → ignored? `%.1f` in the default and missing in a locale → error? `%,d` likewise?
4. `MonogramTest` (core/designsystem/src/test): 4 JUnit4 tests with kotest matchers; claimed green, 210 JVM tests in total, lint 0 errors. Does the convention plugin give the module the JUnit runner it needs (`testImplementation junit`)?
5. Docs: CHANGELOG (round-19 line, 210 / MonogramTest), TEST_MATRIX en/zh (Design system row, screenshot harness note), CLAUDE.md audit trail (rounds 18–19), reviews index rows 18 (`ee48710`) and 19.

You may run `python3 tools/check-strings.py`, `bash -n tools/demo-screenshots.sh`, and `./gradlew :core:designsystem:testDebugUnitTest --console=plain -q` (ANDROID_HOME=$HOME/Library/Android/sdk); no instrumented tests, no devices. You may look at the PNGs under docs/screenshots/phone/*/ (read-only).

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES
- Round-19 verification table (finding → fixed? evidence); new findings (Critical / Important / Minor) with concrete scenario and file:line; other observations
