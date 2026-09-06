靜候單元測試重新執行完成...
等待 `--rerun-tasks` 執行測試完畢...
等待第三次 `--rerun-tasks` 執行完成...
# Review Round 16 — 審查報告（QuietInbox）

- **審查範圍**：`git diff 6225719..a9609e0`（單一 commit `a9609e0`，HEAD）
- **審查性質**：唯讀（READ-ONLY）。未建立、修改或刪除任何檔案，未執行任何會變更 Git 或系統狀態之指令，未執行儀器化測試。
- **本地執行驗證**：
  - `./gradlew :platform:capture:testDebugUnitTest --console=plain -q`（連續 3 次獨立執行，全數 32 tests 通過，0 failures, 0 errors, 0 skipped）。
  - JVM 單元測試總數掃描：全專案 206 tests 全綠（0 failures, 0 errors, 0 skipped）。

---

## 判定（Verdict）

### **APPROVE**（0 Critical、0 Important、0 Minor）

Round-15 Claude subagent 針對 `releaseHeld()` 的跨代 key 抑制所提出的 3 個 Minor 發現（Minor-1：釋放中途斷線讓後續通知自我抑制；Minor-2：同 key 更新內容被誤判為同一則而隱藏缺口；Minor-3：從未啟用的 app 被讀取 key）皆已獲得徹底且精確的修復。
針對 brief 所提示的深層推演與潛在回歸點（snapshot 失敗時之缺口記錄、合成通知處理、溢位覆蓋性、單次讀取世代之圍籬機制、讀取 `postTime` 之隱私邊界、測試決定論以及文件計數），經逐項推演與實測確認**均完全健全，無任何回歸風險**。

---

## Round-15 驗證清單（發現項目對照表）

| # | Round-15 發現 | 是否修復 | 證據（以 commit `a9609e0` 為準） |
|---|---|:---:|---|
| **Minor-1** | `releaseHeld()` 迴圈前預先計算 `liveKeys`，迴圈內卻逐項重讀 volatile 變數；若釋放中途發生 disconnect 或 pause，較晚項目會被自身的 key 抑制，導致既未入隊亦未記錄缺口（隱藏遺失回歸） | ✅ 已修 | [CaptureCoordinator.kt:494-496](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L494-L496)：在迴圈前對 `activeGeneration` 與 `paused` 進行單次讀取快照（`liveGeneration`、`livePaused`），全批次項目皆以此統一樣本進行分類（[:501-504](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L501-L504)）。中途斷線的項目仍會作為當前代入隊，隨後由 consumer 的 `admitted()` 依世代圍籬丟棄並累加 `droppedAfterRevoke`，絕不自我抑制。新增測試 [CaptureCoordinatorTest.kt:812-841](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L812-L841)（驗證釋放中途斷線時兩個項目皆嘗試 snapshot，`droppedAfterRevoke == 2L` 且無誤報 `COLD_START` 缺口）。 |
| **Minor-2** | 同一 `key` 不代表同一則通知：App 在 listener 斷線期間以相同 key 更新內容（例如更新訊息），舊內容的遺失被誤認為「已再次擷取」而隱藏缺口 | ✅ 已修 | [CaptureCoordinator.kt:84](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L84)：新增 `Held.postId` 定義為 `sbn.key + "|" + sbn.postTime`。抑制集合改以 `postId` 比對（[:515](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L515)、[:526](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L526)）。更新後之通知因 `postTime` 變更，不會抑制舊 post 的遺失。新增測試 [CaptureCoordinatorTest.kt:792-810](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L792-L810)（同 key、新 postTime 產生時，舊 post 準確觸發 1 次 `COLD_START` 缺口記錄）。 |
| **Minor-3** | `liveKeys` 在過濾套件前即對所有項目讀取 `sbn.key`，違反 `Held` 承諾「未啟用 app 僅保留 framework 物件而不讀取」之隱私邊界 | ✅ 已修 | [CaptureCoordinator.kt:506](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L506)、[:515](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L515)：`queuedPosts` 僅收集**實際成功 enqueue** 的項目之 `postId`；未啟用套件於 [:506](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L506) 即 `continue` 略過。在 stale 缺口判斷迴圈中，亦於 [:525](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L525) 預先過濾可擷取來源，未啟用的 app 絕不觸碰 `postId`。 |

