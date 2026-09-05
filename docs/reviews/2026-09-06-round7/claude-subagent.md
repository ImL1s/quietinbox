# Round 7 確認審查（Claude Opus subagent，唯讀）— QuietInbox

**審查對象**：commit `e5ad1a3`（`a626b32..e5ad1a3`，單一 commit：「fix: round-6 review findings」）
**審查時間**：2026-09-06
**方法**：`git show --stat` / `git diff` 逐行閱讀 + 讀取相關檔案現況 + 對照 `docs/reviews/2026-09-06-round6/` 的原始發現。
**未執行**：任何 Gradle 建置或測試、任何裝置／模擬器操作、任何會改變版本庫狀態的 git 操作。原因見下方「未涵蓋範圍」。

---

## Verdict

# REQUEST CHANGES

**理由（一句話）**：round-6 的兩項 Important 都**確實修好了**，但同一個 commit 在把 capped 提示搬到 loading gate 之上時，引入了一個新的 Important——**切換週期的載入期間，畫面會對「尚未計算完成的新週期」顯示上一個週期的截斷警語**。這是一個假的資料品質標籤，而 `CLAUDE.md` 把「Honest data-quality labels」列為不可交換的硬性產品規則。

修法是單行改動（兩種擇一，見 I-1）。除此之外整個 commit 品質良好；coroutine 的部分沒有任何會產生錯誤資料的缺陷（唯一的競態見狀態表 J，後果良性）。

**重要前提**：`AnalyticsViewModel.kt` 在我審查期間**又被工作區的其他 agent 修改了**（與 round 6 完全相同的情況）。本報告一律針對 **commit `e5ad1a3`**；工作區未提交的版本已經修掉我下方的 M-1 與 M-4，詳見「工作區漂移」一節。

| 檔案 | commit `e5ad1a3` blob | 目前工作區 blob |
|---|---|---|
| `AnalyticsViewModel.kt` | `64d6afe` | `03fc61d`（**已漂移**） |
| `AnalyticsScreen.kt` | `6ce8f7c` | `599516b`（**報告寫完後才漂移**） |

---

## Round-6 findings status table

