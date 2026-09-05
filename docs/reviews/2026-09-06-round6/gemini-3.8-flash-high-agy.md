# Review Round 6 程式碼審查報告：QuietInbox (fa49902..a626b32)

## 審查結論 (Verdict)
**APPROVE WITH MINOR FIXES**

> **總結**：Round 5 所指認的 7 項問題（包括最關鍵的 `capped` 截斷告示未渲染問題）在此 commit [`a626b32`](file://<repo>) 中已全數確實修復，`BackupService.apply` 的事務提交後取消保護與 `SnapshotFactory.isSelf` 的空字串防禦均已完備，文件與版本號亦已同步。
> 唯一需要在發布前微調的是：重構後的 [`AnalyticsViewModel`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L91-L99) Flow 鏈在引入 `transformLatest` 時遺漏了非主線程切換（`.flowOn(Dispatchers.Default)` 或 `withContext`），導致多達 50,000 筆訊息的 CJK 口頭禪與表情符號分析會在主線程執行；同時背景資料庫更新會連帶觸發 `loading = true` 造成短暫閃爍。修復方式僅需數行，補齊後即可正式上線。

---

## 1. Round 5 修復項目驗證表 (Verification Table)

| # | 修復項目 | 審查狀態 | 涉及檔案與行號 | 驗證分析與結論 |
|---|---|---|---|---|
| 1 | **渲染 `capped` 截斷告示** | **VERIFIED** | [`AnalyticsScreen.kt:645-652`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L645-L652) | 在 `RangeLine` 中判斷 `if (state.capped)`，渲染 `R.string.analytics_capped` 並代入 [`AnalyticsRepository.MESSAGE_CAP`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/AnalyticsRepository.kt#L20)（50,000）。雙語字串資源與參數格式皆吻合。 |
| 2 | **Flow 鏈重構（取首筆立即發射 + 400ms 採樣 + loading 狀態）** | **VERIFIED (帶有改進項)** | [`AnalyticsViewModel.kt:89-99, 193-198`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L89-L99) | `vaultChanges` 採用 `merge(counts.take(1), counts.drop(1).sample(400))`，首筆訊號立即發射；`transformLatest` 切換期間立即 emit `loading = true` 並取消先前計算。 |
| 3 | **`BackupService.apply` 交易提交保護 (`committed` 旗標)** | **VERIFIED** | [`BackupService.kt:216, 340, 347`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L216) | 增加 `var committed = false`，在 `withTransaction` 提交成功後標記為 `true`。`catch` 區塊採用 `if (!committed \|\| f !in usedFiles) mediaDir.delete(f)`，保證若在提交完成後發生 `CancellationException`，已寫入 DB 的 blob 不會被誤刪。 |
| 4 | **`SnapshotFactory.isSelf` 忽略空白或空名稱** | **VERIFIED** | [`SnapshotFactory.kt:60-65, 168`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt#L168) | 將名稱比對收斂為 `!selfName.isNullOrBlank() && person.name?.toString() == selfName`，避免裝置擁有者名稱未填或為空白字元時與無名發送者錯誤判定為 self。 |
| 5 | **文件化 `statsBetween` 排序與索引權衡** | **VERIFIED** | [`Daos.kt:228-233`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L228-L233) | 為 [`MessageDao.statsBetween`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L233) 加上 KDoc，詳細說明在 Schema v2 中缺乏 `sortKey` 索引時，SQLite 掃描區間並對至多 `limit` 筆進行排序是以 CPU 交換受限記憶體堆疊的刻意設計，並列為 Schema v3 候選索引。 |
| 6 | **`BackupService` 媒體準備迴圈縮排修正** | **VERIFIED** | [`BackupService.kt:225-239`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L225-L239) | `try` 區塊內的 `for (media in s.media)` 由原先的 8 格縮排修正為符合規範的 12 格縮排。 |
| 7 | **版本號升級至 3 與 Fastlane Changelog 重命名** | **VERIFIED** | [`app/build.gradle.kts:50`](../../../app/build.gradle.kts#L50)<br>[`fastlane/.../3.txt`](../../../fastlane/metadata/android/zh-TW/changelogs/3.txt) | `versionCode` 升至 3，`en-US` 與 `zh-TW` 下的 `2.txt` 已以 git rename 方式更名為 `3.txt`。 |

---

## 2. Flow 鏈路深入驗證 (Flow Chain Verification)

針對 brief 所要求的 Flow 鏈路行為重點審查：

```kotlin
// feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt
private var last = AnalyticsUiState()

@OptIn(kotlinx.coroutines.FlowPreview::class)
val state: StateFlow<AnalyticsUiState> = combine(selection, vaultChanges(inbox)) { s, _ -> s }
    .transformLatest { s ->
        // A period switch shows a loading state at once and cancels the previous computation.
        emit(last.copy(loading = true, selection = s))
        emit(compute(s))
    }
    .onEach { last = it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

@OptIn(kotlinx.coroutines.FlowPreview::class)
private fun vaultChanges(inbox: InboxRepository): Flow<Any?> {
    val counts = inbox.observeCounts().catch { }.distinctUntilChanged()
    return merge(counts.take(1), counts.drop(1).sample(400))
}
```

1. **首筆狀態是否立即抵達？ (Yes)**
   - `selection` 為帶有初始值的 `MutableStateFlow`。
   - `vaultChanges` 中的 `counts.take(1)` 在收集當下會由 Room 的 [`InboxRepository.observeCounts`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/InboxRepository.kt#L43) 立即發射目前的筆數，隨即結束。
   - `combine` 雙方在訂閱瞬間皆具備初始值，因此會立即發射 `s = PeriodSelection()`，不再受到原本 `debounce(400)` 的 400ms 人為延遲。
2. **切換期間是否先顯示 Loading 再顯示結果？ (Yes)**
   - 當使用者點選期間（呼叫 `setPeriod`），`selection` 立即發射新值，`combine` 轉送後進入 `transformLatest`。
   - `transformLatest` 第一行執行 `emit(last.copy(loading = true, selection = s))`，UI 端的 [`AnalyticsScreen.kt:140`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L140) 收到 `state.loading = true`，立刻切換至 `LoadingScreen()`。
   - 隨後 `compute(s)` 計算完成，發射 `loading = false` 的全新 `AnalyticsUiState`，畫面顯示報表。
3. **前次計算是否確實被取消？ (Yes)**
   - `transformLatest` 是標準的 switch-map 語意：只要上游發射新的 `s`，前一次啟動的協程區塊會被立即 Cancel。當使用者連續快速點選切換期間時，正在執行的前次計算會被中斷，避免無謂消耗。

---

## 3. 新發現問題清單 (New Findings)

### Critical Findings (0 項)
無。無資料遺失、無死鎖、無安全漏洞。

---

### Important Findings (2 項)

#### I-1. 移除 `.flowOn(Dispatchers.Default)` 後，重度計算回退至 Main Thread 執行
- **位置**：[`AnalyticsViewModel.kt:91-99`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L91-L99)
- **分析**：
  在 Round 5 的代碼中，Flow 鏈帶有 `.flowOn(Dispatchers.Default)`。在本 commit 改用 `transformLatest` 時，該調度器被移除。
  由於 `stateIn(viewModelScope, ...)` 預設綁定於 Android 的 `Dispatchers.Main.immediate`，因此 `transformLatest` 區塊內的 `compute(s)` 會直接在**主線程 (Main Thread)** 執行。
  雖然 [`analytics.messagesBetween`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/AnalyticsRepository.kt#L47) 等資料庫存取由 Room 的內部後台線程池處理，但隨後的 CPU 密集運算：
  - [`ActivityAnalytics.catchphrases`](../../../core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/ActivityAnalytics.kt#L293)（對多達 50,000 筆訊息進行 CJK n-gram 掃描與排序）
  - [`ActivityAnalytics.emojiRanking`](../../../core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/ActivityAnalytics.kt#L320)（掃描 50,000 筆訊息的 Unicode Emoji 簇）
  - 各種排行榜與時段分析
  全部都在主線程同步執行。這直接違反了代碼本體在第 83–85 行的 KDoc 承諾（*"catchphrase scanning over 'All' is real work, so the whole pipeline runs off the main thread"*），在訊息量較大的裝置上切換至「全部」時容易引發掉幀甚至 ANR。
- **建議修正**：
  在 [`AnalyticsViewModel.compute`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L110) 內部包裹 `withContext(Dispatchers.Default)`，確保所有純 CPU 分析都在背景線程完成，且維持 `last` 在主線程更新的執行緒安全性：
  ```kotlin
  private suspend fun compute(s: PeriodSelection): AnalyticsUiState = withContext(Dispatchers.Default) {
      val zone = TimeZone.currentSystemDefault()
      // ... 原有計算邏輯 ...
  }
  ```

#### I-2. 背景 Vault 變更會觸發全螢幕 `LoadingScreen` 閃爍
- **位置**：[`AnalyticsViewModel.kt:92-96`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L92-L96)、[`AnalyticsScreen.kt:140`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L140)
- **分析**：
  在 `combine(selection, vaultChanges(inbox)) { s, _ -> s }` 中，當背景有新通知寫入資料庫時，`vaultChanges` 經過 400ms 採樣後發射新訊號，導致 `combine` 重新發射當前期間 `s`。
  `transformLatest` 會無條件執行：
  ```kotlin
  emit(last.copy(loading = true, selection = s))
  emit(compute(s))
  ```
  這使得使用者正在瀏覽統計數據時，一旦背景進來新訊息，畫面會立刻切換成 `LoadingScreen()`（全螢幕進度條），計算完成後再切回內容，形成突兀的畫面閃爍。
- **建議修正**：
  僅在 `selection` 發生實質變更（或首次載入無報表）時發射 `loading = true`，資料庫背景更新時則在後台靜默重算並直接替換成果：
  ```kotlin
  .transformLatest { s ->
      if (s != last.selection || last.report == null) {
          emit(last.copy(loading = true, selection = s))
      }
      emit(compute(s))
  }
  ```

---

### Minor Findings (2 項)

#### M-1. `compute()` 中的 `runCatching` 捕獲了 `CancellationException`
- **位置**：[`AnalyticsViewModel.kt:115, 117, 121, 151`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L115)
- **分析**：
  `runCatching { analytics.messagesBetween(...) }.getOrDefault(emptyList())` 會捕捉到協程被取消時拋出的 `CancellationException`。當 `transformLatest` 中斷先前的計算時，`compute()` 不會在此時立即中斷退出，而是得到 `emptyList()` 後繼續往下跑完 CPU 計算，直到調用 `emit(compute(s))` 時才由 FlowCollector 檢查取消狀態拋出異常。
  這不會導致過期數據外洩（因為 `emit` 會阻擋），但會在取消發生時無謂浪費短暫的 CPU 資源。
- **建議**：
  未來可改用專案已有的協程防護擴充（或在 catch 區塊中 `if (e is CancellationException) throw e`）。

#### M-2. `Daos.kt` 中的 KDoc 註解與實際呼叫端有些微語意落差
- **位置**：[`Daos.kt:230-231`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L230-L231)
- **分析**：
  註解中寫道 `(the caller debounces recomputation)`。呼叫端在本次 commit 中已將 `debounce(400)` 重構為 `merge(take(1), drop(1).sample(400))`（定時採樣而非防抖）。此為文件小瑕疵，不影響邏輯。

---

## 4. 文件與設定檔一致性核對 (Documentation & Configuration)

- [`CHANGELOG.md:16`](../../../CHANGELOG.md#L16)：
  精確更新為 `analytics load at most 50,000 messages per period (the UI says so when capped) and recompute at most once per 400 ms while the vault keeps changing (period switches recompute at once);`，與實際實作完全契合。
- [`docs/SCOPE.md:21`](../../../docs/SCOPE.md#L21)：
  `at most 50,000 messages per period are loaded (the page says when it capped)`，在 `AnalyticsScreen` 補上渲染後已由「不實陳述」轉為「真實陳述」。
- [`docs/TEST_MATRIX.md:16`](../../../docs/TEST_MATRIX.md#L16) 與 [`docs/zh-Hant/TEST_MATRIX.md:16`](../../../docs/zh-Hant/TEST_MATRIX.md#L16)：
  中英文測試矩陣同步更新了 `VaultRoundTripTest` 針對已刪除對話重播不復活的測試描述。
- [`app/build.gradle.kts:50`](../../../app/build.gradle.kts#L50) & [`fastlane/metadata/android/`](../../../fastlane/metadata/android/)：
  `versionCode = 3` 與 `changelogs/3.txt` 嚴格對應，無遺留孤立檔案。

---

## 5. 建議修復程式碼 (Actionable Diff)

在正式推送發布前，建議將 [`AnalyticsViewModel.kt`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt) 進行如下調整：

```kotlin
// feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt
import kotlinx.coroutines.withContext

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val state: StateFlow<AnalyticsUiState> = combine(selection, vaultChanges(inbox)) { s, _ -> s }
        .transformLatest { s ->
            // 僅在選取期間變更或尚無資料時呈現全螢幕 loading，避免背景收到通知時畫面閃爍
            if (s != last.selection || last.report == null) {
                emit(last.copy(loading = true, selection = s))
            }
            emit(compute(s))
        }
        .onEach { last = it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    // 確保 heavy CPU 計算運行於 Dispatchers.Default，避免卡頓主線程
    private suspend fun compute(s: PeriodSelection): AnalyticsUiState = withContext(Dispatchers.Default) {
        val zone = TimeZone.currentSystemDefault()
        // ... 原有計算邏輯保持不變 ...
    }
```
