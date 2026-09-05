# Round 8 確認審查（Claude Opus subagent，唯讀）— QuietInbox

**審查對象**：commit `b4e5639`（`ad5ee31..b4e5639`，單一 commit「fix: round-7 review findings」）。`652aa69` 只把 review index 的「follow-up commit」欄位填成 `b4e5639`，已核對。
**審查時間**：2026-09-06
**方法**：`git show --stat` / `git diff` 逐行閱讀、讀取 `AnalyticsViewModel.kt`／`AnalyticsScreen.kt`／`AnalyticsViewModelTest.kt`／`DatabaseHolder.kt`／`VaultRepository.kt`／`InboxRepository.kt` 現況、對照 `docs/reviews/2026-09-06-round7/` 兩份原始報告，並**實際執行** `./gradlew :feature:analytics:testDebugUnitTest --rerun-tasks`。
**實測結果**：`b4e5639` 版本 6 個測試全綠（`tests="7"` 是後述漂移版；`b4e5639` 版為 6，各測試耗時 0.015–0.814 s）。`core:analytics` 的測試結果 XML 為 4 + 28 = **32**，與 `docs/SCOPE.md:21` 的宣稱相符。
**未執行**：任何裝置／模擬器操作、instrumented 測試、任何會改變版本庫狀態的 git 操作。

---

## Verdict

# APPROVE WITH MINOR FIXES

**一句話理由**：round-7 的兩項 Important（I-1、I-2）與四項 Minor（M-1／M-2／M-5／M-6、M-7／M-3）都**確實修好了**，而且是從程式碼路徑逐條確認，不是表面比對；新增的 6 個測試不是走過場，其中至少三個會在對應的迴歸下真的失敗。唯一實質新發現是 `vaultSignals()` 把 `Ready` 從 vault 狀態流濾掉，使得「解鎖後的復原」必須依賴一次**計數變動**——這在生產環境的可達路徑很窄（見 Minor-1），但同一個 commit 的測試恰好用「解鎖同時改計數」把它遮住了，所以測試名稱比它實際證明的更強。

**重要更新（審查進行中發生）**：我寫報告期間工作區被其他 agent 修改，隨後提交為 `1249be5`「fix: round-8 review finding (unlocking recovers the activity page without a count change)」。該 commit **正是** Minor-1 的修法（把 `vault.state` 改成外層 `flatMapLatest` 訊號），並補上第 7 個測試「a vault that locks while the page is open recovers when unlocked, even without a count change」。我已在 HEAD 上重跑：**7 個測試全綠**。詳見「版本庫漂移」一節。

---

## Round-7 findings status table

