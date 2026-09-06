# Round 22 獨立審查（Claude subagent）— 第 21 輪修正的迷你再審

- 審查對象：`git diff 8954af1..b813c41`（單一 commit `b813c41`，branch `main`，HEAD 與 brief 一致）。
- 審查方式：唯讀。逐段讀完非圖片 diff（`Formatting.kt`、`TimeFormatTest.kt`、四個 feature screen、`DemoReceiver.kt`、`tools/demo-screenshots.sh`、CHANGELOG／CLAUDE.md／TEST_MATRIX en+zh／reviews 索引 en+zh／round-21 三份存檔），對照第 21 輪兩份報告逐項核對；另讀了 `MainNavigation.kt` 的 chrome 規則（`showChrome = current !is ConversationRoute || wide`）、`ReminderScheduler.kt`、`DemoLocalisation.kt`／`DemoDataRepository.kt` 的示範文案、`AnalyticsScreen.kt` 的 `median()` 與 `oneDecimal()`。
- 實際跑過的指令：`python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`；`bash -n tools/demo-screenshots.sh` → exit 0；`./gradlew :core:designsystem:testDebugUnitTest --console=plain -q` → exit 0，JUnit XML：`MonogramTest tests="4" failures="0"`、`TimeFormatTest tests="2" failures="0" errors="0"`。另以 grep 全 repo 找 `Locale.getDefault`／`TimeFormat.`／`DateTimeFormatter`／`String.format`，以 python 把 `has-english-clock` 的 regex 掃過 `platform/storage/src/debug` 全部 Kotlin 與五個語系的 `strings.xml`，以 `cmp` 比對 35 對 docs／fastlane PNG，以 `sips` 取尺寸，加總 `build/test-results` 的 JVM 測試數，讀 `build/reports/lint-results-debug.xml` 的 severity，比對 feature 模組 class 檔與原始碼的 mtime。沒有跑 instrumented test、沒有碰裝置或模擬器、沒有改 repo 任何檔案。
- 目視過的 PNG（在 scratchpad 縮圖後閱讀，repo 檔案未動）：ja-JP 與 ko-KR 的 `1_inbox`／`2_conversation`／`3_search`／`4_activity`／`5_capture`，zh-TW `1_inbox`，zh-CN `2_conversation`。其餘只檢查尺寸與大小。

## Verdict: APPROVE WITH MINOR FIXES

第 21 輪的 Important I-1 修在根因上：`Formatting.kt` 新增 `currentLocale()`（讀 `LocalConfiguration.current.locales`），`relativeTime`、`dayLabel` 與八個呼叫點全部改傳它，`Locale.getDefault()` 在 main 原始碼裡只剩 `TimeFormat` 三個預設參數；`TimeFormatTest` 的斷言確實能分辨「用指定語系」與「用程序預設」。ja-JP／ko-KR 重拍的截圖全部是 24 小時制／오전·오후 與 `2026/09/01`／`2026. 8. 28.`。工具把 `set-app-locales` 移到 `allow_listener`／`pm grant` 之前並在 `am start` 前 `force-stop`，四個 CJK 語系在四張截圖前有 English-clock 斷言，對話頁就緒條件加上「底部列消失」，輸入法殘留改為 `die`，查詢斷言重試 5 次。Minor 1–4、觀察 1／2／3／4／6／7 全部有修。

剩下的都是不阻擋的 Minor：`TimeFormat` 的 `Locale.getDefault()` 預設參數沒有任何呼叫者需要它、卻讓 CLAUDE.md 新規則沒有編譯期保障；`TimeFormatTest` 的常數名與註解寫錯日期；索引第 21 列說「ja-JP 與 ko-KR 重拍」但 diff 換了 35 張（五個語系）；對話頁就緒逾時只 `warn` 就拍；CHANGELOG 沒有記錄第 21 輪的工具強化。沒有 Critical、沒有 Important。

