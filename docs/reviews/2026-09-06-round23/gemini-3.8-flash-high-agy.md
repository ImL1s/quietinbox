# Round 23 獨立審查（Gemini 3.8 Flash high）— 第 22 輪修正的迷你再審

- **審查對象**：`git diff b813c41..800f65b`（單一 commit `800f65b`，branch `main`）。
- **審查方式**：嚴格唯讀（Read-Only）。檢驗 `800f65b` 針對第 22 輪審查報告（`docs/reviews/2026-09-06-round22/{gemini-3.8-flash-high-agy,claude-subagent}.md`）中 Claude subagent 提出的 5 項 Minor 與 4 項觀察（觀察 1、2、4、5）的修復品質。
- **跑過的唯讀指令**：
  - `python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`。
  - `bash -n tools/demo-screenshots.sh` → 語法檢查通過（exit 0）。
  - `git diff` / `git show` / `grep` 全庫盤點 `TimeFormat`、`has_tab`、`has-tab`、`wait_text`。
  - 無建立、修改、刪除任何檔案，無狀態變更指令，無碰觸模擬器或實體裝置。

---

## Verdict: APPROVE

本 commit（`800f65b`）精準且完整地解決了第 22 輪提出的所有 Minor（1–5）與相關觀察（1、2、4、5）：
1. `TimeFormat.time/dateTime/date` 移除預設參數，將 `locale: Locale` 改為編譯期必填，杜絕漏傳 `locale` 導致回退至程序預設 `Locale.getDefault()` 的風險；全庫所有呼叫點皆已傳入 `currentLocale()`（或函式內之 `locale`）。
2. `TimeFormatTest` 修正日期常數，改以 `ZonedDateTime.of(2026, 9, 3, 9, 0, 0, 0, zone)` 建立明確的時間戳，且兩個單元測試均具備嚴格的鑑別力（能確實抓出忽略 `locale` 的實作缺陷）。
3. `tools/demo-screenshots.sh` 將對話畫面就緒檢查（釘選標題在畫面上且底部 15% 不存在收件匣標籤）整併至單一 UI dump 決策（耗時減半），逾時判定從 `warn` 改為嚴格 `die`；`has-english-clock` 加入 `$APP_ID` 過濾避免狀態列 SystemUI 誤判；`has_tab` dump 失敗回傳 2 且無誤將 2 當作不存在的呼叫點；廢棄且無呼叫者的 `wait_text` 已完全移除。
4. 文件面（CHANGELOG、審查索引 README、CLAUDE.md）皆正確對齊第 21/22 輪的強化細節與輪次範圍。

無 Critical、無 Important、無 Minor 缺陷。

---

## 一、第 22 輪發現與修正核對表