| 發現 | 來源 | 是否修好 | 證據（`b4e5639` 的行號） |
|---|---|---|---|
| **I-1** 切換週期的載入佔位帶著上一期間的 `capped` 標籤（與報表、標題列 subtitle） | subagent | ✅ **已修** | `AnalyticsViewModel.kt:121-122` 改為 `emit(AnalyticsUiState(loading = true, selection = s).also { last = it })`——全新物件，`capped = false`、`report = null`，不再 `last.copy(...)`。畫面端 `AnalyticsScreen.kt:142-150` 的 capped 區塊讀 `state.capped`，切換期間為 false；`AnalyticsScreen.kt:102-115` 的標題列 subtitle 只判斷 `report != null`，因為佔位的 `report` 已是 null，round-7 附帶指出的「subtitle 顯示上一期間數字」也一併消失。測試 `AnalyticsViewModelTest.kt:98-116` 用 `gate` 卡住查詢，明確斷言 `placeholder.capped.shouldBeFalse()` 與 `placeholder.report.shouldBeNull()`。 |
| **I-2** vault `Locked`／`Opening` 時分析頁永久轉圈 | subagent | ✅ **主要路徑已修**（復原路徑見 Minor-1） | `AnalyticsViewModel.kt:244-255` 新增 `vaultSignals()`；`:254` 把 `vault.state.filter { it !is Ready }` 併進訊號流，讓 `combine` 至少能發射一次。`:111` Locked → `vaultLocked = true, loading = false`；`:113` Opening → 維持 loading。畫面端 `AnalyticsScreen.kt:151-157` 用 `EmptyState` 呈現，且擺在 loading gate **之前**。字串兩個語系都有：`values/strings.xml:317` `vault_locked_title`／`:168` `health_vault_locked`，`values-b+zh+Hant/strings.xml` 同名同行。 |
| **M-1** `compute()` 5 個查詢中 2 個吞掉 `CancellationException` | 兩份都提 | ✅ **已修（5/5）** | `AnalyticsViewModel.kt:207-213` 的 `orDefault` helper 先 `catch (e: CancellationException) { throw e }` 再 `catch (e: Throwable) { default }`。5 個站點全部改用：`:141`（`messagesBetween`）、`:142`（`observeGaps`）、`:146`（`summaryCountBetween`）、`:183`（`labels`）、`:221`（`earliestTimestamp`）。helper 宣告為 `inline`，所以 lambda 內的 suspend 呼叫合法。 |
| **M-2** `catch { emit(...) }` 之後 counts 流永久結束，不會自我復原 | 兩份都提 | ✅ **已修** | `AnalyticsViewModel.kt:246-250` 改為 `retryWhen { _, attempt -> emit(InboxCounts(0,0,0,0)); delay((1_000L shl minOf(attempt,5L).toInt()).coerceAtMost(30_000L)); true }`——先發 fallback tick 讓畫面脫離 loading，再退避重試，上游不會終止。退避序列 1s／2s／4s／8s／16s／30s，**不會 busy-loop**。測試 `AnalyticsViewModelTest.kt:157-161` 用 `flow { throw IllegalStateException("db") }` 覆蓋。 |
| **M-5** `last` 是無同步保護的可變欄位，且寫在 consumer 端 | subagent | ✅ **已修** | round-7 的正確論點是：舊寫法 `onEach { last = it }` 跑在 channel 的 **consumer** 協程，`cancelAndJoin()` 只排序 producer，因此無 happens-before。現在 `:111`、`:113`、`:121`、`:122` 的讀寫**全部**移進 `transformLatest` 區塊（producer 端）。`ChannelFlowTransformLatest` 在啟動新區塊前對前一個 `Job` 呼叫 `cancelAndJoin()`，join 提供 happens-before 邊，所以同一時間只有一個協程碰 `last`，且看得到前一個的寫入。程式碼註解 `:120-121` 也把這個理由寫下來了。 |
| **M-6** 純 CPU 階段沒有取消檢查，切換週期會被餓死 | subagent | ✅ **已修** | `AnalyticsViewModel.kt:149`、`:159`、`:168`、`:171` 四處 `coroutineContext.ensureActive()`，把最重的 `heatmap` + `catchphrases`（`:169-170`）夾在中間。import 正確：`kotlinx.coroutines.ensureActive`（`:27`）＋ `kotlin.coroutines.coroutineContext`（`:49`）。殘留缺口見 Minor-6。 |
| **M-7 / M-3** 沒有任何 `AnalyticsViewModel` 測試 | 兩份都提 | ✅ **已修** | 新增 `AnalyticsViewModelTest.kt`（6 個測試），`feature/analytics/build.gradle.kts:15-18` 補上 `kotest-runner-junit5` / `mockk` / `kotlinx-coroutines-test` / `:core:testing` 與 `useJUnitPlatform()`。我實跑全綠。 |
| **M-3（subagent）** CHANGELOG 誇大「an error no longer leaves the page loading forever」 | subagent | ✅ **已修** | `CHANGELOG.md:11` 改成範圍精確的「a failing vault-count query emits a fallback tick and retries with back-off instead of silently ending the recompute ticks」，並另外分句描述 locked／opening。沒有再宣稱超出程式碼的涵蓋範圍。 |
| **M-4（subagent）／M-2（agy）** 未使用的 import 與亂序 import | 兩份都提 | ⚠️ **半修** | 未使用的 import 已清乾淨（我逐一比對 53 行 import，無殘留）。但排序仍未整理：`:18-19` 把 `repo.VaultRepository` 排在 `db.VaultState` 之前，`:37-49` 的 flow 群組也非字母序。純美觀。 |

---

## Critical（0 項）

無。這個 commit 不會產生錯誤資料、不會外洩、不觸及權限或加密邊界。

