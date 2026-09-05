# 發布流程

> English: [../RELEASE.md](../RELEASE.md)

靜讀在兩個管道發布**同一個二進位檔**（見 ADR-0006）：

| 管道 | 價格 | 簽章 | 方式 |
| --- | --- | --- | --- |
| GitHub Releases | 免費（GPL-3.0-or-later） | 專案 upload key | 打 `v*` tag 觸發 `release.yml` |
| Google Play（`dev.quietinbox.app`） | 付費、一次性 | Google Play App Signing（upload key 同上） | `release.yml` → internal 軌道；production 由 `workflow_dispatch` 或 `gplay` 推進 |

Play 會重新簽章商店版本，所以兩種安裝無法互相更新；使用者擇一即可。

## 一次性設定（本 repo 已完成）

- Upload keystore 產生在 repo 之外（`~/.android/keystores/quietinbox-upload.jks`，RSA-4096，alias `quietinbox-upload`，
  SHA-256 `A8:2B:DD:BE:0B:E7:87:5E:06:7C:02:90:14:8B:58:46:DA:E6:DE:FB:B0:67:A5:B4:C8:CF:B0:B9:80:BE:DF:29`）。
  `keystore.properties`（已 gitignore）讓 Gradle 的 `release` signing config 指向它。
- GitHub Actions secrets：`QUIETINBOX_KEYSTORE_BASE64`、`QUIETINBOX_KEYSTORE_PASSWORD`、
  `QUIETINBOX_KEY_ALIAS`、`QUIETINBOX_KEY_PASSWORD`、`PLAY_SERVICE_ACCOUNT_JSON`。
- Play Console 已建立付費 App；商店文案與圖片放在 `fastlane/metadata/android/`。
- 隱私權政策：<https://iml1s.github.io/quietinbox-privacy.html>。

## 發布一個版本

1. 在 `app/build.gradle.kts` 提高 `versionCode` / `versionName`；在 `CHANGELOG.md` 新增版本段落，並新增
   `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`（≤ 500 字元）與 `fastlane/whatsnew/whatsnew-<locale>`。
2. 本機跑閘門：`./gradlew test :app:assembleRelease && tools/check-permissions.sh app/build/outputs/apk/release/app-release.apk`，
   把 release APK 裝到裝置上，走過 changelog 裡每一條使用者可見的流程。
3. 獨立審查（名單見 `docs/reviews/README.md`）；修正；再審。
4. `git tag vX.Y.Z && git push --tags`。workflow 會建置、跑權限閘門、發布附 `SHA256SUMS.txt` 的 GitHub release，
   並把 AAB 上傳到 Play 的 **internal** 軌道。
5. 推進到 production：*Actions → Release → Run workflow* 並選 `track=production`，或
   `gplay promote --package dev.quietinbox.app --from internal --to production`。

## 截圖

`tools/demo-screenshots.sh <serial> <locale> <out-dir>` 會把完全虛構的示範資料載入 debug 版並逐頁截圖；
商店用的副本放在 `fastlane/metadata/android/<locale>/images/`，參考用的副本放在 `docs/screenshots/`。
