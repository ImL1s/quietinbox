# Review round 19 (mini re-review of the round-18 localisation fixes) — QuietInbox

DO NOT activate workflow modes; READ-ONLY review only. No edits, no state-changing git, no instrumented tests, no devices.

Repository: /Users/iml1s/Documents/mine/quietinbox, branch main. Review `git diff 7ef07de..ee48710` (one commit, `ee48710`). Round-18 reports: `docs/reviews/2026-09-06-round18/{gemini-3.8-flash-high-agy,claude-subagent}.md` — this commit answers both. Verify each item against the diff, with file:line or string name:

1. `localeFilters` now `en, b+zh+Hant, b+zh+Hans, zh-rTW, zh-rCN, zh-rHK, ja, ko`; claimed: release APK `aapt2 dump resources` shows 89 configs each of (zh-rCN)/(zh-rTW)/(zh-rHK) and badging locales `ja ko zh-CN zh-HK zh-Hans zh-Hant zh-TW`. Any side effect of listing both b+zh+Hant and zh-rTW (duplicate app strings? which wins on a zh-Hant-TW device — the app's own `values-b+zh+Hant` must still win over nothing, since the app has no `values-zh-rTW`)?
2. `DemoLocalisation.kt` (platform/storage debug source set) + `DemoDataRepository` (injects `@ApplicationContext Context`, `context.resources.configuration.locales[0]`, `PendingMessage.localised(words)` after `extras`, title swapped): every authored Chinese string of the seeder has a zh-Hans, ja and ko entry (compare the maps against the literals in `DemoDataRepository`; a missing key silently keeps the Traditional text); the ja/ko names are invented; `SEARCH_SAMPLE` "meeting" survives in enough bodies per language for the screenshot search; `DemoDataTest` passes the context; release builds still contain none of it (debug source set).
3. `tools/demo-screenshots.sh`: `tap_tab` (bottom 15% of the screen) for the five NAV taps, per-locale `DEMO_PINNED_TITLE`; the ja `analytics_tile_captured` is キャプチャ済み. Claimed: zh-CN / ja-JP / ko-KR reruns produce seven distinct shots each (Activity ≠ Capture by md5).
4. Term fixes listed in the commit message (zh-Hans 保险库/对话/QuietInbox/核对/继续捕获/QuietInbox 的提醒; zh-Hant 金庫/對話/QuietInbox/核對; ja 送信元・削除・活動・システム・一部制限・待機中・このアプリについて・自分で一時停止・サイレント モード; ko 소스/보관처리/미확인/폐기됨/사용 설정됨/시각 알 수 없음; the "may mark messages read on the source side" sentence) — check they are applied consistently (no leftover 数据库 for vault in zh-Hans, no 出처/원본 앱 in ko app strings or store text, no 取り除く in ja) and that no placeholder was disturbed.
5. `search_empty_hint` in all five catalogues + the five store descriptions now name Chinese, Japanese and Korean; en-US changelog 5 (≤ 500 chars) gains the Activity sentence; `release-notes.json` five languages, 0.1.1; zh-CN/zh-TW changelog say 删除所有数据 / 刪除所有資料.
6. `tools/check-strings.py`: unknown plurals, `LOCALE_DIR` regex (values-night / values-v31 excluded), single-level module glob, `translatable="false"` skipped, `%f`. Try the mutations from round 18 (M4 unknown plurals, M6 values-v31 with one string) mentally or in a scratch copy.
7. `monogram()`: kana / hangul first glyph. Docs: CHANGELOG, SCOPE en/zh, TEST_MATRIX en/zh, CLAUDE.md, RELEASE.md; index row 18.
Claimed verification: parity OK; assembleDebug + assembleRelease + lint (0 errors) + 206 JVM tests green; instrumented storage 16 green on the API 36 AVD; screenshots inspected (zh-CN inbox shows 张书豪 / 读书会 / 产品团队; ko shows 김미아 Mia Kim / 가족 단톡방; ja Capture page is the Capture page).

You may run `python3 tools/check-strings.py` and `./gradlew :core:designsystem:lintDebug :platform:storage:compileDebugAndroidTestKotlin --console=plain -q` (ANDROID_HOME=$HOME/Library/Android/sdk); no instrumented tests, no devices.

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES
- Round-18 verification table (finding → fixed? evidence); new findings (Critical / Important / Minor) with concrete scenario and file:line / string name; other observations