## Important（0 項）

無。下方 Minor-1 是 agy 在同一輪判為 Important 的那一項；我依「生產可達性 × 後果」判為 Minor，理由寫在該條裡。兩份報告指向**同一個修法**，且該修法已在 `1249be5` 落地，實務結論一致。

---

## Minor

### Minor-1. `Locked → Ready` 的復原依賴一次「計數變動」，計數不變時可能不復原
**位置**：`AnalyticsViewModel.kt:254`（`merge(vault.state.filter { it !is VaultState.Ready }, ticks.map { vault.state.value })`）＋ `:251`（`distinctUntilChanged()`）＋ `DatabaseHolder.kt:74-75`（`flowWithDb`）。

**機制**：`filter { it !is Ready }` 讓 vault 轉回 `Ready` 這件事本身**不會**產生任何訊號，唯一能讓 `combine` 再次發射的是一次 count tick。而 `distinctUntilChanged()`（`:251`）位於 `retryWhen` 之下、`shareIn` 之上——它是**單一長生命週期的運算子實例**：`flowWithDb` 是 `_state.flatMapLatest{}`，vault 非 Ready 時只是「靜默不發射、不完成」，所以整條上游收集從不中斷，`distinctUntilChanged` 的 `previous` 會跨過整段鎖定期存活。若復原後的計數與鎖定前**完全相同**，它會把那一筆吃掉，於是沒有 tick、沒有重算。

**具體失敗情境**（生產可達，但窄）：金庫是空的（counts = `0,0,0,0`），使用者停在活動頁 → 設定裡執行「刪除全部」→ `DatabaseHolder.closeAndDeleteFiles()` 把狀態設為 `Opening`（畫面進入 `:113` 的 loading 分支）→ `holder.retry()` 回到 `Ready` → `flowWithDb` 重新發射 `0,0,0,0` → 被 `distinctUntilChanged` 吃掉 → **活動頁永久轉圈**，直到離開畫面超過 5 秒（`WhileSubscribed(5_000)` 讓上游重啟、`distinctUntilChanged` 重置）或有新通知進來。

**為什麼我判 Minor 而非 Important**：我逐條追過 `DatabaseHolder` 的所有寫入點——`Ready → Locked` 的直接轉換在生產中**不可達**（`guard` 這個 `CoroutineExceptionHandler` 只掛在 init 的 `open()`；`retry()` 由呼叫端 scope 執行且 `open()` 內部自行 `catch`）。唯一可達的是 `Ready → Opening → Ready`，只有 `closeAndDeleteFiles()` 會走，而它會把資料刪光——除非金庫**本來就是空的**，否則計數必然改變、tick 必然發生。開機即 `Locked` 再解鎖的常見路徑則完全正常（鎖定期間從未發射過任何計數，`distinctUntilChanged` 沒有前值可比）。

**補充查證後，可達性其實比上述更低**：`vault.deleteEverything()` 唯一的呼叫端是 `feature/settings/.../SettingsViewModel.kt:142`（由 `SettingsScreen.kt:332` 一個要求輸入「DELETE」的確認對話框觸發），`resetAfterKeyFailure()` 則只在 `feature/health/.../HealthViewModel.kt:123`。兩者都在**別的畫面**，使用者必須先離開活動頁；`collectAsStateWithLifecycle` + `WhileSubscribed(5_000)` 會在離開 5 秒後停掉上游，回到活動頁時 `distinctUntilChanged` 已是全新實例。因此這個情境在實際導覽流程下**接近不可達**，比較像理論缺陷而非可觸發的 bug。agy 判 Important；我判 Minor 純粹是可達性差異，修法完全相同，且已在 `1249be5` 落地。

**更值得注意的是測試**：`AnalyticsViewModelTest.kt:142-143` 在解鎖的**同一時間**把計數從 `(1,1,0,0)` 改成 `(2,2,0,0)`，所以名為「recovers once unlocked」的測試根本無法偵測這個吞掉。同理 `:151-152` 的 opening 測試。這是「測試名稱強過它實際證明的內容」的典型。

