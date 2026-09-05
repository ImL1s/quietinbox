# QuietInbox 第三輪 Code Review（獨立唯讀 subagent）

審查對象：`git diff c96fbf0..6a9b0ce`（20 檔，+618/−36），置於整個 codebase 的脈絡中。
審查依據：`docs/reviews/2026-09-06-round3/brief.md`、round-2 兩份報告（agy `APPROVE WITH MINOR FIXES`、subagent `REQUEST CHANGES`）。

> **審查基準線**：本報告的每一個 file:line 都指向 **commit `6a9b0ce`**，不是工作區。
> 審查進行中有其他 agent 在同一個 repo 上編輯，交報告當下 `git status` 顯示 4 個未提交檔案
> （`docs/SCOPE.md`、`docs/TEST_MATRIX.md`、`platform/backup/.../BackupService.kt`、
> `platform/storage/.../IngestRepository.kt`），其中已含本報告 Minor 1／2／9 的部分修正。
> 那些改動**不在 `6a9b0ce` 裡**，因此仍列為發現；詳見第四節第一條。

---

## 執行過的驗證

- `./gradlew :core:reconcile:test :core:model:test --rerun-tasks` → **BUILD SUCCESSFUL, exit 0**。
- 追加執行 `:core:parser:test :core:identity:test :core:analytics:test :parsers:apps:test :app:testDebugUnitTest :platform:crypto:testDebugUnitTest :platform:backup:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL, exit 0**。由 JUnit XML 逐檔統計：

  | 模組 | tests | failures + errors |
  | --- | ---: | ---: |
  | `core:model` | 5 | 0 |
  | `core:parser` | 10 | 0 |
  | `core:identity` | 5 | 0 |
  | `core:reconcile` | 20 | 0 |
  | `core:analytics` | 4 | 0 |
  | `parsers:apps` | 43 | 0 |
  | `app` | 4 | 0 |
  | `platform:crypto` | 3 | 0 |
  | `platform:backup` | 3 | 0 |
  | **合計** | **97** | **0** |

  `:app:testDebugUnitTest` 通過同時證明 `platform:storage`、`platform:capture`、`platform:backup` 在 HEAD 可編譯（我無法單獨執行這些模組的測試）。
- `./gradlew :platform:crypto:compileDebugAndroidTestKotlin` → **BUILD SUCCESSFUL**。新增的 `WrappedSecretFileTest` 確實可編譯（convention plugin `quietinbox.android.library.gradle.kts:38-41` 已提供 `androidx-test-ext-junit` / `androidx-test-runner` / `kotest-assertions-core` 的 androidTest 依賴）。
- **自建 JVM harness 實際執行 `Reconciler`**：以 `core/reconcile/build/libs/reconcile.jar` + `core/model/build/libs/model.jar` + kotlin-stdlib + kotlinx-serialization-core 掛 classpath，用 Java 直接呼叫 `Reconciler.reconcile(...)`。harness 檔案在 `/private/tmp/qiharness/`，**沒有寫進 repo**。下面標示「harness 實測」的都是執行輸出。
- 雙語字串 parity：`core/designsystem` 兩個 locale 各 298 條，`name` 逐一比對順序與集合完全一致。
- 未執行：instrumented tests（`MigrationTest`、`VaultRoundTripTest`、`WrappedSecretFileTest`）、裝置安裝、任何 git 寫入。除本報告外未修改 repo 內任何檔案。

---

## Verdict

**APPROVE WITH MINOR FIXES**

- 新 **Critical 0** / 新 **Important 0** / 新 **Minor 10**。
- round-2 subagent 的 Important 1／2／3 **全部確實修好**，其中 Important 1 我用 harness 復現了「修好」與「舊行為會壞」兩側。
- round-2 的 Minor 1／3／4／5／6／8／9／10 與 agy M1／M2／M3 也都動了，但只有 8 與 10 是乾淨收尾：
  - **3（cancellation）、5（懸空 id）、9（測試數字）只修了一半**，而 `CHANGELOG.md:22` 與 `docs/SCOPE.md:56` 已用完成式宣告；
  - **1（staging 上限）、4（multiplicity）、6（`sessionId`）方向修對了，但各自帶進一個新的 Minor**（門檻無效／排序退化／清除競態）。
  這是本輪反覆出現的型態：**修正的幅度小於文件的宣稱**。
- 沒有任何一條新發現會造成使用者可見的資料重複或遺失，因此不擋 push。

**通過的條件（依序）**：

1. **確認工作區那 4 個未提交檔案要不要進 round-3 的 fix commit**。它們已含針對 Minor 1（`SCOPE.md:16` 改成 20）、Minor 9（`IngestRepository` 的 `else { storedIds[index] = null }`）與 `BackupService` cancellation 重拋的修正，另有 `docs/TEST_MATRIX.md` 的更新。若不一起提交，本報告對應的發現下一輪會被再抓一次——round 2 就是栽在「修好的東西沒進 commit」。
2. 修正 Minor 1 的兩個測試數字（`docs/SCOPE.md:16` 與 `CHANGELOG.md:17`），或明確寫出計數慣例。
3. 把 `:platform:crypto:connectedDebugAndroidTest` 加進 `ci.yml:85`（Minor 3）；否則 fsync 修正的唯一證據永遠不會被執行。
4. Minor 4（`ORDER BY id ASC`）與 Minor 5／8／9（cancellation 與懸空 id 的剩餘路徑）可在同一輪順手處理。
5. 其餘 Minor 開 issue 追蹤即可。

---

# 一、Round-2 發現的修復驗證表

| 來源 / 編號 | 內容 | 結論 | 位置與證據 |
| --- | --- | :---: | --- |
| subagent **Important 1** | `AmbiguousRepeat` 讓 checkpoint 縮短，下一則通知重複寫入已存在的訊息 | **已修，harness 實測** | `Reconciler.kt:181` `addsNothing = decisions.none { it is Decision.New \|\| it is Decision.Revision }`。詳見下方「Important 1 的實測」 |
| subagent **Important 2** | 目錄 fsync 必然失敗且被吞掉 | **已修** | `WrappedSecretFile.kt:90-102` 改用 `Os.open(dir.path, O_RDONLY, 0)` + `Os.fsync` + `Os.close`，`ErrnoException` 轉成 `IOException`；`:86-87` 在 rename 後呼叫，目錄是新建時再 fsync 一次父目錄；`:42-44` 由 `getOrCreate()` 收斂成 `KeyResult.Failed(Unavailable)` |
| subagent **Important 3** | 文件宣稱程式碼做不到的事 | **已修** | `adr/0004-identity-and-dedup.md:33-35` 補上「"Adds nothing" means no `New` and no `Revision`」並點名 `ReconcilerAmbiguousKeepTest`；`SCOPE.md:52-54` 重寫 export／restore／key 三條；`strings.xml` 兩個 locale 的 `backup_failed_io` 同步改成「備份未寫出；除非最後的複製步驟本身失敗，否則既有的目標檔案不會被更動」，與 `BackupService.kt:79-98` 的實作一致 |
| subagent Minor 1 | staging 沒有文字總量上限 | **已修，但門檻無效** | `BackupRecords.kt:121` `MAX_STAGED_TEXT_CHARS = 64M chars`；`BackupService.kt:227-228` 計數正確（只算非 Media 的 `line.length`，在 decode 之後）。→ **新 Minor 7** |
| subagent Minor 2 | 冷啟動期間 `offer()` 不過濾套件 | **刻意未修** | 但沒有寫進 `SCOPE.md:59` 的 Known defects → **新 Minor 10** |
| subagent Minor 3 | `CaptureCoordinator` 仍吞 `CancellationException` | **部分修正** | `:139`（consumer loop）、`:355`（`process`）、`IngestRepository.kt:132` 都補了重拋；但 `:424` 重拋的例外立刻被 `:407` 的外層 `runCatching` 再吞一次，`:200`、`:321`、`:352` 也還在吞 → **新 Minor 5** |
| subagent Minor 4 | guard 壓掉一筆 multiplicity | **已修，但引入排序退化** | `IngestRepository.kt:198-203` 改成 `Map<String, ArrayDeque<Long>>`，`:226` `removeFirstOrNull()` 逐筆消耗；`Daos.kt:176-178` 新增 `findIdsByFingerprint`。但新查詢是 `ORDER BY id ASC`，舊查詢是 `ORDER BY id DESC LIMIT 1` → **新 Minor 4** |
| subagent Minor 5 | 已刪訊息的 id 被寫回 checkpoint | **部分修正** | `IngestRepository.kt:212` `HashMap<Int, Long?>`、`:291` `Decision.Known` 顯式寫 null、`:315` 改用 `containsKey`。但 `Decision.Revision`（`:297-306`）與被抑制的 `AmbiguousRepeat`（`:219-222`）兩條路徑仍留下懸空 id → **新 Minor 8／9** |
| subagent Minor 6 | `onDisconnected` 沒清 `sessionId` | **已修，但清除方式有競態** | `CaptureCoordinator.kt:202` 加了 `sessionId = null`，但在非同步 `scope.launch { runCatching { ... } }` 內 → **新 Minor 6** |
| subagent Minor 7 | `closeWindow` / `closeAllWindows` 在 `pipelineMutex` 外 | **刻意未修** | 同 Minor 2，未寫入 Known defects → **新 Minor 10** |
| subagent Minor 8 | `process()` 註解與行為不符 | **已修** | `CaptureCoordinator.kt:348-349` 改成「The vault went away before the commit (an event journaled first is replayed later; one not journaled is lost)」，與 `:340-344` 的實際控制流一致 |
| subagent Minor 9 | `SCOPE.md` 測試數字差一 | **未修，且差距擴大** | `SCOPE.md:16` 仍寫「16 JVM tests in `core:reconcile`」，實測 **20** → **新 Minor 1** |
| subagent Minor 10 | property test 沒進入問題所在的空間 | **已修，且確實覆蓋缺陷** | `ReconcilerPropertyTest.kt:70-86` 新增的 generator 全部是 `sourceTimestampEpochMs = null` + `OBSERVED_ONLY` + 字母表只有 1–3 個 body，正好是 Important 1 所在的區域。harness 量化見下 |
| agy **M1** | export 失敗會截斷使用者選定的檔案 | **已修** | `BackupService.kt:79-98`：先寫 `cacheDir` 的 `backup-<uuid>.qibk`，`FileOutputStream.use` 與 `newEncryptingStream.use` 都關閉之後才 `openOutputStream(target, "wt")` 並 `copyTo`；`finally` 於每條路徑刪除暫存檔 |
| agy **M2** | `summaryOnlyCount` 恆為 0 | **以文件處理** | `Entities.kt:100` 加上 `/** Reserved: summary-only observations are not attributable to a conversation in v0.1, so this stays 0. */`。誠實，可接受 |
| agy **M3** | `ReconcilerPropertyTest` 缺 opt-in 標註 | **已修** | `ReconcilerPropertyTest.kt:24` `@OptIn(io.kotest.common.ExperimentalKotest::class)`，本次編譯無該警告 |

## Important 1 的實測（harness 輸出）

輸入：同一個 key `k1`、舊 window `A/B/C` = id 100/101/102、`closed = true`、`postedAtEpochMs = 5000`。

```
P1 step1 decisions=AmbiguousRepeat notes=[FULL_OVERLAP, WINDOW_KEPT] window=[100,101,102,] postedAt=6000 closed=false
P1 step2 decisions=[Known, Known, New] window=[101,102,null,]
```

- `[C]`（新 postTime 6000）判為 `AmbiguousRepeat`，**視窗保留為 `[A,B,C]`**，且新的 postTime 被帶進 checkpoint。
- 下一則 `[B,C,D]` 得到 `Known(101) / Known(102) / New`。round-2 復現的「B 和 C 被重複入庫」已不存在。
- `postedAtEpochMs` 在 `WINDOW_KEPT` 分支被更新為 6000（`Reconciler.kt:184`），因此重連 resync 拿到同一則 postTime 6000 的通知時 `samePost` 成立（`:143-144`），會判成 `Known(REPOST)` 而不是再開一列 ambiguous。fable I3 的修正沒有被這次改動破壞。

## 新 property test 是否真的覆蓋這個缺陷

我用**自建 PRNG**（不是 Kotest 的 seed）重跑 `ReconcilerPropertyTest.kt:70-116` 的同一個生成空間 **20,000 次迭代**，統計 replay 步驟落在缺陷區域的頻率：

```
P2 iterations=20000 replaySteps=217496 ambiguousReplays=52088
   defectHits(old code would shrink)=28650 iterationsWithHit=11734 (58.67%)
   shrinkViolationsUnderCurrentCode=0