統計：Critical 0 · Important 0 · Minor 5 · 觀察 6。

---

## 一、第 21 輪發現逐項核對

### 1.1 Claude subagent 報告

| 第 21 輪發現 | 修了？ | 證據 |
| --- | --- | --- |
| **Important I-1** 所有畫面用 `Locale.getDefault()` 格式化日期時間，per-app 語言不更新它；ja／ko 截圖出現「Sep 3, 2026」「2:52 AM」 | ✅ | `core/designsystem/.../Formatting.kt:36-37` `@Composable fun currentLocale(): Locale = LocalConfiguration.current.locales.let { if (it.isEmpty) Locale.ENGLISH else it[0] }`；`:42` `relativeTime` 改用它；`:65` `dayLabel` 傳 `locale = currentLocale()`。呼叫點：`InboxScreen.kt:224-225`（收件匣缺口時間）、`HealthScreen.kt:237-238`（缺口起訖）、`:332`（連線時間）、`AnalyticsScreen.kt:662-663`（期間起訖）、`:544`＋`:723` `oneDecimal(value, locale)`、`ConversationScreen.kt:378`／`:388`（訊息時間）。全 repo grep：`Locale.getDefault` 在 main 原始碼只剩 `Formatting.kt:16,19,22` 三個預設參數（見新 Minor 1）；`TimeFormat.` 的呼叫者全在 Compose 內；沒有 `SimpleDateFormat`／`DateUtils`／`NumberFormat`；`ofPattern` 只有 `Formatting.kt:53-54`，都帶 `locale`。截圖證據見第三節 |
| 建議的 JVM 測試 | ✅ | `TimeFormatTest.kt`（2）：把程序預設設成 `Locale.US` 後，`TimeFormat.date(…, Locale.JAPAN/KOREA)` 不含 `Sep`、`TimeFormat.time(…, Locale.KOREA)` 含 `오`、`Locale.JAPAN` 不含 `M`、`Locale.US` 含 `Sep`／`M`；本機跑過通過 |
| 建議的工具保險（locale 先於任何會拉起程序的指令、輪詢後 `force-stop`、CJK 語系的 AM/PM 斷言） | ✅ | `tools/demo-screenshots.sh:404` `pm clear` → `:410-425` `set-app-locales`＋`app_locale_is` 輪詢（dump 先存進變數再 `case` 比對）→ `:427-429` `allow_listener`／`pm grant` → `:431` `am force-stop` → `:433-436` `uimode`／`imes_off`／`am start`。`:138-147` helper 新增 `has-english-clock`（regex `\b(AM|PM)\b|\b(Jan|…|Dec) \d`，掃 `text` 與 `content-desc`，印出命中值）；`:287-294` `assert_locale_clock`（en-US 跳過，命中即 `die` 並帶命中字串）；`:459`／`:477`／`:521`／`:527` 在 1、2、4、5 四張截圖前各呼叫一次 |
| Minor 1 `wait_text "$DEMO_PINNED_TITLE"` 在收件匣上就會成立 | ✅ | `:468-470` `conversation_ready() { has_text "$DEMO_PINNED_TITLE" && ! has_tab "$NAV_INBOX"; }`；`:121-136` helper `has-tab` 只認畫面最下 15% 的節點（與 `tap-tab` 同一幾何規則）；`MainNavigation.kt:80` `showChrome = current !is ConversationRoute || wide` 證明手機版對話頁沒有底部列，條件成立。逾時後仍只 `warn`（見新 Minor 4） |
| Minor 2 索引第 20 列寫 BACK 而非 ENTER | ✅ | `docs/reviews/README.md:178`「ENTER dismisses the input method and one still visible is reported」、修正欄 `8954af1`；zh 版 `:211`「以 ENTER 收起輸入法、仍顯示時回報」、`8954af1` |
| Minor 3 輸入法仍顯示只警告 | ✅ | `:499-502` `if ime_shown; then sleep 2; ime_shown && die "an input method is still showing; a store screenshot must not include it"; fi` |
| Minor 4 「search action」措辭 | ✅ | 腳本 `:494-496`「ENTER triggers the single-line field's Done action … the search itself is live, nothing is submitted」；`docs/TEST_MATRIX.md:74`「dismisses it with ENTER (the single-line field's Done action; the search is live, nothing is submitted)」；zh 版 `:65`「以 ENTER（單行欄位的 Done 動作；搜尋是即時的，沒有送出）」 |
| 觀察 1 `get-app-locales \| grep -q` 的 pipefail 型態 | ✅ | `:411-415` `app_locale_is` 先 `current="$(… \| tr -d '\r')"` 再 `case "$current" in *"$LOCALE"*)`，沒有 `grep -q` |
| 觀察 2 API < 33 多花 5 秒 | ✅ | `:416` 第一次 `set-app-locales` 失敗直接走 `else` 分支 `:423-424` 警告，不進迴圈 |
| 觀察 3 `MIN_SHOT_BYTES` 綁定解析度沒寫明 | ✅ | `docs/TEST_MATRIX.md:74`「at least 80 KB (calibrated on the 1080×2400 `QuietInbox_Phone`)」；zh 版同句 |
| 觀察 4 `wait_text` 警告寫「within 10 s」 | ✅ | `:235-236` 註解改為「[attempts] — polls (one UI dump per attempt)」；`:475` 訊息「did not settle after 10 attempts」（`wait_text` 本身已無呼叫者，見觀察 5） |
| 觀察 6 zh TEST_MATRIX 多一個 ASCII 空格 | ✅ | `docs/zh-Hant/TEST_MATRIX.md:65`「複製進 debug 資料庫。Android 13 以上」 |
| 觀察 7 `EXTRA_LANG` 插在兩個 op 常數之間 | ✅ | `DemoReceiver.kt:75-78` 順序改為 `EXTRA_OP`、`OP_SEED`、`OP_CLEAR`、`EXTRA_LANG` |