**修法**：不要把 `Ready` 濾掉——直接 `merge(vault.state, ticks.map { ... })`（`StateFlow` 本身按相等性去重），或以 vault 狀態作為外層 `flatMapLatest` 訊號。**`1249be5` 採用了後者，並補了會在舊實作下失敗的第 7 個測試。**

### Minor-2. `ticks.map { vault.state.value }` 讀的是 map 執行當下的狀態，與 merge 的送達順序不同步
**位置**：`AnalyticsViewModel.kt:254`。

**機制**：tick 的 payload 是**現場重讀**的 `vault.state.value`，但這次讀取與下游 `transformLatest` 收到它之間隔著 `combine`／channel。若在這個空隙裡 vault 轉為非 Ready，`filter` 分支的 `Locked` 可能先送達、被重讀為 `Ready` 的舊 tick 後送達。此時 `transformLatest` 會對一個已關閉的 db 執行 `compute()`：五個查詢全部拋 `VaultUnavailableException`，被 `orDefault` 降級成空集合，畫面因此顯示一份 **`loading = false`、`vaultLocked = false` 的空報表**，而不是「資料庫已鎖定」。因為 `vault.state` 是 `StateFlow`、不會重播同值，也不會再有 tick，這個錯誤畫面在下一次 `Ready` tick 之前不會被更正。

**視窗極小**，且我在 Minor-1 已論證 `Ready → Locked` 生產不可達，所以實務影響接近零。`1249be5` 用 `.map { v }`（沿用外層 `flatMapLatest` 已固定的值）從結構上消除了這個重讀。

### Minor-3. `retryWhen` 的 `attempt` 計數器不會在成功後重置
**位置**：`AnalyticsViewModel.kt:246-249`。

kotlinx-coroutines 1.11.0 的 `retryWhen` 在整段收集期間維持**累計**的 `attempt`，成功並不會歸零。因此一個長時間執行的 session 裡若累積過 6 次零星的暫時性失敗（彼此相隔數分鐘），第 7 次失敗就要等滿 30 秒才重新掛上 counts 流，畫面在那段時間停在最後一份報表上、不會更新。建議在成功發射後重置（例如把重試邏輯包成 `attempt` 可歸零的自訂運算子），或直接把上限壓到 5 秒。

### Minor-4. 查詢失敗被靜默降級成「零報表」，與專案的誠實標籤原則有張力（既有問題，非本 commit 引入）
**位置**：`AnalyticsViewModel.kt:207-213`。

`orDefault` 把資料庫錯誤變成 `emptyList()` / `0`，畫面接著把它當成一份正常報表渲染——使用者看到的「這段期間沒有訊息」與「查詢失敗」**在畫面上完全無法區分**。`CLAUDE.md` 的硬性規則寫著「gaps are shown, never hidden」與「Honest data-quality labels」。建議在 `AnalyticsUiState` 加一個 `degraded: Boolean`，在任一 `orDefault` 走到 default 分支時設起來，畫面比照 `capped` 顯示一行說明。另外 `catch (e: Throwable)` 連 `OutOfMemoryError` 這類 `Error` 也一併吞掉，建議收斂成 `catch (e: Exception)`。

### Minor-5. 「安靜重算」測試的斷言可能被 `StateFlow` 的合併（conflation）悄悄放過
**位置**：`AnalyticsViewModelTest.kt:118-134`。

`seen` 是透過 `h.vm.state`（一個 `StateFlow`）收集的，`StateFlow` 會合併中間值。若有人把 `:121` 的守衛拿掉、讓每次 vault 變動都發射 `loading = true` 佔位，那個佔位很可能在收集端被合併掉，測試**依然會通過**。這是「不會誤報失敗，但也抓不到迴歸」的弱斷言。建議比照 `:98-116` 的做法用 `gate` 把重算的查詢卡住，讓佔位狀態必然可觀察，或改為斷言 `stateIn` 之前的流。

### Minor-6. `compute()` 中間四個 CPU 階段之間沒有取消檢查
**位置**：`AnalyticsViewModel.kt:160-167`。

`rankings`、`bestTime`、`chattiness`、`quiet` 四個連續純 CPU 階段之間沒有 `ensureActive()`。在「全部」期間、50,000 筆訊息的最壞情況下，切換週期仍要等這四段跑完才會解開。最重的 `catchphrases` 已被 `:168`／`:171` 夾住，所以這只是殘留缺口，不是遺漏。