---

## 深入推演與潛在回歸排查（Regression Hunt）

### 1. 活體對象（Live Twin）之 `snapshotFactory.create` 失敗時，stale 對象的行為
- **推演**：若同一通知之當前代複本在 [CaptureCoordinator.kt:508-513](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L508-L513) 執行快照建立時拋出例外（或返回 null），該項目將觸發 `continue`，**不會**執行 [:514](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L514) 的 `enqueue`，亦**不會**將其 `postId` 寫入 `queuedPosts`。
- **後續 stale 判斷**：在 stale 迴圈（[:523-528](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L523-L528)）中，先前的過期複本查詢 `h.postId in queuedPosts` 為 `false`，因此將其納入 `staleSince` 並記錄 `COLD_START` 缺口。
- **結論**：**行為完全正確**。既然活體複本未能成功進入管道（未寫入資料庫或日誌），該通知實質上已遺失。將舊複本視為遺失並記錄缺口，完全符合專案「任何丟失絕不隱瞞（a dropped notification is never hidden）」之核心安全準則。

### 2. `captured != null` 的預先快照／合成項目（`sbn == null`，`postId == null`）
- **推演**：測試或合成通知進入時，`sbn` 為 null，故 [CaptureCoordinator.kt:84](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L84) 之 `postId` 為 null。
- **抑制集合與查表**：`postId` 為 null 不會加入 `queuedPosts`（`h.postId?.let`）；stale 迴圈比對 `null in queuedPosts` 回傳 `false`，若來源可擷取則記入缺口。
- **結論**：**完全可接受**。合成通知（Synthetic）不屬於 Android framework 在 rebind 時主動 resync 的對象，不會在通知欄產生重複遞送的複本，故不存在「抑制 resync 重複項」的需求。跨越世代保留的合成事件記錄缺口屬於保守安全的 fail-closed 設計。

### 3. `dropped == 0` 守衛在已記錄溢位缺口時跳過 stale 缺口的涵蓋性
- **分析**：
  - 緩衝區容量上限為 256。當 `dropped > 0` 時，表示在策略載入前緩衝區已發生淘汰，淘汰順序由最舊者開始（FIFO）。
  - [:488](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L488) 寫入的溢位缺口起點為 `since ?: items.minOfOrNull { it.heldAtEpochMs }`，即第一筆被丟棄項目的到達時間。
  - 所有仍留在緩衝區內的倖存 stale 項目，其到達時間必然晚於或等於該淘汰起點時間。
  - 溢位缺口與 stale 缺口的終點皆為發起當下的系統時間戳 `now`。
- **結論**：溢位缺口所覆蓋的時間區間 `[since, now]` 在數學上嚴格且完整地包含所有倖存 stale 項目的保留區間 `[stale.heldAtEpochMs, now]`。在 `dropped > 0` 時略過 stale 缺口，避免了同一區間產生冗餘的重複有界缺口，涵蓋性論證完全成立。

### 4. `liveGeneration` 單次讀取與世代圍籬機制
- **推演**：`releaseHeld()` 在進入迴圈前單次快照 `liveGeneration`。若在批次釋放途中發生斷線，後續項目仍會依照該 `liveGeneration` 完成快照並放入 `enqueue`。
- **Consumer 端防禦**：放入佇列後，Consumer 執行 [CaptureCoordinator.kt:739](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L739) 之 `admitted(item)` 檢查，此時 `activeGeneration` 已在 `onDisconnected()` 中被置為 null，因此該項目無法通過檢驗，直接丟棄並遞增 `droppedAfterRevoke`。與此同時，`onDisconnected()` 已非同步開啟 `LISTENER_DISCONNECTED` 缺口。
- **結論**：**完全接受**。這與系統中所有既有即時通知遇到斷線時的處理模式（佇列內事件經 generation 圍籬丟棄並記錄 disconnect 缺口）保持高度一致，邏輯封閉且嚴謹。

