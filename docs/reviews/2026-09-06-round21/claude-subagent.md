# Round 21 獨立審查（Claude subagent）— 第 20 輪修正的迷你再審

- 審查對象：`git diff c90e75f..8954af1`（單一 commit `8954af1`，branch `main`）。
- 審查方式：唯讀。逐段讀完 diff（`tools/demo-screenshots.sh`、`DemoData.kt`、`DemoDataRepository.kt`、`DemoReceiver.kt`、CHANGELOG／CLAUDE.md／TEST_MATRIX en+zh／reviews 索引 en+zh／ja changelog 4／`.gitignore`）；對照第 20 輪兩份報告（`docs/reviews/2026-09-06-round20/{claude-subagent,gemini-3.8-flash-high-agy}.md`）逐項核對；讀了 `SearchScreen.kt` 的搜尋欄、`SearchViewModel.kt` 的即時搜尋、`ConversationScreen.kt` 的標題與載入狀態、`MainNavigation.kt` 的 chrome 規則、`Formatting.kt` 的時間格式化、兩個 `DemoModule`、`SettingsViewModel.seedDemo()`、`DemoDataTest` 的 `seed(now)` 呼叫。
- 目視過的 PNG（在 scratchpad 縮圖後閱讀，repo 檔案未動）：五個語系的 `2_conversation.png` 與 `3_search.png`；ja-JP 與 ko-KR 的 `1_inbox`／`4_activity`／`5_capture`／`6_settings`／`7_inbox_dark`；zh-TW 與 zh-CN 的 `1_inbox`；以及從 `c90e75f` 取出的 ja-JP `1_inbox`／`2_conversation`／`4_activity`／`5_capture` 與 ko-KR `1_inbox`／`2_conversation` 舊版做對照。其餘只檢查尺寸（35 張全部 1080×2400）與大小，並以 `cmp` 確認 35 對 docs／fastlane 副本逐張位元組相同。
- 實際跑過的指令：`python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`；`bash -n tools/demo-screenshots.sh` → exit 0；`./gradlew :core:designsystem:testDebugUnitTest :platform:storage:compileDebugAndroidTestKotlin --console=plain -q` → exit 0（兩個 task 皆 up-to-date：`MonogramTest` 的 JUnit XML `tests="4" failures="0" errors="0"`，產出時間 16:12 晚於測試原始碼 15:39；`DemoDataTest*.class` 產出 17:02:39 晚於 `DemoData.kt`／`DemoDataRepository.kt` 的 17:02:35，所以是對本 commit 的原始碼編譯過的結果）。另用 bash 實測 `${SEARCH_QUERY:$i:1}` 在 `set -u` 下、`if ime_shown; then …; ime_shown && warn; fi` 在 `set -e` 下、以及 `ime_shown` 的 regex 對九種 dumpsys 字樣的行為。沒有跑 lint、沒有跑 instrumented test、沒有碰裝置或模擬器、沒有改 repo 任何檔案。

## Verdict: REQUEST CHANGES

第 20 輪的 Important I-1、Minor 1–4、觀察 2／3／7／8 與 agy 的四個 Minor 全部有修，而且修得對：zh-CN 的對話截圖現在是真正的「林小美 Mia Lin」對話（258,636 bytes，舊版 32,360）；每張截圖有 80 KB 下限；搜尋字串一次一個字鍵入、以 ENTER 收起輸入法、dump 失敗與內容不符分開回報；trap 移到 helper 之後；`.pyc` 移除並加入 `.gitignore`；文件的插句、輪次 glob、索引佔位字都補齊。腳本與 Kotlin 的變更本身沒有問題。

但這個 commit 重拍的 35 張圖裡，**ja-JP 與 ko-KR 各有四張（`1_inbox`、`2_conversation`、`4_activity`、`5_capture`；docs 與 fastlane 各一份，共 16 個檔案）把日期與時間用英文格式顯示在日文／韓文介面裡**——「Sep 3, 2026」「2:52 AM」「2:22 PM – 5:22 PM」——而 `c90e75f` 的同一批圖是「2026/09/01」「2:52」「13:07 – 16:07」（ja）與「2026. 9. 1.」「오전 2:52」（ko）。這是本 commit 帶進來的退化，而且根因在 App：`TimeFormat` 用 `Locale.getDefault()`，它不跟著 per-app 語言走；本 App 的 process 因為 NotificationListenerService 幾乎永遠活著，所以在 Android 13+ 於系統設定切換 App 語言的真實使用者也會看到同樣的混合語言。這比第 20 輪的單張載入畫面更廣（兩個語系、八張店面素材）、有程式缺陷、且是相對前一 commit 的退化，因此給 REQUEST CHANGES：ja-JP／ko-KR 的截圖在 App 修正並重拍前不得上傳 Play，其餘部分可以保留。

