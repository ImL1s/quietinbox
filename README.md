# QuietInbox／靜讀

> 使用者自主啟用、本機優先、可驗證資料來源與缺口的 Android 通知副本收件匣。
> A user-enabled, local-first Android inbox that keeps copies of messaging notifications and is honest about what it could not observe.

[English below](#english)

**狀態：v0.1（可安裝的垂直切片）。** 尚未發布到任何商店；來源 adapter 目前只以合成 fixture 測試（`SYNTHETIC_ONLY`）。名稱、套件名與商標均為暫定，尚未查重。

## 這是什麼

QuietInbox 讀取你選擇的通訊 App 發到通知列的內容，把副本存進**本機加密資料庫**，讓你之後不用打開聊天室也能讀。它：

- **不連網**：APK 沒有 `INTERNET` 權限、沒有分析、沒有崩潰回報（`tools/check-permissions.sh` 在 CI 檢查）。
- **不動來源**：只讀通知副本；不呼叫 `contentIntent`／`deleteIntent`／`RemoteInput`，不取消來源通知，不觸發已讀。開啟來源 App 是明確的使用者操作，且事先告知可能觸發已讀。
- **誠實標示資料品質**：擷取健康（已連線／未授權／暫停／中斷區間）、內容品質（結構化／僅摘要／預覽受限）、身分可靠度（已驗證 ID／由串流推定／不明）、媒體結果（本機副本／失效／權限不足…）四個維度分開顯示，永遠有文字＋圖示，不只靠顏色。
- **不假裝知道真相**：沒有 ID 與時間戳的相同單則訊息標為「可能重複」（`AMBIGUOUS_REPEAT`），不硬算成兩則，也不悄悄丟掉；漏掉的訊息數永遠標「未知」。

## 功能（v0.1）

| 區域 | 內容 |
| --- | --- |
| Onboarding | 範圍說明 → 選擇來源 → 通知存取授權（含 Android 13+ 受限制設定指引）→ 合成測試通知 → 標籤說明 |
| 收件匣 | 來源篩選、釘選／封存、本機已查看、身分與重複標籤、健康橫幅、刪除（含防重播 suppression） |
| 對話 | 氣泡、發送者、來源時間 vs 擷取時間、修訂／觀測次數、媒體結果、選取刪除、開啟來源 App（需確認） |
| 搜尋 | 加密 n-gram 索引：繁中子字串、ASCII 詞／3-gram、日期與來源篩選、參數化＋分頁 |
| 活動統計 | 只計已觀測資料：樣本數、歧義數、僅摘要數、時區、時段／日期長條圖、會話與發送者排名、Emoji |
| 擷取健康 | 連線狀態、暫停、管線計數、來源啟用／暫停／移除（保留或刪除副本分開問）、中斷區間、無正文診斷摘要 |
| 設定 | 主題／動態色彩／減少動畫、App 鎖（UI 閘門）、禁止截圖、保存期限、媒體複製揭示、自己的提醒（預設關）、復原金鑰、加密備份匯出／還原、刪除所有資料、已知限制、授權 |

## 架構

```
:app                       Nav3 + list-detail、Hilt、WorkManager、提醒、UI 鎖
:core:model                純 Kotlin 資料契約（NotificationSnapshot / ParsedBatch / …）
:core:parser               Parser SPI、ParserRegistry、StandardParser（MessagingStyle / Inbox / BigText / 摘要）
:core:identity             會話身分：chat id > shortcut > 通知串流 > 標題（永不跨串流合併）
:core:reconcile            有界視窗對齊去重、AMBIGUOUS_REPEAT、revision、checkpoint
:core:analytics            描述統計（不算回覆率／已讀率／收回率）
:core:testing              合成 fixture DSL
:parsers:apps              LINE / WhatsApp / Telegram / Instagram / Messenger adapter（SYNTHETIC_ONLY）
:platform:crypto           Keystore 包裝的每安裝隨機 key、Tink AEAD、復原金鑰編碼
:platform:storage          Room + SQLCipher（單一加密庫含索引與 checkpoint）、DataStore 設定、retention worker
:platform:capture          NotificationListenerService → 有界 snapshot → 佇列 → journal → parse → identity → reconcile → commit
:platform:media            content:// 與通知 bitmap 的限額加密複製
:platform:backup           Tink Streaming AEAD 容器、manifest／EOF／計數驗證、原子合併還原
:core:designsystem         Material 3 Expressive 主題、共用元件、zh-Hant + en 字串
:feature:*                 onboarding / inbox / conversation / search / health / settings / analytics
```

管線（計劃 §5）：`來源通知 → 白名單 → 不可變 snapshot（allow-list、有上限）→ 有界佇列 → 加密 journal（此後才算 accepted）→ parser → 身分 → 對帳 → 單一交易投影 → Flow → UI`。撤權／暫停切換 generation token，排隊中的事件在提交前再檢查（commit fence）。

## 建置

需求：JDK 17、Android SDK（compileSdk 37、build-tools 36）、Gradle wrapper 會自動下載 9.7.1。

```bash
./gradlew :app:assembleDebug
./gradlew :core:model:test :core:parser:test :core:identity:test :core:reconcile:test :core:analytics:test :parsers:apps:test
./gradlew :platform:crypto:testDebugUnitTest :platform:backup:testDebugUnitTest
./gradlew :platform:storage:connectedDebugAndroidTest    # 需要裝置／模擬器（SQLCipher native）
tools/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk
```

手動驗證擷取（不需第二支 App）：

```bash
adb shell cmd notification allow_listener <applicationId>/dev.quietinbox.platform.capture.QuietInboxListenerService
adb shell cmd notification post -S messaging -t "Alice" tag "hello from shell"
```

`cmd notification post` 的來源套件是 `com.android.shell`；在「擷取 → 新增來源」輸入該套件名即可用通用解析器擷取。

## 文件

- [docs/SCOPE.md](docs/SCOPE.md) — 完成定義與尚未完成清單（誠實的 gap list）
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 模組與資料流
- [docs/adr/](docs/adr/) — 架構決策紀錄
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) — 來源相容矩陣（目前全部 `SYNTHETIC_ONLY`）
- [docs/TEST_MATRIX.md](docs/TEST_MATRIX.md) — 測試層與現況
- [PRIVACY.md](PRIVACY.md) · [SECURITY.md](SECURITY.md) · [CONTRIBUTING.md](CONTRIBUTING.md) · [CHANGELOG.md](CHANGELOG.md)

## 授權

原創程式碼採 [GPL-3.0-or-later](LICENSE)。第三方元件保留各自授權（見 App 內「開源授權」）。本專案不包含任何競品 APK、反編譯原始碼、解密資產或詞典。

---

## English

QuietInbox keeps encrypted, on-device copies of the notifications posted by messaging apps you explicitly enable, so you can read them later without opening the chat.

- **Offline by construction**: no `INTERNET` permission, no analytics, no crash reporting; CI fails if a network permission appears in the APK.
- **Read-only towards the source**: it never fires notification intents, never replies, never cancels source notifications, never marks anything read. Opening the source app is an explicit, confirmed user action.
- **Honest about quality**: capture health, content quality, identity reliability and media result are four separate, labelled dimensions.
- **No invented truth**: identical id-less messages are stored as "possible repeat", missing counts are always "unknown".

**Status: v0.1 vertical slice.** Not published to any store. The five app adapters are tested against synthetic fixtures only. Names and package ids are provisional.

Build with `./gradlew :app:assembleDebug` (JDK 17, Android SDK 37). See the module table above and [docs/SCOPE.md](docs/SCOPE.md) for what is and is not done.
