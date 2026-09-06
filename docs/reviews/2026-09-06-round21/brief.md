# Review round 21 (mini re-review of the round-20 fixes) — QuietInbox

DO NOT activate workflow modes; READ-ONLY review only. No edits, no state-changing git, no instrumented tests, no devices.

Repository: /Users/iml1s/Documents/mine/quietinbox, branch main. Review `git diff c90e75f..8954af1` (one commit, `8954af1`). Round-20 reports: `docs/reviews/2026-09-06-round20/{gemini-3.8-flash-high-agy,claude-subagent}.md` — this commit answers the subagent's Important I-1 and Minors 1–4 (+ observations 2, 3, 7, 8) and agy's four Minors. Verify against the diff:

1. `tools/demo-screenshots.sh`: `wait_text "$DEMO_PINNED_TITLE" 10` before `shot "2_conversation"`; `MIN_SHOT_BYTES=80000` enforced in `shot()`; the per-app locale is polled (`cmd locale get-app-locales`, up to 5 s, re-set) before `am start`; the seed broadcast carries `--es lang "$LOCALE"`; the search query is typed one character per `input text` call after a 2 s settle, then `KEYCODE_ENTER`; `ime_shown` captures `dumpsys input_method` into a variable first and matches `(mInputShown|isInputShown)=true|mImeWindowVis=(0x)?[1-9a-f]`; `dump_ui || die` separated from the has-text mismatch; the EXIT trap is registered after `imes_on`; `has-text` also matches content descriptions. Think about: is the per-character loop's `${SEARCH_QUERY:$i:1}` safe under `set -u`; does ENTER (ImeAction) on the search field do anything else in the app (submit? it is a live search); the 80 KB floor vs the smallest real shot (146 KB) and a dark-mode inbox; `wait_text` polling cost.
2. `DemoData.seed(now, locale: Locale? = null)`, `NoDemoData`, `DemoDataRepository` (locale ?: configuration), `DemoReceiver` `EXTRA_LANG` → `Locale.forLanguageTag`; `SettingsViewModel` still calls `seed()` (configuration); `DemoDataTest` unchanged (`seed(now)`). Does the Hilt `@Binds` of `DemoData` still compile for release (`NoDemoData`)?
3. Docs: TEST_MATRIX en/zh bullet now reads as one sentence with the input-method note at the end; CHANGELOG round-20 line; CLAUDE.md demo section mentions `--es lang`; reviews index row 19 = `c90e75f`, row 20 added (fix column still "follow-up commit" — expected until the next docs commit); ja changelog 4 活動分析; `.gitignore` has `__pycache__/` and the .pyc is gone from the tree.
4. Screenshots: 35 PNGs replaced; claimed docs/ and fastlane/ copies identical per locale; the conversation pages localised; search pages show the query with results and no keyboard; smallest file 146 KB. You may open the PNGs read-only.
Claimed verification: parity OK; `./gradlew test lint` green (210 JVM, 0 lint errors); `:platform:storage:compileDebugAndroidTestKotlin` OK; the tool ran in all five locales with the assertions on (one warning-free ko-KR run with the final visibility regex).

You may run `python3 tools/check-strings.py`, `bash -n tools/demo-screenshots.sh`, `./gradlew :core:designsystem:testDebugUnitTest :platform:storage:compileDebugAndroidTestKotlin --console=plain -q` (ANDROID_HOME=$HOME/Library/Android/sdk); no instrumented tests, no devices.

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES
- Round-20 verification table (finding → fixed? evidence); new findings (Critical / Important / Minor) with concrete scenario and file:line; other observations
