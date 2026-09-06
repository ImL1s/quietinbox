# Review Round 20（第 19 輪在地化與截圖修正之迷你再審）— 審查報告

**審查範圍**：`git diff ee48710..c90e75f`（單一 commit `c90e75f`，branch `main`）  
**審查模式**：唯讀審查（READ-ONLY Review），無任何程式碼修改、無 git 狀態變更、無裝置與儀器化測試。  
**驗證指令與結果**：
1. `python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`（exit 0）。
2. `bash -n tools/demo-screenshots.sh` → 語法檢查通過（exit 0）。
3. `./gradlew :core:designsystem:testDebugUnitTest --console=plain` → 4 個測試全數通過（100% successful，耗時 0.028s）。
4. 全 repo JVM 單元測試 → **210 個測試全數通過，0 failures, 0 errors**。
5. `./gradlew :core:designsystem:lintDebug` → 0 lint errors。

---

## 審查結論 (Verdict)

### **APPROVE WITH MINOR FIXES**

Commit `c90e75f` 完整且精準地解決了 Round 19 兩位審查者提出的所有問題：
1. **重要缺陷 I-1 徹底解決**：`tools/demo-screenshots.sh` 透過在啟動前停用預設輸入法、打字後以 `KEYCODE_BACK` 收合語音面板、並以 `has_text "$SEARCH_QUERY" || die` 進行防禦性斷言，徹底杜絕了 Gboard 語言版面吞字組字導致搜尋截圖空白的退化問題。五語系共 35 張截圖全數重新拍攝，所有非英文語系（含 0.1.0 帶有缺陷的 zh-TW）之 `3_search.png` 均呈現完整的 19 筆「meeting」搜尋結果。
2. **用詞殘留 100% 清零**：韓文 `strings_analytics.xml` 與 Play 商店說明之「소스 앱」、繁中三處對齊、日文「活動画面／活動の分析／サイレント モード」、示範水電費金額（12,400 円 / 124,000원）全數修正完畢；全 repo 0 殘留「원본 앱」（除過往 review 紀錄外）、0 殘留「取り除く」、ja 商店 0 殘留「アクティビティ」、zh-Hans 亦無分裂。
3. **字串工具保護網補強**：`tools/check-strings.py` 正確排除了 `tv/car` 等樣式目錄，並完整涵蓋了 `%.1f` 與 `%,d` 等帶旗標佔位符。
4. **設計系統補齊單元測試**：新增 `MonogramTest.kt`（4 個測試），透過 convention plugin 順利繼承 JUnit 4 與 Kotest runner，使 JVM 測試總數正式達到 210 個。

本輪無任何 Critical 或 Important 缺陷，僅有 4 項屬於文檔排版插句錯位、git 誤入 pyc 編譯檔與索引 commit 佔位字之 **Minor** 項目，不影響程式功能與發布安全性。

---

## 一、第 19 輪發現逐項驗證表 (Round-19 Verification Table)