### 5. 讀取 `sbn.postTime` 的合理性與隱私邊界
- **結論**：**完全符合規範且合宜**。
  - `postTime` 與 `packageName`、`key` 均為 Android 系統層級提供的路由中繼資料（framework metadata），不包含通知的 title、text、subText、extras 或自訂 RemoteViews，不涉及任何敏感內文。
  - 核心組件 `SnapshotFactory.kt:144` 在正常快照時本來就讀取 `sbn.postTime`。
  - 本次改動更進一步確保：僅有通過白名單驗證的可擷取來源（`pkg in enabledPackages && pkg !in pausedPackages`），才會讀取其 `postId`。未啟用的應用程式在進入快照或 stale 比對前即已被 `continue` 剔除，嚴格恪守隱私保證。

### 6. 兩個新增測試的決定論（Test Determinism）分析
- **測試 1（同 key 不同 postTime 記錄缺口，[:792-810](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L792-L810)）**：
  - 透過 `CompletableDeferred<Unit>()` 精確控制金庫就緒時機。
  - 兩次 `onPosted` 搭配同步呼叫的 `onDisconnected()` 與 `onConnected()`，所有狀態轉變在測試執行緒上具備嚴格的順序保證。
  - 後續斷言使用 `awaitUntil` 輪詢 journaled 結果與 `coVerify(timeout)` 驗證缺口寫入，無盲目等待（timing guess）。
- **測試 2（釋放中途斷線不自我抑制，[:812-841](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L812-L841)）**：
  - 在 `factory.create` 的 mockk 回呼中，針對 `sbn.id == 1` 同步觸發 `onDisconnected()`。
  - 此機制將斷線時機精準鎖定在釋放執行緒處理第一個項目的當下，排除了多執行緒排程不確定性。
  - 斷言條件 `awaitUntil { coordinator.status.value.droppedAfterRevoke shouldBe 2L }` 及 `coVerify(exactly = 2) { factory.create(...) }` 均具備明確的因果因果鏈與 happens-before 保證。

### 7. 文件與程式碼統計計數核對
- **CaptureCoordinatorTest**：32 個測試（前次 30 個 + 本次新增 2 個）。
- **全專案 JVM 單元測試總數**：經 XML 匯總驗證剛好 **206 個**。
- **儀器化 Storage 測試**：維持 **16 個**。
- **文件同步**：
  - `CHANGELOG.md`、`CLAUDE.md`、`docs/TEST_MATRIX.md`、`docs/zh-Hant/TEST_MATRIX.md`、`docs/reviews/README.md`、`docs/zh-Hant/reviews/README.md` 全部同步更新至第 15 輪審查結果與 32/206 數據。

---

## 新發現（New Findings）

- **Critical**：無。
- **Important**：無。
- **Minor**：無。

---

## 其他觀察（Other Observations）

1. **`sbnOf` mock 入口統一**：
   `CaptureCoordinatorTest.kt:525-531` 將 `sbnOf` 擴充支援 `id` 與 `postTime` 參數，並將兩處 300 則通知的迴圈（[:573](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L573)、[:756](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L756)）統一收斂改用 `sbnOf`，徹底消除了 relaxed mock 預設空字串 key 可能在未來測試擴充時引發的意外抑制陷阱。
2. **審查歷史文檔維護良好**：
   第 14 輪與第 15 輪的審查記錄、報告歸檔與中英文索引皆已在本次提交中妥善對齊。

---

## 結論

Commit `a9609e0` 對 Round-15 提出的 3 個 Minor 瑕疵進行了俐落且嚴謹的修正。邏輯架構在並行中途斷線與同一通知更新覆寫的邊界上均具備嚴密防護，負面對照與測試決定論充分，文檔計數精準無誤。審查結果判定為 **APPROVE**。