未處理但本來就不要求的：觀察 5（每次重拍的搜尋結果數會隨 `now` 變動）——這是示範資料的設計，非缺陷。

### 1.2 agy 報告

第 21 輪 agy 為 APPROVE、0 發現，無需核對。其觀察 2（索引第 20 列佔位字）已在本 commit 回填 `8954af1`。

### 1.3 brief 提出的問題

1. **是否還有 UI 走 `Locale.getDefault()`**：沒有。grep `feature/`、`app/`、`core/`、`platform/` 的 main 原始碼，`Locale.getDefault` 只剩 `Formatting.kt:16,19,22` 的預設參數，而所有 `TimeFormat.time/date/dateTime` 呼叫者（上表七處＋`Formatting.kt:51,65`）都明確傳 `locale`。`feature/settings`、`feature/onboarding`、`app/ui` 沒有任何時間戳顯示；`feature/search` 只用 `relativeTime`（`SearchScreen.kt:184`），本來就走 composition。數字方面 `oneDecimal` 已帶 locale；其餘計數用 `Int.toString()` 與 `%d` 字串資源，五個語系都是阿拉伯數字，沒有問題。
2. **`ReminderScheduler`／Compose 之外的通知**：`app/src/main/kotlin/dev/quietinbox/reminders/ReminderScheduler.kt:138-148` 只用 `context.getString(R.string.reminder_channel/reminder_title)` 與 `getQuantityString(R.plurals.reminder_body_count, …)`，沒有格式化任何日期或時間；`:70-87` 的 `ZonedDateTime` 只做排程計算，不輸出文字。**目前不需要 `currentLocale()`**。第 21 輪的截圖已證明字串資源在程序存活時就跟著 App 語言走（日文字串＋英文時間同框），所以資源查找是安全的；若日後提醒要顯示時間，應讀 `context.resources.configuration.locales[0]`，不可讀 `Locale.getDefault()`，否則會踩回同一個坑。`DemoDataRepository.kt:69` 在沒帶 locale 時讀的正是 `context.resources.configuration.locales[0]`，與這個原則一致。
3. **inline lambda 內的 composable 呼叫**：`HealthScreen.kt:237-238` 與 `InboxScreen.kt:225` 的 `?.let { … currentLocale() }`、`HealthScreen.kt:330-333` 的 `buildString { … }` 都是 Kotlin `inline` 函式，Compose 編譯器允許在非 composable inline 函式的 lambda 內呼叫 composable（同一個 `buildString` 本來就已經在呼叫 `stringResource`）。證據：`feature/{health,inbox,analytics,conversation}/build/intermediates/…/debug/…ScreenKt.class` 的 mtime 17:49:21 晚於四個原始碼的 17:48:55，`app-debug.apk` 17:49:24，也就是本 commit 的原始碼確實編譯過。
4. **`TimeFormatTest` 是否真的會抓到忽略 locale**：會。把 `withLocale(locale)` 拿掉後，formatter 落到 `Locale.getDefault(FORMAT)`，而測試已把預設設成 `Locale.US`：`ja shouldNotContain "Sep"`、`ko shouldNotContain "Sep"` 會因為「Sep 8, 2026」失敗；`TimeFormat.time(…, Locale.KOREA) shouldContain "오"` 會因為「12:53 AM」失敗；`Locale.JAPAN shouldNotContain "M"` 同樣失敗。只有 `ja shouldContain "2026"` 與兩個 `Locale.US` 斷言在兩種實作下都會通過，它們是對照組，不是判別斷言。`Locale.setDefault(Locale)` 會同時設定 `DISPLAY` 與 `FORMAT` 兩個 category，且 `finally` 還原，不會污染同一 JVM 的其他測試。
5. **`has-english-clock` 的假陽性**：用同一個 regex 掃過 `platform/storage/src/debug/**/*.kt`（`DemoDataRepository.kt`、`DemoLocalisation.kt`）與五個語系的 `strings.xml`（`core/designsystem`、`platform/capture`、`app`）：**零命中**。「Reminder: the retro meeting moved to Thursday 16:00.」「Sounds good — let's keep the meeting short.」「delivered at 14:20」「https://example.invalid/demo/notes/42」「Next meeting is on the 18th」都沒有 AM/PM 或「月份 + 空格 + 數字」；沒有任何示範文案含「May 」加數字。Python 的 `\b` 是 Unicode 詞界，CJK 相鄰不會製造假邊界。動態內容方面：`relativeTime` 的「金」「水」「8/28」、來源「demo.quietinbox.chat」、「Asia/Taipei」都不匹配。剩下唯一無法在唯讀審查裡排除的是 SystemUI 節點（見觀察 2）。
6. **`conversation_ready`**：`has_text "$DEMO_PINNED_TITLE" && ! has_tab "$NAV_INBOX"`。在收件匣上 `has_tab` 為真 → 整體為假；在對話頁上標題在 app bar、底部列不存在 → 為真；在載入中畫面標題是「未命名」→ 為假。三種狀態都判對。`set -e` 下 `conversation_ready && break` 在迴圈裡不觸發 errexit。
7. **`query_shown` 重試 5 次**：`:505-513`，`dump_ui` 失敗直接 `die`，內容不符才重試；成功路徑會在 `:513` 多跑一次 dump，可忽略。
8. **文件**：CHANGELOG `:9` Fixed 條目描述與程式一致（composition locale、`currentLocale()`、`TimeFormatTest`、round 21）；`:17`「212 JVM tests in total」——`build/test-results` 31 個 XML 的 `tests` 加總恰為 212；TEST_MATRIX en `:16` 與 zh `:16` 的 Design system 列含 `TimeFormatTest`（2）；harness bullet en `:74`／zh `:65` 的每一項（IME 關閉、逐字鍵入、ENTER=Done、80 KB 校準、釘選標題＋底部列消失、locale 先於啟動、`--es lang`、English-clock 拒拍）都對得上腳本；CLAUDE.md `:83-85` 新規則、`:132-133` `round{10,…,21}`／`rounds 18–21`；索引第 20 列 `8954af1`＋ENTER，第 21 列新增、修正欄「follow-up commit／後續 commit」是預期狀態。
9. **截圖**：35 張全換，35 對 docs／fastlane `cmp` 逐位元組相同，全部 1080×2400，最小 145,258 bytes（ko-KR `4_activity`，與 brief 說的 145 KB 一致），80 KB 下限留有 1.8 倍餘裕。目視結果見第三節。
10. **lint 宣稱**：本 commit 產出（17:49）的 `lint-results-debug.xml` 只有 `Warning`／`Hint`：app 2、designsystem 22（全部 `PluralsCandidate`）、health 1、settings 1 Hint；沒有 `Error`，與「0 lint errors」一致。