| Round-6 發現 | 嚴重度 | 是否修好 | 證據 |
|---|---|---|---|
| **A. `flowOn(Dispatchers.Default)` 被刪除，分析管線跑在 main thread** | Critical(subagent) / Important(agy) | ✅ **已修** | `AnalyticsViewModel.kt:102` 新增 `.flowOn(Dispatchers.Default)`，位置在 `.onEach{}` 之後、`.stateIn(...)` 之前。`flowOn` 影響其上游全部運算子，故 `combine` → `transformLatest` → `compute()` → `onEach` 全部在 `Dispatchers.Default` 執行；`stateIn` 的收集端仍在 `viewModelScope`（Main.immediate）。與屬性上方 KDoc「the whole pipeline runs off the main thread」重新一致。 |
| **B. 每次 vault 變動都閃全螢幕 loading** | Important（兩位審查者） | ✅ **已修** | `AnalyticsViewModel.kt:98` `if (s != last.selection \|\| last.report == null) emit(last.copy(loading = true, selection = s))`。週期未變且已有報表 → 不發射 loading，`compute()` 靜靜地在背景重算，結果直接覆蓋。首次載入（`last.report == null`）仍會顯示 loading，正確。 |
| C. `observeCounts()` 被訂閱兩次 | Minor | ✅ 已修 | `AnalyticsViewModel.kt:200-204` 加了 `.shareIn(viewModelScope, WhileSubscribed(5_000), replay = 1)`，`take(1)` 與 `drop(1)` 現在共用一條上游訂閱。 |
| C'. `catch {}` 讓 counts 流靜默結束 → 卡在 loading | Minor（C 的附帶） | ⚠️ **部分修** | `:201` 改成 `catch { emit(InboxCounts(0,0,0,0)) }`。**只涵蓋「上游拋例外」這一條路徑**；vault 處於 `Locked`／`Opening` 時 `flowWithDb` 是靜默不發射、不拋例外、不完成，`catch` 完全不會觸發，畫面仍然永久卡在 loading。見 I-2。 |
| D. capped 提示只出現在 Overview 一個 tab | Minor | ✅ 已修（但引入 I-1） | `AnalyticsScreen.kt:140-148` 提升到 tab 列下方共用區塊，五個 tab 都看得到；`RangeLine` 內的重複區塊已移除（`:651-658` 刪除）。`docs/SCOPE.md:21` 同步改成「every tab shows a notice when it capped」，措辭與程式碼相符。 |
| E. CHANGELOG 沒有 Round 5 條目 | Minor | ✅ 已修（有一處誇大） | `CHANGELOG.md:16` 新增「Rounds 5–6 review」條目，涵蓋 capped 提示、loading 行為、`committed` guard、`isSelf`、shareIn。誇大處見 M-3。 |
| F. `Daos.kt` KDoc 位置錯誤且用詞過時 | Minor | ✅ 已修 | `Daos.kt:219-224` KDoc 移到 `@Query` 之上（現在 IDE／Dokka 才抓得到），用詞由「the caller debounces recomputation」改為「the caller samples vault changes at 400 ms」，與現行實作相符。事實部分（無以 `sortKey` 起頭的索引）仍然正確。 |
| G. 未使用的 import 與排序 | Minor | ⚠️ **部分修** | `Dispatchers`（:23）與 `flowOn`（:40）因為修好 A 而重新被使用；但 `kotlinx.coroutines.flow.map`（`e5ad1a3` 的 :41）**仍然未使用**（檔內 8 處 `.map` 全是 stdlib 集合 map）。新增的 `InboxCounts`（:19）插在 `ConversationLabel`（:20）之前，仍未照字母序。見 M-4。 |
| H. 取消只是盡力而為（5 個 `runCatching` 中僅 3 個 rethrow） | Minor | ⚠️ **部分修（3/5）** | `:119`、`:121`、`:125` 加了 `.onFailure { if (it is CancellationException) throw it }`；但 `analytics.labels(ids)`（`:155`）與 `analytics.earliestTimestamp()`（`:181`）**仍然吞掉 `CancellationException`**。見 M-1。 |
| I. `SnapshotFactory.isSelf` 沒有回歸測試 | Minor | ❌ 未處理 | `git show --stat e5ad1a3` 顯示本 commit **完全沒有動任何測試檔**。 |
| J. `last` 是無同步保護的可變欄位 | 提示 | ⚠️ **競態存在，但後果良性** | 我原本想主張 `cancelAndJoin()` 建立 happens-before，**這是錯的**：`ChannelFlowTransformLatest`（coroutines 1.11.0，`flow/internal/Merge.kt:9-34`）的 `capacity = Channel.BUFFERED`，且其 `flowCollect` 內含 `assert { collector is SendingCollector }`，代表 `transformLatest` **一定**經過 channel。因此 `emit` 是 producer 端的 `channel.send`，而 `onEach { last = it }` 跑在 **consumer** 協程。`cancelAndJoin()` 只排序 producer 的前後兩個區塊，**不排序 consumer 對 `last` 的寫入**。`last` 又是無 `@Volatile` 的普通 `var`，所以 producer 可能讀到過期的 `last`。**後果良性**：最壞情況是多發一次 `loading = true`，或少做一次「安靜重算」的最佳化；不會產生錯誤資料。脆弱性提示仍成立（見 M-5）。 |

**小結**：brief 指名的兩項（A、B）**確實、完整地修好了**，我用程式碼路徑逐條確認，不是表面比對。

---

## Critical（必須在出貨前修）

**無。**

---

## Important

### I-1. 切換週期的載入期間，畫面對新週期顯示上一個週期的截斷警語（本 commit 引入）

**位置**：`feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt:140-148`（capped 區塊）搭配 `AnalyticsViewModel.kt:98`（loading placeholder）。

**機制**：