統計：Critical 0 · Important 1（App 在地化缺陷 + 店面素材退化）· Minor 4 · 觀察 7。

---

## 一、第 20 輪發現逐項核對

### 1.1 Claude subagent 報告

| 第 20 輪發現 | 修了？ | 證據 |
| --- | --- | --- |
| **Important I-1** zh-CN `2_conversation.png` 是載入中畫面；工具固定等待後就拍、沒有就緒檢查 | ✅ | `tools/demo-screenshots.sh:208-216` 新增 `wait_text`（輪詢 `has_text`，預設 10 次），`:409-410` 在 `tap_first_list_item` 之後 `wait_text "$DEMO_PINNED_TITLE" 10 \|\| warn`、再 `sleep 2` 才 `shot`；`:40` `MIN_SHOT_BYTES=80000`，`:309-315` 在 `shot()` 內量 `wc -c`、低於下限就 `die`。目視五個語系的 `2_conversation.png`：標題分別為「林小美 Mia Lin」×3（en／zh-TW／zh-CN）、「林 美咲 Misaki Hayashi」、「김미아 Mia Kim」，來源列「demo.quietinbox.chat」，訊息本文各語言正確，五張都捲到最新一則（「今天／Today／今日／오늘」在最下方），沒有任何載入指示器。zh-CN 那張 32,360 → 258,636 bytes。35 張最小 147,084 bytes（ko-KR `4_activity`），下限 80 KB 留有約 1.8 倍餘裕；深色收件匣 276,123–316,168 bytes 遠高於下限。docs／fastlane 35 對 `cmp` 相同 |
| Minor 1 TEST_MATRIX 的新句子插在半句中間（en 與 zh） | ✅ | `docs/TEST_MATRIX.md:85-90` 原句「installs the debug APK, wipes app data, …」復原，輸入法／80 KB／釘選標題／locale／`--es lang` 的說明接在「owner's own notifications into the debug vault.」之後；`docs/zh-Hant/TEST_MATRIX.md:114-117` 同樣結構（一個多餘的 ASCII 空格，見觀察 6） |
| Minor 2 `tools/__pycache__/*.pyc` 被 commit | ✅ | diff 顯示 `tools/__pycache__/check-strings.cpython-314.pyc` 刪除；`git ls-files \| grep -i pycache` 為空；`.gitignore:22-24` 加 `__pycache__/` 與 `*.py[cod]` |
| Minor 3 搜尋截圖前無條件按 BACK，失敗訊息把畫面切走說成輸入法組字；`has_text` 內 dump 失敗也被說成組字 | ✅（索引文字與程式不符，見新 Minor 2） | `:429` 改為 `KEYCODE_ENTER`，不再按 BACK；`:431` 用 `ime_shown` 檢查輸入法是否仍顯示，是則再等 2 秒並警告；`:433` `dump_ui \|\| die "uiautomator could not dump the search screen"` 與 `:434-435` 的內容不符訊息分開，後者也把「the page was left」列為可能原因 |
| Minor 4 CHANGELOG／索引說「四個語系重拍」 | ✅ | `CHANGELOG.md:22`「all five locales are re-shot without a keyboard in frame」；`docs/reviews/README.md:103` 與 zh 版 `:130` 第 19 列「all five locales re-shot／五個語系全部重拍」 |
| 觀察 2 EXIT trap 引用還沒定義的 `imes_on` | ✅ | trap 從 `:46` 移到 `:194`，在 `imes_off`／`imes_on` 之後、第一個 `die`（`:341`）之前；`:192-193` 註解說明原因 |
| 觀察 3 `default_input_method` 為空時不警告 | ✅ | `:176-179` 印出「no default input method is set; …」再 `return 0` |
| 觀察 5 五張對話圖捲動位置不一致 | ✅（順帶） | 五張 `2_conversation.png` 現在都停在最新一則；`wait_text` + `sleep 2` 給了列表捲到底的時間 |
| 觀察 6 ja `changelogs/4.txt` 仍寫 アクティビティ | ✅（有意的決定） | 改為「活動分析」，與 App 內分頁一致；這是 versionCode 4 已發布的說明，改動只影響 fastlane 的歷史副本 |
| 觀察 7 索引第 19 列佔位字 | ✅ | en `:103`／zh `:130` 第 19 列改為 `c90e75f`；第 20 列新增，最後一欄「follow-up commit／後續 commit」是本 commit 無法知道自己 hash 的預期狀態 |
| 觀察 8 CLAUDE.md 的輪次 glob | ✅ | `CLAUDE.md:46-47`「`round{10,…,20}/`」「rounds 18–20 reviewed the localisation」 |

