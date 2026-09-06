# SANITIZED REVIEW BRIEF — round 26
DO NOT activate any orchestration workflow mode. READ-ONLY review only. No code changes.

## Repository
`/Users/iml1s/Documents/mine/quietinbox` — QuietInbox / 靜讀, an Android app (Kotlin, Compose,
Room/SQLCipher, Hilt) that keeps on-device encrypted copies of messaging notifications.
Public repo `ImL1s/quietinbox`, GPL-3.0-or-later. Released as 0.1.2 / versionCode 6 on GitHub;
Google Play currently serves 0.1.0 with 0.1.2 in review.

## What to review
The single commit `78e7487` on `main`. Run:

```
git -C /Users/iml1s/Documents/mine/quietinbox log -1 --stat 78e7487
git -C /Users/iml1s/Documents/mine/quietinbox diff 78e7487~1..78e7487 -- tools/demo-screenshots.sh
git -C /Users/iml1s/Documents/mine/quietinbox diff 78e7487~1..78e7487 -- '*.md' '*.yml' '*.toml'
```

## Background — the defect this commit fixes
`tools/demo-screenshots.sh` captures the Google Play store screenshots from a synthetic demo vault.
Its `tap-tab` helper only matched navigation items in the **bottom 15% of the screen**. On a tablet
the navigation is a **left rail**, so on the foldable AVD every tab tap matched nothing; the failure
only produced a `warn`, the run carried on, the device ended up on the launcher, and the 80 KB size
floor passed 3.3 MB of wallpaper. Result: **10 of the 14 tablet screenshots that were committed to
the repository and uploaded to Google Play were the Pixel launcher home screen or the Android system
Settings app**, not QuietInbox. They are live on the store listing right now.

## Review dimensions — cover all
1. **Root cause**: does the change actually fix why the tablet run produced launcher screenshots, or
   only the symptom? Read the new `tap-tab`, `app-foreground`, `conversation-ready` and `shot()`.
2. **Regression risk on the phone path.** The narrow layout is the one that produces the five phone
   locales already live on the store. Look hard at: the `LAYOUT` variable being referenced by
   `tap_tab` (defined later in the file, under `set -u`), the new `sys.argv` offsets in the python
   helper, the rail rule being gated on `wide`, and the `warn`→`die` conversions — could any of them
   fail a run that used to succeed, or silently change which node is tapped?
3. **Are the guards sufficient?** `app-foreground` only asserts that one node belongs to the app.
   Name any screen the harness could still capture wrongly and pass every guard.
4. **Do the 14 new PNGs show the app?** `docs/screenshots/tablet/{en-US,zh-TW}/*.png` (2076x2152).
   Inspect them; they must be QuietInbox screens, in the locale of their directory, and byte-identical
   to `fastlane/metadata/android/{en-US,zh-TW}/images/tenInchScreenshots/`.
5. **Documentation accuracy — the project's standing rule is that docs must never run ahead of code.**
   Every changed claim must be true of the tree at `78e7487`. Check especially: the four corrected test
   counts in `docs/SCOPE.md` and `docs/zh-Hant/SCOPE.md` against the actual test files and
   `docs/TEST_MATRIX.md`; the claim that release signing is done; the claim about the published
   package id; the new `CONTRIBUTING.md` command against `.github/workflows/ci.yml`; the new PR
   template items; the new English module table in `README.md` against `settings.gradle.kts`.
6. **English / Traditional Chinese parity.** Both languages must say the same thing. The Chinese and
   English halves of README, `docs/*.md` vs `docs/zh-Hant/*.md`, the two review indexes.
7. Anything else that is wrong.

## Output format — 繁體中文
- **Verdict**: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- **Critical** (must fix before this is pushed) — itemised, each with file:line
- **Important** (should fix before push)
- **Minor / nitpicks**
- **Other observations**

Cite file:line for every finding. A finding with no citation will be discarded.
