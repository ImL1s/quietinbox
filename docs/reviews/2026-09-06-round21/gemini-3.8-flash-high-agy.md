# Review Round 21（第 20 輪截圖工具與在地化修正之迷你再審）— 審查報告

**審查範圍**：`git diff c90e75f..8954af1`（單一 commit `8954af1`，branch `main`）  
**審查模式**：唯讀審查（READ-ONLY Review），無任何檔案建立／修改／刪除、無狀態變更之 git 指令、無裝置與儀器化測試。  
**驗證指令與結果**：
1. `python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`（exit 0）。
2. `bash -n tools/demo-screenshots.sh` → 語法檢查通過（exit 0）。
3. `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:designsystem:testDebugUnitTest :platform:storage:compileDebugAndroidTestKotlin --console=plain -q` → 兩項任務全數通過（exit 0）。
4. 全 35 張截圖驗證：
   - 雜湊比對：`docs/screenshots/phone/` 與 `fastlane/metadata/android/` 兩處共 35 張 PNG 之 SHA-256 100% 逐一相符。
   - 尺寸規格：全數符合 1080×2400 解析度。
   - 檔案大小：最小者為 `ko-KR/4_activity.png`（147,084 bytes）與 `en-US/5_capture.png`（148,121 bytes），深色收件匣為 276–316 KB，全數遠高於 80 KB 門檻。
   - OCR 實測：五語系之 `2_conversation.png` 均呈現完整在地化對話；五語系之 `3_search.png` 均顯示 `meeting` 查詢及 13 筆結果，畫面無虛擬鍵盤遮擋。

---

## 審查結論 (Verdict)

### **APPROVE**

Commit `8954af1` 嚴謹且精準地解決了 Round 20 兩位審查者提出的所有問題（Claude subagent 之 Important I-1、Minor 1–4、觀察 2、3、7、8，以及 agy 之 4 項 Minor）：
1. **重要缺陷 I-1 徹底解決**：`tools/demo-screenshots.sh` 在截圖對話畫面之前加入 `wait_text "$DEMO_PINNED_TITLE" 10` 輪詢等待，並在 `shot()` 中以 `MIN_SHOT_BYTES=80000` 設下堅實的防禦下限。`zh-CN/2_conversation.png` 已從上一輪的 32 KB 載入轉圈中畫面，成功重新拍攝為 258 KB 的完整對話內容（頂欄「林小美 Mia Lin」、副標「来源: demo.quietinbox.chat」、包含五則完整對話訊息）。
2. **截圖腳本健全性全面提升**：
   - 在啟動 App 前增加 `cmd locale get-app-locales` 最多 5 次輪詢確認與重新設定，徹底消除 `pm clear` 非同步重設 per-app locale 導致 App 啟動退回系統語言的競爭風險。
   - 廣播種子帶入 `--es lang "$LOCALE"`，同時在 `DemoData.seed(now, locale)` 與 `DemoReceiver` 支援明確指定語言，杜絕資料種子與 UI 語系脫節。
   - 搜尋輸入改採間隔 0.3 秒逐字輸入，並以 `KEYCODE_ENTER` 取代無條件 `KEYCODE_BACK`，確保軟體鍵盤收起時絕不退回上一頁，且在 `set -u` 下安全無虞。
   - `ime_shown` 正確處理 `set -o pipefail` 下的 SIGPIPE 問題；`dump_ui` 失敗與文字比對失敗分流報錯；EXIT trap 改至 `imes_on` 定義之後註冊。
3. **文檔與版本控制衛生**：
   - `docs/TEST_MATRIX.md` 英文與繁中版腰斬原句的問題已修復，說明獨立移至項尾。
   - `.gitignore` 補上 `__pycache__/` 與 `*.py[cod]`，誤入版本控制的 `.pyc` 已完全清除（repo 追蹤為 0）。
   - `fastlane/metadata/android/ja-JP/changelogs/4.txt` 歷史發布說明統一把「アクティビティ分析」改為「活動分析」，全 repo 商店文案與字串「アクティビティ」0 殘留。
   - CLAUDE.md、CHANGELOG 與 reviews 索引均完成對齊。
