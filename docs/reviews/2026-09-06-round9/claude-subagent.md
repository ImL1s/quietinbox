# Round 9 最終確認審查（Claude Opus subagent，唯讀）— QuietInbox

**審查對象**：`git diff 652aa69..ea34339`（兩個 commit：`1249be5`「round-8 review finding」、`ea34339`「round-8 minors」）。
**審查時間**：2026-09-06
**方法**：`git show --stat 1249be5 ea34339`、逐行讀 `git diff 652aa69..ea34339`、讀取現況全文
`AnalyticsViewModel.kt` / `AnalyticsScreen.kt` / `AnalyticsViewModelTest.kt` / 兩個語系的
`strings_analytics.xml` / `.github/workflows/ci.yml` / `DatabaseHolder.kt` / `feature/analytics/build.gradle.kts`，
對照 `docs/reviews/2026-09-06-round8/` 兩份原始報告逐條核對，並**實際執行測試**。

**測試執行的來源說明（重要）**：審查期間工作區被其他 agent 修改（見文末「版本庫漂移」），
其中一項改動落在 `AnalyticsViewModel.kt`。我在工作區跑的四次（1 次冷跑 + 3 次 `--rerun-tasks` 重跑）
之中，**至少後三次編譯的是漂移後的內容**。為了得到針對 `ea34339` 本身的乾淨證據，我把該 commit
以 `git archive ea34339 | tar -x` 解到 scratchpad（不動版本庫），在該乾淨樹上以
`--rerun-tasks --no-build-cache --no-daemon` **強制實跑三次**。

**實測結果**：乾淨樹三次強制執行皆 `tests="8" skipped="0" failures="0" errors="0"`，BUILD SUCCESSFUL；
工作區四次亦同。合計七次全綠，無不穩定跡象。單一測試耗時 0.008–0.748 s。
（附帶佐證：乾淨樹第一次執行時 `:feature:analytics:testDebugUnitTest` 命中 `FROM-CACHE`，
表示先前已有一次**輸入完全相同**的成功執行，亦即工作區的第一次冷跑就是 `ea34339` 的內容。）
**未執行**：任何裝置／模擬器操作、instrumented 測試、任何會改變版本庫狀態的 git 操作、任何檔案修改
（本報告除外）。

> **行號基準**：所有 `AnalyticsViewModel.kt` 行號皆以 `git show ea34339:` 的內容為準（已用
> `git show ea34339:… | cat -n` 核對）。審查期間工作區再次漂移，見文末「版本庫漂移」。

---

## Verdict

# APPROVE WITH MINOR FIXES

**一句話理由**：round-8 兩份報告中這兩個 commit **聲稱要修的每一項都真的修好了**，而且是結構性修法而非
繞過症狀；`flatMapLatest` 的改寫我逐條追過 lost tick / double compute / `WhileSubscribed` 重啟三個迴歸面，
沒有發現新的 Critical 或 Important；新增的兩個測試不是走過場，文件與程式碼一致、字串兩語系齊平、CI 終於
會跑到這些測試。下列 Minor 全部不阻擋 push。

---

## Round-8 findings status table

