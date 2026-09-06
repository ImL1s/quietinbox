# 審查報告 — Round 14（Round-13 修復之迷你再審）

- **審查範圍**：`git diff c6b6645..eae0003`（commit [`eae0003`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L5-L10)，位於已發行之 `v0.1.1` = `c6b6645` 之上；中間 `537ad80` 僅為文件修訂）。
- **審查性質**：唯讀 mini re-review。逐項核對第 13 輪（Claude subagent）提出的 5 項 Minor 是否修復，並對這批修改可能引入的潛在回歸進行深度分析。
- **本地實測核驗**：
  - `:platform:capture:testDebugUnitTest` 27 個測試全數通過（exit 0）。
  - 全專案 JVM 測試總數為 **201** 個，無任何 failure / error / skipped。
  - 工作目錄保持完全唯讀，無任何檔案修改、新增或刪除。

---

## Verdict

### **APPROVE**

第 13 輪審查報告（`docs/reviews/2026-09-06-round13/claude-subagent.md`）所列之 5 項 Minor 全部已精確修復，並補上 3 個具備真實負面對照（negative control）的協調器測試；備份匯出將媒體嚴格約束於交易內快照的 `maxId`，根除了孤兒 Media 記錄；冷啟動與金庫鎖定的遺失記憶改為「磁碟寫入成功後才忘記」，使資料一致性與 fail-closed 承諾更為嚴密。未發現任何 Critical、Important 或 Minor 等級之新缺陷或回歸。

---

## Round-13 核對表格

