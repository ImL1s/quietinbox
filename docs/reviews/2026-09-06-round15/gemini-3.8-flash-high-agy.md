# Review Round 15 — 審查報告（QuietInbox）

- **審查範圍**：`git diff eae0003..6225719`（單一 commit `6225719`）
- **審查性質**：唯讀（READ-ONLY）。未建立、修改或刪除任何檔案，未執行任何會變更 Git 或系統狀態之指令，未執行儀器化測試。
- **本地執行驗證**：`./gradlew :platform:capture:testDebugUnitTest --console=plain -q`（實測 30 tests 全數通過，0 failures, 0 errors, 0 skipped）。

---

## Verdict

### **APPROVE**（0 Critical、0 Important、0 Minor）

Round-14 Claude subagent 所提出的 5 個 Minor 與 2 項觀察（Observation 4、5）皆已獲得精確且優雅的修復；brief 點名的 7 個潛在回歸點（包含 lost update 推演、`liveKeys` 與全域暫停、`sbn.key` 邊界、重檢成本、冪等性、測試決定論及文件計數）經深入推演與交叉比對，**均無實質漏洞或邏輯缺陷**。測試全綠，文件與計數（30 / 204 / 16）完全一致。

---

## Round-14 驗證清單（Claude subagent 發現項目對照）

| # | Round-14 發現 | 是否修復 | 證據（以 commit `6225719` 為準） |
|---|---|:---:|---|
| **Minor-1** | `releaseHeld()` 溢位與 stale-held 缺口為 fire-and-forget 寫入，失敗即遺失（ADR-0007「只有成功寫入後才會忘記」承諾過頭） | ✅ 已修 | 抽取 [CaptureCoordinator.kt:521-531](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L521-L531) 之 `recordColdStartLoss(start)`：寫入若失敗且 `coldStartLossSince == null`，將保留至 `coldStartLossSince`，由下次 `settleColdStartGap()` 補寫。於 [:485](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L485) 與 [:513](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L513) 統一調用。新測試 [CaptureCoordinatorTest.kt:740-759](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L740-L759) 驗證溢位寫入失敗後保留並於下次 policy load 補寫（負面對照 E）。 |
| **Minor-2** | `dropHeld()` 立即關閉之時序視窗未完全關死（落地在 settle 查詢之後、`sourcesLoaded = true` 之前） | ✅ 已修 | [CaptureCoordinator.kt:405-409](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L405-L409)：在 `sourcesLoaded = true` 後增加重檢 volatile `coldStartGapId`，若非 null 則補調用 `closeOpenGaps(COLD_START)` 並清空。與 `dropHeld()` 之 volatile 寫讀（先寫 `coldStartGapId` 再讀 `sourcesLoaded`）構成 Dekker 序列一致性。舊測試改用 `atLeast = 2`；新增測試 [CaptureCoordinatorTest.kt:709-738](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L709-L738)（負面對照 D）。 |
| **Minor-3** | stale 缺口 over-report：未排除暫停來源，且連線重繫（rebind）的 active resync 導致同一則通知被擷取又被記為缺口 | ✅ 已修 | [CaptureCoordinator.kt:489](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L489)：建立 `liveKeys`（收集當前代且未全域暫停之 `sbn?.key`）。[:497-499](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L497-L499) 排除 `pausedPackages`，且僅在 `h.sbn?.key !in liveKeys` 時才記錄 stale 缺口。[CaptureCoordinatorTest.kt:525](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L525) 讓 `sbnOf` 提供相異 key；新增測試 [:761-784](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L761-L784)（負面對照 F）。 |
| **Minor-4** | `MediaDao.maxId()` 與帶上限分頁之 `exportPage` 無任何層級之測試覆蓋 | ✅ 已修 | 新增儀器化測試 [MediaExportBoundTest.kt](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/MediaExportBoundTest.kt)：驗證空資料表時 `maxId()` 為 0L 且首頁為空；兩筆記錄情境下驗證 `exportPage(0, bound, ...)` 正確邊界排除後續 commit 之 blob。 |
| **Minor-5** | Bitmap 上限測試依賴 `scope.launch` 之啟動順序，實測 10 跑 1 紅（flake） | ✅ 已修 | [CaptureCoordinatorTest.kt:839](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L839)：將斷言改為與順序無關之 `bitmaps.count { it == null } shouldBe 1`，消除多執行緒排程順序差異造成的假性失敗。 |
| **Obs-4** | `coldStartTimeoutMs` 未標註 `@VisibleForTesting` | ✅ 已修 | [CaptureCoordinator.kt:118](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L118) 已加上 `@androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)`。 |
| **Obs-5** | `docs/SCOPE.md` 冷啟動描述漏列跨事件保留之通知與寫入成功才忘記之語意 | ✅ 已修 | [docs/SCOPE.md:76](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L76) 補齊：「a notification of a capturable source held across a disconnect / pause / maintenance run... forgotten only once that write succeeded」。 |

