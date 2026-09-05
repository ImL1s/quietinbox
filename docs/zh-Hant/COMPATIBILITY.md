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
