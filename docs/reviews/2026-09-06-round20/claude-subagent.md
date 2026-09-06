# Round 20 獨立審查（Claude subagent）— 第 19 輪修正的迷你再審

- 審查對象：`git diff ee48710..c90e75f`（單一 commit `c90e75f`，branch `main`）。
- 審查方式：唯讀。逐段讀完 diff（`tools/demo-screenshots.sh`、`tools/check-strings.py`、`MonogramTest.kt`、zh-Hant／ja／ko 字串、`DemoLocalisation.kt`、ja／ko 商店文案、changelog 5／whatsnew／`release-notes.json`、CHANGELOG／TEST_MATRIX en+zh／CLAUDE.md／reviews 索引 en+zh）；對照第 19 輪兩份報告（`docs/reviews/2026-09-06-round19/{claude-subagent,gemini-3.8-flash-high-agy}.md`）逐項核對；讀了 `Avatars.kt` 的 `monogram()`、`ConversationScreen.kt` 的載入狀態、`MainNavigation.kt` 的 back stack、`build-logic` 三個 convention plugin；在 scratchpad 複本對 `check-strings.py` 做 8 種突變；用 bash 實測 EXIT trap 在 `set -e` 下的行為。
- 目視過的 PNG：五個語系的 `3_search.png`、zh-CN／en-US／zh-TW 的 `2_conversation.png`、zh-CN 的 `1_inbox.png`，以及從 `ee48710` 取出的 zh-CN 舊版 `2_conversation.png`；其餘 26 張只檢查尺寸（全部 1080×2400）與檔案大小，並以 `cmp` 確認 35 張 docs 與 fastlane 副本逐張位元組相同。
- 實際跑過的指令：`python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`；`bash -n tools/demo-screenshots.sh` → exit 0；`./gradlew :core:designsystem:testDebugUnitTest --console=plain -q` → exit 0，`build/test-results/testDebugUnitTest/TEST-…MonogramTest.xml` 顯示 `tests="4" failures="0" errors="0"`。沒有跑 lint（不在允許清單）、沒有跑 instrumented test、沒有碰裝置或模擬器、沒有改 repo 任何檔案。

## Verdict: APPROVE WITH MINOR FIXES

第 19 輪的 Important I-1、Minor 1–10 與 agy 的兩個 nit 全部有修，而且修得對：搜尋截圖的根因（輸入法組字）以「開 App 前停用預設輸入法、拍照前 `has_text` 硬性斷言」處理，五個語系的 `3_search.png` 現在都顯示「meeting」＋ 19 筆結果、沒有鍵盤；字串與商店文案的殘留全部清掉；parity gate 的兩個缺口以突變證實補上；`monogram()` 有四個真的跑起來的 JVM 測試。

但這個 commit 在重拍全部 35 張圖時，帶進一個第 19 輪同類、md5 檢查原理上抓不到的商店素材缺陷：**zh-CN 的 `2_conversation.png`（docs 與 fastlane 兩份相同）是對話頁的載入中畫面**——標題「未命名对话」、來源一欄空白、畫面中央一個轉圈、沒有任何訊息；`ee48710` 的舊版是正確的「林小美 Mia Lin」對話。這是唯一的 Important。與第 19 輪對同類缺陷的評級一致，維持 APPROVE WITH MINOR FIXES；**但 zh-CN 的手機截圖在 `2_conversation.png` 重拍並目視確認前不得上傳 Play**。

統計：Critical 0 · Important 1（商店素材／截圖工具）· Minor 4 · 觀察 9。

---

## 一、第 19 輪發現逐項核對

### 1.1 Claude subagent 報告