| 發現（來源） | 是否修好 | 證據（`ea34339` 行號） |
|---|---|---|
| **agy I-1 ／ subagent Minor-1**：已訂閱狀態下 `Locked → Ready` 且計數未變時活動頁不復原 | ✅ **已修（雙保險）** | `AnalyticsViewModel.kt:268-271` 改為 `vault.state.flatMapLatest { v -> if (v !is Ready) flowOf(v) else merge(counts.take(1), counts.drop(1).sample(400)).map { v } }`。每次轉為 `Ready` 都開一條**全新**內層訂閱，`take(1)` 因此重新可用。復原有**兩條互相獨立**的路徑：(a) `:267` 的 `shareIn(..., replay = 1)`——`WhileSubscribed(5_000)` 的 `replayExpirationMillis` 預設為 `Long.MAX_VALUE`，鎖定期間快取不會失效，重訂閱即刻重播一筆；(b) `DatabaseHolder.kt:74-75` 的 `flowWithDb` 在 `Ready` 時重新訂閱 Room，Room 立刻發射當前計數。即使 (a) 失效 (b) 仍成立。測試 `AnalyticsViewModelTest.kt:155-163` 專門覆蓋此路徑（開著頁面鎖定→解鎖、**不動計數**）。 |
| **subagent Minor-2**：`ticks.map { vault.state.value }` 現場重讀造成狀態錯配 | ✅ **順帶結構性消除** | `:270` 的 `.map { v }` 沿用外層 `flatMapLatest` 已固定的 `v`，不再重讀 `vault.state.value`，「對已關閉的 db 跑 compute 而顯示空報表」的視窗從結構上消失。 |
| **subagent M-1 / Minor-8 ／ agy M-1**：測試 harness 與 `flowWithDb` 行為不符 | ✅ **已修** | `AnalyticsViewModelTest.kt:82-85`：`observeCounts()` 改為 `vaultState.flatMapLatest { v -> if (v is Ready) (countsFlow ?: counts) else emptyFlow() }`，與 `DatabaseHolder.kt:75` 逐字對應，並留下註解說明鏡像關係。這也讓 `:150`、`:168` 兩處得以刪掉「解鎖同時改計數」的遮蔽寫法，測試名稱不再強過它證明的內容。 |
| **subagent Minor-7 ／ agy M-2**：執行緒斷言依賴 `"DefaultDispatcher-worker"` 命名 | ✅ **已修** | `AnalyticsViewModelTest.kt:94` 取 `testThread = Thread.currentThread().name`，`:101` 斷言 `shouldNotBe testThread`。**不是同義反覆**：`:53` 把 Main 設為 `UnconfinedTestDispatcher`，若 `flowOn(Dispatchers.Default)`（`AnalyticsViewModel.kt:128`）被刪掉，`stateIn` 的收集會在訂閱者（測試）的執行緒上急切執行，查詢執行緒就會等於 `testThread`，斷言失敗。 |
| **subagent Minor-5**：「安靜重算」測試被 `StateFlow` 合併悄悄放過 | ✅ **已修（仍有殘留視窗，見 Minor-2）** | `AnalyticsViewModelTest.kt:130` 先 `h.gate = CompletableDeferred()` 把重算卡在第一個查詢，`:132` 等 `queries` 遞增，`:135-136` 直接斷言 `state.value.loading` 為 false 且 `report != null`。若拿掉 `AnalyticsViewModel.kt:125` 的守衛，佔位狀態會是當下可見狀態，斷言會失敗。舊版收集 `seen` 再事後掃描的弱斷言已刪除。 |
| **subagent Minor-4（前半）**：查詢失敗被靜默降級成「零報表」，違反誠實標籤原則 | ✅ **已修** | `AnalyticsUiState.degraded`（`:84-85`）＋ `Degradation`（`:209-210`）＋ `Degradation.orDefault`（`:217-224`，default 分支設 `any = true`）＋ 5 個查詢站點全部改用（`:144`、`:146`、`:147`、`:151`、`:188`、`:232`）＋ `:205` `degraded = degradation.any`。UI：`AnalyticsScreen.kt:151-159` 以 `colorScheme.error` 的 `labelSmall` 呈現，位置與既有 `capped` 標籤（`:142-150`）一致。字串兩語系皆有：`values/strings_analytics.xml:77`、`values-b+zh+Hant/strings_analytics.xml:76`。測試 `AnalyticsViewModelTest.kt:180-186`（`labels()` 拋錯 → `degraded` 為 true）與 `:173-178`（計數流失敗但報表本身健康 → `degraded` 為 false）成對，**不是同義反覆**。 |
| **subagent Minor-4（後半）**：`catch (e: Throwable)` 連 `Error` 一起吞 | ❌ **未修（本 commit 未聲稱要修）** | `:221` 仍為 `catch (e: Throwable)`。詳見 Minor-3。（工作區已有未提交修正，見文末。） |
| **subagent Minor-3**：`retryWhen` 的 `attempt` 成功後不歸零 | ✅ **已修** | `:258` 區域變數 `consecutiveFailures`，`:260` `.onEach { consecutiveFailures = 0 }` 置於 `retryWhen` **上游**（因此 `:262` 的 fallback 發射不會誤觸重置），`:263` 改用 `minOf(consecutiveFailures++, 5)`。退避序列仍為 1／2／4／8／16／30 s。新引入的行為取捨見 Minor-1。 |
| **subagent Minor-11 ／ round-7 M-4 後半**：import 排序 | ✅ **已修** | `:18-24` 已是 `db.VaultState` → `repo.*`；`:25-54` 為 `javax` → `kotlin` → `kotlinx` 的 ASCII 序；`:32-52` flow 群組亦為 ASCII 序（大寫先於小寫）。我逐行核對，無殘留。 |
| **CI**：`feature:analytics` 與 `platform:capture` 從未在 CI 跑過 | ✅ **已修** | `.github/workflows/ci.yml:31-32` 加入 `:platform:capture:testDebugUnitTest` 與 `:feature:analytics:testDebugUnitTest`，`:17` 的 job 名稱同步更新。`platform/capture/src/test/…/CaptureCoordinatorTest.kt` 存在（11 個測試），不是空跑。JDK 17 + ubuntu-latest 無移植性問題——見「測試品質」第 3 點。 |
| **文件**：CHANGELOG / SCOPE / TEST_MATRIX（en + zh-Hant）／ reviews index（en + zh-Hant） | ✅ **已修且與程式碼相符** | 見下方「文件核對」。 |
| subagent Minor-6（四個 CPU 階段間缺 `ensureActive()`）、Minor-9（測試不清理 ViewModel）、Minor-10（兩路訂閱可能多送／漏送一次 tick） | ⬜ **未修，本 commit 亦未聲稱要修** | `:165-172` 四階段之間仍無檢查；測試無 `onCleared()`；`:270` 仍是兩個獨立訂閱。三項皆為當初判定「可留到下一輪或忽略」者。 |