---

## 二、新發現

### Critical

無。

### Important

無。

### Minor

**Minor 1 `TimeFormat` 三個 `locale = Locale.getDefault()` 預設參數沒有任何呼叫者需要，卻讓新規則沒有編譯期保障**
- `core/designsystem/.../Formatting.kt:16,19,22`。全 repo grep 顯示 `TimeFormat.time/date/dateTime` 的呼叫者全部在 Compose 內、全部已明確傳 `locale`；第 21 輪報告「預設參數可保留給非 UI 呼叫者」的前提（有非 UI 呼叫者）並不成立。CLAUDE.md `:83-85` 新增的規則「never `Locale.getDefault()`」目前只靠人記得：下一個寫 `TimeFormat.time(it)` 而漏掉 `locale =` 的呼叫點會靜靜地把第 21 輪的缺陷帶回來，編譯過、lint 過、`TimeFormatTest` 也抓不到（它只測 `TimeFormat` 本身）。
- 修法：拿掉三個預設值（`locale: Locale` 必填），讓編譯器執行這條規則；或至少留下一個 lint／grep 閘門。成本是零——沒有呼叫者需要改。

**Minor 2 `TimeFormatTest` 的常數名與註解寫錯日期**
- `core/designsystem/src/test/.../TimeFormatTest.kt:16`：`private val septemberThird = 1_788_800_000_000L // 2026-09-04 in Asia/Taipei, morning`。實算：1,788,800,000 s = 2026-09-07T16:53:20Z = Asia/Taipei **2026-09-08 00:53:20**。名稱說 3 日、註解說 4 日早上、實際是 8 日凌晨。斷言不受影響（仍是 Sep、仍是 AM／오전、仍含 2026），但下一個想改斷言（例如加 `shouldContain "9/8"`）的人會被誤導。
- 修法：改名為 `septemberEighthMidnight` 之類並修正註解，或換成 `ZonedDateTime.of(2026, 9, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()` 讓意圖自我說明。

