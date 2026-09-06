> English: [../COMPATIBILITY.md](../COMPATIBILITY.md)

# 來源相容性矩陣

狀態值（計畫 §14）：`UNTESTED / SYNTHETIC_ONLY / REAL_DEVICE_PASSED / PARTIAL / REGRESSED / BLOCKED`。
新版本的來源 App 絕不會沿用舊資料列的狀態。

| 來源 | Package | Adapter | Adapter 版本 | 狀態 | QuietInbox commit | 來源 versionCode | OS／裝置 | 證據 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| LINE | `jp.naver.line.android` | `line` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `parsers/apps/src/test/.../LineParserTest.kt` |
| WhatsApp | `com.whatsapp` | `whatsapp` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `WhatsAppParserTest.kt` |
| Telegram | `org.telegram.messenger` | `telegram` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `TelegramParserTest.kt` |
| Instagram | `com.instagram.android` | `instagram` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `InstagramParserTest.kt` |
| Messenger | `com.facebook.orca` | `messenger` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `MessengerParserTest.kt` |
| 其他任何 App | — | `standard` | 1.0.0 | SYNTHETIC_ONLY | HEAD | — | — | `core/parser/src/test/.../StandardParserTest.kt` |
| QuietInbox 合成發布器 | `dev.quietinbox.app.debug` | `standard` | 1.0.0 | REAL_DEVICE_PASSED | afa7818 | 1 | Android 16／Samsung SM-S9280 | 引導流程步驟 4 擷取到 3/3 則訊息（2026-09-06） |

目前沒有任何 adapter 以 `VERIFIED` 信心度輸出 `sourceMessageId` 或 `SOURCE_CHAT_ID` 證據，因為還沒有來自
真實裝置的 fixture。要把某一列提升為 `REAL_DEVICE_PASSED`，必須以兩個知情同意的測試帳號、並在訊息內容中
放入合成標記，完成 `TEST_MATRIX.md` 中的 T001／T004／T016／T017／T045 情境；真實的私人訊息絕不會進入這個
repository。

編譯／目標基準：compileSdk 37（所使用的 AndroidX 版本要求），targetSdk 36
（計畫 §4 基準）。API 37 的 target 線路已列入追蹤，但尚未實際執行。

## 工作設定檔、Device Policy 與低記憶體裝置（QI-ID-008）

- Android 會把工作設定檔 App 的通知送到安裝在**個人**設定檔的 listener（副本會標上設定檔：`profileKey` 為
  `user:<id>`，收件匣對這類會話顯示「工作設定檔」標籤）。安裝在工作設定檔*裡面*的 listener 會被系統忽略，
  因此靜讀必須安裝在個人設定檔。
- Device Policy Controller（MDM）可以阻止工作設定檔 App 的通知送到個人設定檔的 listener。靜讀無法偵測這件事；
  該來源只會一直沒有出現。擷取健康頁會顯示 listener 已連線，但什麼都沒進來。
- 來源設定以套件為單位，不分設定檔：啟用 LINE 會同時擷取個人與工作的 LINE。逐設定檔的來源控制與會話身分中的
  非 null 帳號鍵是已規劃的 schema 工作（見 `docs/SCOPE.md`「未完成」）。
- Android Q 以前的低記憶體（Go）裝置完全不綁定通知 listener（`ActivityManager.isLowRamDevice`）；靜讀在那些裝置上無法擷取。

## 提交匿名 fixture（QI-PARSER-017）

只接受合成內容的 fixture；真實對話永遠不會進入 repo。要把某個來源的列升級：

1. 用兩個你自己的測試帳號。傳送的訊息正文只能是測試標記（`T001 alpha`、`T004 sticker`……），對應
   `TEST_MATRIX.md` 的每個情境（T001 / T004 / T016 / T017 / T045）各一則。
2. 在 debug 版，擷取 → 複製摘要 會給出不含正文的診斷摘要；parser 警告與通知樣板是重點。
3. 記錄：來源 App versionCode、Android 版本、OEM、系統語言、通知設定（預覽開／關）、通知形狀
   （MessagingStyle / BigText / Inbox / summary）與 extras 的**鍵名**（絕不要可能帶文字的值）。
4. 確認在靜讀讀副本沒有讓來源端標記已讀。
5. 開一張「來源相容性回報」issue（`.github/ISSUE_TEMPLATE/compatibility_report.yml`）。維護者會把它轉成
   `parsers/apps/src/test/` 下同樣合成文字的 Kotest fixture，矩陣的列連同 commit、versionCode 與裝置升級為 `REAL_DEVICE_PASSED`。