### Minor-7. 測試的執行緒斷言依賴 kotlinx-coroutines 的排程器命名
**位置**：`AnalyticsViewModelTest.kt:95`：`h.queryThreads.first() shouldStartWith "DefaultDispatcher-worker"`。

這個名字由 `CoroutineScheduler` 產生，**與作業系統／JDK 無關**，所以 Linux + JDK 17 的 CI 不會因此壞掉——brief 問的那個具體風險我確認**不存在**。但只要有人設 `-Dkotlinx.coroutines.scheduler=off`，或未來把 `Dispatchers.Default` 換成注入的 dispatcher，斷言就會無故失敗。斷言「不等於收集端執行緒」語意更準也更穩。**`1249be5` 已改成 `shouldNotBe testThread`。**

### Minor-8. `Harness` 的 `observeCounts()` mock 與 `flowWithDb` 的真實行為不符
**位置**：`AnalyticsViewModelTest.kt:81`。

mock 直接回傳 `MutableStateFlow`，在 vault 為 `Locked`／`Opening` 時**仍會發射**；真實的 `flowWithDb`（`DatabaseHolder.kt:74-75`）在非 `Ready` 時是靜默不發射的。因此 locked／opening 兩個測試餵給 `vaultSignals()` 的訊號形狀在生產中不可能出現，測試對這兩個狀態的保護力低於表面。**`1249be5` 已讓 harness 用 `flatMapLatest` 鏡像 `flowWithDb`。**

### Minor-9. 測試沒有清理 ViewModel，背景重試會活過整個 spec
沒有任何測試呼叫 `onCleared()` 或取消 `viewModelScope`，`shareIn`／`stateIn` 的上游會留到 spec 結束。`:157-161` 那個失敗查詢的測試更會留下一個退避越來越長的重試迴圈。今天無害（`WhileSubscribed(5_000)` 在最後一個訂閱者離開 5 秒後就停），但若日後有測試共用 harness，殘留就會互相干擾。

### Minor-10. `merge(counts.take(1), counts.drop(1).sample(400))` 可能多送一次 tick
`take(1)` 與 `drop(1)` 是對同一個 `SharedFlow` 的**兩個獨立訂閱**。兩者訂閱時機不同步時，可能一個發射 v1、另一個丟掉 v1 之後又發射 v1（來自 replay），造成一次多餘的重算。後果良性（只是多算一次），列此僅為記錄。

### Minor-11. import 排序（round-7 M-4 的後半）
`AnalyticsViewModel.kt:18-24` 仍把 `repo.VaultRepository` 排在 `db.VaultState` 之前，`:37-49` 的 flow 群組也非字母序。純美觀，HEAD 亦同。

---

## Flow 鏈路：逐題回答 brief 的疑問（針對 `b4e5639`）