---

## Critical（0 項）

無。這兩個 commit 不觸及權限、加密、Room migration、擷取管線或任何會產生錯誤資料的路徑；
`INTERNET` 權限與「絕不對來源通知動作」的硬規則完全未被碰到。

## Important（0 項）

無。我針對 brief 指名的四個迴歸面各自追到底，結論如下（詳細機制寫在「Flow 鏈路逐題回答」）：
`flatMapLatest` 取消內層**不會**造成漏 tick（replay 快取 + `flowWithDb` 重訂閱雙保險）、
**不會**重複 compute（`VaultState.Ready` 是 data class，`StateFlow` 按相等性去重，同一個 db 實例不會重複發射）、
`WhileSubscribed` 重啟後回到畫面是「舊報表原地靜默更新」而非全螢幕轉圈；
`consecutiveFailures` 由單一 `shareIn` 收集協程獨佔，無資料競爭；
`degraded` **不可能**出現在載入中或佔位上。

---

## Minor

### Minor-1. 退避歸零後，「發射一筆再失敗」的抖動上游會固定每秒重試並每秒重算一次
**位置**：`AnalyticsViewModel.kt:260`（`onEach { consecutiveFailures = 0 }`）＋ `:263`。

**機制**：重置條件是「任何一次成功發射」，而不是「持續健康一段時間」。若上游是
**發射 → 拋錯 → 發射 → 拋錯** 的抖動型失敗，每一輪都會先觸發 `onEach` 歸零，於是退避永遠停在第一階
的 1 秒，不再成長。

**具體失敗情境**：SQLCipher 在某次金鑰重新驗證後進入半可用狀態——Room 的 invalidation flow 第一次查詢
成功回傳計數、下一次查詢拋 `VaultUnavailableException`。此時每一秒發生一次：`retryWhen` 先發
`InboxCounts(0,0,0,0)` fallback（`:262`）、延遲 1 秒、重訂閱、真實計數發射一次後再拋錯。
`distinctUntilChanged()`（`:266`）不會擋下來，因為真實值與 `(0,0,0,0)` 交替出現、彼此不相等。
於是 tick 以約 1 Hz 進入管線（`sample(400)` 只把上限壓到 2.5 Hz），`compute()` 每秒重跑一次，
在「全部」期間最壞情況要掃 50,000 筆訊息 + catchphrase 掃描。舊實作在同一情境下退避會長到 30 秒，
所以這是一個**真實但很窄的行為退步**：使用者感受是活動頁停在前景時裝置持續發燙。

**建議**（任選其一，都不影響本輪 Verdict）：把重置條件改成「距離上一次失敗已超過一個退避週期才歸零」，
或保留歸零但把最短重試間隔從 1 秒提高到 5 秒，或以「連續 N 筆成功」為歸零門檻。

### Minor-1b. 空金庫上，`degraded` 誠實標籤可能在資料庫恢復後繼續掛著
**位置**：`AnalyticsViewModel.kt:262`（fallback `InboxCounts(0, 0, 0, 0)`）＋ `:266`（`distinctUntilChanged()`）。