**Minor 3 reviews 索引第 21 列說「ja-JP 與 ko-KR 重拍」，本 commit 其實換了 35 張（五個語系）**
- `docs/reviews/README.md:179` 末尾「ja-JP and ko-KR re-shot」、`docs/zh-Hant/reviews/README.md:212`「ja-JP 與 ko-KR 重拍」。diff 的 `--stat` 顯示 en-US、zh-TW、zh-CN 的 14 張也全部替換（例如 en-US `2_conversation` 262,488 → 232,431 bytes），commit 訊息自己寫「All five locales re-shot with the fixed app and the final tool」。與第 20 輪 Minor 4（「四個語系」其實五個）同型。
- 修法：改成「all five locales re-shot (the clock assertion on for the four CJK ones)／五個語系全部重拍（四個 CJK 語系開啟時鐘斷言）」。

**Minor 4 對話頁就緒逾時只 `warn` 就拍，收件匣被存成 `2_conversation.png` 仍會通過所有閘門**
- `tools/demo-screenshots.sh:475` `conversation_ready || warn "the conversation page did not settle after 10 attempts"` → `:476-478` `sleep 2`、`assert_locale_clock`、`shot`。若 `tap_first_list_item` 沒點中（例如列表尚未載入），畫面仍是收件匣：約 280 KB 過 80 KB 下限，沒有 AM/PM 也過時鐘斷言，一張收件匣就以對話頁之名進 repo。同一 commit 對輸入法殘留（`:501`）與查詢不符（`:513`）都改成 `die`，唯獨這裡留 `warn`，與「店面素材必須正確」的方針不一致。
- 修法：改成 `die`。

