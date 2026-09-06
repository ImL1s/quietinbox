# Review round 24 (release readiness of 0.1.2) — QuietInbox

DO NOT activate workflow modes; READ-ONLY review only. No edits, no state-changing git, no instrumented tests, no devices, no Play Console access.

Repository: /Users/iml1s/Documents/mine/quietinbox, branch main, HEAD `0bf44ba` (local, not pushed). Review `git diff 996f8d7..0bf44ba` (one commit: the 0.1.2 version bump) **and** the release as a whole: everything on main since tag `v0.1.1` (`git log v0.1.1..0bf44ba`, rounds 13–23 already reviewed, reports under `docs/reviews/2026-09-06-round{13..23}/`).

## What ships
- `versionCode` 6 / `versionName` 0.1.2 (`app/build.gradle.kts`).
- Store notes for versionCode 6: `fastlane/metadata/android/{en-US,zh-TW,zh-CN,ja-JP,ko-KR}/changelogs/6.txt`, the same texts in `fastlane/whatsnew/whatsnew-<locale>` and `fastlane/release-notes.json`. Google Play hard limit 500 characters each (`wc -m`).
- `CHANGELOG.md`: `[Unreleased]` folded into `## [0.1.2] — 2026-09-06`; new block `### Known issues in 0.1.1 (fixed in 0.1.2)` at the end of the 0.1.1 section.
- Context that matters for the wording: Play still has **0.1.0** (versionCode 4) under review; **0.1.1 was released on GitHub only**. 0.1.2 will be uploaded to Play production directly (superseding the in-review 0.1.0), so Play users update from 0.1.0 straight to 0.1.2 and the versionCode-6 note must also carry the 0.1.1 audit fixes. GitHub users go 0.1.1 → 0.1.2.

## Evidence already collected (verify, do not repeat on devices)
- `./gradlew test :app:assembleRelease :app:bundleRelease` green: 212 JVM tests, 0 failures (JUnit XML under `**/build/test-results/`).
- `aapt2 dump badging` of the release APK: versionCode 6, versionName 0.1.2, locales ja ko zh-CN zh-HK zh-Hans zh-Hant zh-TW, no INTERNET permission (`tools/check-permissions.sh` OK); signer CN=QuietInbox Upload, SHA-256 a82bdd…bedf29; AAB `jar verified`.
- Release APK (FLAG_SECURE, so uiautomator dumps, not screenshots) walked on the API 36 phone AVD: onboarding in English; Messages (com.google.android.apps.messaging) added as a source; two SMS injected through the emulator console were captured (inbox row, conversation with "7:12 PM · source time / 7:13 PM · captured"); then the per-app language switched to zh-TW, zh-CN, ja-JP, ko-KR: bottom-bar labels in each language, capture page "since 下午7:14 / 19:15 / 19:16 / 오후 7:16", conversation times "下午7:12 · 來源時間 / 19:12 · 来源时间 / 19:12 · 送信元の時刻 / 오후 7:12 · 소스 시각", the Activity custom-period DateRangePicker in each language (選擇日期區間 / 选择日期范围 / 期間を選択 / 기간 선택, weekday names localised) and English in en-US (positive control); Settings page; no crash records.

## Review dimensions
1. **Store notes (five languages)**: ≤ 500 chars each; the five texts say the same things; the 0.1.1 audit clause is accurate against `CHANGELOG.md` 0.1.1 and does not over-claim (no "reply", "read", "all messages", nothing about content the source app did not post); terminology matches the app catalogues (`core/designsystem/src/main/res/values-*/strings.xml`: zh-Hant 金庫/來源/擷取, zh-Hans 保险库/来源/捕获, ja 保管庫/ソース/キャプチャ, ko 보관소/소스/캡처); natural in each language; Play-ready (no markdown, no line noise).
2. **CHANGELOG fold**: nothing lost or duplicated between `[Unreleased]` (now "Nothing yet.") and `[0.1.2]`; the lead line; the "Known issues in 0.1.1" block describes the two shipped bugs correctly (AndroidX `zh-r*` resources dropped by `localeFilters`; dates not following an Android 13+ per-app language while the process lives) — check them against the code and the round-18/21 reports.
3. **Docs not ahead of code**: `docs/SCOPE.md` + zh, `docs/RELEASE.md` + zh, `README.md`, `CLAUDE.md`, `docs/TEST_MATRIX.md` + zh — anything that now contradicts 0.1.2 as the shipping version? (The distribution row of SCOPE and the Play status lines in RELEASE.md are deliberately left for a docs commit *after* the upload lands, 537ad80 pattern; say so if you disagree.)
4. **Release mechanics** (read `.github/workflows/release.yml`, `docs/RELEASE.md`): tag `v0.1.2` → workflow builds the signed APK/AAB, permission gate, GitHub release with the CHANGELOG section as notes (`awk` extracts `## [0.1.2]` … next `## [`). Would the notes extraction work for this CHANGELOG? Any reason the workflow would fail on this commit?
5. **Operational Play plan (comment if wrong)**: with the `gplay` CLI (service account already reaches `dev.quietinbox.app`), one edit: `edits create` → `bundles upload` (the CI-built `quietinbox-0.1.2.aab`) → `sync import-listings --dir fastlane/metadata/android` (creates ja-JP/ko-KR/zh-CN, updates en-US/zh-TW full descriptions) → `images delete-all --type phoneScreenshots` for en-US and zh-TW (Play holds the seven 0.1.0 screenshots; phone max is 8) → `images sync --dir fastlane/metadata/android` (icon, featureGraphic, 7 phone screenshots for all five locales; en-US/zh-TW tenInch kept) → `tracks update --track production --releases @releases.json` (name 0.1.2, status completed, versionCodes ["6"], releaseNotes = the five texts) → `edits validate` → `edits commit` (sent for review). Anything missing (e.g. the 0.1.1 changelogs/5.txt now never uploaded is fine?), any ordering hazard?
6. Anything else that should stop the tag or the upload.

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- Critical (must fix before tag/upload) — itemized with file:line
- Important (should fix before tag/upload)
- Minor / nitpicks
- Other observations
Write the complete report to the output path named in your launcher prompt and print it to stdout.
