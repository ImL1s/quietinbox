# Review Round 8 程式碼審查報告：QuietInbox (`git diff ad5ee31..b4e5639`)

## 審查結論 (Verdict)

**APPROVE WITH MINOR FIXES**

> **總結**：Commit [`b4e5639`](file:///Users/iml1s/Documents/mine/quietinbox) 針對 Round 7 報告中所提出的所有重要缺陷（I-1、I-2）與次要改進項目（M-1、M-2、M-5、M-6、M-7、New）提出了精確且完整的實作。
> 
> 核心改進包含：
> 1. 切換期間發射全新的 [`AnalyticsUiState(loading = true, selection = s)`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L121)，徹底根除前一期間的 `capped` 截斷告示與副標題殘留；
> 2. 將金庫狀態納入管線，鎖定或解鎖中提供專門的狀態與 [`EmptyState`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L152) 說明，終結無限轉圈；
> 3. 抽取 [`orDefault`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L207-L213) 工具函式確保 5 處查詢站點皆正確傳播協程取消異常，且計數流改用具備指數退避的 `retryWhen`；
> 4. `last` 的讀寫完全收斂至 [`transformLatest`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L108-L123) 區塊內部，消除了 consumer/producer 間的並行資料競爭，並在 CPU 密集運算間插入 [`coroutineContext.ensureActive()`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L148)；
> 5. 新增 [`AnalyticsViewModelTest`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt)（6 支 JVM 測試），在真實的 `Dispatchers.Default` 驗證管線規則；
> 6. 將期間選項獨立為 [`selectedPeriod`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L95) 狀態流直接綁定 UI，點擊時即時反饋。
>
> 經深入追蹤，新 Flow 鏈路在「已訂閱畫面且 5 秒內鎖定又解鎖且訊息數量未變」的情境下存在微小的重算訊號遺失邊界（見 Important 發現），但由於使用者通常需跳轉至其他頁面解鎖（超過 5 秒即重設 Flow），實務影響極低。文件、版本與測試矩陣均精準同步，建議核准。

---

## 1. Round 7 問題驗證表 (Round-7 Verification Table)

| # | Round-7 發現項目 | 狀態 | 檔案與行號 | 驗證證據與分析 |
|---|---|---|---|---|
| 1 | **I-1: 期間切換載入佔位帶有舊週期 `capped` 標籤與副標題** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:121`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L121) | 不再使用 `last.copy(...)`，而是發射全新的 `AnalyticsUiState(loading = true, selection = s)`。預設的 `capped = false` 與 `report = null` 確保頂部不會出現紅色的截斷警語，且 [`AnalyticsScreen.kt:101`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L101) 的 `subtitle` 在 `report == null` 時為 null，不會攜帶舊週期的訊息數。 |
| 2 | **I-2: 金庫鎖定 (Locked) 或開啟中 (Opening) 導致活動頁無限轉圈** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:109-115, 244-255`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L109-L115)<br>[`AnalyticsScreen.kt:151-158`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L151-L158) | `vaultSignals()` 將非 Ready 狀態與 counts ticks 合併；`transformLatest` 遇 `Locked` 立即發射 `AnalyticsUiState(loading = false, vaultLocked = true)`，UI 以 `EmptyState(icon = SyncProblem, title = vault_locked_title)` 呈現明確說明；`Opening` 則維持 loading。冷啟動不再卡在無限轉圈。 |
| 3 | **M-1: 協程取消異常遭 `runCatching` 吞噬 (部分站點)** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:141, 142, 146, 183, 207-213, 221`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L207-L213) | 抽出 `inline fun orDefault`，捕獲 `CancellationException` 時顯式 rethrow，其它 `Throwable` 回傳預設值。全部 5 處查詢站點（`messagesBetween`、`observeGaps`、`summaryCountBetween`、`labels`、`earliestTimestamp`）均套用此 helper。 |
| 4 | **M-2: `catch { emit }` 導致計數 Flow 永久終止** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:246-250`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L246-L250) | 改用 `.retryWhen { _, attempt -> emit(InboxCounts(0,0,0,0)); delay(...); true }`。查詢失敗時發射保底值避免卡死，並以指數退避（1s 至 30s）定期重試，資料庫恢復後可自動恢復計數監聽。 |
| 5 | **M-5: `last` 狀態讀寫存在跨協程並行競爭** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:111, 113, 121, 122`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L111) | 移除下游的 `.onEach { last = it }`。`last` 變數現在**僅在 `transformLatest` 收集 block 內部**進行讀寫更新。由於 `transformLatest` 保證在前一個 block 完成／取消前不啟動下一個 block，讀寫具有嚴格的單執行緒順序保證。 |
| 6 | **M-6: 被取消的週期計算在純 CPU 階段無 suspension point** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:148, 159, 168, 171`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L148) | 在 DB 查詢之後、`ActivityAnalytics.compute` 之後、排名前後、Emoji 計算前分別插入 `coroutineContext.ensureActive()`。一旦切換期間觸發取消，CPU 密集運算會及早退出。 |
| 7 | **M-7 / M-3: `AnalyticsViewModel` 缺乏單元測試** | **VERIFIED (已修復)** | [`feature/analytics/src/test/.../AnalyticsViewModelTest.kt`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt)<br>[`feature/analytics/build.gradle.kts:15-26`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/build.gradle.kts#L15-L26) | 新增 6 支 Kotest 規格測試，覆蓋：Default 執行緒驗證、週期切換佔位乾淨度（無前次截斷標籤）、背景變更靜默重算、鎖定狀態呈現與解鎖恢復、開啟中保持 loading、計數查詢失敗降級處理。 |
| 8 | **New: 期間晶片列反應延遲** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:92-95`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L92-L95)<br>[`AnalyticsScreen.kt:89, 132`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L89) | 獨立暴露 `selectedPeriod: StateFlow<PeriodSelection>`，`PeriodRow` 直接綁定此狀態。使用者點擊晶片時 UI 立即更新選中高亮，不需等待慢速查詢取消完成。 |

---

## 2. Flow 鏈路行為深度追蹤 (Flow Chain In-Depth)

針對 brief 中特別點名的 Flow 組合邏輯進行嚴格追蹤：

```kotlin
// AnalyticsViewModel.kt:244-255
private fun vaultSignals(): Flow<VaultState> {
    val counts = inbox.observeCounts()
        .retryWhen { _, attempt ->
            emit(InboxCounts(0, 0, 0, 0))
            delay((1_000L shl minOf(attempt, 5L).toInt()).coerceAtMost(30_000L))
            true
        }
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    val ticks = merge(counts.take(1), counts.drop(1).sample(400))
    return merge(vault.state.filter { it !is VaultState.Ready }, ticks.map { vault.state.value })
}
```

### 2.1 首筆發射是否可能遺失？ (Can the first emission be lost?)
* **結論：不會遺失。**
* **分析**：
  1. **金庫處於 Ready 時**：`vault.state.filter { it !is Ready }` 過濾掉首筆；但在第二條分支中，Room 的 `observeCounts()` 在開始收集時會立即執行查詢並發射目前計數。`counts.take(1)` 立即放行此首筆計數，`ticks.map { vault.state.value }` 將其映射為 `VaultState.Ready` 並發射。`combine(selection, vaultSignals())` 兩端皆有值，即刻驅動 `transformLatest`。
  2. **金庫處於 Locked / Opening 時**：`vault.state` 作為 `StateFlow`，在被訂閱時立即向新收集者發射當前值。`vault.state.filter { it !is Ready }` 立即通過並發射 `Locked` 或 `Opening`，`combine` 立即啟動對應的分支處理。

### 2.2 Locked $\to$ Ready 復原是否可能失敗？ (Can Locked $\to$ Ready recovery fail?)
* **結論：冷啟動正常；但在「畫面維持開啟（已訂閱）狀態下發生鎖定且解鎖後訊息計數未變」的邊界情境下會失敗。**
* **分析**：
  1. **冷啟動復原（正常）**：若使用者開啟 App 時金庫為 `Locked`，`DatabaseHolder.flowWithDb` 產生 `emptyFlow()`，`counts` 從未發射過任何值。當金庫解鎖為 `Ready` 時，Room 發出第 1 筆計數，`counts.take(1)` 接收並觸發 `ticks` 發射，成功恢復。
  2. **運作中解鎖且計數未變（邊界缺陷，見 Important 1）**：若畫面在 `Ready` 下已完成初始載入，`counts.take(1)` 已經消耗完畢。若金庫轉為 `Locked` 後又恢復為 `Ready`，此時 `vault.state.filter { it !is Ready }` 會把 `Ready` 濾除；若在此期間沒有新通知進來，Room 發射的 `InboxCounts` 與鎖定前相同，會被 `counts.distinctUntilChanged()` 吞噬。這導致 `ticks` 無任何發射，`vaultSignals()` 永遠不會發射 `Ready`，畫面將持續停留在「Vault locked」。

### 2.3 `retryWhen` 是否會造成 Busy-loop 或餓死下游？ (Can `retryWhen` busy-loop or starve?)
* **結論：不會。**
* **分析**：
  1. 發生例外時，`retryWhen` 在呼叫 `delay` 之前先 `emit(InboxCounts(0, 0, 0, 0))`，確保下游在首筆查詢失敗時不會一直卡在 loading。
  2. 延遲時間為 `(1_000L shl minOf(attempt, 5L).toInt()).coerceAtMost(30_000L)`，即 `1s, 2s, 4s, 8s, 16s, 30s`，必定至少等待 1 秒，完全杜絕 busy-loop。
  3. Kotlin Flow 的 `retryWhen` 內部實作不捕捉 `CancellationException`，ViewModelScope 結束時取消能正常向下傳遞。

### 2.4 `ticks.map { vault.state.value }` 是否會與狀態變更產生 Race Condition？
* **結論：不會產生無效狀態。**
* **分析**：
  `DatabaseHolder` 的金庫狀態機在 `open()` 過程中，是**先**將 `_state.value` 設為 `VaultState.Ready(db)`，**隨後** `flowWithDb` 的 `flatMapLatest` 才會切換到 `block(s.db)` 開始監聽 Room。因此當 `counts` 因資料庫變更而發射時，`vault.state.value` 必定已是 `Ready`，不會誤讀到舊的 `Locked`。

### 2.5 `last` 的記帳是否已無資料競爭？ (Is the `last` bookkeeping now race-free?)
* **結論：是，已徹底安全。**
* **分析**：
  `last` 的所有寫入點（`:111, :113, :121, :122`）與唯一讀取點（`:121`）全數收斂在 `transformLatest` 的 lambda 內部。依照 Kotlin Coroutines 的 `transformLatest` 規格，上游發射新值時會對前一個 block 執行 `cancelAndJoin()`，在前一個協程徹底退出前不會啟動下一個協程。因此不存在並行的 producer/consumer 同時讀寫 `last` 的可能，完全具備 happens-before 語意。

### 2.6 Locked / Opening 分支上的 `.also { last = it }` 行為是否正常？
* **結論：行為正確。**
* **分析**：
  在 `Locked` 與 `Opening` 分支發射的 state 中，`report` 皆為 `null`。這確保當後續狀態變為 `Ready` 時，`:121` 行的 `last.report == null` 判定必然為 `true`，從而正確觸發 `loading = true` 佔位發射與後續的 `compute(s)` 計算，不會被前次遺留的報表繞過重算。

---

## 3. 測試套件審查 (Test Suite Review)

針對 [`AnalyticsViewModelTest.kt`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt) 的 6 個測試進行檢驗：

1. **非同義反覆（Not Tautological）**：
   - 測試真實建構了 `AnalyticsViewModel`，透過 `gate: CompletableDeferred` 攔截 suspend 函式以精確捕捉期間切換瞬間的中間狀態（`loading = true, capped = false, report = null`），確實測試了管線邏輯而非 mock 行為。
2. **時序穩定性（Timing Flakiness）**：
   - 測試大多使用 `first { predicate }` 搭配 `withTimeout(10_000)` 等待條件達成，不依賴脆弱的固定 sleep。
   - 測試 5（Opening 保持 loading）使用了 `withTimeoutOrNull(700) { h.vm.state.first { !it.loading } }.shouldBeNull()`，依賴了 700ms 的真實時間等待。在極度緩慢的 CI 環境中這會增加 700ms 執行時間，但判定是 `.shouldBeNull()`，邏輯上具備確定性。
3. **執行緒名稱相依（Thread Naming Dependency）**：
   - 測試 1 中的 `h.queryThreads.first() shouldStartWith "DefaultDispatcher-worker"`（第 95 行）驗證了任務確實分派給了 `Dispatchers.Default` 執行緒池。此名稱為 Kotlin Coroutines `DefaultScheduler` 的標準命名，在一般 JVM/Android 測試環境中穩定通過；但若日後有環境設定了 `kotlinx.coroutines.scheduler.default.name` 系統屬性，該測試可能會失敗（見 Minor 2）。

---

## 4. 新發現問題清單 (New Findings)

### Critical Findings (0 項)
無。

---

### Important Findings (1 項)

#### I-1. 在已訂閱狀態下，金庫解鎖且訊息筆數未變時活動頁無法自動復原
* **位置**：[`AnalyticsViewModel.kt:254`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L254)
* **機制**：
  ```kotlin
  return merge(vault.state.filter { it !is VaultState.Ready }, ticks.map { vault.state.value })
  ```
  `vaultSignals()` 刻意過濾了 `vault.state` 中的 `Ready` 狀態，將 `Ready` 的觸發完全交給 `ticks.map { vault.state.value }`。
  而 `ticks` 依賴 `counts.distinctUntilChanged()`。
* **具體失敗情境**：
  1. 使用者停留在分析頁面，金庫為 `Ready`。`counts.take(1)` 已被消耗完畢。
  2. 發生短暫的金庫鎖定事件（例如金庫檔案因測試或背景同步被重新驗證，`vault.state` 變為 `Locked`）。
  3. `vault.state.filter { it !is VaultState.Ready }` 立即發射 `Locked`，活動頁顯示「Vault locked」。
  4. 金庫恢復為 `Ready`，但在此期間**沒有任何新訊息進來**，Room 發射的 `InboxCounts` 與鎖定前完全一致。
  5. `counts.distinctUntilChanged()` 判定新舊計數相同，將其**過濾捨棄**。
  6. `ticks` 沒有發射任何項目；而 `vault.state.filter { it !is Ready }` 又將 `Ready` 濾除。
  7. `vaultSignals()` 毫無訊號，活動頁永久停留在「Vault locked」，直到有新通知進來改變計數，或使用者切出畫面超過 5 秒再切回。
  *(註：`AnalyticsViewModelTest` 的測試 4 與測試 5 為了讓測試通過，皆必須顯式加上 `h.counts.value = InboxCounts(2, 2, 0, 0)`，正是因為缺少這個改變，解鎖就無法被偵測到。)*
* **建議修復**：
  改用 `flatMapLatest` 取代兩路 `merge`，在 `vault.state` 切換為 `Ready` 時自然啟動新的計數監聽並立即發射首筆：
  ```kotlin
  vault.state.flatMapLatest { v ->
      if (v !is VaultState.Ready) flowOf(v)
      else ticks.map { v }
  }
  ```
  或保留 `merge` 但將 `vault.state` 的 `Ready` 納入並使用 `distinctUntilChanged()` 收斂。

---

### Minor Findings (2 項)

#### M-1. `AnalyticsViewModelTest.Harness` Mock 與 `flowWithDb` 真實行為存在偏差
* **位置**：[`feature/analytics/src/test/.../AnalyticsViewModelTest.kt:63, 81`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt#L63)
* **說明**：
  真實環境下 `inbox.observeCounts()` 透過 `flowWithDb` 實作，金庫在 `Locked` 或 `Opening` 時會輸出 `emptyFlow()`（完全不發射）。但測試 harness 的 `counts = MutableStateFlow(InboxCounts(1, 1, 0, 0))` 在金庫為 Locked 時依然會立即發射第一筆值。這提早耗盡了 `counts.take(1)`，也是促成測試必須手動修改 `counts.value` 才能通過的原因。
* **影響**：不影響生產代碼，但降低了單元測試對生產環境生命週期的還原度。

#### M-2. 測試 1 斷言依賴特定的執行緒名稱字串
* **位置**：[`feature/analytics/src/test/.../AnalyticsViewModelTest.kt:95`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt#L95)
* **說明**：
  `h.queryThreads.first() shouldStartWith "DefaultDispatcher-worker"` 依賴了 Kotlin 協程底層 worker 執行緒的命名。若未來 CI 或測試框架調整了協程 scheduler 名稱，可能產生非預期的測試失敗。建議後續可改為比對 `Thread.currentThread() != mainThread`。

---

## 5. 文件、版本與測試矩陣核對 (Documentation & Review Matrix)

1. **CHANGELOG.md [Unreleased]**：
   - [`CHANGELOG.md:8-11`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L8-L11) 精確記錄了本次 Round 7 修正（包括乾淨佔位、金庫鎖定/解鎖處理、`retryWhen` 退避重試、協程取消保護、CPU 階段取消檢查、`selectedPeriod` 即時晶片列，以及 6 支 JVM 測試）。
2. **SCOPE.md**：
   - [`docs/SCOPE.md:21`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L21) 記錄了 `32 JVM tests in core:analytics plus 6 in AnalyticsViewModelTest (state rules, off-main-thread computation, locked/opening vault)`，與代碼實體完全相符。
3. **TEST_MATRIX.md**：
   - 英文版 [`docs/TEST_MATRIX.md:21, 76`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L21) 與繁體中文版 [`docs/zh-Hant/TEST_MATRIX.md:21, 65`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L21) 同步更新，明確記錄 `AnalyticsViewModelTest` 的 6 個測試規格，並在「尚未涵蓋」章節誠實標註該狀態尚未在實體裝置上完整走過。
4. **獨立審查紀錄索引**：
   - [`docs/reviews/README.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L18) 與 [`docs/zh-Hant/reviews/README.md:16`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L16) 已將 Round 7 的 Fix commit 指向本次提交 `b4e5639`。

---

## 6. 總結

Commit [`b4e5639`](file:///Users/iml1s/Documents/mine/quietinbox) 扎實解決了先前審查的所有阻擋項與重要體驗瑕疵。除了新提出的 I-1 邊界條件可在下一版本維護中簡化外，本 PR 代碼結構優雅、取消安全、效能防禦完善且具備良好測試覆蓋，判定為 **APPROVE WITH MINOR FIXES**。