```kotlin
// AnalyticsViewModel.kt:98
if (s != last.selection || last.report == null) emit(last.copy(loading = true, selection = s))
```

`last.copy(...)` 只覆寫 `loading` 與 `selection`，**`capped` 原封不動地沿用上一個週期的值**。而 `AnalyticsScreen.kt` 本次把 capped 區塊搬到了 loading gate **之上**：

```kotlin
if (state.capped) {                       // :140-148  ← 新位置，在 gate 之上
    Text(stringResource(R.string.analytics_capped, AnalyticsRepository.MESSAGE_CAP), ...)
}
if (state.loading || report == null) {    // :149
    LoadingScreen(); return@Column
}
```

**失敗情境（可重現）**：

1. vault 內有 8 萬則訊息。使用者選「全部」→ `messages.size == 50_000` → `capped = true` → 畫面顯示「這段期間超過 50000 則觀測訊息；只統計最新的 50000 則。」
2. 使用者切到「最近 7 天」（實際只有 300 則，完全沒有被截斷）。
3. `transformLatest` 發射 `last.copy(loading = true, selection = 最近7天)` → `capped` 仍是 `true`。
4. 畫面：期間列顯示「最近 7 天」、下方**紅色錯誤色**的截斷警語仍在、內容區是轉圈。
5. 這個狀態持續整個 `compute()` 的時間——而在大 vault 上，那正是最久的時候（0.5–數秒；loading 狀態存在的理由就是這個）。
6. `compute()` 完成後 `capped = false`，警語才消失。

**為什麼算 Important 而不是 Minor**：`CLAUDE.md` 的硬性產品規則寫著「Honest data-quality labels ... never trade these away」與「gaps are shown, never hidden」。這裡是反方向：**對一個尚未計算、事實上沒有被截斷的週期，宣告它被截斷了**。`analytics_capped` 用的是 `colorScheme.error`，視覺上是一個明確的資料完整性宣告，不是裝飾。本 commit 之前不會發生——舊的 `RangeLine` 只在 `overviewTab` 內被呼叫，而 `overviewTab` 位於 loading gate **之後**，永遠不會與過期資料同框。

**修法（擇一，皆為單行）**：

```kotlin
// A) ViewModel：placeholder 不得攜帶上一個週期的品質標籤
if (s != last.selection || last.report == null) {
    emit(last.copy(loading = true, selection = s, capped = false))
}
```

```kotlin
// B) Screen：品質標籤只在有對應報表時顯示
if (state.capped && !state.loading && report != null) { ... }
```

建議 **A**，因為 `capped` 是 `AnalyticsUiState` 的欄位，讓它與 `selection` 保持同源比在畫面端加條件更不容易再壞一次。

**同一類別的既有問題（非本 commit 引入，順帶記錄）**：`AnalyticsScreen.kt:101-113` 的 `LargeFlexibleTopAppBar` subtitle 只判斷 `report != null`，所以切換週期的載入期間，標題列會顯示**上一個週期**的「已擷取 / 模糊 / 僅摘要」三個數字，同樣在 gate 之上。若採修法 A，建議一併把 placeholder 的 `report` 也清成 `null`（代價是切換週期時標題列會短暫空白，可接受），或在 subtitle 加 `!state.loading`。

### I-2. vault 處於 `Locked`／`Opening` 時，分析頁永久卡在 loading（既有問題，但正是 brief Q3 的答案）

**位置**：`AnalyticsViewModel.kt:94`（`combine`）＋ `:200-204`（`vaultChanges`）＋ `platform/storage/.../DatabaseHolder.kt:74-75`（`flowWithDb`）。

**機制**：

```kotlin
// DatabaseHolder.kt:74
fun <T> flowWithDb(block: (QuietInboxDatabase) -> Flow<T>): Flow<T> =
    _state.flatMapLatest { s -> if (s is VaultState.Ready) block(s.db) else emptyFlow() }
```

vault 不是 `Ready` 時，`observeCounts()` **靜默不發射、不完成、不拋例外**。所以：