---

## Brief 點名回歸排查與深層推演（Regression Hunt）

### 1. `recordColdStartLoss` 與 `settleColdStartGap()` 之間是否有 Lost Update？
* **場景推演**：`recordColdStartLoss` 執行於 pipeline lock 外的非同步 `scope.launch` 協程中，是否有檢查並賦值（check-then-act）競態？
  * **狀況 A（settle 讀到 null）**：settle 先讀取 `since = null`，接著協程寫入失敗將 `coldStartLossSince` 設為 `t`。settle 因 `since == null` 不觸發清除操作。`t` 完整保留至下一次 policy load 寫入。**安全（Kept）。**
  * **狀況 B（settle 讀到 t0 並清除，與失敗協程交錯）**：
    1. 假設 `coldStartLossSince` 原先已有記錄 `t0`。
    2. Settle 讀取 `since = t0`，呼叫 `guarded { recordGap(t0, now_settle, ...); written = true }`。
    3. 在 settle 尚未執行 `coldStartLossSince = null` 前，`recordColdStartLoss(t1)` 的失敗回呼觸發。因看到 `coldStartLossSince` 非 null（仍為 `t0`），故未覆蓋；隨後 settle 將 `coldStartLossSince` 設為 null。
  * **分析結論：無遺失缺口風險**。
    * 第一，此交錯的前提是 `recordColdStartLoss` 遭遇寫入失敗（如磁碟滿），而同一個微秒內 settle 的寫入卻奇蹟般地成功；若磁碟滿，settle 寫入亦會拋出例外，`written` 維持 false，不會清空 `coldStartLossSince`。
    * 第二，即使 settle 成功，settle 所記錄的區間為 `[t0, now_settle]`，其中 `now_settle` 為保險庫就緒當下的時間戳。由於 `t0 <= t1 < now_settle`，此 `BOUNDED` 缺口在語意與時間範圍上**已完整涵蓋**了 `[t1, now_settle]`。因此在有界缺口模型下不存在丟失遺失記錄的問題。

### 2. 全域 `paused` 為 true 時，`liveKeys` 與 stale gap 的行為是否正確？
* **機制分析**：
  * 當全域 `paused == true` 時，[CaptureCoordinator.kt:489](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L489) 之 `liveKeys` 確為空集合（因 `!paused` 條件不成立）。
  * 此時遍歷 held 緩衝區，所有項目皆滿足 `h.generation != activeGeneration || paused`。
  * 若此項目的來源為啟用且該來源未暫停（`capturable == true`），則會將其加入 `staleSince`，並記錄為 `COLD_START` 缺口。
* **是否應由全域暫停抑制 stale 缺口？**
  * **不應該抑制**。這些通知是在「冷啟動期間（`sourcesLoaded == false`）、使用者尚未按下全域暫停」時到達並進入 held 緩衝的。若金庫冷啟動及時完成，該通知理應被正常擷取；正是因為冷啟動延宕，在金庫開啟前夕遭遇全域暫停，導致通知最終未能進入 DB。此遺失的根本觸發起因確實屬於冷啟動延遲。
  * 雖其區間 `[heldAt, now]` 與全域暫停的 `PAUSED_BY_USER` 缺口存在重疊，但有界缺口（`BOUNDED`）本質即為安全導向的過度近似（fail closed），不會漏報潛在遺失，健康頁面僅列表展示，不影響業務正確性。