這是 `distinctUntilChanged` 的第三條路徑（另兩條是 Minor-1 的抖動、與 round-8 已修的解鎖復原）。
**具體情境**：金庫是空的（真實計數就是 `0,0,0,0`）→ 資料庫發生暫時性故障 →
`retryWhen` 發出 fallback `(0,0,0,0)` → 觸發一次 `compute()`，五個查詢全失敗 →
`degraded = true`，畫面顯示「此報表可能不完整」→ 資料庫恢復，Room 發出真實的 `(0,0,0,0)` →
**與快取的 fallback 相等，被 `distinctUntilChanged` 吃掉** → 沒有 tick、沒有重算 →
紅色誠實標籤一直掛到下一次真正的計數變動或金庫狀態轉換為止。

情境很窄（需要空金庫 + 暫時性故障），而且顯示的內容**對當下那份報表而言仍然誠實**
（它確實是降級算出來的），只是「陳舊」。若要處理，最小改法是讓 fallback 帶一個
不可能與真實值相等的哨兵值，或把 `distinctUntilChanged()` 移到 fallback 注入之前。

### Minor-2. 「安靜重算」測試的觀測點仍有一個小視窗，可能**漏抓**（不會誤報失敗）
**位置**：`AnalyticsViewModelTest.kt:132-136`。

**機制**：`queries` 的遞增發生在 mock 內（`:73`），而佔位狀態（若守衛被移除）要經過
`flowOn(Dispatchers.Default)` 的緩衝 channel 才會被 `stateIn` 的收集協程寫進 `state.value`。
producer 發完 `emit` 不會掛起，會直接往下跑到 `messagesBetween` 遞增計數，因此存在
「`queries` 已遞增、`state.value` 尚未更新」的空窗。實務上 `:131` 的計數變更要先經過 `sample(400)`，
測試的 `while (…) delay(20)` 迴圈早已在自旋，遞增後最多 20 ms 才會讀 `state.value`，
足夠讓 channel 排空——所以這是**漏抓**風險而非 flake 風險，我四次重跑也都穩定。

**零成本的補強建議**：`:126` 的背景收集器目前是 `collect {}`，把它改回
`collect { seen += it }`，並在 `:137` `gate.complete(Unit)` 之後補一句
`seen.none { it.loading }.shouldBeTrue()`。有 `gate` 卡住重算之後，round-8 Minor-5 對
「`StateFlow` 合併會吃掉佔位」的原始反對意見已不成立（佔位會被長時間持有），兩者合起來嚴格強於任一方。

### Minor-3. `orDefault` 仍以 `catch (e: Throwable)` 收尾，`Error` 會被翻譯成「報表可能不完整」
**位置**：`AnalyticsViewModel.kt:221`。

`degraded` 旗標讓這件事比之前更值得處理：`OutOfMemoryError`、`StackOverflowError`、linkage error
現在不再只是靜默變成空清單，而是**主動對使用者宣稱「部分資料無法讀取」**——把 JVM 級故障誤述成
資料層故障。建議收斂成 `catch (e: Exception)`。（此為 round-8 subagent Minor-4 的後半，本 commit 未聲稱要修；
工作區已有未提交修正，見文末。）

### Minor-4. `AnalyticsScreen.kt:151` 的 `&& !state.loading` 是死條件
`degraded` 只在 `compute()` 的回傳值上為 true（`:190-206`），而該狀態必然 `loading = false`；
`:115`、`:117`、`:125` 三個佔位／終止狀態都是全新物件，`degraded` 皆為預設 false。
因此 `!state.loading` 永遠成立。留著無害（防禦性），但會讓讀者以為存在「載入中同時 degraded」的狀態。

### Minor-5. `consecutiveFailures` 的生命週期跨越 `WhileSubscribed` 的重啟
**位置**：`AnalyticsViewModel.kt:258`。

該變數是 `vaultSignals()` 的區域變數，而 `vaultSignals()` 只在 `state` 屬性初始化時求值一次
（`:111`），因此它的壽命等同 ViewModel。使用者離開活動頁超過 5 秒、上游被
`WhileSubscribed(5_000)` 收掉之後再回來，若第一次訂閱就失敗，退避會**接續**離開前的階數而不是從 1 秒開始。
可以論證這是想要的行為（同一個 ViewModel 內的持續故障），列此僅為記錄。

**並行安全性我已確認無虞**：`onEach` 與 `retryWhen` 兩個 lambda 都跑在 `shareIn` 唯一的上游收集協程裡
（`inbox.observeCounts()` 內部的 `flowOn` 只影響它自己上游的運算子），協程的掛起／恢復提供 happens-before，
因此不需要 `@Volatile`。這一點 brief 有特別點名，結論是**沒有問題**。

