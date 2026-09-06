# Round 24 獨立審查（Gemini 3.8 Flash high via agy）— QuietInbox 0.1.2 發布就緒審查

- **審查對象**：
  - 本地 `main` 分支 HEAD `0bf44ba`（commit: `release: 0.1.2 (versionCode 6) — three more languages, rounds 13–23 fixes`）。
  - 版本躍升差異：`git diff 996f8d7..0bf44ba`。
  - 自標籤 `v0.1.1` 以來的完整提交歷程：`git log v0.1.1..0bf44ba`（共 14 個 commit，涵蓋 rounds 13–23 所有修正與發布準備）。
- **審查模式**：嚴格唯讀（Read-Only）。未建立、編輯、移動或刪除任何儲存庫檔案，未執行狀態變更指令。
- **執行的唯讀驗證**：
  - `python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`。
  - `gplay validate screenshots --dir fastlane/metadata/android --output table` → `valid: true`（五語系 phoneScreenshots 各 7 張；en-US、zh-TW tenInchScreenshots 各 7 張）。
  - 字元數全面盤點（`wc -m` / Node 腳本）：
    - 5 語系 `fastlane/metadata/android/<locale>/changelogs/6.txt`、`whatsnew/whatsnew-<locale>` 與 `release-notes.json`：en-US (483), ja-JP (252), ko-KR (283), zh-CN (182), zh-TW (187)，全數小於 Google Play 500 字元限制。
    - 5 語系商店中繼資料：`title.txt`（10~13 / 上限 30）、`short_description.txt`（36~78 / 上限 80）、`full_description.txt`（928~2467 / 上限 4000）。
  - 釋出說明提取驗證：執行 CI 相同之 `awk -v ver="0.1.2" ... CHANGELOG.md`，確認精準擷取 `## [0.1.2]` 區段且在下一版本 `## [0.1.1]` 前結束。
  - 權限門檻比對：release APK 宣告之權限與 `tools/check-permissions.sh` 比對，確認無任何網路相關權限。

---

## Verdict: APPROVE

本 commit（`0bf44ba`）與整體 0.1.2 發布內容各方面皆已齊備且經過嚴謹驗證，無任何阻擋發布（tag / upload）的問題：

1. **商店文案嚴謹合規**：五語系文案皆小於 500 字元限制；用詞完全吻合各語系資源檔（`strings.xml`）；0.1.1 審計修正說明精確且無誇大承諾；`changelogs/6.txt`、`whatsnew-*` 與 `release-notes.json` 三處完全一致。
2. **CHANGELOG 折疊乾淨**：`[Unreleased]` 順利收攏為「Nothing yet.」；0.1.2 標題與導言清楚完整；`Known issues in 0.1.1 (fixed in 0.1.2)` 正確收錄在 0.1.1 區段末尾，精準指明了 AndroidX 中文資源遺失與 Android 13+ 行程存活時語言格式未連動兩大缺陷。
3. **文件無超前程式碼**：`docs/SCOPE.md`（含中文版）維持 0.1.0 審查中／0.1.1 GitHub 發布之現狀，待發布作業完成後再補提交（符合 `537ad80` 既定慣例）；其餘文件（RELEASE、README、CLAUDE、TEST_MATRIX）皆精確同步。
4. **發布機制運作正確**：`.github/workflows/release.yml` 的 Release Notes `awk` 提取語法與本版本 CHANGELOG 結構完全相符；權限閘門與產物封裝邏輯完備。
5. **Play 上架營運計畫安全無虞**：使用 `gplay` CLI 進行單次 edit 操作的順序（create → bundle → listings → delete phone screenshots → sync images → track update → validate → commit）合乎 Google Play API 規範；跳過未登陸 Play 的 0.1.1 是正確的營運決策。

---

## 缺陷與問題清單

