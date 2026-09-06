# QuietInbox 第 27 輪程式碼審查報告（Round 27 Mini Re-review）

- **審查對象**：[`/Users/iml1s/Documents/mine/quietinbox`](file:///Users/iml1s/Documents/mine/quietinbox)
- **審查區間**：`git diff 78e7487..b9b49cc`（涵蓋第 26 輪審查發現之修正、兩處 App 字串缺陷修復、49 張商店截圖重拍、以及升級至 0.1.3 / versionCode 7 之發行準備）
- **審查模式**：唯讀審查（READ-ONLY），未修改任何倉庫程式碼，未啟用任何自動編排模式。

---

## Verdict：**APPROVE**

**0 Critical、0 Important、2 Minor / nitpicks、3 Observations。**

本輪改動（`b9b49cc`）完整且嚴謹地解決了第 26 輪審查所提出的所有阻斷性與改進項目（特別是 `tap_tab` 的點擊生效確認機制與重試循環），並順利修正了在第 26 輪截圖中暴露的兩處字串缺陷（收件匣計數 plurals 與連線健康頁單句化）。全部 49 張截圖經雜湊比對完全 byte-identical，真機/模擬器 OCR 抽檢皆確認呈現真實 App 介面且無文案缺陷。發行相關資產（Gradle、CHANGELOG、Fastlane 五語系說明）全部對齊且無超前宣稱。各模組 212 項 JVM 測試、字串同位檢查與無網路權限檢查全數通過。專案具備為 0.1.3 / versionCode 7 打 tag 並上傳 Google Play 之就緒狀態。

---

## 審查維度詳解

### 1. 第 26 輪審查發現之修正驗證（Round-26 Findings）

#### Important-1：`tap_tab` 點擊生效確認與畫面驗證
- **實作位置**：[`tools/demo-screenshots.sh:143-175`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L143-L175)（`tab-selected` helper）、[`tools/demo-screenshots.sh:348-363`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L348-L363)（`tap_tab` 函式）
- **機制分析**：
  1. Compose Material 3 的 `NavigationBarItem` 與 `NavigationRailItem` 透過 `Modifier.selectable(role = Role.Tab)` 將選中狀態寫入語意樹（`AccessibilityNodeInfo.isSelected` 在 `uiautomator dump` 中表現為 `selected="true"`）。
  2. 新增的 `tab-selected` 指令首先搜尋帶有目標標籤（文字或 content-desc）且位於導覽區間（窄版底部 15% 或寬版左側 15%）的節點，計算其幾何中心點 `centre(box)`。
  3. 接著檢查該中心點是否落在任何帶有 `selected="true"` 的容器節點 bounds 內。
  4. `tap_tab` 在送出點擊座標後，以 5 次循環（每次間隔 1 秒並執行帶重試的 `dump_ui`）呼叫 `tab-selected` 進行驗證，若 5 次皆未確認選中則回傳 1 並觸發呼叫端的 `die`。
- **針對 Brief 問題之判定**：
  - **畫面未切換時是否仍能通過？**
    **不會**。若點擊被吞掉或無效，舊分頁保持選中狀態，目標標籤的幾何中心點無法落在舊分頁的 `selected="true"` 容器內，`tab-selected` 必然回傳 1，重試超時後腳本立即終止（fail loud）。
  - **原本會成功的執行是否可能在此失敗？**
    在正常模擬器環境下**不會**。Compose 分頁切換一般在數十毫秒至 1 秒內完成，5 秒的重試窗口提供了充足的餘裕。唯一可能失敗的情境是測試裝置或模擬器嚴重卡頓（超過 5 秒無法完成渲染或 UI dump 連續 3 次失敗），但這屬於需要暴露的異常狀態，而非誤報。

#### 其他 Round-26 項目之收斂確認
- **Minor-1（`in_bottom_bar` 窄版守衛）**：
  [`tools/demo-screenshots.sh:132`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L132) 已補上 `layout == "narrow"` 守衛，寬版面不再可能誤匹配底部 15% 區域。
- **Minor-2（`in_left_rail` 左緣邊界守衛）**：
  [`tools/demo-screenshots.sh:137`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L137) 已補上 `box[0] <= int(width * 0.15) and box[2] <= int(width * 0.15)`，嚴格限定候選節點左右邊界皆在最左 15% 內。
- **Minor-3（`LAYOUT` 前向引用）**：
  [`tools/demo-screenshots.sh:53-55`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L53-L55) 在檔案全域常數區宣告 `LAYOUT="narrow"`，消除 `set -u` 下的前向引用風險。
- **Minor-4（`dump_ui` 重試）**：
  [`tools/demo-screenshots.sh:318-328`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L318-L328) 新增 3 次重試循環（間隔 1 秒），避免窗口未 idle 導致單次 dump 失敗使整個 run 退出。
- **Minor-5（`screen_dp_width` 實體螢幕假設）**：
  [`tools/demo-screenshots.sh:487-490`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L487-L490) 於註解中清楚說明其假設直立、全螢幕，誤判時會 fail loud（`tap_tab dies`）。
- **Minor-6（`TEST_MATRIX` 對話頁描述）**：
  [`docs/TEST_MATRIX.md:74`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L74) 與 [`docs/zh-Hant/TEST_MATRIX.md:65`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L65) 均已標註「(on the narrow layout)」／「在窄版面」。
- **夜間模式失敗防護**：
  [`tools/demo-screenshots.sh:661`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L661) 由原先的 `|| warn` 改為 `|| die "could not switch the device to night mode — 7_inbox_dark would be the light inbox"`，防止淺色截圖混入深色資產。
- **JVM 與真機測試指令完整性**：
  [`CONTRIBUTING.md:21-22`](file:///Users/iml1s/Documents/mine/quietinbox/CONTRIBUTING.md#L21-L22)、[`docs/zh-Hant/CONTRIBUTING.md:20-21`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/CONTRIBUTING.md#L20-L21) 與 [`README.md:85-87,187`](file:///Users/iml1s/Documents/mine/quietinbox/README.md#L85-L87) 均已同步納入 CI 實際執行的完整測試組（包含 `:platform:backup`）。

---

### 2. 兩處 App 字串缺陷修復驗證（The Two String Fixes）

#### (1) `inbox_summary` 轉為雙 `plurals` 與 joiner
- **資源定義**：
  - 預設目錄 [`values/strings.xml:51-59`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values/strings.xml#L51-L59)：
    - `inbox_summary_saved` 包含 `one`（`"%1$d recognisable message saved."`）與 `other`（`"%1$d recognisable messages saved."`）。
    - `inbox_summary_uncertain` 包含 `one`（`"Plus %1$d observation with uncertain identity."`）與 `other`（`"Plus %1$d observations with uncertain identity."`）。
    - `inbox_summary_join` 定義為 `"%1$s %2$s"`（中間留空格）。
  - 中日韓目錄（`values-b+zh+Hant`、`values-b+zh+Hans`、`values-ja`、`values-ko`）：
    - 均只有 `other` 項目，無多餘的 `one` 項目，完全符合東亞語言無單複數語法區隔之規範。
    - 中文與日文的 `inbox_summary_join` 為 `"%1$s%2$s"`（無空格），韓文為 `"%1$s %2$s"`（分詞空格），符號與格式完全精準。
- **呼叫端邏輯**：
  [`feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt:222-232`](file:///Users/iml1s/Documents/mine/quietinbox/feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt#L222-L232)：
  ```kotlin
  val saved = state.counts.messages - state.counts.ambiguous
  val savedText = pluralStringResource(R.plurals.inbox_summary_saved, saved, saved)
  val base = if (state.counts.ambiguous == 0) {
      savedText
  } else {
      stringResource(
          R.string.inbox_summary_join,
          savedText,
          pluralStringResource(R.plurals.inbox_summary_uncertain, state.counts.ambiguous, state.counts.ambiguous),
      )
  }
  ```
  - 當 `ambiguous == 0` 時，直接回傳 `savedText`，完全省略不確定觀測的子句（不再出現「另有 0 筆」或「plus 0 observations」）。
  - 當 `ambiguous > 0` 時，透過 `inbox_summary_join` 拼接兩句，邏輯乾淨且邊界安全。

#### (2) `health_since` 改為 `health_connected_body_since`
- **資源定義**：
  - 舊有的獨立片語 `health_since`（`"since %1$s"`）已自全部 5 個目錄中徹底移除。
  - 新增整合式單句 `health_connected_body_since` 於 5 個目錄，直接將時間戳記嵌入完整句子：
    - en: `"Connected since %1$s, but that does not guarantee the source posts a notification for every message."`
    - zh-TW: `"自 %1$s 起連線，但已連線不代表來源 App 每則訊息都會發通知。"`
    - zh-CN: `"自 %1$s 起连接，但已连接不代表来源应用的每条消息都会发出通知。"`
    - ja: `"%1$s から接続していますが、送信元がすべてのメッセージに通知を出すとは限りません。"`
    - ko: `"%1$s부터 연결되어 있지만, 소스 앱이 모든 메시지에 알림을 보낸다는 보장은 없습니다。"`
- **呼叫端邏輯**：
  [`feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt:332-334`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/HealthScreen.kt#L332-L334)：
  ```kotlin
  ListenerState.CONNECTED -> state.capture.connectedSinceEpochMs?.let {
      stringResource(R.string.health_connected_body_since, TimeFormat.time(it, locale = currentLocale()))
  } ?: stringResource(R.string.health_connected_body)
  ```
  徹底消除了過去將句號後拼接小寫片語（`"…for every message. since 11:18 PM"`）與中文多餘空格的問題。

- **靜態工具驗證**：
  執行 `python3 tools/check-strings.py` 結果為 `OK: 0 error(s), 0 warning(s)`，無任何孤立字串或佔位符不一致。

---

### 3. 49 張商店截圖品質與位元組一致性驗證（The 49 Screenshots）

- **截圖組成**：
  - 5 個手機語系（`en-US`、`ja-JP`、`ko-KR`、`zh-CN`、`zh-TW`）× 7 張（`1_inbox` ~ `7_inbox_dark`）= 35 張（解析度 1080×2400）。
  - 2 個平板語系（`en-US`、`zh-TW`）× 7 張 = 14 張（解析度 2076×2152）。
  - 共計 49 張 PNG 檔案。
- **Byte-identical 驗證**：
  比對 [`docs/screenshots/**`](file:///Users/iml1s/Documents/mine/quietinbox/docs/screenshots) 與 [`fastlane/metadata/android/**`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android) 對應路徑之 SHA-256 雜湊值，**全部 49 對檔案位元組完全一致**。
- **畫面內容與文字 OCR 驗證**：
  1. **英文收件匣單數驗證**：
     對 `phone/en-US/1_inbox.png` 與 `tablet/en-US/1_inbox.png` 執行 OCR，均清晰呈現：
     `"128 recognisable messages saved. Plus 1 observation with uncertain identity."`
     確定為單數 **"Plus 1 observation"**，舊有的 "plus 1 observations" 已修正。
  2. **英文擷取健康頁單句化驗證**：
     對 `phone/en-US/5_capture.png` 執行 OCR，清晰呈現單一連貫句子：
     `"Connected since 11:56 PM, but that does not guarantee the source posts a notification for every message."`
     無斷句殘片。
  3. **繁體中文平板截圖真偽檢驗**：
     對 `tablet/zh-TW/*.png` 進行抽檢，`1_inbox.png` 出現「收件匣」、「已保存 128 則可辨識通知訊息。另有 1 筆身分不明觀測。」；`6_settings.png` 出現「動態色彩」；`7_inbox_dark.png` 確認為深色主題收件匣。原先在第 26 輪出現的 Pixel Launcher 與 Android Settings 系統畫面已徹底消失，全部為 QuietInbox 本體真實介面。

---

### 4. 0.1.3 / versionCode 7 發行就緒性檢驗（Release Readiness）

- **Gradle 設定**：
  [`app/build.gradle.kts:50-51`](file:///Users/iml1s/Documents/mine/quietinbox/app/build.gradle.kts#L50-L51) 已將 `versionCode` 升至 `7`，`versionName` 升至 `"0.1.3"`。
- **變更記錄（CHANGELOG）**：
  [`CHANGELOG.md:9-44`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L9-L44) 建立 `## [0.1.3] — 2026-09-06` 區塊，詳實記錄兩處使用者可見之字串修正、平板截圖工具之重構與防禦加固。
- **Google Play 商店更新說明一致性與長度限制（<= 500 字）**：
  - `en-US`：428 字元
  - `ja-JP`：155 字元
  - `ko-KR`：178 字元
  - `zh-CN`：97 字元
  - `zh-TW`：97 字元
  五語系之 [`fastlane/metadata/android/*/changelogs/7.txt`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android)、[`fastlane/whatsnew/whatsnew-*`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/whatsnew) 與 [`fastlane/release-notes.json`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/release-notes.json) 經自動化指令比對，內容與位元組 100% 相同。
- **文案語氣與範疇**：
  更新說明專注於向一般使用者解釋兩處文案的具體修正（收件匣摘要單複數及 0 筆省略、連線健康時間整合），明確強調「擷取、儲存與你的資料都沒有改變」，未夾帶開發內部工具或重構細節，符合商店使用者的預期與信任感。

---

### 5. 文件真實性檢驗（Docs Never Run Ahead of Code）

- 本專案規範嚴禁文件領先於程式碼狀態（特別是尚未發生的發行或上傳）：
  - [`README.md:29, 129`](file:///Users/iml1s/Documents/mine/quietinbox/README.md#L29) 均明確維持：「目前上架的是 0.1.0；0.1.2（審計修正、簡中／日／韓）已於 2026-09-06 送 Google 審查，通過後自動更新。要立刻拿到 0.1.2 請用 GitHub 版」，未將 0.1.3 宣稱為已發布或已送審。
  - [`docs/SCOPE.md:35`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L35) 與 [`docs/zh-Hant/SCOPE.md:35`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/SCOPE.md#L35) 維持狀態為「完成（Play：0.1.0 於 2026-09-06 上架、0.1.2 審查中；GitHub：0.1.2）」。
  - [`docs/reviews/README.md:42`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L42) 與 [`docs/zh-Hant/reviews/README.md:40`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L40) 將第 26 輪處理結果正確登記為 `pending`，反映當前正處於 mini re-review 之事實。
  - 全倉庫無任何超前聲稱 0.1.3 已在 GitHub 發布或已在 Google Play 上線的語句。

---

### 6. 中英雙語對稱性檢查（English / Traditional Chinese Parity）

- **工作流程指引**：
  [`CONTRIBUTING.md:21-22`](file:///Users/iml1s/Documents/mine/quietinbox/CONTRIBUTING.md#L21-L22) 與 [`docs/zh-Hant/CONTRIBUTING.md:20-21`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/CONTRIBUTING.md#L20-L21) 均同步新增 `./gradlew test` 及 CI 實際跑的 14 個測試模組命令。
- **README 測試指引**：
  [`README.md:85-87`](file:///Users/iml1s/Documents/mine/quietinbox/README.md#L85-L87)（繁中）與 [`README.md:187`](file:///Users/iml1s/Documents/mine/quietinbox/README.md#L187)（英文）均同步加入 `:platform:backup:connectedDebugAndroidTest`。
- **測試矩陣**：
  [`docs/TEST_MATRIX.md:74`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L74)（"the conversation shot waits (on the narrow layout) until..."）與 [`docs/zh-Hant/TEST_MATRIX.md:65`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L65)（"對話頁截圖在窄版面會等到..."）精準對稱。
- **審查記錄索引**：
  [`docs/reviews/README.md:42`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L42) 與 [`docs/zh-Hant/reviews/README.md:40`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L40) 第 26 輪記錄逐字對應。

---

### 7. 建置與安全性驗證（Build & Permissions Verification）

- `./gradlew test :app:assembleDebug`：建置成功，450 項 Gradle 任務正常執行，212 項 JVM 單元測試全數通過。
- `tools/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk`：驗證通過，輸出 `OK: no network permission in app/build/outputs/apk/debug/app-debug.apk`，未引入任何聯網或未授權權限。
- `python3 tools/check-strings.py`：驗證通過（0 errors, 0 warnings）。

---

## 審查發現評級

### Critical（必須在 Tag 前修正）
*無*（0 項）。

---

### Important（應在 Tag 前修正）
*無*（0 項）。

---

### Minor / nitpicks

1. [`tools/demo-screenshots.sh:137`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L137)、[`tools/demo-screenshots.sh:168`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L168)
   ```python
   in_left_rail = layout == "wide" and box[0] <= int(width * 0.15) and box[2] <= int(width * 0.15)
   ```
   **說明**：幾何定義上 `box[0]` 為節點 left，`box[2]` 為節點 right，恆有 `box[0] <= box[2]`。當 `box[2] <= int(width * 0.15)` 成立時，`box[0] <= int(width * 0.15)` 必然成立。此處雙重檢查屬於防禦性冗餘，不影響邏輯正確性，但代碼上略顯贅述。

2. [`CHANGELOG.md:28-29`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L28-L29)
   ```markdown
   Both locales are re-shot from the demo vault and inspected one by one.
   ```
   **說明**：第 26 輪審查曾建議在此處補述「Play listing 仍待發布流程執行 `gplay images sync`」，避免讀者誤以為商店線上資產已自動更新。雖然 0.1.3 本身的發行流程（[`docs/RELEASE.md:38-43`](file:///Users/iml1s/Documents/mine/quietinbox/docs/RELEASE.md#L38-L43)）即包含在維護者機器上執行 `images delete-all --type tenInchScreenshots --confirm` 與 `images sync`，使此條目作為 0.1.3 正式發布後的歷史紀錄完全合情合理，但在當前尚未上傳前，若能隨附一句提醒，將更符合極致嚴密的文檔規範。

---

### Other observations（其他觀察）

1. [`fastlane/metadata/android/zh-TW/changelogs/7.txt:1`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/zh-TW/changelogs/7.txt#L1)
   五語系的更新說明極為精確簡練，且繁中、簡中皆精準控管在 97 字元，遠低於 Google Play 的 500 字上限，在地化文案風格自然流暢。
2. [`tools/demo-screenshots.sh:318-328`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L318-L328)
   `dump_ui` 加入 3 次重試後，大幅降低了 CI 或本機模擬器因窗口動畫尚未靜止導致的瞬態失敗，截圖生成管線的穩定度與強韌度（robustness）明顯提升。
3. [`feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt:222`](file:///Users/iml1s/Documents/mine/quietinbox/feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt#L222)
   `saved = state.counts.messages - state.counts.ambiguous`：在資料庫層面，`ambiguous` 是 `messages` 計數加上 `is_ambiguous = 1` 條件查詢之子集，因此相減值恆大於等於 0，無負數風險。

---

## 結論

Commit `b9b49cc` 乾淨俐落地收斂了 Round 26 的全部意見，同時完成了對 0.1.3（`versionCode` 7）的發布準備。代碼品質良好、資產齊備、測試全綠、無文件超前問題。
審查判定：**APPROVE**。可立即進行 git tag `v0.1.3` 並執行後續之發布與 Google Play 商店資產同步。
