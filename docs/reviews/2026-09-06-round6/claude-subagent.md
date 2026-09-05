# Round 6 確認審查（Claude subagent，唯讀）— QuietInbox

**審查對象**：`<repo>`，branch `main`，HEAD `a626b32`，diff `fa49902..a626b32`
**審查時間**：2026-09-06 約 04:50–05:15
**方法**：程式碼閱讀 + `./gradlew test --console=plain`（僅 JVM）+ 於 `/private/tmp/qi-r6/` 編譯執行的 coroutine 行為驗證程式。未動任何裝置／模擬器，未執行 instrumented 測試，未做任何會改變狀態的 git 操作。

---

## Verdict

# REQUEST CHANGES

理由：commit `a626b32` 為了加上 loading 狀態而重寫了 `AnalyticsViewModel.state` 的 Flow 鏈，過程中**把 `.flowOn(Dispatchers.Default)` 一併刪掉了**。整條分析管線（最多 50,000 則訊息的 n-gram 片語掃描、熱點圖、五張排行榜）因此改在 **main thread** 上執行。這是本次 commit 引入的新回歸，嚴重度 Critical，且與該屬性正上方仍然保留的 KDoc「the whole pipeline runs off the main thread」直接矛盾。

Round-5 三位審查者共同提出的那一項 Important（`capped` 提示從未被畫出來）**確實已修好**；`committed` guard、`isSelf` 空字串防護、`statsBetween` 註解、重新縮排、versionCode 3 也都**逐項驗證通過**。問題全部集中在 analytics 的 Flow 改寫上。

---

## ⚠️ 重要前提：工作區在審查期間被修改

審查進行到一半時，`AnalyticsViewModel.kt` 被本 session 的其他 agent 修改了（來源檔 mtime `05:04:33`）。這些修改**尚未 commit**，不屬於我被指派審查的 `a626b32`：

```
$ git status --short --branch
## main...origin/main [ahead 4]
 M .github/workflows/release.yml
 M docs/RELEASE.md
 M docs/reviews/README.md
 M docs/zh-Hant/RELEASE.md
 M feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt
?? docs/reviews/2026-09-06-round6/
```

**工作區在審查期間持續變動。** 上面的 `git status` 是我做完程式碼確認之後截下的；再過幾分鐘後 `app/build.gradle.kts` 也被改動，`fastlane/metadata/android/{en-US,zh-TW}/changelogs/3.txt` 被 `4.txt` 取代（看起來是有人在準備 versionCode 4）。驗證表第 14 項是針對 commit `a626b32` 驗證的（該 commit 內 versionCode = 3、changelog = `3.txt`），不受後續工作區變動影響。我自己沒有修改版本庫內的任何檔案，唯一的寫入是本報告，位於已被 gitignore 的 `.omc/research/` 之下。

工作區版本的快照存於 `/private/tmp/qi-r6/AnalyticsViewModel.worktree.kt`（md5 `03be9e29ec0347ec62ca47cd52d40ac7`）。它正好處理了本報告的 A、B 兩項，以及 H 的一部分：

- 補回 `.flowOn(Dispatchers.Default)`（放在 `.onEach` 之後、`.stateIn` 之前）→ **A 已解**
- `if (s != last.selection || last.report == null) emit(last.copy(loading = true, ...))` → **B 已解**
- 三個 `runCatching` 加上 `.onFailure { if (it is CancellationException) throw it }` → **H 部分解**

我另外確認工作區版本**可以編譯**（`feature/analytics` 的 class 檔 mtime `05:04:35`，比來源檔晚 2 秒；`./gradlew :feature:analytics:compileDebugKotlin` 回報 UP-TO-DATE），並用 harness 驗證了修正後的行為（見下方證據 D）。

**結論**：上面的 REQUEST CHANGES 是針對 **commit `a626b32`** 下的。若把工作區這份未提交的修正一併提交，A 與 B 即消除，剩下的只有 Minor，屆時可降為 **APPROVE WITH MINOR FIXES**。但 C、D、E、F、G、I 這幾項工作區版本並未處理，H 也只做了 3/5。

---

## 驗證表（brief 逐項）

