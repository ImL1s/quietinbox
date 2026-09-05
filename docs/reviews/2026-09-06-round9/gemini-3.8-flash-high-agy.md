# Review Round 9 程式碼審查報告：QuietInbox (`git diff 652aa69..ea34339`)

## 審查結論 (Verdict)

# **APPROVE**

> **總結**：本次審查涵蓋 commit [`1249be5`](file:///Users/iml1s/Documents/mine/quietinbox) 與 [`ea34339`](file:///Users/iml1s/Documents/mine/quietinbox)。這兩個 commit 精準、徹底地解決了 Round 8 審查報告中所列出的所有問題（包含 agy 指出的 Important I-1 / subagent Minor-1，以及 subagent 提出的 3 項次要改進與測試脆弱點）：
> 1. **金庫外層訊號化 (`flatMapLatest`)**：以 `vault.state` 作為外層驅動訊號，在 `Ready` 時重新掛載計數流，使金庫在計數未變的情況下解鎖也能保證頁面完全復原；
> 2. **誠實降級標籤 (`Degradation`)**：將 `orDefault` 升級為依附於單次計算生命週期的 `Degradation.orDefault`，查詢異常時在 UI 上以錯誤色明確標示誠實標籤（`analytics_degraded`，英文與繁體中文語系同步），且在載入與佔位期間有嚴格守衛保護，絕不殘留；
> 3. **退避計數器自動歸零**：計數查詢的指數退避計數器改由 local `consecutiveFailures` 與 `.onEach { consecutiveFailures = 0 }` 控制，一旦連線成功立即重置；
> 4. **測試套件確定性與完整性**：重構「安靜重算」測試（使用 `CompletableDeferred` 門閂凍結進行中的重算查詢，徹底避免 StateFlow 合併導致的漏報），測試 Harness 精準鏡像 `DatabaseHolder.flowWithDb`，執行緒驗證改用 `shouldNotBe testThread` 消除對排程器命名的脆弱相依，並新增第 7 與第 8 支單元測試；
> 5. **CI 與文件完全同步**：CI JVM 測試工作納入 `:platform:capture:testDebugUnitTest` 與 `:feature:analytics:testDebugUnitTest`；Import 嚴格排序；CHANGELOG、SCOPE、TEST_MATRIX（雙語）及 Review 索引完整同步。
> 
> 經實測，`:feature:analytics:testDebugUnitTest` 8 支測試全數通過（2.136s，0 failures, 0 errors），未發現任何 Critical 或 Important 迴歸缺陷。

---

## 1. Round-8 發現項目驗證表 (Round-8 Verification Table)

| # | Round-8 發現項目 | 狀態 | 檔案與行號 | 驗證證據與分析 |
|---|---|---|---|---|
| 1 | **agy I-1 / subagent Minor-1: 金庫解鎖但計數未變時活動頁無法復原** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:265-268`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L265-L268)<br>[`AnalyticsViewModelTest.kt:155-163`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt#L155-L163) | 改用 `vault.state.flatMapLatest { v -> if (v !is VaultState.Ready) flowOf(v) else merge(counts.take(1), counts.drop(1).sample(400)).map { v } }`。外層監聽 `vault.state`，一旦狀態轉變為 `Ready`，必定啟動新的計數訂閱，由 `shareIn(..., replay = 1)` 立即重播當前計數並發射首筆 tick，不再受限於 `counts.distinctUntilChanged()`。測試 4 移除人為計數修改，並新增測試 5 驗證頁面開著時鎖定再解鎖（計數完全不變）可確實復原。 |
| 2 | **subagent M-1 (Minor-8): 測試 Harness 未鏡像 `flowWithDb`** | **VERIFIED (已修復)** | [`AnalyticsViewModelTest.kt:83-85`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt#L83-L85) | `Harness` 中的 `inbox.observeCounts()` 改寫為 `this@Harness.vaultState.flatMapLatest { v -> if (v is VaultState.Ready) (countsFlow ?: counts) else emptyFlow() }`，與 [`DatabaseHolder.kt:74-75`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/DatabaseHolder.kt#L74-L75) 的 `flowWithDb` 保持 1:1 行為一致（金庫非 Ready 時靜默且不發射）。 |
| 3 | **subagent M-1 (Minor-7): 執行緒斷言依賴協程排程器工作執行緒名稱** | **VERIFIED (已修復)** | [`AnalyticsViewModelTest.kt:94, 101`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt#L94) | 移除字串比對 `shouldStartWith "DefaultDispatcher-worker"`，改以 `val testThread = Thread.currentThread().name` 搭配 `h.queryThreads.first() shouldNotBe testThread`。語意精準（確認非收集端/主執行緒），不再受 JVM 或排程器內部命名變更影響。 |
| 4 | **subagent Minor-3: `retryWhen` 累計 `attempt` 不會在成功發射後重置** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:257-263`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L257-L263) | 抽出區域變數 `var consecutiveFailures = 0`，在 `retryWhen` 上游插入 `.onEach { consecutiveFailures = 0 }`，並在重試區塊中使用 `consecutiveFailures++`。只要有任一筆正常計數成功送出，退避延遲立即重歸 1 秒。 |
| 5 | **subagent Minor-4: 查詢失敗被靜默降級，違反誠實標籤原則** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:85, 143, 208-223`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L208-L223)<br>[`AnalyticsScreen.kt:151-159`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L151-L159)<br>[`strings_analytics.xml`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values/strings_analytics.xml#L77) | 新增 `AnalyticsUiState.degraded: Boolean`；在 `compute()` 引入私有輔助類別 `Degradation` 與延伸函式 `Degradation.orDefault`，遇非取消例外時設 `any = true`；UI 在 `state.degraded && !state.loading` 時呈現錯誤顏色文字標籤（英文：`analytics_degraded`，中文：`部分資料無法讀取；此報表可能不完整。`）；新增測試 8 驗證此標籤行為。 |
| 6 | **subagent Minor-5: 「安靜重算」測試可能被 StateFlow 合併悄悄放過** | **VERIFIED (已修復)** | [`AnalyticsViewModelTest.kt:124-142`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt#L124-L142) | 改用 `h.gate = CompletableDeferred()` 卡住重算時的第一個訊息查詢，透過 `while (h.queries.get() <= before) delay(20)` 確保重算已在執行中且被卡住，此時明確斷言 `h.vm.state.value.loading.shouldBeFalse()` 與 `report.shouldNotBeNull()`。若實作移除了 loading 防護而發射佔位狀態，測試必定紅燈失敗。 |
| 7 | **CI 與平台測試涵蓋不足** | **VERIFIED (已修復)** | [`.github/workflows/ci.yml:17, 31-32`](file:///Users/iml1s/Documents/mine/quietinbox/.github/workflows/ci.yml#L17) | `ci.yml` 的 `jvm-tests` 步驟完整加入 `:platform:capture:testDebugUnitTest` 與 `:feature:analytics:testDebugUnitTest`，工作名稱更新為 `JVM unit tests (core, parsers, platform, feature, app)`。 |
| 8 | **Import 排序凌亂** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:3-54`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L3-L54)<br>[`AnalyticsViewModelTest.kt:3-44`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt#L3-L44) | 所有 import 依字母順序（從 `androidx`、`dagger`、`dev`、`javax`、`kotlin`、`kotlinx`）嚴格排列，無重複或未使用項目。 |

---

## 2. 深入架構與迴歸分析 (Regression Analysis)

針對 brief 提示的 7 個關鍵面向進行深入檢視：

### 2.1 `flatMapLatest` 在金庫狀態切換時的取消行為（是否會遺失 tick 或重複計算？）
* **分析**：
  ```kotlin
  return vault.state.flatMapLatest { v ->
      if (v !is VaultState.Ready) flowOf(v)
      else merge(counts.take(1), counts.drop(1).sample(400)).map { v }
  }
  ```
  1. **無重複計算**：`vault.state` 是 `StateFlow`，內部具備 `distinctUntilChanged` 特性，相同狀態不會重複觸發 `flatMapLatest`。進入 `Ready` 分支時，`counts.take(1)` 取得 replay 的第 1 筆計數即完成，`counts.drop(1)` 丟棄第 1 筆後才開始每 400ms 取樣後續變更。兩條流互斥，不會對首筆計數發射兩次。
  2. **無遺失 tick**：當金庫轉為 `Ready` 時，`shareIn` 的 `replay = 1` 確保內層流在訂閱瞬間立刻獲得最新計數，並經由 `combine(selection, vaultSignals())` 驅動 `compute(s)`；在 `Ready` 存續期間，內層訂閱持續生效，資料庫的任何增減皆能即時送達。
  3. **取消保護**：若金庫由 `Ready` 轉為 `Locked`，`flatMapLatest` 會立即取消內層的 Room 計數監聽協程，徹底避免在資料庫關閉期間觸發無效查詢。

### 2.2 `consecutiveFailures` 區域變數被 Flow 鏈路上的兩個 Lambda 捕獲之安全性
* **分析**：
  1. `consecutiveFailures` 為 `vaultSignals()` 的區域 `var`。因為 `vaultSignals()` 僅在 `val state: StateFlow` 初始化時呼叫一次，該變數的生命週期綁定在該 ViewModel 實例上。
  2. `counts` 由 `shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)` 管理。在 Kotlin Coroutines Flow 規範中，單一上游資料流的收集是由 `shareIn` 的單一工作協程順序驅動的。
  3. `.onEach { consecutiveFailures = 0 }` 與 `.retryWhen { _, _ -> consecutiveFailures++ }` 是這條上游流的序列算子：發射成功時由工作協程執行 `onEach`，拋出例外時由同一工作協程進入 `retryWhen`。兩者不會並行執行，不存在執行緒資料競爭（Race Condition）。

### 2.3 `Degradation` 作為私有類別與 inline member-extension 之架構健康度
* **分析**：
  ```kotlin
  private class Degradation { var any = false }
  private inline fun <T> Degradation.orDefault(default: T, block: () -> T): T = try {
      block()
  } catch (e: CancellationException) {
      throw e
  } catch (e: Throwable) {
      any = true
      default
  }
  ```
  1. **生命週期完全隔離**：每次呼叫 `compute(s)` 皆透過 `val degradation = Degradation()` 建立全新物件，計算完畢後作為一次性標記使用。計算結束後的狀態 `degraded = degradation.any` 直接存入不可變的 `AnalyticsUiState`。
  2. **避免 cross-talk**：由於它是單次呼叫區域物件，前次計算的查詢失敗絕不會污染到下一次計算；若資料庫暫時性錯誤在下一次重算時復原，新的 `Degradation.any` 即為 `false`，畫面標籤自動清除。
  3. **取消語意完備**：顯式捕捉 `CancellationException` 並重新拋出，保證切換週期或離開畫面時協程正常 unwind。

### 2.4 `degraded` 標籤是否可能在載入中顯示或被佔位狀態殘留？
* **分析**：
  1. `AnalyticsUiState` 定義中 `degraded: Boolean = false` 為預設值。
  2. 週期切換或金庫狀態變更發射的佔位物件皆為全新的 `AnalyticsUiState(loading = true, selection = s)`，`degraded` 均為 `false`。
  3. 畫面端在 [`AnalyticsScreen.kt:151`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L151) 加上了雙重防護：
     ```kotlin
     if (state.degraded && !state.loading) {
         Text(stringResource(R.string.analytics_degraded), ...)
     }
     ```
     即使未來有人誤在 `loading = true` 時給予 `degraded = true`，`!state.loading` 的守衛也保證載入轉圈期間絕不渲染該標籤。

### 2.5 雙語 `strings_analytics.xml` 的一致性檢驗
* **分析**：
  - 英文 ([`core/designsystem/.../values/strings_analytics.xml:77`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values/strings_analytics.xml#L77))：
    `<string name="analytics_degraded">Part of the vault could not be read; this report may be incomplete.</string>`
  - 繁中 ([`core/designsystem/.../values-b+zh+Hant/strings_analytics.xml:76`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh+Hant/strings_analytics.xml#L76))：
    `<string name="analytics_degraded">部分資料無法讀取；此報表可能不完整。</string>`
  - 兩者資源名稱完全吻合、無格式化參數缺漏、文意通順且符合專案的「誠實資料品質」原則。

### 2.6 單元測試套件（8 個測試）的確定性與時序檢驗
* **實測**：執行 `./gradlew :feature:analytics:testDebugUnitTest --rerun-tasks`，8 支測試全數通過（2.136s）：
  1. `first report arrives, carries no stale label, and is computed off the main thread` (0.762s)
  2. `a period switch shows a clean loading placeholder: no report and no capped label from the previous period` (0.157s)
  3. `a vault change recomputes quietly, without a loading state` (0.424s)
  4. `a locked vault is shown as locked, never as an endless spinner, and recovers once unlocked` (0.010s)
  5. `a vault that locks while the page is open recovers when unlocked, even without a count change` (0.010s)
  6. `an opening vault keeps the loading state and computes once ready` (0.712s)
  7. `a failing count query does not leave the page loading` (0.008s)
  8. `a failing query marks the report as degraded instead of passing it off as complete` (0.007s)
* **確定性評估**：
  - 測試 2 與測試 3 使用 `CompletableDeferred` 門閂攔截掛起函式，直接在查詢阻塞的中間點檢驗 StateFlow 當前值，完全不受背景排程延遲干擾。
  - 測試 6 使用 `withTimeoutOrNull(700)` 檢查 `Opening` 狀態下未過早結束 loading，700ms 大於 sampling 400ms，在慢速 CI 環境中只會等待並確定性回傳 null，非同義反覆。

### 2.7 文件與代碼真實性同步確認
* **核對結果**：
  - `CHANGELOG.md`：`[Unreleased]` 節段記載 8 JVM tests 及 Round 8 各項修正，精準無誇大。
  - `docs/SCOPE.md:21`：標註 `plus 8 in AnalyticsViewModelTest`，與程式碼實體一致。
  - `docs/TEST_MATRIX.md:21` 與 `docs/zh-Hant/TEST_MATRIX.md:21`：測試項目清單雙語皆同步為 8 項規格說明。
  - `docs/reviews/README.md:19` 與 `docs/zh-Hant/reviews/README.md:17`：Round 8 審查結論與 commit 追蹤紀錄雙語齊全。

---

## 3. 新發現問題清單 (New Findings)

### Critical Findings (0 項)
無。

### Important Findings (0 項)
無。

### Minor Observations (3 項，皆為極低優先級觀察，不阻擋發布)

#### 1. `consecutiveFailures` 在極端長生命週期下的整數累加邊界
* **位置**：[`AnalyticsViewModel.kt:261`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L261)
* **現象**：`minOf(consecutiveFailures++, 5)` 在持續失敗時會不斷遞增 `consecutiveFailures`。
* **分析**：雖然 `minOf(..., 5)` 限制了 bit-shift 操作數上限，但理論上若應用程式持續處於資料庫失敗狀態達 20 億次重試（需耗時數千年），`consecutiveFailures` 可能溢位為負數。
* **建議改善**（後續優化即可）：可收斂寫法為 `minOf(consecutiveFailures + 1, 6)`，避免無上限遞增。

#### 2. `Degradation.orDefault` 捕捉 `Throwable` 包含 JVM `Error`
* **位置**：[`AnalyticsViewModel.kt:218`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L218)
* **現象**：`catch (e: Throwable)` 會捕獲包含 `OutOfMemoryError`、`LinkageError` 等非 `Exception` 的嚴重 JVM 錯誤。
* **分析**：目前已有顯式 rethrow `CancellationException`，已避開最常見的協程取消失效；但在標準 JVM 慣例中，一般建議收斂為 `catch (e: Exception)`，讓嚴重的系統級 `Error` 能夠正常中斷進程。

#### 3. 測試 Harness 未顯式取消 `viewModelScope`
* **位置**：[`AnalyticsViewModelTest.kt:87`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModelTest.kt#L87)
* **現象**：測試 Harness 實例化 `AnalyticsViewModel`，但未在測試結束時呼叫 `viewModelScope.cancel()`。
* **分析**：由於 `WhileSubscribed(5_000)` 在收集者離開後會停止上游，且每個 test 皆使用獨立的 Harness 實例，目前在 JVM 單元測試中不會造成干擾或跨測試外洩。若日後測試案例持續擴增，建議在 `afterEach` 加入清理機制。

---

## 4. 總結評語

Commit `1249be5` 與 `ea34339` 展示了極高的重構品質與嚴謹度：
- 不僅徹底修復了 Round 8 指出的所有問題（包含重要邊界與次要細節），
- 且在不破壞任何既有行為的前提下，補齊了對應的確定性回歸測試與雙語字串誠實標籤，
- 全套 8 支單元測試與 CI 工作流程均已驗證通過。

審查結論維持 **APPROVE**，可直接推進後續流程。