- **Critical (must fix before tag/upload)**：無。
- **Important (should fix before tag/upload)**：無。
- **Minor / nitpicks**：
  1. **商店更新文案 en-US 與其他 4 語系的微小不對稱（字元預算取捨）**：
     - 在 `zh-TW`、`zh-CN`、`ja-JP`、`ko-KR` 的版本 6 更新說明中，皆包含「`到期副本立即隱藏`」（ja: `期限切れのコピーは即座に非表示` / ko: `만료된 사본은 즉시 숨김`）。
     - 在 `en-US` 中，此句被省略（目前長度 483 字元；若加上 `expired copies are hidden at once; ` 共約 35 字元，總長將達 518 字元而突破 Google Play 的 500 字元硬限制）。此取捨合理且必要，不影響發布，但建議在跨語系文案紀錄中備註此項非對稱係因長度限制所致。
  2. **Play CLI 指令中的 `--confirm` 旗標提醒**：
     - Brief 中之步驟提及 `images delete-all --type phoneScreenshots for en-US and zh-TW`。
     - 需注意 `gplay images delete-all` 指令強烈要求 `--confirm` 旗標（預設為 false，未帶會直接拒絕執行），執行時請務必加上 `--confirm`。

---

## 六大維度詳細審查分析

### 1. 商店更新說明（Store notes, 5 languages）

- **長度門檻檢查（Google Play 硬限制 ≤ 500 字元）**：
  - `en-US`: 483 字元（含換行 484）— 符合（裕度 17 字元）
  - `zh-TW`: 187 字元（含換行 188）— 符合
  - `zh-CN`: 182 字元（含換行 183）— 符合
  - `ja-JP`: 252 字元（含換行 253）— 符合
  - `ko-KR`: 283 字元（含換行 284）— 符合
- **三處同步核對**：
  - `fastlane/metadata/android/<locale>/changelogs/6.txt`
  - `fastlane/whatsnew/whatsnew-<locale>`
  - `fastlane/release-notes.json`
  - 經 Node 腳本自動比對，五個語系在上述三處檔案內容 **100% 逐字元相符**。
- **專有名詞與詞彙目錄一致性**：
  - **繁體中文 (zh-TW)**：
    - 使用詞彙：`來源`（對應 `values-b+zh+Hant/strings.xml` 之 `來源：%1$s`）、`副本`、`圖片複製`（對應 `media_disclosure_title` 關於媒體副本）、`揭露`、`「刪除所有資料」`（精確對應 `delete_everything` 之文字與「」引號）、`到期副本立即隱藏`、`搜尋`、`提醒`。
  - **簡體中文 (zh-CN)**：
    - 使用詞彙：`来源`（對應 `values-b+zh+Hans/strings.xml`）、`副本`、`图片复制`、`披露`、`“删除所有数据”`（精確對應 `delete_everything` 之文字與“”引號）、`到期副本立即隐藏`、`搜索`、`提醒`。
  - **日文 (ja-JP)**：
    - 使用詞彙：`ソース`、`コピー`、`画像のコピー`、`注意事項`、`「すべてのデータを削除」`（精確對應 `delete_everything`）、`期限切れのコピーは即座に非表示`、`検索`、`リマインダー`。
  - **韓文 (ko-KR)**：
    - 使用詞彙：`소스`、`사본`、`이미지 복사`、`안내`、`‘모든 데이터 삭제’`（精確對應 `delete_everything`）、`만료된 사본은 즉시 숨김`、`검색`、`리마인더`。
- **內容真實性與無過度宣稱**：
  - 嚴格限定於 App 實際能力（無宣稱「能回覆」、「能讀取未發出的訊息」等）。
  - 對 0.1.1 審計修復的描述精準對照 `CHANGELOG.md`（停止來源停止儲存、披露同意前關閉圖片複製、刪除所有資料步驟驗證、搜尋後續匹配、未讀提醒觸發）。
  - 格式純淨：無 Markdown 標記、無換行雜訊，符合 Play 審查規範。

### 2. CHANGELOG 折疊與缺陷陳述（CHANGELOG fold）