| # | brief 要求確認的項目 | 結果 | 證據 |
|---|---|---|---|
| 1 | `capped` 提示已被畫出（`AnalyticsScreen.RangeLine`） | ✅ 已渲染 | `AnalyticsScreen.kt:645-651`；字串雙語齊備：`values/strings_analytics.xml:76`、`values-b+zh+Hant/strings_analytics.xml:75`（都用 `%1$d`，兩處代入同一個值，語意正確） |
| 2 | `AnalyticsRepository.MESSAGE_CAP` 可從 feature 模組取用 | ✅ | `AnalyticsRepository.kt:48` 為 `companion object` 的 `const val`；`feature:analytics` 已編譯通過 |
| 3 | `capped` 旗標的計算 | ✅ | `AnalyticsViewModel.kt:156` `messages.size >= MESSAGE_CAP`；恰好 50,000 筆會誤報為 capped，但 `AnalyticsRepository.kt:17-19` 的 KDoc 已明文把這當成刻意的保守作法，不列為問題 |
| 4 | 以 merge(first, sample(400)) 取代 debounce | ⚠️ 行為對，實作有代價 | `AnalyticsViewModel.kt:195-199`；第一筆立即到達已驗證，但 `observeCounts()` 被訂閱**兩次** → 見 Minor C |
| 5 | **第一個狀態是否立刻到達** | ✅ | `merge(counts.take(1), ...)` 讓首筆不經 sample；harness PART1 觀察到首筆立即發出 |
| 6 | **切換週期是否先顯示 loading 再出結果** | ⚠️ 會，但**每次 vault 變動也會** | 見 Important B；harness PART2 實測 |
| 7 | **前一次計算是否被取消** | ⚠️ 僅盡力而為 | 見 Minor H。`transformLatest` 會取消協程，但 commit 版的 `runCatching` 把 `CancellationException` 吞掉；純 CPU 階段完全沒有 suspension point，取消不會真的中止運算 |
| 8 | 分析管線是否仍在背景執行緒 | ❌ **否（Critical A）** | `a626b32` 的鏈為 `combine → transformLatest → onEach → stateIn(viewModelScope, ...)`，全鏈無 `flowOn`；`viewModelScope` 是 `Dispatchers.Main.immediate` |
| 9 | `BackupService.apply` 的 `committed` guard | ✅ 正確 | `BackupService.kt:216 / 340 / 342 / 347`。commit 前 `committed=false` → 全刪；commit 後只刪 `usedFiles` 外的孤兒 blob。`usedFiles` 只在真的 insert 訊息時累加（`:305`） |
| 10 | 「commit 成功卻被回報成 Failed」的風險 | ✅ 不存在 | `MediaDirectory.delete` 以 `runCatching` 包住 `File.delete()`（`RetentionWorker.kt:109-111`），不會拋出，因此 commit 後的清理迴圈不可能把成功的還原打成 `Failed(IO)` |
| 11 | `isSelf` 忽略空白名字 | ✅ 正確 | `SnapshotFactory.kt:168` `!selfName.isNullOrBlank() && person.name?.toString() == selfName`；分支順序讓 key/uri 優先，失敗方向偏向「不是自己」，與 `:62-66` 新註解一致 |
| 12 | `statsBetween` 排序取捨的註解正確性 | ✅ 事實正確／⚠️ 位置與用詞 | `Daos.kt:228-232`。`MessageEntity` 的索引為 `["conversationId","sortKey"]`、`["conversationId","sourceMessageId"]`、`fingerprint`、`expiresAtEpochMs`、`observedAtEpochMs`、`mediaState`（`Entities.kt:111-118`）→ 的確**沒有以 `sortKey` 起頭的索引**。但註解寫在 `@Query` 之後、宣告之前，且內容已過時 → 見 Minor F |
| 13 | 準備迴圈重新縮排 | ✅ 純排版 | `BackupService.kt:225-238`，`git diff -w` 對這段無語意差異；迴圈本來就在 `try` 內 |
| 14 | versionCode 3 + changelog 改名 | ✅ | `app/build.gradle.kts:50` = 3；`fastlane/metadata/android/{en-US,zh-TW}/changelogs/3.txt` 已改名，舊的 `2.txt` 不存在；長度 141 / 117 bytes，遠低於 500 上限 |
| 15 | CHANGELOG 是否對得上 | ⚠️ 只改了 Round 4 那行，**沒有 Round 5 條目** | `CHANGELOG.md:16`；見 Minor E |
| 16 | SCOPE 是否對得上 | ⚠️ 略微高估 | `docs/SCOPE.md:21` 寫「the page says when it capped」，實際只有 Overview 一個 tab 會說 → 見 Minor D。（`docs/zh-Hant/SCOPE.md` 不存在，是既有的翻譯缺口，非本次引入） |
| 17 | TEST_MATRIX 是否對得上 | ✅ | `docs/TEST_MATRIX.md:16` 與 `docs/zh-Hant/TEST_MATRIX.md:16` 新增的「刪除的會話在重播後不會復活」對應到真實存在的 `VaultRoundTripTest.kt:160 deletedConversationDoesNotResurrectOnReplay` |
| 18 | JVM 測試 | ✅ 全綠 | `./gradlew test` BUILD SUCCESSFUL；22 份 test-results XML 合計 **157 tests / 0 failures / 0 errors / 0 skipped**。Gradle 回報多數模組 UP-TO-DATE，結果反映目前這棵樹的輸入 |

