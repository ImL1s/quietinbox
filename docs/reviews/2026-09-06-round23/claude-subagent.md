# Round 23 獨立審查（Claude subagent）— 第 22 輪修正的迷你再審

- 審查對象：`git diff b813c41..800f65b`（單一 commit `800f65b`，branch `main`，HEAD = `800f65b`，與 brief 一致）。`git status --short` 顯示追蹤中的檔案與 HEAD 完全相同（只有一個未追蹤的 `docs/reviews/2026-09-06-round23/` 目錄），所以以下讀到的工作樹內容就是該 commit 的內容。
- 審查方式：唯讀。逐段讀完整份 diff（`CHANGELOG.md`、`CLAUDE.md`、`Formatting.kt`、`TimeFormatTest.kt`、`tools/demo-screenshots.sh`、reviews 索引 en＋zh、round-22 四份存檔），並讀了 HEAD 的完整 `tools/demo-screenshots.sh`（561 行）、`Formatting.kt`、`TimeFormatTest.kt`、`MainNavigation.kt` 的 chrome 規則、`docs/TEST_MATRIX.md` en＋zh 的 harness 段落；以 grep 找出 `TimeFormat.*`／`Locale.getDefault`／`currentLocale()` 的所有呼叫點，以及腳本內 `has_tab`／`has_text`／`wait_text` 的所有出現處。沒有跑 instrumented test、沒有碰裝置或模擬器、沒有改 repo 任何檔案、沒有跑會改狀態的 git 指令。
- 實際跑過的指令與結果：
  - `python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`，exit 0。
  - `bash -n tools/demo-screenshots.sh` → exit 0。
  - `./gradlew :core:designsystem:testDebugUnitTest --console=plain -q` → exit 0（task 為 up-to-date，沿用 18:16:34 的結果）。
  - **超出 brief 清單、但只動 build 目錄或 scratchpad 的額外驗證（在此揭露）**：同一 task 加 `--rerun` 再跑一次，讓測試真的執行 → exit 0，`TimeFormatTest tests="2" failures="0" errors="0"`（`japaneseAndKoreanDatesCarryNoEnglishMonth`、`timesFollowTheGivenLocaleNotTheProcessDefault`）、`MonogramTest tests="4" failures="0"`；`shellcheck -S warning tools/demo-screenshots.sh` → 0 個 warning；把 heredoc 內的 python helper 抽到 scratchpad 做 `py_compile` → 通過；在 scratchpad 用 JDK 17 跑一段 Java，把測試用的 instant 以 ja／ko／US／zh-TW／zh-CN 五個語系格式化，並模擬拿掉 `withLocale` 的結果（見 §1.3 第 1 點）。
  - `cmp`：`docs/reviews/2026-09-06-round22/{claude-subagent,gemini-3.8-flash-high-agy,brief}.md` 與 `.omc/research/dual-review-round22-{subagent,agy,brief-safe}.md` **逐位元組相同**，存檔是 verbatim。
  - `git diff --stat b813c41..800f65b -- docs/screenshots fastlane` → 空，截圖確實未動。

## Verdict: APPROVE

第 22 輪 subagent 的 Minor 1–5 與觀察 1、2、4、5 全部在 diff 裡有對應修正，而且修得乾淨：`TimeFormat` 三個 formatter 的 `locale` 改為必填、所有呼叫點都已具名傳 `currentLocale()`；測試常數改由 `ZonedDateTime.of(2026, 9, 3, 9, 0, 0, 0, zone)` 建立，名稱、實值、斷言三者一致；對話頁就緒改為單一 dump 的 `conversation-ready` helper、逾時直接 `die`；`has-english-clock` 只看 `package == $APP_ID` 的節點；`has_tab` 在 dump 失敗時回 2；`wait_text` 移除；索引第 21 列回填 `b813c41` 並改為「五個語系全部重拍」；CHANGELOG 補上第 21／22 輪的工具條目；CLAUDE.md 的輪次 glob 與「rounds 18–22」更新；round-22 四份存檔 verbatim。沒有 Critical、沒有 Important、沒有新的 Minor；下面五個觀察都不需要再開一輪。

