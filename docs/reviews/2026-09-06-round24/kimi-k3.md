我先讀取 review brief，了解本次審查範圍。發現 en-US 與其他四語的 0.1.1 修正清單不一致（缺「到期副本」條款）。繼續核對術語與程式碼。再核對幾個術語細節、changelogs 目錄結構與 git 狀態。最後檢查商店文案長度上限（title 30 / short 80 / full 4000），然後撰寫報告。所有查證完成。現在撰寫繁體中文報告並輸出。報告已寫入 `.omc/research/dual-review-round24-kimi.md`。以下為完整內容：

---

# Round 24 審查報告：0.1.2（versionCode 6）發布就緒度 — Kimi

審查範圍：`git diff 996f8d7..0bf44ba`（0.1.2 版本跳號單一 commit）加上 v0.1.1..0bf44ba 整體（rounds 13–23）。唯讀審查，未建置、未跑裝置。

## Verdict: APPROVE WITH MINOR FIXES

可以下 tag、可以上傳。唯一需要在送審前處理的是五語商店文案之間的一處內容不一致（en-US 少一條 0.1.1 修正條款）；其餘為文件層級的小問題。

## Critical（tag／上傳前必須修）

無。

## Important（tag／上傳前應修）

1. **en-US 商店文案缺少「到期副本立即隱藏」條款，五語內容不一致。**
   - `fastlane/metadata/android/en-US/changelogs/6.txt:1`、`fastlane/whatsnew/whatsnew-en-US:1`、`fastlane/release-notes.json`（en-US 物件）。
   - zh-TW／zh-CN／ja-JP／ko-KR 四語的 0.1.1 修正清單都有「到期副本立即隱藏／到期副本立即隐藏／期限切れのコピーは即座に非表示／만료된 사본은 즉시 숨김」（對應 CHANGELOG 0.1.1 的 #7），唯獨 en-US 沒有。
   - 看起來是無意疏漏而非取捨：0.1.1 的舊 en-US 文案（`release-notes.json` diff 中被取代的那行）本來就有 "expired copies are hidden at once"。
   - 限制：en-US 目前 483 字元（上限 500），直接補 "; expired copies are hidden at once"（約 +35）會爆到約 518。需要順手刪減，例如 "the date and time pickers are Chinese for Chinese users" 可縮為 "pickers are Chinese for Chinese users"（釋出約 18 字元），再補該條款即可壓回 500 內。

## Minor / nitpicks

2. **CLAUDE.md:48 已過時**：仍寫 "all strings (en + zh-Hant, parity checked)"，但 0.1.2 出貨五個語系（en / zh-Hant / zh-Hans / ja / ko）。README.md:70 與 TEST_MATRIX.md:17 皆已更新為五語。這是 7ef07de 加語系時留下的，不是本次 commit 造成；可在上傳後的 docs commit 一併修，不阻擋 tag。
3. **術語小出入（可不改）**：zh-TW／zh-CN 商店文案用「揭露／披露」稱呼媒體揭露，但 App 內實際字串是「關於媒體副本／关于媒体副本」（`media_disclosure_title`，values-b+zh+Hant/strings.xml、values-b+zh+Hans/strings.xml），目錄中「揭露」「披露」出現次數為 0。使用者看得懂，若要完全一致可改為「在你接受媒體副本說明前」。ja 的「注意事項」對 App 內「メディアのコピーについて」同屬軟性出入；ko 的「안내」與 App 內「미디어 사본 안내」一致。
4. 舊的 `changelogs/5.txt`（五語都還留著 0.1.1 文案，zh-TW 版還用舊稱「保險庫」而非現行「金庫」）——0.1.1 從未上 Play、未來也不會，留著無害，僅供記錄。

## Other observations

**商店文案（維度 1）— 其餘全部通過：**
- 字元數：en-US 483、zh-TW 187、zh-CN 182、ja-JP 252、ko-KR 283，全部 ≤ 500（`wc -m` 與 Python `len()` 皆驗過；三種載體 `changelogs/6.txt`＝`whatsnew-<locale>`＝`release-notes.json` 逐語系 `cmp` 完全相同）。
- 0.1.1 審計條款對 CHANGELOG 0.1.1 逐條核實：停止／暫停／移除來源（#1）、媒體副本預設關閉待揭露同意（#2）、刪除所有資料逐步驗證（#3）、搜尋不漏後續命中（#11）、提醒只在有未讀時發出（#15，文案寫「有未讀」是合理改寫）、到期副本隱藏（#7，四語有）。無過度宣稱：沒有「回覆」「已讀」「所有訊息」或任何來源 App 未發佈內容的暗示。
- 術語核對目錄：來源（zh-Hant 目錄 24 處「來源」）／来源／ソース（ja 11 處）／소스（ko 26 處）✓；「刪除所有資料／删除所有数据／すべてのデータを削除／모든 데이터 삭제」與 `delete_everything` 字串完全一致（strings.xml:272 各語）✓；ja「キャプチャ」、ko「캡처」與目錄一致 ✓。各語行文自然、無 markdown、無多餘換行。
- 其他商店欄位也在 Play 上限內：title 10–13（≤30）、short 36–78（≤80，en-US 78 接近上限但合規）、full 929–2468（≤4000）。
- `python3 tools/check-strings.py`：0 error, 0 warning。