---

## 新發現

### Critical

#### A. `flowOn(Dispatchers.Default)` 被刪除，整條分析管線改跑在 main thread

`feature/analytics/.../AnalyticsViewModel.kt`（`a626b32` 版本，第 92-99 行）：

```kotlin
val state: StateFlow<AnalyticsUiState> = combine(selection, vaultChanges(inbox)) { s, _ -> s }
    .transformLatest { s ->
        emit(last.copy(loading = true, selection = s))
        emit(compute(s))
    }
    .onEach { last = it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())
```

舊版最後一步是 `.map { compute(s) }.flowOn(Dispatchers.Default)`。新版把 `.map` 換成 `transformLatest` 時，`.flowOn(Dispatchers.Default)` 沒有跟著搬過來。`grep` 確認整個檔案裡 `flowOn` 只剩下第 38 行的 import（未使用）。

`stateIn(viewModelScope, ...)` 的收集協程跑在 `Dispatchers.Main.immediate`，而 Flow 運算子在沒有 `flowOn` 時繼承下游的 context，所以 `compute()` 整段都在 main thread 上跑：

- `AnalyticsRepository.messagesBetween`（`AnalyticsRepository.kt:20-31`）：Room 的 suspend query 本身會切到 query executor，但回來之後的 `.asReversed().map { ObservedMessage(...) }` 要在呼叫端的 dispatcher 上配置最多 50,000 個物件 → main thread。
- `ActivityAnalytics.catchphrases`（`ActivityAnalytics.kt:293-317`）：對**每一則**訊息 body 跑 `PhraseScanner.phrases()` 做 n-gram 切分並累計 HashMap。
- 再加上 `compute`、`rankings`、`bestTime`、`chattiness`、`quietRate`、`heatmap`、`emojiRanking`，以及 `labels()` 每個會話一次 DAO 查詢。

而且第 83-85 行的 KDoc 還原封不動地寫著「catchphrase scanning over "All" is real work, so **the whole pipeline runs off the main thread**」——程式碼已經與自己的文件相反。

在大型 vault 上，這對「全部」週期幾乎必然造成 ANR；即使是小 vault，切換週期與每次 vault 變動也會掉幀。

**修法**（工作區已採用第一種）：

```kotlin
    .onEach { last = it }
    .flowOn(Dispatchers.Default)          // ← 補回來
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())
```

或在 `transformLatest` 內 `emit(withContext(Dispatchers.Default) { compute(s) })`。前者較好，順便把 `messagesBetween` 的物件映射也移出 main。

---

### Important

#### B. 每一次 vault 變動都會把整個分析畫面換成全螢幕 loading

`combine(selection, vaultChanges(inbox))` 在**任一**上游發射時都會發射，所以 `transformLatest` 的 `emit(last.copy(loading = true, selection = s))` 不只在切換週期時跑，**每一次被 sample 到的 vault 變動（最快每 400 ms 一次）也會跑**。

而畫面端（`AnalyticsScreen.kt:140-142`）是硬切換，不是疊加：

```kotlin
if (state.loading || report == null) {
    LoadingScreen()
    return@Column
}
```

`LoadingScreen` 是 `Box(fillMaxSize)` 置中的轉圈（`States.kt:31-35`），所以整個 `LazyColumn` 會被移除再重建 —— 使用者的**捲動位置每次都會被重設**。在通知進來的期間，分析頁會反覆在轉圈與內容之間閃爍。