```

- `defectHits` 是「replay 步驟產生 `AmbiguousRepeat`，且前一視窗比本次內容長」的次數——**舊程式碼在這些點上一定會把視窗縮短**，於是 `res.newWindow.items.size shouldBeGreaterThanOrEqual (prev?.items?.size ?: 0)`（`ReconcilerPropertyTest.kt:95`）會失敗。
- 單次迭代命中率 **58.67%**，所以 Kotest 那組 1,000 iterations 幾乎不可能錯過。這個 property test 是真的在測 Important 1，不是裝飾。
- 現行程式碼在 217,496 個 replay 步驟中 **0 次**縮短視窗。

`ReconcilerAmbiguousKeepTest`（`ReconcilerTest.kt:183-210`）兩個案例也直接對應 Important 1 的觸發序列與單項視窗的 postTime 更新，`shouldContain ReconcileNote.WINDOW_KEPT` 與 `listOf("Known","Known","New")` 兩條斷言正好卡在缺陷上。

## Property 的邏輯是否成立（brief 第 2.7 點）

成立，而且 KDoc 的措辭是誠實的。在「無 id、無來源時間戳、內容可重複」的空間裡「不遺失」本來就無法成立（視窗剛好滑動自己的長度時，與一次 repost 在觀測上完全不可區分），所以斷言只宣稱三件事：replay 不產生 `New`（`:94`）、replay 不縮短視窗（`:95`）、真實推進的 `New` 數不超過 `advance`（`:105`）、以及相同內容再貼一次不新增也不縮短（`:109-110`）。這正是「no duplication, not no loss」，KDoc（`:63-69`）也是這樣寫的。

---

# 二、新發現：Critical

**0 項。**

# 三、新發現：Important

**0 項。**

Brief 點名要查的四個退化風險，我逐一驗證後認為都不成立：

- **`addsNothing` 忽略 `AmbiguousRepeat` 與 `IngestRepository.commit` 的互動**：ambiguous 那一列仍會以 `DedupState.AMBIGUOUS_REPEAT` 插入（`IngestRepository.kt:257-273`），並對舊列補一條 `AMBIGUOUS_REPEAT` observation link（`:276-280`），所以沒有遺失；`WINDOW_KEPT` 時所有 `decisionIndex` 都是 null（`Reconciler.kt:184`），`:315` 因此 fallback 回 `item.messageId`，舊 id 完整帶過去，harness 實測 `[100,101,102]` 原封不動。下一則 `[B,C,D]` 對齊到保留的內容，沒有重複也沒有斷鏈。
- **`WrappedSecretFile` 部分失敗後的下一次 `getOrCreate()`**：rename 成功後才呼叫 `fsyncDirectory`，所以此時 `.tmp` 已不存在、`file` 已存在且內容有效。`getOrCreate()` 回 `Failed(Unavailable)`，但 `:35` 的下一次呼叫走 `if (file.exists()) return read()`，讀回**同一把** secret。不會產生「換了一把新 key、舊 vault 打不開」的分歧。行為正確。
- **`BackupService.export` 的 `Ok(counts)` 移出 `use` 區塊**：沒有改變 EOF 處理。舊寫法的非區域 return 也會先跑 `use` 的 finally（`close()` 會寫出最後一段認證 tag），close 失敗時例外照樣傳到 `catch`。新寫法唯一的實質差別是**認證段確定落在暫存檔之後、才開始碰使用者的文件**，這是改善不是退化。
- **`CaptureCoordinator` consumer loop**：`:136-143` 的 `catch (t: Throwable)` 先 `if (t is CancellationException) throw t` 再繼續 `while (true)`，非取消類的 throwable（含 OOM）仍會重新進入 `for (item in queue)`；取消則向外傳播讓 scope 乾淨結束。行為正確。

---

# 四、新發現：Minor（10 項）

## Minor 1 — 兩個測試數字與實測不符（docs-honesty）

`docs/SCOPE.md:16`、`CHANGELOG.md:17`

- `SCOPE.md:16`：「**16** JVM tests in `core:reconcile` including **a** 1,000-seed property test」。實測 **20**，而且現在是**兩個** property test。round-2 Minor 9 已經指出這個數字錯了（當時 17），本 commit 又加了 3 個測試而沒有更新它。
- `CHANGELOG.md:17`：「**74** JVM tests including a 1,000-seed property test」。我把 9 個模組的 JUnit XML 全部重跑後統計是 **97**（失敗 0）。

**修法**：`SCOPE.md:16` 改成「20 JVM tests … two 1,000-iteration property tests」；`CHANGELOG.md:17` 改成 97，並把「a 1,000-seed property test」改成複數。若刻意採用某種計數慣例，就把慣例寫出來（見 Minor 2）。

## Minor 2 — `docs/TEST_MATRIX.md` 的更新沒有進 commit

`docs/TEST_MATRIX.md`（工作區已改、未提交）

`6a9b0ce` 裡的測試矩陣仍是 round-1 的版本：「36 tests in `core:*`」、只有一個 property test、`ReconcilerAmbiguousKeepTest` 沒有出現在 §7.2 對照表、也沒有 `platform:crypto` 的 instrumented lane。**這正是 round 2 抓到的同一個型態**（修好的東西留在工作區沒提交），只是這次影響範圍是文件而非程式碼，所以不影響 verdict。

另外，未提交版本寫的「39 tests in `core:*`」是 `parser 10 + identity 5 + reconcile 20 + analytics 4`，**刻意排除了 `core:model` 的 5**，但同一格的執行指令又包含 `:core:model:test`。實際 `core/*` 合計 44。

**修法**：把 `docs/TEST_MATRIX.md` 一起提交；把 39 改成 44，或在該格註明「不含 `core:model`」。

## Minor 3 — CI 從不編譯也不執行 `platform:crypto` 的 androidTest

`.github/workflows/ci.yml:85`

`instrumented` job 的 script 只有 `:platform:storage:connectedDebugAndroidTest`；`assemble` job（`ci.yml:50`）只跑 `:app:assembleDebug :app:assembleRelease :app:lintDebug`，不會建 library 模組的 androidTest。也就是說 **`WrappedSecretFileTest` 在 CI 裡既不編譯也不執行**——它是 Important 2 的唯一證據，卻沒有任何自動化在守它。

同時要說清楚這個測試能證明與不能證明什麼：

- **能**：如果 `Os.fsync` 在真機的 `filesDir` 上硬失敗，`getOrCreate()` 會回 `Failed`，`shouldBeInstanceOf<KeyResult.Ok<ByteArray>>()` 立刻紅。這正好擋住 brief 擔心的「健康裝置反而建不出 key」的退化。
- **不能**：它無法證明 fsync **真的執行過**——把 `fsyncDirectory` 換回舊版那個靜默 no-op，這個測試照樣全綠。

**修法**：`ci.yml:85` 改成 `script: ./gradlew --no-daemon --console=plain :platform:storage:connectedDebugAndroidTest :platform:crypto:connectedDebugAndroidTest`。

## Minor 4 — checkpoint-loss guard 現在連到**最舊**的列，不是位置正確的最新列

`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:176-178`、`IngestRepository.kt:198-203, 226`

```kotlin
@Query("SELECT id FROM message WHERE conversationId = :conversationId AND fingerprint = :fingerprint ORDER BY id ASC")
suspend fun findIdsByFingerprint(conversationId: Long, fingerprint: String): List<Long>
```

舊的 `findIdByFingerprint`（`:173-174`）是 `ORDER BY id DESC LIMIT 1`，取**最新**的一列。新查詢改成 ASC 且從佇列前端消耗，於是：一個對話裡有 5 筆「好」、checkpoint 被清掉、通知顯示 `[好]` 時，guard 會把它連到**第一筆**「好」，而不是通知實際顯示的那一筆。批次 `[好, 好]` 遇到 3 筆既有列時，連到的是最舊的兩筆而非最新的兩筆。

不會重複也不會遺失（Minor 4 的 multiplicity 修正本身是對的），但 `incrementObservation` 加在錯的列上，`storedIds` 寫進 checkpoint 的也是舊 id，後續對齊會一路連到那些舊列。

**修法**：取最新的 k 筆再由舊到新消耗，k = 該 fingerprint 在本批次中 `New && !confirmedById` 的決策數：

```kotlin
val wanted = reconcile.decisions.filter { it is Decision.New && !it.confirmedById }
    .groupingBy { it.fingerprint }.eachCount()
val preExisting: Map<String, ArrayDeque<Long>> = if (ReconcileNote.NO_PREVIOUS_WINDOW in reconcile.notes) {
    wanted.mapValues { (fp, k) -> ArrayDeque(db.messageDao().findIdsByFingerprint(conversationId, fp).takeLast(k)) }
        .filterValues { it.isNotEmpty() }
} else emptyMap()
```

順帶：`findIdsByFingerprint` 沒有 `LIMIT`，一個有上萬筆同 fingerprint 的對話會一次撈回全部 id；加上 `takeLast(k)` 之後可再補 `LIMIT`。

## Minor 5 — `replayJournal` 重拋的 `CancellationException` 立刻被外層 `runCatching` 再吞一次

`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:407, 424`

```kotlin
private suspend fun replayJournal() = withContext(Dispatchers.Default) {
    runCatching {                       // :407  ← 會接住 :424 重拋的 CancellationException
        ...
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e   // :424
                        ingest.markJournalRetryable(...)
                    }
    }
}
```

`runCatching` 接 `Throwable`，所以 `:424` 的重拋只讓 replay 迴圈提早結束，接著就被 `:407` 轉成正常完成。實務上取消仍會在 `withContext` 的邊界重新拋出（job 已取消），所以影響有限——但 `CHANGELOG.md:22` 與 `SCOPE.md:56` 寫的是「capture no longer swallows coroutine cancellation」／「`CancellationException` is rethrown in the capture pipeline」，這個用詞比實作精確。同一個檔案裡 `:200`（`onDisconnected`）、`:321`、`:352` 三處 `runCatching` 也還在吞。

**修法**：套用同一個 commit 在 `IngestRepository.kt:132` 已經用過的寫法：

```kotlin
runCatching { ... }.onFailure { if (it is CancellationException) throw it }
```

## Minor 6 — `sessionId = null` 放在非同步 `launch` 內，會漏清也會誤清

`CaptureCoordinator.kt:199-206`

```kotlin
scope.launch {
    runCatching {
        sessionId?.let { health.endSession(it, now, "DISCONNECTED") }
        sessionId = null                       // :202
        ...
    }
}
```

兩個問題：(a) `endSession` 拋例外時 `runCatching` 接住，`sessionId` 不會被清，Minor 6 的原始症狀（暫停時對同一個已結束的 session 再 `endSession` 一次）就回來了；(b) disconnect → 立刻 reconnect 時，`onConnected` 的 `:183` 可能先把新 session id 寫進去，隨後才輪到這個 coroutine 把它清成 null，新 session 之後就再也不會被結束。

**修法**：像上面兩行的 `activeGeneration = null`（`:196`）一樣**同步**清掉：

```kotlin
val sid = sessionId
sessionId = null
scope.launch { runCatching { sid?.let { health.endSession(it, now, "DISCONNECTED") } ; ... } }
```

## Minor 7 — staging 的兩個上限都高於一般 Android heap，OOM 仍會先發生

`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupRecords.kt:121`、`BackupService.kt:227-228, 243-245`

計數本身是對的（只累加非 `Media` 記錄的 `line.length`，在 `decodeFromString` 之後、加入 list 之前檢查），但門檻的量級沒有解決 Minor 1 想解決的問題：

| 上限 | 字面值 | 實際保留在 heap 的量級 |
| --- | --- | --- |
| `MAX_STAGED_TEXT_CHARS` | 64 M chars | 約 128 MB（UTF-16）＋ 解碼後 record 物件的額外開銷 |
| `MAX_STAGED_MEDIA_BYTES` | 256 MB（解碼後位元組） | `BackupRecord.Media` 保留的是 base64 **字串**，約 341 M chars ≈ **682 MB** |
| `MAX_RECORDS` | 2,000,000 筆 | 光物件標頭與欄位參考就遠超一般 heap |

一般 Android app heap 是 192–512 MB，所以刻意構造的備份檔仍會在任何一個上限觸發**之前**先 OOM。

**修法**：把 `MAX_STAGED_TEXT_CHARS` 降到 8–16 M chars；`BackupRecord.Media` 在 staging 階段就 `Base64.decode` 成 `ByteArray`（或直接落到暫存檔），讓 `MAX_STAGED_MEDIA_BYTES` 的字面值與實際佔用一致。

## Minor 8 — 被抑制的 `AmbiguousRepeat` 仍把未驗證的舊 id 寫回 checkpoint

`IngestRepository.kt:210-212, 219-222`

`:210-212` 的新註解宣稱「an explicit null means "observed, but no stored row" … so the checkpoint never keeps a dangling id」。但抑制路徑：

```kotlin
if (db.suppressionDao().isSuppressed(suppressionKey, decision.fingerprint, now) > 0) {
    suppressed++
    continue                     // :221 ← 沒有寫 storedIds[index]
}
```

`continue` 之後 `storedIds` 沒有這個 index，`:315` 的 `containsKey` 為 false，於是 fallback 回 `item.messageId`——對 `AmbiguousRepeat` 而言那是 `Reconciler.kt:190` 寫進去的 `d.existingMessageId`，**沒有經過 `db.messageDao().get(it) != null` 驗證**。而抑制權杖正是使用者刪除該 fingerprint 時建立的，所以那個 id 高機率就是已刪除的列。

實際傷害有限（下一則的 `Decision.Known` 會在 `:290` 驗證失敗並寫成 null，自我修復），但註解的宣稱是過頭的。

**修法**：`continue` 之前補一行

```kotlin
if (decision is Decision.AmbiguousRepeat) storedIds[index] = decision.existingMessageId?.takeIf { db.messageDao().get(it) != null }
else storedIds[index] = null
```

## Minor 9 — `Decision.Revision` 在目標已被刪除時也留下懸空 id

`IngestRepository.kt:297-306`

```kotlin
is Decision.Revision -> {
    val id = decision.existingMessageId
    val old = db.messageDao().get(id)
    if (old != null) {
        ...
        storedIds[index] = id
    }
    // 沒有 else 分支
}
```

`old == null` 時 `storedIds` 沒有條目，`:315` fallback 回 `item.messageId`（`Reconciler.kt:188` 寫入的同一個已失效 id）。可達性是真的但很罕見：`lookupById`（`IngestRepository.kt:121`）在 commit 交易**之外**執行，使用者要剛好在查詢與 commit 之間刪掉那則訊息。

**修法**：補上 `else { storedIds[index] = null }`。

> **注意**：交報告當下工作區已經有人加上了這個 `else` 分支，但它**不在 `6a9b0ce` 裡**。請確認它有被提交。

## Minor 10 — 兩個「刻意不修」的 round-2 Minor 沒有進入 Known defects

`docs/SCOPE.md:56, 59`

`:56` 那段列了 round 2 的 3 個 Important 與 5 個「Also fixed」的 Minor，但**沒有說哪些 Minor 沒修**。brief 說 subagent Minor 2（冷啟動 `offer()` 不過濾套件）與 Minor 7（`closeWindow` / `closeAllWindows` 在 `pipelineMutex` 之外）是「documented as known issues」——我 grep 過整個 `docs/`（排除 `docs/reviews/`），這兩件事只存在於歸檔的 round-2 報告裡，**`SCOPE.md:59` 的「Known defects and rough edges」段（`:59-63`）完全沒有提到**。讀者會合理推論 round 2 的東西都處理完了。

**修法**：在 `:59` 那段加兩條：

```markdown
- Before the source list is first loaded, `offer()` queues notifications from any package and
  `process()` drops them; the bitmap budget can be spent on non-source notifications meanwhile.
- `closeWindow` / `closeAllWindows` run outside `pipelineMutex`, so a close can race a commit that
  rewrites the checkpoint with `closed = false`.
```

---

# 五、其他觀察（已驗證，讀者可能會懷疑的部分）

- **工作區在審查期間被其他 agent 修改**。交報告當下 `git status` 顯示 `docs/SCOPE.md`、`docs/TEST_MATRIX.md`、`platform/backup/.../BackupService.kt`、`platform/storage/.../IngestRepository.kt` 四個未提交檔案。已確認：`SCOPE.md:16` 已被改成 20 / two property tests（Minor 1 的一半）、`IngestRepository.kt` 已加上 Minor 9 的 `else` 分支、`BackupService.kt` 已在 `export` / `import` / `apply` 的 `catch (e: Exception)` 前補上 `if (e is CancellationException) throw e`。**本報告的所有 file:line 都是對 `6a9b0ce` 的**，因此這些仍列為發現；請以「有沒有提交」為準來銷案。
- **`BackupService` 在 `6a9b0ce` 同樣吞 `CancellationException`**（`:73-76` 的 `holder.db()`、`:94-96` 的 export catch、`:184-186` 的 import catch、`:371-374` 的 apply catch）。這與 Minor 5 是同一個型態，只是不在 `CHANGELOG.md:22` 那句「capture」的宣稱範圍內，所以我沒有另立一條。
- **`WrappedSecretFile` 的 `mkdirs` 多層 fsync 缺口**：`:76` 的 `createdDir` 只讓 `:87` 對 `dir.parentFile` 補一次 fsync，但 `mkdirs()` 可能一次建立多層。生產環境不會踩到（`KeyMaterial.kt:25` 的 `files/keys` 永遠只差一層，`filesDir` 由 `Context` 保證存在），只有 `WrappedSecretFileTest` 那個 `deeper` 案例會建兩層。
- **`.tmp` 在資料寫入失敗時會留下**：`:78-81` 的 `FileOutputStream(tmp).use` 只 close 不刪除，`out.fd.sync()` 因磁碟滿而拋例外時 `db.key.tmp` 留在 `files/keys/`。內容是 Keystore 包裹過的，下一次 `getOrCreate()` 會直接覆寫，所以只是清潔問題。
- **ambiguous 那一列被排除在對齊視窗之外，是作者明示的取捨**：`WINDOW_KEPT` 之後 checkpoint 指向舊的 C（102），新插入的 ambiguous 列（103）不會再被後續觀測連上。`Reconciler.kt:100-102` 的 KDoc 已經說明「re-observation of an existing position, not a new position」，資料沒有遺失（103 仍在收件匣且標記為 `AMBIGUOUS_REPEAT`，並有一條指向 102 的 link），只是後續 `observationCount` 會加在 102 上。
- **export 需要兩倍磁碟空間**：完整密文先落在 `cacheDir`，再複製到 SAF 目標。目標在 SD 卡、內部儲存吃緊時可能失敗，但失敗路徑是安全的（`FileOutputStream(staging)` 拋 `IOException` → `Failed(IO)`，使用者的文件連開都沒開）。暫存檔名是 `backup-<32 位 hex>.qibk`，同時多次 export 不會撞名；`finally`（`:96-99`）在包含 `openOutputStream` 回傳 null 的提前 return 在內的每條路徑上刪除它。
- **`SCOPE.md:56` 的段落插在項目符號清單中間**，把 `:57` 那條 App lock 的 bullet 切成一個新的清單。純排版，但渲染出來會斷開。
- **`WrappedSecretFileTest` 的斷言與 durability 宣稱一致**：`WrappedSecretFileTest.kt:20-23` 的 KDoc 老實寫了「only runs on a device: java.io cannot open a directory, so this cannot be proven on the JVM」。誠實。
- **`ReconcilerAmbiguousKeepTest` 用的 `window()` helper**（`ReconcilerTest.kt:22-26`）給 id 100/101/102 且不帶 `postedAtEpochMs`，測試自己 `.copy(postedAtEpochMs = 5_000)` 補上——測試意圖與 harness 復現的情境完全一致。
- **雙語字串 parity 未破**：兩個 locale 各 298 條，`name` 的順序與集合完全一致，本 commit 的 `backup_failed_io` 兩邊同步修改。
- **`docs/reviews/2026-09-06-round2/claude-subagent.md` 與 `docs/reviews/2026-09-06-round2/claude-subagent.md` 逐位元組相同**，歸檔沒有被裁剪或美化。`docs/reviews/README.md:10` 也誠實記錄了 round 2 的兩個 verdict 與「Codex and Kimi still blocked」。

---

# 六、建議的處理順序

1. **提交工作區那 4 個檔案**（Minor 2／9 與 `SCOPE.md`、`BackupService.kt` 的 cancellation 修正）。round 2 就是栽在這一點。
2. 修 Minor 1 的兩個數字（`SCOPE.md:16` → 20、`CHANGELOG.md:17` → 97），並把 `TEST_MATRIX.md` 的 39 與 44 對齊或註明慣例。
3. `ci.yml:85` 加上 `:platform:crypto:connectedDebugAndroidTest`（Minor 3）——否則 Important 2 的修正在 CI 裡沒有任何守門。
4. Minor 4（`takeLast(k)`）、Minor 5（外層 `runCatching` 加 `onFailure`）、Minor 6（同步清 `sessionId`）、Minor 8（抑制路徑寫 `storedIds`）可在同一輪順手處理。
5. Minor 7（staging 上限）與 Minor 10（Known defects 兩條）可開 issue 追蹤。
6. 發版前仍需在真機跑一次 `MigrationTest`、`VaultRoundTripTest` 與新的 `WrappedSecretFileTest`：schema v1→v2 與目錄 fsync 這兩條路徑到目前為止**零真機實測**。
