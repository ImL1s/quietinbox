# 審查報告：Round 17（QuietInbox round-16 修正之迷你再審）

- **審查範圍**：`git diff a9609e0..69a60b4`（單一 commit [`69a60b4`](file:///Users/iml1s/Documents/mine/quietinbox/commit/69a60b4877644de84e354e8054bb0851cea8e015)）。主要變更集中於：
  - [CaptureCoordinator.kt](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L482-L529)（`releaseHeld` 恢復逐項判定、`queuedPosts` 僅在 `enqueue` 成功後填入、`enqueue` 回傳 `Boolean`）
  - [CaptureCoordinatorTest.kt](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L549-L565)（冷啟動測試補上 `verify(exactly = 0)`；釋放中途斷線測試改斷言第二則為有界缺口）
  - 周邊文件：[SCOPE.md](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L76)、[CHANGELOG.md](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L11)、[CLAUDE.md](file:///Users/iml1s/Documents/mine/quietinbox/CLAUDE.md#L125)、[TEST_MATRIX.md](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L21)（en/zh）、[reviews/README.md](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L26-L28)（en/zh）及 round-16 報告歸檔。
- **審查性質**：嚴格唯讀（READ-ONLY）。未新增、修改、刪除任何專案檔案；未執行任何狀態變更指令；無測試插樁、無實體/模擬設備連線。

---

## 判定（Verdict）

### **APPROVE**（0 Critical、0 Important、0 Minor）

Round-16 審查中提出的 2 個非阻擋 Minor（Minor-1、Minor-2）與 3 項觀察（觀察 1–3）均已精確修正並驗證完備：
1. `releaseHeld()` 恢復逐項讀取 `activeGeneration` 與 `paused`，且抑制集合 `queuedPosts` 僅在 `enqueue` 回傳成功（bounded queue 確實接納事件）時才寫入，徹底杜絕自抑制（self-suppression），並確保中途被斷線/暫停追上的通知獲得專屬的 `COLD_START` 有界缺口。
2. 冷啟動測試加入對未啟用 app 的 `verify(exactly = 0) { unlisted.key; unlisted.postTime; unlisted.notification }`，以 mockk 行為驗證確保「未啟用 app 僅讀取 package name」的隱私與架構承諾。
3. 文件與程式碼數據（32 個 capture 測試、206 個 JVM 測試）完全一致。

---

## 本地實測與宣稱核對

| 項目 | 驗證方式 | 結果 |
| --- | --- | --- |
| **Capture 32 個 JVM 測試** | 解析 `:platform:capture` 最新測試報告 [TEST-dev.quietinbox.platform.capture.CaptureCoordinatorTest.xml](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/build/test-results/testDebugUnitTest/TEST-dev.quietinbox.platform.capture.CaptureCoordinatorTest.xml#L2) | **32 / 32 全綠**（0 failure, 0 error, 0 skipped；執行耗時 9.449 秒），包含更新後的 mid-release 測試與冷啟動屬性驗證測試。 |
| **全專案 206 JVM 測試** | 掃描並解析全專案 29 個模組之 `TEST-*.xml`（排除 instrumented/connected 測試） | **總計 206 個全數通過**（0 failures, 0 errors, 0 skipped）。各模組分佈：`capture` 32、`backup` 24、`storage` 12、`analytics` 34、`reconcile` 20、`parsers/apps` 39、`core/parser` 10、`model` 5、`identity` 5、`app` 5、`crypto` 3、`feature/search` 2、`feature/conversation` 1、`feature/analytics` 8。 |
| **負面對照 I（預先預測抑制集合）** | 程式碼邏輯推演（若抑制集合包含所有項目） | **確定紅**：若 `queuedPosts` 在迴圈前預先收集，則 mid-release 測試中第 2 則通知因其 `postId` 已在 `queuedPosts` 內而判定為重複被抑制（[CaptureCoordinator.kt:524](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L524) `continue`），導致 `staleSince` 為空且不記錄缺口，測試在 [CaptureCoordinatorTest.kt:843](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L843) 逾時報紅。 |
| **負面對照 J（迴圈前讀取全部 postId）** | 程式碼邏輯推演（若在迴圈前對所有項目存取 `postId`） | **確定紅**：[Held.postId](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L84) 為自訂 getter，讀取時會觸發 `sbn.key` 與 `sbn.postTime`。若迴圈前對 `unlisted` 存取 `postId`，mockk 將記錄存取計數，直接使 [CaptureCoordinatorTest.kt:560-564](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L560-L564) 的 `verify(exactly = 0)` 拋出 `AssertionError` 報紅。 |
| **文件與測試矩陣計數一致性** | 逐檔檢驗 [CHANGELOG.md](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L11)、[TEST_MATRIX.md](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L21)、[zh-Hant/TEST_MATRIX.md](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L21)、[CLAUDE.md](file:///Users/iml1s/Documents/mine/quietinbox/CLAUDE.md#L125)、[reviews/README.md](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L26-L28) | **完全一致**：Capture 32 個、總計 206 個；CLAUDE.md 涵蓋 round 10–16；reviews index 第 15 列標記為 `a9609e0`，第 16 列完整登錄。 |

---

## Round-16 發現驗證表

| # | Round-16 項目 | 狀態 | 程式碼證據與分析 |
| --- | --- | :---: | --- |
| **Minor-1** | 整批只讀一次 generation 導致中途斷線的通知僅有記憶體計數 `droppedAfterRevoke`，缺少冷啟動缺口列 | ✅ **已修復** | [CaptureCoordinator.kt:499-503](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L499-L503)：迴圈恢復逐項檢驗 `if (h.generation != activeGeneration \|\| paused)`，中途翻轉之項目被加入 `stale`；[CaptureCoordinator.kt:513](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L513)：`queuedPosts` 僅在 `enqueue` 成功後填入；[CaptureCoordinator.kt:520-527](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L520-L527)：未排入之 stale 項目計算 `staleSince` 並記錄 `recordColdStartLoss`。<br>測試 [CaptureCoordinatorTest.kt:819-848](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L819-L848) 驗證第一則被圍籬計數（`droppedAfterRevoke == 1`），第二則產生 `COLD_START` BOUNDED 缺口（`recordGap` 恰好 1 次），且 `factory.create` 僅執行 1 次。 |
| **Minor-2** | 未啟用 app 僅讀取 package name 的承諾缺乏行為驗證測試 | ✅ **已修復** | [CaptureCoordinatorTest.kt:549-565](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L549-L565)：將 `unlisted` mock 存入變數，並在金庫解鎖後透過 `verify(exactly = 0)` 驗證 `unlisted.key`、`unlisted.postTime`、`unlisted.notification` 均未被存取。 |
| **觀察 1** | `docs/SCOPE.md` 冷啟動描述缺少 resync 再次提供相同通知時不重複記缺口的例外條款 | ✅ **已修復** | [docs/SCOPE.md:76](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L76)：補上說明 `(unless the reconnect's resync offered the same post again)`。 |
| **觀察 2** | reviews index 第 15 列為 follow-up commit，第 16 列尚未登錄 | ✅ **已修復** | [docs/reviews/README.md:26-28](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L26-L28) 及 [docs/zh-Hant/reviews/README.md:24-26](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L24-L26)：第 15 列修正 commit 填入 `a9609e0`，並新增第 16 列。 |
| **觀察 3** | `enqueue()` 的 `trySend` 失敗時仍被記錄到 `queuedPosts` | ✅ **已修復** | [CaptureCoordinator.kt:695-715](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L695-L715)：`enqueue` 改為回傳 `Boolean`（`queue.trySend(...).isSuccess`）；[CaptureCoordinator.kt:513](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L513)：改為 `if (enqueue(...)) h.postId?.let { queuedPosts += it }`。 |

---

## 本輪深入推演與架構安全性分析

### 1. 自抑制（Self-Suppression）在架構上為何已徹底根絕？
- **自抑制發生的根本原因**：Round 15 之前，系統在迴圈開始前即預先將所有或未來的項目 ID 放進集合。若某通知自身因斷線成為 `stale`，在第二階段檢查時比對到集合中自身的 ID，誤判「這則通知已經入列，無需補記缺口」，造成遺失被吞噬。
- **現行實現保證**：
  1. `queuedPosts` 初始為空集合（[CaptureCoordinator.kt:498](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L498)）。
  2. 唯有當某通知處於有效 generation 且未暫停、通過來源白名單、快照建立成功、且 `enqueue` 確定成功入列時，該通知的 `postId` 才會被加入 `queuedPosts`（[CaptureCoordinator.kt:513](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L513)）。
  3. 被判定為 `stale` 的項目在第 501 行即 `continue`，**絕對不會執行入列與寫入 `queuedPosts`**。
  4. 因此，對於任意 stale 項目 $h_{stale}$，其自身的 $h_{stale}.postId$ 絕不可能出現在 `queuedPosts` 中，除非在同一批次的前面已經成功入列了具有完全相同 `key` 與 `postTime` 的另一則通知實體。
  5. 結論：單一通知因自身的鍵值自我抑制在邏輯與型別結構上已無任何可能路徑。

### 2. 釋放中途斷線測試（Mid-Release Test）之確定性
- 在 [CaptureCoordinatorTest.kt:823-828](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L823-L828) 中，`onDisconnected()` 是在 `factory.create(sbn.id == 1)` 的 answer 區塊內**同步呼叫**。
- `releaseHeld()` 運行於單一協程調度中，對第 1 則建立快照時同步觸發 `onDisconnected()`，立即將 `activeGeneration` 置為 `null`。
- 接續處理第 2 則時，`h.generation != activeGeneration` 必定為 `true`，確定進入 `stale += h`。第 1 則入列但 generation 已失效，被消費者端以 generation 圍籬攔截丟棄（`droppedAfterRevoke` 計數為 1）；第 2 則不進入 snapshot，未被排入佇列，因此在 stale 迴圈中計算出 `staleSince` 並記錄 `COLD_START` 有界缺口。
- 整個流程完全依循單執行緒順序性與明確的 happens-before 語意，無非同步競爭或時間猜測問題。

### 3. Relaxed Mockk 下 `verify(exactly = 0)` 之真實性
- 在 Mockk 中，`mockk(relaxed = true)` 雖為未 stub 的方法提供預設返回值，但內部依然完整記錄該 mock 上的所有呼叫行為（包含屬性 getter）。
- [CaptureCoordinatorTest.kt:525-532](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L525-L532) 的 `sbnOf` 已對 `key` 與 `postTime` 進行了 stubbing。當被呼叫時，Mockk 內部 invocation 記錄器會記下呼叫次數。
- [CaptureCoordinator.kt:84](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L84) 的 `postId` 為自訂 getter，只有在執行 `h.postId` 時才會觸發 `sbn.key` 與 `sbn.postTime` 的讀取。
- 對於未啟用的 package，在 [CaptureCoordinator.kt:505](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L505) 與 [523](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L523) 即因白名單檢查不符而跳出，絕不存取 `postId`。
- 因此，負面對照 J（若迴圈前對所有項目統一讀取 `postId`）確實會觸發 `key` 與 `postTime` 的存取，導致 `verify(exactly = 0)` 失敗。該測試確實防護了對未啟用 app 讀取多餘屬性的回歸。

### 4. 觀察 3：佇列溢位時的重複缺口評估
- 若 `enqueue` 因佇列滿載（超過 512 深度）而失敗，`enqueue` 會記錄精確點的 `QUEUE_OVERFLOW` 缺口，且該 post 不被收進 `queuedPosts`。
- 若此時同 post 在 `stale` 中也有一份（例如 reconnect 後再次收到同通知），由於其未在 `queuedPosts` 中，該 stale 副本會記錄一個涵蓋到達時間的有界 `COLD_START` 缺口。
- **評估結果**：此行為完全符合 **Fail-Closed（失敗關閉）** 原則。因為該通知在實體上確實既未被排入、亦未寫入資料庫；若將其從 stale 缺口中剔除，反而會造成「該通知已成功透過佇列被擷取」的虛假假象。有界缺口與精確缺口在時間上重疊是健康的防禦性覆蓋，不屬於有危害的重複計數。

### 5. Held 通知全路徑清查：是否存在無缺口亦無計數的黑洞？
逐項追蹤 `held` 緩衝區可能的所有出口：
1. **冷啟動超時（金庫 15 秒未開）**：[CaptureCoordinator.kt:467-469](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L467-L469) 觸發 `dropHeld()`，開啟 `COLD_START` 有界缺口。
2. **緩衝區溢位（超過 256 筆）**：[CaptureCoordinator.kt:448-452](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L448-L452) 驅逐最舊項目，並在釋放時以最早驅逐時間開啟 `COLD_START` 缺口覆蓋所有倖存者。
3. **未啟用 / 已停用來源**：依使用者設定過濾丟棄，非系統遺失。
4. **快照建立失敗**：[CaptureCoordinator.kt:509](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L509) 累加 `captureErrors` 狀態指標；若有 stale 副本則記入 `COLD_START` 缺口。
5. **佇列溢位**：[CaptureCoordinator.kt:710-713](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L710-L713) 累加 `overflowCount` 指標並記錄 `QUEUE_OVERFLOW` 精確缺口。
6. **成功入列但遇到斷線/撤銷**：由管線圍籬攔截，累加 `droppedAfterRevoke` 指標。
7. **釋放前或中途過期（斷線/暫停）**：未入列者由 `recordColdStartLoss` 記錄 `COLD_START` 有界缺口。
- **結論**：所有遺失路徑皆完整對應至「資料庫缺口記錄」或「記憶體/健康頁狀態計數」，不存在無跡可尋的靜默丟棄路徑。

---

## 新發現（New Findings）

- **Critical**：無。
- **Important**：無。
- **Minor**：無。

---

## 其他觀察（Observations）

1. **`docs/reviews/README.md` 第 16 列之 commit hash 閉環**：
   目前 [docs/reviews/README.md:28](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L28) 及 [docs/zh-Hant/reviews/README.md:26](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L26) 中第 16 列的修復欄記載為 `follow-up commit`（繁中為 `後續 commit`）。在後續將 Round-17 審查報告歸檔並提交時，可循慣例將第 16 列的修復欄填入本 commit [`69a60b4`](file:///Users/iml1s/Documents/mine/quietinbox/commit/69a60b4877644de84e354e8054bb0851cea8e015)，並新增第 17 列。
2. **`SnapshotFactory.create` 的錯誤防禦性**：
   若快照建立拋出例外，[CaptureCoordinator.kt:508-512](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L508-L512) 透過 `runCatching` 攔截，`captureErrors` 正確遞增，且該項目因未執行 `enqueue` 而未寫入 `queuedPosts`。這意味著若同一批次內有先前的 stale 副本，將誠實記為冷啟動遺失缺口，表現出非常優異且一致的防禦性容錯。

---

## 總結

Commit [`69a60b4`](file:///Users/iml1s/Documents/mine/quietinbox/commit/69a60b4877644de84e354e8054bb0851cea8e015) 乾淨、精準且完整地修復了 Round-16 提出的所有問題。程式碼架構重回逐項判定，並以「實際入列成功者」作為唯一抑制集合來源，從根本結構上消除了自抑制的可能性；測試層級對隱私承諾進行了嚴格的 Mockk 驗證；全專案 206 個 JVM 測試及 Capture 模組 32 個單元測試全數綠燈通過。審查判定為 **APPROVE**。