第二個觸發點：`WhileSubscribed(5_000)`。使用者離開分析頁超過 5 秒再回來，上游重新啟動，第一筆就是 `last.copy(loading = true)` → 又是一次全螢幕轉圈。改動前的版本會讓舊報表留在畫面上，等新結果算好再換掉。

harness 實測（見證據 B），在**週期沒有改變**的情況下連續兩次 vault 變動：

```
loading=true sel=0
loading=false sel=0
loading=true sel=0     ← vault 變動，週期沒變，卻還是進了 loading
loading=false sel=0
loading=true sel=0     ← 同上
loading=false sel=0
```

**修法**（工作區已採用第一種）：

```kotlin
if (s != last.selection || last.report == null) emit(last.copy(loading = true, selection = s))
```

或者改畫面：`loading && report != null` 時在內容上方疊一條細的進度指示，只有 `report == null` 才走 `LoadingScreen()`。後者體驗更好，因為切換週期時也不必把已有內容清掉。

---

### Minor

#### C. `observeCounts()` 被訂閱兩次，Room observer 加倍

```kotlin
private fun vaultChanges(inbox: InboxRepository): Flow<Any?> {
    val counts = inbox.observeCounts().catch { }.distinctUntilChanged()
    return merge(counts.take(1), counts.drop(1).sample(400))
}
```
（`AnalyticsViewModel.kt:195-199`）

`counts` 是冷流（`InboxRepository.kt:43-50` → `DatabaseHolder.flowWithDb` 的 `flatMapLatest`），`merge` 會**各自完整收集一次**。harness 實測訂閱數為 **2**。`observeCounts()` 內部 `combine` 了 4 個 Room observable query，所以實際跑的是 8 個 COUNT 查詢與兩組 InvalidationTracker observer，而不是 4 個。

功能上沒錯（兩次收集幾乎同時開始，`drop(1)` 丟掉的是自己那條的首筆），但這是白花的成本，而且如果哪天 `observeCounts()` 改成熱流（`SharedFlow`/`StateFlow`），`take(1)`／`drop(1)` 的語意就會整個變掉。

**修法**：先收斂成一條再切分，例如

```kotlin
val counts = inbox.observeCounts().catch { }.distinctUntilChanged()
    .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
return merge(counts.take(1), counts.drop(1).sample(400))
```

順帶一提（既有問題，非本次引入）：`catch { }` 會讓 counts 流在出錯時**靜默結束**。若它在發出任何一筆之前就結束，`combine` 永遠不會發射，分析頁會卡在初始的 `loading = true` 不動。建議 `catch { emit(...) }` 或改用 `onEach`/`retry`。

#### D. capped 提示只出現在 Overview 一個 tab

`RangeLine` 只被 `overviewTab` 使用（`AnalyticsScreen.kt:254`）。Rankings、Best Time、Chattiness、Quiet 這四個 tab 用的是**同一份被截斷的 `messages`**，卻完全沒有任何截斷提示。

`docs/SCOPE.md:21` 寫「the page says when it capped」，對五分之四的 tab 而言是高估的。

**修法**：把 capped 提示提升到 tab 列下方的共用區塊（就放在 `AnalyticsScreen.kt:134-139` 那段 subtitle 附近），五個 tab 都看得到；或至少把 SCOPE 的措辭改成「總覽頁會說明」。

#### E. CHANGELOG 沒有 Round 5 條目

本次 commit 只修改了 Round 4 那一行的 debounce 用詞（`CHANGELOG.md:16`），沒有新增 Round 5 的條目。`grep -i "round 5\|committed\|blank"` 在 CHANGELOG 中零命中。前面每一輪（Round 3、Round 4、Whole-repository）都有自己的條目，這輪的三項實質修正——`BackupService` commit 後的 blob 安全網、`isSelf` 空白名字防護、capped 提示終於被畫出來——都沒有被記錄。

**修法**：在 `### Changed` 下補一條 Round 5 條目，並在裡面誠實寫出 loading 狀態的行為（切換週期才顯示）與 capped 提示的實際涵蓋範圍。

#### F. `Daos.kt` 的 KDoc 位置錯誤且內容過時

```kotlin
@Query(""" ... """)
/**
 * Newest [limit] rows of the period. ...（the caller debounces recomputation）...
 */
suspend fun statsBetween(...)
```
（`Daos.kt:219-233`）

兩個問題：