- `:201` 新加的 `catch { emit(InboxCounts(0,0,0,0)) }` **不會被觸發**（沒有例外可捕捉）。
- `vaultChanges(inbox)` 一筆都不發，`combine(selection, vaultChanges)` 依 `combine` 的語意**永不發射**。
- `state` 停留在 `stateIn` 的初始值 `AnalyticsUiState()`，其 `loading = true`、`report = null`。
- `AnalyticsScreen.kt:149-152` → `LoadingScreen()` 無限轉圈，**沒有任何錯誤訊息、沒有重試入口**。

**失敗情境**：Keystore 金鑰無法解封（正是 `DatabaseHolder.kt:37-41` KDoc 明文設計要優雅處理的狀態：「the vault stays Locked and nothing is deleted — recovery is a user decision」）。此時：

- `feature/inbox/.../InboxViewModel.kt:80` 有 `vaultLocked = vaultState is VaultState.Locked` → 收件匣會告知使用者。
- `feature/health/.../HealthViewModel.kt:77` 有 `vaultFailure = (vaultState as? VaultState.Locked)?.failure` → 健康頁會顯示失敗原因。
- `AnalyticsViewModel` **完全沒有讀 `vaultState`**，而 `app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt:130-132` 的 `entry<AnalyticsRoute>` 也**沒有任何 vault 狀態守門**——分析分頁在 vault 鎖住時照樣可以點進去，然後永遠轉圈。

**這一項不是本 commit 引入的**（`a626b32` 與更早都一樣）。我列為 Important 是因為 brief 的第 3 題直接問「can the screen get stuck with `loading = true`?」，誠實的答案是「會，而且是在專案明文設計要優雅處理的那個狀態下」。同時本 commit 的程式碼註解「an error must not leave the page in "loading" forever」與 `CHANGELOG.md:16` 的「an error no longer leaves the page loading forever」都**把涵蓋範圍講得比實際大**。

**修法**：比照 Inbox／Health，讓 `AnalyticsViewModel` 也 `combine` 進 `inbox.vaultState`，`Locked` 時發射一個帶錯誤訊息的非 loading 狀態；或最低限度把 `vaultChanges` 改成 `merge(inbox.vaultState.map { }, counts...)`，讓 `combine` 至少能發射一次。

---

## Minor / nitpicks

### M-1. `compute()` 仍有兩處吞掉 `CancellationException`（round-6 H 只做了 3/5）

- `AnalyticsViewModel.kt:155` `val labels = runCatching { analytics.labels(ids) }.getOrDefault(emptyMap())`
- `AnalyticsViewModel.kt:181` `earliestEpochMs = runCatching { analytics.earliestTimestamp() }.getOrNull()`

`runCatching` 捕捉 `Throwable`，`CancellationException` 也在內。`labels()`（`AnalyticsRepository.kt:34-37`）對每個不重複的會話 id 各跑一次 DAO 查詢，是 `compute()` 裡最後也最貴的 DB 階段；`earliestTimestamp()` 則在「全部」週期的一開始。切換週期造成的取消落在這兩處時不會真的中止運算。

實務影響有上限，但**不是我原先以為的「結果不會落地」**：`SendChannel.send` 的 KDoc（coroutines 1.11.0，`channels/Channel.kt:115`）明文寫著「Note that this function does not check for cancellation when it is not suspended.」——64 格的 buffer 幾乎不會滿，所以被取消的區塊算出來的降級結果（`labels` 為空）**會短暫落地，再被下一個區塊的結果覆蓋**。違反 `CLAUDE.md`「never swallow `CancellationException`」，也與同一個 commit 在其他三處刻意加的 rethrow 不一致。

**這一項在未提交的工作區已經修好**（見下節）。

### M-2. `catch { emit(...) }` 之後 counts 流永久結束，不會自我復原