| 項目 | 原始問題描述 | 修復狀態 | 驗證與程式碼證據 |
| :--- | :--- | :---: | :--- |
| **Minor 1** | `TimeFormat` 三個方法仍帶 `Locale.getDefault()` 預設參數，使「禁止使用程序預設」規則缺乏編譯期保障 | **已修復** | [`Formatting.kt:20,23,26`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Formatting.kt#L20-L26)：`time`、`dateTime`、`date` 的 `locale: Locale` 全部移除預設值，改為編譯期必填。KDoc 明確記錄設計原由。全庫 grep 證實所有 UI 呼叫點皆顯式傳入 `currentLocale()`。 |
| **Minor 2** | `TimeFormatTest` 常數名 `septemberThird` 與註解說 9/4 早上，實際 epoch 為 9/8 凌晨，誤導維護者 | **已修復** | [`TimeFormatTest.kt:16`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/test/kotlin/dev/quietinbox/core/designsystem/components/TimeFormatTest.kt#L16)：改為 `septemberThirdMorning = ZonedDateTime.of(2026, 9, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()`，自我解釋意圖。 |
| **Minor 3** | 審查索引第 21 列寫「ja-JP 與 ko-KR 重拍」，但實際替換了五個語系共 35 張截圖 | **已修復** | [`docs/reviews/README.md:32`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L32)、[`docs/zh-Hant/reviews/README.md:30`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L30)：第 21 列皆更正為「all five locales re-shot (the clock assertion on for the four CJK ones)」/「五個語系全部重拍，四個 CJK 語系開啟時鐘斷言」。 |
| **Minor 4** | 對話頁就緒逾時僅 `warn` 便拍照，可能讓收件匣被儲存為 `2_conversation.png` 並通過所有門檻 | **已修復** | [`tools/demo-screenshots.sh:493`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L493)：`conversation_ready || die "the conversation page did not settle after 10 attempts (a store screenshot must be the conversation, not the inbox)"`。 |
| **Minor 5** | CHANGELOG 未記錄第 21 輪的截圖工具強化與全語系重拍 | **已修復** | [`CHANGELOG.md:12`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L12)：補上完整條目「Round-21/22 review fixes (`docs/reviews/2026-09-06-round21/`, `round22/`): the screenshot tool sets and confirms the app language before anything starts...」。 |
| **觀察 1** | `has_tab` 在 `dump_ui` 失敗時回傳 1，可能使外部誤將 dump 失敗讀作分頁消失 | **已修復** | [`tools/demo-screenshots.sh:297`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L297)：`dump_ui || return 2`。此外 `conversation_ready` 改由 Python helper 直接統一判斷，已不再依賴 shell 的 `! has_tab`。 |
| **觀察 2** | `has-english-clock` 掃描所有節點，未過濾 package，可能誤抓 SystemUI 狀態列時鐘 | **已修復** | [`tools/demo-screenshots.sh:142-146`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L142-L146)：接受 `package = sys.argv[2]`，凡 `(node.get("package") or "") != package` 者皆 `continue` 跳過；在呼叫點傳入 `"$APP_ID"`。註解亦明確標記假設裝置語言為英文。 |
| **觀察 4** | `conversation_ready` 每次迭代進行兩次 `uiautomator dump`，開銷較高 | **已修復** | [`tools/demo-screenshots.sh:153-172`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L153-L172)：新增 helper 指令 `conversation-ready`，在同一份 `ui.xml` 樹中同時判斷標題出現與底部導覽列收件匣分頁消失，單次輪詢僅需一次 dump。 |
| **觀察 5** | `wait_text` 已無任何呼叫點 | **已修復** | [`tools/demo-screenshots.sh`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh)：已將無人使用的 `wait_text` 函式完全移除。 |

---

## 二、Brief 核心檢驗項目深入分析

### 1. `TimeFormat` 與單元測試鑑別力

- **編譯期強型別安全與呼叫點盤點**：
  - `Formatting.kt` 中的 `time`、`dateTime`、`date` 定義均為 `locale: Locale`（無預設值）。
  - `localDate(epochMs, zone)` 維持無 `locale`，因其僅解析為 ISO-8601 年月日數值，不涉及語系格式化，設計正確。
  - 全專案 grep 盤點：
    - `feature/conversation/ConversationScreen.kt:378, 388` → `locale = currentLocale()`
    - `feature/analytics/AnalyticsScreen.kt:662, 663` → `locale = currentLocale()`
    - `feature/health/HealthScreen.kt:237, 238, 332` → `locale = currentLocale()`
    - `feature/inbox/InboxScreen.kt:224, 225` → `locale = currentLocale()`
    - `Formatting.kt:55, 69` → 內部呼叫傳入 `locale = locale` 或 `locale = currentLocale()`
  - 每個呼叫點均顯式傳遞，日後若有開發者呼叫漏填即會在編譯期報錯。
- **KDoc 準確度**：
  - 程式碼註解載明：「*The locale is required on purpose: the UI passes [currentLocale], and a default of `Locale.getDefault()` would quietly bring back the process-default bug the moment a call site forgot it.*」精確解釋了約束架構的意圖，完全符合目前實作。
- **`TimeFormatTest` 鑑別力檢驗**：
  - 測試使用 `septemberThirdMorning = ZonedDateTime.of(2026, 9, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()`，代表台北時間 2026 年 9 月 3 日上午 09:00:00。
  - 在執行前先執行 `Locale.setDefault(Locale.US)`，模擬程序預設為英文語系：
    1. **日期測試 `japaneseAndKoreanDatesCarryNoEnglishMonth`**：
       - 日文（`Locale.JAPAN`）：中等格式為 `2026/09/03` 或 `2026年9月3日`，符合 `shouldNotContain "Sep"` 與 `shouldContain "2026"`。
       - 韓文（`Locale.KOREA`）：中等格式為 `2026. 9. 3.`，符合 `shouldNotContain "Sep"`。
       - 美國（`Locale.US`）：中等格式為 `Sep 3, 2026`，符合 `shouldContain "Sep"`。
       - **鑑別力**：若實作漏用傳入的 `locale` 而回退至 `Locale.getDefault()`（即 `Locale.US`），日文與韓文的斷言均會因包含 `"Sep"` 而立即失敗。
    2. **時間測試 `koreanTimeCarriesKoreanMeridiemAndJapaneseOmitsMeridiemMarker`**：
       - 韓文上午 9:00 為 `오전 9:00`，符合 `shouldContain "오"`。
       - 日文上午 9:00 為 `9:00`（無 AM/PM 標記），符合 `shouldNotContain "M"`。
       - 美國上午 9:00 為 `9:00 AM`，符合 `shouldContain "M"`。
       - **鑑別力**：若實作忽略傳入的 `locale` 回退至 `Locale.US`，韓文將得到 `"9:00 AM"`（不含 `"오"`，失敗），日文亦會得到 `"9:00 AM"`（包含 `"M"`，失敗）。
  - 結論：兩個測試均具備 100% 有效的防回歸鑑別力。

### 2. 截圖工具 `tools/demo-screenshots.sh` 強化

- **`conversation-ready` 單次 dump 與逾時失敗**：
  - Python helper `conversation-ready "$DEMO_PINNED_TITLE" "$NAV_INBOX"` 於單次走訪解析樹中：
    1. 記錄畫面高度 `height = max(box[3])`。
    2. 檢查是否有節點文字或 content-desc 等於 `$DEMO_PINNED_TITLE`（記錄 `title_seen = True`）。
    3. 檢查是否有節點文字或 content-desc 等於 `$NAV_INBOX`，且位於底部 15%（`box[1] >= int(height * 0.85)`）；若發現則判定底部導覽列仍在（即仍處於收件匣），立即回傳 1（未就緒）。
    4. 僅當 `title_seen == True` 且無底部收件匣標籤時回傳 0。
  - 迴圈嘗試 10 次，若仍未就緒則直接執行 `die` 退出，徹底防止將收件匣當作對話截圖輸出。
- **`has-english-clock` 限制 App 專屬節點**：
  - Python helper 讀入 `sys.argv[2]`（即傳入的 `$APP_ID`），凡 `(node.get("package") or "") != package` 一律跳過，確保 Android 系統頂部狀態列（`com.android.systemui`）的英文時間不會干擾 CJK 語系的時鐘檢查。
- **`has_tab` 回傳碼與呼叫點稽核**：
  - `has_tab()` 於 `dump_ui` 失敗時明確回傳 2（`dump_ui || return 2`）。
  - 全域搜尋證實，腳本中已**無任何地方呼叫 `has_tab`**（對話頁已改走 Python helper 的 `conversation-ready` 指令）。因此絕無任何呼叫點會將回傳值 2 誤判為分頁已消失。
- **`wait_text` 移除**：
  - 全庫搜尋確認 `wait_text` 函式定義已自 `tools/demo-screenshots.sh` 刪除，且無殘留呼叫點。

### 3. 文件一致性與稽核軌跡

- **`CHANGELOG.md`**：
  - 新增之 Round-21/22 條目詳述了工具端修正（locale 設定順序、啟動前 force-stop、CJK 英文時鐘拒拍、限制 app package、對話頁雙條件就緒與逾時 die、輸入法殘留 die、重試查詢）以及 `TimeFormat` 編譯期必填 `locale` 與重拍五個語系。
- **審查索引（`docs/reviews/README.md` 與 `docs/zh-Hant/reviews/README.md`）**：
  - 第 21 列：正確指明修正 commit 為 `b813c41`，且載明「五個語系全部重拍，四個 CJK 語系開啟時鐘斷言」（修正了原先只提兩個語系的落差）。
  - 第 22 列：已追加記錄 Round 22 審查概況（agy APPROVE、subagent APPROVE WITH MINOR FIXES），修正欄標註為 `follow-up commit` / `後續 commit`（符合本專案在當輪修復 commit 產生前的既有慣例）。
- **`CLAUDE.md`**：
  - 第 132–133 行更新為 `docs/reviews/2026-09-06-round{10,…,22}/` 與 `rounds 18–22 reviewed the localisation`，範圍更新完整。

---

## 三、新發現 (New Findings)

- **Critical**：無。
- **Important**：無。
- **Minor**：無。

---

## 四、其他觀察 (Other Observations，不阻擋)

1. **`has_tab` 與 `has-tab` 成為備用 helper**：
   - 由於對話頁就緒檢查已改為整合式的 `conversation-ready`，目前 shell 函式 `has_tab` 與 Python 命令 `has-tab` 在腳本內部已無呼叫點。不過其保留了完整的測試語意（dump 失敗回傳 2、僅檢查底部 15% 節點），可作為未來擴充測試時的通用工具函式，無負面影響。
2. **審查索引第 22 列回填**：
   - 目前 `README.md` 中第 22 列的修正 commit 欄位記載為 `follow-up commit` / `後續 commit`，於本輪審查結束並確認 commit 雜湊（即 `800f65b`）後，可在後續維護中比照過往輪次將其回填。
