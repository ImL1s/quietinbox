Diff 已看過。接下來驗證字數、三處 byte-identical、術語與 awk 擷取。awk 擷取正常。再驗證兩件事：awk 輸出不會溢出到 0.1.2 段之外，以及 CLAUDE.md 所稱的 CI parity 檢查屬實。全部檢查通過。現在寫出繁體中文報告並印出。第 25 輪迷你再審完成，報告已寫入 `.omc/research/dual-review-round25-kimi.md` 並印出（如上）。

**Verdict：APPROVE**（0 Critical／0 Important／0 Minor）

重點結論：

- 五項聲稱的修正全部屬實：英文文案補回「expired copies are hidden at once」（實測 468 字元）、五語改用 unviewed 系術語且與字串目錄對齊、ja/ko 移除中文選擇器子句並改全形冒號、CHANGELOG 導言不再斷言 Play 升級、CLAUDE.md 列出五個目錄（CI parity 抽查屬實）。
- 橫向檢查全數通過：五份文案字元數 187–468 皆 ≤ 500；`changelogs/6.txt`、`whatsnew-<locale>`、`release-notes.json` 三處 byte-identical；zh 保留中文選擇器子句的刻意差異維持正確；release.yml 的 awk 仍恰好擷取 0.1.2 一節；diff 僅觸及文件與文案，無程式碼回歸。
- 僅 3 條不阻擋觀察（CHANGELOG「one clause」措辭略寬鬆、zh「尚未查看」與目錄「未查看」的細微差異、ja 文案措辭較 en 具體），皆不需更動。
�／未读／未読／읽지 않은 殘留。
  - ja-JP 與 ko-KR 不再提及「中文使用者以中文顯示選擇器」，改為日期、時間與選擇畫面皆跟隨 App 語言；zh-TW／zh-CN 保留「選擇器對中文使用者也是中文」子句——刻意差異維持正確。
  - ja-JP 的冒號已改為全形「：」。
- **Subagent M-1（CHANGELOG 導言不再斷言 Play 升級路徑）**：已修復。`CHANGELOG.md` 0.1.2 導言改為「0.1.1 was a GitHub-only release」，不再把 Play 上傳寫成既成事實。
- **Kimi Minor 2（CLAUDE.md 版面行列出五個目錄）**：已修復。`CLAUDE.md` 現寫 `en, zh-Hant, zh-Hans, ja, ko; parity checked in CI`；抽查屬實——`.github/workflows/ci.yml:51` 確實執行 `python3 tools/check-strings.py`，且該腳本以 `values-*` glob 動態涵蓋五個語系目錄。
- **Reviews 索引第 24 列（en + zh）與歸檔**：已補。`docs/reviews/README.md` 與 `docs/zh-Hant/reviews/README.md` 皆新增第 24 列，內容對位一致；三份第 24 輪報告與 brief 已歸檔於 `docs/reviews/2026-09-06-round24/`。

## 橫向檢查

- **字數上限**：en 468、zh-TW 192、zh-CN 187、ja 238、ko 260（字元數），全部 ≤ 500。
- **三處 byte-identical**：逐一比對 `metadata/android/<locale>/changelogs/6.txt`、`whatsnew/whatsnew-<locale>` 與 `release-notes.json` 對應項目，五個語系皆完全相同。
- **五份文案語意對齊**：六個 0.1.1 審計修正子句（停止來源即停止副本、圖片複製待揭露同意、刪除所有資料逐步驗證、到期副本立即隱藏、搜尋不漏後續命中、提醒僅在有未查看時發出）在五語中皆存在且順序一致；唯一刻意差異即選擇器子句，如上所述。
- **發行 workflow 的 awk 擷取**：以 release.yml 的 `awk -v ver="${TAG#v}" '/^## \[/{p = index($0, "[" ver "]") > 0} p'` 對 0.1.2 實測，恰好擷取 0.1.2 一節（僅 1 個 `## [` 標題），未溢出到 0.1.1 段。CHANGELOG 改版後仍相容。
- **無回歸**：本 commit 僅觸及文件與商店文案（19 個檔案），無程式碼變更；SCOPE.md／RELEASE.md 的 Play 狀態行與 zh 文件對位等延後項未被更動，符合 brief 的延後聲明。

## 觀察（不阻擋）

1. CHANGELOG 導言稱商店文案「carry the 0.1.1 audit fixes in one clause」；實際上文案以一句加冒號後接六項列表呈現。稱之為 one clause 略嫌寬鬆，但非錯誤陳述，無需更動。
2. zh 文案用「尚未查看」，目錄字串用「未查看」——詞根一致（查看），語意相同且文案語境下更自然，屬可接受的一致性。
3. ja 文案寫「未閲覧のコピーがあるとき」（有未閲覧的副本時），en 寫「something is unviewed」；ja 較具體但語意等價，且與 ja 目錄的「未閲覧」用字完全對齊。

## 結論

第 24 輪列出的所有必修項目皆已正確落實，五語商店文案在長度、三處一致性、術語與語意對齊上均通過驗證，CHANGELOG 與發行 workflow 相容，無回歸。第 24 輪修正列車可關閉，0.1.2 就緒。
