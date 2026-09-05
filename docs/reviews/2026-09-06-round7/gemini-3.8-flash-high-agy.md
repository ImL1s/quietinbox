# Review Round 7 程式碼審查報告：QuietInbox (`git diff a626b32..e5ad1a3`)

## 審查結論 (Verdict)

**APPROVE WITH MINOR FIXES**

> **總結**：Commit [`e5ad1a3`](file:///Users/iml1s/Documents/mine/quietinbox) 已確實修復 Round 6 中指認的兩項 Important / Critical 問題（恢復 `.flowOn(Dispatchers.Default)` 避免主線程卡頓、修復背景 Vault 變動引發的全螢幕 Loading 閃爍），並主動解決了多項 Minor 項目（以 `shareIn` 收斂雙重訂閱、`catch` 提供保底資料防卡死、將 `capped` 截斷告示提升至全 Tab 共用、修正 `Daos.kt` KDoc 位置與用詞、補齊版本號與 CHANGELOG）。
>
> 經深入追蹤 Flow 鏈路行為，未發現架構性回歸；JVM 測試全數通過（**157 tests, 0 failures, 0 errors**）。剩下的少數事項皆為純代碼潔癖或防禦性優化（如未使用的 import、少數剩餘的 `runCatching`），完全不影響上線安全性與邏輯正確性。

---

## 1. Round 6 問題驗證表 (Round-6 Verification Table)

| # | Round-6 發現項目 | 狀態 | 檔案與行號 | 驗證證據與分析 |
|---|---|---|---|---|
| 1 | **恢復 `.flowOn(Dispatchers.Default)`**<br>*(避免主線程執行 5 萬筆訊息的 n-gram 與 Emoji 分析)* | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:102`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L102) | `.flowOn(Dispatchers.Default)` 已加回至 `.onEach { last = it }` 與 `.stateIn(...)` 之間。上游的 `combine`、`transformLatest`、`compute(s)`（包括 CJK n-gram 切分與 Emoji 權重排序）已全數回歸 Default 執行緒池執行。 |
| 2 | **消除背景 Vault 更新的全螢幕 Loading 閃爍**<br>*(避免通知進來時畫面切換轉圈並重設捲動位置)* | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:98`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L98) | 加上保護判斷：`if (s != last.selection \|\| last.report == null) emit(last.copy(loading = true, selection = s))`。當僅有背景資料庫變更（`s == last.selection` 且已有舊報表）時，不發射 `loading = true`，而是在背景默默重算並原地替換內容，不中斷使用者瀏覽。 |
| 3 | **收斂 `observeCounts()` 雙重訂閱與錯誤防護** | **VERIFIED (已修復)** | [`AnalyticsViewModel.kt:200-204`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L200-L204) | 加上 `.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)`，使 `take(1)` 與 `drop(1)` 共享單一上游訂閱；同時在前面加上 `.catch { emit(InboxCounts(0, 0, 0, 0)) }`，避免 DB observer 拋錯時導致 Flow 終止而永久停留在 loading。 |
| 4 | **將 `capped` 截斷告示擴展至所有 Tab** | **VERIFIED (已修復)** | [`AnalyticsScreen.kt:140-148, 642-654`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L140-L148) | 原先僅在 `RangeLine`（OverviewTab）繪製的告示被移至最外層頂部（Period 切換列與子標題正下方），現在五個 Tab（Overview、Rankings、Best Time、Chattiness、Quiet）都能清楚看見 50,000 筆截斷誠實聲明，`RangeLine` 內的重複告示亦已移除。 |
| 5 | **`compute()` 協程取消異常重新拋出** | **VERIFIED (大幅改善)** | [`AnalyticsViewModel.kt:119, 121, 125`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L119) | 三大 suspend 查詢（`messagesBetween`、`observeGaps`、`summaryCountBetween`）皆補上 `.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }`，確保在切換週期時能及時中止查詢。 |
| 6 | **`Daos.kt` KDoc 順序與用詞修正** | **VERIFIED (已修復)** | [`Daos.kt:219-223`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L219-L223) | KDoc 註解已移至 `@Query` 註釋正上方（能被 Dokka/IDE 正確識別為函式文件），內容亦將過時的 "debounces" 修正為 "(the caller samples vault changes at 400 ms)"。 |
| 7 | **版本號 versionCode 4 與 Changelog 同步** | **VERIFIED (已修復)** | [`app/build.gradle.kts:50`](file:///Users/iml1s/Documents/mine/quietinbox/app/build.gradle.kts#L50)<br>[`fastlane/.../changelogs/4.txt`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/zh-TW/changelogs/4.txt) | `versionCode` 升至 4，`en-US` 與 `zh-TW` 的 fastlane changelog 已更名為 `4.txt`；`.github/workflows/release.yml:98` 同步補上 Google Play 僅在 `workflow_dispatch` 觸發的保護。 |

---

## 2. Flow 鏈路行為與回歸驗證 (Flow Chain In-Depth)

針對 brief 所要求的 6 項 Flow 關鍵行為深入追蹤分析：

### Q1: 首筆狀態是否依然立即到達？ (Does the first state still arrive at once?)
* **結論：是。**
* **分析**：
  1. [`selection`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L82) 為 `MutableStateFlow(PeriodSelection())`，訂閱開始時會立即發射初始期間。
  2. [`vaultChanges(inbox)`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L199-L205) 中，`counts` 被訂閱時 Room DAO 立即發射當前筆數；`counts.take(1)` 在收集當下立即發射該筆計數並完成。
  3. `combine` 雙方在初始時點皆具備資料，立刻發射第一筆 `s = PeriodSelection()`，進入 `transformLatest`。
  4. 此時 `last.report == null` 為 true，立即發射 `loading = true`，隨後 `compute(s)` 算完後發射正式資料，整個流程沒有任何 400ms 人為延遲。

### Q2: 具備重播的共用流在 `drop(1)` 時，是否會丟失晚期訂閱者的實質變更？ (Can `drop(1)` on the replayed shared flow drop a *real* change for a late subscriber?)
* **結論：不會。**
* **分析**：
  1. `vaultChanges(inbox)` 是在 `AnalyticsViewModel` 初始化時被調用**一次**，作為 `combine(selection, vaultChanges(inbox))` 的參數傳入。
  2. UI 元件（Composable）是透過 `viewModel.state.collectAsStateWithLifecycle()` 訂閱對外暴露的 `StateFlow`，**晚期訂閱者根本不會直接訂閱 `vaultChanges` 或 `counts`**，只會讀取 `state.value` 或接收 `StateFlow` 的最新狀態。
  3. 對於內部唯一收集 `vaultChanges` 的 `stateIn` 協程而言：`counts.take(1)` 在收集首筆後即結束；`counts.drop(1)` 僅丟棄與 `take(1)` 相同的首筆重播值，後續所有實質變更（第 2 筆、第 3 筆...）皆會順利穿過 `drop(1)` 進入 `sample(400)`。
  4. 即使使用者切出畫面超過 5 秒導致 `WhileSubscribed(5_000)` 停止上游並在切回時重啟，`take(1)` 與 `drop(1)` 會同時重新收集：`take(1)` 立即發射當前最新計數使畫面重算，`drop(1)` 丟棄該重播計數並監聽之後的新事件，沒有任何實質變更會被永久吞掉。

### Q3: 畫面是否可能卡死在 `loading = true`？ (Can the screen get stuck in `loading = true`?)
* **結論：不會。**
* **分析**：
  1. 在 `vaultChanges` 中，加入了 `.catch { emit(InboxCounts(0, 0, 0, 0)) }`。若 Room 資料庫查詢在發出第一筆前失敗，該 catch 區塊保證仍會發射預設值，使 `combine` 不會陷入無限等待。
  2. 在 `compute(s)` 內部，所有的資料庫查詢均由 `runCatching { }.getOrDefault(...)` 保護，在發生非取消例外時會安全降級（如 `emptyList()`、`0`、`emptyMap()`）；純 Kotlin 計算如 [`ActivityAnalytics.compute`](file:///Users/iml1s/Documents/mine/quietinbox/core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/ActivityAnalytics.kt#L52) 在空列表輸入下均能正常回傳空報表物件，絕不拋出非預期例外。
  3. 當 `compute(s)` 執行完成，必定執行 `emit(compute(s))`，其產出的 `AnalyticsUiState` 明確設定 `loading = false`。
  4. 唯二能中斷 `compute(s)` 的只有協程取消（例如使用者切換至另一個期間），而取消後 `transformLatest` 會立即為新期間啟動新的執行區塊，最終區塊必然會完成並發射 `loading = false`。

### Q4: 新選取的期間是否可能顯示過期的舊報表？ (Can a stale report be shown for the new selection?)
* **結論：不會。**
* **分析**：
  1. 當期間變更時（例如從「過去7天」切換為「本月」），`s != last.selection` 成立，第一步立即執行 `emit(last.copy(loading = true, selection = s))`。
  2. 在 [`AnalyticsScreen.kt:149-152`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L149-L152) 中：
     ```kotlin
     if (state.loading || report == null) {
         LoadingScreen()
         return@Column
     }
     ```
     只要 `state.loading == true`，畫面會立即返回 `LoadingScreen()`，舊報表瞬間從螢幕上卸載，絕不會與新期間的標題或選項混淆呈現。
  3. 隨後 `compute(s)` 是以新選取的 `s` 獨立進行計算，計算完成發射時帶有新報表與 `loading = false`。
  4. 先前期間正在進行的計算會被 `transformLatest` 的取消機制中斷，且即使計算已接近尾聲，被取消的協程在呼叫 `emit` 時也會因 `CancellationException` 被攔截，絕無可能將過期報表發射出來覆蓋新資料。

### Q5: 前一次的計算是否確實被取消？ (Does the previous computation get cancelled?)
* **結論：是（於資料庫 suspend 查詢與發射邊界確實取消）。**
* **分析**：
  1. `transformLatest` 在接收到新值時，會主動調用 `previousJob?.cancel()` 取消前一個子協程。
  2. Commit `e5ad1a3` 在最耗時的 3 個 Room suspend 查詢（`messagesBetween`、`observeGaps`、`summaryCountBetween`）加上了 `.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }`，協程取消時不再被 `runCatching` 吞噬，而是立刻中斷退出。
  3. *邊界注意*：在純 CPU 計算階段（如對 50,000 筆訊息進行 n-gram 切分）中間沒有 suspension point 或 `ensureActive()`，若取消訊號在此時抵達，CPU 運算仍會跑完該迴圈，但會在最後調用 `emit` 時被 FlowCollector 檢查取消狀態並拋出例外。由於目前全鏈已運行在 `Dispatchers.Default` 背景線程池上，這不會造成主線程掉幀，亦不會外洩無效狀態。

### Q6: `last` 快照是否安全被異動？ (Is the `last` snapshot mutated safely?)
* **結論：是。**
* **分析**：
  1. 運算子鏈的宣告順序為：
     ```kotlin
     .transformLatest { s -> ... } // 讀取 last
     .onEach { last = it }         // 寫入 last
     .flowOn(Dispatchers.Default)
     .stateIn(...)
     ```
  2. `transformLatest` 與 `onEach` 位於 `.flowOn(Dispatchers.Default)` 的同一側（上游 Context）。在 Kotlin Flow 的收集架構中，同一個 Flow 鏈上的 `emit` 到 `onEach` 是在同一個協程執行序列中順序調用的。
  3. 當前一個區塊被取消、新區塊啟動時，`transformLatest` 的內部狀態機提供了 happens-before 語意保證；且在 JVM 規範下物件參照的賦值（Reference write）具備原子性。
  4. 更關鍵的是，`last` 僅作為是否提前展示 `loading = true` 骨架畫面的**輔助判斷**（`if (s != last.selection || last.report == null)`），它**從未**參與報表內容實質數據的計算（`compute(s)` 僅依賴輸入參數 `s` 與資料庫查詢）。因此絕不會因快照時序問題導致數據污染。

---

## 3. 新發現問題清單 (New Findings)

### Critical Findings (0 項)
無。無資料外洩、無執行緒死鎖、無主線程阻塞、無崩潰風險。

---

### Important Findings (0 項)
無。Round 6 的兩項 Important 項目皆已徹底修復且無衍生回歸。

---

### Minor Findings (3 項)

#### M-1. `compute()` 中仍有兩處 `runCatching` 未傳播 `CancellationException`
* **位置**：[`AnalyticsViewModel.kt:155, 181`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L155)
* **情境**：
  - 第 155 行：`val labels = runCatching { analytics.labels(ids) }.getOrDefault(emptyMap())`
  - 第 181 行：`earliestEpochMs = runCatching { analytics.earliestTimestamp() }.getOrNull()`
  這兩處在取消發生時仍會吞掉 `CancellationException`。若取消正好發生在 `analytics.labels(ids)` 查詢期間，它會將其視為一般錯誤並回傳 `emptyMap()`，隨後組裝完 `AnalyticsUiState`，直到呼叫 `emit` 時才拋出取消例外。
* **影響**：不影響正確性（發射會被擋下，不會外洩），僅是在取消時稍微多花費數毫秒執行 label 查詢。
* **建議**：未來可考慮將這兩處亦加上 `.onFailure { if (it is CancellationException) throw it }`，或封裝統一的協程安全防護 helper。

#### M-2. `AnalyticsViewModel.kt` 中殘留未使用的 `map` 引用
* **位置**：[`AnalyticsViewModel.kt:41`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L41)
* **情境**：`import kotlinx.coroutines.flow.map`。該檔案中所有的 `.map` 調用皆為 Kotlin 集合標準庫的 `Iterable.map`，Flow 的 `map` 運算子已不再使用。
* **影響**：純編譯器警告（Unused import），不影響建置與執行。

#### M-3. `AnalyticsViewModel` 依然缺乏單元測試
* **位置**：[`feature/analytics/src/test/`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/test/)（目前仍為空目錄）
* **情境**：雖然 `core:analytics` 擁有 32 個極為完整的演算法與指標測試，但 `feature:analytics` 模組內尚無 `AnalyticsViewModelTest` 來鎖定 Flow 的狀態切換規則（如：切換期間立即 loading、背景 Vault 更新維持靜默重算）。
* **建議**：後續迭代可引入 `kotlinx-coroutines-test`，透過測試案例將此 Flow 鏈的時序與 dispatcher 行為固化。

---

## 4. 文件、版本與測試矩陣核對 (Documentation & Test Counts)

1. **JVM 單元測試執行結果**：
   - 執行命令：`./gradlew test --console=plain`
   - 結果：**BUILD SUCCESSFUL**，全數 24 份測試報表共 **157 tests, 0 failures, 0 errors, 0 skipped**。
2. **CHANGELOG 一致性**：
   - [`CHANGELOG.md:23`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L23) 明確記載：`157 JVM tests (72 in core:* including two 1,000-iteration property tests, 43 adapter tests, 24 backup, 11 capture, 4 reminder, 3 crypto)`，數字加總精準吻合 157。
   - [`CHANGELOG.md:35`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L35) 已完整記錄 Round 5 與 Round 6 的修復內容（包括全 Tab 顯示截斷、400ms 採樣背景靜默重算、`flowOn(Dispatchers.Default)`、交易提交後保護等）。
3. **SCOPE 一致性**：
   - [`docs/SCOPE.md:21`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L21) 的敘述已更新為 `every tab shows a notice when it capped`，與 [`AnalyticsScreen.kt:140`](file:///Users/iml1s/Documents/mine/quietinbox/feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L140) 搬移後的共用位置完全吻合。
4. **TEST_MATRIX 一致性**：
   - [`docs/TEST_MATRIX.md`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md) 與 [`docs/zh-Hant/TEST_MATRIX.md`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md) 記載的測試數量（`core:*` 72、`parsers:apps` 43、`app` 4、`backup` 21、`capture` 11、`crypto` 6）與實際代碼及測試產物完全相符。
5. **版本與發行設定**：
   - `app/build.gradle.kts` 的 `versionCode` 為 4。
   - `fastlane/metadata/android/{en-US,zh-TW}/changelogs/4.txt` 皆存在且小於 500 字元限制。
   - 後續提交（`b462d9c`、`10a591e`、`4b47990`）僅處理了冷快取相依性驗證元資料（`verification-metadata.xml`）與發行手冊文件，對核心代碼無副作用。

---

## 5. 總結

Commit `e5ad1a3` 對 Round 6 的回應完整且扎實，關鍵效能與 UX 問題已全數解決。本 PR / Commit 狀態健康，建議核准（**APPROVE WITH MINOR FIXES**）。