### 3. 讀取 `sbn.key` 是否破壞「政策未知前不讀取任何內容」之原則？
* **評估結論：完全接受且符合規範**。
  1. **執行時機在政策載入後**：`releaseHeld()` 僅在 `loadSourcePolicy()` 獲取來源政策之後調用，此時已非政策未知狀態。
  2. **中繼資料性質**：`sbn.key` 為 Android 框架之路由鍵（由 `userId|pkg|id|tag|uid` 組成），不含 Notification 的 title、text、extras、icon 或任何敏感內容，與既有讀取 `sbn.packageName` 屬同一層級之系統中繼資料。
  3. **去重必要性**：真機環境下 rebind 觸發之 active resync 會將通知欄既有通知再次 offer，比對 framework key 乃避免同一通知「既被成功擷取又被誤報為缺口」之唯一安全機制。

### 4. 旗標翻轉後的重檢成本確認
* **程式碼核對**：[CaptureCoordinator.kt:405-409](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L405-L409)
  ```kotlin
  val late = coldStartGapId
  if (late != null) guarded {
      health.closeOpenGaps(System.currentTimeMillis(), GapReason.COLD_START)
      coldStartGapId = null
  }
  ```
* **成本確認**：在絕大多數正常的 policy load 中，`coldStartGapId` 為 null（先前未超時或已在 settle 中清為 null），故僅執行單次 volatile 讀取與 null 判斷，**不佔用 DB I/O、不觸發鎖競爭、無記憶體配置，代價實質為 0**。僅在極端超時且 drop 恰好與 policy load 交錯時才會觸發一次關閉。

### 5. `coldStartGapId = null` 與重複關閉之冪等性
* **分析**：
  * [HealthRepository.kt:56-59](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/HealthRepository.kt#L56-L59) 的 `closeOpenGaps` 透過 SQL `WHERE endEpochMs IS NULL` 查詢並關閉。若呼叫第二次，查詢結果為空集合，為純粹的 no-op。
  * `coldStartGapId = null` 亦為冪等寫入。雙方同時看到對方寫入並執行關閉的情形完全安全。

### 6. 三個新測試之決定論分析（Test Determinism）
* **測試 1**（settle 與 flag flip 間落地之 gap 關閉，[:709-738](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L709-L738)）：透過 4 個 `CompletableDeferred`（`policyReady`、`gapGate`、`closeStarted`、`closeGate`）精準閂鎖時序，完全排除任意 `delay()`。
* **測試 2**（溢位失敗保留並補寫，[:740-759](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L740-L759)）：使用 `awaitUntil { h.journaled.size shouldBe 256 }` 與 `coVerify(timeout)` 條件輪詢，確定性高。
* **測試 3**（跨連線保留遇暫停或 resync 不記缺口，[:761-784](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L761-L784)）：同線程同步派發搭配 `stillHolds` 斷言。
* 三個測試無時序競態（timing guess），決定論保證充分。

### 7. 文件與程式碼統計計數交叉驗證
* **CaptureCoordinatorTest**：實測 30 個測試（原 27 + 本次新增 3 個）。
* **JVM 單元測試總數**：196 個 Kotest `test("...")` + 8 個 JUnit `@Test` = **204 個**，各模組加總正確。
* **儀器化 Storage 測試數**：5（DeletionGraph）+ 2（DemoData）+ 3（Migration）+ 2（SearchPaging）+ 3（VaultRoundTrip）+ 1（MediaExportBoundTest）= **16 個**。
* **文件同步狀態**：`CHANGELOG.md`、`CLAUDE.md`、`docs/TEST_MATRIX.md`、`docs/zh-Hant/TEST_MATRIX.md`、`docs/reviews/README.md`、`docs/zh-Hant/reviews/README.md` 全部核對一致。

---

## 新發現（New Findings）

- **Critical**：無。
- **Important**：無。
- **Minor**：無。

---

## 結論

Commit `6225719` 完整且嚴謹地解決了 Round-14 的所有遺留問題，不僅修正了邊界競爭與 Flaky 測試，亦補齊了對應的儀器化測試與中英雙語文件。程式碼邏輯健全、無回歸風險，審查判定為 **APPROVE**。
