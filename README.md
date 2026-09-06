# QuietInbox／靜讀

> 使用者自主啟用、本機優先、對資料來源與缺口誠實的 Android 通知副本收件匣。
> A user-enabled, local-first Android inbox that keeps encrypted copies of messaging notifications and is honest about what it could not observe.

[English](#english) · [繁體中文](#繁體中文) · [Docs／文件](docs/) · [Privacy／隱私](PRIVACY.md) · [Security／安全](SECURITY.md)

[![CI](https://github.com/ImL1s/quietinbox/actions/workflows/ci.yml/badge.svg)](https://github.com/ImL1s/quietinbox/actions/workflows/ci.yml)
[![Release](https://github.com/ImL1s/quietinbox/actions/workflows/release.yml/badge.svg)](https://github.com/ImL1s/quietinbox/actions/workflows/release.yml)
[![Licence: GPL-3.0-or-later](https://img.shields.io/badge/licence-GPL--3.0--or--later-blue.svg)](LICENSE)

<p align="center">
  <img src="docs/screenshots/phone/en-US/1_inbox.png" width="180" alt="Inbox">
  <img src="docs/screenshots/phone/en-US/2_conversation.png" width="180" alt="Conversation">
  <img src="docs/screenshots/phone/en-US/4_activity.png" width="180" alt="Activity">
  <img src="docs/screenshots/phone/en-US/5_capture.png" width="180" alt="Capture health">
</p>

---

## 繁體中文

### 取得方式

| 管道 | 價格 | 說明 |
| --- | --- | --- |
| [Google Play](https://play.google.com/store/apps/details?id=dev.quietinbox.app) | 付費（一次性） | 自動更新、支持開發；功能與開源版完全相同 |
| [GitHub Releases](https://github.com/ImL1s/quietinbox/releases) | 免費（GPL-3.0-or-later） | 直接安裝 APK，附 SHA-256 |

兩邊套件名相同但簽章不同（Play 由 Google Play App Signing 重新簽章），因此無法互相覆蓋安裝，請擇一使用。沒有訂閱、沒有內購、沒有廣告、沒有功能鎖，理由見 [ADR-0006](docs/adr/0006-distribution-and-monetisation.md)。

### 這是什麼

靜讀讀取你選擇的通訊 App 發到通知列的內容，把副本存進**本機加密資料庫**，讓你之後不用打開聊天室也能讀，對方也不會看到已讀。它：

- **不連網**：APK 沒有 `INTERNET` 權限、沒有分析、沒有崩潰回報；`tools/check-permissions.sh` 在 CI 與每次發布時檢查。
- **不動來源**：只讀通知副本；不呼叫 `contentIntent`／`deleteIntent`／`RemoteInput`，不取消來源通知，不觸發已讀。開啟來源 App 是明確的使用者操作，且事先告知可能觸發已讀。
- **誠實標示資料品質**：擷取健康、內容品質、身分可靠度、媒體結果四個維度分開顯示，永遠有文字＋圖示，不只靠顏色。
- **不假裝知道真相**：沒有 ID 與時間戳的相同單則訊息標為「可能重複」（`AMBIGUOUS_REPEAT`），不硬算成兩則，也不悄悄丟掉；漏掉的訊息數永遠標「未知」。

### 功能

| 區域 | 內容 |
| --- | --- |
| Onboarding | 範圍說明 → 選擇來源 → 通知存取授權（含 Android 13+ 受限制設定指引）→ 合成測試通知 → 標籤說明 |
| 收件匣 | 來源篩選、釘選／封存、本機已查看、身分與重複標籤、健康橫幅、刪除（含防重播 suppression） |
| 對話 | 氣泡、發送者、來源時間 vs 擷取時間、修訂／觀測次數、媒體結果、選取刪除、開啟來源 App（需確認） |
| 搜尋 | 加密 n-gram 索引：繁中子字串、ASCII 詞／3-gram、日期與來源篩選、參數化＋分頁 |
| 活動洞察 | 只計已觀測資料：概觀（樣本、時段長條、週×時熱力圖、Emoji 與口頭禪）、排行榜（全部／平日／週末）、最佳時段、好聊度、神隱率；期間 7 天／本月／上月／3 個月／全部／自訂，全部免費 |
| 擷取健康 | 連線狀態、暫停、管線計數、來源啟用／暫停／移除、中斷區間、無正文診斷摘要 |
| 設定 | 主題／動態色彩／減少動畫、App 鎖、禁止截圖、保存期限、媒體複製揭示、自己的提醒、復原金鑰、加密備份匯出／還原、刪除所有資料、已知限制、授權 |
| Demo 模式（僅 debug） | 一鍵載入完全虛構的示範資料，用來截圖與走過所有功能而不暴露真實通知；`tools/demo-screenshots.sh` 自動截圖 |

### 架構

```
:app                       Nav3 + list-detail、Hilt、WorkManager、提醒、UI 鎖
:core:model                純 Kotlin 資料契約（NotificationSnapshot / ParsedBatch / …）
:core:parser               Parser SPI、ParserRegistry、StandardParser（MessagingStyle / Inbox / BigText / 摘要）
:core:identity             會話身分：chat id > shortcut > 通知串流 > 標題（永不跨串流合併）
:core:reconcile            有界視窗對齊去重、AMBIGUOUS_REPEAT、revision、checkpoint
:core:analytics            描述統計與洞察（熱力圖、排行、時段、好聊度、神隱率、口頭禪）
:core:testing              合成 fixture DSL
:parsers:apps              LINE / WhatsApp / Telegram / Instagram / Messenger adapter（SYNTHETIC_ONLY）
:platform:crypto           Keystore 包裝的每安裝隨機 key、Tink AEAD、復原金鑰編碼
:platform:storage          Room + SQLCipher、DataStore 設定、retention worker、demo 資料
:platform:capture          NotificationListenerService → 有界 snapshot → 佇列 → journal → parse → identity → reconcile → commit
:platform:media            content:// 與通知 bitmap 的限額加密複製
:platform:backup           Tink Streaming AEAD 容器、manifest／EOF／計數驗證、原子合併還原
:core:designsystem         Material 3 Expressive 主題、共用元件、zh-Hant + en 字串
:feature:*                 onboarding / inbox / conversation / search / health / settings / analytics
```

管線：`來源通知 → 白名單 → 不可變 snapshot（有上限）→ 有界佇列 → 加密 journal（此後才算 accepted）→ parser → 身分 → 對帳 → 單一交易投影 → Flow → UI`。撤權、暫停、來源變更與維護都是圍籬：每個事件在等鎖前、鎖內與提交前各檢查一次；來源清單未知前通知原封保留不讀；重設或還原是 `VaultMaintenance` 後面的獨佔維護執行（[ADR-0007](docs/adr/0007-maintenance-gate-and-fail-closed-capture.md)）。

### 建置與驗證

需求：JDK 17、Android SDK（compileSdk 37、build-tools 36）；Gradle wrapper 會自動下載 9.7.1。

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew test :app:assembleDebug                                   # 全部 JVM 測試 + debug APK
./gradlew :platform:storage:connectedDebugAndroidTest \
          :platform:crypto:connectedDebugAndroidTest                # SQLCipher、migration、金鑰 fsync（需裝置）
tools/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk
```

手動驗證擷取（不需第二支 App）：

```bash
adb shell cmd notification allow_listener <applicationId>/dev.quietinbox.platform.capture.QuietInboxListenerService
adb shell cmd notification post -S messaging -t "Alice" tag "hello from shell"
```

`cmd notification post` 的來源套件是 `com.android.shell`；在「擷取 → 新增來源」輸入該套件名即可用通用解析器擷取。截圖用的 demo 資料：`adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op seed -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver`（僅 debug 版）。

### 文件

- [docs/SCOPE.md](docs/SCOPE.md) — 完成定義與尚未完成清單（誠實的 gap list）
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（[中文](docs/zh-Hant/ARCHITECTURE.md)） — 模組與資料流
- [docs/adr/](docs/adr/)（[中文](docs/zh-Hant/adr/)） — 架構決策紀錄
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)（[中文](docs/zh-Hant/COMPATIBILITY.md)） — 來源相容矩陣
- [docs/TEST_MATRIX.md](docs/TEST_MATRIX.md)（[中文](docs/zh-Hant/TEST_MATRIX.md)） — 測試層與現況
- [docs/RELEASE.md](docs/RELEASE.md)（[中文](docs/zh-Hant/RELEASE.md)） — 發布流程
- [docs/reviews/](docs/reviews/) — 每一輪獨立審查的原始報告
- [PRIVACY.md](PRIVACY.md)（[中文](docs/zh-Hant/PRIVACY.md)） · [SECURITY.md](SECURITY.md)（[中文](docs/zh-Hant/SECURITY.md)） · [CONTRIBUTING.md](CONTRIBUTING.md)（[中文](docs/zh-Hant/CONTRIBUTING.md)） · [CHANGELOG.md](CHANGELOG.md)

### 授權

原創程式碼採 [GPL-3.0-or-later](LICENSE)。第三方元件保留各自授權（見 [NOTICE](NOTICE) 與 App 內「開源授權」）。本專案不包含任何競品 APK、反編譯原始碼、解密資產或詞典。

---

## English

### Get it

| Channel | Price | Notes |
| --- | --- | --- |
| [Google Play](https://play.google.com/store/apps/details?id=dev.quietinbox.app) | paid, one-time | auto-updates and supports development; identical features |
| [GitHub Releases](https://github.com/ImL1s/quietinbox/releases) | free (GPL-3.0-or-later) | install the APK directly; SHA-256 checksums attached |

Both use the same package name but different signatures (Play re-signs with Google Play App Signing), so one cannot update over the other: pick one. No subscription, no in-app purchases, no ads, no locked features; the reasoning is in [ADR-0006](docs/adr/0006-distribution-and-monetisation.md).

### What it is

QuietInbox reads what the messaging apps you explicitly enable post to the notification shade and keeps a copy in an **encrypted on-device database**, so you can read it later without opening the chat and without the other side ever seeing a read receipt. It is:

- **Offline by construction**: no `INTERNET` permission, no analytics, no crash reporting; `tools/check-permissions.sh` fails CI and every release if a network permission appears.
- **Read-only towards the source**: it never fires `contentIntent`/`deleteIntent`/`RemoteInput`, never cancels a source notification, never marks anything read. Opening the source app is an explicit, confirmed user action with a warning that it may trigger read receipts.
- **Honest about quality**: capture health, content quality, identity reliability and media result are four separate, labelled dimensions, always text + icon, never colour alone.
- **Free of invented truth**: an identical id-less, timestamp-less message is stored as a "possible repeat" (`AMBIGUOUS_REPEAT`), never silently dropped and never counted as a confirmed second message; missing counts are always "unknown".

### Features

| Area | What you get |
| --- | --- |
| Onboarding | scope → sources → notification access (with Android 13+ restricted-settings guidance) → synthetic test notification → label legend |
| Inbox | source filters, pin/archive, local "viewed", identity and repeat labels, health banner, delete with anti-replay suppression |
| Conversation | bubbles, sender, source time vs capture time, revision/observation counts, media result, select-to-delete, open source app (confirmed) |
| Search | encrypted n-gram index: CJK substrings, Latin words/3-grams, date and source filters, parameterised and paged |
| Activity insights | observed data only: overview (sample, by-hour bars, weekday×hour heat map, emoji and catchphrases), rankings (all/weekdays/weekends), best time, chattiness, quiet rate; periods 7 days/this month/last month/3 months/all/custom, all free |
| Capture health | connection state, pause, pipeline counters, enable/pause/remove sources, gap intervals, body-free diagnostics |
| Settings | theme/dynamic colour/reduced motion, app lock, screenshot blocking, retention, media-copy disclosure, own reminders, recovery key, encrypted backup export/restore, delete everything, limitations, licences |
| Demo mode (debug only) | one tap loads fully fictional data to screenshot and exercise every feature without exposing real notifications; `tools/demo-screenshots.sh` captures every screen |

### Architecture

See the module table in the Chinese section above; the pipeline is `source notification → allow-list → immutable bounded snapshot → bounded queue → encrypted journal (only now "accepted") → parser → identity → reconcile → single-transaction projection → Flow → UI`. Revoke, pause, source changes and maintenance are fences that every event re-checks before the lock, inside it and before the commit; before the source list is known a notification is held unread; a reset or restore is an exclusive maintenance run behind `VaultMaintenance` ([ADR-0007](docs/adr/0007-maintenance-gate-and-fail-closed-capture.md)). Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), decisions: [docs/adr/](docs/adr/).

### Build and verify

Requirements: JDK 17, Android SDK (compileSdk 37, build-tools 36); the Gradle wrapper downloads 9.7.1.

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew test :app:assembleDebug
./gradlew :platform:storage:connectedDebugAndroidTest :platform:crypto:connectedDebugAndroidTest   # device required
tools/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk
```

Manual capture check without a second app: grant the listener with `adb shell cmd notification allow_listener <applicationId>/dev.quietinbox.platform.capture.QuietInboxListenerService`, then `adb shell cmd notification post -S messaging -t "Alice" tag "hello from shell"`; add `com.android.shell` as a source by package name. Demo data for screenshots (debug builds only): `adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op seed -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver`.

### Documentation

[docs/SCOPE.md](docs/SCOPE.md) (what is done and what is not) · [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/adr/](docs/adr/) · [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) · [docs/TEST_MATRIX.md](docs/TEST_MATRIX.md) · [docs/RELEASE.md](docs/RELEASE.md) · [docs/reviews/](docs/reviews/) (verbatim independent review reports) · [PRIVACY.md](PRIVACY.md) · [SECURITY.md](SECURITY.md) · [CONTRIBUTING.md](CONTRIBUTING.md) · [CHANGELOG.md](CHANGELOG.md). Traditional Chinese versions live under [docs/zh-Hant/](docs/zh-Hant/).

### Licence

Original code is [GPL-3.0-or-later](LICENSE). Third-party components keep their own licences (see [NOTICE](NOTICE)). The repository contains no competitor APKs, decompiled sources, decrypted assets or dictionaries.