### Minor-6. 文件的三個小尾巴
- **`CHANGELOG.md:8` 與 `docs/TEST_MATRIX.md:21` 對同一個測試的描述已不一致**：TEST_MATRIX 隨斷言改寫
  更新成「first report computed off the collector's thread」（zh-Hant `:21` 同步為「首份報表不在收集端的
  執行緒計算」），CHANGELOG 卻仍寫「the first report is computed on `Dispatchers.Default`」。
  程式碼確實用 `flowOn(Dispatchers.Default)`（`AnalyticsViewModel.kt:128`），所以敘述本身不算錯，
  但**做為對測試涵蓋範圍的描述，它強過測試實際證明的內容**（`:101` 只證明「不等於收集端執行緒」）。
  這正是專案再三被提醒的「docs must not run ahead of the code」型態，建議與 TEST_MATRIX 統一措辭。
- `docs/reviews/README.md:19` 與 `docs/zh-Hant/reviews/README.md:17` 的 fix commit 欄寫
  ``` `1249be5` + follow-up ```／``` `1249be5` + 後續 commit ```，沒有指名 `ea34339`。既然該 commit 已存在，
  直接寫出來對日後追溯比較有用（前幾輪都是單一 SHA）。
- `docs/TEST_MATRIX.md:21` 與 `docs/zh-Hant/TEST_MATRIX.md:21` 的執行欄仍寫「JVM」，其他每一列都給完整
  gradle 指令。既然 `ci.yml:32` 現在明確跑 `:feature:analytics:testDebugUnitTest`，補上該指令零成本。
  （round-8 已提過，本 commit 未聲稱要修。）

---

## Flow 鏈路：逐題回答 brief 的迴歸疑問（針對 `ea34339`）

1. **`flatMapLatest` 取消內層訂閱會漏 tick 嗎？** 不會漏掉「該重算」這件事。內層被取消時
   `counts.take(1)` 與 `counts.drop(1).sample(400)` 兩個訂閱一起消失，但 `shareIn(replay = 1)`
   的快取在 `WhileSubscribed(5_000)` 之下不會過期（`replayExpirationMillis` 預設無限），
   重訂閱時 `take(1)` 立刻拿到一筆；即使快取被清掉，`flowWithDb`（`DatabaseHolder.kt:75`）
   在 `Ready` 時重新訂閱 Room 也會立刻發一筆。**殘留視窗**（round-8 Minor-10，未修）：
   `take(1)` 與 `drop(1)` 是兩個獨立訂閱，若在兩者掛上的微秒級空隙間剛好來一筆新計數，
   `drop(1)` 會把新值當成「第一筆」丟掉，導致該次計數變動不觸發額外重算。
   後果良性——`compute()` 本來就是重新查資料庫，內容不會是舊的，只是少一次重算時機，下一筆通知就會補上。
2. **會 double compute 嗎？** 不會。`VaultState.Ready` 是 `data class Ready(val db: QuietInboxDatabase)`
   （`DatabaseHolder.kt:31`），`StateFlow` 按相等性去重，同一個 db 實例不會重複發射，
   `flatMapLatest` 也就不會重啟。只有 `retry()` / `closeAndDeleteFiles()` 產生**新** db 實例時才重啟——
   而那正是應該重算的時機。冷啟動 `Opening → Ready` 只會 compute 一次。
3. **`WhileSubscribed` 重啟會退化嗎？** 不會。回到畫面時 `stateIn` 保留最後一個值，
   `take(1)` 由 replay 快取立刻取得 tick，而 `AnalyticsViewModel.kt:125` 的
   `s == last.selection && last.report != null` 使佔位不被發射——看到的是舊報表原地靜默更新。
   若離開前的最後狀態是 `vaultLocked`，回來時 `last.report == null` 會先發一次佔位再算，行為正確。
4. **`consecutiveFailures` 被兩個 lambda 共用有競態嗎？** 沒有，見 Minor-5 的說明。
5. **`Degradation` + 成員擴充函式有問題嗎？** 沒有。`private class Degradation`（`:210`）是每次
   `compute()` 新建（`:143`），不跨計算殘留；`private inline fun <T> Degradation.orDefault`（`:217`）
   同時帶 dispatch receiver（ViewModel）與 extension receiver，`inline` 讓 lambda 內的 suspend 呼叫
   合法內聯進 `compute()`——這是原本 `orDefault` 就用的手法，改成成員擴充後性質不變。
   `CancellationException` 仍在 `any = true` 之前 rethrow（`:219-220`），取消**不會**被誤記為 degraded，
   這一點很關鍵，因為切換期間會取消上一次計算。