| 第 19 輪發現 | 修了？ | 證據 |
| --- | --- | --- |
| **Important I-1** 非英文語系的 `3_search.png` 全是「無結果」（假名／諺文／拼音組字），工具從未斷言查詢字串 | ✅ | `tools/demo-screenshots.sh:170-186` `imes_off`／`imes_on`；`:333` 在 `am start` 之前呼叫 `imes_off`；`:46` EXIT trap 先 `imes_on` 再刪暫存目錄；`:373` `KEYCODE_BACK` 收起輸入法面板；`:376` `has_text "$SEARCH_QUERY" \|\| die`。`has_text` 是節點文字**全等**比對（helper `:119-124`），結果列的長句不會誤中，只有搜尋欄本身能通過。目視五張 `3_search.png`：en-US「19 results」、zh-TW「19 筆結果」、zh-CN「19 条结果」、ja「19 件の結果」、ko「결과 19개」，搜尋欄都是「meeting」，都沒有鍵盤，底部導覽列完整。docs 與 fastlane 五張 `cmp` 相同 |
| Minor 1 ko `analytics_quiet_formula` 殘留「원본 앱」 | ✅ | `values-ko/strings_analytics.xml:67` 改「소스 앱이 그날 알림을 게시하지 않았을 뿐」；檔頭註解 `:4` 同步。全 repo 剩下的「원본」只有 `values-ko/strings.xml:79` `conv_open_source_fallback`「원본 알림」與 ko 商店「원본 알림을 절대 건드리지 않습니다」——兩處都是第 18 輪議定保留的「原始通知」用法 |
| Minor 2 zh-Hant 三行沒跟上 zh-Hans | ✅ | `values-b+zh+Hant/strings.xml:78` `conv_open_source_body`「可能會在來源 App 中把訊息標為已讀」、`:181` `health_no_gaps`「本次執行期間沒有記錄到中斷」、`:236` `section_reminders`「QuietInbox 的提醒」。grep 全 repo 已無「自己的提醒」「本次連線」 |
| Minor 3 ja 商店 アクティビティ／サイレントモード | ✅（一處歷史檔未動，見觀察 6） | `ja-JP/full_description.txt:6`「活動の分析」、`:19`「サイレント モード」；`ja-JP/changelogs/5.txt`、`whatsnew-ja-JP`、`release-notes.json` 三處都改「活動画面」且三者逐字相同。grep 全 repo 已無「取り除く」「サイレントモード」（無空格）；「アクティビティ」只剩 `ja-JP/changelogs/4.txt`（0.1.0 的已發布說明） |
| Minor 4 ko 商店「원본에」 | ✅ | `ko-KR/full_description.txt:11`「소스 앱에 읽음 표시가 되지 않고」，與 App 內 `ob_scope_point_2` 一致 |
| Minor 5 `values-tv`／`values-car` 被當成語系 | ✅ | `check-strings.py:20` `LOCALE_DIR` 加 `(?!(?:tv\|car\|desk\|watch\|vrheadset\|night\|land\|port)$)`，`:55` 仍用 `fullmatch`。突變：`values-tv`／`values-car` 各放一條字串 → 兩個模組的 locale 清單不變、`OK`；對照組 `values-fil`（三字母語言）與 `values-en-rGB` 各放一條 → `FAIL: 380 error(s)`，仍被當語系檢查，沒有誤傷。`values-night-v31` 放一條 → 忽略（`fullmatch` 擋掉） |
| Minor 6 帶旗標的佔位符（`%.1f`、`%,d`）閘門看不到 | ✅ | `:18` `PLACEHOLDER = %(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdf]\|%%`。突變：預設目錄放 `%.1f`、四個語系沒有 → 4 個 `placeholders differ … ['%.1f'] vs []`；`%,d` 同 → 4 錯；ko 把 `%1$d` 改 `%1$s` → 1 錯；ko 改 `%1$.1f` → 1 錯（`['%1$d'] vs ['%1$.1f']`）。現況清點：兩個模組所有 XML 只用 `%1$d`／`%2$d`／`%3$d`／`%1$s`／`%2$s`／`%3$s`／`%%` 七種形式，新 regex 全數涵蓋，沒有任何現有佔位符落在 `[sdf]` 之外；這次擴充是預防性的 |
| Minor 7 `monogram()` 沒測試 | ✅ | `core/designsystem/src/test/…/MonogramTest.kt`（新檔，4 個 `@Test`，kotest `shouldBe`）。實跑 `:core:designsystem:testDebugUnitTest` → JUnit XML `tests="4" failures="0" errors="0"`。Runner 來源：`quietinbox.android.library.gradle.kts:36-37` 給每個 Android library `testImplementation junit` + `kotest-assertions-core`，Android unit test 預設走 JUnit 4，不需要 vintage engine；`useJUnitPlatform()` 只在 `quietinbox.kotlin.jvm` 純 JVM 模組設定，設計系統模組不受影響。案例對照 `Avatars.kt:64-77`：漢字取 `codePointAt(0)`、假名／諺文走 `SINGLE_GLYPH_SCRIPTS`、拉丁兩詞取首字母大寫、單詞取前兩字、空白給 `?`——四組斷言都對應到一條分支 |
| Minor 8 reviews 索引第 18 列佔位字 | ✅（第 19 列出現同樣的佔位字，見觀察 7） | `docs/reviews/README.md:29` 與 `docs/zh-Hant/reviews/README.md:27` 第 18 列改為「fixed in `ee48710`」／「在 `ee48710` 修正」，最後一欄 `ee48710`；第 19 列新增，內容與兩份第 19 輪報告相符 |
| Minor 9 示範帳單金額不寫實 | ✅ | `DemoLocalisation.kt:139` ja「12,400 円」、`:199` ko「124,000원」；zh-Hans `:77`「1,240 元」未動。三者作為一個月水電瓦斯都在合理區間 |
| Minor 10 CHANGELOG／TEST_MATRIX／CLAUDE.md 同步 | ✅（措辭見 Minor 1、觀察 8、9） | CHANGELOG `[Unreleased]` 加第 19 輪一段並改「`MonogramTest` (4); 210 JVM tests」；TEST_MATRIX en `:16`／zh `:16` 各加「Design system／設計系統」列；CLAUDE.md `:130`「rounds 18–19 reviewed the localisation」 |