未處理但本來就不要求的：觀察 1（`:170-171` 的「Android keeps at least one input method enabled」註解未改）、觀察 4（SIGKILL 後的手動還原指令未寫進文件）。

### 1.2 agy 報告

| 第 20 輪發現 | 修了？ | 證據 |
| --- | --- | --- |
| Minor 1 TEST_MATRIX 插句 | ✅ | 同 1.1 Minor 1 |
| Minor 2 `__pycache__` | ✅ | 同 1.1 Minor 2 |
| Minor 3 CLAUDE.md glob | ✅ | 同 1.1 觀察 8 |
| Minor 4 索引第 19 列佔位字 | ✅ | 同 1.1 觀察 7 |

### 1.3 brief 提出的問題

1. **`${SEARCH_QUERY:$i:1}` 在 `set -u` 下**：實測 `bash -c 'set -euo pipefail; SEARCH_QUERY="meeting"; for ((i = 0; i < ${#SEARCH_QUERY}; i++)); do printf "[%s]" "${SEARCH_QUERY:$i:1}"; done'` → `[m][e][e][t][i][n][g]`，正常；`SEARCH_QUERY` 與 `i` 都有值，子字串展開不觸發 `set -u`。`sleep 0.3` 在 macOS 的 BSD sleep 與 GNU sleep 都接受小數。
2. **ENTER 在搜尋欄做什麼**：`SearchScreen.kt:69-86` 是 material3 `TextField`、`singleLine = true`、沒有 `keyboardOptions`／`keyboardActions`。Compose 對單行欄位把 `ImeAction.Default` 換成 `Done`，ENTER 觸發預設的 Done 處理：收起輸入法、不插入換行、不呼叫任何 callback。搜尋本身是即時的（`SearchViewModel.kt:59` `debounce(250)`，`:67` `setQuery` 每個字元都更新），所以 ENTER 沒有「送出」也沒有副作用；查詢與頁面都留著。與腳本 `:427-428` 註解描述的行為一致；只是它不是「搜尋動作」，見新 Minor 4。
3. **80 KB 下限**：最小真實截圖 147,084 bytes（ko-KR `4_activity`），深色收件匣 276–316 KB，載入畫面 32 KB；下限落在兩群中間、離最小值約 1.8 倍。它綁定在 1080×2400；換較小解析度的 AVD 會誤判（觀察 3）。
4. **`wait_text` 成本**：每次迭代是一次 `uiautomator dump`（模擬器上約 1–3 秒）+ `adb pull` + python + `sleep 1`，參數 10 是「次數」不是秒數，最壞約 20–40 秒，只在對話沒載入時才付；可接受，但 `:409` 的警告文字「within 10 s」低估了（觀察 4）。
5. **Hilt 在 release 是否仍編譯**：release 端是 `platform/storage/src/release/…/DemoModule.kt:15` 的 `@Provides fun demoData(): DemoData = NoDemoData`（不是 `@Binds`），`NoDemoData` 在 `DemoData.kt:20` 覆寫兩個參數的 `seed`，介面變更對它是原始碼相容的；debug 端 `@Binds abstract fun bindDemoData(impl: DemoDataRepository): DemoData` 不受簽名影響。`SettingsViewModel.kt:120` 仍呼叫 `demoData.seed()`，走預設 `locale = null` → `DemoDataRepository.kt:69` 用 configuration；`DemoDataTest.kt:81/107/136` 的 `seed(now)` 用預設值編譯（見上方 mtime 證據）。`DemoReceiver.kt:57` 用 `Locale.forLanguageTag`，垃圾字串不會丟例外，只會得到 `und` → `DemoLocalisation.forLocale` 回空 map；`DemoLocalisation.kt:16-23` 對 `zh-CN`（無 script、country 在 `SIMPLIFIED_REGIONS`）與 `zh-TW` 的判斷對 `forLanguageTag` 與 configuration 兩種來源一致。
6. **文件**：TEST_MATRIX en／zh 是一句；CHANGELOG `:23` 第 20 輪段落與程式一致（ENTER、80 KB、釘選標題、trap、locale 輪詢、`--es lang`）；CLAUDE.md `:36`「`--es lang ja-JP` names the demo's language」；索引第 19 列 `c90e75f`、第 20 列新增；ja changelog 4「活動分析」；`.gitignore` 兩條。
7. **截圖**：35 張全換、35 對相同、全部 1080×2400、最小 147,084 bytes；五張對話頁在地化且捲到底；五張搜尋頁欄位「meeting」、各 13 筆結果（en「13 results」、zh-TW「13 筆結果」、zh-CN「13 条结果」、ja「13 件の結果」、ko「결과 13개」）、沒有鍵盤、底部導覽列完整。**但 ja／ko 的日期時間格式是英文，見 Important I-1。**