1. **位置**：KDoc 夾在註解與宣告之間。Kotlin 編譯得過（它只是一段註解），但 KDoc 必須在**整個宣告之前**（含註解）才會被 IDE 與 Dokka 當成該函式的文件。目前這段等於是一段看不到的註解。
2. **內容**：「the caller debounces recomputation」已經不成立——呼叫端現在是 sample，而且（在 `a626b32` 上）跑在 main thread。

事實部分是對的：確實沒有以 `sortKey` 起頭的索引（`Entities.kt:111-118`）。

**修法**：把 KDoc 移到 `@Query` 之上，並把「debounces」改成「samples vault changes (400 ms) and recomputes off the main thread」。

#### G. 未使用的 import 與 import 排序

`AnalyticsViewModel.kt` 在 `a626b32` 上留下三個未使用的 import：`Dispatchers`（:22）、`flowOn`（:38）、`kotlinx.coroutines.flow.map`（:39，檔案裡的 `.map` 全是 stdlib 的集合 `map`）。新增的六個 flow import（:29-35）插在 `distinctUntilChanged` 之前，沒有照字母序。`AnalyticsScreen.kt:3` 更把 `dev.quietinbox.platform.storage.repo.AnalyticsRepository` 放在所有 `androidx.*` 之前，明顯脫離排序。

本專案沒有 ktlint / detekt / spotless（`.github/workflows/` 只有 `ci.yml`、`release.yml`），所以**不會讓建置失敗**，只是編譯器警告。工作區的修正讓 `Dispatchers` 與 `flowOn` 重新被使用，`map` 仍然是多餘的。

#### H. 「前一次計算被取消」只是盡力而為

brief 明確問到這一點，誠實的答案是：**部分**。

`transformLatest` 確實會取消前一個區塊的協程，但：

1. `a626b32` 版的 `compute()` 把每個 suspend DB 呼叫都包在 `runCatching { }.getOrDefault(...)` 裡。`CancellationException` 屬於 `Throwable`，會被 `runCatching` 吞掉 → `messages` 變成空 list，然後整個 `compute()` 會**用空資料一路跑到底**，直到最後 `emit` 才在 `ensureActive` 上拋出。這是本次改動新暴露出來的路徑：舊的 `.map` 從來不取消 `compute`。
2. 工作區版本在其中 **3 個**站點加了 `.onFailure { if (it is CancellationException) throw it }`（`:117`、`:119`、`:123`），但 **`analytics.labels(ids)`（:153）與 `analytics.earliestTimestamp()`（:179）仍然照吞不誤**。`labels()` 是最貴的一個（每個不重複的會話 id 一次 DAO 查詢），而且是最後一個 DB 步驟。
3. 中間的純 CPU 階段（`ActivityAnalytics.*`、`heatmap`、`catchphrases`）**完全沒有 suspension point**，所以落在那裡的取消一點作用也沒有，50,000 筆的運算還是會整個跑完。

都不會產生錯誤的資料（`transformLatest` 保證舊區塊的 `emit` 不會落地），但「前一次計算被取消」在文件上不該講得太滿。

**修法**：把剩下兩個 `runCatching` 補上同樣的 rethrow；更乾淨的作法是抽一個 helper：

```kotlin
private inline fun <T> guarded(block: () -> T, fallback: T): T =
    try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { fallback }
```

並在 `compute()` 的長迴圈之間插入 `currentCoroutineContext().ensureActive()`。

#### I. `isSelf` 空白名字分支沒有回歸測試

`platform/capture/src/test/` 下只有 `CaptureCoordinatorTest.kt`，`SnapshotFactory` 完全沒有單元測試。Round 4 的 key/uri 改動與 Round 5 的空白名字改動都是純靠閱讀確認的。這段邏輯（`SnapshotFactory.kt:163-169`）是四路分支，而且失敗方向直接影響「別人的訊息會不會被標成我自己的」這種隱私語意，值得幾個純 JVM 測試把四條分支釘住。

#### J.（提示，非缺陷）`last` 是無同步保護的可變欄位

`private var last`（`:89`）在 `transformLatest` 中被讀、在 `onEach` 中被寫。在 `a626b32` 上兩者都在 main thread，安全；在工作區版本上兩者都在 `flowOn(Dispatchers.Default)` 的同一條收集鏈裡循序執行，協程的 happens-before 也涵蓋得到，同樣安全。只是把 ViewModel 的可變欄位當成 Flow 的狀態承載本身較脆弱——之後只要有人把 `flowOn` 的位置挪一下（例如挪到 `onEach` 之前）就會出現跨執行緒的讀寫。建議改用 `scan()` 或在 `flow { }` builder 內用區域變數持有。