6. **`degraded` 會出現在載入中或佔位上嗎？** 不會。`:115`（Locked）、`:117`（Opening）、`:125`（佔位）
   都建構全新的 `AnalyticsUiState`，`degraded` 取預設 false；唯一設為 true 的地方是 `:205`，
   而該狀態 `loading = false` 且 `report != null`。UI 端 `AnalyticsScreen.kt:151` 的
   `&& !state.loading` 因此是死條件（Minor-4）。順帶確認渲染順序正確：degraded 標籤（`:151`）在
   `vaultLocked` 的 `EmptyState`（`:160`）與 loading gate（`:168`）**之前**，
   所以當 `messagesBetween` 失敗導致空報表時，使用者會同時看到
   「Part of the vault could not be read; this report may be incomplete.」與「Not enough observed messages」——
   這正是 round-8 Minor-4 要求的「查詢失敗與真的沒訊息在畫面上可區分」。
7. **字串齊平嗎？** 齊平。兩個 `strings_analytics.xml` 各 60 個 `<string>`，`name` 清單
   **逐字且同序相同**（`diff` 無輸出）。新字串無格式參數，無 `%1$d` 錯配風險。
   語意上英文寫 “Part of the vault”、中文寫「部分資料」，可接受的在地化差異。

---

## 測試品質

1. **同義反覆**：沒有。八個測試都建構真實的 `AnalyticsViewModel` 並斷言管線行為，不是斷言 mock。
   我逐一檢查了「拿掉被測性質後是否會失敗」：
   - `:101` 拿掉 `flowOn` → 查詢跑在測試執行緒 → 失敗。
   - `:114-116` 拿掉「發射全新物件」的佔位 → `capped`／`report` 殘留 → 失敗。
   - `:135` 拿掉 `:125` 的守衛 → 佔位可見 → 失敗（殘留視窗見 Minor-2）。
   - `:160-162` 回到舊的 `filter { it !is Ready }` 實作 → 永遠等不到 `report != null` → 逾時失敗。
   - `:185` 拿掉 `degradation.any` → 失敗。
   - `:177` 的 `degraded.shouldBeFalse()` 是很好的**反向**斷言：它固定住「計數流失敗**不算**報表降級」
     這條界線，避免 `degraded` 日後被濫用成「任何錯誤都亮紅字」。
2. **時序穩定性**：四次重跑全綠、無 flake。唯一的真實時間等待是 `:167` 的
   `withTimeoutOrNull(700)`，判定為 `.shouldBeNull()`——CI 再慢也只會更容易通過（不會誤報失敗），
   代價是固定 0.7 s。其餘全部是 `first { predicate }` + `withTimeout(10_000)`，
   10 秒對 ubuntu-latest runner 綽綽有餘（本機最慢的測試 0.748 s）。
   `sample(400)` 只出現在生產碼，測試不對它做時間斷言。
   `h.gate` / `h.messages` 是普通 `var` 跨執行緒讀寫，但每次寫入之後都緊接著一次
   `MutableStateFlow` 的 volatile 寫入（`:131`）或由測試協程自己觸發（`:112`），
   happens-before 成立，實務上不會看到過期值。
3. **CI 移植性（Linux + JDK 17）**：沒有問題。
   - 執行緒名稱相依已移除（`:101`）；殘留的理論風險是「Kotest 若被設定成在 `Dispatchers.Default`
     上跑測試體，且恰好與 compute 撞到同一個 worker」——`feature/analytics/build.gradle.kts` 沒有任何
     Kotest 並行／dispatcher 設定，專案也沒有 `kotest.properties` 或 `AbstractProjectConfig`，
     採用預設值，因此不會發生。
   - `Dispatchers.setMain(UnconfinedTestDispatcher())` 在 `beforeSpec`／`afterSpec` 成對（`:53-54`），
     模組內只有這一個 spec，不會與其他 spec 互搶 Main。
   - `:platform:capture:testDebugUnitTest` 與 `:feature:analytics:testDebugUnitTest` 都是 Android
     library 的 unit test，與既有的 `:platform:crypto` / `:platform:backup` 同一類，
     Android SDK 環境已由現有 job 涵蓋；`gradle/verification-metadata.xml` 也已列入
     `mockk` / `kotlinx-coroutines-test` / `kotest-runner-junit5`（`:platform:capture` 早已使用其中兩個）。