### 1.2 agy 報告

| 第 19 輪發現 | 修了？ | 證據 |
| --- | --- | --- |
| Nit 1 ko `analytics_quiet_formula` 원본 앱 | ✅ | 同 Minor 1 |
| Nit 2 ko 商店「원본에」 | ✅ | 同 Minor 4 |

### 1.3 brief 提出的問題

1. **BACK 在沒有輸入法顯示時**：`MainNavigation.kt:83-86` 切到頂層分頁時 back stack 變成 `[Inbox, Search]`，`:94` 的 `onBack` 是 `removeLastOrNull()`，所以 BACK 會回到收件匣；收件匣沒有任何節點文字全等「meeting」（列表預覽是長句），`:376` 會 `die`——確實是設計上的大聲失敗，但錯誤訊息把原因歸給「輸入法組字」，見 Minor 3。
2. **只有一個輸入法的裝置**：依 AOSP `InputMethodManagerService.setInputMethodEnabledLocked` 的讀法，停用最後一個已啟用的輸入法會成功、選取值被重設為空（未在裝置驗證）。結果是沒有輸入法：`input text` 的字母不經組字直接進欄位，查詢是對的；接著 `:373` 的 BACK 沒有面板可收，退回收件匣，斷言失敗。可接受（沒有假成功），但 `:176` 的警告文字描述的是另一種失敗，見觀察 1。
3. **trap 在 `imes_off` 沒跑過時**：`imes_on` 用 `${IME_DISABLED:-0}` 守門，`IME_DISABLED` 只在 `ime disable` 成功後設 1，所以沒跑過或停用失敗都不會誤還原；正確。trap 字串在 `:46` 定義、函式在 `:180` 才存在的順序問題見觀察 2。

---

## 二、新發現

### Important

**I-1 zh-CN `2_conversation.png` 是載入中畫面，會出現在 Play 的第二張圖**