1. **首次發射會遺失嗎？** 不會。`combine` 的兩路：`selection` 是 `StateFlow`，立刻滿足；`vaultSignals()` 在 vault 為 `Locked`／`Opening` 時由 `:254` 的 filter 分支立刻滿足，在 `Ready` 時由第一筆 count tick 滿足。在 tick 到達前，`stateIn` 的初始值 `AnalyticsUiState()`（`loading = true`）讓畫面正確顯示 `LoadingScreen`。
2. **`Locked → Ready` 復原會失敗嗎？** 會，在一個窄條件下——見 Minor-1。開機即鎖定再解鎖的主要路徑正常。
3. **`retryWhen` 會 busy-loop 或餓死下游嗎？** 不會 busy-loop（第一次重試就 `delay(1_000)`，最高 30 s）。不會餓死：`sample(400)` 會在下一個 400 ms 邊界把待決值送出，不會因為後面沒有新值就永久扣住。唯一的行為瑕疵是 `attempt` 不重置（Minor-3）。
4. **`ticks.map { vault.state.value }` 會與狀態變更競態嗎？** 會，視窗極小且生產近乎不可達——見 Minor-2。
5. **`last` 的記帳現在無競態嗎？** 是。讀寫全部搬進 `transformLatest` 區塊（producer 端），`cancelAndJoin()` 提供排序與 happens-before。round-7 指出的 consumer/producer 跨執行緒問題已消失。殘留脆弱性：`last` 仍是普通 `var`，正確性依賴一個不明顯的 `transformLatest` 內部細節，`:120-121` 的註解有把理由寫下來，可接受。
6. **`.also { last = it }` 在 Locked／Opening 分支上行為正確嗎？** 正確。`.also` 作用在 `AnalyticsUiState` 上、在 `emit` **之前**求值，所以寫入的一定是即將發射的那個物件。唯一的理論瑕疵：若 `emit` 在 channel 滿時掛起並隨即被取消，`last` 會記錄一個從未送達的狀態，下一輪因此少發一次佔位。需要 channel 塞滿 64 筆才可能，實務不可達。
7. **`WhileSubscribed` 重啟行為？** 正確。離開超過 5 秒後回來：`stateIn` 保留最後一個值，`counts` 的 replay 快取（`replayExpirationMillis` 預設無限）讓 `take(1)` 立刻取得一筆 tick，而 `s == last.selection && last.report != null` 使 `:121` 不發射佔位——回到畫面看到的是舊報表原地靜默更新，不是全螢幕轉圈。這是正確的行為。附帶觀察：若離開前的最後狀態是 `vaultLocked`，回來的頭幾毫秒會先重播「資料庫已鎖定」再被第一筆 tick 蓋掉，是一次很短的畫面閃動，`b4e5639` 與 HEAD 皆然，純美觀。

---

## 文件核對

| 項目 | 結果 |
|---|---|
| `CHANGELOG.md` `[Unreleased]` | ✅ Added/Changed 兩段與程式碼一致；round-7 M-3 指出的誇大措辭已收斂為精確範圍。`:27` 的「157 JVM tests」屬於已發布的 `[0.1.0]` 區段，維持歷史值正確；commit message 的 163 = 157 + 6，純算術。 |
| `docs/SCOPE.md:21` | ✅ 「32 JVM tests in `core:analytics` plus 6 in `AnalyticsViewModelTest`」——32 我從測試結果 XML 實測（4 + 28），6 亦實測。無 `docs/zh-Hant/SCOPE.md`，故無對照缺口。 |
| `docs/TEST_MATRIX.md:21` + `:76-79`、`docs/zh-Hant/TEST_MATRIX.md:21` + `:65-68` | ✅ 新增列與「Not covered yet／尚未涵蓋」段落兩個語系一字對應，且誠實聲明「locked-vault 狀態尚未在裝置上走過」。小瑕疵：新列的執行欄寫「JVM」，其他每一列都給完整 gradle 指令，建議補成 `./gradlew :feature:analytics:testDebugUnitTest`。 |
| `docs/reviews/README.md:18`、`docs/zh-Hant/reviews/README.md:16` | ✅ round 7 那列內容與兩份原始報告相符；`652aa69` 把「follow-up commit」填成 `b4e5639`，兩語系同步。round-7 的三份報告（agy、subagent、kimi-blocked）與 brief 都已逐字歸檔。 |
| `gradle/verification-metadata.xml` | ✅ 非問題。新增的三個測試相依 `io.mockk`／`kotlinx-coroutines-test`／`kotest-runner-junit5` 都已列於 metadata（分別出現 10／7／5 次），且 `platform/capture/build.gradle.kts:24-26` 早已引入其中兩個，冷快取 CI 不會解析到未列出的 artifact。 |

**「Docs must not run ahead of the code」**：本輪未發現任何超前宣稱。

---

## 版本庫漂移（審查進行中發生，已提交為 `1249be5`）

我開始審查時 `git status` 只有一個未追蹤的 `docs/reviews/2026-09-06-round8/`；寫報告途中 `AnalyticsViewModel.kt` 與 `AnalyticsViewModelTest.kt` 被修改，隨後提交為 `1249be5`。這與 round 6、round 7 是同一個模式。本報告的 Verdict 與行號**一律針對 `b4e5639`**。

`1249be5` 的改動與我的發現對應如下（我已逐行閱讀並實跑）：