**Minor 5 CHANGELOG 沒有記錄第 21 輪的工具強化與重拍**
- `CHANGELOG.md:9` 只有 App 缺陷那一條（正確）。第 13–20 輪每一輪都有「Round-N review fixes（`docs/reviews/…`）」條目把工具變更列進去（`:13-15`），第 21 輪的 locale-before-grants、`force-stop`、English-clock 斷言、對話頁就緒條件、輸入法 `die`、五個語系重拍只出現在 TEST_MATRIX 與 commit 訊息。專案一直在追的「docs must match code」在這裡少了一格。
- 修法：在 `:9` 之後補一條「Round-21 review fixes（`docs/reviews/2026-09-06-round21/`）：…」，或把工具部分併進現有那條。

---

## 三、截圖目視結果

| 檔案 | 看到的日期／時間 | 判定 |
| --- | --- | --- |
| ja-JP `1_inbox` | 摘要「15:02 – 18:02 に欠落の可能性があります」；列時間「7:33」「12:35」「昨日」 | ✅ 24 小時制 |
| ja-JP `2_conversation` | 「2026/09/01」「2026/09/04」「今日」；「18:48 · 送信元の時刻」「2:52」「11:07」「7:33」 | ✅ |
| ja-JP `4_activity` | 「2026/08/31 – 2026/09/06 · Asia/Taipei」 | ✅ |
| ja-JP `5_capture` | 「18:01 から」 | ✅ |
| ko-KR `1_inbox` | 摘要「오후 2:59 – 오후 5:59 사이에 누락이 있을 수 있습니다」；列時間「오전 7:33」「오후 12:35」「어제」「금」 | ✅ 오전／오후 |
| ko-KR `2_conversation` | 「2026. 8. 28.」「2026. 9. 1.」「2026. 9. 4.」「오늘」；「오후 6:48 · 소스 시각」「오전 2:52」「오전 11:07」「오전 7:33」 | ✅ |
| ko-KR `4_activity` | 「2026. 8. 31. – 2026. 9. 6. · Asia/Taipei」 | ✅ |
| ko-KR `5_capture` | 「오후 5:58부터」 | ✅ |
| ja-JP `3_search` | 欄位「meeting」、「13 件の結果」、結果列「7:33」「金」「水」「8/28」、無鍵盤、底部列完整 | ✅ |
| ko-KR `3_search` | 欄位「meeting」、「결과 13개」、結果列「오전 7:33」「금」「수」「8/28」、無鍵盤、底部列完整 | ✅（「8/28」見觀察 3） |
| zh-TW `1_inbox` | 「下午2:52 至 下午5:52 可能中斷」「上午7:33」「下午12:35」「昨天」「週五」 | ✅ |
| zh-CN `2_conversation` | 「2026年8月28日」「2026年9月1日」「2026年9月4日」「今天」；「18:48 · 来源时间」「02:52」「07:33」 | ✅ |

沒有任何一張出現 AM／PM 或英文月份；五種語系的對話頁都捲到最新一則、標題在 app bar、沒有載入指示器。

---

## 四、其他觀察（不阻擋）