- 檔案：`docs/screenshots/phone/zh-CN/2_conversation.png` 與 `fastlane/metadata/android/zh-CN/images/phoneScreenshots/2_conversation.png`（位元組相同，32,360 bytes；其他四個語系的對話圖 185,971–278,545 bytes；`ee48710` 的 zh-CN 舊版 220,139 bytes）。
- 畫面內容：頂欄標題「未命名对话」、副標「来源：」後面空白、沒有頭像與釘選／封存按鈕、畫面中央一個 loading 指示器、沒有任何訊息。對照程式：`ConversationScreen.kt:135` 在 `state.conversation == null` 時標題退回 `analytics_unknown_conversation`（zh-Hans 即「未命名对话」）、`:133` 沒有 conversation 就不畫 `MonogramAvatar`、`:173` 顯示 `LoadingScreen`。這是對話尚未載入的暫態，不是任何一個示範對話。
- 舊版正確：`ee48710` 的同一張是「林小美 Mia Lin／来源：demo.quietinbox.chat」加五則訊息。commit 訊息與 CHANGELOG 只宣稱重拍搜尋圖，但 diff 裡 35 張全換，這張是重拍時帶進來的退化。
- 工具側原因（事實部分）：`tools/demo-screenshots.sh:360-361` `tap_first_list_item` 之後直接 `shot "2_conversation"`，中間只有固定等待（`tap_text` 內 `sleep 1`、`tap_first_list_item` 再 `sleep 1`、`shot` 內 `sleep 1`），沒有任何「畫面已就緒」的斷言——正是第 19 輪對搜尋圖指出的同一種缺口，這次只補在搜尋圖上。為什麼這一次載入超過三秒，我沒有裝置證據，不下結論。
- 為什麼既有檢查抓不到：七張 md5 互不相同（載入畫面當然跟其他六張不同），docs 與 fastlane 相同（因為是同一張複製過去）。一個「每張不得小於 N KB」的下限（這張 32 KB，其餘最小 141 KB）或每張一個內容斷言都會抓到。
- 建議修法：
  1. `shot "2_conversation"` 之前輪詢（例如最多 10 秒、每秒一次）`has_text "$DEMO_PINNED_TITLE"`——標題點擊成功時頂欄的標題節點文字就是釘選對話的名字；但 `first-list-item` 與固定座標兩條 fallback 沒有可斷言的標題，所以另外加一道通用守門：斷言該語系的 `analytics_unknown_conversation` 字串**不在**畫面上，或者對每一張 `shot` 加檔案大小下限，兩者擇一或並用。
  2. 重拍 zh-CN（至少 `2_conversation.png`），並在 commit 前逐張目視五個語系的 35 張；本輪的 commit 訊息說「re-shot」但沒有人看過這一張。
  3. 在 zh-CN 重拍完成前，Play 的 zh-CN 手機截圖不要上傳。

### Minor

**Minor 1 TEST_MATRIX 的新句子插在半句中間（en 與 zh 都是）**
- `docs/TEST_MATRIX.md:70`：「… `<out-dir>` installs the On Android 13+ the keyboard follows the app language, … unless the field shows the query.」下一行才接「debug APK, wipes app data, …」；原句「installs the debug APK」被硬生生切開。
- `docs/zh-Hant/TEST_MATRIX.md:63`：「會安裝 debug APK、清除 app Android 13 以上鍵盤會跟著 App 語言走，…拒絕拍搜尋頁。」下一行接「資料、授予監聽器…」，同樣的切法。
- 修法：把新句子移到該 bullet 的句尾（「…owner's own notifications into the debug vault.」之後），兩份文件同步。

**Minor 2 Python 快取被 commit 進 repo**
- `tools/__pycache__/check-strings.cpython-314.pyc`（新增，`git ls-files` 可見）；`.gitignore` 沒有 `__pycache__/`。
- 修法：`git rm --cached` 該檔，`.gitignore` 加 `__pycache__/`。