4. **未清理**：八個測試都沒有取消 `viewModelScope`（round-8 Minor-9，未修）。
   `:173-178` 那個失敗查詢的測試會留下一個退避重試迴圈活到 spec 結束。今天無害
   （`WhileSubscribed(5_000)` 會在最後一個訂閱者離開 5 秒後停掉），且 `docs/TEST_MATRIX.md`
   的工作區版本已把這件事誠實寫進「Not covered yet」。

---

## 文件核對（docs vs code）

| 項目 | 結果 |
|---|---|
| `CHANGELOG.md:8` `[Unreleased] Added` | ✅ 「8 JVM tests」與實測 XML 的 `tests="8"` 相符；逐項描述與八個測試名稱一一對應，包含新增的兩項（鎖定後無計數變動仍復原、查詢失敗標示 degraded）。 |
| `CHANGELOG.md:12` `[Unreleased] Changed` | ✅ 新增的 round-8 段落四個子句全部對得上程式碼：`flatMapLatest`（`:268`）、harness 鏡像儲存層（測試 `:82-85`）、不再依賴 worker 執行緒名（測試 `:101`）、degraded 誠實標籤（`:205` + Screen `:151`）、退避成功後重啟（`:260`）。**沒有超出程式碼的宣稱**。 |
| `CHANGELOG.md:28`「157 JVM tests」 | ✅ 非問題。該行屬於已發布的 `[0.1.0]` 區段，是歷史值，不應隨 `[Unreleased]` 變動。 |
| `docs/SCOPE.md:21` | ✅ 「32 JVM tests in `core:analytics` plus 8 in `AnalyticsViewModelTest`」，8 為我實測值。 |
| `docs/TEST_MATRIX.md:21` ／ `docs/zh-Hant/TEST_MATRIX.md:21` | ✅ 兩語系同步改為 8 個測試，八項描述逐一對應且**與英文版一字對譯**（包括「首份報表不在收集端的執行緒計算」這個隨斷言改寫而更新的措辭）。小尾巴見 Minor-6。 |
| `docs/reviews/README.md:19` ／ `docs/zh-Hant/reviews/README.md:17` | ✅ round-8 那列的 reviewer 名單、兩個 verdict、Important／Minor 數量（agy 1 Important；subagent 0 Important、11 Minor）我逐一對照歸檔報告確認無誤；合併 verdict「APPROVE WITH MINOR FIXES」符合「strictest verdict wins」。Kimi blocked 有註明。兩語系同步。fix commit 欄見 Minor-6。 |
| round-8 報告歸檔 | ✅ `docs/reviews/2026-09-06-round8/` 內 `brief.md`、`gemini-3.8-flash-high-agy.md`（174 行）、`claude-subagent.md`（180 行）、`kimi-blocked.md` 皆逐字歸檔，符合 `docs/reviews/README.md` 的規定。 |

**「Docs must not run ahead of the code」**：本輪未發現任何超前宣稱。反向也成立——
`docs/TEST_MATRIX.md` 的「Not covered yet」誠實寫出 degraded 標籤尚未在裝置上走過。

---

## 其他觀察

- **`ci.yml` 的補洞是這兩個 commit 最被低估的價值**：在 `1249be5` 之前，
  `AnalyticsViewModelTest`（round 7 新增）與 `CaptureCoordinatorTest`（11 個測試，早就存在）
  **在 CI 上一次都沒跑過**。現在兩者都進了 `jvm-tests` job，且該 job 是 `assemble` 與
  `instrumented` 的 `needs` 前置，破壞會擋住整條流水線。
- `retryWhen` 的 fallback 值 `InboxCounts(0, 0, 0, 0)`（`:262`）只被當作 tick 使用
  （`:270` 的 `.map { v }` 把它丟掉），不會有「計數歸零」被畫到 UI 上的風險——
  活動頁的數字全部來自 `compute()` 重新查詢的結果。
- `AnalyticsScreen.kt` 的四段 gate 順序（capped → degraded → vaultLocked → loading）是對的：
  兩個誠實標籤都在終止分支之前，`return@Column` 不會把它們吃掉。
- 本輪沒有新增任何相依、沒有動 `gradle/verification-metadata.xml`、
  沒有碰 manifest 或權限，`tools/check-permissions.sh` 的結果不會改變。

---

## 版本庫漂移（審查進行中發生，尚未提交）

與 round 6／7／8 相同的模式：我讀完 HEAD 之後，工作區被其他 agent 修改。
`git status` 目前為：

```
 M CHANGELOG.md
 M docs/TEST_MATRIX.md
 M docs/zh-Hant/TEST_MATRIX.md
 M feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt
?? docs/reviews/2026-09-06-round9/
```