| Round-19 發現項目 | 狀態 | 具體證據與檔案行號 |
| :--- | :---: | :--- |
| **I-1 [Important] 搜尋截圖無結果與 Gboard 組字吞字**<br>（日韓中搜尋頁因鍵盤版面導致 query 變亂碼、無結果；`tools/demo-screenshots.sh` 缺斷言） | **已修復** | 1. [`tools/demo-screenshots.sh:172-186`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L172-L186)：新增 `imes_off`（App 啟動前停用預設輸入法）與 `imes_on`（EXIT trap 還原）。<br>2. [`tools/demo-screenshots.sh:373-376`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L373-L376)：輸入後送 `KEYCODE_BACK` 收起語音面板，並以 `has_text "$SEARCH_QUERY" \|\| die` 嚴格斷言搜尋框內容。<br>3. 實測 OCR 與 MD5 驗證：五個語系之 `3_search.png` 均正確顯示「meeting」與搜尋結果（en-US、ja-JP、ko-KR、zh-CN、zh-TW 全數為 19 筆結果，無鍵盤遮擋），`docs/` 與 `fastlane/` 兩處 35 張截圖逐一完全一致。 |
| **Minor 1（agy Nit 1）ko「원본 앱」漏網** | **已修復** | [`core/designsystem/src/main/res/values-ko/strings_analytics.xml:4, 67`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-ko/strings_analytics.xml#L4)：`analytics_quiet_formula` 內文與檔頭註解均由「원본 앱」改為「소스 앱」。全 repo 活躍字串 0 殘留。 |
| **Minor 2 zh-Hant 三處未同步 zh-Hans** | **已修復** | [`core/designsystem/src/main/res/values-b+zh+Hant/strings.xml`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh+Hant/strings.xml)：<br>• `:78` `conv_open_source_body`：改為「...可能會在來源 App 中把訊息標為已讀；在這裡看副本則永遠不會。」<br>• `:181` `health_no_gaps`：改為「本次執行期間沒有記錄到中斷。」<br>• `:236` `section_reminders`：改為「QuietInbox 的提醒」。 |
| **Minor 3 ja 商店與記錄仍寫「アクティビティ」** | **已修復** | 1. [`fastlane/metadata/android/ja-JP/full_description.txt:6`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ja-JP/full_description.txt#L6)：改為「活動の分析」。<br>2. [`fastlane/metadata/android/ja-JP/changelogs/5.txt`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ja-JP/changelogs/5.txt)、[`fastlane/whatsnew/whatsnew-ja-JP`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/whatsnew/whatsnew-ja-JP)、[`fastlane/release-notes.json:16`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/release-notes.json#L16)：均改為「活動画面」。<br>3. [`values-ja/strings_analytics.xml:3`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-ja/strings_analytics.xml#L3)：註解改為「活動」画面。 |
| **Minor 4 ja 商店「サイレントモード」半形空格** | **已修復** | [`fastlane/metadata/android/ja-JP/full_description.txt:19`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ja-JP/full_description.txt#L19)：改為「サイレント モード」，與系統標準譯法完全一致。 |
| **Minor 5（agy Nit 2）ko 商店「원본에」** | **已修復** | [`fastlane/metadata/android/ko-KR/full_description.txt:11`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ko-KR/full_description.txt#L11)：承諾第 2 點改為「사본을 읽어도 소스 앱에 읽음 표시가 되지 않고...」，對齊 App 內部用詞。 |
| **Minor 6 `LOCALE_DIR` 將 `tv/car` 誤判為語系目錄** | **已修復** | [`tools/check-strings.py:20`](file:///Users/iml1s/Documents/mine/quietinbox/tools/check-strings.py#L20)：正規表達式加入負向先行斷言 `(?!(?:tv\|car\|desk\|watch\|vrheadset\|night\|land\|port)$)`，排除 Android 裝置與顯示模式限定詞。經突變驗證 `values-tv` 確實被完全略過。 |
| **Minor 7 `PLACEHOLDER` 漏看帶旗標格式（`%.1f`、`%,d`）** | **已修復** | [`tools/check-strings.py:18`](file:///Users/iml1s/Documents/mine/quietinbox/tools/check-strings.py#L18)：改為 `r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdf]\|%%"`。經突變驗證，若預設字串含 `%.1f` 或 `%,d` 而語系目錄遺漏，將正確觸發 parity error。 |
| **Minor 8 `monogram()` 缺乏單元測試** | **已修復** | 新增 [`core/designsystem/src/test/kotlin/.../MonogramTest.kt`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/test/kotlin/dev/quietinbox/core/designsystem/components/MonogramTest.kt)：4 個測試涵蓋漢字（1 字）、假名與諺文（1 字）、拉丁文字（2 字縮寫）、空白/null（回退 `?`）。 |
| **Minor 9 reviews 索引第 18 列佔位字** | **已修復** | [`docs/reviews/README.md:29`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L29) 與 [`docs/zh-Hant/reviews/README.md:27`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L27)：第 18 列確認填入 commit hash `ee48710`，並補上第 19 列紀錄。 |
| **Minor 10 示範帳單金額日韓不寫實** | **已修復** | [`platform/storage/.../DemoLocalisation.kt:139, 199`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/debug/kotlin/dev/quietinbox/platform/storage/repo/DemoLocalisation.kt#L139)：日文水電費改為 `12,400 円`，韓文改為 `124,000원`，符合生活常理。 |

---

## 二、Brief 核心機制與設計思考深入查核

### 1. `tools/demo-screenshots.sh` 輸入法控制與邊界情境
- **無輸入法時按 `KEYCODE_BACK`**：
  若當下沒有輸入法面板存在，`KEYCODE_BACK` 會直接作用在 App 上，導致退出搜尋介面回到上層分頁。此時緊接著的 `has_text "$SEARCH_QUERY" || die` 會因畫面上找不到 "meeting" 而立即終止（`exit 1`）。這正是有意設計的**大聲失敗（loud failure）**防禦機制，避免產出非預期畫面的截圖。
- **裝置僅有一個預設輸入法（不可停用）**：
  Android 系統安全策略限制不能停用系統中唯一啟用的輸入法。腳本在 `imes_off` 時檢測 `ime list -s` 少於 2 個會發出警告。若該唯一輸入法在打字時組字吞字，後續的 `has_text "$SEARCH_QUERY"` 將檢驗失敗並直接報錯退出。此行為是完全可接受且必要的——寧可截圖失敗阻擋流程，也絕不能讓未斷言的空白/亂碼截圖流入 Play 商店。
- **`imes_off` 未執行時的 EXIT trap 安全性**：
  `imes_on` 受到 `if [ "${IME_DISABLED:-0}" = 1 ]` 的嚴格守護。若腳本在 `imes_off` 執行前即退出，或停用失敗，`IME_DISABLED` 保持為 0，trap 不會進行任何不必要的 IME 操作，行為安全。
- **截圖實質檢驗**：
  實測比對 `docs/screenshots/phone/` 與 `fastlane/metadata/android/` 下 5 個語系共 35 張截圖，SHA-256 雜湊值 100% 逐一相符。透過 OCR 實測檢驗五語系 `3_search.png`：
  - `en-US`：搜尋框 `meeting`，顯示 `19 results`（無鍵盤遮擋）
  - `ja-JP`：搜尋框 `meeting`，顯示 `19 件の結果`（無鍵盤遮擋）
  - `ko-KR`：搜尋框 `meeting`，顯示 `결과 19개`（無鍵盤遮擋）
  - `zh-CN`：搜尋框 `meeting`，顯示 `19 条结果`（無鍵盤遮擋）
  - `zh-TW`：搜尋框 `meeting`，顯示 `19 個結果`（無鍵盤遮擋）

### 2. 用詞一致性與殘留物清查
- **`원본 앱`**：除歷史評審紀錄報告（Round 18、19、20 brief）之外，整個 repo 的所有原始碼、資源 XML、商店文案中 **0 殘留**。
- **`取り除く`**：全 repo 的所有資源檔與商店文案中 **0 殘留**。
- **`アクティビティ`**：除 `changelogs/4.txt`（0.1.0 歷史發行備忘，不可篡改）外，當前版本之 `changelogs/5.txt`、`whatsnew`、`release-notes.json`、`full_description.txt` 與字串註解中已全數統一為「活動画面／活動の分析／活動」，**0 殘留**。
- **`数据库` (zh-Hans)**：`values-b+zh+Hans/strings.xml:276` 僅保留 `delete_everything_step_database`（「删除保险库数据库」，對應實體 SQLite 檔案），其餘抽象概念全數為「保险库」，商店文案中 **0 殘留**「数据库」。

### 3. `tools/check-strings.py` 突變測試驗證
針對 Brief 要求檢驗的突變情境進行記憶體模擬與正則驗證：
1. **`values-tv` 含有單一字串**：`LOCALE_DIR` 負向先行斷言命中 `tv$`，`fullmatch` 回傳 `None`，該目錄被完全略過，不會再產生 380 個不存在的 missing string 假警報。
2. **預設字串含 `%.1f` 且語系遺漏**：`PLACEHOLDER` 成功匹配 `%.1f`（`(\.\d+)?f`），提取為 `['%.1f']`，語系為 `[]`，比對不一致觸發 `error: placeholders differ`。
3. **預設字串含 `%,d` 且語系遺漏**：`PLACEHOLDER` 成功匹配 `%,d`（`[-#+ 0,(]*d`），提取為 `['%,d']`，語系為 `[]`，比對不一致觸發 `error: placeholders differ`。
4. 帶位置參數與複合旗標形式（如 `%1$.2f`、`%2$,d`）亦均能完整匹配並檢查。

### 4. `MonogramTest` 與 Convention Plugin 架構
- [`core/designsystem/build.gradle.kts`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/build.gradle.kts) 套用了 `alias(libs.plugins.quietinbox.android.library.compose)`。
- [`quietinbox.android.library.compose.gradle.kts:3`](file:///Users/iml1s/Documents/mine/quietinbox/build-logic/src/main/kotlin/quietinbox.android.library.compose.gradle.kts#L3) 繼承了 `quietinbox.android.library`。
- [`quietinbox.android.library.gradle.kts:35-36`](file:///Users/iml1s/Documents/mine/quietinbox/build-logic/src/main/kotlin/quietinbox.android.library.gradle.kts#L35-L36) 明確宣告了：
  ```kotlin
  "testImplementation"(libs.findLibrary("junit").get())
  "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
  ```
  因此 `core:designsystem` 透過慣例外掛自動具備了執行 JUnit 4 與 Kotest matchers 所需的全部環境，無需在模組內手動聲明。4 個測試執行結果為 100% 通過。

---

## 三、本輪新發現事項 (New Findings)

本輪**未發現任何 Critical 或 Important 等級缺陷**。共發現 4 項 **Minor** 等級事項：

### Minor 1: `TEST_MATRIX.md` 英文與繁中版截圖工具說明斷句插字錯誤
- **位置**：[`docs/TEST_MATRIX.md:70`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L70) 與 [`docs/zh-Hant/TEST_MATRIX.md:63`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L63)
- **現狀**：
  - **英文版**：
    ```markdown
    - Screenshot harness: `tools/demo-screenshots.sh <adb-serial> <en-US|zh-TW|zh-CN|ja-JP|ko-KR> <out-dir>` installs the On Android 13+ the keyboard follows the app language, so the tool switches every input method off while it types the search query, restores them afterwards, and refuses to shoot the search screen unless the field shows the query.
      debug APK, wipes app data, grants the listener...
    ```
    （新增的 Android 13+ 說明直接插入在 `installs the` 與 `debug APK` 之間，導致「installs the ... debug APK」原句被腰斬破裂）。
  - **繁中版**：
    ```markdown
    - 截圖工具：`tools/demo-screenshots.sh <adb-serial> <en-US|zh-TW|zh-CN|ja-JP|ko-KR> <out-dir>` 會安裝 debug APK、清除 app Android 13 以上鍵盤會跟著 App 語言走，所以工具在輸入搜尋字串期間關閉所有輸入法、之後還原，並在搜尋欄沒有顯示該字串時拒絕拍搜尋頁。
      資料、授予監聽器與 `POST_NOTIFICATIONS`...
    ```
    （新增說明直接插入在「清除 app」與「資料」之間，變成「清除 app [插入句] 資料」）。
- **建議**：將新增的輸入法說明移至該項目的末尾獨立成句，恢復原本流暢的句子結構。

### Minor 2: Python 編譯快取檔案意外提交進 Git
- **位置**：[`tools/__pycache__/check-strings.cpython-314.pyc`](file:///Users/iml1s/Documents/mine/quietinbox/tools/__pycache__/check-strings.cpython-314.pyc)
- **現狀**：在 commit `c90e75f` 中，`tools/__pycache__/check-strings.cpython-314.pyc`（7,887 bytes）被加入版本控制。檢查 [`.gitignore`](file:///Users/iml1s/Documents/mine/quietinbox/.gitignore) 發現目前未設定 `__pycache__/` 或 `*.pyc` 忽略規則。
- **建議**：
  1. 在 [`.gitignore`](file:///Users/iml1s/Documents/mine/quietinbox/.gitignore) 加入：
     ```gitignore
     # Python
     __pycache__/
     *.py[cod]
     ```
  2. 執行 `git rm --cached tools/__pycache__/check-strings.cpython-314.pyc` 移除快取檔。

### Minor 3: `CLAUDE.md` 審查目錄 glob 未更新
- **位置**：[`CLAUDE.md:129-130`](file:///Users/iml1s/Documents/mine/quietinbox/CLAUDE.md#L129-L130)
- **現狀**：行 130 已寫入 `rounds 18–19 reviewed the localisation`，但行 129 仍為 `docs/reviews/2026-09-06-round{10,…,18}/`。
- **建議**：將 `{10,…,18}` 更新為 `{10,…,19}`（或 `{10,…,20}`）。

### Minor 4: reviews 索引第 19 列修正 commit 仍為佔位字
- **位置**：[`docs/reviews/README.md:30`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L30) 與 [`docs/zh-Hant/reviews/README.md:28`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L28)
- **現狀**：第 19 列的對應修正 commit 仍記錄為 `follow-up commit` / `後續 commit`。
- **建議**：該修正 commit 現已確定為 `c90e75f`，後續可填入正式 commit hash。

---

## 四、總結

Commit `c90e75f` 高品質地落實了自動化截圖腳本的輸入法隔離與搜尋畫面斷言，全面修復了所有非英文語系截圖無搜尋結果的退化問題，並徹底肅清了多語系字典與商店文案中的殘留用詞，同時完備了字串檢查工具與設計系統單元測試。

新發現的 4 項 Minor 均屬文檔排版微調與 `.gitignore` 衛生維護，無阻擋發布之虞。審查結論為 **APPROVE WITH MINOR FIXES**。
