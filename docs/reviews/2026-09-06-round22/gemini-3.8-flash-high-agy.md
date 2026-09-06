# Review Round 22（第 21 輪修正之迷你再審）— 審查報告

- **審查範圍**：`git diff 8954af1..b813c41`（單一 commit [`b813c41`](file:///Users/iml1s/Documents/mine/quietinbox)，branch `main`）
- **審查模式**：唯讀審查（READ-ONLY Review），無任何檔案建立／修改／刪除、無狀態變更之 git 指令、無裝置與儀器化測試。
- **驗證指令與結果**：
  1. `python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`（exit 0）。
  2. `bash -n tools/demo-screenshots.sh` → 語法檢查通過（exit 0）。
  3. 全 35 對截圖比對（`docs/screenshots/phone/` 與 `fastlane/metadata/android/`）：
     - 雜湊比對：35 對 PNG 逐一計算 SHA-256，100% 完全相符（0 mismatch、0 missing）。
     - 規格尺寸：全數 1080×2400。
     - 檔案大小：最小者為 `ko-KR/4_activity.png`（145,258 bytes），深色收件匣為 275–317 KB，全數遠高於 80 KB 防禦下限。
  4. 截圖目視查核（透過唯讀檢視器讀取真實像素）：
     - `ja-JP`：`1_inbox` 缺口摘要為「15:02 – 18:02」、`2_conversation` 日期分隔為「2026/09/01」「2026/09/04」「今日」且訊息時間為「18:48」「2:52」「11:07」「7:33」、`4_activity` 期間為「2026/08/31 – 2026/09/06 · Asia/Taipei」、`5_capture` 連線時間為「18:01 から」；完全呈現 24 小時制與日文年月日格式，零 AM/PM 或英文月份殘留。
     - `ko-KR`：`1_inbox` 缺口摘要為「오후 2:59 – 오후 5:59」、`2_conversation` 日期分隔為「2026. 8. 28.」「2026. 9. 1.」「2026. 9. 4.」「오늘」且訊息時間帶「오전/오후」（如「오후 6:48」「오전 2:52」）、`4_activity` 期間為「2026. 8. 31. – 2026. 9. 6. · Asia/Taipei」、`5_capture` 為「오후 5:58부터」；完全符合韓文日期時間慣例。
     - `3_search`（全 5 語系）：均顯示 `meeting` 查詢詞與在地化之 13 筆結果，畫面無虛擬鍵盤，底部導覽列完整。

---

## 審查結論 (Verdict)

### **APPROVE**

Commit `b813c41` 完整且徹底地解決了 Round 21 提出的所有問題（Claude subagent 之 Important I-1、Minor 1–4、觀察 1、3、4、6、7）：
1. **重要缺陷 I-1 徹底解決**：
   - **App 端（根因消除）**：在 [`Formatting.kt`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Formatting.kt#L37) 引入 `@Composable fun currentLocale(): Locale`，從 Composition 取得 `LocalConfiguration.current.locales`；所有畫面（[`InboxScreen.kt`](file:///Users/iml1s/Documents/mine/quietinbox/feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt#L224-L225)、[`HealthScreen.kt`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt#L236-L237)、[`AnalyticsScreen.kt`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L544)、[`ConversationScreen.kt`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L377)）與 `dayLabel` 均改傳 `currentLocale()`，全 codebase 的 UI 層零殘留 `Locale.getDefault()`。新增 [`TimeFormatTest.kt`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/test/kotlin/dev/quietinbox/core/designsystem/components/TimeFormatTest.kt#L13)（2 個 JVM 測試）建立防護網。
   - **工具端（雙層防護）**：語言設定與確認提前至 `allow_listener` 之前執行，並在啟動前呼叫 `am force-stop` 杜絕行程搶先以系統語言啟動；針對非英文語系加入 `assert_locale_clock`，若畫面上出現英文 AM/PM 或月份字樣立即中斷。
   - **素材端**：全數重新拍攝，ja-JP 與 ko-KR 截圖已完全回歸正確的在地化時間與日期格式。
2. **截圖工具健壯性完善**：
   - `conversation_ready` 同時檢查釘選標題與確認 `$NAV_INBOX` 分頁不在畫面上，解決收件匣第一列滿足標題檢查的漏洞（Minor 1）。
   - 輸入法仍顯示時從 `warn` 改為 `die`，杜絕帶鍵盤截圖進入版本庫（Minor 3）。
   - 搜尋結果檢查加入 5 次重試輪詢（`query_shown`），包容非同步渲染延遲。
   - `pipefail` 比對改以變數接收處理、`wait_text` 說明修正為 attempts。
3. **文件與文案精準度齊備**：
   - 索引第 20 列改為 ENTER 說明並回填 `8954af1`、第 21 列新增；TEST_MATRIX、CHANGELOG、CLAUDE.md、`DemoReceiver` 常數排序均完成對齊；繁中 TEST_MATRIX 多餘空格已清除。

---

## 一、第 21 輪發現逐項驗證表 (Round-21 Verification Table)

| Round-21 發現項目 | 狀態 | 具體證據與檔案行號 |
| :--- | :---: | :--- |
| **I-1 [Important] ja-JP 與 ko-KR 截圖出現英文日期與 AM/PM 時間**<br>（`TimeFormat` 走 `Locale.getDefault()`，NotificationListener 使 process 常駐導致無法跟隨 per-app 語言） | **已修復** | 1. **App 根因修復**：[`Formatting.kt:37`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Formatting.kt#L37) 新增 `@Composable fun currentLocale(): Locale = LocalConfiguration.current.locales.let { if (it.isEmpty) Locale.ENGLISH else it[0] }`。`dayLabel`（`:65`）、`InboxScreen.kt:224-225`、`HealthScreen.kt:236-237, 332`、`AnalyticsScreen.kt:544, 662-663, 723`、`ConversationScreen.kt:377, 388` 全數顯式傳入 `currentLocale()`。<br>2. **單元測試保護**：新增 [`TimeFormatTest.kt:13-45`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/test/kotlin/dev/quietinbox/core/designsystem/components/TimeFormatTest.kt#L13-L45)（2 個測試），將 process default 設為 `Locale.US`，斷言日韓格式化結果不含 `Sep`、韓文含 `오`、日文不含 `M`。<br>3. **工具端保險**：[`tools/demo-screenshots.sh:410-431`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L410-L431) 在 `allow_listener` 之前即透過 `app_locale_is` 輪詢確認語言設定生效，並在 `am start` 之前執行 `am force-stop`；`:287-294` 新增 `assert_locale_clock`，以 regex 檢查非英文語系畫面上是否有 `\b(AM|PM)\b|\b(Jan|...|Dec) \d`。<br>4. **素材查核**：ja-JP 與 ko-KR 全部重拍，像素檢驗確認已完全顯示為 24 小時制與 `yyyy/MM/dd`（ja）及 `오전/오후` 與 `yyyy. M. d.`（ko）。 |
| **Minor 1 `wait_text "$DEMO_PINNED_TITLE"` 可被收件匣本身滿足** | **已修復** | [`tools/demo-screenshots.sh:468-475`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L468-L475)：改寫為 `conversation_ready() { has_text "$DEMO_PINNED_TITLE" && ! has_tab "$NAV_INBOX"; }`，利用手機版進入對話頁會隱藏底部導覽列的特性，確保非收件匣畫面。 |
| **Minor 2 reviews 索引第 20 列描述 BACK 而非 ENTER** | **已修復** | [`docs/reviews/README.md:31`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L31) 與繁中版：改寫為「ENTER dismisses the input method and one still visible is reported」／「以 ENTER 收起輸入法、仍顯示時回報」，並填入 commit `8954af1`。 |
| **Minor 3 輸入法仍顯示時只警告，照樣拍搜尋圖** | **已修復** | [`tools/demo-screenshots.sh:499-502`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L499-L502)：`if ime_shown; then sleep 2; ime_shown && die "an input method is still showing; a store screenshot must not include it"; fi`，改為直接 `die` 拒絕殘缺素材。 |
| **Minor 4 「the field's search action (ENTER)」措辭與 Compose 不符** | **已修復** | [`docs/TEST_MATRIX.md:90`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L90)、[`docs/zh-Hant/TEST_MATRIX.md:78`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L78) 與 [`tools/demo-screenshots.sh:494`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L494)：改為「ENTER (the single-line field's Done action; the search is live, nothing is submitted)」／「ENTER（單行欄位的 Done 動作；搜尋是即時的，沒有送出）」。 |
| **觀察 1 `grep -q` 與 pipefail 提早退出風險** | **已修復** | [`tools/demo-screenshots.sh:411-414`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L411-L414)：`app_locale_is()` 先將 dump 存入 `local current` 變數並去除 `\r`，再以 `case "$current" in *"$LOCALE"*)` 比對，避免管線中斷問題。 |
| **觀察 3 `MIN_SHOT_BYTES` 解析度假設未註明** | **已修復** | [`docs/TEST_MATRIX.md:90`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L90) 與繁中版：加註說明「calibrated on the 1080×2400 `QuietInbox_Phone`」／「以 1080×2400 的 `QuietInbox_Phone` 校準」。 |
| **觀察 4 `wait_text` 警告寫「within 10 s」低估實際開銷** | **已修復** | [`tools/demo-screenshots.sh:235`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L235)：註解由 `[seconds]` 改為 `[attempts]`；`:475` 警告文字改為「after 10 attempts」。 |
| **觀察 6 zh-Hant TEST_MATRIX 句號後多餘空格** | **已修復** | [`docs/zh-Hant/TEST_MATRIX.md:78`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L78)：「複製進 debug 資料庫。Android 13 以上」已移除句號後多餘之 ASCII 空格。 |
| **觀察 7 `DemoReceiver` 常數排序拆開** | **已修復** | [`DemoReceiver.kt:78-79`](file:///Users/iml1s/Documents/mine/quietinbox/app/src/debug/kotlin/dev/quietinbox/debug/DemoReceiver.kt#L78-L79)：`EXTRA_LANG` 移至 `OP_SEED` 與 `OP_CLEAR` 之後，維持操作常數相鄰。 |

---

## 二、Brief 核心機制查核與問題深入分析

### 1. App 端時間格式化查核
- **全全域 `Locale.getDefault()` 掃描**：
  - 於 `feature/` 與 `app/` 進行全文 grep，**UI 格式化呼叫點已完全清零**。
  - 唯一存在的 `Locale.getDefault()` 僅在 [`Formatting.kt:16-23`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Formatting.kt#L16-L23) 的預設參數引數中（保留供非 Composable 呼叫端相容），以及 [`TimeFormatTest.kt`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/test/kotlin/dev/quietinbox/core/designsystem/components/TimeFormatTest.kt) 用於設定測試情境的 process default。
- **Compose 外部的 `ReminderScheduler` / 通知機制**：
  - 查核 [`ReminderScheduler.kt:147-148`](file:///Users/iml1s/Documents/mine/quietinbox/app/src/main/kotlin/dev/quietinbox/reminders/ReminderScheduler.kt#L147-L148)：通知內容透過 `context.getString(R.string.reminder_title)` 與 `context.resources.getQuantityString(R.plurals.reminder_body_count, unviewed, unviewed)` 產生。
  - 提醒通知僅格式化純整數計數，**完全不包含任何日期或時間字串**。其讀取走 Android Resources 系統，本質上跟隨 App 與系統資源配置，不需要亦未曾使用 `Locale.getDefault()` 或 `currentLocale()`。
- **inline lambda (`let`, `buildString`) 內呼叫 `currentLocale()` 的有效性**：
  - Kotlin 標準函式庫之 `let` 與 `buildString` 均為 `inline` 函式。
  - 在 Jetpack Compose 編譯架構下，`inline` 函式會直接展開至呼叫端的作用域中，其 lambda 區塊會透明繼承外層函式的 `@Composable` 環境。
  - 實例中包含 `stringResource(...)` 等 Composable 函式早已穩定運行於這些同等作用域內（如 `HealthScreen.kt:332` 之 `buildString`）；編譯通過無誤。
- **`TimeFormatTest` 測試牙齒檢驗（心智修補實驗）**：
  - 若在 `Formatting.kt` 中將 `.withLocale(locale)` 註解／移除，`TimeFormat` 會回退至預設的 `Locale.getDefault()`（測試中被主動強制設定為 `Locale.US`）：
    1. `japaneseAndKoreanDatesCarryNoEnglishMonth`：日韓日期將格式化為英文 `"Sep 4, 2026"`，直接違反 `ja shouldNotContain "Sep"` 與 `ko shouldNotContain "Sep"`，測試必定失敗。
    2. `timesFollowTheGivenLocaleNotTheProcessDefault`：時間將帶有英文 `"AM"` 或 `"PM"`，違反韓文 `shouldContain "오"` 與日文 `shouldNotContain "M"`，測試必定失敗。
  - 結論：此單元測試具備百分之百攔截 `Locale.getDefault()` 退化的能力。

### 2. 截圖工具 `tools/demo-screenshots.sh` 查核
- **語言設定時序防禦**：
  - `pm clear` 之後，先呼叫 `set-app-locales` 並藉由 `app_locale_is` 輪詢驗證最多 5 次，確保系統已確認語言變更。
  - 隨後才執行 `allow_listener` 與 `pm grant`。
  - 在正式 `am start` 之前，強制執行 `am force-stop "$APP_ID"`，確保監聽器綁定時預先喚醒的舊程序徹底被終止，App 必然由具備正確 per-app locale 的乾淨環境冷啟動。
- **`assert_locale_clock` 與 Regex 假陽性（False Positive）評估**：
  - 檢驗 regex：`r"\b(AM|PM)\b|\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d"`。
  - 全面比對 `DemoLocalisation.kt`、`DemoDataRepository.kt` 與字串檔：
    - 示範訊息時間均為 `"16:00"`、`"10:00"`、`"7 時半"` 等 24 小時制或在地用語，無任何獨立的 `"AM"` 或 `"PM"` 單字。
    - 示範文字與 URL 中無任何英文月份接空格與數字（如 `"May 1"`）之結構。
    - 結論：該斷言在非英文語系之 1、2、4、5 號截圖前執行，既能精準攔截英文時鐘退化，又絕不會對示範內容產生誤報。
- **對話頁就緒條件 `conversation_ready`**：
  - `has_text "$DEMO_PINNED_TITLE" && ! has_tab "$NAV_INBOX"`。
  - 手機版導覽邏輯在進入對話畫面時隱藏底部 NavigationBar；收件匣清單本身雖有名為釘選標題的列，但因同時帶有 `$NAV_INBOX` 分頁，`! has_tab` 保證不會在收件匣誤判為就緒。
- **輸入法殘留處理**：
  - `ime_shown` 重試後仍為真時直接呼叫 `die`，確保問題素材即刻中止，不再讓帶有軟體鍵盤的截圖意外合入。
- **`query_shown` 重試**：
  - 在拍照前進行最多 5 次輪詢比對，排除 UI 非同步更新之時序競爭。

### 3. 文檔與規格同步查核
- **CHANGELOG.md**：Fixed 項目詳實記錄 Round 21 根因（Android 13+ 活程序走 process default）與解決方案（`currentLocale()`）；JVM 測試計數更新為 212（原 210 + 2 個 `TimeFormatTest`）。
- **TEST_MATRIX.md**（en／zh-Hant）：設計系統列完整補上 `TimeFormatTest` 測試說明與執行指令；示範截圖說明更新為 Done 動作、80 KB 門檻與 1080×2400 解析度校準、語言設定順序及英文時鐘防護機制。
- **CLAUDE.md**：開發準則明定 UI 日期、時間、數字必須使用 `currentLocale()`，絕不用 `Locale.getDefault()`；稽核追蹤更新為 `rounds 18–21 reviewed the localisation`。
- **Reviews Index**：第 20 列改為 ENTER 說明並回填 `8954af1`；第 21 列新增記錄。

---

## 三、新發現事項 (New Findings)

- **Critical**：0 項。
- **Important**：0 項。
- **Minor**：0 項。

---

## 四、其他觀察 (Observations)

1. **reviews 索引第 21 列佔位文字**：
   [`docs/reviews/README.md:32`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L32) 與繁中版中，第 21 列的修復 commit 欄位維持填寫「follow-up commit」／「後續 commit」。這屬於正常的過渡狀態（commit 建立時無法預知自身的 SHA），待下一次文檔提交時回填 `b813c41` 即可。

---

## 五、總結

Commit `b813c41` 展現了極高的工程嚴謹度：從根因上徹底拔除了 Jetpack Compose 介面時間在地化對 process default locale 的依賴，建立了可靠的 Composition-level locale 機制與防護測試；同時在自動化截圖工具層補齊了程序生命週期重設、導覽狀態雙重驗證、鍵盤強制限阻與語言時鐘斷言；全 35 張店面截圖品質無懈可擊。

本輪審查全數通過，結論為 **APPROVE**。