**本報告的 Verdict 與所有行號一律針對 `ea34339`**（我已用 `git show ea34339:` 重新核對每一個引用行號）。
未提交的改動與我的發現對應如下：

- `AnalyticsViewModel.kt:221` `catch (e: Throwable)` → `catch (e: Exception)`（註解 `// JVM Errors (OOM, linkage) keep propagating`）
  → **正是 Minor-3 的修法**。
- `AnalyticsViewModel.kt:263` 拆成 `delay((1_000L shl consecutiveFailures)…)` 加一行
  `consecutiveFailures = minOf(consecutiveFailures + 1, 5)`。這只是把計數器**夾住不再無限成長**，
  退避序列（1／2／4／8／16／30 s）與行為完全等價，**沒有**處理 Minor-1 的抖動情境。
- `docs/TEST_MATRIX.md` / `docs/zh-Hant/TEST_MATRIX.md` 的「Not covered yet」補上 degraded 標籤未裝置驗證
  與 harness 不取消 `viewModelScope`（呼應 round-8 Minor-9）——兩語系同步，是誠實的加分。
- `CHANGELOG.md` 在 `[0.1.0]` 下新增「Known issues in 0.1.0 (fixed on main, ship in 0.1.1)」段落，
  誠實記錄已上架版本的兩個活動頁缺陷。**這一段我尚未逐條驗證**（超出本輪 diff 範圍），
  但敘述與 round-7 兩項 Important 的內容相符。

**上述未提交改動我未逐條驗證其正確性**。不過我在工作區跑的後三次測試（`--rerun-tasks`）
編譯的正是漂移後的內容，八個測試同樣全綠——這是意料中的：兩處差異對這組測試行為等價
（沒有任何測試會拋 `Error`；退避在測試存活期間也達不到第 5 階）。
提交前仍應重跑 `./gradlew :feature:analytics:testDebugUnitTest` 並確認 CHANGELOG 新段落的敘述。

---

## 未涵蓋範圍（誠實聲明）

- 沒有任何裝置／模擬器操作。degraded 標籤的實際版面、鎖定→解鎖的復原動畫、
  紅色 `labelSmall` 在深色主題下的對比度，都是從程式碼路徑推導，未在裝置上確認。
- 只跑了 `:feature:analytics:testDebugUnitTest`（乾淨樹 3 次 + 工作區 4 次）。
  `./gradlew test` 全模組我**未執行**，`:platform:capture:testDebugUnitTest`（CI 新增項）
  我也未單獨執行——只確認測試檔 `CaptureCoordinatorTest.kt` 存在。
- 測試在 macOS / JDK 執行；CI 的 ubuntu-latest + JDK 17 組合我是從設定與程式碼推導無移植性問題，
  未實際在 Linux 上驗證。
- 沒有跑 instrumented 測試、沒有跑 `tools/check-permissions.sh`、沒有 assemble release。
- 沒有做任何會改變版本庫狀態的 git 操作，沒有修改本報告以外的任何檔案。
- 我讀了 round-8 兩份報告的全文以建立核對清單，但沒有重新驗證它們自身的結論是否正確
  （本輪任務是確認「聲稱修好的是否真的修好」）。

---

## 修正優先序（針對 `ea34339`，全部非阻擋）

1. **（Minor-1）** 退避歸零條件加一道防抖：最短重試間隔提高到 5 秒，或以「距上次失敗超過一個退避週期」
   為歸零門檻。這是本輪唯一有實際使用者可感後果（裝置發燙）的項目。
2. **（Minor-3）** `catch (e: Exception)`——**工作區已修**，提交即可。
3. **（Minor-6 第一項）** 把 `CHANGELOG.md:8` 的「computed on `Dispatchers.Default`」改成與
   `docs/TEST_MATRIX.md:21` 一致的「off the collector's thread」。這是唯一一處「文件敘述強過測試證明」，
   與專案反覆被提醒的規則直接相關，改一個詞即可。
4. **（Minor-2）** 「安靜重算」測試把 `seen` 收集加回去並斷言 `none { it.loading }`，
   零成本補上殘留的漏抓視窗。
5. **（Minor-1b）** 若要一併處理，讓 `retryWhen` 的 fallback 帶哨兵值，避免空金庫上誠實標籤變陳舊。
6. **（美觀）** Minor-4 拿掉死條件 `&& !state.loading`；Minor-6 其餘兩處文件尾巴。