**Minor 3 `:373` 的 BACK 無條件按下，失敗時的訊息會誤導**
- 情境：沒有輸入法面板在畫面上時（觀察 1 的單輸入法裝置、或語音輸入法沒有升起面板），BACK 落到 App，back stack `[Inbox, Search]` 被 pop 回收件匣（`MainNavigation.kt:84-86`、`:94`）；`:376` 隨即 `die "… an input method composed the keystrokes"`，但實際上查詢是對的、是畫面被切走了。操作者會去查輸入法而不是查 BACK。
- 同一行還有第二種混淆：`has_text` 內 `dump_ui \|\| return 1`，uiautomator 偶發的「could not get idle state」也會回 1，同樣被說成輸入法組字。
- 修法：按 BACK 前先確認輸入法真的顯示中（`dumpsys input_method` 的欄位名隨 API 版本不同——`mInputShown`、`mImeWindowVis`、較新版本的 `isInputShown`——請以目標 AVD 的輸出為準），沒有顯示就不按；`has_text` 失敗時先重試一次 dump，並把「dump 失敗」與「欄位內容不符」分成兩條訊息。

**Minor 4 CHANGELOG／索引說「四個語系重拍」，diff 是五個語系 35 張全換**
- `CHANGELOG.md:15`「those four are re-shot」、`docs/reviews/README.md:30` 與 zh 版第 19 列「four locales re-shot／四個語系重拍」；實際 `git diff --stat` 是 en-US 七張也全部更新（en-US 搜尋結果數也從舊版的 14 變成 19）。brief 的說法（五個語系）才對。
- 修法：改寫成「全部五個語系重拍」，或說明 en-US 是順帶重拍。

---

## 三、翻譯品質（本輪改動的字串）

- **zh-Hant**：`conv_open_source_body`「在那邊打開聊天室可能會在來源 App 中把訊息標為已讀；在這裡看副本則永遠不會。」——與英文「may mark messages as read on the source side」對位，去掉了「對方看到已讀」的窄化；`health_no_gaps`「本次執行期間沒有記錄到中斷。」對應「in this session」，比「本次連線」準確（gap 可以來自暫停，不只斷線）；`section_reminders`「QuietInbox 的提醒」與 zh-Hans 一致，比「自己的提醒」清楚。三行都自然。
- **ja**：「活動の分析」與 App 內分頁「活動」一致；「サイレント モード」的半形空格寫法與 Google 日文 UI 的片假名複合詞風格一致，也與 App 內 `reminders_desc` 相同；「活動画面が回り続けなくなりました」通順。`strings_analytics.xml` 檔頭改「活動」正確。
- **ko**：「사본을 읽어도 소스 앱에 읽음 표시가 되지 않고 답장도 하지 않습니다.」自然、與 `ob_scope_point_2` 用同一個詞；「소스 앱이 그날 알림을 게시하지 않았을 뿐일 수도 있습니다」語體與同檔其他句子一致（합니다체）。
- **示範金額**：ja「12,400 円」作為一個月光熱費、ko「124,000원」作為一個月공과금，都是可信的家庭帳單；數字與單位的空格習慣（日文有空格、韓文無空格）維持全表一致。zh-Hans「1,240 元」未動，仍可信。
- 殘留掃描：「원본」只剩「원본 알림」兩處（議定保留）；「取り除く」0 處；「サイレントモード」（無空格）0 處；「アクティビティ」只剩 `ja-JP/changelogs/4.txt`（見觀察 6）；zh-Hans「数据库」只剩 `delete_everything_step_database`「删除保险库数据库」（對應英文 "vault database"，正確）。

---

## 四、其他觀察（不阻擋，建議順手處理）

