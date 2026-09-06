# Release process

> 繁體中文：[docs/zh-Hant/RELEASE.md](zh-Hant/RELEASE.md)

QuietInbox ships the **same binary** in two places (see ADR-0006):

| Channel | Price | Signing | How |
| --- | --- | --- | --- |
| GitHub Releases | free (GPL-3.0-or-later) | project upload key | `release.yml` on a `v*` tag |
| Google Play (`dev.quietinbox.app`) | paid, one-time | Google Play App Signing (same upload key) | `workflow_dispatch` of `release.yml` with `track=internal` or `production`, or `gplay` from a maintainer machine; a tag alone never touches Play |

The two installs cannot update over each other because Play re-signs the store copy; users pick one.

## One-time setup (done for this repository)

- Upload keystore generated outside the repo (`~/.android/keystores/quietinbox-upload.jks`, RSA-4096,
  alias `quietinbox-upload`, SHA-256 `A8:2B:DD:BE:0B:E7:87:5E:06:7C:02:90:14:8B:58:46:DA:E6:DE:FB:B0:67:A5:B4:C8:CF:B0:B9:80:BE:DF:29`).
  `keystore.properties` (gitignored) points the Gradle `release` signing config at it.
- GitHub Actions secrets: `QUIETINBOX_KEYSTORE_BASE64`, `QUIETINBOX_KEYSTORE_PASSWORD`,
  `QUIETINBOX_KEY_ALIAS`, `QUIETINBOX_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`.
- Play Console app created (paid), listing texts and graphics live in `fastlane/metadata/android/` (en-US, zh-TW, zh-CN, ja-JP, ko-KR). The release workflow uploads only the what's-new texts; listing texts and images are synced from those files with `gplay` (step 5), which also creates a new listing language.
- Privacy policy: <https://iml1s.github.io/quietinbox-privacy.html>.

## Cutting a release

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`; add the version section to
   `CHANGELOG.md` and a `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`
   (≤ 500 chars) plus `fastlane/whatsnew/whatsnew-<locale>`.
2. Run the gate locally: `./gradlew test :app:assembleRelease && tools/check-permissions.sh app/build/outputs/apk/release/app-release.apk`,
   install the release APK on a device and walk every user-facing flow in the changelog.
3. Independent review (`docs/reviews/README.md` roster); fix; re-review.
4. `git tag vX.Y.Z && git push --tags`. The workflow builds, gates and publishes the GitHub release
   with `SHA256SUMS.txt`. It does not touch Google Play.
5. Google Play (deliberate): *Actions → Release → Run workflow* with the tag and `track=internal`
   or `track=production` (bundle + what's-new only); or, as done for 0.1.2, one `gplay` edit from a
   maintainer machine with the CI-built AAB (`gh run download <release run> -n release-<version>`):
   `edits create` → `bundles upload --file dist/quietinbox-<version>.aab` → `sync import-listings --dir fastlane/metadata/android`
   → `images plan --dir fastlane/metadata/android` (and `images delete-all --type phoneScreenshots --confirm` for a locale whose
   screenshots are being replaced: Play keeps at most 8) → `images sync --dir fastlane/metadata/android` →
   `tracks update --track production --releases @releases.json` (`status: completed`, `versionCodes`, `releaseNotes` = `fastlane/release-notes.json`)
   → `edits validate` → `edits commit`. Uploading the CI artifact keeps the Play copy and the GitHub copy byte-identical;
   a locally built AAB is signed with the same key but may differ in bytes (no reproducible-build comparison yet).

## Screenshots

`tools/demo-screenshots.sh <serial> <locale> <out-dir>` seeds the debug build with fully synthetic
demo data and captures every screen; store copies live under
`fastlane/metadata/android/<locale>/images/` and reference copies under `docs/screenshots/`.

## Dependency verification

`gradle/verification-metadata.xml` pins a sha256 for every resolved artifact and CI fails on anything
unlisted. After changing dependencies, regenerate it from a **cold** cache so the parent/BOM poms and
modules that Linux CI resolves are recorded too (a warm cache skips them):

```sh
GRADLE_USER_HOME=/tmp/gradle-cold JAVA_HOME=<jdk17> ./gradlew --no-daemon \
  --write-verification-metadata sha256 --dry-run \
  test :app:assembleDebug :app:assembleRelease :app:bundleRelease :app:lintDebug \
  :platform:storage:assembleDebugAndroidTest :platform:crypto:assembleDebugAndroidTest
```

Diff `gradle/verification-metadata.dryrun.xml` against the checked-in file, then adopt it. `aapt2` is
trusted by name because each host OS resolves a different jar.