- `vaultSignals()` 改為 `vault.state.flatMapLatest { v -> if (v !is Ready) flowOf(v) else merge(counts.take(1), counts.drop(1).sample(400)).map { v } }` → **解掉 Minor-1**：每次轉為 `Ready` 都會開一條新的內層訂閱，`shareIn(replay = 1)` 的重播讓第一筆 tick 立刻到達，計數不變也能復原；同時 **解掉 Minor-2**：`.map { v }` 沿用外層已固定的值，不再重讀 `vault.state.value`。
- 測試 harness 改用 `flatMapLatest` 鏡像 `flowWithDb` → **解掉 Minor-8**。
- 執行緒斷言改為 `shouldNotBe testThread` → **解掉 Minor-7**。
- 新增第 7 個測試「a vault that locks while the page is open recovers when unlocked, even without a count change」，commit message 聲明它在舊實作下會失敗。
- `.github/workflows/ci.yml` 的 JVM job 補上 `:platform:capture` 與 `:feature:analytics`——重要，否則新測試不會在 CI 跑到。
- `SCOPE`／`TEST_MATRIX`（en + zh-Hant）的計數由 6 改為 7，兩語系同步。

**我在 HEAD（`1249be5`）實跑 `./gradlew :feature:analytics:testDebugUnitTest --rerun-tasks`：`tests="7" skipped="0" failures="0" errors="0"`，BUILD SUCCESSFUL。**

Minor-3、4、5、6、9、10、11 在 HEAD 上**仍然成立**，全部可以留到下一輪或直接忽略。

---

## 其他觀察

- `orDefault` 宣告為 `inline`，所以 lambda 內的 suspend 呼叫可以合法內聯進 `compute()` —— 這是個乾淨的解法，值得保留。
- `feature/analytics/build.gradle.kts:20-26` 明確關掉 Kotest 的 classpath autoscan 並註明「本模組每個測試都是 Kotest spec，不需要 JUnit4 vintage engine」，理由寫得比大多數模組清楚。
- `AnalyticsScreen.kt` 的三段 gate 順序正確：capped → vaultLocked → loading。`vaultLocked` 狀態是全新物件、`capped = false`，所以不會出現「鎖定中還宣告資料被截斷」的組合。
- `1249be5` 把 `feature:analytics` 加進 CI 之前，這 6 個測試在 CI 上其實**一次都沒跑過**（`ci.yml` 的 JVM job 原本只列 core / parsers / crypto / backup / app）。這點 `b4e5639` 自己沒有處理，是它最實質的疏漏之一，已由後續 commit 補上。

---

## 未涵蓋範圍（誠實聲明）

- 沒有任何裝置／模擬器操作。`vaultLocked` 的 `EmptyState` 版面、Minor-1 的轉圈情境都是從程式碼路徑推導，未在裝置上錄影確認。`docs/TEST_MATRIX.md:76-79` 自己也這樣寫。
- 沒有跑 instrumented 測試、沒有跑全模組 `./gradlew test`，只跑了 `:feature:analytics:testDebugUnitTest`。commit message 宣稱的「163 JVM tests green」我**未驗證**。
- 沒有做任何會改變版本庫狀態的 git 操作。
- 沒有審查 round-8 的 agy 報告內容再據以調整判斷；我讀了它的標題結構以確認涵蓋範圍是否重疊，發現我們獨立收斂到同一項主要發現（agy 判 Important，我判 Minor，修法相同），另有本報告的 Minor-3／4／5／6／9／10 是 agy 未列出的。

---

## 修正優先序（針對 HEAD `1249be5`）

1. **（Minor）** Minor-5：把「安靜重算」測試改成用 `gate` 卡住查詢，否則它抓不到守衛被移除的迴歸。這是目前**唯一一個會讓人誤以為有保護、實際沒有**的地方。
2. **（Minor）** Minor-4：`AnalyticsUiState` 加 `degraded` 旗標，讓查詢失敗與「真的沒訊息」在畫面上可區分——這條與 `CLAUDE.md` 的誠實原則直接相關。
3. **（Minor）** Minor-3：`retryWhen` 的 `attempt` 成功後歸零，或把退避上限壓到 5 秒。
4. **（美觀）** Minor-6 補 `ensureActive()`、Minor-9 測試收尾、Minor-11 import 排序、TEST_MATRIX 執行欄補完整指令。