統計：Critical 0 · Important 0 · Minor 0 · 觀察 5。

---

## 一、第 22 輪發現逐項核對

### 1.1 Claude subagent 報告（Minor 1–5、觀察 1–6）

| 第 22 輪發現 | 修了？ | 證據（HEAD 行號） |
| --- | --- | --- |
| **Minor 1** `TimeFormat` 三個 `locale = Locale.getDefault()` 預設值沒人用、卻讓規則沒有編譯期保障 | ✅ | `core/designsystem/.../Formatting.kt:20,23,26` 三個簽名都是 `zone: ZoneId = ZoneId.systemDefault(), locale: Locale`（`locale` 無預設）。全 repo grep：`Locale.getDefault` 在 main 原始碼只剩 `Formatting.kt:16,34` 兩處 KDoc 文字；程式碼裡零出現。所有呼叫者（`Formatting.kt:55,69`、`InboxScreen.kt:224-225`、`HealthScreen.kt:237-238,332`、`AnalyticsScreen.kt:662-663`、`ConversationScreen.kt:378,388`）都寫 `locale = currentLocale()`（或 `relativeTime` 內先取到的 `locale` 變數）；測試三處位置參數傳 `zone, Locale.X`。新 KDoc `:14-18` 說明「required on purpose … a default of `Locale.getDefault()` would quietly bring back the process-default bug」，與程式一致 |
| **Minor 2** `TimeFormatTest` 常數名與註解寫錯日期 | ✅ | `TimeFormatTest.kt:16` `septemberThirdMorning = ZonedDateTime.of(2026, 9, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()`；JDK 實算 = `1788397200000` = `2026-09-03T09:00+08:00[Asia/Taipei]`，名稱、實值一致，舊的錯誤註解已移除 |
| **Minor 3** 索引第 21 列說「ja-JP 與 ko-KR 重拍」，其實五個語系 | ✅ | `docs/reviews/README.md:32` 末尾「all five locales re-shot, the clock assertion on for the four CJK ones」，修正欄 `b813c41`；zh 版 `docs/zh-Hant/reviews/README.md:30`「五個語系全部重拍，四個 CJK 語系開啟時鐘斷言」、`b813c41`。`git log` 確認 `b813c41` 就是第 21 輪的修正 commit |
| **Minor 4** 對話頁就緒逾時只 `warn` 就拍 | ✅ | `tools/demo-screenshots.sh:493` `conversation_ready \|\| die "the conversation page did not settle after 10 attempts (a store screenshot must be the conversation, not the inbox)"`；與 `:519`（輸入法）、`:531`（查詢）同為 `die`，三個閘門口徑一致 |
| **Minor 5** CHANGELOG 沒有第 21 輪的工具條目 | ✅ | `CHANGELOG.md:12`「Round-21/22 review fixes (`docs/reviews/2026-09-06-round21/`, `round22/`)」逐項核對：locale 先於啟動並 `force-stop`（腳本 `:427-448`）、非英文語系四張截圖前的 English-clock 拒拍且「the app's own nodes only; it assumes an English device language」（`:142-146`、`:301-311`）、對話頁等釘選標題＋底部列消失且失敗即中止（`:485-493`）、輸入法殘留即失敗（`:517-520`）、查詢重試（`:527-531`）、`TimeFormat` 編譯期必填（`Formatting.kt:20-26`）、五個語系重拍（`b813c41`）。每一句都有程式對應，沒有 docs ahead of code |
| **觀察 1** `has_tab` 在 dump 失敗時回 1，`! has_tab` 會把「dump 失敗」讀成「底部列不見了」 | ✅ | `:294-299` `has_tab() { dump_ui \|\| return 2; … }`，註解明寫「a caller must not read a failed dump as "the bar is gone"」。目前沒有任何呼叫者（見觀察 A） |
| **觀察 2** `has-english-clock` 掃所有節點，不看 `package` | ✅ | `:142` `package = sys.argv[2]`、`:145-146` `if (node.get("package") or "") != package: continue`；`:308` 呼叫時傳 `"$APP_ID"`；`:303` 註解補上「It assumes an English device language (the project AVDs)」 |
| **觀察 3** `relativeTime` 的固定 `M/d` pattern | —（本來就說不在範圍） | `Formatting.kt:58` 未改，符合第 22 輪「既有行為、不在 diff 範圍」的定位 |
| **觀察 4** `conversation_ready` 每次嘗試 dump 兩次 | ✅ | `:485-488` `dump_ui \|\| return 1` 後只呼叫一次 `python3 "$HELPER" conversation-ready "$DEMO_PINNED_TITLE" "$NAV_INBOX"`；helper `:153-172` 在同一棵樹上同時判「標題出現」與「底部 15% 沒有收件匣分頁」。註解 `:484`「One UI dump per attempt」 |
| **觀察 5** `wait_text` 沒有呼叫者 | ✅ | diff 刪除 `wait_text` 定義；HEAD 全檔 grep `wait_text` 零出現 |
| **觀察 6** 時鐘斷言只認英文、假設裝置語言為英文 | ✅（以文件化處理） | `:303` 與 CHANGELOG `:12` 都明寫此假設；行為未改，符合第 22 輪「文件可註明」的建議 |