---

## 二、新發現

### Important

**I-1 ja-JP 與 ko-KR 的 `1_inbox`／`2_conversation`／`4_activity`／`5_capture` 把日期與時間用英文格式顯示在日文／韓文介面裡；根因是 `TimeFormat` 用 `Locale.getDefault()`，它不跟著 per-app 語言走**

證據（新版 `8954af1` vs 舊版 `c90e75f`，docs 與 fastlane 副本相同）：

| 檔案 | `8954af1` 畫面上的文字 | `c90e75f` 同一處 |
| --- | --- | --- |
| ja-JP `1_inbox.png` 標題下摘要 | 「2:22 PM – 5:22 PM に欠落の可能性があります」 | 「13:07 – 16:07 に欠落の可能性があります」 |
| ja-JP `2_conversation.png` 日期分隔與訊息時間 | 「Sep 3, 2026」「Sep 4, 2026」「2:52 AM · 送信元の時刻」「6:34 PM」「1:29 PM」 | 「2026/09/01」「2026/09/03」「2:52 · 送信元の時刻」「18:34」「13:29」 |
| ja-JP `4_activity.png` 期間 | 「Aug 31, 2026 – Sep 6, 2026 · Asia/Taipei」 | 「2026/08/31 – 2026/09/06 · Asia/Taipei」 |
| ja-JP `5_capture.png` 連線時間 | 「5:21 PM から」 | 「16:06 から」 |
| ko-KR `1_inbox.png` 摘要 | 「2:26 PM – 5:26 PM 사이에 누락이 있을 수 있습니다」 | 「오후 1:09 – 오후 4:09 사이에 …」 |
| ko-KR `2_conversation.png` | 「Sep 3, 2026」「2:52 AM · 소스 시각」「6:34 PM」 | 「2026. 9. 1.」「오전 2:52 · 소스 시각」「오후 6:34」 |
| ko-KR `4_activity.png` | 「Aug 31, 2026 – Sep 6, 2026」 | （同一格式化路徑，未另取舊圖） |
| ko-KR `5_capture.png` | 「5:25 PM부터」 | （同上） |

同一批圖裡不受影響的：`3_search.png`（列表時間「7:33」「金」「오전 7:33」「금」都是對的）、`6_settings.png`（沒有時間）、`7_inbox_dark.png`（ja「14:22 – 17:22」、ko「오후 2:26 – 오후 5:26」，**正確**）。zh-TW／zh-CN／en-US 五張都正確（zh-TW「上午2:52」「2026年9月3日」「下午2:17 至下午5:17」；zh-CN「02:52」「2026年9月3日」）。

程式對照：