- **折疊完整性**：
  - `## [Unreleased]` 下清空為 `Nothing yet.`。
  - `## [0.1.2] — 2026-09-06` 承接所有 Rounds 13–23 累積的 Added 與 Fixed 條目，無任何項目遺漏或重複。
  - 導言清楚點出 `versionCode 6`、三種新語言支援、Rounds 13–23 修正、以及 Play 使用者自 0.1.0 直升 0.1.2 因而文案納入 0.1.1 修正的背景。
- **`Known issues in 0.1.1 (fixed in 0.1.2)` 區塊檢驗**：
  - 位於 0.1.1 節末、0.1.0 節前，歸屬清晰。
  - 正確指出已於 0.1.2 修復的兩大瑕疵：
    1. **AndroidX 中文資源遺漏**：`localeFilters` 原先僅保留 `b+zh+Hant`，導致 Material3 日期時間選擇器與 content descriptions 回退為英文（現已於 `app/build.gradle.kts` 保留 `zh-rTW` / `zh-rCN` / `zh-rHK`）。
    2. **行程存活時日期時間未依 App 語言格式化**：因 NotificationListenerService 常駐，原先使用 `Locale.getDefault()` 無法即時感應 Android 13+ 應用程式語言切換（現已改由 Compose Composition 的 `currentLocale()` 驅動，並有 `TimeFormatTest` 守護）。

### 3. 文件狀態與程式碼同步性（Docs not ahead of code）

- **發行狀態列設計（SCOPE.md）**：
  - `docs/SCOPE.md` 與 `docs/zh-Hant/SCOPE.md` 中的發行列載明：`Play: 0.1.0 under Google review; GitHub: 0.1.1`。
  - 此設計與 0.1.1 時的 commit `537ad80` 一致：將 SCOPE 與 RELEASE 的發行完成狀態保留給上傳／發布「落地之後」的專屬 docs commit，避免文件超前於尚未完成的外部發布行為。此策略完全合理且符合專案一貫流程。
- **其餘文檔盤點**：
  - `docs/RELEASE.md`（含中文版）：發行步驟 1–5 與本次 0.1.2 演練完全吻合。
  - `README.md`、`CLAUDE.md`：無硬編碼版本號衝突，指令皆維持最新。
  - `docs/TEST_MATRIX.md`（含中文版）：CaptureCoordinatorTest 數量（32 個）與截圖工具 5 語系參數皆已完美對齊。

### 4. 發布自動化機制（Release mechanics）

- **`.github/workflows/release.yml` 檢視**：
  - 標籤觸發：推送 `v0.1.2` 標籤時，自動執行 `build` 與 `github-release`。
  - 產物建置與簽章：建置 signed APK 與 AAB，產生 `SHA256SUMS.txt`。
  - 權限門檻：執行 `tools/check-permissions.sh`，檢驗無任何網路權限（`INTERNET`、`ACCESS_NETWORK_STATE`、`QUERY_ALL_PACKAGES` 等）。
  - **Release Notes 提取腳本檢驗**：
    - 腳本行：`NOTES=$(awk -v ver="${TAG#v}" '/^## \[/{p = index($0, "[" ver "]") > 0} p' CHANGELOG.md)`
    - 本地實測 `ver="0.1.2"`：
      - 當掃描到 `## [0.1.2]` 時，`p` 設為 1，開始輸出。
      - 當掃描到下一版本標題 `## [0.1.1]` 時，匹配 `/^## \[/` 觸發條件，`index($0, "[0.1.2]")` 為 0，使 `p` 變為 0，立即停止輸出。
      - 測試結果：**100% 精準擷取 0.1.2 完整區塊**，不會夾帶 0.1.1 內容，亦不會因 `Known issues in 0.1.1` 內的標題受到干擾。

### 5. Google Play 營運計畫分析（Operational Play plan）

使用 `gplay` CLI 於單一 edit session 進行發布的建議順序與注意事項：