`AnalyticsViewModel.kt:201`。`catch` 發射 fallback 後上游即**完成**。`shareIn` 的 `SharingStarted.WhileSubscribed` 在 `START` 指令下執行 `upstream.collect(shared)`，上游完成後要等到下一次 `STOP → START` 才會重新訂閱。因此若 DB 錯誤是暫時性的（例如鎖定期間的一次查詢失敗），只要使用者停在分析頁不動，**vault 變動就再也不會觸發重算**，畫面顯示過期報表且不自我更新；必須離開分析頁超過 5 秒再回來才會復活。

比修正前（`catch {}` 靜默結束 → 卡在 loading）嚴格更好，所以不擋出貨。建議改用 `retryWhen { _, _ -> delay(1_000); true }`，或 `onEach`/`catch` 後接 `retry`。

### M-3. `CHANGELOG.md:16` 有一處誇大

「an error no longer leaves the page loading forever」——只有「上游拋例外」這一條路徑成立。vault `Locked`／`Opening` 仍然會永久 loading（I-2）。`CLAUDE.md` 明列「Docs must not run ahead of the code」，建議改為「a failing vault-count query no longer leaves the page loading forever」。

### M-4. `AnalyticsViewModel.kt` 仍有未使用的 import 與亂序 import

`import kotlinx.coroutines.flow.map`（`e5ad1a3` 的 :41）未被使用；`InboxCounts`（:19）排在 `ConversationLabel`（:20）之前。專案沒有 ktlint/detekt/spotless（`.github/workflows/` 只有 `ci.yml`、`release.yml`），所以只是編譯器警告，不會擋建置。**未使用 import 在工作區已移除。**

### M-5. `last` 作為 Flow 狀態載體仍然脆弱（round-6 J 的提示未處理）

`AnalyticsViewModel.kt:91`。競態的後果目前良性（見狀態表 J），但 `last` 記錄的是「最近一次**算出來**的狀態」，不是「UI **正在顯示**的狀態」。`onEach { last = it }` 在值送進 `flowOn` 的 channel **之前**執行，所以一個被取消而從未抵達 `stateIn` 的結果仍會更新 `last`。若日後有人把 `flowOn` 挪到 `onEach` 之前，讀寫就會跨執行緒。建議改用 `scan()` 或 `flow { }` builder 內的區域變數。

### M-6.（衍生自 M-5）被取消的計算仍會送達，切換週期時可能閃一格舊週期的結果（既有問題）

我一開始推測這裡會有 livelock（風暴期間永遠停在轉圈），**查證後推翻了**。決定性證據在專案自己用的 coroutines 1.11.0 原始碼：

- `flow/internal/Merge.kt:9-34`：`ChannelFlowTransformLatest` 的 `capacity = Channel.BUFFERED`，`flowCollect` 內有 `assert { collector is SendingCollector } // So cancellation behaviour is not leaking into the downstream`。
- `channels/Channel.kt:115`：`send` 的 KDoc 明文「Note that this function does not check for cancellation when it is not suspended.」

合起來：transform 區塊的 `emit` 是往 64 格 buffer 的 `channel.send`，幾乎不會 suspend，**所以被取消的區塊做完純 CPU 階段後，結果照樣送達下游**。不會 livelock，轉圈一定會結束。

真正的（很小的）後果是順序性的：

1. 首次載入週期 A，`compute(A)` 正在跑。
2. 使用者在 A 算完之前切到週期 B。
3. `transformLatest` 先 `cancel + join` A 的區塊——A 在純 CPU 階段沒有 suspension point，所以它跑完並**送出 A 的完整結果**（其中 `selection = A`）。
4. 之後才輪到 B 的 placeholder（`loading = true, selection = B`）與 B 的結果。
5. 使用者看到的順序是：轉圈 →（一格）**A 的報表、且期間列選中的是 A** → 轉圈 → B 的報表。

`stateIn` 在 Main 上的 conflation 通常會把這一格吃掉（同一個 frame 內連續 set value），所以多數情況看不到；但這不是保證。**硬化方式**：在 `emit(compute(s))` 之前插入 `currentCoroutineContext().ensureActive()`，讓被取消的區塊不要送出結果。