- `core/designsystem/.../Formatting.kt:16-23` `TimeFormat.time / dateTime / date` 的 `locale` 預設是 `Locale.getDefault()`——process 層級的預設語言。
- 同檔 `:29-33` `relativeTime` 從 `LocalConfiguration.current.locales[0]` 讀，註解寫明「Read through the composition (observable on configuration change)」——這就是為什麼 `3_search` 的列表時間對、其他頁錯。
- 走 `Locale.getDefault()` 的呼叫點：`ConversationScreen.kt:377`、`:387`（訊息時間）、`Formatting.kt:56`（`dayLabel` 的日期分隔）、`InboxScreen.kt:223-224`（收件匣摘要的缺口時間）、`HealthScreen.kt:236-237`、`:331`（擷取頁的缺口與連線時間）、`AnalyticsScreen.kt:661-662`（活動頁期間）；另 `AnalyticsScreen.kt:722` 的 `String.format(Locale.getDefault(), "%.1f", …)` 同源（數字格式差異較不顯眼）。
- 判定性的證據：兩個語系的 `7_inbox_dark.png` 都對。它是 `:457` `cmd uimode night yes` 之後拍的，夜間模式是 process 層級的 configuration change，框架在那一刻把 process 的預設語言同步成 ja／ko；在那之前 Activity 的資源已是 ja／ko（字串全對），但 `Locale.getDefault()` 還是裝置語言 en。這是 per-app 語言以 Activity 層級 override 套到一個已經活著的 process 時的已知行為。

為什麼是本 commit 的退化、以及為什麼 zh 沒事（**假設，需在裝置驗證**）：`:360` `cmd notification allow_listener` 會讓 NotificationManagerService 綁定監聽器、把剛被 `pm clear` 殺掉的 process 用裝置語言重新拉起來；`:365` 的 `set-app-locales` 若落在 process 起來之後，只會以 Activity 層級套用。本 commit 新增的 `:369-373` 輪詢在第一次檢查失敗（`pm clear` 的非同步重設剛好落在 `:365` 之後）時會 `sleep 1` 再 `set-app-locales` 一次——此時監聽器拉起的 process 幾乎必然已經活著。`c90e75f` 沒有這個重設，四個 CJK 語系的 process 在那次跑都是先套語言再啟動（所以舊圖全對）；這次 zh-TW／zh-CN 兩次跑贏了競賽、ja／ko 兩次輸了。競賽的細節無法在唯讀審查裡證明，但「process 預設語言 = 裝置語言、Activity 資源 = per-app 語言、夜間模式後同步」這三點都寫在像素上。

真實使用者的影響：本 App 的 process 因為 NotificationListenerService 幾乎永遠活著。Android 13+ 的使用者在系統設定「App 語言」把 QuietInbox 切成日文，會拿到日文字串加上系統語言（例如英文）的日期時間，直到下一次 process 層級的 configuration change 或 process 被殺；這與第 18–20 輪一直在修的在地化目標直接衝突。

建議修法（三層都要）：

1. **App（根因）**：讓 composable 呼叫點把 composition 的 locale 傳進 `TimeFormat`，做法與 `relativeTime` 一致——例如在 `Formatting.kt` 加一個 `@Composable fun currentLocale() = LocalConfiguration.current.locales.let { if (it.isEmpty) Locale.ENGLISH else it[0] }`，`dayLabel` 與上列七個呼叫點改傳 `locale = currentLocale()`；`AnalyticsScreen.kt:722` 同樣。`TimeFormat` 的預設參數可以保留給非 UI 呼叫者。加一個 JVM 測試證明 `TimeFormat.date(…, locale = Locale.JAPAN)` 不含英文月份，並把「App 活著時切換 App 語言、打開對話頁」列進裝置 walkthrough。
2. **工具（保險）**：`set-app-locales` 移到任何可能拉起 process 的指令之前（至少在 `:360` `allow_listener` 之前），輪詢確認生效後、`:380` `am start` 之前 `am force-stop "$APP_ID"`，讓 process 一定帶著 override 啟動；並為四個 CJK 語系在 `1_inbox`、`2_conversation`、`5_capture` 前加內容斷言：畫面上不得有任何節點文字含「 AM」「 PM」（zh-TW 用上午／下午、zh-CN 用 24 小時制、ja 用 24 小時制、ko 用 오전／오후）。現在的 helper 只有全等比對，需要加一個子字串或 regex 指令。
3. **素材**：App 修正後重拍 ja-JP 與 ko-KR（docs + fastlane 各 7 張），逐張目視時明確檢查日期、時間、數字格式，不只檢查字串；重拍前這兩個語系的截圖不得上傳 Play。commit 訊息說 35 張「inspected one by one」，但這 8 張帶著可見的混合語言通過了檢視——檢查清單需要把格式列進去。