### 1.2 agy 報告

第 22 輪 agy 為 APPROVE、0 發現；其觀察 1（索引第 21 列佔位字）已在本 commit 回填 `b813c41`（en `:32`、zh `:30`）。

### 1.3 brief 提出的三個問題

1. **`TimeFormat.time/date/dateTime` 的 locale 必填、每個呼叫者傳 `currentLocale()`、KDoc 是否準確、兩個測試是否仍能分辨**：
   - 必填 ✓、呼叫者 ✓（見 Minor 1 列）。KDoc 準確：它說 UI 傳 `[currentLocale]`，而 `relativeTime` 傳的是先取好的 `locale` 變數（`:46,55`）、其餘呼叫點直接傳 `currentLocale()`，語意相同；`[currentLocale]` 連結指向同檔同套件的頂層函式，可解析。`zone` 保留預設而 `locale` 必填、且 `locale` 排在 `zone` 之後，代表跳過 `zone` 的呼叫一定要具名 `locale =`——所有呼叫點正是這樣寫的。
   - 分辨力（JDK 17，程序預設設成 `Locale.US`，instant = 2026-09-03 09:00 Asia/Taipei）：

     | 語系 | `date` (MEDIUM) | `time` (SHORT) |
     | --- | --- | --- |
     | ja_JP | `2026/09/03` | `9:00` |
     | ko_KR | `2026. 9. 3.` | `오전 9:00` |
     | en_US | `Sep 3, 2026` | `9:00 AM` |
     | zh_TW／zh_CN | `2026年9月3日` | `上午9:00` |
     | **拿掉 `withLocale`（落到預設 US）** | `Sep 3, 2026` | `9:00 AM` |

     結論：`ja shouldNotContain "Sep"`、`ko shouldNotContain "Sep"`、`ko time shouldContain "오"`、`ja time shouldNotContain "M"` 四個判別斷言在補丁版全部失敗；`ja shouldContain "2026"` 與兩個 `Locale.US` 斷言是對照組。兩個測試都仍能分辨「用指定語系」與「用程序預設」；把時刻改到 09:00 沒有削弱任何斷言（韓文早上是 오전，日文 24 小時制無 M，美式有 AM）。`--rerun` 實跑通過。