| # | 項目 | 是否修復 | 證據（程式碼位置與實證） |
| :--- | :--- | :---: | :--- |
| **Minor-1** | 補寫若失敗則鎖定期間遺失永久消失；記憶清空過早 | ✅ **已修復** | [`CaptureCoordinator.kt:412-420`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L412-L420)（`settleColdStartGap`）：引入 `written` 旗標，只有在 `guarded` 區塊成功後才執行 `coldStartLossSince = null`；[`CaptureCoordinator.kt:228-240`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L228-L240)（`vault.state.collectLatest` Ready 收集器）：只有在 `written == true` 時才將 `vaultGapOpen` 設為 `false` 與 `vaultGapSince` 設為 `null`。新增測試 [`CaptureCoordinatorTest.kt:655-678`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L655-L678) 驗證第一次寫入失敗後不會遺忘，下一次 policy load 成功補記（恰好調用 2 次，第 3 次不再重複呼叫）。 |
| **Minor-2** | `dropHeld()` 在 pipeline lock 外執行，`openGap` 可能在 `settleColdStartGap()` 之後才落地留下未關閉缺口 | ✅ **已修復** | [`CaptureCoordinator.kt:529-532`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L529-L532)：`openGap` 完成且 `written` 為 true 後，重查 `sourcesLoaded`，若 policy 已載入則立即呼叫 `health.closeOpenGaps(now, COLD_START)` 並將 `coldStartGapId` 置空。新增測試 seam [`CaptureCoordinator.kt:118`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L118)（`coldStartTimeoutMs`）與新測試 [`CaptureCoordinatorTest.kt:680-704`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L680-L704)（以 `CompletableDeferred` 閘控證明落地在 policy 載入後的 row 會被立即關閉，`closeOpenGaps` 恰好調用 2 次）。 |
| **Minor-3** | 跨越斷線／暫停被略過的 held 項目未覆蓋到斷線前的遺失時間 | ✅ **已修復** | [`CaptureCoordinator.kt:477-505`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L477-L505)：在 `releaseHeld()` 迴圈中，當 `h.generation != activeGeneration || paused` 時，若 `dropped == 0` 且屬於已啟用套件，將其 `heldAtEpochMs` 累計至 `staleSince`；迴圈結束後非同步寫入一筆涵蓋 `[staleStart, now]` 的 `COLD_START` 有界缺口。新增測試 [`CaptureCoordinatorTest.kt:706-723`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L706-L723) 驗證跨越斷線的通知不會無聲消失，恰好記錄 1 次缺口。 |
| **Minor-4** | 交易外分頁匯出媒體可能讀到快照後提交的新訊息媒體，形成孤兒 Media 記錄 | ✅ **已修復** | [`Daos.kt:377-384`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/db/Daos.kt#L377-L384)：新增 `MediaDao.maxId()`（`SELECT COALESCE(MAX(id), 0) FROM media_blob`），並在 `exportPage` 加入 `AND id <= :maxId` 條件；[`BackupService.kt:148-186`](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/BackupService.kt#L148-L186)：在資料列交易內原子取得 `expected to db.mediaDao().maxId()`，交易外以此 `mediaMaxId` 進行 keyset 分頁；KDoc 更新為正確說明。 |
| **Minor-5** | `docs/reviews/README.md` 第 12 輪表格宣稱所有 Minor 已修，但 manifest media 計數僅以文件說明處理 | ✅ **已修復** | [`docs/reviews/README.md:23`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L23) 與 [`docs/zh-Hant/reviews/README.md:21`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L21)：文字修正為「seven Minors and the locked-vault observation fixed in d409d4b with two new tests, the manifest media count documented instead of changed」，中英文版本均已精確澄清。 |

---

## 針對 Brief 潛在回歸點之深度調查

### 1. `vaultGapOpen` 在關閉失敗後保持 true 的影響
- **問題分析**：若金庫就緒時寫入失敗（例如磁碟空間不足），`vaultGapOpen` 與 `vaultGapSince` 保持為 true 及原時間戳。當後續又有通知進入 [`process()`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L717-L726)，`process()` 捕捉到 `VaultUnavailableException` 時，會因 `if (!vaultGapOpen)` 為 false 而跳過 `openGap`。
- **評估結果**：此行為**完全正確**。
  1. `vaultGapSince` 記錄的是金庫最初開始無法存取的時間點。跳過 `openGap` 正好避免了將較早的起點時間戳以較晚的時間戳覆蓋。
  2. 若先前資料庫已有開啟中的缺口列，重複調用 `openGap` 只會產生重複且時間重疊的 open row。
  3. 當金庫下一次真正 Ready 且磁碟可寫時，收集器會從原創的 `vaultGapSince` 到當前時間一次記錄完整的有界缺口；只有在成功寫入磁碟後，`vaultGapOpen` 才會被重置為 false，允許未來新的 lock-out 重新開展記錄。

### 2. `coldStartLossSince` 是否存在永不被清除的路徑
- **問題分析**：是否存在 `since != null` 但 `written` 永遠保持 false 的情況？
- **評估結果**：不存在卡死。
  1. [`settleColdStartGap()`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L408-L421) 位於 [`loadSourcePolicy()`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L392-L401) 內。每當來源清單改變、通知到達且 policy 未載入、或金庫就緒時，皆會調用 `loadSourcePolicy()`。
  2. `loadSourcePolicy()` 首先執行 `val list = sources.sources()`（Room 資料庫讀取），金庫必須為 Ready 才會進入 `settleColdStartGap()`。
  3. 在 `settleColdStartGap()` 中，`written` 僅在 Room 寫入擲出 `SQLiteException`（如磁碟完全滿）時才會為 false。一旦磁碟環境恢復正常，下一次來源發射或 policy 載入即可寫入成功並清空 `coldStartLossSince`。
  4. 若金庫正常，`written` 必為 true，`coldStartLossSince` 即被置為 null。

### 3. `dropHeld()` 在鎖外執行的並行與競態分析
- **問題分析**：`dropHeld()` 的 `openGap` 與並行的 `settleColdStartGap()` 是否可能留下 open row、重複關閉、或 `coldStartGapId = null` 造成後續 `dropHeld()` 誤判？
- **評估結果**：並行設計安全無虞。
  1. **冪等性**：[`HealthRepository.closeOpenGaps()`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/repo/HealthRepository.kt#L56-L59) 是依 reason 查詢所有 `endEpochMs IS NULL` 的資料列進行更新。若同時或連續關閉多次，第 2 次查無 open row 即為 no-op，絕不擲錯。
  2. **晚到 row 的即時收尾**：若 `openGap` 因金庫延遲而在 `settleColdStartGap()` 完成之後才返回，此時 `sourcesLoaded` 已為 true，[`CaptureCoordinator.kt:529-532`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L529-L532) 會立即觸發 `closeOpenGaps`，確保不會殘留未關閉的 row。
  3. **`coldStartGapId = null` 競態**：`dropHeld` 一開始檢查 `if (items.isEmpty() && dropped == 0) return`。一旦 policy 載入（`sourcesLoaded = true`），`onPosted` 不再將通知放進 `held`；即使 `coldStartGapId` 被置為 null，後續的 `dropHeld` 也會因 `items` 為空而直接返回，絕不會誤開新 row。

### 4. `releaseHeld()` 中的 Stale Gap 與 `dropped == 0` 保護
- **問題分析**：`paused` 與 `activeGeneration == null` 的交集、與其他事件缺口的重疊性、以及 `dropped == 0` 的防護效果。
- **評估結果**：
  1. 當使用者設定暫停（`setPaused(true)`）時，`activeGeneration` 亦會被同步設為 `null`，因此 `h.generation != activeGeneration || paused` 同時覆蓋了暫停、斷線與維護。
  2. 斷線或暫停事件開啟的缺口起點是該事件發生的時間點，但 held 項目的到達時間早於該事件；透過 `staleSince` 補記 `[staleStart, now]` 的 `COLD_START` 缺口，精確補足了到達至斷線之間的空白。
  3. 若 `dropped > 0`，代表 held 緩衝區發生過溢位淘汰，[`CaptureCoordinator.kt:468-472`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L468-L472) 在前面已排程了一筆自最早被淘汰項目時間起算的 `COLD_START` 缺口；該缺口起點必定早於任何倖存的 held 項目。因此加上 `dropped == 0` 條件，巧妙地避免了重複記錄兩筆重疊的 `COLD_START` 缺口。

### 5. `MediaDao.maxId()` 在空資料表下的表現
- **問題分析**：若 `media_blob` 表無任何資料，`COALESCE(MAX(id), 0)` 回傳 `0L`，匯出分頁迴圈是否正常終止？
- **評估結果**：
  1. 當無資料時，`maxId` 為 `0L`，`mediaMaxId = 0L`。
  2. 匯出分頁查詢為 `id > :afterId AND id <= :maxId`。第一頁傳入 `after = 0L`，SQL 條件為 `id > 0 AND id <= 0`，永遠無法滿足，因此返回空清單。
  3. 迴圈於 [`BackupService.kt:190`](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/BackupService.kt#L190) 立即 `break` 退出。
  4. 最終備份記錄之 `actual.media = 0`、`skippedMedia = 0`，行為完全正常。

### 6. 測試決定論與負面對照（Negative Controls）
- 3 個新增測試均在實體協程調度器環境下以 `CompletableDeferred` 明確控制先後順序，不依賴虛擬延遲（sleep）：
  - 測試 1：第 1 次 settle 失敗、第 2 次成功。若移除 `written` 檢查改回直接置 null，第 2 次不會補記，`exactly = 2` 斷言必定失敗。
  - 測試 2：延遲 `openGap` 落地時間點至 policy 載入後。若移除 `dropHeld` 的 `closeOpenGaps` 補救區塊，關閉次數將只有 1 次，`exactly = 2` 斷言必定失敗。
  - 測試 3：斷線重連後釋放 held。若移除 `staleSince` 區塊，將不會記錄缺口，`exactly = 1` 斷言必定失敗。
- 負面對照均具備真正的斷言約束力。

### 7. 文件與程式碼數據一致性
- `CaptureCoordinatorTest` 測試數量：程式碼 27 個，文件標示 27 個（[`CHANGELOG.md:9`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L9)、[`docs/TEST_MATRIX.md:21`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L21)、[`docs/zh-Hant/TEST_MATRIX.md:21`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L21)）。
- JVM 測試總數：全模組 201 個，各處文件一致記載為 201 個。
- ADR-0007 雙語文案已更新為「且只有寫入成功後才會忘掉那筆遺失 / and a loss is only forgotten once that write succeeded」。
- 所有文件與代碼無衝突。

---

## 結論

本次修復（commit `eae0003`）針對 Round 13 提出的 5 項 Minor 提供了乾淨、扎實且具備決定論測試覆蓋的解答，無引入新的架構風險或回歸問題，判定為 **APPROVE**。