### Minor

**Minor 1 `wait_text "$DEMO_PINNED_TITLE"` 在收件匣上就會成立，「對話頁就緒」的檢查可能被收件匣自己滿足**
- `tools/demo-screenshots.sh:406-411`：`tap_first_list_item` 就是用 `tap_text "$DEMO_PINNED_TITLE"` 在收件匣找到那個標題節點來點的，所以收件匣本身就有一個文字全等於釘選標題的節點。若點擊沒生效或導覽慢於 `tap_text`／`tap_first_list_item` 的兩秒固定等待，`wait_text` 會在收件匣上立刻回 0，`sleep 2` 後拍下的 `2_conversation.png` 是收件匣，大小約 280 KB，也通過 80 KB 下限。這次觀察到的失敗型態（導覽已發生、對話還在載入、標題是「未命名对话」）確實被擋下，但註解 `:407` 說的「in the app bar」不是程式檢查的東西。
- 修法：`MainNavigation.kt:80` 在手機上對 `ConversationRoute` 隱藏底部導覽列，所以「釘選標題存在 且 `$NAV_INBOX` 分頁節點不存在」是乾淨的就緒條件；或改用 helper 的 bounds 只接受畫面上方 14% 內的標題節點。

**Minor 2 reviews 索引第 20 列描述的機制不在程式裡**
- `docs/reviews/README.md:104` 與 `docs/zh-Hant/reviews/README.md:131`：「BACK only when an input method is shown／只在輸入法顯示時按 BACK」。實際程式 `:429` 按的是 `KEYCODE_ENTER`，搜尋截圖前完全不按 BACK；`:431` 在輸入法仍顯示時只警告。CHANGELOG `:23` 與 TEST_MATRIX 寫的是 ENTER，正確；索引是唯一不一致的地方。CLAUDE.md 的規則是文件不得跑在程式前面（或描述另一個機制）。
- 修法：改成「ENTER dismisses the input method; one still visible is reported／以 ENTER 收起輸入法，仍顯示時回報」。

**Minor 3 `:431` 輸入法仍顯示時只警告，照樣拍搜尋圖**
- 情境：ENTER 之後語音面板（或任何輸入法）沒有收起；`ime_shown` 兩次都真 → `warn`，然後 `:433-436` 的 dump 與 `has-text` 都通過（欄位有「meeting」）、80 KB 下限也通過（面板讓畫面更複雜），`3_search.png` 帶著面板進 repo。這正是第 19 輪的失敗型態，這次守門降級成警告。
- 修法：改成 `die`，或至少把這張標記為不可用於店面（例如另存 `3_search.rejected.png` 並讓腳本以非零退出）。

**Minor 4 「the field's search action (ENTER)」的措辭與程式不符**
- `docs/TEST_MATRIX.md:90`「dismisses it with the field's search action (ENTER)」、`docs/zh-Hant/TEST_MATRIX.md:117`「以搜尋欄的 ENTER 動作收起輸入法」、腳本 `:427`「ENTER is the field's search action」。`SearchScreen.kt:69-86` 沒有設定 `ImeAction.Search`；單行欄位的 IME 動作是 Done，ENTER 執行的是 Compose 預設的 Done 處理（收起輸入法）。行為描述正確，名稱不對；若日後有人真的把欄位改成 `ImeAction.Search` 而沒有 `onSearch`，Compose 對 Search 的預設處理是「什麼都不做」，輸入法就不會收起——這句話會把人引到錯的方向。
- 修法：改寫成「ENTER 觸發單行欄位的 Done 動作，收起輸入法；搜尋是即時的，沒有送出」。

---

## 三、其他觀察（不阻擋）