2. **`tools/demo-screenshots.sh`**：
   - `conversation-ready`（`:153-172`）：先掃一次求最大 bottom 當螢幕高度，再一次走訪：`text/content-desc == title` 記 `title_seen`；`== inbox_tab` 且 `top >= 0.85 × height` 立即回 1；最後 `0 if title_seen else 1`。語意 = 標題出現 **且** 底部 15% 沒有收件匣分頁，與舊的 `has_text && ! has_tab` 完全等價，但只用一份 dump。三種狀態：收件匣（標題在第一列、分頁在底部）→ 1；載入中（標題是「未命名」）→ 1；對話頁（標題在 app bar、手機版無底部列，`MainNavigation.kt:80` `showChrome = current !is ConversationRoute \|\| wide`）→ 0。判對。
   - shell 端 `conversation_ready()`（`:485-488`）：`dump_ui \|\| return 1` 把 dump 失敗當「未就緒」→ 迴圈重試 → 10 次後 `die`。這是正確的失敗方向（不再可能把 dump 失敗讀成「底部列不見了」）。`set -e` 下 `conversation_ready && break` 與 `conversation_ready \|\| die` 都不會誤觸 errexit。
   - 逾時 → `die` ✓（`:493`）。
   - `has-english-clock "$APP_ID"` 過濾 `node.package` ✓（`:142-146`、`:308`）。uiautomator dump 的每個 `<node>` 都有 `package` 屬性，Compose 節點的值是 applicationId（`dev.quietinbox.app.debug`），與 `APP_ID` 同一常數。
   - `has_tab` 回 2 ✓（`:297`）。**brief 問「是否還有呼叫者把 2 當成 gone」：沒有——`has_tab` 在 HEAD 已經沒有任何呼叫者**（grep 只剩 `:294-299` 的定義），所以新的回傳契約目前沒有被任何路徑執行到（見觀察 A）。
   - `wait_text` 移除、無殘留呼叫 ✓。
   - `bash -n`、`shellcheck -S warning`、helper `py_compile` 全部乾淨。
3. **文件**：CHANGELOG 第 21／22 輪條目 ✓（逐句對得上程式）；索引第 21 列 `b813c41`＋五個語系 ✓（en＋zh）；第 22 列 en `:33`／zh `:31` 修正欄「follow-up commit／後續 commit」是預期狀態，內容（0 Critical、0 Important、5 Minor、6 觀察、合併 APPROVE WITH MINOR FIXES）與存檔報告一致；CLAUDE.md `:132-133` `round{10,…,22}`、`rounds 18–22` ✓；`docs/zh-Hant/` 沒有 CHANGELOG 鏡像，不需同步。

### 1.4 宣稱的驗證

- **`./gradlew :app:assembleDebug test lint` 綠**：build 產物時間戳都在原始碼修改（`Formatting.kt`／`TimeFormatTest.kt` 18:16:31）之後——`TimeFormat.class` 與 designsystem 測試 XML 18:16:34、`app-debug.apk` 18:16:36、`lint-results-debug.xml` 18:16:47；commit 時間 18:22:52。`build/test-results` 31 個 XML 的 `tests` 加總 = **212** ✓；14 個模組的 lint 報告只有 `Warning`／`Hint`，沒有 `Error` ✓（我讀報告，沒有重跑 lint）。
- **一個要誠實說明的點**：四個 feature 模組的 `*ScreenKt.class` 時間戳仍是 17:49:21（上一個 commit 的 build），也就是那次 build 沒有重新編譯它們。移除 `locale` 預設值不改 JVM 簽名（`time$default` 仍存在，因 `zone` 仍有預設），既有 bytecode 以 mask 呼叫仍然有效，所以 APK 在執行期沒有問題；而**原始碼層面能否對著新簽名編譯，我是靠 grep 證明的**（每個呼叫點都具名傳 `locale`，沒有任何一處依賴被移除的預設），不是靠一次重新編譯。這個結論很穩，但要說清楚證據類型。
- **工具在 ja-JP／ko-KR 跑綠**：無裝置，無法驗證。**截圖未變**：已驗證（`docs/screenshots` 與 `fastlane` 在此 diff 範圍為空）。

---

## 二、新發現

### Critical

無。

### Important

無。

### Minor

無。

---

## 三、其他觀察（不阻擋，回填 SHA 那個 commit 可順手處理）

**A. `has_tab`、`has_text` 的 shell wrapper 與 python `has-tab` 指令現在都是死碼**
- `tools/demo-screenshots.sh:121-136`（python `has-tab`）、`:294-299`（`has_tab`）、`:313-316`（`has_text`）：HEAD 全檔只剩定義，沒有呼叫者——`conversation_ready` 改走 `conversation-ready`，`query_shown` 直接呼叫 helper 的 `has-text`。本 commit 以「no callers left」刪掉 `wait_text`，但同一條理由下的另外兩個 wrapper 留著，而且 `has_tab` 剛獲得的「回 2」契約與註解（觀察 1 的修法）目前沒有任何路徑執行到。與第 22 輪觀察 5 同級：刪掉，或留著就讓註解說明它們是給日後的 helper 用。

