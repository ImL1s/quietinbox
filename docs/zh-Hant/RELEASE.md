# 發布流程

> English: [../RELEASE.md](../RELEASE.md)

靜讀在兩個管道發布**同一個二進位檔**（見 ADR-0006）：

| 管道 | 價格 | 簽章 | 方式 |
| --- | --- | --- | --- |
| GitHub Releases | 免費（GPL-3.0-or-later） | 專案 upload key | 打 `v*` tag 觸發 `release.yml` |
| Google Play（`dev.quietinbox.app`） | 付費、一次性 | Google Play App Signing（upload key 同上） | 以 `workflow_dispatch` 執行 `release.yml`（`track=internal` 或 `production`），或在維護者機器用 `gplay`；只打 tag 不會動到 Play |

Play 會重新簽章商店版本，所以兩種安裝無法互相更新；使用者擇一即可。

## 一次性設定（本 repo 已完成）

- Upload keystore 產生在 repo 之外（`~/.android/keystores/quietinbox-upload.jks`，RSA-4096，alias `quietinbox-upload`，
  SHA-256 `A8:2B:DD:BE:0B:E7:87:5E:06:7C:02:90:14:8B:58:46:DA:E6:DE:FB:B0:67:A5:B4:C8:CF:B0:B9:80:BE:DF:29`）。
  `keystore.properties`（已 gitignore）讓 Gradle 的 `release` signing config 指向它。
- GitHub Actions secrets：`QUIETINBOX_KEYSTORE_BASE64`、`QUIETINBOX_KEYSTORE_PASSWORD`、
  `QUIETINBOX_KEY_ALIAS`、`QUIETINBOX_KEY_PASSWORD`、`PLAY_SERVICE_ACCOUNT_JSON`。
- Play Console 已建立付費 App；商店文案與圖片放在 `fastlane/metadata/android/`（en-US、zh-TW、zh-CN、ja-JP、ko-KR）。release workflow 只上傳 what's-new；商店文案與圖片用 `gplay` 從這些檔案同步（步驟 5），新的商店語言也是這樣建立。
- 隱私權政策：<https://iml1s.github.io/quietinbox-privacy.html>。

## 發布一個版本

1. 在 `app/build.gradle.kts` 提高 `versionCode` / `versionName`；在 `CHANGELOG.md` 新增版本段落，並新增
   `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`（≤ 500 字元）與 `fastlane/whatsnew/whatsnew-<locale>`。
2. 本機跑閘門：`./gradlew test :app:assembleRelease && tools/check-permissions.sh app/build/outputs/apk/release/app-release.apk`，
   把 release APK 裝到裝置上，走過 changelog 裡每一條使用者可見的流程。
3. 獨立審查（名單見 `docs/reviews/README.md`）；修正；再審。
4. `git tag vX.Y.Z && git push --tags`。workflow 會建置、跑權限閘門、發布附 `SHA256SUMS.txt` 的 GitHub release；
   它不會碰 Google Play。
5. Google Play（刻意觸發）：*Actions → Release → Run workflow* 指定 tag 與 `track=internal` 或 `track=production`（只上傳 bundle 與 what's-new）；或像 0.1.2 那樣，在維護者機器用 CI 建好的 AAB（`gh run download <release run> -n release-<version>`）走一個 `gplay` edit：
   `edits create` → `bundles upload --file dist/quietinbox-<version>.aab` → `sync import-listings --dir fastlane/metadata/android`
   → `images plan --dir fastlane/metadata/android`（要換掉某語系的截圖時先 `images delete-all --type phoneScreenshots --confirm`：Play 最多留 8 張）→ `images sync --dir fastlane/metadata/android`
   → `tracks update --track production --releases @releases.json`（`status: completed`、`versionCodes`、`releaseNotes` = `fastlane/release-notes.json`）→ `edits validate` → `edits commit`。
   上傳 CI 產物可讓 Play 與 GitHub 的副本位元組相同；本機建的 AAB 用同一把金鑰簽章，但位元組可能不同（尚無可重現建置比對）。
   平板截圖有換時，`--type tenInchScreenshots` 也要一起刪。
6. 上傳 R8 mapping，讓 Play Vitals 的堆疊看得懂：在同一個 edit 裡、`edits commit` 之前執行
   `gplay deobfuscation upload --package dev.quietinbox.app --edit <edit> --version-code <n> --file dist/quietinbox-<version>-mapping.txt`。
   一定要用 CI 產物裡的 mapping——本機重建的那份對不上已上傳的 bundle。release 另外附一份 `…-mapping.txt.gz`。

## 截圖

`tools/demo-screenshots.sh <serial> <locale> <out-dir>` 會把完全虛構的示範資料載入 debug 版並逐頁截圖；
商店用的副本放在 `fastlane/metadata/android/<locale>/images/`，參考用的副本放在 `docs/screenshots/`。

## 相依套件驗證

`gradle/verification-metadata.xml` 為每個解析到的 artifact 固定 sha256，CI 遇到未列出的就會失敗。
變更相依套件後，請用**冷**快取重新產生，這樣 Linux CI 會解析到的 parent / BOM pom 與 module 才會被記錄（熱快取會略過它們）：

```sh
GRADLE_USER_HOME=/tmp/gradle-cold JAVA_HOME=<jdk17> ./gradlew --no-daemon \
  --write-verification-metadata sha256 --dry-run \
  test :app:assembleDebug :app:assembleRelease :app:bundleRelease :app:lintDebug \
  :platform:storage:assembleDebugAndroidTest :platform:crypto:assembleDebugAndroidTest
```

把 `gradle/verification-metadata.dryrun.xml` 與已提交的檔案比對後採用。`aapt2` 以名稱信任，因為每種主機 OS 解析到的 jar 不同。