1. `:370` 與 `:374` `shell cmd locale get-app-locales … | tr -d '\r' | grep -q "$LOCALE"` 是腳本自己在 `:199-200` 警告過的 `pipefail` + `grep -q` 提早退出型態；`get-app-locales` 只輸出一行，寫端在 grep 退出前就已寫完，實務上安全，但和 `ime_shown` 的寫法不一致。可比照先存進變數再比對。
2. `:369-373` 在 API < 33 的裝置上會多花 5 秒（五次失敗的 `get-app-locales`）才印出 `:375` 的警告；`:365-366` 已經警告過一次，這裡的迴圈可在第一次 `set-app-locales` 失敗時直接跳過。
3. `MIN_SHOT_BYTES` 綁定在 1080×2400：在較小解析度的 AVD 上，正常畫面可能低於 80 KB，`:313` 的訊息會說「the screen was not ready」誤導操作者。可依 `wm size` 的像素數換算，或在註解與 CLAUDE.md 寫明只對 `QuietInbox_Phone` 校準。
4. `wait_text` 的第二個參數是次數，`:409` 的警告寫「within 10 s」；每次迭代含一次 uiautomator dump，實際是 20–40 秒。改成「after 10 attempts」即可。
5. 搜尋結果從 `c90e75f` 的 19 筆變成本 commit 的 13 筆，五個語系一致。`DemoDataRepository.kt:394-399` 的訊息時間窗是相對 `now` 的（`earliest = now - 29 天`、`latest = now - 10 分鐘`、逐日隨機時刻），跑的時間不同就有不同數量的樣本落在窗內；不是缺陷，但代表每次重拍店面截圖的數字都會變，比較舊圖時要知道這點。同理 ja／ko `4_activity` 的「識別済み 34 → 39 件」。
6. `docs/zh-Hant/TEST_MATRIX.md:117`「複製進 debug 資料庫。 Android 13 以上」句號後多一個 ASCII 空格。
7. `DemoReceiver.kt:78` 的 `EXTRA_LANG` 插在 `OP_SEED` 與 `OP_CLEAR` 之間，把兩個 op 常數拆開；純排版。

---

## 四、翻譯品質（本輪改動的文字）

- CHANGELOG 第 20 輪段落與 TEST_MATRIX en 的長句讀得通，但 en 的 TEST_MATRIX 那句已經接近 90 個英文字，可拆成兩句。
- zh-Hant TEST_MATRIX：「等輸入法穩定後一次一個字鍵入查詢、以搜尋欄的 ENTER 動作收起輸入法」自然；「App 只在 per-app 語言請求確實生效後才啟動」與英文對位。
- zh-Hant 索引第 20 列「對話頁等釘選標題出現、每張截圖有大小下限、只在輸入法顯示時按 BACK…」——最後一項與程式不符（Minor 2），其餘對。
- ja `changelogs/4.txt`「活動分析」與 `5.txt`／`full_description.txt` 的「活動」一致。

---

## 五、建議的修正清單（給下一個 commit）

1. `TimeFormat` 的 composable 呼叫點改讀 composition locale（`ConversationScreen.kt:377,387`、`Formatting.kt:56`、`InboxScreen.kt:223-224`、`HealthScreen.kt:236-237,331`、`AnalyticsScreen.kt:661-662,722`），補一個 JVM 測試；腳本把 `set-app-locales` 提前到 `allow_listener` 之前並在 `am start` 前 `force-stop`，CJK 語系加「沒有 AM／PM」的內容斷言；重拍 ja-JP 與 ko-KR 共 14 張，逐張檢查日期時間格式（I-1）。
2. `wait_text` 的對話頁就緒條件加上「`$NAV_INBOX` 分頁不存在」或標題位置限制（Minor 1）。
3. 索引第 20 列 en／zh 改成 ENTER 的描述（Minor 2）。
4. `:431` 輸入法仍顯示改為 `die`（Minor 3）。
5. TEST_MATRIX en／zh 與腳本註解把「search action」改成 Done 動作（Minor 4）。
6. 順手：`:370/:374` 的 grep 型態、`wait_text` 警告文字、`MIN_SHOT_BYTES` 的解析度假設、zh TEST_MATRIX 的多餘空格（觀察 1、3、4、6）。