```bash
# 1. 建立 Edit Session
EDIT_ID=$(gplay edits create --package dev.quietinbox.app | jq -r '.id')

# 2. 上傳 AAB 檔案（必須在 tracks update 前完成）
gplay bundles upload --package dev.quietinbox.app --edit "$EDIT_ID" --file dist/quietinbox-0.1.2.aab

# 3. 匯入商店基本資料（建立 ja-JP、ko-KR、zh-CN，更新 en-US、zh-TW）
# 注意：必須先於圖片上傳，以確保 Play 建立對應語系
gplay sync import-listings --package dev.quietinbox.app --edit "$EDIT_ID" --dir fastlane/metadata/android

# 4. 清除舊有的手機截圖（避免 7 + 7 = 14 超出 8 張上限）
# 重要：--confirm 為必填旗標！
gplay images delete-all --package dev.quietinbox.app --edit "$EDIT_ID" --locale en-US --type phoneScreenshots --confirm
gplay images delete-all --package dev.quietinbox.app --edit "$EDIT_ID" --locale zh-TW --type phoneScreenshots --confirm

# 5. 同步所有語系的圖形與截圖（依 SHA-256 差異上傳，未變動的 10 吋平板截圖會自動保留）
gplay images sync --package dev.quietinbox.app --edit "$EDIT_ID" --dir fastlane/metadata/android

# 6. 更新正式軌道（production track）
# 注意：releases.json 應為包含 TrackRelease 物件的陣列，versionCodes 為 ["6"]
gplay tracks update --package dev.quietinbox.app --edit "$EDIT_ID" --track production --releases @releases.json

# 7. 驗證與提交審查
gplay edits validate --package dev.quietinbox.app --edit "$EDIT_ID"
gplay edits commit   --package dev.quietinbox.app --edit "$EDIT_ID"
```

- **略過 `changelogs/5.txt` 的正確性**：
  - 完全正確。0.1.1 僅在 GitHub 發布，從未進入 Google Play。Play 上的版本直接由 0.1.0（versionCode 4）升級至 0.1.2（versionCode 6）。在 Play API 中，Release Notes 是綁定於特定 release 的版本號；既然 versionCode 5 不在該軌道上發布，便無需也不應上傳 5.txt。versionCode 6 的更新說明已完整收錄 0.1.1 審計修復要點。
- **順序危險性評估**：
  - `sync import-listings` 在 `images sync` 之前：正確。Play API 要求語系 Listing 存在後方能接受該語系的截圖上傳。
  - `images delete-all` 在 `images sync` 之前：正確。否則 7 張現有截圖加上 7 張新截圖會因超出 8 張限制而回傳 400 Bad Request。
  - `bundles upload` 在 `tracks update` 之前：正確。軌道發布需要參照已上傳的 versionCode 6。
  - 平板截圖安全：`en-US` 與 `zh-TW` 的 10 吋平板截圖自 0.1.0 起即無變更，`images sync` 具備 SHA-256 哈希比對能力，不會重複上傳或引發配額超限。

### 6. 其他阻止發布之阻擋項盤點

- 字串同動性檢查：`tools/check-strings.py` 執行結果為 0 錯誤、0 警告。
- 版本號與配置：`app/build.gradle.kts` 中 `versionCode = 6`、`versionName = "0.1.2"`、`targetSdk = 36`、`minSdk = 26`，`localeFilters` 包含所有 8 個語系配置識別符。
- 專案工作區狀態：乾淨無任何未追蹤或未提交之程式碼變更（僅有本輪唯讀審查之 brief 與報告檔案）。
- 結論：無任何阻擋打標記（tag）或上傳（upload）之情事。

---

## 總結評語

QuietInbox 0.1.2 在歷經 rounds 13–17 的安全審計修復收尾、rounds 18–22 的三語系本土化演進、以及 round 23 的截圖與時鐘格式強固之後，整體儲存庫在架構安全、語系品質、截圖可靠度與發布自動化面上均達到高度成熟水準。本次 0.1.2 的發布設定、更新文案與營運計畫完備無誤，建議直接核可發布。

---

*報告檔案已寫入：`/Users/iml1s/Documents/mine/quietinbox/.omc/research/dual-review-round24-agy.md`*