1. `tools/demo-screenshots.sh:280-283` `has_tab` 在 `dump_ui` 失敗時回 1，`conversation_ready` 的 `! has_tab` 會把「dump 失敗」讀成「底部列不見了」。因為前一個 `has_text` 剛成功 dump 過，實務上幾乎不會發生，但語意上不對；可讓 `has_tab` 在 dump 失敗時回 2 並在 `conversation_ready` 區分。
2. `:138-147` `has-english-clock` 掃 dump 裡**所有**節點，不看 `package` 屬性。uiautomator dump 是否含 SystemUI 狀態列、狀態列時鐘的 `content-desc` 是否帶「PM」，本輪無法在唯讀審查裡驗證；brief 說五個語系都跑綠，表示目前沒有命中。但這是裝置語言／SystemUI 版本相依的：一旦命中，CJK 跑會以「the process locale did not follow the app language」這個誤導訊息中止。強化方式：只掃 `node.get("package") == APP_ID` 的節點（helper 可從 argv 接 `$APP_ID`）。**標記為未驗證**。
3. `Formatting.kt:54` `relativeTime` 的 `ofPattern("M/d", locale)` 是固定 pattern，只有分隔符與月日順序不跟語系走：ko-KR `3_search` 的「8/28」在韓文慣例應是「8. 28.」，ja／zh 慣例是「8月28日」。不在本 diff 範圍，是既有行為；若要改，`DateTimeFormatterBuilder.getLocalizedDateTimePattern` 或 Android 的 `DateFormat.getBestDateTimePattern(locale, "Md")` 都能給出各語系的樣式。
4. `:468-474` `conversation_ready` 每次嘗試做兩次 `uiautomator dump`（`has_text` 一次、`has_tab` 一次），一次 dump 餵兩個 helper 查詢可以減半；最壞情況 10 次 × 2 次 dump ≈ 20–60 秒。
5. `:235-245` `wait_text` 已沒有任何呼叫者（對話頁改用 `conversation_ready` 迴圈），可刪或留作 helper；留著的話註解已正確。
6. `assert_locale_clock` 只認**英文**時鐘：AVD 是 en-US 所以夠用，但若裝置語言是其他語言（例如 zh-TW 裝置拍 ja-JP），程序語言落後會顯示「下午2:52」而不被抓到。既然 App 已改為 composition locale，這條斷言現在真正守的是「`app_locale_is` 失敗只 `warn`、App 整個以裝置語言啟動」這條路，對 en-US AVD 仍有效；文件可註明它假設裝置語言為英文。

---

## 五、翻譯品質（本輪改動的文字）

- CHANGELOG Fixed 條目與 CLAUDE.md 規則的英文精確，「the listener keeps it alive」把因果講清楚。
- zh-Hant TEST_MATRIX `:65` 的長句讀得通；「那是程序語言落後於 App 語言的徵兆」與英文對位。
- zh-Hant 索引第 21 列「CJK 語系出現英文時鐘就拒拍、對話頁要等底部列消失、輸入法殘留即中止」自然；「ja-JP 與 ko-KR 重拍」見 Minor 3。
- `Formatting.kt` 的 KDoc 把 process default 與 composition locale 的差別寫得清楚，值得保留。

---

## 六、建議的修正清單（給下一個 commit）

1. `TimeFormat.time/date/dateTime` 的 `locale` 改為必填（Minor 1）。
2. `TimeFormatTest` 常數改名並修正註解（Minor 2）。
3. 索引第 21 列 en／zh 改為「五個語系全部重拍」（Minor 3）。
4. `conversation_ready` 逾時改 `die`（Minor 4）。
5. CHANGELOG 補第 21 輪工具條目（Minor 5）。
6. 順手：`has-english-clock` 限定 `package == APP_ID`、`has_tab` 區分 dump 失敗、`conversation_ready` 共用一次 dump、刪除或保留 `wait_text`（觀察 1、2、4、5）。