（順帶更正歸檔紀錄：round-6 報告 H 寫的「`transformLatest` 保證舊區塊的 `emit` 不會落地」與同一個誤解有關，也不成立。這些報告是逐字歸檔的，所以在這裡標註出來。）

### M-7. 本 commit 沒有任何測試

`git show --stat e5ad1a3` 顯示零個測試檔變動。`feature/analytics/src/test/kotlin/` 與 `feature/analytics/src/androidTest/kotlin/` **兩個目錄都是空的**，`AnalyticsViewModel` 在整個版本庫沒有任何測試。`flowOn` 這一項已經被誤刪過一次（round 6 的 Critical），而它是一個純結構性、code review 很容易再次漏掉的東西。

建議補一支 JVM 測試（`kotlinx-coroutines-test` + `Dispatchers.setMain`）釘住三件事：
1. `compute()` 執行的 thread name 不是設定給 `Dispatchers.Main` 的那一條。
2. 週期不變的 vault 變動不會產生 `loading = true` 的中間狀態。
3. 真正的週期切換會產生 `loading = true`，且該狀態的 `capped` 為 `false`（同時鎖住 I-1 的修正）。

---

## 其他觀察

### 逐項驗證 brief 的四個審查面向

**1. 兩項 round-6 發現是否真的修好？** 是，見狀態表 A/B。特別確認 `flowOn` 的位置正確：`flowOn` 只影響其**上游**，`.onEach{}.flowOn(Default).stateIn(viewModelScope, ...)` 使 `combine`／`transformLatest`／`compute()`／`onEach` 全在 Default 上，`stateIn` 的收集端仍在 Main。這是正確的擺法（若放在 `onEach` 之前，`last` 就會跨執行緒讀寫）。首次發射沒有遺失：`stateIn` 的初始值先到，`counts.take(1)` 立刻讓 `combine` 滿足兩路條件，`last.report == null` 讓第一次仍顯示 loading。

**2. Coroutine 正確性。**

- **`transformLatest` 取消**：**順序**正確——`cancelAndJoin()` 保證前一個區塊在新區塊啟動前結束，所以舊結果不可能覆蓋新結果。但取消**不會阻止舊區塊送出它的結果**（見 M-6 的原始碼證據），也不會中斷沒有 suspension point 的純 CPU 階段。
- **`CancellationException` rethrow**：5 處中 3 處正確，2 處遺漏（M-1）。
- **`last` 的資料競爭**：**存在但良性**。`onEach { last = it }` 跑在 `transformLatest` 內部 channel 的 consumer 端，`transformLatest` 的 `cancelAndJoin()` 不排序它對 `last` 的寫入，`last` 也沒有 `@Volatile`；最壞情況只是多發或少發一次 `loading = true`（狀態表 J 有原始碼依據）。
- **`shareIn` + `WhileSubscribed(5_000)` + `merge(take(1), drop(1).sample(400))`：第一筆 vault 發射是否一定會到？** **會，所有訂閱順序都安全。** 我逐一檢查了四種排列：
  - `take(1)` 先訂閱、`drop(1)` 後訂閱，中間有發射 → `drop(1)` 靠 `replay = 1` 拿到重播的那筆並丟棄，行為與同時訂閱一致。
  - `drop(1)` 先訂閱 → 它丟掉第一筆，`take(1)` 稍後訂閱時由 `replay = 1` 補上並立即發射。
  - 兩者都在多筆發射之後才訂閱 → `take(1)` 拿到重播的最新一筆立即發射，仍然有觸發。
  - 冷啟動、replay cache 空 → 兩者都在第一筆之前訂閱，`take(1)` 發射、`drop(1)` 丟棄。

  **`replay = 1` 正是讓 `drop(1)` 不會「丟錯元素」的關鍵**，這個設計是對的。`merge` 是 `ChannelLimitedFlowMerge`，兩條分支各自 `launch`，順序不保證，但如上所述四種排列都安全。