---

## 證據

### A. JVM 測試

```
$ ./gradlew test --console=plain
BUILD SUCCESSFUL in 3s
361 actionable tasks: 16 executed, 345 up-to-date
```
彙總 22 份 `TEST-*.xml`：**tests=157 skipped=0 failures=0 errors=0**。Gradle 回報多數模組 UP-TO-DATE，結果反映目前這棵樹的輸入。

### B. Flow 行為驗證（`a626b32` 版本）

`/private/tmp/qi-r6/Harness.kt`，以 Gradle cache 內的 `kotlin-compiler-embeddable 2.1.0` + `kotlinx-coroutines-core-jvm 1.10.2` 編譯執行：

```
PART1 subscriptions_to_observeCounts=2 (1 = single collection, 2 = duplicated DB observers)
PART2 sequence:
   loading=true sel=0
   loading=false sel=0
   loading=true sel=0
   loading=false sel=0
   loading=true sel=0
   loading=false sel=0
PART3 compute ran on: fake-main (scope dispatcher = fake-main)
```

- PART1 → Minor C：`merge(c.take(1), c.drop(1).sample(400))` 對來源冷流訂閱了 2 次。
- PART2 → Important B：`sel` 從頭到尾都是 0（週期沒換），但兩次 vault 變動各自產生了一輪 `loading=true`。
- PART3 → Critical A：沒有 `flowOn` 時，`transformLatest` 的區塊在 `stateIn` scope 的 dispatcher 上執行（此處以單執行緒的 `fake-main` 模擬 `Dispatchers.Main.immediate`）。

### C. `git status` 快照

見上方「重要前提」一節。工作區檔案快照：`/private/tmp/qi-r6/AnalyticsViewModel.worktree.kt`（md5 `03be9e29ec0347ec62ca47cd52d40ac7`）。

### D. Flow 行為驗證（工作區未提交版本）

`/private/tmp/qi-r6/Harness2.kt`，套用工作區的 loading guard 與 `flowOn` 位置：

```
sequence:
   loading=true sel=0
   loading=false sel=0
   loading=true sel=1
   loading=false sel=1
compute threads: [DefaultDispatcher-worker-1, DefaultDispatcher-worker-3]  (collector scope = fake-main)
```

兩次「週期不變的 vault 變動」不再產生 loading；真正的週期切換（sel 0→1）仍然有 loading；運算跑在 `DefaultDispatcher-worker-*` 而非收集端的 dispatcher。**A 與 B 的修法確認有效。**

---

## 修正優先序

1. **（Critical）** 補回 `.flowOn(Dispatchers.Default)`——工作區已做，需要 commit。
2. **（Important）** loading 只在真正切換週期時發射——工作區已做，需要 commit。同時建議把畫面端改成疊加式指示，這樣切換週期也不必清掉既有內容與捲動位置。
3. **（Minor）** 補完 H 剩下的兩個 `runCatching`（`labels`、`earliestTimestamp`）。
4. **（Minor）** `shareIn` 收斂 `observeCounts()` 的雙重訂閱（C）。
5. **（Minor）** capped 提示提升到五個 tab 共用，或修正 SCOPE 措辭（D）。
6. **（Minor）** 補 CHANGELOG 的 Round 5 條目（E）。
7. **（Minor）** `Daos.kt` KDoc 移到 `@Query` 之上並更新用詞（F）；清掉未使用 import（G）。
8. **（Minor）** 為 `SnapshotFactory.isSelf` 的四條分支補 JVM 測試（I）。

前兩項提交之後，本輪即可視為 **APPROVE WITH MINOR FIXES**。

---

## 附記：本次審查未涵蓋

- 任何 instrumented / 裝置測試（brief 明令禁止）。`VaultRoundTripTest`、`MigrationTest`、`DemoDataTest` 只確認了「存在且名稱與 TEST_MATRIX 相符」，沒有實際執行。
- `.github/workflows/release.yml`、`docs/RELEASE.md`、`docs/reviews/README.md`、`docs/zh-Hant/RELEASE.md` 這四個工作區已修改的檔案不在 `fa49902..a626b32` 內，未納入審查。
- `docs/reviews/2026-09-06-round5/` 下新增的三份 round-5 報告只確認了存在，未逐字比對。