**CHANGELOG 折疊（維度 2）— 通過：**
- diff 確認 `[Unreleased]` 的既有內容整段移到 `[0.1.2]` 之下、無遺失無重複，`[Unreleased]` 現為 "Nothing yet."。
- 導言行（「rounds 13–23 fixes on top of 0.1.1」、Play 用戶 0.1.0→0.1.2 的說明）與 commit 範圍相符。
- 「Known issues in 0.1.1」區塊與程式碼核實：(a) `localeFilters` 舊值只留 `b+zh+Hant`、現值含 `zh-rTW`/`zh-rCN`/`zh-rHK`（app/build.gradle.kts:76，註解亦說明理由）；(b) 日期時間現走 composition 的 `currentLocale()`（core/designsystem/.../Formatting.kt:41），`TimeFormat` 三個函式都要求顯式 `locale`（Formatting.kt:20–27，KDoc 明說是防範此 bug 回歸）。兩條都正確標註「also in 0.1.0」。

**文件未超前程式碼（維度 3）— 通過，同意 deferral：**
- SCOPE.md:37 與 zh-Hant/SCOPE.md:35 的發行列仍寫「Play：0.1.0 審查中；GitHub：0.1.1」；RELEASE.md 的 Play 狀態行同。我**同意** brief 的判斷：這些列描述的是「已發佈」狀態，0.1.2 尚未在任何渠道發佈，上傳落地後再隨 docs commit 更新（537ad80 模式）是正確順序，不算文件超前。
- RELEASE.md:21 已列五語 listing locale，與 0.1.2 一致。README.md、TEST_MATRIX.md（含 zh-Hant 版）皆已反映五語與 round 13–23 的測試現況（212 JVM／16 instrumented）。唯 CLAUDE.md:48 落後（見 Minor #2）。

**發布機制（維度 4）— 通過：**
- CHANGELOG 萃取：`awk -v ver="0.1.2" '/^## \[/{p = index($0, "[" ver "]") > 0} p'`——`## [Unreleased]` 不含 `[0.1.2]`（p=0）、`## [0.1.2] — 2026-09-06` 起印、`## [0.1.1]` 停。萃取結果即完整 0.1.2 段落。逐行手算驗證可行。
- release.yml 本身無在此次 commit 上失敗的理由：版本跳號只動 `versionCode`/`versionName`；測試＋assembleRelease＋bundleRelease 已有綠燈證據（212 JVM tests、permission gate、簽章驗證）；`check-permissions.sh` 路徑未變；`inputs.tag || github.ref` 對 tag push 與 workflow_dispatch 兩路都成立。一個已知但可接受的行為：github-release job 的 `gh release create ... || gh release upload --clobber` 在 tag 重跑時會覆蓋資產，屬設計意圖。

**Play 操作計畫（維度 5）— 認可，無順序危害：**
- `edits create → bundles upload → sync import-listings → images delete-all（en-US/zh-TW phoneScreenshots）→ images sync → tracks update → validate → commit` 順序正確：tracks update 引用 versionCode 6，bundle 上傳在前 ✓。
- phoneScreenshots 的 delete-all 是必要的：Play 現存 7 張 0.1.0 截圖，上限 8 張，不刪直接傳 7 張會爆。zh-CN/ja-JP/ko-KR 是新語言、無舊圖，不需 delete；repo 端五語各 7 張 phone、icon、featureGraphic 齊全，tenInch 僅 en-US/zh-TW（各 7 張）✓，與計畫描述一致。
- `changelogs/5.txt` 從不上傳：無妨。Play 顯示的 release notes 來自 track release（releases.json 的五語文字），不來自 fastlane changelogs 檔；0.1.1 未上 Play，其文案本就無處可投。
- 唯一補充（非缺失）：commit 送審後，審查中的 0.1.0 production release 會被 0.1.2 取代，不需另外 retire——計畫未寫但這是正確預期。

**其他（維度 6）：**
- `git status` 乾淨，僅有本次審查的 untracked `docs/reviews/2026-09-06-round24/`，屬預期。
- 無其他阻擋 tag 或上傳的事項。