- **重新進入畫面時的重複計算（無害）**：離開分析頁 > 5 秒後回來，`take(1)` 先拿到 replay cache 裡的舊值立即觸發一次重算；接著 `shareIn` 重啟上游、`distinctUntilChanged` 是全新實例故第一筆必然通過，`drop(1)` 放行 → `sample(400)` 後再觸發第二次重算。多算一次，但因為週期沒變、`report != null`，不會閃 loading，使用者看不出來。
- **ViewModel 清除時的洩漏**：無。`shareIn` 與 `stateIn` 都掛在 `viewModelScope`，`onCleared()` 會一併取消。`vaultChanges(inbox)` 在建構期只被呼叫一次（作為 `combine` 的參數），不會每次重訂閱都建立新的 SharedFlow。

**3. UI 狀態。** `loading` 可能卡住 → 見 I-2。過期報表配新週期 → `LoadingScreen` 的 early return 擋住了主體，但 capped 警語（I-1）與標題列 subtitle 漏在 gate 之上。`capped` 一致性 → 在**非** loading 狀態下正確且五個 tab 一致（round-6 D 已修）；loading 期間不一致（I-1）。

**4. diff 的其餘部分。**

- **`.github/workflows/release.yml:98`** 新增 `if: github.event_name == 'workflow_dispatch'`：語意正確。tag push 時 `google-play` job 會被跳過，`github-release` job 的條件是 `startsWith(github.ref, 'refs/tags/v') || inputs.tag != ''`，不受影響。`workflow_dispatch` 的 `tag` 是 `required: true`，`build` job 以 `ref: ${{ inputs.tag || github.ref }}` 取出正確的 tag。**與 `docs/RELEASE.md` §4-5、`docs/zh-Hant/RELEASE.md` 的新敘述一致**，也符合 `CLAUDE.md`「Google Play uploads ... are a deliberate `workflow_dispatch`, never a side effect of a tag」。
- `whatsNewDirectory: src/fastlane/whatsnew` 指向的 `fastlane/whatsnew/{whatsnew-en-US, whatsnew-zh-TW}` 確實存在（141 / 117 bytes，遠低於 500 字元上限）。`docs/RELEASE.md` 新引用的 `fastlane/release-notes.json` 也存在。
- **`app/build.gradle.kts:50`** `versionCode = 4`，`fastlane/metadata/android/{en-US,zh-TW}/changelogs/3.txt` → `4.txt` 純改名（`git show --stat` 顯示 0 行變動），與 versionCode 對齊。
- **`docs/SCOPE.md:21`**「every tab shows a notice when it capped」與 `AnalyticsScreen.kt:140-148` 的新位置相符，**沒有跑在程式碼前面**。
- **`docs/reviews/README.md`** 補上 round 5 與 round 6 兩列，round 4 的「see round 5」改成實際 commit `fa49902`；round 6 那列的 fix commit 欄仍寫「follow-up commit」，本 commit 提交後應可回填為 `e5ad1a3`（極小 nit）。
- **`docs/TEST_MATRIX.md` 未被本 commit 修改，這是正確的**——commit 沒有新增或刪除任何測試，計數不會偏移。`CLAUDE.md` 要求的「Re-read docs/TEST_MATRIX.md counts after adding tests」在此不適用。
- **`platform/storage/.../Daos.kt`** 純註解搬移＋措辭更新，`@Query` 字串與函式簽章一字未動，無行為風險。

---

## 工作區漂移（未提交，不屬於 `e5ad1a3`）

審查進行到一半時，`AnalyticsViewModel.kt` 被本 session 的其他 agent 改動（`git status --short` 顯示 ` M feature/analytics/.../AnalyticsViewModel.kt`，blob 由 `64d6afe` 變為 `03fc61d`）。改動內容為：

1. 抽出 helper 並套用到**全部 5 個**站點（含 `labels` 與 `earliestTimestamp`）→ **M-1 已在工作區解決**：

```kotlin
private inline fun <T> orDefault(default: T, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    default
}
```

