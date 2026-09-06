# SANITIZED REVIEW BRIEF — round 28 (mini re-review)
DO NOT activate any orchestration workflow mode. READ-ONLY review only. No code changes.

## Repository
`/Users/iml1s/Documents/mine/quietinbox` — QuietInbox / 靜讀. This is the last gate before the
`v0.1.3` tag (versionCode 7), the GitHub release and the Google Play upload.

## What to review
`git -C /Users/iml1s/Documents/mine/quietinbox diff b9b49cc..fd99784`, the fixes for round 27.
Round 27's reports are archived verbatim in `docs/reviews/2026-09-06-round27/` — read them first and
check each finding against the diff, saying per finding whether it is fixed, partly fixed, or not
addressed.

## Review dimensions
1. **The navigation rule.** Round 27's Important-1 was that `box[0] <= K and box[2] <= K` is a
   tautology, so the previous "fix" changed nothing while the docs claimed it did. The rule is now
   "among candidates in the navigation strip, take the one furthest into it — lowest on a bottom bar,
   leftmost on a rail". Is that actually different from taking the first match? Construct a dump
   where the new rule picks a different node than the old one, and one where it still picks wrongly.
2. **`tab-selected` scoping.** It now requires the `selected` node to belong to the app AND to be
   inside the navigation strip. Can a notification shade, a dialog, or an in-app selected chip still
   satisfy it? Can a legitimate navigation item fail it (e.g. if Compose marks a node outside the
   strip)?
3. **`tap_tab` re-tapping.** The tap is now re-sent on every one of five attempts. Is a repeated tap
   on a navigation item safe (idempotent), or can it toggle, double-navigate or open something?
4. **`inbox_summary_join` used for the gap clause.** Check `InboxScreen.kt` and all five catalogues.
   Does English still read correctly with the space, and Chinese/Japanese without it? Look at
   `docs/screenshots/phone/{zh-TW,zh-CN,ja-JP}/1_inbox.png` and
   `docs/screenshots/tablet/zh-TW/1_inbox.png` and confirm.
5. **The 49 screenshots** must each be a QuietInbox screen in their directory's language, and
   `docs/screenshots/**` byte-identical to `fastlane/metadata/android/**`.
6. **Release readiness.** `versionCode` 7 / `versionName` 0.1.3, the CHANGELOG section and its date,
   the five `changelogs/7.txt` (≤ 500 chars) identical to `fastlane/whatsnew/*` and
   `fastlane/release-notes.json`, the `release.yml` change that adds the R8 mapping to `dist/` and
   the gzip to the release assets (does the glob still match the APK? is the mapping path right for
   AGP 9?), and `docs/RELEASE.md` step 6 in both languages.
7. **Docs never run ahead of code.** The tag, the GitHub release and the Play upload have NOT
   happened yet at `fd99784`. Flag any sentence that assumes they have. The review index rows 26 and
   27 carry a `pending` fix-commit cell on purpose.
8. English / Traditional Chinese parity for everything this commit touches.

## Output format — 繁體中文
- **Verdict**: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- **Critical** (must fix before the tag) — each with file:line
- **Important** (should fix before the tag)
- **Minor / nitpicks**
- **Other observations**

Cite file:line for every finding.