**B. `has-english-clock` 的 package 過濾沒有正向對照**
- `:142-146` 的過濾讓一次跑綠的 CJK run 分不出「畫面沒有英文時鐘」與「過濾把 App 節點全丟掉了」；而這條斷言自加入以來從未在真實 run 裡觸發過（第 21 輪是靠目視發現缺陷，斷言在修好之後才加）。實務風險很低——`$APP_ID` 同時驅動 `am start -n`（`:31,453`）與 demo 廣播，值不對會在任何截圖之前就失敗——所以不列 Minor。便宜的對照：en-US 目前整個跳過（`:305`），改成在 en-US 的 `1_inbox` 前**斷言偵測器有命中**（英文收件匣的列時間是「7:33 AM」這類），就能證明過濾後 App 節點仍在、regex 仍會咬。這與 CLAUDE.md「每個 X must not happen 的測試都有 negative control」的精神一致。

**C. TEST_MATRIX 的 harness 段落落後 CHANGELOG 兩個子句**
- `docs/TEST_MATRIX.md:74`、`docs/zh-Hant/TEST_MATRIX.md:65` 仍寫「the conversation shot waits until the pinned title is on screen and the bottom bar is gone」與「refuses … if an English AM/PM time or month appears」，沒說（1）等不到就整個 run 失敗、（2）只看 App 自己的節點並假設裝置語言為英文；CHANGELOG `:12` 兩者都有。是文件**落後**程式（安全方向），各補半句即可。

**D. 索引第 22 列「all fixed」的措辭**
- `docs/reviews/README.md:33`／zh `:31`：「6 observations）→ … all fixed in the follow-up commit（…六項…）」。括號列了六項，而第 22 輪的觀察 3（`M/d` 固定 pattern）與 6（只認英文時鐘）是刻意不改（6 以文件化處理）。回填 SHA 時建議寫成「the five Minors and observations 1, 2, 4, 5 fixed」，避免讀者以為六個觀察全改了。與第 18 列「every Important and the actionable Minors fixed」的寫法對齊。

**E. 成功路徑多一次 dump；工具是手機版面專用**
- `:489-493` 迴圈 `break` 後 `conversation_ready \|\| die` 會再 dump 一次（與 `query_shown` 同型，第 22 輪已說可忽略），用旗標可省。另外 `conversation-ready` 的「底部列消失」規則、`tap-tab` 的底部 15% 規則、`MIN_SHOT_BYTES` 的校準都只對手機版面成立；寬視窗（`Foldable_Test` 展開，`MainNavigation.kt:141` 走 `NavigationRail`）下 `tap_tab` 本來就會失敗、就緒檢查退化成「標題出現」。這是**既有**限制、不是本 commit 的退化；CLAUDE.md `:104` 同時列了兩個 AVD，腳本表頭加一句「phone layout only」可省一次誤會。

---

## 四、翻譯品質（本輪改動的文字）

- `Formatting.kt:14-18` 新 KDoc 把「為什麼必填」講得直接；「would quietly bring back the process-default bug the moment a call site forgot it」是好句子。
- CHANGELOG `:12` 一句長但每個子句都可對應程式；「(the app's own nodes only; it assumes an English device language)」精確。
- zh-Hant 索引第 21／22 列與英文對位：「locale 改為編譯期必填、常數改由日期建立、就緒逾時直接失敗、時鐘偵測只看 App 自己的節點、每次就緒檢查只 dump 一次、移除無人使用的 helper」自然、無漏項。
- `tools/demo-screenshots.sh:294-295`、`:301-303`、`:484` 三段新註解都準確描述了程式行為。

---

## 五、建議（非必要）

1. 回填索引第 22 列 SHA 時順手：觀察 A（刪 `has_tab`／`has_text`／python `has-tab` 或註明保留原因）、觀察 D（措辭）、觀察 C（TEST_MATRIX 兩個子句）。
2. 觀察 B 的 en-US 正向對照值得加，成本是幾行 shell；加了之後「時鐘斷言真的會咬」就有了證據。