2. 移除未使用的 `import kotlinx.coroutines.flow.map`，新增 `import kotlinx.coroutines.CancellationException` → **M-4 的未使用 import 部分已在工作區解決**（import 排序仍未整理）。

兩點技術確認，供下一輪不必重新爭論：

- `orDefault` 標了 `inline` 且 `block` 未標 `noinline`/`crossinline`，因此 lambda 會被內聯進 `suspend fun compute()`，內部呼叫 `analytics.messagesBetween(...)` 等 suspend 函式**合法可編譯**。
- `catch (e: Throwable)` 的捕捉廣度與它取代的 `runCatching` **完全相同**（`runCatching` 同樣捕捉 `Throwable`），不是新的退步。

**報告寫完當下的再一次漂移**：`git status` 另外出現 ` M docs/TEST_MATRIX.md`、 ` M docs/zh-Hant/TEST_MATRIX.md` 與未追蹤的 `docs/reviews/2026-09-06-round7/`，接著 `feature/analytics/build.gradle.kts` 與 `AnalyticsScreen.kt` 也被改動（`AnalyticsScreen.kt` blob 由 `6ce8f7c` 變為 `599516b`），看起來有人正在補測試相依與畫面修正。這些都在 `e5ad1a3` 之後，不影響 M-7 對該 commit 的陳述（該 commit 本身零測試變動），但下一輪應重新核對 TEST_MATRIX 的計數。

**工作區這份改動不影響本報告的 Verdict**：I-1（capped 過期標籤）與 I-2（Locked vault 永久 loading）在工作區版本中**都還沒有處理**。

---

## 未涵蓋範圍（誠實聲明）

- **沒有執行 `./gradlew test` 或任何建置**。原因：`AnalyticsViewModel.kt` 在審查期間正被其他 agent 編輯，工作區與 `e5ad1a3` 不同，跑出來的結果不能歸因於被審查的 commit；且 brief 明令除報告檔外不得改動版本庫內任何檔案。若需要對 `e5ad1a3` 的建置證據，乾淨作法是 `git worktree add <scratch>/r7 e5ad1a3` 後在該處建置。
- 沒有任何裝置／模擬器操作，沒有 instrumented 測試。I-1 的失敗情境是從程式碼路徑推導的，未在裝置上錄影確認。
- 沒有驗證 `.github/workflows/release.yml` 的實際執行（無法在唯讀審查中觸發 GitHub Actions），只做了靜態的條件與路徑檢查。
- `docs/reviews/2026-09-06-round6/gemini-3.8-flash-high-agy.md` 只讀了章節大綱與兩項 Important 的標題，未逐字比對其 Minor 內容。

---

## 修正優先序

1. **（Important，單行）** I-1：`emit(last.copy(loading = true, selection = s, capped = false))`，或畫面端加 `&& !state.loading`。順帶處理標題列 subtitle 的同類問題。
2. **（Important）** I-2：讓 `AnalyticsViewModel` 感知 `VaultState.Locked`，比照 `InboxViewModel:80` / `HealthViewModel:77`，別讓分析頁在鎖定的 vault 上無限轉圈。同時把 `CHANGELOG.md:16` 的措辭收斂（M-3）。
3. **（Minor）** M-1：兩處 `runCatching` 補 rethrow — **工作區已做，提交即可**。
4. **（Minor）** M-7：補一支 `AnalyticsViewModel` 的 JVM 測試，把 `flowOn`、loading 條件、以及第 1 點的 `capped` 修正一起釘住。這是唯一能防止 `flowOn` 第三次被誤刪的手段。
5. **（Minor）** M-2 counts 流的自我復原、M-4 import 排序、M-5 `last` 改用 `scan()`、M-6 `ensureActive()`。
6. **（nit）** `docs/reviews/README.md` round 6 那列的 fix commit 回填為 `e5ad1a3`。

完成第 1、2 項後，本輪即可視為 **APPROVE WITH MINOR FIXES**。