4. **架構相容性**：`DemoData` 介面新增選用參數 `locale: Locale? = null`，Release 變體之 `NoDemoData` 同步實作，Hilt `@Binds` / `@Provides` 編譯通過，維持零示範資料外洩至 Release 的設計承諾。

本輪無任何 Critical、Important 或 Minor 缺陷。

---

## 一、第 20 輪發現逐項驗證表 (Round-20 Verification Table)

| Round-20 發現項目 | 狀態 | 具體證據與檔案行號 |
| :--- | :---: | :--- |
| **I-1 [Important] zh-CN `2_conversation.png` 是載入中畫面**<br>（32 KB 轉圈、未命名對話；缺畫面就緒檢查） | **已修復** | 1. [`tools/demo-screenshots.sh:409-411`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L409-L411)：點擊進入對話後，新增 `wait_text "$DEMO_PINNED_TITLE" 10` 等待釘選對話標題出現，並 `sleep 2` 待滾動穩定後才拍照。<br>2. [`tools/demo-screenshots.sh:40, 310-314`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L40)：設定 `MIN_SHOT_BYTES=80000`，低於 80 KB 直接 `die`。<br>3. 實測圖檔：`docs/` 與 `fastlane/` 下之 `zh-CN/2_conversation.png` 從 32,360 bytes 變為 258,636 bytes；OCR 證實頂欄為「林小美 Mia Lin」、副標「来源: demo.quietinbox.chat」、包含「我把日历更新了…」「晚餐想吃什么…」等完整訊息。 |
| **Minor 1 TEST_MATRIX 說明句插在半句中間**<br>（英文切斷 installs the ... debug APK；繁中切斷清除 app ... 資料） | **已修復** | [`docs/TEST_MATRIX.md:68-75`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L68-L75) 與 [`docs/zh-Hant/TEST_MATRIX.md:60-70`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L60-L70)：主句完整保留（「installs the debug APK, wipes app data...」／「會安裝 debug APK、清除 app 資料...」），Android 13+ 輸入法說明完整移至該項目尾端。 |
| **Minor 2 Python 快取 `.pyc` 提交進 Git** | **已修復** | 1. [`.gitignore:21-24`](file:///Users/iml1s/Documents/mine/quietinbox/.gitignore#L21-L24)：加入 `__pycache__/` 與 `*.py[cod]`。<br>2. `git show --stat 8954af1`：已刪除 `tools/__pycache__/check-strings.cpython-314.pyc`。<br>3. `git ls-files '*.pyc'` 結果為空，repo 內 0 追蹤。 |
| **Minor 3 搜尋截圖無條件按 BACK 且失敗訊息混淆** | **已修復** | 1. [`tools/demo-screenshots.sh:427-431`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L427-L431)：改送 `KEYCODE_ENTER`（單行搜尋欄之搜尋動作），天然收起輸入法且不觸發 backstack 退頁；若仍顯示輸入法（`ime_shown`）才發出 warn。<br>2. [`tools/demo-screenshots.sh:433-435`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L433-L435)：分流為 `dump_ui || die "uiautomator could not dump the search screen"` 與文字斷言失敗兩條獨立錯誤訊息。 |
| **Minor 4 CHANGELOG／索引稱「四個語系重拍」，實為五個** | **已修復** | 1. [`CHANGELOG.md:15`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L15)：Round-19 改為「all five locales are re-shot without a keyboard in frame.」。<br>2. [`docs/reviews/README.md:30`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L30) 與繁中版第 19 列改為「all five locales re-shot」／「五個語系全部重拍」。 |
| **觀察 2 EXIT trap 在 helper 之前註冊之潛在地雷** | **已修復** | [`tools/demo-screenshots.sh:185-194`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L185-L194)：`imes_on()` 函式定義完成後，才註冊 `trap 'imes_on; rm -rf "$WORK_DIR"' EXIT`，避免 `set -e` 下因函式未就緒中斷 cleanup。 |
| **觀察 3 `default_input_method` 為空時無提示** | **已修復** | [`tools/demo-screenshots.sh:176-179`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L176-L179)：若取值為空或 `null`，明確印出 `warn "no default input method is set..."`。 |
| **觀察 7 reviews 索引第 19 列佔位字** | **已修復** | [`docs/reviews/README.md:30`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L30) 與繁中版：第 19 列正式填入 commit hash `c90e75f`；新增第 20 列記錄。 |
| **觀察 8 `CLAUDE.md` glob 範圍** | **已修復** | [`CLAUDE.md:129-130`](file:///Users/iml1s/Documents/mine/quietinbox/CLAUDE.md#L129-L130)：更新為 `round{10,…,20}/` 與 `rounds 18–20 reviewed the localisation`。 |
| **ja changelog 4「アクティビティ分析」殘留** | **已修復** | [`fastlane/metadata/android/ja-JP/changelogs/4.txt:1`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ja-JP/changelogs/4.txt#L1)：改為「活動分析」。`fastlane/` 內完全清零。 |

---

## 二、Brief 核心機制與設計思考深入查核

### 1. `tools/demo-screenshots.sh` 機制驗證
- **`${SEARCH_QUERY:$i:1}` 在 `set -u` 下的安全性**：
  `SEARCH_QUERY` 在頂部指派為 `"meeting"`（非空），`for ((i = 0; i < ${#SEARCH_QUERY}; i++))` 是典型的 C-style 迴圈，`i` 從 0 開始遞增至長度減 1。實測在 `set -euo pipefail` 環境下，變數皆在作用域內且有值，不會觸發 `unbound variable`，執行完全安全。
- **搜尋欄輸入 `KEYCODE_ENTER` 的行為影響**：
  查核 [`SearchScreen.kt:69-86`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchScreen.kt#L69-L86)：
  - 搜尋欄採用 Compose `TextField(value = state.query, onValueChange = viewModel::setQuery, singleLine = true)`。
  - 搜尋為即時流式篩選（reactive live search），每次字元變更即更新查詢；並未宣告自訂的 `KeyboardActions(onSearch = ...)`。
  - 對 `singleLine = true` 的 TextField 發送 `KEYCODE_ENTER` 時，Compose 預設的 IME 行為是收起虛擬鍵盤或消耗該鍵事件，**不會觸發表單提交、不會清除字串、更不會跳轉頁面**。
  - 相較於上一輪直接按 `KEYCODE_BACK`（在無鍵盤時會 pop backstack 退回收件匣），`KEYCODE_ENTER` 能聚焦在輸入框上收合鍵盤，徹底杜絕非預期退頁。
- **80 KB 門檻值 vs 實體截圖（最小 147 KB）與深色收件匣**：
  - 實測統計本輪全部 35 張 PNG：
    - 最小者：`ko-KR/4_activity.png`（147,084 bytes）、`en-US/5_capture.png`（148,121 bytes）。
    - 深色收件匣（`7_inbox_dark.png`）：各語系介於 276,123 bytes 至 316,168 bytes 之間。
    - 異常載入畫面（如上一輪的轉圈畫面）：僅約 32 KB。
  - 結論：80 KB 設定於 32 KB 與 147 KB 之間，具備充裕的安全緩衝（~48 KB 餘裕），既能百分之百攔截空畫面與 Loading 轉圈，又絕不會誤殺深色主題或簡潔介面截圖。
- **`wait_text` 輪詢開銷分析**：
  - `wait_text` 每次迴圈執行 `uiautomator dump`、`adb pull` 與 python 剖析，耗時約 1–1.5 秒，隨後 `sleep 1`。
  - 當畫面已渲染完成時，第 1 次嘗試即成功返回（耗時僅約 1 秒）；若遇載入延遲，最多嘗試 10 次（上限約 15–20 秒）。
  - 對截圖腳本而言，此開銷微乎其微，但換來確定性的非同步畫面就緒保證。加上 `uihelper.py:124` 同時支援 `content-desc` 比對，適應力強。

### 2. DemoData 多語系注入與 Release 編譯
- **介面與實作對齊**：
  - [`DemoData.kt:13`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/DemoData.kt#L13)：`suspend fun seed(now: Long = System.currentTimeMillis(), locale: java.util.Locale? = null): DemoCounts`。
  - [`NoDemoData.kt:19`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/DemoData.kt#L19)：`override suspend fun seed(now: Long, locale: java.util.Locale?): DemoCounts = DemoCounts(0, 0)`。
  - [`DemoDataRepository.kt:63`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/debug/kotlin/dev/quietinbox/platform/storage/repo/DemoDataRepository.kt#L63)：`words = DemoLocalisation.forLocale(locale ?: context.resources.configuration.locales[0])`。
- **呼叫端相容性**：
  - `SettingsViewModel.kt:120`：呼叫 `demoData.seed()`，走預設引數（`locale = null`，回退至系統 configuration），完全相容無更動。
  - `DemoReceiver.kt:57`：接收 `--es lang` 參數，解析為 `Locale.forLanguageTag(it)` 後帶入 `repository.seed(locale = locale)`。
  - `DemoDataTest.kt:81`：呼叫 `demo.seed(now)`，走預設引數（`locale = null`），實測編譯通過。
- **Hilt Release 綁定相容性**：
  - Release source set 的 [`DemoModule.kt:14-15`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/release/kotlin/dev/quietinbox/platform/storage/di/DemoModule.kt#L14-L15) 提供 `@Provides fun demoData(): DemoData = NoDemoData`。
  - 因為 `NoDemoData` 完整且正確覆寫了更新後的 `DemoData` 介面，在 Release 編譯期型別完全匹配，沒有缺漏方法或簽章不合。

### 3. 截圖實物全數核對
- **對話頁截圖（`2_conversation.png`）在地化核對**：
  - `en-US`：頂欄「Mia Lin」、副標「Source: demo.quietinbox.chat」。
  - `ja-JP`：頂欄「林 美咲 Misaki Hayashi」、副標「送信元: demo.quietinbox.chat」。
  - `ko-KR`：頂欄「김미아 Mia Kim」、副標「소스: demo.quietinbox.chat」。
  - `zh-CN`：頂欄「林小美 Mia Lin」、副標「来源: demo.quietinbox.chat」。
  - `zh-TW`：頂欄「林小美 Mia Lin」、副標「來源: demo.quietinbox.chat」。
- **搜尋頁截圖（`3_search.png`）核對**：
  - 5 個語系均顯示搜尋字詞 `meeting`。
  - 5 個語系均顯示篩選結果（`en-US: 13 results`、`ja-JP: 13 件の結果`、`ko-KR: 결과 13개`、`zh-CN: 13 条结果`、`zh-TW: 13 個結果`）。
  - 所有搜尋頁截圖中，結果清單佔滿畫面中下部，虛擬鍵盤已完全收合，底部導覽列完整清晰。

---

## 三、新發現事項 (New Findings)

- **Critical**：0 項。
- **Important**：0 項。
- **Minor**：0 項。

---

## 四、其他觀察 (Observations)

1. **`wait_text` 逾時與 `MIN_SHOT_BYTES` 之雙層縱深防禦**：
   在 `tools/demo-screenshots.sh:409` 中，`wait_text "$DEMO_PINNED_TITLE" 10 || warn "..."` 逾時僅印出 warning 而未立即 `die`。但緊接著在 `:411` 的 `shot "2_conversation"` 中，若畫面仍停留在轉圈 loading 狀態（約 32 KB），`MIN_SHOT_BYTES=80000` 檢查會立即觸發 `die` 終止腳本。兩者形成了優雅的防禦縱深：前者主動輪詢等待非同步載入，後者作為最後底線強制拒絕殘缺圖片。
2. **reviews 索引第 20 列之佔位文字**：
   `docs/reviews/README.md:31` 與 `docs/zh-Hant/reviews/README.md:29` 中，第 20 列的修正 commit 目前填寫為 `follow-up commit` / `後續 commit`。此為預期之正常現象（commit 建立時無法預先得知自己的 SHA），待下一輪文檔提交時回填 `8954af1` 即可。

---

## 五、總結

Commit `8954af1` 乾淨、精準且徹底地修復了自動化截圖工具的非同步載入等待與鍵盤控制問題，以 `MIN_SHOT_BYTES` 建立了穩固的產物質量底線，消除了所有多語系示範資料與 App 語言請求間的時序漏洞，並清除了所有殘留字串與快取檔案。

本輪審查全數通過，結論為 **APPROVE**。
