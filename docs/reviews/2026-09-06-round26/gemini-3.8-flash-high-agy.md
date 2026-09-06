# QuietInbox 獨立代碼審查報告 — Round 26

- **審查目標**：Commit `78e7487`（`main` 分支最新提交）
- **審查模型**：Gemini 3.8 Flash (High)
- **審查日期**：2026-09-06
- **審查結論**：**APPROVE WITH MINOR FIXES**

---

## 結論摘要

Commit [`78e7487`](file:///Users/iml1s/Documents/mine/quietinbox) 成功且根本性地修復了平板截圖腳本捕獲到 Android 桌面（Launcher）與系統設定畫面的缺陷，同時完成了全倉庫的中英文文件對齊與過期資訊修正：

1. **根本原因徹底根治**：平板／摺疊裝置（寬度 ≥ 600dp）使用 Compose `NavigationRail`，舊版 [`tools/demo-screenshots.sh`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh) 的 `tap-tab` 僅比對螢幕底部 15%（NavigationBar），導致分頁點擊全部失效；舊腳本在 `shot "2_conversation"` 與 `shot "3_search"` 後無條件執行 `KEYCODE_BACK`，在寬版面（ListDetail 雙欄且 Rail 常駐）下導致 Activity 被 pop 離開 App 進入桌面；加上舊大小門檻（80 KB）放行了 3.3 MB 的桌布。新版腳本引入 dp 寬度判定（`screen_dp_width`）、Left Rail 座標適配、雙欄就緒判定（`titles >= 2`）、窄版專屬 BACK 守衛，以及 `assert_app_foreground` 與 `die` 錯誤防線，徹底解決了根因。
2. **手機路徑無回歸風險**：深入檢查了 `LAYOUT` 變數作用域、Python 參數偏移量、`layout == "wide"` 條件門檻與 `die` 退出邏輯，手機窄版流程完全保持既有正確行為。
3. **14 張平板截圖驗證無誤**：2076×2152 解析度、en-US 與 zh-TW 各 7 張均經 OCR 與尺寸檢視，皆為 QuietInbox 本體畫面（包含對話雙欄展開），且與 Fastlane 目錄檔案位元組完全一致（byte-identical）。
4. **文件與程式碼精確對齊**：測試數量（32、5、22、34）、發行簽章、固定 Package ID、五國語系檢查關卡、CI 整合測試指令與 15 個 Gradle 模組清單均經程式碼與工作流驗證無誤。
5. **雙語對稱性（Parity）完備**：README、SCOPE、ARCHITECTURE、ADR-0001、ADR-0006/0007 雙向連結、審查索引皆達成中英一致。

---

## 審查維度詳解

### 1. 根本原因（Root Cause）分析
- **舊版失敗根因鏈**：
  1. [`tools/demo-screenshots.sh`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh) 舊版 `tap-tab` 僅以 `box[1] >= int(height * 0.85)` 判定底部導覽列。在寬度 ≥ 600dp 的平板（如 `Foldable_Test` 2076×2152）上，導覽元件為位於左側 15% 的 `NavigationRail`（見 [`MainNavigation.kt:143`](file:///Users/iml1s/Documents/mine/quietinbox/app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt#L143)），導致點擊搜尋、活動等分頁時找不到節點。
  2. 舊腳本所有 `tap_tab` 呼叫均接 `|| warn "..."`，失敗時僅印出警告而不中斷。
  3. 進入對話頁後，舊腳本無條件送出 `shell input keyevent KEYCODE_BACK`。在寬版雙欄下，返回鍵直接將根畫面的 Activity 退到背景，畫面掉落到桌面或系統設定。
  4. 隨後在第 3 步（搜尋），`has-text "$SEARCH_QUERY"`（"meeting"）在收件匣的預覽摘要中碰巧存在，再次送出 `KEYCODE_BACK`，使設備完全停留在桌面。
  5. 舊的大小下限僅設為 80,000 bytes（80 KB），桌面壁紙截圖壓縮後達 3.3 MB，順利通過門檻並被提交。
- **本 Commit 修復評估**：
  - [`tools/demo-screenshots.sh:130`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L130)：新增 `in_left_rail = layout == "wide" and box[2] <= int(width * 0.15)`，使平板能正確命中左側 Rail 項目。
  - [`tools/demo-screenshots.sh:536`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L536)、[`tools/demo-screenshots.sh:576`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L576)：將對話與搜尋後的 `KEYCODE_BACK` 限縮於 `[ "$LAYOUT" = "narrow" ]`，避免在平板雙欄下誤退 App。
  - [`tools/demo-screenshots.sh:182`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L182)：`conversation-ready` 在 `layout == "wide"` 時改為檢驗 `titles >= 2`（清單列與細節頁標題同時呈現），正確掌握 ListDetail 開啟時機。
  - [`tools/demo-screenshots.sh:375`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L375)：`assert_app_foreground` 確保拍攝前前景存在 `dev.quietinbox.app.debug` 節點。
  - [`tools/demo-screenshots.sh:512`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L512)：所有分頁導覽失敗一律轉換為 `die` 終止執行。
  - **結論**：修復確實切中根本原因，而非僅遮掩症狀。

### 2. 手機路徑（Phone Path）回歸風險
- **`LAYOUT` 變數作用域與 `set -u`**：
  - 雖然 [`tools/demo-screenshots.sh:294`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L294) 在函式宣告中引用了 `$LAYOUT`，但該變數在全域執行段 [`tools/demo-screenshots.sh:436`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L436) 即初始化為 `"narrow"` 或 `"wide"`。
  - `tap_tab` 首次呼叫發生在導覽就緒後的 [`tools/demo-screenshots.sh:512`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L512)，引導流程（Onboarding）僅使用 `tap_text`，從未呼叫 `tap_tab`。因此在 `set -u` 下不會觸發 unbound variable 錯誤。
- **Python Helper 參數偏移量**：
  - `tap-tab`：在 [`tools/demo-screenshots.sh:297`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L297) 傳入 `$LAYOUT`，[`tools/demo-screenshots.sh:114`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L114) 正確讀取 `sys.argv[2]` 為 layout，`sys.argv[3:]` 為 wanted 標籤，偏移完全正確。
  - `conversation-ready`：在 [`tools/demo-screenshots.sh:526`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L526) 傳入 `$LAYOUT`，[`tools/demo-screenshots.sh:166`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L166) 讀取 `title, inbox_tab, layout = sys.argv[2], sys.argv[3], sys.argv[4]`，解包長度精確相符。
  - 其他 helper 指令參數結構維持不變。
- **Rail 規則限制於 `wide`**：
  - [`tools/demo-screenshots.sh:130`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L130) 明確限制 `layout == "wide"` 才能判定左側 15%，防止手機上左上角的短標題（特別是 CJK 字串）被誤判為 NavigationRail 分頁。手機窄版路徑只會比對底部 15%（NavigationBar）。
- **`warn` → `die` 轉換**：
  - 在手機路徑上，如果分頁不存在或點擊失敗，本來就代表流程失常；轉換為 `die` 能即時捕捉問題，不會造成原本正常執行的流程無故中斷。

### 3. 防護機制（Guards）完整性分析
- [`tools/demo-screenshots.sh:140`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L140) 的 `app-foreground` 僅斷言畫面上至少有一個節點屬於 `dev.quietinbox.app.debug`。此防護能有效排除 Launcher、系統 Settings 及 App 崩潰（Crash/ANR 系統對話框無 App 節點）。
- **仍可能繞過現有防護而拍錯的極端場景**：
  1. **分頁切換卡死或對話框遮擋**：若點擊新分頁（如活動或擷取）時 UI 執行緒卡頓或被 App 內部的 AlertDialog／ModalBottomSheet 阻擋，畫面仍停留在前一頁或停留在對話框上。因其節點仍屬於 `dev.quietinbox.app.debug` 且畫面內容 > 80 KB，腳本會將同一頁重複拍下（類似 Round 18 曾出現的活動頁重複情況）。
  2. **引導流程未完成**：若 Onboarding 12 次迭代未按完，App 停留在 Onboarding 畫面，仍屬 App package 且 > 80 KB。
  3. **夜間模式切換失敗**：[`tools/demo-screenshots.sh:600`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L600) 的 `shell cmd uimode night yes` 若因系統權限或環境失敗，僅發出 `warn`，隨後的 `7_inbox_dark.png` 將在通過 `app-foreground` 下拍出淺色收件匣。
  4. **全域覆蓋的半透明系統層**：例如系統音量滑桿或通知遮罩下拉一部分，底層 App 節點依然在 uiautomator 樹中。

### 4. 14 張平板截圖檢驗
- 檢查範圍：
  - `docs/screenshots/tablet/{en-US,zh-TW}/*.png`
  - `fastlane/metadata/android/{en-US,zh-TW}/images/tenInchScreenshots/*.png`
- 檢驗結果：
  - **尺寸規格**：全部 14 張圖片均為精確的 `2076 × 2152`（Foldable 展開規格）。
  - **位元組一致性**：`docs/` 與 `fastlane/` 對應的 14 組檔案 SHA-256 雜湊完全相同（`sha256_match=True`）。
  - **檔案大小**：介於 186 KB 至 498 KB 之間（排除了舊版 3.4 MB 壁紙與小於 80 KB 的空白頁）。
  - **內容與語言驗證（Swift Vision OCR）**：
    - `en-US`：包含 "Inbox"、"Search"、"Activity"、"Capture"、"Settings"、"128 recognisable messages saved..."、"meeting" 等全英文介面。
    - `zh-TW`：包含「收件匣」、「搜尋」、「活動統計」、「擷取健康」、「設定」、「已保存 128 則可辨識通知訊息...」、「meeting」等全繁體中文介面。
    - `2_conversation.png`：明確呈現左側收件匣清單、右側對話詳細內容與標題「林小美 Mia Lin」之雙欄佈局。
    - 無任何 Launcher 或系統 Settings 殘留。

### 5. 文件真實性檢驗（Docs never run ahead of code）
- **四項測試數量更正**（[`docs/SCOPE.md:16-24`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L16-L24)、[`docs/zh-Hant/SCOPE.md:16-24`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/SCOPE.md#L16-L24)）：
  - `CaptureCoordinatorTest`：代碼中實測 32 個 `@Test`（先前標示 16）。
  - `VaultMaintenanceTest`：代碼中實測 5 個 `@Test`（先前標示 4）。
  - `core:reconcile`：包含 `ReconcilerTest`（20）與 `ReconcilerPropertyTest`（2），共 22 個測試（先前標示 20）。
  - `core:analytics`：包含 `InsightsTest`（28）與 `ActivityAnalyticsTest`（6），共 34 個測試（先前標示 32）。
  - 四處更正與現況 100% 吻合。
- **發行簽章狀態**（[`docs/SCOPE.md:42`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L42)、[`docs/zh-Hant/SCOPE.md:42`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/SCOPE.md#L42)）：
  - 檢視 [`.github/workflows/release.yml:37-50`](file:///Users/iml1s/Documents/mine/quietinbox/.github/workflows/release.yml#L37-L50)，已具備自 Secret 還原 Keystore 並對 APK/AAB 進行發行簽章之流程，描述「發行簽章本身已完成：`release.yml` 會用上傳金鑰簽出 APK／AAB，金鑰存放在 repo 之外」屬實。
- **Package ID 狀態**：
  - 描述「`dev.quietinbox.app` 已在 Google Play 上架（Gradle namespace 為 `dev.quietinbox`），已發布的 applicationId 不能變更」屬實。
- **`CONTRIBUTING.md` 與 CI 指令對齊**（[`CONTRIBUTING.md:23-25`](file:///Users/iml1s/Documents/mine/quietinbox/CONTRIBUTING.md#L23-L25)、[`docs/zh-Hant/CONTRIBUTING.md:22-24`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/CONTRIBUTING.md#L22-L24)）：
  - 檢查 [`.github/workflows/ci.yml:71`](file:///Users/iml1s/Documents/mine/quietinbox/.github/workflows/ci.yml#L71)，CI 執行的正是 `:platform:storage:connectedDebugAndroidTest :platform:crypto:connectedDebugAndroidTest :platform:backup:connectedDebugAndroidTest` 三套測試。
- **PR Template 項目**（[`.github/PULL_REQUEST_TEMPLATE.md:7-18`](file:///Users/iml1s/Documents/mine/quietinbox/.github/PULL_REQUEST_TEMPLATE.md#L7-L18)）：
  - 全面更新為雙語檢查清單，要求 5 份語言目錄全數通過 `tools/check-strings.py`，並新增截圖人工逐張檢核項目。
- **README 模組清單**（[`README.md:161-176`](file:///Users/iml1s/Documents/mine/quietinbox/README.md#L161-L176)）：
  - 比對 [`settings.gradle.kts:26-53`](file:///Users/iml1s/Documents/mine/quietinbox/settings.gradle.kts#L26-L53)，清單包含 `:app`、6 個 `:core:*`、1 個 `:parsers:apps`、5 個 `:platform:*` 及 `:feature:*`，共 15 個模組說明，無一遺漏。

### 6. 中英文雙語對稱性（Parity）
- `README.md`：繁體中文與英文半部結構嚴格對齊，英文半部補齊了原本省略的模組架構表，並分別展示對應語系的截圖。
- `docs/SCOPE.md` 與 `docs/zh-Hant/SCOPE.md`：測試數量、已完成／未完成事項與附註修訂同步更新。
- `docs/TEST_MATRIX.md` 與 `docs/zh-Hant/TEST_MATRIX.md`：同步補上截圖雙防線與版面適配說明。
- `docs/ARCHITECTURE.md` 與 `docs/zh-Hant/ARCHITECTURE.md`：活動頁 5 分頁架構段落完成對齊。
- `docs/adr/0001-toolchain-and-module-layout.md`：附錄（`:parsers:apps` 整合說明）已同步至 `docs/zh-Hant/adr/0001-...`。
- `docs/adr/0006`、`docs/adr/0007`：頂部均補上對應繁體中文版的相對連結。
- `docs/reviews/README.md` 與 `docs/zh-Hant/reviews/README.md`：`whole-repo` 審查紀錄列皆已補齊。

---

## 審查發現評級

### Critical（必須在推送前修正）
*無*（0 項）。

### Important（建議在推送前修正）
*無*（0 項）。

### Minor / 建議調整（Nitpicks）

1. [`tools/demo-screenshots.sh:129-131`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L129-L131)
   ```python
   in_bottom_bar = box[1] >= int(height * 0.85)
   in_left_rail = layout == "wide" and box[2] <= int(width * 0.15)
   if in_bottom_bar or in_left_rail:
   ```
   **說明**：`in_left_rail` 嚴格限制在 `layout == "wide"`，但 `in_bottom_bar` 未限制 `layout == "narrow"`。在寬版面下，如果螢幕底部 15% 恰好出現與分頁名稱相同的文字或按鈕，理論上仍會被命中。建議將其對稱寫為：
   `in_bottom_bar = layout == "narrow" and box[1] >= int(height * 0.85)`。

2. [`tools/demo-screenshots.sh:600-601`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L600-L601)
   ```bash
   shell cmd uimode night yes >/dev/null 2>&1 || warn "could not switch the device to night mode"
   sleep 3
   shot "7_inbox_dark"
   ```
   **說明**：若模擬器切換夜間模式指令失敗，僅發出 `warn`，隨後仍會執行 `shot "7_inbox_dark"`。這會導致淺色的收件匣截圖被當作深色收件匣截圖輸出。建議改為 `|| die "could not switch the device to night mode"`。

3. [`README.md:85-86`](file:///Users/iml1s/Documents/mine/quietinbox/README.md#L85-L86)、[`README.md:187`](file:///Users/iml1s/Documents/mine/quietinbox/README.md#L187)
   ```bash
   ./gradlew :platform:storage:connectedDebugAndroidTest              :platform:crypto:connectedDebugAndroidTest
   ```
   **說明**：README 的建置與驗證快速範例中僅列出 `storage` 與 `crypto`，但 [`CONTRIBUTING.md:23-24`](file:///Users/iml1s/Documents/mine/quietinbox/CONTRIBUTING.md#L23-L24) 與 CI 均包含 `:platform:backup:connectedDebugAndroidTest`。建議後續可將 `backup` 連同補進 README 範例指令中，保持完全一致。

---

## 其他觀察（Observations）

- **截圖品質優良**：新擷取的 14 張平板截圖已完全消除原先被 Google Play 審查與線上商店展示之 Pixel Launcher 桌布及 Settings 應用畫面，Demo 資料的本地化時間、時鐘與字串均展現正確狀態。
- **錯誤防禦顯著強化**：從過去的盲目 `warn` 改為 `die`，並結合前台 Package ID 檢查，建構了 fail-closed 的截圖生成管線，大幅降低未來版本自動產圖再次污染 Fastlane metadata 的風險。