1. `tools/demo-screenshots.sh:168` 註解「Android keeps at least one input method enabled」與 `:176` 的警告「the search query may be composed by its layout」：依 AOSP 原始碼讀法，最後一個輸入法也能被停用（選取值重設為空），此時字母不會被組字、查詢是對的，之後出問題的是 BACK（Minor 3）。未在裝置驗證；建議把註解與警告改成描述真正的後果（「沒有第二個輸入法時 BACK 會離開搜尋頁、搜尋圖會被拒絕」）。
2. `:46` 的 EXIT trap 引用 `:180` 才定義的 `imes_on`。實測 `bash -c 'set -euo pipefail; trap "imes_on; echo cleanup-ran" EXIT; exit 1'` → `imes_on: command not found`，而且 **`cleanup-ran` 沒印**：在 `set -e` 下 trap 清單第一個命令失敗會讓後面的 `rm -rf "$WORK_DIR"` 跳過。今天 `:47-179` 之間沒有任何會結束腳本的路徑，所以不會發生；但把 `trap` 移到兩個函式定義之後（或把 `imes_on` 提前）可以消掉這個地雷。
3. `:174` 若 `default_input_method` 是空或 `null` 就直接 return、不警告；這種裝置上搜尋圖的行為與觀察 1 相同，操作者卻不會收到任何提示。可以在這裡也印一行 warn。
4. 腳本被 `SIGKILL`（不是 Ctrl-C）時 trap 不會跑，AVD 會留在「Gboard 停用、語音輸入法為預設」的狀態；`tools/` 的說明或 TEST_MATRIX 可以提一句手動還原指令（`adb shell ime enable <id>` + `ime set <id>`）。
5. en-US `2_conversation.png` 捲到最新一則訊息（底部），zh-TW 的同一張停在對話開頭（上方的「已驗證來源 ID／已保存 24 則」chips 與 8 月 8 日的訊息）；五個語系的第二張圖捲動位置不一致，商店頁面上會看起來像不同畫面。純外觀，若要統一可在拍照前多等一次捲動完成或明確捲到底。
6. `fastlane/metadata/android/ja-JP/changelogs/4.txt`（0.1.0 的說明，已隨 versionCode 4 發布）仍寫「アクティビティ分析」；Play 只顯示最新版本的說明，留著是歷史紀錄，改掉是為了一致，兩者都可以，但要是有意的決定。
7. `docs/reviews/README.md:30` 與 zh 版第 19 列的「all fixed in the follow-up commit／後續 commit」是同一種佔位字（commit 無法知道自己的 hash），下一個 commit 應改成 `c90e75f`，與第 18 列這輪的處理相同。
8. `CLAUDE.md:129` 的路徑 glob 仍是 `round{10,…,18}/`，同一句後半已改成「rounds 18–19」；應為 `round{10,…,19}/`。
9. brief 寫 en-US 搜尋圖是「14」筆：那是 `ee48710` 之前的舊圖；本 commit 的 en-US `3_search.png` 是 19 筆，五個語系一致。不是缺陷，只是 brief 的數字過時。
10. CHANGELOG 的「210 JVM tests」是 206 + 4 的算術；本輪只跑了設計系統模組（4/4 通過），沒有跑全套，也沒有跑 lint（不在允許清單），「lint 0 errors」未經本輪驗證。

---

## 五、建議的修正清單（給下一個 commit）

1. 重拍 zh-CN `2_conversation.png`（docs + fastlane），逐張目視 35 張；`shot "2_conversation"` 前加就緒斷言（輪詢 `has_text "$DEMO_PINNED_TITLE"`＋「`analytics_unknown_conversation` 不在畫面上」或每張檔案大小下限）（I-1）。
2. TEST_MATRIX en／zh 把插錯位置的句子移到 bullet 尾（Minor 1）。
3. `git rm --cached tools/__pycache__/…pyc`，`.gitignore` 加 `__pycache__/`（Minor 2）。
4. BACK 前檢查輸入法是否顯示；`has_text` 失敗訊息分開「dump 失敗」與「內容不符」（Minor 3）。
5. CHANGELOG／索引改成「五個語系重拍」；索引第 19 列填 `c90e75f`；CLAUDE.md glob 改 `round{10,…,19}`（Minor 4、觀察 7、8）。
